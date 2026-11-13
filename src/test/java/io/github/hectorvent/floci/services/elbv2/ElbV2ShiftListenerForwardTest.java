package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** A traffic shift is a change of the listener's default action: the listener shows it and the in-use marks follow. */
class ElbV2ShiftListenerForwardTest {

    private static final String REGION = "us-west-2";

    @Test
    void aFullShiftIsShownByTheListenerAndMovesTheInUseMarkToTheNewGroup() {
        ElbV2Service service = ElbV2ServiceTest.serviceWithStorage(ElbV2ServiceTest.sharedStorageFactory(),
                mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String blue = targetGroup(service, "blue");
        String green = targetGroup(service, "green");
        String listenerArn = listenerForwardingTo(service, blue);

        service.shiftListenerForward(REGION, listenerArn, blue, green, 100);

        Listener listener = service.describeListeners(REGION, null, List.of(listenerArn)).get(0);
        assertEquals(List.of(green), listener.getDefaultActions().stream().map(Action::getTargetGroupArn).toList());
        AwsException inUse = assertThrows(AwsException.class, () -> service.deleteTargetGroup(REGION, green));
        assertEquals("ResourceInUse", inUse.getErrorCode());
        service.deleteTargetGroup(REGION, blue);
        assertEquals(List.of("green"), service.describeTargetGroups(REGION, null, List.of(), List.of()).stream()
                .map(TargetGroup::getTargetGroupName).toList());
    }

    @Test
    void aPartialShiftKeepsBothGroupsInUseAndShowsBothWeights() {
        ElbV2Service service = ElbV2ServiceTest.serviceWithStorage(ElbV2ServiceTest.sharedStorageFactory(),
                mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String blue = targetGroup(service, "blue");
        String green = targetGroup(service, "green");
        String listenerArn = listenerForwardingTo(service, blue);

        service.shiftListenerForward(REGION, listenerArn, blue, green, 10);

        Listener listener = service.describeListeners(REGION, null, List.of(listenerArn)).get(0);
        List<Action.TargetGroupTuple> shown = listener.getDefaultActions().get(0).getTargetGroups();
        assertEquals(List.of(blue, green), shown.stream().map(Action.TargetGroupTuple::getTargetGroupArn).toList());
        assertEquals(List.of(90, 10), shown.stream().map(Action.TargetGroupTuple::getWeight).toList());
        for (String targetGroupArn : List.of(blue, green)) {
            AwsException inUse = assertThrows(AwsException.class,
                    () -> service.deleteTargetGroup(REGION, targetGroupArn));
            assertEquals("ResourceInUse", inUse.getErrorCode());
        }
    }

    private static String listenerForwardingTo(ElbV2Service service, String targetGroupArn) {
        String loadBalancerArn = service.createLoadBalancer(REGION, "shifted", "internal", "application", "ipv4",
                List.of("subnet-a", "subnet-b"), List.of("sg-a"), Map.of()).getLoadBalancerArn();
        Action forward = new Action();
        forward.setType("forward");
        forward.setTargetGroupArn(targetGroupArn);
        return service.createListener(REGION, loadBalancerArn, "HTTP", 80, null, List.of(),
                List.of(forward), List.of(), Map.of()).getListenerArn();
    }

    private static String targetGroup(ElbV2Service service, String name) {
        return service.createTargetGroup(REGION, name, "HTTP", "HTTP1", 9999, "vpc-a", "instance", "HTTP",
                "traffic-port", true, "/ready", 30, 5, 5, 2, "200", "ipv4", Map.of()).getTargetGroupArn();
    }
}
