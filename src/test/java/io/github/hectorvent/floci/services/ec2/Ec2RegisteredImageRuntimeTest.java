package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Drives registration/copy handlers and the real resolver; only Docker launch is mocked. */
class Ec2RegisteredImageRuntimeTest {
    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String ARM_IMAGE = "example/worker-arm:1";
    private static final String X86_IMAGE = "example/worker-x86:1";

    @TempDir
    Path directory;

    private Ec2Service service;
    private Ec2QueryHandler handler;
    private Ec2ContainerManager containerManager;

    @BeforeEach
    void createServiceWithExplicitCatalogBindings() throws Exception {
        Path file = directory.resolve("images.yaml");
        Files.writeString(file, """
                defaultDockerImage: example/fallback:1
                images:
                  - imageId: ami-native-arm
                    dockerImage: example/worker-arm:1
                    name: native-arm
                    description: ARM worker
                    architecture: arm64
                    creationDate: '2026-10-08T00:00:00.000Z'
                    guestRuntime: image
                    registrationNames: [arm-release]
                  - imageId: ami-cloud-x86
                    dockerImage: example/worker-x86:1
                    name: cloud-x86
                    description: x86 cloud worker
                    architecture: x86_64
                    creationDate: '2026-10-08T00:00:00.000Z'
                    guestRuntime: systemd
                    cloudInit: true
                    registrationNames: [x86-release]
                """);
        Ec2ImageCatalog catalog = new Ec2ImageCatalog(file);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services().ec2().mock()).thenReturn(false);
        containerManager = mock(Ec2ContainerManager.class);
        service = new Ec2Service(config, containerManager, mock(Ec2PortForwardManager.class),
                new AmiImageResolver(catalog), catalog, new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        handler = new Ec2QueryHandler(service, config, null, null, null, null);
    }

    @Test
    void registerImageThenRunInstancesUsesDeclaredImageRuntimeAndPlatform() {
        String id = imageId(query("RegisterImage", EAST, "Name", "arm-release"));

        Image registered = service.describeImages(EAST, List.of(id), List.of()).getFirst();
        assertEquals("arm64", registered.getArchitecture());
        assertEquals("ami-native-arm", registered.getSourceImageId());
        assertEquals(new ResolvedAmiImage(ARM_IMAGE, ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"),
                launchedImage(EAST, id, "t4g.micro"));
    }

    @Test
    void crossRegionCopyKeepsItsSourceDespiteAnUnrelatedRegistrationName() {
        String source = imageId(query("RegisterImage", EAST,
                "Name", "arm-release", "Architecture", "arm64"));
        String copy = imageId(query("CopyImage", WEST, "SourceRegion", EAST,
                "SourceImageId", source, "Name", "x86-release"));

        assertEquals("arm64", service.describeImages(WEST, List.of(copy), List.of()).getFirst().getArchitecture());
        assertEquals(new ResolvedAmiImage(ARM_IMAGE, ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"),
                launchedImage(WEST, copy, "t4g.micro"));
    }

    @Test
    void registeredSystemdImageRetainsItsRuntimeMetadata() {
        String id = imageId(query("RegisterImage", EAST,
                "Name", "x86-release", "Architecture", "x86_64"));

        assertEquals(new ResolvedAmiImage(X86_IMAGE, ResolvedAmiImage.SYSTEMD_RUNTIME, true, "linux/amd64"),
                launchedImage(EAST, id, "t3.micro"));
    }

    @Test
    void mismatchedArchitectureIsRejectedBeforeTheNameIsRegistered() {
        Response rejected = query("RegisterImage", EAST,
                "Name", "arm-release", "Architecture", "x86_64");
        assertEquals(400, rejected.getStatus());
        assertTrue(rejected.getEntity().toString().contains("InvalidParameterValue"));
        assertTrue(service.describeImages(EAST, List.of(), List.of(),
                Map.of("name", List.of("arm-release"))).isEmpty());

        String id = imageId(query("RegisterImage", EAST, "Name", "arm-release"));
        assertEquals(new ResolvedAmiImage(ARM_IMAGE, ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"),
                launchedImage(EAST, id, "t4g.micro"));
    }

    @Test
    void unmatchedRegistrationNameRetainsMetadataAndFallbackBehavior() {
        String id = imageId(query("RegisterImage", EAST, "Name", "arm-release-extra"));

        Image image = service.describeImages(EAST, List.of(id), List.of()).getFirst();
        assertEquals("x86_64", image.getArchitecture());
        assertNull(image.getSourceImageId());
        assertEquals(ResolvedAmiImage.minimal("example/fallback:1"), launchedImage(EAST, id, "t3.micro"));
    }

    @Test
    void createImageKeepsItsSourceDespiteAnUnrelatedRegistrationName() {
        Instance original = launch(EAST, "ami-native-arm", "t4g.micro");
        when(containerManager.commitInstance(eq(original), anyString())).thenReturn("example/captured:1");

        Image captured = service.createImage(EAST, original.getInstanceId(), "x86-release", null, true);

        assertEquals("arm64", captured.getArchitecture());
        assertEquals(new ResolvedAmiImage("example/captured:1", ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"),
                launchedImage(EAST, captured.getImageId(), "t4g.micro"));
    }

    private Response query(String action, String region, String... entries) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            params.add(entries[i], entries[i + 1]);
        }
        return handler.handle(action, params, region);
    }

    private String imageId(Response response) {
        assertEquals(200, response.getStatus(), response.getEntity().toString());
        String xml = response.getEntity().toString();
        int start = xml.indexOf("<imageId>") + "<imageId>".length();
        assertTrue(start >= "<imageId>".length());
        return xml.substring(start, xml.indexOf("</imageId>", start));
    }

    private Instance launch(String region, String imageId, String instanceType) {
        return service.runInstances(region, imageId, instanceType, 1, 1,
                null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
    }

    private ResolvedAmiImage launchedImage(String region, String imageId, String instanceType) {
        Instance instance = launch(region, imageId, instanceType);
        ArgumentCaptor<ResolvedAmiImage> resolved = ArgumentCaptor.forClass(ResolvedAmiImage.class);
        verify(containerManager).launch(eq(instance), resolved.capture(), any(), eq(region), any(), any(), any());
        return resolved.getValue();
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
