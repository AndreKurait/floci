package io.floci.aws.services.autoscaling;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.ExposedPorts;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.ec2.AmiImageResolver;
import io.floci.aws.services.ec2.ResolvedAmiImage;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.regions.Region;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.floci.aws.services.autoscaling.AutoScalingEventSdkFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(AutoScalingNativeEventSdkTest.NativeEc2.class)
@Timeout(420)
class AutoScalingNativeEventSdkTest {
    private static final ObjectMapper SNAPSHOT_MAPPER = snapshotMapper();

    public static final class NativeEc2 implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false",
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

    @Test
    void nativeTerminationEventRequiresObservedContainerAbsence(@TempDir Path directory) throws IOException {
        InspectContainerResponse snapshotControl = SNAPSHOT_MAPPER.readValue("""
                {"Id":"owned-container","Image":"sha256:owned-image",
                 "Config":{"ExposedPorts":{"22/tcp":{},"53/udp":{}},"Labels":{"floci.test.owner":"owned"}},
                 "HostConfig":{"Privileged":true},"State":{"Running":true}}
                """, InspectContainerResponse.class);
        JsonNode snapshot = SNAPSHOT_MAPPER.valueToTree(snapshotControl);
        assertEquals(SNAPSHOT_MAPPER.readTree("{\"22/tcp\":{},\"53/udp\":{}}"),
                snapshot.path("Config").path("ExposedPorts"));
        assertEquals("owned-container", snapshot.path("Id").asText());
        assertEquals("sha256:owned-image", snapshot.path("Image").asText());
        assertEquals("owned", snapshot.path("Config").path("Labels").path("floci.test.owner").asText());
        assertTrue(snapshot.path("HostConfig").path("Privileged").asBoolean());
        assertTrue(snapshot.path("State").path("Running").asBoolean());
        assertThrows(IllegalArgumentException.class, () -> SNAPSHOT_MAPPER.valueToTree(new Object()));

        try (OwnedImage image = new OwnedImage(directory);
             AutoScalingEventSdkFixture owned = new AutoScalingEventSdkFixture(
                     "native-container", ACCOUNT, Region.US_EAST_1, null)) {
            image.build();
            String ami = "ami-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
            when(images.resolveImage(ami)).thenReturn(new ResolvedAmiImage(image.id, "image", false, null));
            owned.create(ami);
            owned.onEvent = event -> {
                if (TERMINATE.equals(event.path("detail-type").asText())) {
                    List<Container> remaining = image.containers("termination-event-receipt");
                    assertTrue(remaining.isEmpty(), "Success event arrived while the owned native container existed");
                    image.terminationAbsenceObserved = true;
                }
            };
            owned.desired(1);
            String instance = owned.ready(1).getFirst();
            owned.receive(LAUNCH, Set.of(instance));
            assertEquals("running", owned.instance(instance).state().nameAsString());
            List<Container> running = image.containers("launch-event-receipt");
            assertEquals(1, running.size());
            assertEquals("running", running.getFirst().getState());
            image.instanceId = instance;
            image.containerId = running.getFirst().getId();
            InspectContainerResponse inspected = docker.inspectContainerCmd(image.containerId).exec();
            assertEquals(image.id, inspected.getImageId());
            assertEquals(true, inspected.getHostConfig().getPrivileged());
            image.record("inspect-running-container", inspected);

            owned.desired(0);
            owned.ready(0);
            owned.receive(TERMINATE, Set.of(instance));

            assertTrue(image.terminationAbsenceObserved);
            assertEquals("terminated", owned.instance(instance).state().nameAsString());
            assertTrue(image.containers("after-termination").isEmpty());
            owned.success();
            image.forwardComplete = true;
        }
    }

    private static ObjectMapper snapshotMapper() {
        SimpleModule module = new SimpleModule();
        // Docker Java represents each exposed port's empty JSON object as a plain Object placeholder.
        module.addSerializer(ExposedPorts.class, new JsonSerializer<ExposedPorts>() {
            @Override
            public void serialize(ExposedPorts ports, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                generator.writeStartObject();
                for (ExposedPort port : ports.getExposedPorts()) {
                    generator.writeObjectFieldStart(port.toString());
                    generator.writeEndObject();
                }
                generator.writeEndObject();
            }
        });
        return MAPPER.copy().registerModule(module);
    }

    private final class OwnedImage implements AutoCloseable {
        private final Path directory;
        private final String marker = "asg-event-image-" + UUID.randomUUID().toString().replace("-", "");
        private final String tag = "floci-" + marker + ":test";
        private final String namespace = config.docker().resourceNamespace().orElse("");
        private final List<Map<String, Object>> operations = new ArrayList<>();
        private String id;
        private String baseId;
        private String dockerfile;
        private String instanceId;
        private String containerId;
        private boolean attempted;
        private boolean forwardComplete;
        private boolean terminationAbsenceObserved;

        private OwnedImage(Path directory) {
            this.directory = directory;
        }

