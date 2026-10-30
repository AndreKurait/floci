package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ContainerNamedNetworkDockerIntegrationTest {

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishedHttpPortWorksWithoutDefaultBridge(boolean linkLocal) throws Exception {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            Assumptions.abort("Docker daemon must be available: " + e.getMessage());
        }
        String name = "floci-named-network-test-" + UUID.randomUUID();
        String networkId = dockerClient.createNetworkCmd().withName(name).withDriver("bridge").exec().getId();
        String containerId = null;
        try {
            ContainerBuilder.Builder builder = containerBuilder.newContainer(TestImages.BUSYBOX)
                    .withName(name)
                    .withNetworkMode(name)
                    .withPortBinding(8080, 0)
                    .withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("mkdir -p /www && printf network-ok > /www/index.html"
                            + " && exec httpd -f -p 8080 -h /www"));
            if (linkLocal) {
                builder.withLinkLocalIp("169.254.170.31");
            }
            ContainerInfo info = lifecycleManager.createAndStart(builder.build());
            containerId = info.containerId();
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            assertEquals(name, inspect.getHostConfig().getNetworkMode());
            assertEquals(Set.of(name), inspect.getNetworkSettings().getNetworks().keySet());
            int port = info.publishedHostPort(8080).orElseThrow();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port))
                    .timeout(Duration.ofSeconds(10)).build();
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
                await().atMost(Duration.ofSeconds(20)).ignoreExceptions().untilAsserted(() -> {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode());
                    assertEquals("network-ok", response.body());
                });
            }
        } finally {
            try {
                if (containerId != null) {
                    dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
                }
            } finally {
                dockerClient.removeNetworkCmd(networkId).exec();
            }
        }
    }
}
