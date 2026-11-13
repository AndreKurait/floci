package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Raw post-launch attachment requires a privileged guest, separate from protected managed mounts. */
@QuarkusTest
@TestProfile(Ec2RawVolumeUserDataDockerIntegrationTest.RawDeviceProfile.class)
class Ec2RawVolumeUserDataDockerIntegrationTest {

    @Inject
    Ec2Service ec2Service;

    @Inject
    DockerClient dockerClient;

    @InjectMock
    AmiImageResolver images;

    private final List<String> instancesToClean = new ArrayList<>();

    @BeforeEach
    void checkPrerequisites() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for EC2 volume block device tests");
    }

    private boolean isDockerAvailable() {
        if (dockerClient == null) {
            return false;
        }
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterEach
    void cleanup() {
        for (String instanceId : instancesToClean) {
            ec2Service.deleteInstanceForTest("us-east-1", instanceId);
        }
        instancesToClean.clear();
    }

    @Test
    void runInstancesAttachesDataDevicesBeforeTheFirstUserDataCommand() {
        when(images.resolveImage(anyString())).thenReturn(ResolvedAmiImage.minimal("alpine:3.21"));
        LaunchTemplateData.BlockDeviceMapping data = new LaunchTemplateData.BlockDeviceMapping();
        data.setDeviceName("/dev/xvdf");
        LaunchTemplateData.Ebs ebs = new LaunchTemplateData.Ebs();
        ebs.setVolumeSize(1);
        ebs.setVolumeType("gp3");
        data.setEbs(ebs);
        Instance instance = ec2Service.runInstances("us-east-1", "ami-first-boot-data", "t3.micro",
                1, 1, null, List.of(), null, null, List.of(),
                "#!/bin/sh\nif test -b /dev/xvdf; then echo ready; else echo missing; fi > /root/first-boot-data\n",
                null, null, null, 0, null, null, null, null, false, null, null, List.of(data))
                .getInstances().getFirst();
        instancesToClean.add(instance.getInstanceId());
        try {
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                assertEquals("running", instance.getState().getName());
                assertNotNull(instance.getDockerContainerId());
                ContainerExec.Result result = execIn(instance.getDockerContainerId(), "cat /root/first-boot-data");
                assertEquals(0, result.exitCode(), result.summary());
                assertEquals("ready", result.stdout().trim());
            });
        } finally {
            ec2Service.terminateInstances("us-east-1", List.of(instance.getInstanceId()));
            await().atMost(Duration.ofSeconds(30)).until(() -> "terminated".equals(instance.getState().getName()));
        }
    }

    private ContainerExec.Result execIn(String containerIdOrName, String command) {
        try {
            return ContainerExec.runMerged(dockerClient, containerIdOrName, new String[]{"sh", "-c", command}, 30);
        } catch (RuntimeException e) {
            return new ContainerExec.Result(-1, "", Objects.requireNonNullElse(e.getMessage(), e.toString()), false);
        }
    }

    public static class RawDeviceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.ec2.mock", "false",
                    "floci.services.ec2.volume-block-devices", "true",
                    "floci.services.ec2.privileged", "true",
                    "floci.network.security-group-enforcement.enabled", "false");
        }
    }
}