        private void build() throws IOException {
            docker.pingCmd().exec();
            assertThrows(NotFoundException.class, () -> docker.inspectImageCmd(tag).exec());
            InspectImageResponse base = docker.inspectImageCmd(config.services().ec2().volumeHelperImage()).exec();
            baseId = base.getId();
            record("inspect-base-image", base);
            dockerfile = """
                    FROM %s
                    LABEL floci.test.owner="%s"
                    LABEL floci.test.namespace="%s"
                    ENTRYPOINT ["sh", "-c", "trap 'exit 0' TERM; sleep 2147483647 & wait"]
                    """.formatted(baseId, marker, namespace);
            Files.writeString(directory.resolve("Dockerfile"), dockerfile);
            attempted = true;
            String builtId = docker.buildImageCmd(directory.toFile()).withTags(Set.of(tag)).withPull(false)
                    .start().awaitImageId();
            record("build-returned-image-id", Map.of("id", builtId));
            InspectImageResponse created = docker.inspectImageCmd(builtId).exec();
            String resolvedId = created.getId();
            assertTrue(resolvedId.matches("sha256:[0-9a-f]{64}"));
            String buildDigest = builtId.replaceFirst("^sha256:", "");
            assertTrue(buildDigest.matches("[0-9a-f]{12,64}"));
            assertTrue(resolvedId.substring(7).startsWith(buildDigest));
            id = resolvedId;
            assertEquals(marker, created.getConfig().getLabels().get("floci.test.owner"));
            assertEquals(namespace, created.getConfig().getLabels().get("floci.test.namespace"));
            assertEquals(id, created.getId());
            record("inspect-created-image", created);
        }

        private List<Container> containers(String operation) {
            assertNotNull(id);
            List<Container> found = docker.listContainersCmd().withShowAll(true).withAncestorFilter(List.of(id)).exec();
            found.forEach(container -> assertEquals(id, container.getImageId()));
            record(operation, found);
            return found;
        }

        private void record(String operation, Object value) {
            operations.add(Map.of("operation", operation, "at", Instant.now().toString(),
                    "result", SNAPSHOT_MAPPER.valueToTree(value)));
        }

        @Override
        public void close() {
            Throwable cleanupFailure = null;
            boolean cleanupComplete = !attempted;
            try {
                if (attempted) {
                    try {
                        InspectImageResponse current = docker.inspectImageCmd(tag).exec();
                        assertEquals(marker, current.getConfig().getLabels().get("floci.test.owner"));
                        assertEquals(namespace, current.getConfig().getLabels().get("floci.test.namespace"));
                        if (id != null) {
                            assertEquals(id, current.getId());
                        }
                        id = current.getId();
                        record("inspect-before-cleanup", current);
                        assertTrue(containers("final-owned-container-inventory").isEmpty(),
                                "Service cleanup must remove owned containers before the image");
                        docker.removeImageCmd(id).withForce(false).exec();
                    } catch (NotFoundException absent) {
                        record("image-already-absent", Map.of("type", absent.getClass().getName()));
                    }
                    assertThrows(NotFoundException.class, () -> docker.inspectImageCmd(tag).exec());
                    if (id != null) {
                        assertThrows(NotFoundException.class, () -> docker.inspectImageCmd(id).exec());
                    }
                    cleanupComplete = true;
                }
            } catch (RuntimeException | Error failure) {
                cleanupFailure = failure;
                throw failure;
            } finally {
                try {
                    Path output = Path.of(System.getProperty("audit.autoscaling.events.directory",
                            "target/autoscaling-event-wire"));
                    Files.createDirectories(output);
                    Map<String, Object> receipt = new LinkedHashMap<>();
                    receipt.put("case", "native-container-image");
                    receipt.put("marker", marker);
                    receipt.put("namespace", namespace);
                    receipt.put("account", ACCOUNT);
                    receipt.put("region", Region.US_EAST_1.id());
                    receipt.put("imageTag", tag);
                    receipt.put("imageId", id);
                    receipt.put("baseImageId", baseId);
                    receipt.put("dockerfile", dockerfile);
                    receipt.put("instanceId", instanceId);
                    receipt.put("containerId", containerId);
                    receipt.put("forwardComplete", forwardComplete);
                    receipt.put("terminationAbsenceObserved", terminationAbsenceObserved);
                    receipt.put("cleanupComplete", cleanupComplete);
                    receipt.put("operations", operations);
                    MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.resolve(marker + ".json").toFile(), receipt);
                } catch (IOException | RuntimeException | Error recordingFailure) {
                    if (cleanupFailure != null) {
                        cleanupFailure.addSuppressed(recordingFailure);
                    } else if (recordingFailure instanceof IOException io) {
                        throw new UncheckedIOException(io);
                    } else if (recordingFailure instanceof RuntimeException runtime) {
                        throw runtime;
                    } else {
                        throw (Error) recordingFailure;
                    }
                }
            }
        }
    }
}
