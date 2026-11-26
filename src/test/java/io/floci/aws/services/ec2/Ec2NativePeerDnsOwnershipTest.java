package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.ec2.model.SecurityGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2NativePeerDnsOwnershipTest {
    private static final String HOST = "api.peer.example";
    private static final Ec2NativePeerDependencies POLICY = new Ec2NativePeerDependencies("""
            [{"hostname":"api.peer.example","address":"172.18.0.2","port":443}]
            """);

    @Test
    void dnsIsPublishedOnlyForTheExactAdmittedEc2Endpoint() {
        SecurityGroupFirewallManager manager = manager();
        assertTrue(manager.controlDependency(HOST, 1, "172.18.0.3").isEmpty());
        manager.register(endpoint("eni-ec2", "172.18.0.3", true), "helper-ec2", Map.of());
        manager.register(endpoint("eni-lambda", "172.18.0.4", false), "helper-lambda", Map.of());
        assertEquals(List.of("172.18.0.2"),
                manager.controlDependency(HOST, 1, "172.18.0.3").orElseThrow().addresses());
        assertTrue(manager.controlDependency(HOST, 1, "172.18.0.4").isEmpty(),
                "a non-EC2 endpoint with the same literal control connection has no native peer DNS authority");
        assertTrue(manager.controlDependency(HOST, 1, "172.18.0.99").isEmpty());
        assertTrue(manager.controlDependency("sibling." + HOST, 1, "172.18.0.3").isEmpty());
        assertTrue(manager.controlDependency(HOST, 1, null).isEmpty());
    }

    @Test
    void replacingTheRegisteredOwnerDoesNotRetainItsDnsAdmission() {
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint("eni-ec2", "172.18.0.3", true), "helper", Map.of());
        manager.register(endpoint("eni-ec2", "172.18.0.3", false), "helper", Map.of());
        assertTrue(manager.controlDependency(HOST, 1, "172.18.0.3").isEmpty());
    }

    @Test
    void securityGroupUpdateRetainsTheSameDeclaredConnectionAndDns() {
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint("eni-ec2", "172.18.0.3", true), "helper", Map.of());
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg-new");
        manager.updateGroups("eni-ec2", Set.of("sg-new"), Map.of("sg-new", group), Map.of());
        assertEquals(List.of("172.18.0.2"),
                manager.controlDependency(HOST, 1, "172.18.0.3").orElseThrow().addresses());
    }

    @Test
    void unregisteredNamespaceCannotKeepUsingTheDependency() {
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint("eni-ec2", "172.18.0.3", true), "helper", Map.of());
        manager.unregister("eni-ec2");
        assertTrue(manager.controlDependency(HOST, 1, "172.18.0.3").isEmpty());
    }

    @Test
    void expiredObservedNamespaceLosesItsDnsAuthority() {
        SecurityGroupFirewallManager manager = manager();
        manager.register(endpoint("eni-ec2", "172.18.0.3", true), "helper", Map.of());
        assertTrue(manager.controlDependency(HOST, 1, "172.18.0.3").isPresent());
        manager.retireDnsOrigins();
        assertTrue(manager.controlDependency(HOST, 1, "172.18.0.3").isEmpty(),
                "the missing helper in the next Docker observation must withdraw its dependency");
    }

    private static SecurityGroupFirewallManager manager() {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("nft-exec");
        when(create.exec()).thenReturn(created);
        when(docker.execCreateCmd(anyString())).thenReturn(create);
        when(docker.execStartCmd("nft-exec").exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        });
        when(docker.inspectExecCmd("nft-exec").exec().getExitCodeLong()).thenReturn(0L);
        return SecurityGroupDnsFixture.manager(docker,
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
    }

    private static SecurityGroupNftCompiler.Endpoint endpoint(String eni, String address, boolean nativeEc2) {
        return new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-native", eni,
                "10.0.1." + (nativeEc2 ? "10" : "11"), address, Set.of("sg"), List.of(),
                POLICY.connections(), nativeEc2, Set.of(), List.of(),
                nativeEc2 ? POLICY.dependencies() : Map.of());
    }
}
