package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElbV2CfnTargetsTest {
    private static final String REGION = "us-east-1";
    private static final String ARN = "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/web/12345678";
    private final ElbV2Service service = mock(ElbV2Service.class);
    private final ElbV2CfnTargets targets = new ElbV2CfnTargets(service);
    private final TargetGroup group = new TargetGroup();
    private final StackResource resource = new StackResource();
    private final CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        group.setTargetGroupArn(ARN);
        group.setPort(8080);
        group.setTargetType("ip");
        resource.setPhysicalId(ARN);
        resource.setResourceType("AWS::ElasticLoadBalancingV2::TargetGroup");
        resource.setLogicalId("Targets");
        resource.setAttributes(new HashMap<>());
        when(engine.omitNoValue(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> {
            List<TargetDescription> rows = call.getArgument(2);
            for (TargetDescription row : rows) {
                group.getTargets().removeIf(old -> sameKey(old, row));
                group.getTargets().add(row);
            }
            return null;
        }).when(service).registerTargets(eq(REGION), eq(ARN), anyList());
        doAnswer(call -> {
            List<TargetDescription> rows = call.getArgument(2);
            group.getTargets().removeIf(old -> rows.stream().anyMatch(row -> sameKey(old, row)));
            return null;
        }).when(service).deregisterTargets(eq(REGION), eq(ARN), anyList());
    }

    @Test
    void updatesAndRemovalKeepRegistrationsFromOtherCallersAndOtherPorts() {
        TargetDescription outside = target("10.0.0.8", 9000, null);
        group.getTargets().add(outside);
        apply(List.of(target("10.0.0.8", null, null)), false);
        assertEquals(List.of(9000, 8080), group.getTargets().stream().map(TargetDescription::getPort).toList());

        apply(List.of(target("10.0.0.9", 8090, "all")), true);
        assertEquals(List.of(outside.getId(), "10.0.0.9"),
                     group.getTargets().stream().map(TargetDescription::getId).toList());
        assertEquals("all", group.getTargets().get(1).getAvailabilityZone());
        apply(List.of(), true);
        assertEquals(List.of(outside), group.getTargets());
    }

    @Test
    void rollbackRestoresChangedRegistrationWithoutRemovingAnUnrelatedLaterRegistration() {
        apply(List.of(target("10.0.0.8", null, "us-east-1a")), false);
        apply(List.of(target("10.0.0.8", 8080, "us-east-1b"), target("10.0.0.9", 8090, null)), true);
        TargetDescription outside = target("10.0.0.10", 8080, null);
        group.getTargets().add(outside);
        assertTrue(targets.restore(resource));
        assertEquals(2, group.getTargets().size());
        TargetDescription original = group.getTargets().stream()
                .filter(row -> row.getId().equals("10.0.0.8")).findFirst().orElseThrow();
        assertEquals(8080, original.getPort());
        assertEquals("us-east-1a", original.getAvailabilityZone());
        assertTrue(group.getTargets().contains(outside));
        assertFalse(resource.getAttributes().containsKey(CfnRollback.TARGET_GROUP_TARGETS_UPDATE_SNAPSHOT_ATTR));
        apply(List.of(), true);
        assertEquals(List.of(outside), group.getTargets());
    }

    @Test
    void failedRemovalCompensatesAndKeepsTheOriginalFailure() {
        apply(List.of(target("10.0.0.8", 8080, null)), false);
        AwsException failure = new AwsException("InternalFailure", "injected removal failure", 500);
        // The second removal is compensation of the newly added target; use the controlled boundary again.
        doAnswer(call -> {
            List<TargetDescription> rows = call.getArgument(2);
            if (rows.getFirst().getId().equals("10.0.0.8")) {
                throw failure;
            }
            group.getTargets().removeIf(old -> rows.stream().anyMatch(row -> sameKey(old, row)));
            return null;
        }).when(service).deregisterTargets(eq(REGION), eq(ARN), anyList());

        assertSame(failure, assertThrows(AwsException.class,
                () -> apply(List.of(target("10.0.0.9", 8080, null)), true)));
        assertEquals(List.of("10.0.0.8"), group.getTargets().stream().map(TargetDescription::getId).toList());
        assertFalse(resource.getAttributes().containsKey(CfnRollback.TARGET_GROUP_TARGETS_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void aFailedCompensationRetainsItsSnapshotForRecovery() {
        apply(List.of(target("10.0.0.8", 8080, null)), false);
        AwsException failure = new AwsException("InternalFailure", "registration failure", 500);
        doThrow(failure).when(service).registerTargets(eq(REGION), eq(ARN), anyList());
        assertSame(failure, assertThrows(AwsException.class,
                () -> apply(List.of(target("10.0.0.9", 8080, null)), true)));
        assertTrue(resource.getAttributes().containsKey(CfnRollback.TARGET_GROUP_TARGETS_UPDATE_SNAPSHOT_ATTR));
        assertTrue(resource.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void failedRegistrationDeletesTheNewTargetGroup() {
        AwsException failure = new AwsException("InvalidTarget", "invalid target", 400);
        doThrow(failure).when(service).registerTargets(eq(REGION), eq(ARN), anyList());
        assertSame(failure, assertThrows(AwsException.class, () -> targets.apply(resource, group,
                List.of(target("bad", 8080, null)), context(false), true)));
        verify(service).deleteTargetGroup(REGION, ARN);
    }

    @Test
    void replacingAGroupWithoutTargetsDoesNotInheritItsPreviousManagedRegistrations() {
        apply(List.of(target("10.0.0.8", 8080, null)), false);
        group.getTargets().clear();
        targets.apply(resource, group, List.of(), context(false), true);
        TargetDescription outside = target("10.0.0.8", 8080, null);
        group.getTargets().add(outside);
        apply(List.of(), true);
        assertEquals(List.of(outside), group.getTargets());
    }

    @Test
    void lambdaTargetsKeepAnAbsentPort() {
        group.setTargetType("lambda");
        targets.apply(resource, group, List.of(target("function", null, null)), context(false), true);
        assertNull(group.getTargets().getFirst().getPort());
    }

    @Test
    void schemaTypesAndRequiredIdFailBeforeAnyServiceCalls() throws Exception {
        for (String value : List.of("{\"Targets\":{}}", "{\"Targets\":[{}]}",
                "{\"Targets\":[{\"Id\":[],\"Port\":8080}]}", "{\"Targets\":[{\"Id\":\"i-1\",\"Port\":\"not-a-port\"}]}")) {
            JsonNode props = mapper.readTree(value);
            assertThrows(AwsException.class, () -> ElbV2CfnTargets.declared(props, context(false)), value);
        }
        verifyNoInteractions(service);
    }

    @Test
    void readsTheDeclaredTargetAndConvertsItsTextPort() throws Exception {
        JsonNode props = mapper.readTree("""
                {"Targets":[{"Id":"i-123","Port":"8080","AvailabilityZone":"all"}]}
                """);
        List<TargetDescription> declared = ElbV2CfnTargets.declared(props, context(false));
        assertEquals(1, declared.size());
        assertEquals("i-123", declared.getFirst().getId());
        assertEquals(8080, declared.getFirst().getPort());
        assertEquals("all", declared.getFirst().getAvailabilityZone());
        verifyNoInteractions(service);
    }

    private void apply(List<TargetDescription> requested, boolean update) {
        targets.apply(resource, group, requested, context(update), false);
    }

    private ProvisionContext context(boolean update) {
        return new ProvisionContext(engine, REGION, "000000000000", "stack", update ? ARN : null);
    }

    private static TargetDescription target(String id, Integer port, String zone) {
        TargetDescription target = new TargetDescription();
        target.setId(id);
        target.setPort(port);
        target.setAvailabilityZone(zone);
        return target;
    }

    private static boolean sameKey(TargetDescription first, TargetDescription second) {
        return first.getId().equals(second.getId()) && Objects.equals(first.getPort(), second.getPort());
    }
}
