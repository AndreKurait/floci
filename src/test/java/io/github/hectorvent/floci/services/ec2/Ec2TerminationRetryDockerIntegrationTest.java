package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(Ec2TerminationRetryDockerIntegrationTest.ContainerProfile.class)
class Ec2TerminationRetryDockerIntegrationTest {

    public static class ContainerProfile implements QuarkusTestProfile {
        private static final String NAMESPACE = "ec2-termination-retry-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.ec2.mock", "false",
                    "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.network.security-group-enforcement.enabled", "false",
                    "floci.docker.resource-namespace", NAMESPACE);
        }
    }

    @Inject
    Ec2Service service;

    @InjectSpy
    DockerClient docker;

    @InjectMock
    AmiImageResolver images;

    @TestHTTPResource
    URI endpoint;

    @BeforeEach
    void requireDocker() throws InterruptedException {
        assumeTrue(DockerAvailability.isAvailable(), "Docker must be available for EC2 teardown");
        when(images.resolveImage("ami-termination-retry")).thenReturn(ResolvedAmiImage.minimal(TestImages.BUSYBOX));
    }

    @Test
    void oneAcceptedTerminationRecoversFromDockerFailureAndReleasesItsSecurityGroup() throws Exception {
        try (Ec2Client sdk = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String vpc = sdk.createVpc(request -> request.cidrBlock("10.73.0.0/16")).vpc().vpcId();
            try {
                String subnet = sdk.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.73.1.0/24"))
                        .subnet().subnetId();
                try {
                    String group = sdk.createSecurityGroup(request -> request.vpcId(vpc)
                            .groupName("termination-retry").description("Instance teardown dependency")).groupId();
                    try {
                        exerciseTermination(sdk, subnet, group);
                    } finally {
                        sdk.deleteSecurityGroup(request -> request.groupId(group));
                    }
                } finally {
                    sdk.deleteSubnet(request -> request.subnetId(subnet));
                }
            } finally {
                sdk.deleteVpc(request -> request.vpcId(vpc));
            }
        }
    }

    private void exerciseTermination(Ec2Client sdk, String subnet, String group) throws Exception {
        String id = sdk.runInstances(request -> request.imageId("ami-termination-retry")
                .instanceType("t3.micro").subnetId(subnet).securityGroupIds(group).minCount(1).maxCount(1))
                .instances().getFirst().instanceId();
        CountDownLatch failedRemoval = new CountDownLatch(1);
        CountDownLatch allowRetry = new CountDownLatch(1);
        String containerId = null;
        try {
            await().atMost(Duration.ofSeconds(60)).until(() -> state(sdk, id) == InstanceStateName.RUNNING);
            Instance instance = service.describeInstances("us-east-1", List.of(id), Map.of())
                    .getFirst().getInstances().getFirst();
            containerId = instance.getDockerContainerId();
            String guest = containerId;
            String networkMode = docker.inspectContainerCmd(guest).exec().getHostConfig().getNetworkMode();
            assertTrue(networkMode.startsWith("container:"), networkMode);
            String helper = networkMode.substring("container:".length());
            RemoveContainerCmd removal = spy(docker.removeContainerCmd(guest));
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(invocation -> {
                if (attempts.incrementAndGet() == 1) {
                    failedRemoval.countDown();
                    throw new DockerException("tried to kill container, but did not receive an exit event", 500);
                }
                assertTrue(allowRetry.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(removal).exec();
            doReturn(removal).when(docker).removeContainerCmd(guest);

            sdk.terminateInstances(request -> request.instanceIds(id));

            assertTrue(failedRemoval.await(5, TimeUnit.SECONDS));
            assertEquals(InstanceStateName.SHUTTING_DOWN, state(sdk, id));
            assertTrue(Boolean.TRUE.equals(docker.inspectContainerCmd(guest).exec().getState().getRunning()));
            Ec2Exception dependency = assertThrows(Ec2Exception.class,
                    () -> sdk.deleteSecurityGroup(request -> request.groupId(group)));
            assertEquals("DependencyViolation", dependency.awsErrorDetails().errorCode());
            allowRetry.countDown();

            await().atMost(Duration.ofSeconds(30)).until(() -> state(sdk, id) == InstanceStateName.TERMINATED);
            assertEquals(2, attempts.get());
            assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(guest).exec());
            assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(helper).exec());
        } finally {
            allowRetry.countDown();
            if (containerId != null) {
                doCallRealMethod().when(docker).removeContainerCmd(containerId);
            }
            // Recovery here is cleanup only; the assertions above require a single accepted request.
            sdk.terminateInstances(request -> request.instanceIds(id));
            await().atMost(Duration.ofSeconds(60)).until(() -> state(sdk, id) == InstanceStateName.TERMINATED);
        }
    }

    private static InstanceStateName state(Ec2Client sdk, String id) {
        return sdk.describeInstances(request -> request.instanceIds(id)).reservations()
                .getFirst().instances().getFirst().state().name();
    }
}
