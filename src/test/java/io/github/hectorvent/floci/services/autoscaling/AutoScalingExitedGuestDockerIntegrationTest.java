package io.github.hectorvent.floci.services.autoscaling;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.AmiImageResolver;
import io.github.hectorvent.floci.services.ec2.ResolvedAmiImage;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(AutoScalingExitedGuestDockerIntegrationTest.Profile.class)
class AutoScalingExitedGuestDockerIntegrationTest {
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false",
                    "floci.services.ec2.privileged", "false",
                    "floci.services.ec2.volume-block-devices", "false",
                    "floci.services.ec2.vpc-networks.enabled", "false");
        }
    }

    @Inject
    DockerClient docker;

    @Inject
    EmulatorConfig config;

    @InjectMock
    AmiImageResolver images;

    @TestHTTPResource
    URI endpoint;

    @Test
    void guestExitTerminatesOldEc2InstanceBeforeReplacement(@TempDir Path directory) throws Exception {
        try {
            docker.pingCmd().exec();
        } catch (RuntimeException unavailable) {
            Assumptions.abort("Docker daemon is unavailable: " + unavailable.getMessage());
        }
        String name = "exited-" + UUID.randomUUID();
        Files.writeString(directory.resolve("Dockerfile"), """
                FROM %s
                LABEL floci.test.owner="%s"
                ENTRYPOINT ["sh", "-c", "trap 'exit 0' TERM; sleep 2147483647 & wait"]
                """.formatted(config.services().ec2().volumeHelperImage(), name));
        String image = docker.buildImageCmd(directory.toFile()).start().awaitImageId();
        when(images.resolveImage("ami-exited-guest")).thenReturn(
                new ResolvedAmiImage(image, "image", false, null));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        try (Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             AutoScalingClient asg = AutoScalingClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            asg.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                    .imageId("ami-exited-guest").instanceType("t3.micro"));
            try {
                asg.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                        .launchConfigurationName(name).minSize(0).maxSize(1).desiredCapacity(1)
                        .availabilityZones("us-east-1a"));
                await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                    AutoScalingGroup group = asg.describeAutoScalingGroups(request ->
                            request.autoScalingGroupNames(name)).autoScalingGroups().getFirst();
                    assertFalse(group.instances().isEmpty(), "Waiting for the initial instance");
                    assertEquals("InService", group.instances().getFirst().lifecycleStateAsString());
                });
                String original = asg.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                        .autoScalingGroups().getFirst().instances().getFirst().instanceId();
                String container = docker.listContainersCmd().withAncestorFilter(List.of(image)).exec()
                        .getFirst().getId();
                // Exit the actual guest without calling EC2 StopInstances or TerminateInstances.
                docker.stopContainerCmd(container).withTimeout(10).exec();

                await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                    AutoScalingGroup group = asg.describeAutoScalingGroups(request ->
                            request.autoScalingGroupNames(name)).autoScalingGroups().getFirst();
                    assertFalse(group.instances().isEmpty(), "Waiting for the replacement instance");
                    String replacement = group.instances().getFirst().instanceId();
                    assertNotEquals(original, replacement);
                    assertEquals("terminated", ec2.describeInstances(request -> request.instanceIds(original))
                            .reservations().getFirst().instances().getFirst().state().nameAsString());
                    assertEquals("running", ec2.describeInstances(request -> request.instanceIds(replacement))
                            .reservations().getFirst().instances().getFirst().state().nameAsString());
                });
            } finally {
                asg.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                asg.deleteLaunchConfiguration(request -> request.launchConfigurationName(name));
                await().atMost(Duration.ofSeconds(30)).until(() -> docker.listContainersCmd()
                        .withShowAll(true).withAncestorFilter(List.of(image)).exec().isEmpty());
            }
        } finally {
            docker.removeImageCmd(image).exec();
        }
    }
}
