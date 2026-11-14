package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.insights.CloudWatchInsightRulesService;
import io.github.hectorvent.floci.services.cloudwatch.insights.model.InsightRule;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudWatchInsightRuleCfnProvisionerTest {
    private static final String NAME = "example-rule";
    private static final String ARN = "arn:aws:cloudwatch:us-east-1:111122223333:insight-rule/example-rule";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CloudWatchInsightRulesService service = mock(CloudWatchInsightRulesService.class);
    private final CloudWatchInsightRuleCfnProvisioner provisioner = new CloudWatchInsightRuleCfnProvisioner(service);

    @Test
    void arnRefAndBothGetAttNamesComeFromTheOwnedNativeRecord() {
        StackResource resource = resource();
        when(service.arn(NAME, "us-east-1")).thenReturn(ARN);
        when(service.putOwned(eq(NAME), eq("{}"), eq("DISABLED"), eq(false), eq(Map.of("team", "example")),
                eq(Set.of()), anyString(), eq(false), any(), eq("us-east-1"))).thenAnswer(invocation ->
                    new InsightRule(NAME, ARN, "{}", "DISABLED", Map.of("team", "example"),
                            invocation.getArgument(6)));
        ObjectNode props = properties();
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "example");

        provisioner.provision(resource, props, context(null));

        assertEquals(Set.of("AWS::CloudWatch::InsightRule"), provisioner.resourceTypes());
        assertEquals(ARN, resource.getPhysicalId());
        assertEquals(ARN, resource.getAttributes().get("Arn"));
        assertEquals(NAME, resource.getAttributes().get("RuleName"));
        assertFalse(resource.getAttributes().get("FlociInsightRuleOwner").isEmpty());
        provisioner.delete(resource, "us-east-1");
        verify(service).deleteOwned(ARN, resource.getAttributes().get("FlociInsightRuleOwner"), "us-east-1");
        assertThrows(IllegalStateException.class,
                () -> provisioner.delete("AWS::CloudWatch::InsightRule", ARN, "us-east-1"));
    }

    @Test
    void missingRequiredPropertyFailsBeforeAnyNativeCall() {
        for (String required : Set.of("RuleName", "RuleBody", "RuleState")) {
            ObjectNode props = properties();
            props.remove(required);
            AwsException error = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource(), props, context(null)));
            assertEquals("ValidationError", error.getErrorCode());
        }
        verifyNoInteractions(service);
    }

    @Test
    void failedInPlaceUpdateRetainsTheCompletePreviousRecordAndAttributesForRollback() {
        StackResource resource = resource();
        resource.setPhysicalId(ARN);
        Map<String, String> before = Map.of("Arn", ARN, "RuleName", NAME,
                "FlociInsightRuleOwner", "owner", "FlociInsightRuleTagKeys", "[\"old\"]");
        resource.getAttributes().putAll(before);
        InsightRule previous = new InsightRule(NAME, ARN, "old-body", "DISABLED", Map.of("old", "value"), "owner");
        when(service.arn(NAME, "us-east-1")).thenReturn(ARN);
        when(service.putOwned(eq(NAME), eq("{}"), eq("DISABLED"), eq(false), eq(Map.of()),
                eq(Set.of("old")), eq("owner"), eq(true), any(), eq("us-east-1"))).thenAnswer(invocation -> {
                    Consumer<InsightRule> beforeCommit = invocation.getArgument(8);
                    beforeCommit.accept(previous);
                    throw new IllegalStateException("injected commit failure");
                });

        assertThrows(IllegalStateException.class,
                () -> provisioner.provision(resource, properties(), context(ARN)));
        assertTrue(provisioner.retainsFailedUpdateState(resource));
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(service).restoreOwned(NAME, previous, "owner", "us-east-1");
        assertEquals(before, resource.getAttributes());
        assertEquals(ARN, resource.getPhysicalId());
    }

    private ObjectNode properties() {
        return mapper.createObjectNode().put("RuleName", NAME).put("RuleBody", "{}").put("RuleState", "DISABLED");
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Rule");
        resource.setResourceType("AWS::CloudWatch::InsightRule");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private ProvisionContext context(String previous) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "111122223333", "example-stack", previous);
    }
}
