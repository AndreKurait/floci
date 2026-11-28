package io.floci.aws.services.elb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.NetworkNamespaceLease;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.services.elb.model.ClassicListener;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import io.floci.aws.services.elb.model.ClassicLoadBalancerPolicy;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElbClassicPolicyReconciliationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final ElbClassicFrontendManager manager;
    private final ClassicLoadBalancer loadBalancer = new ClassicLoadBalancer();
    private final IllegalStateException allocationFailure = new IllegalStateException("Replacement allocation failed");

    ElbClassicPolicyReconciliationTest() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services().elb().vpcFrontends()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        SecurityGroupFirewallManager firewall = mock(SecurityGroupFirewallManager.class);
        when(firewall.enabled()).thenReturn(true);
        VpcNetworkManager networks = mock(VpcNetworkManager.class);
        when(networks.enabled()).thenReturn(true);
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(List.of());
        when(docker.inspectContainerCmd("helper").exec().getState().getRunning()).thenReturn(true);
        when(docker.inspectContainerCmd("proxy").exec().getState().getRunning()).thenReturn(true);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(AccountAwareStorageBackend.inMemory(ACCOUNT)).when(factory)
                .create(eq("elb"), eq("elb-classic-frontends.json"), any());
        manager = new ElbClassicFrontendManager(config, ec2, firewall, networks,
                mock(ContainerBuilder.class), lifecycle, new ObjectMapper(), mock(IamService.class),
                mock(AcmService.class), mock(VpcRoutingManager.class), factory);
        manager.initialize();
        loadBalancer.setAccountId(ACCOUNT);
        loadBalancer.setRegion(REGION);
        loadBalancer.setLoadBalancerName("policy-reload");
        loadBalancer.setScheme("internal");
        loadBalancer.setSubnets(List.of("subnet"));
        loadBalancer.setSecurityGroups(List.of("sg"));
        ClassicListener listener = new ClassicListener();
        listener.setProtocol("HTTPS");
        listener.setInstanceProtocol("HTTP");
        listener.setLoadBalancerPort(443);
        listener.setInstancePort(8080);
        listener.setSslCertificateId("certificate");
        loadBalancer.setListeners(List.of(listener));
        when(ec2.createServiceNetworkInterface(eq(REGION), eq("subnet"), anyString(), anyList(), anyString()))
                .thenThrow(allocationFailure);
        when(ec2.findServiceNetworkInterfaces(eq(REGION), anyString())).thenReturn(List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"attach", "detach", "replaceAttributes"})
    void effectivePolicyChangesAttemptReplacementAndKeepPreviousOnFailure(String change) throws Exception {
        if (!"attach".equals(change)) {
            assign("restricted", "ECDHE-RSA-AES128-GCM-SHA256");
        }
        installCurrent();
        if ("detach".equals(change)) {
            loadBalancer.setListenerPolicies(Map.of());
        } else {
            assign("restricted", "replaceAttributes".equals(change)
                    ? "ECDHE-RSA-AES256-GCM-SHA384" : "ECDHE-RSA-AES128-GCM-SHA256");
        }

        assertSame(allocationFailure, assertThrows(IllegalStateException.class, () -> manager.reconcile(loadBalancer)));
        verify(ec2).createServiceNetworkInterface(eq(REGION), eq("subnet"), anyString(), anyList(), anyString());
        assertEquals("10.0.1.10", manager.address(loadBalancer).orElseThrow());
        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), any(), anyInt());
        verify(ec2, never()).releaseNetworkInterfaceFromService(anyString(), anyString());
    }

    @Test
    void equivalentPolicyRenameDoesNotReplaceHealthyFrontend() throws Exception {
        assign("original", "ECDHE-RSA-AES128-GCM-SHA256");
        installCurrent();
        assign("renamed", "ECDHE-RSA-AES128-GCM-SHA256");

        assertFalse(manager.reconcile(loadBalancer));
        verifyNoInteractions(ec2);
    }

    @Test
    void unassignedPolicyDoesNotReplaceHealthyFrontend() throws Exception {
        installCurrent();
        loadBalancer.setPolicies(Map.of("unused", policy("unused", "ECDHE-RSA-AES128-GCM-SHA256")));

        assertFalse(manager.reconcile(loadBalancer));
        verifyNoInteractions(ec2);
    }

    @Test
    void missingAssignedPolicyFailsBeforeAllocationAndKeepsPrevious() throws Exception {
        installCurrent();
        loadBalancer.setListenerPolicies(Map.of(443, List.of("missing")));

        assertThrows(AwsException.class, () -> manager.reconcile(loadBalancer));
        verifyNoInteractions(ec2);
        assertEquals("10.0.1.10", manager.address(loadBalancer).orElseThrow());
        verify(lifecycle, never()).stopAndRemoveStrict(anyString(), any(), anyInt());
    }

    private void assign(String name, String cipher) {
        loadBalancer.setPolicies(Map.of(name, policy(name, cipher)));
        loadBalancer.setListenerPolicies(Map.of(443, List.of(name)));
    }

    private static ClassicLoadBalancerPolicy policy(String name, String cipher) {
        return ClassicSslPolicies.create(name, "SSLNegotiationPolicyType",
                Map.of("Protocol-TLSv1.2", "true", cipher, "true"));
    }

    @SuppressWarnings("unchecked")
    private void installCurrent() throws Exception {
        Method state = ElbClassicFrontendManager.class.getDeclaredMethod("state", ClassicLoadBalancer.class);
        state.setAccessible(true);
        String snapshot = (String) state.invoke(manager, loadBalancer);
        ElbClassicFrontendManager.Frontend frontend = new ElbClassicFrontendManager.Frontend(
                ACCOUNT, REGION, snapshot, "eni-current", "10.0.1.10",
                new NetworkNamespaceLease("helper", "helper-name"), "proxy", Map.of(), Map.of());
        Field field = ElbClassicFrontendManager.class.getDeclaredField("frontends");
        field.setAccessible(true);
        Map<String, ElbClassicFrontendManager.Frontends> frontends =
                (Map<String, ElbClassicFrontendManager.Frontends>) field.get(manager);
        frontends.put(ACCOUNT + "/" + REGION + "/policy-reload",
                new ElbClassicFrontendManager.Frontends(frontend.state(), List.of(frontend)));
    }
}
