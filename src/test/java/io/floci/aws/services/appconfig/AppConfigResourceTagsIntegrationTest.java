package io.floci.aws.services.appconfig;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.ResourceNotFoundException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AppConfigResourceTagsIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final Map<String, String> INITIAL = Map.of("team", "payments", "remove", "yes");
    @TestHTTPResource("/")
    URI baseUri;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"application", "environment", "configurationprofile", "deploymentstrategy"})
    void sdkCreateMergeOverwriteRemoveAndDeleteTags(String type) {
        try (AppConfigClient client = AwsTestClients.create(AppConfigClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            Resource resource = create(client, type);
            try {
                assertEquals(INITIAL, client.listTagsForResource(request -> request.resourceArn(resource.arn())).tags());
                client.tagResource(request -> request.resourceArn(resource.arn()).tags(Map.of("team", "billing", "empty", "")));
                assertEquals(Map.of("team", "billing", "remove", "yes", "empty", ""),
                        client.listTagsForResource(request -> request.resourceArn(resource.arn())).tags());
                client.untagResource(request -> request.resourceArn(resource.arn()).tagKeys("remove", "absent"));
                assertEquals(Map.of("team", "billing", "empty", ""),
                        client.listTagsForResource(request -> request.resourceArn(resource.arn())).tags());
                client.untagResource(request -> request.resourceArn(resource.arn()).tagKeys(List.of()));
                assertEquals(Map.of("team", "billing", "empty", ""),
                        client.listTagsForResource(request -> request.resourceArn(resource.arn())).tags());
                for (String invalid : List.of(resource.arn().replace(ACCOUNT, "111111111111"),
                        resource.arn().replace("us-east-1", "us-west-2"))) {
                    assertThrows(ResourceNotFoundException.class,
                            () -> client.listTagsForResource(request -> request.resourceArn(invalid)));
                    assertThrows(ResourceNotFoundException.class,
                            () -> client.tagResource(request -> request.resourceArn(invalid).tags(Map.of("bad", "yes"))));
                    assertThrows(ResourceNotFoundException.class,
                            () -> client.untagResource(request -> request.resourceArn(invalid).tagKeys("team")));
                }
                try (AppConfigClient otherAccount = AwsTestClients.create(AppConfigClient.builder(), "111111111111", Region.US_EAST_1);
                        AppConfigClient otherRegion = AwsTestClients.create(AppConfigClient.builder(), ACCOUNT, Region.US_WEST_2)) {
                    for (AppConfigClient foreign : List.of(otherAccount, otherRegion)) {
                        assertThrows(ResourceNotFoundException.class,
                                () -> foreign.listTagsForResource(request -> request.resourceArn(resource.arn())));
                        assertThrows(ResourceNotFoundException.class,
                                () -> foreign.tagResource(request -> request.resourceArn(resource.arn()).tags(Map.of("bad", "yes"))));
                        assertThrows(ResourceNotFoundException.class,
                                () -> foreign.untagResource(request -> request.resourceArn(resource.arn()).tagKeys("team")));
                    }
                }
                assertEquals(Map.of("team", "billing", "empty", ""),
                        client.listTagsForResource(request -> request.resourceArn(resource.arn())).tags());
                client.untagResource(request -> request.resourceArn(resource.arn()).tagKeys("team", "empty"));
                assertTrue(client.listTagsForResource(request -> request.resourceArn(resource.arn())).tags().isEmpty());
                remove(client, resource);
                assertThrows(ResourceNotFoundException.class,
                        () -> client.listTagsForResource(request -> request.resourceArn(resource.arn())));
                assertThrows(ResourceNotFoundException.class,
                        () -> client.tagResource(request -> request.resourceArn(resource.arn()).tags(Map.of("bad", "yes"))));
            } finally {
                removeIfPresent(client, resource);
                client.deleteApplication(request -> request.applicationId(resource.parent()));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"application", "environment", "configurationprofile", "deploymentstrategy"})
    void resourceScopedIamAllowsOnlyItsGrantedTagActions(String type) {
        try (AppConfigClient root = AwsTestClients.create(AppConfigClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            Resource allowed = create(root, type);
            Resource other = create(root, type);
            try {
                String user = "appconfig-tags-" + UUID.randomUUID().toString().substring(0, 8);
                String rootAuth = auth(ACCOUNT, "iam");
                String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), rootAuth, user).accessKeyId();
                EnforcementFixtures.putUserPolicy(baseUri.getPort(), rootAuth, user, "tags", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Action":["appconfig:TagResource","appconfig:ListTagsForResource"],"Resource":"%s"}]}
                        """.formatted(allowed.arn()));
                try (AppConfigClient scoped = AwsTestClients.create(AppConfigClient.builder(), key, Region.US_EAST_1)) {
                    scoped.tagResource(request -> request.resourceArn(allowed.arn()).tags(Map.of("allowed", "yes")));
                    assertEquals("yes", scoped.listTagsForResource(request -> request.resourceArn(allowed.arn())).tags().get("allowed"));
                    assertEquals(403, assertThrows(AppConfigException.class,
                            () -> scoped.untagResource(request -> request.resourceArn(allowed.arn()).tagKeys("team"))).statusCode());
                    assertEquals(403, assertThrows(AppConfigException.class,
                            () -> scoped.listTagsForResource(request -> request.resourceArn(other.arn()))).statusCode());
                    assertEquals(403, assertThrows(AppConfigException.class,
                            () -> scoped.tagResource(request -> request.resourceArn(other.arn()).tags(Map.of("bad", "yes")))).statusCode());
                }
                assertEquals(INITIAL, root.listTagsForResource(request -> request.resourceArn(other.arn())).tags());
            } finally {
                removeIfPresent(root, allowed);
                removeIfPresent(root, other);
                root.deleteApplication(request -> request.applicationId(allowed.parent()));
                root.deleteApplication(request -> request.applicationId(other.parent()));
            }
        }
    }

    @Test
    void resourceResponsesDoNotExposeInternalTagsAndInvalidCreateTagsFailBeforeStorage() {
        String authorization = auth(ACCOUNT, "appconfig");
        try (AppConfigClient client = AwsTestClients.create(AppConfigClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            for (String type : List.of("application", "environment", "configurationprofile", "deploymentstrategy")) {
                Resource resource = create(client, type);
                try {
                    given().header("Authorization", authorization).get(resource.path()).then().statusCode(200)
                            .body("$", not(hasKey("tags")), "$", not(hasKey("Tags")));
                    String listPath = resource.path().substring(0, resource.path().lastIndexOf('/'));
                    given().header("Authorization", authorization).get(listPath).then().statusCode(200)
                            .body("Items.find { it.Id == '" + resource.id() + "' }", not(hasKey("tags")))
                            .body("Items.find { it.Id == '" + resource.id() + "' }", not(hasKey("Tags")));
                } finally {
                    removeIfPresent(client, resource);
                    client.deleteApplication(request -> request.applicationId(resource.parent()));
                }
            }
            for (String invalid : List.of("null", "[]", "{\"key\":null}", "{\"key\":1}", "{\"\":\"value\"}")) {
                given().header("Authorization", authorization).contentType("application/json")
                        .body("{\"Name\":\"invalid-create-tags\",\"Tags\":" + invalid + "}")
                        .post("/applications").then().statusCode(400);
            }
            assertTrue(client.listApplications(request -> { }).items().stream().noneMatch(item -> "invalid-create-tags".equals(item.name())));
            String id = given().header("Authorization", authorization).contentType("application/json")
                    .body("{\"Name\":\"wire-create-tags\",\"Tags\":{\"keep\":\"yes\"}}")
                    .post("/applications").then().statusCode(201)
                    .body("$", not(hasKey("tags")), "$", not(hasKey("Tags"))).extract().path("Id");
            client.deleteApplication(request -> request.applicationId(id));
        }
    }

    private static Resource create(AppConfigClient client, String type) {
        String parent = client.createApplication(request -> request.name("tag-parent-" + UUID.randomUUID())).id();
        String id = switch (type) {
            case "application" -> client.createApplication(request -> request.name("tag-application").tags(INITIAL)).id();
            case "environment" -> client.createEnvironment(request -> request.applicationId(parent).name("tag-environment").tags(INITIAL)).id();
            case "configurationprofile" -> client.createConfigurationProfile(request -> request.applicationId(parent)
                    .name("tag-profile").locationUri("hosted").tags(INITIAL)).id();
            case "deploymentstrategy" -> client.createDeploymentStrategy(request -> request.name("tag-strategy")
                    .deploymentDurationInMinutes(0).growthFactor(100f).replicateTo("NONE").tags(INITIAL)).id();
            default -> throw new IllegalArgumentException(type);
        };
        String arnResource = (type.equals("environment") || type.equals("configurationprofile"))
                ? "application/" + parent + "/" + type + "/" + id : type + "/" + id;
        String path = switch (type) {
            case "application" -> "/applications/" + id;
            case "environment" -> "/applications/" + parent + "/environments/" + id;
            case "configurationprofile" -> "/applications/" + parent + "/configurationprofiles/" + id;
            case "deploymentstrategy" -> "/deploymentstrategies/" + id;
            default -> throw new IllegalArgumentException(type);
        };
        return new Resource(type, parent, id, "arn:aws:appconfig:us-east-1:" + ACCOUNT + ":" + arnResource, path);
    }

    private static void remove(AppConfigClient client, Resource resource) {
        switch (resource.type()) {
            case "application" -> client.deleteApplication(request -> request.applicationId(resource.id()));
            case "environment" -> client.deleteEnvironment(request -> request.applicationId(resource.parent()).environmentId(resource.id()));
            case "configurationprofile" -> client.deleteConfigurationProfile(request -> request.applicationId(resource.parent()).configurationProfileId(resource.id()));
            case "deploymentstrategy" -> client.deleteDeploymentStrategy(request -> request.deploymentStrategyId(resource.id()));
            default -> throw new IllegalArgumentException(resource.type());
        }
    }

    private static void removeIfPresent(AppConfigClient client, Resource resource) {
        try {
            remove(client, resource);
        } catch (ResourceNotFoundException expected) {
            // The lifecycle case deliberately deletes its resource before checking tag failures.
        }
    }

    private record Resource(String type, String parent, String id, String arn, String path) {}
}
