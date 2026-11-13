package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** The rules of a listener go with it, and so do their tags. */
class ElbV2DeletedListenerRuleTagsTest {

    private static final String REGION = "us-west-2";

    private ElbV2Service service;
    private String loadBalancerArn;
    private String listenerArn;
    private String ruleArn;

    @BeforeEach
    void setUp() {
        service = ElbV2ServiceTest.serviceWithStorage(ElbV2ServiceTest.sharedStorageFactory(),
                mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        loadBalancerArn = service.createLoadBalancer(REGION, "tagged-rule", "internal", "application", "ipv4",
                List.of("subnet-a", "subnet-b"), List.of("sg-a"), Map.of()).getLoadBalancerArn();
        Action fixedResponse = new Action();
        fixedResponse.setType("fixed-response");
        fixedResponse.setFixedResponseStatusCode("200");
        listenerArn = service.createListener(REGION, loadBalancerArn, "HTTP", 80, null, List.of(),
                List.of(fixedResponse), List.of(), Map.of()).getListenerArn();
        RuleCondition path = new RuleCondition();
        path.setField("path-pattern");
        path.setValues(List.of("/a/*"));
        path.setPathPatternValues(List.of("/a/*"));
        Rule rule = service.createRule(REGION, listenerArn, List.of(path), 10, List.of(fixedResponse),
                Map.of("team", "edge"));
        ruleArn = rule.getRuleArn();
        assertEquals(Map.of("team", "edge"), service.describeTags(List.of(ruleArn)).get(ruleArn));
    }

    @Test
    void deletingAListenerDeletesTheTagsOfItsRules() {
        service.deleteListener(REGION, listenerArn);

        assertEquals(Map.of(), service.describeTags(List.of(ruleArn)).get(ruleArn));
    }

    @Test
    void deletingALoadBalancerDeletesTheTagsOfItsRules() {
        service.deleteLoadBalancer(REGION, loadBalancerArn);

        assertEquals(Map.of(), service.describeTags(List.of(ruleArn)).get(ruleArn));
    }
}
