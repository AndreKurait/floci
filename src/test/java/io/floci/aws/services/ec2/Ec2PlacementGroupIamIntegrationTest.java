package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.PlacementGroup;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2PlacementGroupIamIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void taggedCreateNeedsBothExactNamePermissionsAndCreateAction() {
        Fixture fixture = fixture();
        String name = fixture.name(Region.US_EAST_1);
        String arn = arn(name);
        Map<String, Object> create = allow("ec2:CreatePlacementGroup", arn,
                Map.of("ec2:PlacementGroupName", name, "ec2:PlacementGroupStrategy", "cluster",
                        "ec2:Region", "us-east-1", "aws:RequestTag/audit-owner", fixture.marker));
        fixture.policy(create);
        denied(() -> fixture.create(name, fixture.marker));
        fixture.absent(Region.US_EAST_1, name);
        fixture.policy(create, allow("ec2:CreateTags", arn + "-sibling", Map.of()));
        denied(() -> fixture.create(name, fixture.marker));
        fixture.absent(Region.US_EAST_1, name);
        fixture.policy(create, allow("ec2:CreateTags", arn,
                Map.of("ec2:CreateAction", "CreatePlacementGroup", "aws:RequestTag/audit-owner", fixture.marker)));
        PlacementGroup created = fixture.create(name, fixture.marker);
        assertEquals(arn, created.groupArn());
        assertEquals(List.of(created), fixture.root.describePlacementGroups(r -> r.groupNames(name)).placementGroups());
    }

    @Test
    void suppliedStrategyAndRequestTagsCannotBypassCreateConditions() {
        Fixture fixture = fixture();
        String name = fixture.name(Region.US_EAST_1);
        fixture.policy(allow("ec2:CreatePlacementGroup", arn(name),
                Map.of("ec2:PlacementGroupStrategy", "cluster", "aws:RequestTag/audit-owner", fixture.marker)),
                allow("ec2:CreateTags", arn(name), Map.of()));
        denied(() -> fixture.user.createPlacementGroup(r -> r.groupName(name).strategy("partition").partitionCount(2)
                .tagSpecifications(tags(fixture.marker))));
        fixture.absent(Region.US_EAST_1, name);
        denied(() -> fixture.create(name, "other"));
        fixture.absent(Region.US_EAST_1, name);
        assertEquals(name, fixture.create(name, fixture.marker).groupName());
    }

    @Test
    void explicitTagDenialAndDeniedDryRunLeaveNoGroup() {
        Fixture fixture = fixture();
        String name = fixture.name(Region.US_EAST_1);
        Map<String, Object> deny = new LinkedHashMap<>(allow("ec2:CreateTags", arn(name), Map.of()));
        deny.put("Effect", "Deny");
        fixture.policy(allow(List.of("ec2:CreatePlacementGroup", "ec2:CreateTags"), arn(name), Map.of()), deny);
        denied(() -> fixture.create(name, fixture.marker));
        denied(() -> fixture.user.createPlacementGroup(r -> r.groupName(name).strategy("cluster")
                .tagSpecifications(tags(fixture.marker)).dryRun(true)));
        fixture.absent(Region.US_EAST_1, name);
        fixture.policy(allow(List.of("ec2:CreatePlacementGroup", "ec2:CreateTags"), arn(name), Map.of()));
        assertEquals(name, fixture.create(name, fixture.marker).groupName());
    }

    @Test
    void untaggedCreateUsesItsLiteralNameAndRefusesSiblingAndForeignArn() {
        Fixture fixture = fixture();
        String name = fixture.name(Region.US_EAST_1);
        String sibling = fixture.name(Region.US_EAST_1);
        fixture.policy(allow("ec2:CreatePlacementGroup", arn(name).replace(ACCOUNT, "222222222222"), Map.of()));
        denied(() -> fixture.user.createPlacementGroup(r -> r.groupName(name).strategy("cluster")));
        fixture.absent(Region.US_EAST_1, name);
        fixture.policy(allow("ec2:CreatePlacementGroup", arn(name), Map.of()));
        denied(() -> fixture.user.createPlacementGroup(r -> r.groupName(sibling).strategy("cluster")));
        fixture.absent(Region.US_EAST_1, sibling);
        PlacementGroup created = fixture.user.createPlacementGroup(r -> r.groupName(name).strategy("cluster"))
                .placementGroup();
        assertEquals(arn(name), created.groupArn());
        assertTrue(created.tags().isEmpty());
    }

    @Test
    void namePermissionCannotCreateInAnotherSigningRegion() {
        Fixture fixture = fixture();
        String name = fixture.name(Region.US_WEST_2);
        fixture.policy(allow("ec2:CreatePlacementGroup", arn(name), Map.of()));
        try (Ec2Client west = fixture.user(Region.US_WEST_2)) {
            denied(() -> west.createPlacementGroup(r -> r.groupName(name).strategy("cluster")));
            fixture.absent(Region.US_WEST_2, name);
            fixture.policy(allow("ec2:CreatePlacementGroup", arn(name).replace("us-east-1", "us-west-2"), Map.of()));
            assertEquals(name, west.createPlacementGroup(r -> r.groupName(name).strategy("cluster"))
                    .placementGroup().groupName());
        }
    }

    @Test
    void describeRequiresWildcardPermissionAndHonorsRegionCondition() {
        Fixture fixture = fixture();
        String name = fixture.name(Region.US_EAST_1);
        PlacementGroup original = fixture.root.createPlacementGroup(r -> r.groupName(name).strategy("cluster"))
                .placementGroup();
        fixture.policy(allow("ec2:DescribePlacementGroups", arn(name), Map.of()));
        denied(() -> fixture.user.describePlacementGroups(r -> r.groupNames(name)));
        fixture.policy(allow("ec2:DescribePlacementGroups", "*", Map.of("ec2:Region", "us-east-1")));
        assertEquals(List.of(original),
                fixture.user.describePlacementGroups(r -> r.groupNames(name)).placementGroups());
        try (Ec2Client west = fixture.user(Region.US_WEST_2)) {
            denied(() -> west.describePlacementGroups(r -> r.filters(Filter.builder()
                    .name("group-name").values(name).build())));
        }
    }

    @Test
    void deleteUsesStoredTagAliasesAndDryRunPreservesThatGeneration() {
        Fixture fixture = fixture();
        String name = fixture.name(Region.US_EAST_1);
        PlacementGroup original = fixture.root.createPlacementGroup(r -> r.groupName(name).strategy("cluster")
                .tagSpecifications(tags(fixture.marker))).placementGroup();
        fixture.policy(allow("ec2:DeletePlacementGroup", arn(name), Map.of("aws:ResourceTag/audit-owner", "wrong")));
        denied(() -> fixture.user.deletePlacementGroup(r -> r.groupName(name)));
        denied(() -> fixture.user.deletePlacementGroup(r -> r.groupName(name).dryRun(true)));
        assertEquals(List.of(original),
                fixture.root.describePlacementGroups(r -> r.groupNames(name)).placementGroups());
        fixture.policy(allow("ec2:DeletePlacementGroup", arn(name),
                Map.of("aws:ResourceTag/audit-owner", fixture.marker, "ec2:ResourceTag/audit-owner", fixture.marker,
                        "ec2:PlacementGroupName", name, "ec2:PlacementGroupStrategy", "cluster")));
        Ec2Exception dryRun = assertThrows(Ec2Exception.class,
                () -> fixture.user.deletePlacementGroup(r -> r.groupName(name).dryRun(true)));
        assertEquals("DryRunOperation", dryRun.awsErrorDetails().errorCode());
        assertEquals(List.of(original),
                fixture.root.describePlacementGroups(r -> r.groupNames(name)).placementGroups());
        fixture.user.deletePlacementGroup(r -> r.groupName(name));
        fixture.absent(Region.US_EAST_1, name);
    }

    private Fixture fixture() {
        return new Fixture();
    }

    private static void denied(Runnable action) {
        Ec2Exception exception = assertThrows(Ec2Exception.class, action::run);
        assertEquals(403, exception.statusCode());
        assertEquals("UnauthorizedOperation", exception.awsErrorDetails().errorCode());
    }

    private static String arn(String name) {
        return "arn:aws:ec2:us-east-1:" + ACCOUNT + ":placement-group/" + name;
    }

    private static TagSpecification tags(String marker) {
        return TagSpecification.builder().resourceType("placement-group")
                .tags(Tag.builder().key("audit-owner").value(marker).build()).build();
    }

    private static Map<String, Object> allow(Object action, String resource, Map<String, String> conditions) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Effect", "Allow");
        statement.put("Action", action);
        statement.put("Resource", resource);
        if (!conditions.isEmpty()) {
            statement.put("Condition", Map.of("StringEquals", conditions));
        }
        return statement;
    }

    private final class Fixture {
        private final String marker = UUID.randomUUID().toString();
        private final String userName = "pg-iam-" + marker;
        private final UserCredentials credentials;
        private final Ec2Client root;
        private final Ec2Client user;

        Fixture() {
            credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), userName);
            root = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1);
            user = user(Region.US_EAST_1);
            cleanup.register(root::close);
            cleanup.register(user::close);
        }

        Ec2Client user(Region region) {
            return AwsTestClients.create(Ec2Client.builder(),
                    credentials.accessKeyId(), credentials.secretAccessKey(), region, endpoint);
        }

        String name(Region region) {
            String name = "pg-iam-" + UUID.randomUUID();
            cleanup.register(() -> {
                try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, region)) {
                    List<PlacementGroup> groups = client.describePlacementGroups(r -> r.filters(Filter.builder()
                            .name("group-name").values(name).build())).placementGroups();
                    if (!groups.isEmpty()) {
                        assertEquals(name, groups.getFirst().groupName());
                        client.deletePlacementGroup(r -> r.groupName(name));
                    }
                }
            });
            return name;
        }

        PlacementGroup create(String name, String value) {
            return user.createPlacementGroup(r -> r.groupName(name).strategy("cluster")
                    .tagSpecifications(tags(value))).placementGroup();
        }

        void absent(Region region, String name) {
            try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, region)) {
                assertTrue(client.describePlacementGroups(r -> r.filters(Filter.builder()
                        .name("group-name").values(name).build())).placementGroups().isEmpty());
            }
        }

        @SafeVarargs
        final void policy(Map<String, Object>... statements) {
            try {
                EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), userName, "placement",
                        JSON.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(statements))));
            } catch (JsonProcessingException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
