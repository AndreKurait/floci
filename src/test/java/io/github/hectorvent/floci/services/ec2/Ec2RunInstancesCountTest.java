package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RunInstances launches MaxCount instances when nothing limits it, as many as fit when that still
 * meets MinCount, and nothing at all otherwise.
 */
class Ec2RunInstancesCountTest {

    private static final String REGION = "us-east-1";
    /** A /28 holds sixteen addresses, of which EC2 reserves the first four and the last one. */
    private static final int FREE_ADDRESSES_IN_A_SLASH_28 = 11;

    private Ec2Service service;

    @BeforeEach
    void setUp() {
        service = Ec2ServiceFixtures.mockModeService();
    }

    @Test
    void launchesTheMaximumCountWhenNothingLimitsIt() {
        List<Instance> launched = run(1, 4, slash28Subnet());

        assertThat(launched, hasSize(4));
        assertEquals(4, storedInstances());
    }

    @Test
    void launchesWhatFitsInTheSubnetWhenThatMeetsTheMinimum() {
        String subnetId = slash28Subnet();

        List<Instance> launched = run(2, FREE_ADDRESSES_IN_A_SLASH_28 + 3, subnetId);

        assertThat(launched, hasSize(FREE_ADDRESSES_IN_A_SLASH_28));
        assertEquals(FREE_ADDRESSES_IN_A_SLASH_28,
                launched.stream().map(Instance::getPrivateIpAddress).distinct().count());
    }

    @Test
    void launchesNothingWhenTheSubnetCannotHoldTheMinimum() {
        String subnetId = slash28Subnet();

        AwsException refused = assertThrows(AwsException.class,
                () -> run(FREE_ADDRESSES_IN_A_SLASH_28 + 1, FREE_ADDRESSES_IN_A_SLASH_28 + 3, subnetId));

        assertEquals("InsufficientFreeAddressesInSubnet", refused.getErrorCode());
        assertEquals(0, storedInstances());
        assertThat("every address the refused launch had taken is free again",
                run(FREE_ADDRESSES_IN_A_SLASH_28, FREE_ADDRESSES_IN_A_SLASH_28, subnetId),
                hasSize(FREE_ADDRESSES_IN_A_SLASH_28));
    }

    @Test
    void refusesARequestThatMayLaunchTwoInstancesOnOneSuppliedNetworkInterface() {
        String subnetId = slash28Subnet();
        String interfaceId = service.createNetworkInterface(REGION, subnetId, "primary", null,
                List.of(), List.of(), List.of()).getNetworkInterfaceId();

        AwsException refused = assertThrows(AwsException.class,
                () -> service.runInstances(REGION, "ami-test", "t3.micro", 1, 2, null, List.of(), null, null,
                        List.of(), null, null, null, interfaceId, 0, null, null, null, null, false, null, null,
                        null, false));

        assertEquals("InvalidParameterCombination", refused.getErrorCode());
        assertEquals(0, storedInstances());
    }

    private String slash28Subnet() {
        String vpcId = service.createVpc(REGION, "10.60.0.0/16", false).getVpcId();
        return service.createSubnet(REGION, vpcId, "10.60.1.0/28", "us-east-1a").getSubnetId();
    }

    private List<Instance> run(int minCount, int maxCount, String subnetId) {
        return service.runInstances(REGION, "ami-test", "t3.micro", minCount, maxCount, null, List.of(),
                subnetId, null, List.of(), null, null).getInstances();
    }

    private int storedInstances() {
        return service.describeInstances(REGION, List.of(), Map.of()).stream()
                .mapToInt(reservation -> reservation.getInstances().size()).sum();
    }
}
