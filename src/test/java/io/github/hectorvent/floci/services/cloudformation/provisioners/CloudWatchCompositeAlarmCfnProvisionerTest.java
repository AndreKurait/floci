package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudWatchCompositeAlarmCfnProvisionerTest {
    @Test
    void rejectedBackingUpdateDoesNotArmRollbackToDeleteAnUnmanagedReplacement() throws Exception {
        CloudWatchMetricsService service = mock(CloudWatchMetricsService.class);
        CloudWatchCompositeAlarmCfnProvisioner provisioner = new CloudWatchCompositeAlarmCfnProvisioner(service);
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(call -> {
            JsonNode node = call.getArgument(0);
            return node == null || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        StackResource resource = new StackResource();
        resource.setLogicalId("Alarm");
        resource.setResourceType("AWS::CloudWatch::CompositeAlarm");
        resource.setPhysicalId("kept");
        resource.getAttributes().put("Arn", "prior-arn");
        AwsException collision = new AwsException("ValidationError", "A metric alarm now has this name", 400);
        doThrow(collision).when(service).putCompositeAlarm(any(), eq("us-east-1"), eq(true));
        ProvisionContext context = new ProvisionContext(engine, "us-east-1", "000000000000", "stack", "kept");
        JsonNode props = new ObjectMapper().readTree("{\"AlarmName\":\"kept\",\"AlarmRule\":\"TRUE\"}");
        assertSame(collision, assertThrows(AwsException.class, () -> provisioner.provision(resource, props, context)));
        assertFalse(provisioner.retainsFailedUpdateState(resource));
        assertFalse(provisioner.rollbackUpdate(resource));
        assertEquals("kept", resource.getPhysicalId());
        assertEquals(Map.of("Arn", "prior-arn"), resource.getAttributes());
        verify(service, never()).deleteAlarms(any(), anyString());
    }
}
