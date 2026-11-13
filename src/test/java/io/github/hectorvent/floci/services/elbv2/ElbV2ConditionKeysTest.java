package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ElbV2ConditionKeysTest {

    private static final String ARN = "arn:aws:elasticloadbalancing:us-east-1:123456789012:targetgroup/blue/1";

    private ElbV2Service service;
    private RequestContext requestContext;
    private ElbV2ConditionKeys keys;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(ElbV2Service.class);
        Instance<ElbV2Service> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(service);
        requestContext = new RequestContext();
        requestContext.setAccountId("123456789012");
        requestContext.setRegion("us-east-1");
        EmulatorConfig config = mock(EmulatorConfig.class);
        keys = new ElbV2ConditionKeys(instance, requestContext, config);
    }

    @Test
    void readsTheNamedTargetGroupTags() {
        when(service.describeTags(List.of(ARN))).thenReturn(Map.of(ARN, Map.of("team", "blue")));

        assertEquals(List.of(Map.of("aws:ResourceTag/team", List.of("blue"))),
                keys.resourceContexts("elasticloadbalancing:DeregisterTargets", Map.of("TargetGroupArn", ARN)));
    }

    @Test
    void keepsUntaggedResourcesAsSeparateEmptyContexts() {
        String untagged = ARN.replace("/blue/", "/untagged/");
        when(service.describeTags(any())).thenReturn(Map.of(ARN, Map.of("team", "blue")));

        assertEquals(List.of(Map.of("aws:ResourceTag/team", List.of("blue")), Map.of()),
                keys.resourceContexts("elasticloadbalancing:DeregisterTargets", Map.of("ResourceArns.member.1", ARN,
                        "ResourceArns.member.2", untagged)));
    }

    @Test
    void doesNotReadAnotherAccountTags() {
        requestContext.setAccountId("999999999999");

        assertEquals(List.of(Map.of()), keys.resourceContexts("elasticloadbalancing:DeregisterTargets", Map.of("TargetGroupArn", ARN)));
        verifyNoInteractions(service);
    }

    @Test
    void doesNotReadAnotherRegionTags() {
        requestContext.setRegion("us-west-2");

        assertEquals(List.of(Map.of()), keys.resourceContexts("elasticloadbalancing:DeregisterTargets", Map.of("TargetGroupArn", ARN)));
        verifyNoInteractions(service);
    }

    @Test
    void doesNotReadAnotherPartitionTags() {
        String foreign = ARN.replace("arn:aws:", "arn:aws-cn:");

        assertEquals(List.of(Map.of()), keys.resourceContexts("elasticloadbalancing:DeregisterTargets", Map.of("TargetGroupArn", foreign)));
        verifyNoInteractions(service);
    }

    @Test
    void doesNotReadMalformedArns() {
        assertEquals(List.of(Map.of()), keys.resourceContexts("elasticloadbalancing:DeregisterTargets",
                Map.of("TargetGroupArn", "not-an-arn")));
        verifyNoInteractions(service);
    }

    @Test
    void creationLeavesNewResourceTagsToTheGenericResolver() {
        assertEquals(List.of(), keys.resourceContexts("elasticloadbalancing:CreateListener",
                Map.of("LoadBalancerArn", ARN)));
        verifyNoInteractions(service);
    }
}
