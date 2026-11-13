package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/** Deleting a resource deletes its tags, so DescribeTags never lists a resource that is gone. */
class Ec2DeletedResourceTagsTest {

    private static final String REGION = "us-east-1";

    private Ec2Service service;
    private String vpcId;

    @BeforeEach
    void setUp() {
        service = Ec2ServiceFixtures.mockModeService();
        vpcId = service.createVpc(REGION, "10.70.0.0/16", false).getVpcId();
    }

    @Test
    void deletingASubnetDeletesItsTags() {
        String subnetId = service.createSubnet(REGION, vpcId, "10.70.1.0/24", "us-east-1a").getSubnetId();
        tag(subnetId);

        service.deleteSubnet(REGION, subnetId);

        assertThat(tagsOf(subnetId), is(empty()));
    }

    @Test
    void deletingAnInternetGatewayDeletesItsTags() {
        String gatewayId = service.createInternetGateway(REGION).getInternetGatewayId();
        tag(gatewayId);

        service.deleteInternetGateway(REGION, gatewayId);

        assertThat(tagsOf(gatewayId), is(empty()));
    }

    @Test
    void deletingARouteTableDeletesItsTags() {
        String routeTableId = service.createRouteTable(REGION, vpcId).getRouteTableId();
        tag(routeTableId);

        service.deleteRouteTable(REGION, routeTableId);

        assertThat(tagsOf(routeTableId), is(empty()));
    }

    @Test
    void releasingAnElasticIpDeletesItsTags() {
        String allocationId = service.allocateAddress(REGION).getAllocationId();
        tag(allocationId);

        service.releaseAddress(REGION, allocationId);

        assertThat(tagsOf(allocationId), is(empty()));
    }

    private void tag(String resourceId) {
        service.createTags(REGION, List.of(resourceId), List.of(new Tag("Name", "short-lived")));
        assertThat(tagsOf(resourceId), hasSize(1));
    }

    private List<Map<String, String>> tagsOf(String resourceId) {
        return service.describeTags(REGION, Map.of("resource-id", List.of(resourceId)));
    }
}
