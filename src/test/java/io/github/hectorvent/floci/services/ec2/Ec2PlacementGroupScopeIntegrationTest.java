package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.PlacementGroup;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class Ec2PlacementGroupScopeIntegrationTest {

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "us-gov-west-1,aws-us-gov", "cn-north-1,aws-cn"})
    void nativeMetadataAndLiteralNamesAreScopedByAccountRegionAndPartition(String regionName, String partition) {
        String name = "pg-scope-" + UUID.randomUUID();
        Region region = Region.of(regionName);
        try (Ec2Client owner = AwsTestClients.create(Ec2Client.builder(), "111111111111", region);
             Ec2Client sibling = AwsTestClients.create(Ec2Client.builder(), "222222222222", region);
             Ec2Client otherRegion = AwsTestClients.create(Ec2Client.builder(), "111111111111", Region.EU_WEST_1)) {
            PlacementGroup first = owner.createPlacementGroup(r -> r.groupName(name).strategy("cluster"))
                    .placementGroup();
            try {
                assertEquals("arn:" + partition + ":ec2:" + regionName
                        + ":111111111111:placement-group/" + name, first.groupArn());
                assertTrue(sibling.describePlacementGroups(r -> r.filters(
                        Filter.builder().name("group-name").values(name).build())).placementGroups().isEmpty());
                assertTrue(otherRegion.describePlacementGroups(r -> r.filters(
                        Filter.builder().name("group-name").values(name).build())).placementGroups().isEmpty());
                assertEquals("InvalidPlacementGroup.Unknown", assertThrows(Ec2Exception.class,
                        () -> sibling.deletePlacementGroup(r -> r.groupName(name))).awsErrorDetails().errorCode());
                assertEquals(List.of(first), owner.describePlacementGroups(r -> r.groupNames(name)).placementGroups());
                PlacementGroup second = sibling.createPlacementGroup(r -> r.groupName(name).strategy("cluster"))
                        .placementGroup();
                try {
                    assertNotEquals(first.groupId(), second.groupId());
                    assertEquals(List.of(first), owner.describePlacementGroups(r -> r.groupIds(first.groupId()))
                            .placementGroups());
                    assertEquals(List.of(second), sibling.describePlacementGroups(r -> r.groupNames(name))
                            .placementGroups());
                } finally {
                    sibling.deletePlacementGroup(r -> r.groupName(name));
                }
            } finally {
                owner.deletePlacementGroup(r -> r.groupName(name));
            }
            assertTrue(owner.describePlacementGroups(r -> r.filters(Filter.builder().name("group-name").values(name)
                    .build())).placementGroups().isEmpty());
        }
    }
}
