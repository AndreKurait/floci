package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** ModifyRule moves the "in use" mark with the rule: onto the group it now forwards to, off the one it left. */
class ElbV2ModifyRuleTargetGroupTest {

    private static final String REGION = "us-west-2";

    @Test
    void theGroupARuleIsMovedToIsInUseAndTheOneItLeftCanBeDeleted() {
        ElbV2Service service = ElbV2ServiceTest.serviceWithStorage(ElbV2ServiceTest.sharedStorageFactory(),
                mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String loadBalancerArn = service.createLoadBalancer(REGION, "moved-rule", "internal", "application", "ipv4",
                List.of("subnet-a", "subnet-b"), List.of("sg-a"), Map.of()).getLoadBalancerArn();
        Action fixedResponse = new Action();
        fixedResponse.setType("fixed-response");
        fixedResponse.setFixedResponseStatusCode("200");
        String listenerArn = service.createListener(REGION, loadBalancerArn, "HTTP", 80, null, List.of(),
                List.of(fixedResponse), List.of(), Map.of()).getListenerArn();
        String left = targetGroup(service, "left");
        String movedTo = targetGroup(service, "moved-to");
        RuleCondition path = new RuleCondition();
        path.setField("path-pattern");
        path.setValues(List.of("/a/*"));
        path.setPathPatternValues(List.of("/a/*"));
        Rule rule = service.createRule(REGION, listenerArn, List.of(path), 10, List.of(forward(left)), Map.of());

        service.modifyRule(REGION, rule.getRuleArn(), null, List.of(forward(movedTo)));

        AwsException inUse = assertThrows(AwsException.class, () -> service.deleteTargetGroup(REGION, movedTo));
        assertEquals("ResourceInUse", inUse.getErrorCode());
        service.deleteTargetGroup(REGION, left);
        assertEquals(List.of("moved-to"), service.describeTargetGroups(REGION, null, List.of(), List.of()).stream()
                .map(TargetGroup::getTargetGroupName).toList());
    }

    private static String targetGroup(ElbV2Service service, String name) {
        return service.createTargetGroup(REGION, name, "HTTP", "HTTP1", 9999, "vpc-a", "instance", "HTTP",
                "traffic-port", true, "/ready", 30, 5, 5, 2, "200", "ipv4", Map.of()).getTargetGroupArn();
    }

    private static Action forward(String targetGroupArn) {
        Action action = new Action();
        action.setType("forward");
        action.setTargetGroupArn(targetGroupArn);
        return action;
    }
}
