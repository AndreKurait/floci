package io.github.hectorvent.floci.services.elbv2;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** A parameter that must be a number and is not one is the caller's mistake: ValidationError, not a server fault. */
class ElbV2NumberParameterTest {

    private static final String REGION = "us-west-2";

    @ParameterizedTest
    @CsvSource({
            "CreateTargetGroup, Port",
            "CreateTargetGroup, HealthCheckIntervalSeconds",
            "CreateTargetGroup, HealthCheckTimeoutSeconds",
            "CreateTargetGroup, HealthyThresholdCount",
            "CreateTargetGroup, UnhealthyThresholdCount",
            "ModifyTargetGroup, HealthCheckIntervalSeconds",
            "CreateListener, Port",
            "ModifyListener, Port",
            "CreateRule, Priority",
            "CreateRule, Actions.member.1.Order",
            "CreateRule, Actions.member.1.ForwardConfig.TargetGroups.member.1.Weight",
            "CreateRule, Actions.member.1.ForwardConfig.TargetGroupStickinessConfig.DurationSeconds",
            "SetRulePriorities, RulePriorities.member.1.Priority",
            "RegisterTargets, Targets.member.1.Port",
            "DeregisterTargets, Targets.member.1.Port"})
    void aNumberThatIsNotOneIsAnsweredWithValidationError(String action, String parameter) {
        ElbV2Service service = mock(ElbV2Service.class);
        MultivaluedMap<String, String> params = request();
        params.putSingle(parameter, "eighty");

        Response response = new ElbV2QueryHandler(service).handle(action, params, REGION);

        assertEquals(400, response.getStatus());
        assertThat(response.getEntity().toString(), containsString("<Code>ValidationError</Code>"));
        assertThat(response.getEntity().toString(), containsString("eighty&apos; is not a valid integer."));
        verifyNoInteractions(service);
    }

    @Test
    void aRulePriorityThatIsLeftOutIsAnsweredWithValidationError() {
        ElbV2Service service = mock(ElbV2Service.class);
        MultivaluedMap<String, String> params = request();
        params.remove("RulePriorities.member.1.Priority");

        Response response = new ElbV2QueryHandler(service).handle("SetRulePriorities", params, REGION);

        assertEquals(400, response.getStatus());
        assertThat(response.getEntity().toString(), containsString("<Code>ValidationError</Code>"));
        verifyNoInteractions(service);
    }

    private static MultivaluedMap<String, String> request() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("Name", "numbers");
        params.putSingle("Protocol", "HTTP");
        params.putSingle("VpcId", "vpc-a");
        params.putSingle("TargetGroupArn", "target-group-arn");
        params.putSingle("LoadBalancerArn", "load-balancer-arn");
        params.putSingle("ListenerArn", "listener-arn");
        params.putSingle("Priority", "10");
        params.putSingle("Conditions.member.1.Field", "path-pattern");
        params.putSingle("Conditions.member.1.Values.member.1", "/a/*");
        params.putSingle("Actions.member.1.Type", "forward");
        params.putSingle("Actions.member.1.ForwardConfig.TargetGroups.member.1.TargetGroupArn", "target-group-arn");
        params.putSingle("DefaultActions.member.1.Type", "forward");
        params.putSingle("DefaultActions.member.1.TargetGroupArn", "target-group-arn");
        params.putSingle("RulePriorities.member.1.RuleArn", "rule-arn");
        params.putSingle("RulePriorities.member.1.Priority", "10");
        params.putSingle("Targets.member.1.Id", "i-0123456789abcdef0");
        return params;
    }
}
