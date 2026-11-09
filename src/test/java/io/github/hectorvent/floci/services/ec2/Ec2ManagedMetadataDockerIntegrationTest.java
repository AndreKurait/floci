package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(Ec2ManagedMetadataDockerIntegrationTest.ManagedGuest.class)
class Ec2ManagedMetadataDockerIntegrationTest {
    @Inject DockerClient docker;
    @Inject Ec2Service service;
    @InjectMock AmiImageResolver images;

    public static class ManagedGuest implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false");
        }
    }

    @Test
    void metadataIsReadyAtGuestStartupAndSurvivesStopStartAndReboot() {
        String image = System.getProperty("test.ec2-metadata-guest-image");
        Assumptions.assumeTrue(image != null && DockerAvailability.answers(docker),
                "An owned metadata probe guest image and Docker are required");
        when(images.resolveImage(anyString())).thenReturn(ResolvedAmiImage.minimal(image));
        String userData = "#!/bin/sh\n" + tokenRead() + " > /tmp/userdata-instance-id\n";
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String id = client.runInstances(b -> b.imageId("ami-amazonlinux2023")
                    .instanceType("t3.micro").minCount(1).maxCount(1)
                    .metadataOptions(m -> m.httpTokens("required"))
                    .userData(Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8))))
                    .instances().getFirst().instanceId();
            Instance instance = service.findInstanceForAccount("000000000000", "us-east-1", id).orElseThrow();
            try {
                await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
                    assertEquals("running", instance.getState().getName());
                    assertEquals(id, exec(instance, "cat /tmp/boot-instance-id").trim());
                    assertEquals(id, exec(instance, "cat /tmp/userdata-instance-id").trim());
                });
                assertEquals("401", exec(instance,
                        "curl -sS --max-time 3 -o /dev/null -w '%{http_code}' "
                                + "http://169.254.169.254/latest/meta-data/instance-id"));
                client.modifyInstanceMetadataOptions(b -> b.instanceId(id).httpEndpoint("disabled"));
                assertEquals("404", exec(instance,
                        "curl -sS --max-time 3 -o /dev/null -w '%{http_code}' -X PUT "
                                + "-H 'X-aws-ec2-metadata-token-ttl-seconds: 60' "
                                + "http://169.254.169.254/latest/api/token"));
                client.modifyInstanceMetadataOptions(b -> b.instanceId(id).httpEndpoint("enabled"));
                assertEquals(id, exec(instance, tokenRead()).trim());
                exec(instance, "rm /tmp/boot-instance-id");
                client.stopInstances(b -> b.instanceIds(id));
                await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                        assertEquals("stopped", instance.getState().getName()));
                client.startInstances(b -> b.instanceIds(id));
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                    assertEquals("running", instance.getState().getName());
                    assertEquals(id, exec(instance, "cat /tmp/boot-instance-id").trim());
                    assertEquals(id, exec(instance, tokenRead()).trim());
                });
                exec(instance, "rm /tmp/boot-instance-id");
                client.rebootInstances(b -> b.instanceIds(id));
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                    assertEquals(id, exec(instance, "cat /tmp/boot-instance-id").trim());
                    assertEquals(id, exec(instance, tokenRead()).trim());
                });
            } finally {
                String container = instance.getDockerContainerId();
                client.terminateInstances(b -> b.instanceIds(id));
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                    assertEquals("terminated", instance.getState().getName());
                    if (container != null) {
                        assertFalse(docker.listContainersCmd().withShowAll(true).exec().stream()
                                .anyMatch(c -> container.equals(c.getId())));
                    }
                });
            }
        }
    }

    private String exec(Instance instance, String command) {
        ContainerExec.Result result = ContainerExec.run(docker, instance.getDockerContainerId(),
                new String[]{"sh", "-ec", command}, 10);
        assertEquals(0, result.exitCode(), result.summary());
        return result.stdout();
    }

    private static String tokenRead() {
        return "token=$(curl -fsS --max-time 3 -X PUT "
                + "-H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token); "
                + "curl -fsS --max-time 3 -H \"X-aws-ec2-metadata-token: $token\" "
                + "http://169.254.169.254/latest/meta-data/instance-id";
    }
}
