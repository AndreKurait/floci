package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.SpotInstanceRequest;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A RequestSpotInstances call that fails answers with an error and no request ids, so it must
 * leave neither requests nor instances behind.
 */
class Ec2SpotRequestBatchTest {

    private static final String REGION = "us-east-1";
    /** A /28 holds sixteen addresses, of which EC2 reserves the first four and the last one. */
    private static final int FREE_ADDRESSES_IN_A_SLASH_28 = 11;

    private Ec2Service service;
    private String subnetId;

    @BeforeEach
    void setUp() {
        service = Ec2ServiceFixtures.mockModeService();
        String vpcId = service.createVpc(REGION, "10.80.0.0/16", false).getVpcId();
        subnetId = service.createSubnet(REGION, vpcId, "10.80.1.0/28", "us-east-1a").getSubnetId();
    }

    @Test
    void aSpotRequestThatCannotBeFilledLeavesNoRequestsAndNoInstancesBehind() {
        AwsException refused = assertThrows(AwsException.class,
                () -> request(FREE_ADDRESSES_IN_A_SLASH_28 + 1));

        assertEquals("InsufficientFreeAddressesInSubnet", refused.getErrorCode());
        assertThat(service.describeSpotInstanceRequests(REGION, List.of(), Map.<String, List<String>>of()),
                is(empty()));
        assertEquals(0, storedInstances());
    }

    @Test
    void aSpotRequestThatFitsCreatesOneFulfilledRequestPerInstance() {
        List<SpotInstanceRequest> requests = request(FREE_ADDRESSES_IN_A_SLASH_28);

        assertThat(requests, hasSize(FREE_ADDRESSES_IN_A_SLASH_28));
        assertEquals(FREE_ADDRESSES_IN_A_SLASH_28,
                requests.stream().map(SpotInstanceRequest::getInstanceId).distinct().count());
        assertEquals(FREE_ADDRESSES_IN_A_SLASH_28,
                requests.stream().map(SpotInstanceRequest::getSpotInstanceRequestId).distinct().count());
        assertEquals(FREE_ADDRESSES_IN_A_SLASH_28, storedInstances());
    }

    private List<SpotInstanceRequest> request(int instanceCount) {
        return service.requestSpotInstances(REGION, null, instanceCount, null, null, "ami-test", "t3.micro", null,
                subnetId, List.of(), null, null, List.of(), List.of(), null);
    }

    private int storedInstances() {
        return service.describeInstances(REGION, List.of(), Map.of()).stream()
                .mapToInt(reservation -> reservation.getInstances().size()).sum();
    }
}
