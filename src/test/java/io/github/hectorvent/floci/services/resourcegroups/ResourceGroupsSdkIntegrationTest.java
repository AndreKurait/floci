package io.github.hectorvent.floci.services.resourcegroups;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.resourcegroups.ResourceGroupsClient;
import software.amazon.awssdk.services.resourcegroups.model.BadRequestException;
import software.amazon.awssdk.services.resourcegroups.model.GroupConfigurationItem;
import software.amazon.awssdk.services.resourcegroups.model.GroupConfigurationParameter;
import software.amazon.awssdk.services.resourcegroups.model.GroupFilter;
import software.amazon.awssdk.services.resourcegroups.model.ListGroupsResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ResourceGroupsSdkIntegrationTest {
    private static final String ACCOUNT = "128880000001";
    private static final String REGION = "eu-west-1";
    private static final String POOL = "AWS::EC2::CapacityReservationPool";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void sdkListingPagesWithinAccountRegionAndFilterAndObservesDeletion() {
        String prefix = "sdk-list-" + UUID.randomUUID();
        GroupFilter filter = GroupFilter.builder().name("configuration-type").values(POOL).build();
        List<Runnable> cleanup = new ArrayList<>();
        try (ResourceGroupsClient owner = client(ACCOUNT, REGION);
             ResourceGroupsClient otherAccount = client("128880000002", REGION);
             ResourceGroupsClient otherRegion = client(ACCOUNT, "us-east-1")) {
            try {
                create(owner, prefix + "-a", cleanup);
                create(owner, prefix + "-b", cleanup);
                create(otherAccount, prefix + "-foreign-account", cleanup);
                create(otherRegion, prefix + "-foreign-region", cleanup);

                ListGroupsResponse first = owner.listGroups(r -> r.filters(filter).maxResults(1));
                assertEquals(List.of(prefix + "-a"),
                        first.groupIdentifiers().stream().map(group -> group.groupName()).toList());
                assertNotNull(first.nextToken());
                assertEquals("SDK list fixture", first.groupIdentifiers().getFirst().description());
                ListGroupsResponse second = owner.listGroups(
                        r -> r.filters(filter).maxResults(1).nextToken(first.nextToken()));
                assertEquals(List.of(prefix + "-b"),
                        second.groupIdentifiers().stream().map(group -> group.groupName()).toList());
                assertNull(second.nextToken());
                assertEquals(second.groupIdentifiers(), owner.listGroups(
                        r -> r.filters(filter).maxResults(1).nextToken(first.nextToken())).groupIdentifiers());
                assertThrows(BadRequestException.class,
                        () -> otherAccount.listGroups(r -> r.filters(filter).nextToken(first.nextToken())));
                assertThrows(BadRequestException.class,
                        () -> otherRegion.listGroups(r -> r.filters(filter).nextToken(first.nextToken())));
                assertThrows(BadRequestException.class,
                        () -> owner.listGroups(r -> r.nextToken(first.nextToken())));
                assertEquals(2, owner.listGroups().groupIdentifiers().size());
                assertTrue(owner.listGroups(r -> r.filters(GroupFilter.builder()
                        .name("resource-type").values("AWS::EC2::CapacityReservation").build()))
                        .groupIdentifiers().isEmpty());
                assertTrue(owner.listGroups(r -> r.filters(GroupFilter.builder()
                        .name("configuration-type").values("AWS::EC2::HostManagement").build()))
                        .groupIdentifiers().isEmpty());
                owner.deleteGroup(r -> r.group(prefix + "-a"));
                cleanup.removeFirst();
                assertEquals(List.of(prefix + "-b"), owner.listGroups().groupIdentifiers().stream()
                        .map(group -> group.groupName()).toList());
            } finally {
                cleanup.reversed().forEach(Runnable::run);
            }
        }
    }

    @Test
    void queryPaginationRejectsMalformedValuesAndIgnoresUnknownBodyMembers() {
        String auth = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261102/"
                + REGION + "/resource-groups/aws4_request";
        for (String maximum : List.of("0", "51", "abc", "2147483648")) {
            given().header("Authorization", auth).contentType("application/json")
                    .queryParam("maxResults", maximum).body("{}").post("/groups-list").then().statusCode(400);
        }
        given().header("Authorization", auth).contentType("application/json")
                .body("{\"MaxResults\":0,\"NextToken\":\"ignored\"}").post("/groups-list").then().statusCode(200);
        given().header("Authorization", auth).contentType("application/json")
                .body("[]").post("/groups-list").then().statusCode(400);
    }

    private ResourceGroupsClient client(String account, String region) {
        return ResourceGroupsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "secret")))
                .build();
    }

    private void create(ResourceGroupsClient client, String name, List<Runnable> cleanup) {
        client.createGroup(r -> r.name(name).description("SDK list fixture").configuration(
                GroupConfigurationItem.builder().type(POOL).build(),
                GroupConfigurationItem.builder().type("AWS::ResourceGroups::Generic")
                        .parameters(GroupConfigurationParameter.builder().name("allowed-resource-types")
                                .values("AWS::EC2::CapacityReservation").build()).build()));
        cleanup.add(() -> client.deleteGroup(r -> r.group(name)));
    }
}
