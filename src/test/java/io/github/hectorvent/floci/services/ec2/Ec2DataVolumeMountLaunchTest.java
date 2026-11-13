package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A catalog image that declares a managed data-volume mount boots from the same AMI whether or not a launch maps
 * a volume at that device, as one AMI does on EC2 whatever its launches map: the mount applies only to launches
 * that map one snapshot-free volume there. Drives the real catalog, resolver and Query handler; only Docker launch
 * is mocked.
 */
class Ec2DataVolumeMountLaunchTest {
    private static final String REGION = "us-east-1";
    private static final Ec2ImageCatalog.VolumeMount DATA =
            new Ec2ImageCatalog.VolumeMount("/dev/sdf", "/var/lib/engine/data", "ext4");

    @TempDir
    Path directory;

    private Ec2QueryHandler handler;
    private Ec2ContainerManager containerManager;

    @BeforeEach
    void createServiceWithAMountedDataVolumeImage() throws Exception {
        Path file = directory.resolve("images.yaml");
        Files.writeString(file, """
                includeDefaultImages: true
                images:
                  - imageId: ami-0123456789abcdef0
                    dockerImage: example/engine:1
                    name: engine
                    description: engine with a managed data volume
                    architecture: x86_64
                    creationDate: '2026-11-06T00:00:00.000Z'
                    guestRuntime: image
                    volumeMounts:
                      - device: /dev/sdf
                        target: /var/lib/engine/data
                        filesystem: ext4
                """);
        Ec2ImageCatalog catalog = new Ec2ImageCatalog(file);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services().ec2().mock()).thenReturn(false);
        containerManager = mock(Ec2ContainerManager.class);
        Ec2Service service = new Ec2Service(config, containerManager, mock(Ec2PortForwardManager.class),
                new AmiImageResolver(catalog), catalog, new Ec2InstanceTypeCatalog(), new InMemoryStorageFactory());
        handler = new Ec2QueryHandler(service, config, null, null, null, null, mock(RegionResolver.class));
    }

    @Test
    void aLaunchThatMapsTheDeclaredDeviceGetsTheManagedMount() {
        Response response = runInstances("BlockDeviceMapping.1.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "10", "BlockDeviceMapping.1.Ebs.VolumeType", "gp3");

        assertEquals(200, response.getStatus(), response.getEntity().toString());
        assertEquals(List.of(DATA), launchedImage().volumeMounts());
    }

    @Test
    void aLaunchWithoutTheDeclaredDeviceBootsTheSameImageWithoutAManagedMount() {
        Response response = runInstances("BlockDeviceMapping.1.DeviceName", "/dev/sdb",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "7", "BlockDeviceMapping.1.Ebs.VolumeType", "gp3");

        assertEquals(200, response.getStatus(), response.getEntity().toString());
        ResolvedAmiImage launched = launchedImage();
        assertEquals("example/engine:1", launched.dockerImage());
        assertEquals(ResolvedAmiImage.IMAGE_RUNTIME, launched.guestRuntime());
        assertTrue(launched.volumeMounts().isEmpty());
    }

    @Test
    void aLaunchWithNoBlockDeviceMappingsBootsWithoutAManagedMount() {
        Response response = runInstances();

        assertEquals(200, response.getStatus(), response.getEntity().toString());
        assertTrue(launchedImage().volumeMounts().isEmpty());
    }

    @Test
    void aSuppressedDeclaredDeviceBootsWithoutAManagedMount() {
        Response response = runInstances("BlockDeviceMapping.1.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.1.NoDevice", "");

        assertEquals(200, response.getStatus(), response.getEntity().toString());
        assertTrue(launchedImage().volumeMounts().isEmpty());
    }

    private Response runInstances(String... entries) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.add("ImageId", "ami-0123456789abcdef0");
        params.add("InstanceType", "m5.large");
        params.add("MinCount", "1");
        params.add("MaxCount", "1");
        for (int i = 0; i < entries.length; i += 2) {
            params.add(entries[i], entries[i + 1]);
        }
        return handler.handle("RunInstances", params, REGION);
    }

    private ResolvedAmiImage launchedImage() {
        ArgumentCaptor<ResolvedAmiImage> resolved = ArgumentCaptor.forClass(ResolvedAmiImage.class);
        verify(containerManager).launch(any(), resolved.capture(), any(), eq(REGION), any(), any(), any(), any(),
                any());
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
