package io.floci.aws.services.elb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.ec2.AmiImageResolver;
import io.floci.aws.services.ec2.Ec2ContainerManager;
import io.floci.aws.services.ec2.Ec2ImageCatalog;
import io.floci.aws.services.ec2.Ec2InstanceTypeCatalog;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElbClassicProcessRecoveryTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final int CRASH_EXIT = 73;

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"allocation", "namespace", "deletion"})
    void hardStopLeavesRecoverableOwnership(String boundary) throws Exception {
        runChild("deletion".equals(boundary) ? "namespace" : boundary, CRASH_EXIT);
        assertEquals("allocated", Files.readString(directory.resolve("phase")));
        if ("deletion".equals(boundary)) {
            runChild("deletion", CRASH_EXIT);
            assertEquals("deleted", Files.readString(directory.resolve("phase")));
        }
        runChild("recover", 0);
        assertEquals("recovered", Files.readString(directory.resolve("phase")));
        runChild("empty", 0);
    }

    @Test
    void emptyStorageStartupRemainsIdempotentAcrossProcesses() throws Exception {
        Files.writeString(directory.resolve("phase"), "empty");
        runChild("empty", 0);
        runChild("empty", 0);
    }

    private void runChild(String mode, int expectedExit) throws Exception {
        Path output = directory.resolve(mode + ".log");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+EnableDynamicAgentLoading", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ElbClassicProcessRecoveryTest.class.getName(), directory.toString(), mode);
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), () -> "Child timed out: " + output);
            assertEquals(expectedExit, child.exitValue(), () -> readLog(output));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Owned child did not exit");
            }
        }
    }

    private static String readLog(Path output) {
        try {
            return Files.readString(output);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read child output", failure);
        }
    }

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        String mode = arguments[1];
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.defaultRegion()).thenReturn(REGION);
        when(config.storage().persistentPath()).thenReturn(directory.resolve("storage").toString());
        when(config.services().ec2().mock()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.DOCUMENTATION);
        when(config.services().ec2().mockPublicAddress()).thenReturn(Optional.empty());
        when(config.services().elb().vpcFrontends()).thenReturn(true);
        ServiceConfigAccess services = mock(ServiceConfigAccess.class);
        when(services.storageMode(anyString())).thenReturn("persistent");
        StorageFactory factory = new StorageFactory(config, services);
        AccountAwareStorageBackend<ElbClassicFrontendManager.Ownership> ownership =
                factory.create("elb", "elb-classic-frontends.json", new TypeReference<>() {});
        Ec2Service ec2 = spy(new Ec2Service(config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class),
                new Ec2InstanceTypeCatalog(), factory));
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(List.of());
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        VpcNetworkManager networks = mock(VpcNetworkManager.class);
        when(networks.enabled()).thenReturn(true);
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        when(firewall.enabled()).thenReturn(true);
        ElbClassicFrontendManager manager = new ElbClassicFrontendManager(config, ec2, firewall, networks,
                mock(ContainerBuilder.class), lifecycle, new ObjectMapper(), mock(IamService.class),
                mock(AcmService.class), mock(VpcRoutingManager.class), factory);
        if ("recover".equals(mode) || "empty".equals(mode) || "deletion".equals(mode)) {
            if (!"empty".equals(mode)) {
                assertEquals(1, ownership.scanAllAccounts().size(), "Crash must preserve ownership intent");
            }
            int expectedInterfaces = "allocated".equals(Files.readString(directory.resolve("phase"))) ? 1 : 0;
            assertEquals(expectedInterfaces,
                    ec2.describeNetworkInterfaces(REGION, List.of(), Map.of()).networkInterfaces().size(),
                    "Fresh EC2 storage must reflect the crash boundary");
            if ("deletion".equals(mode)) {
                doAnswer(call -> {
                    call.callRealMethod();
                    Files.writeString(directory.resolve("phase"), "deleted");
                    Runtime.getRuntime().halt(CRASH_EXIT);
                    return null;
                }).when(ec2).deleteNetworkInterface(eq(REGION), anyString());
            }
            manager.initialize();
            assertTrue(ownership.scanAllAccounts().isEmpty(), "Recovered ownership must be retired");
            assertTrue(ec2.describeNetworkInterfaces(REGION, List.of(), Map.of()).networkInterfaces().isEmpty(),
                    "Real persisted EC2 allocation must be deleted");
            Files.writeString(directory.resolve("phase"), "recovered");
            return;
        }
        ec2.ensureDefaultResources(REGION);
        String subnet = ec2.describeSubnets(REGION, List.of(), Map.of()).getFirst().getSubnetId();
        String group = ec2.describeSecurityGroups(REGION, List.of(), List.of(), Map.of()).getFirst().getGroupId();
        ClassicLoadBalancer loadBalancer = new ClassicLoadBalancer();
        loadBalancer.setAccountId(ACCOUNT);
        loadBalancer.setRegion(REGION);
        loadBalancer.setLoadBalancerName("process-recovery");
        loadBalancer.setScheme("internal");
        loadBalancer.getSubnets().add(subnet);
        loadBalancer.getSecurityGroups().add(group);
        doAnswer(call -> {
            NetworkInterface eni = (NetworkInterface) call.callRealMethod();
            Files.writeString(directory.resolve("phase"), "allocated");
            if ("allocation".equals(mode)) {
                Runtime.getRuntime().halt(CRASH_EXIT);
            }
            return eni;
        }).when(ec2).createServiceNetworkInterface(eq(REGION), anyString(), anyString(), anyList(), anyString());
        when(firewall.createNamespace(anyString(), anyString(), anyString(), anyString(), any(), anyMap(), anyMap()))
                .thenAnswer(call -> {
                    Runtime.getRuntime().halt(CRASH_EXIT);
                    return null;
                });
        manager.reconcile(loadBalancer);
        throw new AssertionError("Expected crash boundary was not reached");
    }
}
