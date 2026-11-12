package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreatePlacementGroupRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.PlacementGroup;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2PlacementGroupSdkIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @ParameterizedTest
    @CsvSource({"cluster,,", "spread,rack,", "partition,,3"})
    void createsReadsAndDeletesSuppliedMetadata(String strategy, String spread, Integer partitions) {
        String name = uniqueName();
        String marker = UUID.randomUUID().toString();
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1)) {
            CreatePlacementGroupRequest.Builder request = CreatePlacementGroupRequest.builder()
                    .groupName(name).strategy(strategy).spreadLevel(spread).partitionCount(partitions)
                    .tagSpecifications(tags(marker));
            PlacementGroup created = ec2.createPlacementGroup(request.build()).placementGroup();
            try {
                assertEquals(name, created.groupName());
                assertEquals(strategy, created.strategyAsString());
                assertEquals(spread, created.spreadLevelAsString());
                assertEquals(partitions, created.partitionCount());
                assertEquals("available", created.stateAsString());
                assertTrue(created.groupId().startsWith("pg-"));
                assertEquals("arn:aws:ec2:us-east-1:" + ACCOUNT + ":placement-group/" + name, created.groupArn());
                assertEquals(Map.of("audit-owner", marker, "empty", ""), tagMap(created));
                assertEquals(List.of(created), ec2.describePlacementGroups(r -> r.groupNames(name)).placementGroups());
                assertEquals(List.of(created),
                        ec2.describePlacementGroups(r -> r.groupIds(created.groupId())).placementGroups());
                assertEquals(List.of(created), ec2.describePlacementGroups(r -> r.filters(
                        filter("tag:audit-owner", marker), filter("group-name", name + "*"),
                        filter("strategy", "unmatched", strategy), filter("tag-key", "empty"),
                        filter("state", "available"), filter("group-arn", created.groupArn()))).placementGroups());
                assertTrue(ec2.describePlacementGroups(r -> r.filters(
                        filter("tag:audit-owner", marker), filter("strategy", strategy),
                        filter("strategy", "unmatched"))).placementGroups().isEmpty());
                assertTrue(ec2.describePlacementGroups(r -> r.filters(
                        filter("tag:audit-owner", marker), filter("tag:empty", "nonempty")))
                        .placementGroups().isEmpty());
                assertCode("InvalidPlacementGroup.Duplicate",
                        () -> ec2.createPlacementGroup(request.build()));
                assertEquals(List.of(created), ec2.describePlacementGroups(r -> r.groupNames(name)).placementGroups());
            } finally {
                ec2.deletePlacementGroup(r -> r.groupName(name));
            }
            assertCode("InvalidPlacementGroup.Unknown", () -> ec2.describePlacementGroups(r -> r.groupNames(name)));
            assertTrue(ec2.describePlacementGroups(r -> r.filters(filter("group-name", name)))
                    .placementGroups().isEmpty());
            PlacementGroup replacement = ec2.createPlacementGroup(r -> r.groupName(name).strategy("cluster"))
                    .placementGroup();
            try {
                assertNotEquals(created.groupId(), replacement.groupId());
                assertTrue(replacement.tags().isEmpty());
            } finally {
                ec2.deletePlacementGroup(r -> r.groupName(name));
            }
        }
    }

    @Test
    void describesAnEmptyOwnedNameFilter() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertTrue(ec2.describePlacementGroups(r -> r.filters(filter("group-name", uniqueName())))
                    .placementGroups().isEmpty());
        }
    }

    @Test
    void rejectsAnAbsentDeleteWithThePlacementGroupCode() {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertCode("InvalidPlacementGroup.Unknown", () -> ec2.deletePlacementGroup(r -> r.groupName(uniqueName())));
        }
    }

    @Test
    void dryRunDoesNotCreateOrDeleteAnOwnedGroup() {
        String name = uniqueName();
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1)) {
            assertCode("DryRunOperation",
                    () -> ec2.createPlacementGroup(r -> r.groupName(name).strategy("cluster").dryRun(true)));
            assertTrue(ec2.describePlacementGroups(r -> r.filters(filter("group-name", name)))
                    .placementGroups().isEmpty());
            PlacementGroup created = ec2.createPlacementGroup(r -> r.groupName(name).strategy("cluster"))
                    .placementGroup();
            try {
                assertCode("DryRunOperation", () -> ec2.describePlacementGroups(r -> r.groupNames(name).dryRun(true)));
                assertCode("DryRunOperation", () -> ec2.deletePlacementGroup(r -> r.groupName(name).dryRun(true)));
                assertEquals(List.of(created), ec2.describePlacementGroups(r -> r.groupNames(name)).placementGroups());
            } finally {
                ec2.deletePlacementGroup(r -> r.groupName(name));
            }
        }
    }

    private static Filter filter(String name, String... values) {
        return Filter.builder().name(name).values(values).build();
    }

    private static TagSpecification tags(String marker) {
        return TagSpecification.builder().resourceType("placement-group").tags(
                Tag.builder().key("audit-owner").value(marker).build(),
                Tag.builder().key("empty").value("").build()).build();
    }

    private static Map<String, String> tagMap(PlacementGroup group) {
        return group.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private static void assertCode(String code, Runnable request) {
        Ec2Exception failure = assertThrows(Ec2Exception.class, request::run);
        assertEquals(code, failure.awsErrorDetails().errorCode());
        assertFalse(failure.awsErrorDetails().errorMessage().isBlank());
    }

    private static String uniqueName() {
        return "pg-sdk-" + UUID.randomUUID().toString().substring(0, 16);
    }
}
