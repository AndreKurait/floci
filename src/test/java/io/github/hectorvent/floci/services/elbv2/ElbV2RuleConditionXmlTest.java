package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** A condition value is text, so the characters XML reserves are escaped in the answer. */
class ElbV2RuleConditionXmlTest {

    private static final String REGION = "us-west-2";

    @Test
    void aConditionValueWithReservedCharactersIsEscapedInDescribeRules() {
        ElbV2Service service = ElbV2ServiceTest.serviceWithStorage(ElbV2ServiceTest.sharedStorageFactory(),
                mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String loadBalancerArn = service.createLoadBalancer(REGION, "escaped", "internal", "application", "ipv4",
                List.of("subnet-a", "subnet-b"), List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String listenerArn = service.createListener(REGION, loadBalancerArn, "HTTP", 80, null, List.of(),
                List.of(fixedResponse()), List.of(), Map.of()).getListenerArn();
        RuleCondition path = new RuleCondition();
        path.setField("path-pattern");
        path.setValues(List.of("/a&b"));
        path.setPathPatternValues(List.of("/a&b"));
        RuleCondition header = new RuleCondition();
        header.setField("http-header");
        header.setHttpHeaderName("X-Shape");
        header.setHttpHeaderValues(List.of("<round>"));
        Rule rule = service.createRule(REGION, listenerArn, List.of(path, header), 10, List.of(fixedResponse()),
                Map.of());
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RuleArns.member.1", rule.getRuleArn());

        Response response = new ElbV2QueryHandler(service).handle("DescribeRules", params, REGION);

        String xml = (String) response.getEntity();
        assertEquals(200, response.getStatus(), xml);
        assertThat(xml, containsString("<member>/a&amp;b</member>"));
        assertThat(xml, containsString("<member>&lt;round&gt;</member>"));
        assertThat(xml, not(containsString("/a&b")));
        assertThat(xml, not(containsString("<round>")));
    }

    private static Action fixedResponse() {
        Action action = new Action();
        action.setType("fixed-response");
        action.setFixedResponseStatusCode("200");
        return action;
    }
}
