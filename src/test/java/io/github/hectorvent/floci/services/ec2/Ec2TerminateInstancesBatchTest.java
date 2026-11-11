package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** TerminateInstances acts on every instance a request names or on none of them. */
class Ec2TerminateInstancesBatchTest {

    private static final String REGION = "us-east-1";

    @Test
    void terminateInstancesTerminatesNothingWhenOneIdDoesNotExist() {
        Ec2Service service = Ec2ServiceFixtures.mockModeService();
        String vpcId = service.createVpc(REGION, "10.61.0.0/16", false).getVpcId();
        String subnetId = service.createSubnet(REGION, vpcId, "10.61.1.0/24", "us-east-1a").getSubnetId();
        Instance instance = service.runInstances(REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), subnetId,
                null, List.of(), null, null).getInstances().getFirst();

        AwsException refused = assertThrows(AwsException.class, () -> service.terminateInstances(REGION,
                List.of(instance.getInstanceId(), "i-0123456789abcdef0")));

        assertEquals("InvalidInstanceID.NotFound", refused.getErrorCode());
        assertEquals("running", describedState(service, instance.getInstanceId()));
    }

    private static String describedState(Ec2Service service, String instanceId) {
        return service.describeInstances(REGION, List.of(instanceId), Map.of()).getFirst().getInstances().getFirst()
                .getState().getName();
    }
}
