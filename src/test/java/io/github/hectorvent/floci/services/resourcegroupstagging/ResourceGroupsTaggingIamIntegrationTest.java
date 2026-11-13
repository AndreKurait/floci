package io.github.hectorvent.floci.services.resourcegroupstagging;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.ResourceGroupsTaggingApiException;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class ResourceGroupsTaggingIamIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void signedTaggingClientReadsResourcesUnderTheTagPermission() throws Exception {
        String marker = UUID.randomUUID().toString();
        try (SqsClient admin = SqsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String queue = admin.createQueue(request -> request.queueName("tag-iam-" + marker)
                    .tags(Map.of("tag-iam", marker))).queueUrl();
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "tag-reader-", "read",
                    policy("tag:GetResources"));
                 ResourceGroupsTaggingApiClient actor = client(user)) {
                assertEquals(1, actor.getResources(request -> request
                                .tagFilters(filter -> filter.key("tag-iam").values(marker)))
                        .resourceTagMappingList().size());
                assertDenied(() -> actor.getTagKeys(request -> {}), "tag:GetTagKeys");
            } finally {
                admin.deleteQueue(request -> request.queueUrl(queue));
            }
        }
    }

    @Test
    void theSigningScopeIsNotAnIamPermissionNamespace() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "tag-wrong-", "read",
                policy("tagging:GetResources"));
             ResourceGroupsTaggingApiClient actor = client(user)) {
            assertDenied(() -> actor.getResources(request -> {}), "tag:GetResources");
        }
    }

    @Test
    void anExplicitTagDenyOverridesAWildcardGrant() throws Exception {
        String document = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"*","Resource":"*"},
                  {"Effect":"Deny","Action":"tag:GetResources","Resource":"*"}]}""";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "tag-denied-", "read", document);
             ResourceGroupsTaggingApiClient actor = client(user)) {
            assertDenied(() -> actor.getResources(request -> {}), "tag:GetResources");
        }
    }

    private ResourceGroupsTaggingApiClient client(SignedIamUsers.User user) {
        return ResourceGroupsTaggingApiClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey())))
                .build();
    }

    private static String policy(String action) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"%s","Resource":"*"}]}""".formatted(action);
    }

    private static void assertDenied(Executable call, String action) {
        ResourceGroupsTaggingApiException denied = assertThrows(ResourceGroupsTaggingApiException.class, call);
        assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
        assertTrue(denied.getMessage().contains(action), denied.getMessage());
    }
}
