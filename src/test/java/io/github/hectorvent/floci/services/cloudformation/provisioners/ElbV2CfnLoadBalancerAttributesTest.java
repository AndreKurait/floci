package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElbV2CfnLoadBalancerAttributesTest {
    private static final String REGION = "us-east-1";
    private static final String ARN = "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/web/12345678";
    private static final String HOST = "routing.http.preserve_host_header.enabled";
    private static final String SNAPSHOT = CfnRollback.LOAD_BALANCER_ATTRIBUTES_UPDATE_SNAPSHOT_ATTR;
    private final ElbV2Service service = mock(ElbV2Service.class);
    private final CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
    private final ElbV2CfnLoadBalancerAttributes attributes = new ElbV2CfnLoadBalancerAttributes(service);
    private final StackResource resource = new StackResource();
    private final Map<String, String> live = new HashMap<>(Map.of(HOST, "false", "idle_timeout.timeout_seconds", "60"));
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        resource.setLogicalId("Balancer");
        resource.setResourceType("AWS::ElasticLoadBalancingV2::LoadBalancer");
        resource.setPhysicalId(ARN);
        resource.setAttributes(new HashMap<>());
        when(engine.omitNoValue(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        when(service.describeLoadBalancerAttributes(REGION, ARN)).thenAnswer(call -> new HashMap<>(live));
        doAnswer(call -> {
            live.putAll(call.getArgument(2));
            return null;
        }).when(service).modifyLoadBalancerAttributes(eq(REGION), eq(ARN), anyMap());
        doAnswer(call -> {
            Map<String, String> previous = call.getArgument(2);
            previous.forEach((key, value) -> {
                if (value == null) {
                    live.remove(key);
                } else {
                    live.put(key, value);
                }
            });
            return null;
        }).when(service).restoreLoadBalancerAttributes(eq(REGION), eq(ARN), anyMap());
    }

    @Test
    void parsesBooleanValuesAsStringsAndRejectsInvalidRowsBeforeCallingTheService() throws Exception {
        Map<String, String> declared = ElbV2CfnLoadBalancerAttributes.declared(mapper.readTree("""
                {"LoadBalancerAttributes":[{"Key":"routing.http.preserve_host_header.enabled","Value":true}]}
                """), context(false));
        assertEquals(Map.of(HOST, "true"), declared);
        for (String value : List.of("{}", "[{}]", "[{\"Key\":\"key\"}]", "[{\"Key\":[],\"Value\":true}]")) {
            assertThrows(AwsException.class, () -> ElbV2CfnLoadBalancerAttributes.declared(
                    mapper.readTree("{\"LoadBalancerAttributes\":" + value + "}"), context(false)), value);
        }
        verifyNoInteractions(service);
    }

    @Test
    void omittedAttributesKeepAllCurrentValues() {
        attributes.apply(resource, Map.of(), context(true), false, () -> fail("Unexpected subnet restore"));
        assertEquals("false", live.get(HOST));
        verifyNoInteractions(service);
    }

    @Test
    void rollbackRestoresOnlyChangedKeysAndRemovesPreviouslyAbsentValues() {
        attributes.apply(resource, Map.of(HOST, "true", "future.attribute", "value"),
                context(true), false, () -> fail("Unexpected subnet restore"));
        live.put("idle_timeout.timeout_seconds", "120");
        attributes.restore(resource);
        assertEquals(Map.of(HOST, "false", "idle_timeout.timeout_seconds", "120"), live);
        assertFalse(resource.getAttributes().containsKey(SNAPSHOT));
    }

    @Test
    void failedWriteRestoresAttributesAndTheAlreadyChangedSubnets() {
        AwsException failure = new AwsException("InternalFailure", "write failed", 500);
        doAnswer(call -> {
            live.put(HOST, "true");
            throw failure;
        }).when(service).modifyLoadBalancerAttributes(eq(REGION), eq(ARN), anyMap());
        AtomicBoolean restoredSubnets = new AtomicBoolean();
        assertSame(failure, assertThrows(AwsException.class, () -> attributes.apply(resource, Map.of(HOST, "true"),
                context(true), false, () -> restoredSubnets.set(true))));
        assertEquals("false", live.get(HOST));
        assertTrue(restoredSubnets.get());
        assertFalse(resource.getAttributes().containsKey(SNAPSHOT));
    }

    @Test
    void failureToReadAttributesStillRestoresAlreadyChangedSubnets() {
        AwsException failure = new AwsException("InternalFailure", "read failed", 500);
        when(service.describeLoadBalancerAttributes(REGION, ARN)).thenThrow(failure);
        AtomicBoolean restoredSubnets = new AtomicBoolean();
        assertSame(failure, assertThrows(AwsException.class, () -> attributes.apply(resource, Map.of(HOST, "true"),
                context(true), false, () -> restoredSubnets.set(true))));
        assertTrue(restoredSubnets.get());
        verify(service, never()).modifyLoadBalancerAttributes(anyString(), anyString(), anyMap());
    }

    @Test
    void failedRestoreRetainsItsSnapshotAndRecoveryReason() {
        AwsException failure = new AwsException("InternalFailure", "write failed", 500);
        doThrow(failure).when(service).modifyLoadBalancerAttributes(eq(REGION), eq(ARN), anyMap());
        doThrow(failure).when(service).restoreLoadBalancerAttributes(eq(REGION), eq(ARN), anyMap());
        assertSame(failure, assertThrows(AwsException.class, () -> attributes.apply(resource, Map.of(HOST, "true"),
                context(true), false, () -> fail("Restore must retry attributes first"))));
        assertTrue(resource.getAttributes().containsKey(SNAPSHOT));
        assertTrue(resource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void failedAttributesOnCreateRemoveTheNewLoadBalancer() {
        AwsException failure = new AwsException("ValidationError", "write failed", 400);
        doThrow(failure).when(service).modifyLoadBalancerAttributes(eq(REGION), eq(ARN), anyMap());
        assertSame(failure, assertThrows(AwsException.class, () -> attributes.apply(resource, Map.of(HOST, "true"),
                context(false), true, () -> fail("No prior load balancer"))));
        verify(service).deleteLoadBalancer(REGION, ARN);
    }

    @Test
    void failedUpdateTrackingCarriesAttributeAndSubnetSnapshotsForRetry() {
        resource.getAttributes().put(SNAPSHOT, "{\"key\":\"before\"}");
        resource.getAttributes().put(CfnRollback.LOAD_BALANCER_SUBNETS_UPDATE_SNAPSHOT_ATTR, "subnet-before");
        resource.setStatus("UPDATE_FAILED");
        ElbV2CfnProvisioner provisioner = new ElbV2CfnProvisioner(service);
        assertTrue(provisioner.requiresUpdateRecovery(resource));
        StackResource previous = new StackResource();
        previous.setAttributes(new HashMap<>());
        provisioner.mergeFailedUpdateResourceTracking(previous, resource);
        assertEquals(resource.getAttributes(), previous.getAttributes());
        provisioner.clearUpdate(resource);
        assertFalse(provisioner.requiresUpdateRecovery(resource));
    }

    private ProvisionContext context(boolean update) {
        return new ProvisionContext(engine, REGION, "000000000000", "stack", update ? ARN : null);
    }
}
