package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ElbClassicNodeSelectionTest {
    @ParameterizedTest
    @CsvSource({
            "false,us-east-1a,us-east-1a,true",
            "false,us-east-1a,us-east-1b,false",
            "false,us-east-1b,us-east-1b,true",
            "true,us-east-1a,us-east-1a,true",
            "true,us-east-1a,us-east-1b,true",
            "true,us-east-1b,us-east-1a,true",
            "true,us-east-1a,us-east-1c,false",
            "false,us-east-1b,us-east-1c,false"
    })
    void crossZoneRoutingOnlyUsesInstancesInEnabledZones(
            boolean crossZone, String nodeZone, String instanceZone, boolean eligible) {
        ClassicLoadBalancer lb = loadBalancer(crossZone);
        Instance instance = new Instance();
        Placement placement = new Placement();
        placement.setAvailabilityZone(instanceZone);
        instance.setPlacement(placement);
        assertEquals(eligible, ElbClassicFrontendManager.eligibleZone(lb, nodeZone, instance));
    }

    @Test
    void anInstanceWithoutPlacementCannotBecomeAFrontendTarget() {
        assertFalse(ElbClassicFrontendManager.eligibleZone(loadBalancer(true), "us-east-1a", new Instance()));
    }

    private ClassicLoadBalancer loadBalancer(boolean crossZone) {
        ClassicLoadBalancer lb = new ClassicLoadBalancer();
        lb.setAvailabilityZones(List.of("us-east-1a", "us-east-1b"));
        lb.getAttributes().setCrossZoneLoadBalancingEnabled(crossZone);
        return lb;
    }
}
