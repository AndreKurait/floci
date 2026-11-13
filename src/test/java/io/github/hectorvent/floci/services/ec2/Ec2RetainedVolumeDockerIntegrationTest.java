package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.github.hectorvent.floci.testing.IndependentServiceSettingsProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** SDK recovery into the existing privileged raw-device mode; native mounts stay unprivileged. */
@QuarkusTest
@TestProfile(Ec2RetainedVolumeDockerIntegrationTest.RawDeviceProfile.class)
class Ec2RetainedVolumeDockerIntegrationTest {

    public static class RawDeviceProfile extends IndependentServiceSettingsProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> settings = new HashMap<>(super.getConfigOverrides());
            settings.put("floci.network.security-group-enforcement.enabled", "false");
            return settings;
        }
    }

    @Inject
    Ec2Service ec2Service;

    @Inject
    Ec2VolumeBlockDeviceManager volumeBlockDeviceManager;

    @Inject
    DockerClient dockerClient;

    @Inject
    EmulatorConfig config;

    @InjectMock
    AmiImageResolver images;

    @TestHTTPResource
    URI endpoint;

    @BeforeEach
    void requireDocker() {
        try {
            dockerClient.pingCmd().exec();
        } catch (RuntimeException unavailable) {
            Assumptions.abort("Docker daemon is unavailable: " + unavailable.getMessage());
        }
    }

    @Test
    void managedPreparationRefusesUnknownAllocatedDataWithoutChangingIt() {
        String volumeId = "vol-" + UUID.randomUUID().toString().replace("-", "");
        String helper = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        String raw = "/volumes/" + volumeId + ".raw";
        volumeBlockDeviceManager.createVolume(volumeId, 1);
        try {
            ContainerExec.Result written = execIn(helper,
                    "printf unrecognized-data | dd of=" + raw + " conv=notrunc status=none");
            assertEquals(0, written.exitCode(), written.summary());
            ContainerExec.Result before = execIn(helper, "sha256sum " + raw + "; stat -c '%s %b %i' " + raw);
            assertEquals(0, before.exitCode(), before.summary());
            Instance instance = new Instance();
            instance.setInstanceId("i-fixture");
            instance.setOwnerId(config.defaultAccountId());
            instance.setRegion("us-east-1");
            instance.setRootDeviceName("/dev/xvda");
            Volume volume = new Volume();
            volume.setVolumeId(volumeId);
            volume.setRegion("us-east-1");
            volume.setSize(1);
            volume.setManagedFilesystem("ext4");
            VolumeAttachment attachment = new VolumeAttachment();
            attachment.setVolumeId(volumeId);
            attachment.setInstanceId(instance.getInstanceId());
            attachment.setDevice("/dev/sdf");
            attachment.setState("attached");
            volume.getAttachments().add(attachment);

            AwsException failure = assertThrows(AwsException.class, () -> volumeBlockDeviceManager.prepareMount(
                    volume, instance, new Ec2ImageCatalog.VolumeMount("/dev/sdf", "/data", "ext4")));

            assertTrue(failure.getMessage().contains("refusing to format existing data"), failure.getMessage());
            ContainerExec.Result after = execIn(helper, "sha256sum " + raw + "; stat -c '%s %b %i' " + raw);
            assertEquals(0, after.exitCode(), after.summary());
            assertEquals(before.stdout(), after.stdout());
            assertEquals("", execIn(helper, "losetup -j " + raw).stdout().trim());
            assertTrue(dockerClient.listVolumesCmd().exec().getVolumes().stream().noneMatch(row ->
                    ContainerStorageHelper.resourceName(config, "ec2", null, volumeId).equals(row.getName())));
        } finally {
            // This file belongs to this fixture; no EC2 volume record was created.
            assertTrue(volumeBlockDeviceManager.deleteVolume(volumeId));
        }
    }

    @Test
    void sdkRetainedFilesystemReattachesWithoutLosingDataOrFailedDetachState(@TempDir Path directory) throws Exception {
        Path managed = Files.createDirectory(directory.resolve("managed"));
        Path recovery = Files.createDirectory(directory.resolve("recovery"));
        Files.writeString(managed.resolve("Dockerfile"), """
                FROM %s
                ENTRYPOINT ["sh", "-ec", "test -b /dev/sdf; findmnt -n -o FSTYPE /data | grep -qx ext4; printf mounted > /data/first-boot; trap 'exit 0' TERM; sleep 2147483647 & wait"]
                """.formatted(config.services().ec2().volumeHelperImage()));
        Files.writeString(recovery.resolve("Dockerfile"), """
                FROM %s
                ENTRYPOINT ["sh", "-ec", "trap 'exit 0' TERM; sleep 2147483647 & wait"]
                """.formatted(config.services().ec2().volumeHelperImage()));
        String managedImage = dockerClient.buildImageCmd(managed.toFile()).start().awaitImageId();
        String recoveryImage = dockerClient.buildImageCmd(recovery.toFile()).start().awaitImageId();
        when(images.resolveImage("ami-managed-retained")).thenReturn(new ResolvedAmiImage(
                managedImage, "image", false, null,
                List.of(new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4"))));
        when(images.resolveImage("ami-raw-recovery")).thenReturn(new ResolvedAmiImage(
                recoveryImage, "image", false, null));
        List<String> launched = new ArrayList<>();
        List<String> retainedVolumes = new ArrayList<>();
        try (Ec2Client sdk = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            try {
                String firstId = sdk.runInstances(request -> request.imageId("ami-managed-retained")
                        .instanceType("t3.micro").minCount(1).maxCount(1)
                        .blockDeviceMappings(mapping -> mapping.deviceName("/dev/sdf")
                                .ebs(ebs -> ebs.volumeType("gp3").volumeSize(1).deleteOnTermination(false))))
                        .instances().getFirst().instanceId();
                launched.add(firstId);
                Instance first = ec2Service.describeInstances("us-east-1", List.of(firstId), Map.of())
                        .getFirst().getInstances().getFirst();
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                    assertEquals("running", first.getState().getName());
                    assertEquals("mounted", successfulOutput(first, "cat /data/first-boot"));
                });
                String volumeId = sdk.describeInstances(request -> request.instanceIds(firstId))
                        .reservations().getFirst().instances().getFirst().blockDeviceMappings().stream()
                        .filter(mapping -> "/dev/sdf".equals(mapping.deviceName()))
                        .findFirst().orElseThrow().ebs().volumeId();
                retainedVolumes.add(volumeId);
                sdk.createTags(request -> request.resources(volumeId).tags(tag -> tag.key("Owner").value("retained")));
                String uuid = successfulOutput(first, "blkid -s UUID -o value /dev/sdf");
                successfulOutput(first, "printf recovery-data > /data/document");

                assertThrows(Ec2Exception.class, () -> sdk.detachVolume(request -> request.volumeId(volumeId)));
                assertEquals("in-use", sdk.describeVolumes(request -> request.volumeIds(volumeId))
                        .volumes().getFirst().stateAsString());
                assertEquals("attached", sdk.describeVolumes(request -> request.volumeIds(volumeId))
                        .volumes().getFirst().attachments().getFirst().stateAsString());
                assertEquals("recovery-data", successfulOutput(first, "cat /data/document"));
                assertTrue(sdk.describeVolumes(request -> request.volumeIds(volumeId)).volumes().getFirst().tags()
                        .stream().anyMatch(tag -> "Owner".equals(tag.key()) && "retained".equals(tag.value())));
                sdk.terminateInstances(request -> request.instanceIds(firstId));
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                    assertEquals("terminated", first.getState().getName());
                    assertEquals("available", sdk.describeVolumes(request -> request.volumeIds(volumeId))
                            .volumes().getFirst().stateAsString());
                });

                String recoveryId = sdk.runInstances(request -> request.imageId("ami-raw-recovery")
                        .instanceType("t3.micro").minCount(1).maxCount(1))
                        .instances().getFirst().instanceId();
                launched.add(recoveryId);
                Instance recovered = ec2Service.describeInstances("us-east-1", List.of(recoveryId), Map.of())
                        .getFirst().getInstances().getFirst();
                await().atMost(Duration.ofSeconds(60)).until(() -> "running".equals(recovered.getState().getName()));
                assertTrue(Boolean.TRUE.equals(dockerClient.inspectContainerCmd(recovered.getDockerContainerId())
                        .exec().getHostConfig().getPrivileged()));
                String mountName = ContainerStorageHelper.resourceName(config, "ec2", null, volumeId);
                for (boolean foreignOwner : List.of(true, false)) {
                    Map<String, String> labels = new HashMap<>(ContainerStorageHelper.defaultLabels(config));
                    labels.putAll(ContainerStorageHelper.resourceIdentityLabels("ec2", volumeId,
                            foreignOwner ? "999999999999" : first.getOwnerId(), "us-east-1"));
                    Map<String, String> options = Map.of(
                            "type", "ext4", "device", "/dev/loop0", "o", foreignOwner ? "rw" : "ro");
                    dockerClient.createVolumeCmd().withName(mountName).withDriver("local")
                            .withLabels(labels).withDriverOpts(options).exec();
                    try {
                        assertThrows(Ec2Exception.class, () -> sdk.attachVolume(request -> request.volumeId(volumeId)
                                .instanceId(recoveryId).device("/dev/sdf")));
                        assertEquals("available", sdk.describeVolumes(request -> request.volumeIds(volumeId))
                                .volumes().getFirst().stateAsString());
                        assertEquals(labels, dockerClient.inspectVolumeCmd(mountName).exec().getLabels());
                        assertEquals(options, dockerClient.inspectVolumeCmd(mountName).exec().getOptions());
                    } finally {
                        dockerClient.removeVolumeCmd(mountName).exec();
                    }
                }
                successfulOutput(recovered, "printf unrelated > /dev/sdf");
                assertThrows(Ec2Exception.class, () -> sdk.attachVolume(request -> request.volumeId(volumeId)
                        .instanceId(recoveryId).device("/dev/sdf")));
                assertEquals("unrelated", successfulOutput(recovered, "cat /dev/sdf"));
                assertEquals("available", sdk.describeVolumes(request -> request.volumeIds(volumeId))
                        .volumes().getFirst().stateAsString());
                successfulOutput(recovered, "rm /dev/sdf");
                sdk.attachVolume(request -> request.volumeId(volumeId).instanceId(recoveryId).device("/dev/sdf"));
                successfulOutput(recovered, "mkdir -p /recovered; mount /dev/sdf /recovered");
                assertEquals(uuid, successfulOutput(recovered, "blkid -s UUID -o value /dev/sdf"));
                assertEquals("recovery-data", successfulOutput(recovered, "cat /recovered/document"));

                sdk.stopInstances(request -> request.instanceIds(recoveryId));
                await().atMost(Duration.ofSeconds(60)).until(() -> "stopped".equals(recovered.getState().getName()));
                sdk.startInstances(request -> request.instanceIds(recoveryId));
                await().atMost(Duration.ofSeconds(60)).until(() -> "running".equals(recovered.getState().getName()));
                await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                        assertEquals(0, execIn(recovered.getDockerContainerId(), "test -b /dev/sdf").exitCode()));
                successfulOutput(recovered, "mount /dev/sdf /recovered");
                assertEquals(uuid, successfulOutput(recovered, "blkid -s UUID -o value /dev/sdf"));
                assertEquals("recovery-data", successfulOutput(recovered, "cat /recovered/document"));
                assertThrows(Ec2Exception.class, () -> sdk.detachVolume(request -> request.volumeId(volumeId)));
                assertEquals("in-use", sdk.describeVolumes(request -> request.volumeIds(volumeId))
                        .volumes().getFirst().stateAsString());
                successfulOutput(recovered, "umount /recovered");
                sdk.detachVolume(request -> request.volumeId(volumeId));
                assertEquals(0, execIn(recovered.getDockerContainerId(), "test ! -e /dev/sdf").exitCode());
                sdk.attachVolume(request -> request.volumeId(volumeId).instanceId(recoveryId).device("/dev/sdg"));
                successfulOutput(recovered, "mount /dev/sdg /recovered");
                assertEquals(uuid, successfulOutput(recovered, "blkid -s UUID -o value /dev/sdg"));
                assertEquals("recovery-data", successfulOutput(recovered, "cat /recovered/document"));
                sdk.terminateInstances(request -> request.instanceIds(recoveryId));
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                    assertEquals("terminated", recovered.getState().getName());
                    assertEquals("available", sdk.describeVolumes(request -> request.volumeIds(volumeId))
                            .volumes().getFirst().stateAsString());
                });
                sdk.deleteVolume(request -> request.volumeId(volumeId));
                assertEquals("InvalidVolume.NotFound", assertThrows(Ec2Exception.class,
                        () -> sdk.describeVolumes(request -> request.volumeIds(volumeId)))
                        .awsErrorDetails().errorCode());
                retainedVolumes.remove(volumeId);
            } finally {
                for (String id : launched) {
                    sdk.terminateInstances(request -> request.instanceIds(id));
                }
                await().atMost(Duration.ofSeconds(60)).until(() -> launched.stream().allMatch(id ->
                        "terminated".equals(sdk.describeInstances(request -> request.instanceIds(id))
                                .reservations().getFirst().instances().getFirst().state().nameAsString())));
                for (String volume : retainedVolumes) {
                    sdk.deleteVolume(request -> request.volumeId(volume));
                }
            }
        } finally {
            dockerClient.removeImageCmd(managedImage).exec();
            dockerClient.removeImageCmd(recoveryImage).exec();
        }
    }

    private ContainerExec.Result execIn(String container, String command) {
        return ContainerExec.runMerged(dockerClient, container, new String[]{"sh", "-c", command}, 30);
    }

    private String successfulOutput(Instance instance, String command) {
        ContainerExec.Result result = execIn(instance.getDockerContainerId(), command);
        assertEquals(0, result.exitCode(), result.summary());
        return result.stdout().trim();
    }
}
