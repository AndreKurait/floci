package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElbClassicCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ElbClassicService service = mock(ElbClassicService.class);
    private final ElbClassicCfnProvisioner provisioner = new ElbClassicCfnProvisioner(service);
    private final StackResource resource = new StackResource();
    private final ClassicLoadBalancer loadBalancer = new ClassicLoadBalancer();

    @BeforeEach
    void setup() {
        resource.setLogicalId("Web");
        resource.setResourceType("AWS::ElasticLoadBalancing::LoadBalancer");
        loadBalancer.setLoadBalancerName("web");
        loadBalancer.setScheme("internet-facing");
        loadBalancer.setDnsName("web.example.test");
        loadBalancer.setCanonicalHostedZoneName("web.example.test");
        loadBalancer.setCanonicalHostedZoneNameId("test-zone");
        loadBalancer.setHealthCheck(ClassicHealthCheck.defaults());
        ClassicListener listener = new ClassicListener();
        listener.setProtocol("HTTP");
        listener.setLoadBalancerPort(80);
        listener.setInstancePort(8080);
        loadBalancer.setListeners(List.of(listener));
        when(service.requireLoadBalancer(REGION, "web")).thenReturn(loadBalancer);
        when(service.createLoadBalancer(eq(REGION), eq("web"), anyList(), anyList(),
                anyList(), anyList(), anyString(), anyMap())).thenReturn(loadBalancer);
    }

    @Test
    void failedCreateCompensatesOnlyItsNewLoadBalancer() {
        RuntimeException failure = new AwsException("ValidationError", "bad attributes", 400);
        doThrow(failure).when(service).modifyLoadBalancerAttributes(eq(REGION), eq("web"), any());

        assertSame(failure, assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties(), context(null, null))));

        verify(service).deleteLoadBalancer(REGION, "web");
        verify(service, never()).restoreLoadBalancerConfiguration(anyString(), anyString(), any());
        assertFalse(resource.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void failedCompensationKeepsOwnedReplacementForRetry() {
        RuntimeException failure = new AwsException("ValidationError", "bad attributes", 400);
        RuntimeException cleanup = new AwsException("InternalFailure", "cleanup refused", 500);
        resource.setPhysicalId("old");
        doThrow(failure).when(service).modifyLoadBalancerAttributes(eq(REGION), eq("web"), any());
        doThrow(cleanup).when(service).deleteLoadBalancer(REGION, "web");

        assertSame(failure, assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties(), context("old", properties()))));

        assertEquals(List.of(cleanup), List.of(failure.getSuppressed()));
        assertEquals("true", resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertTrue(resource.getAttributes().get(CfnRollback.REPLACEMENT_CLEANUP_ATTR).contains("web"));
        verify(service, never()).deleteLoadBalancer(REGION, "old");
    }

    @Test
    void failedInPlaceUpdateRestoresSnapshotWithoutDeletingTheResource() {
        when(service.hasLoadBalancer(REGION, "web")).thenReturn(true);
        resource.setPhysicalId("web");
        RuntimeException failure = new AwsException("ValidationError", "bad attributes", 400);
        doThrow(failure).when(service).modifyLoadBalancerAttributes(eq(REGION), eq("web"), any());

        assertSame(failure, assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties(), context("web", properties()))));

        verify(service).restoreLoadBalancerConfiguration(eq(REGION), eq("web"), argThat(previous ->
                previous.getHealthCheck().getTarget().equals(loadBalancer.getHealthCheck().getTarget())
                        && previous.getListeners().getFirst().getInstancePort() == 8080));
        verify(service, never()).deleteLoadBalancer(anyString(), anyString());
    }

    @Test
    void failedSnapshotRestoreIsRetainedAndCanBeRetried() {
        when(service.hasLoadBalancer(REGION, "web")).thenReturn(true);
        resource.setPhysicalId("web");
        doThrow(new AwsException("ValidationError", "bad attributes", 400))
                .when(service).modifyLoadBalancerAttributes(eq(REGION), eq("web"), any());
        doThrow(new AwsException("InternalFailure", "restore refused", 500)).doNothing()
                .when(service).restoreLoadBalancerConfiguration(eq(REGION), eq("web"), any());

        assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties(), context("web", properties())));
        resource.setStatus("UPDATE_FAILED");
        assertTrue(provisioner.requiresUpdateRecovery(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.requiresUpdateRecovery(resource));
    }

    @Test
    void duplicateNameDoesNotBecomeOwnedByTheFailedStack() {
        when(service.createLoadBalancer(eq(REGION), eq("web"), anyList(), anyList(),
                anyList(), anyList(), anyString(), anyMap()))
                .thenThrow(new AwsException("DuplicateLoadBalancerName", "already exists", 400));

        assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties(), context(null, null)));

        verify(service, never()).deleteLoadBalancer(anyString(), anyString());
        assertNull(resource.getPhysicalId());
    }

    @Test
    void unsupportedBackendPolicyFailsBeforeAllocation() {
        ObjectNode properties = properties();
        properties.putArray("Policies").addObject().put("PolicyName", "backend")
                .put("PolicyType", "ProxyProtocolPolicyType");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties, context(null, null)));

        assertEquals("UnsupportedOperation", failure.getErrorCode());
        verifyNoInteractions(service);
    }

    private static ObjectNode properties() {
        ObjectNode properties = MAPPER.createObjectNode().put("LoadBalancerName", "web");
        properties.putArray("Listeners").addObject().put("Protocol", "HTTP")
                .put("LoadBalancerPort", 80).put("InstancePort", 8080);
        return properties;
    }

    private static ProvisionContext context(String prior, JsonNode priorProperties) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(call -> {
            JsonNode node = call.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.resolveStringList(any())).thenCallRealMethod();
        return new ProvisionContext(engine, REGION, "000000000000", "test-stack", prior, event -> {}, priorProperties);
    }
}
