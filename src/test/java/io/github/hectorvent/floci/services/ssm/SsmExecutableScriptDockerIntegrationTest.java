package io.github.hectorvent.floci.services.ssm;

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
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(SsmExecutableScriptDockerIntegrationTest.Profile.class)
class SsmExecutableScriptDockerIntegrationTest {
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
    void sdkRunsTheGuestInterpreterAndPreservesFailures(@TempDir Path directory) throws Exception {
        try {
            docker.pingCmd().exec();
        } catch (RuntimeException unavailable) {
            Assumptions.abort("Docker daemon is unavailable: " + unavailable.getMessage());
        }
        Files.writeString(directory.resolve("Dockerfile"), """
                FROM %s
                ENTRYPOINT ["sh", "-c", "trap 'exit 0' TERM; sleep 2147483647 & wait"]
                """.formatted(config.services().ec2().volumeHelperImage()));
        String image = docker.buildImageCmd(directory.toFile()).start().awaitImageId();
        when(images.resolveImage("ami-ssm-script")).thenReturn(
                new ResolvedAmiImage(image, "image", false, null));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        try (Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             SsmClient ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String instance = ec2.runInstances(request -> request.imageId("ami-ssm-script")
                    .instanceType("t3.micro").minCount(1).maxCount(1)).instances().getFirst().instanceId();
            try {
                await().atMost(Duration.ofSeconds(30)).until(() -> ec2.describeInstances(request ->
                        request.instanceIds(instance)).reservations().getFirst().instances().getFirst()
                        .state().nameAsString().equals("running"));
                String script = "#!/bin/cat\nthis is input for cat, not a shell command";
                GetCommandInvocationResponse result = run(ssm, instance, script);
                assertEquals("Success", result.statusAsString());
                assertEquals(script + "\n", result.standardOutputContent());
                assertEquals(0, result.responseCode());

                result = run(ssm, instance, "#!/bin/sh -e\nfalse\nprintf 'must not run'");
                assertEquals("Failed", result.statusAsString());
                assertEquals(1, result.responseCode());
                assertEquals("", result.standardOutputContent());

                result = run(ssm, instance, "pwd\nprintf diagnostic >&2\nexit 7");
                assertEquals("Failed", result.statusAsString());
                assertEquals(7, result.responseCode());
                assertEquals("/tmp\n", result.standardOutputContent());
                assertEquals("diagnostic", result.standardErrorContent());

                result = run(ssm, instance, "for p in /tmp/floci-ssm.*; do [ ! -d \"$p\" ] || printf '%s\\n' \"$p\"; done");
                assertEquals("Success", result.statusAsString());
                assertEquals(1, result.standardOutputContent().lines().count(),
                        "Only the current invocation's temporary directory may remain");
            } finally {
                ec2.terminateInstances(request -> request.instanceIds(instance));
                await().atMost(Duration.ofSeconds(30)).until(() -> docker.listContainersCmd()
                        .withShowAll(true).withAncestorFilter(List.of(image)).exec().isEmpty());
            }
        } finally {
            docker.removeImageCmd(image).exec();
        }
    }

    private GetCommandInvocationResponse run(SsmClient ssm, String instance, String script) {
        String command = ssm.sendCommand(request -> request.documentName("AWS-RunShellScript")
                .instanceIds(instance).parameters(Map.of(
                        "commands", List.of(script), "workingDirectory", List.of("/tmp"),
                        "executionTimeout", List.of("10")))).command().commandId();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            String status = ssm.getCommandInvocation(request -> request.commandId(command)
                    .instanceId(instance)).statusAsString();
            return !List.of("Pending", "InProgress", "Delayed").contains(status);
        });
        GetCommandInvocationResponse result = ssm.getCommandInvocation(request ->
                request.commandId(command).instanceId(instance));
        assertTrue(result.executionEndDateTime() != null);
        return result;
    }
}
