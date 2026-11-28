package io.floci.aws.services.eks;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.EksException;
import software.amazon.awssdk.services.eks.model.ListAccessPoliciesRequest;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EksAccessPolicyCatalogIamEnforcementIntegrationTest {
    private static final String ACCOUNT = "111111111111";

    @TestHTTPResource
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void sdkPolicyAllowsCatalogListingOnWildcardResource() {
        UserCredentials credentials = user("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"eks:ListAccessPolicies","Resource":"*"}]}""");
        try (EksClient client = client(credentials)) {
            assertFalse(client.listAccessPolicies(ListAccessPoliciesRequest.builder().build()).accessPolicies().isEmpty());
        }
    }

    @Test
    void sdkRequiresWildcardResourceAndHonorsExplicitDeny() {
        UserCredentials scoped = user("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"eks:ListAccessPolicies",
                   "Resource":"arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy"}]}""");
        UserCredentials denied = user("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"eks:*","Resource":"*"},
                  {"Effect":"Deny","Action":"eks:ListAccessPolicies","Resource":"*"}]}""");
        for (UserCredentials credentials : new UserCredentials[]{scoped, denied}) {
            try (EksClient client = client(credentials)) {
                EksException error = assertThrows(EksException.class, () -> client.listAccessPolicies(ListAccessPoliciesRequest.builder().build()));
                assertEquals(403, error.statusCode());
            }
        }
    }

    @Test
    void targetAndActionHintsCannotBorrowAnotherOperationPermission() {
        UserCredentials credentials = user("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"eks:ListClusters","Resource":"*"}]}""");
        given().header("Authorization", auth(credentials.accessKeyId(), "eks"))
                .header("X-Amz-Target", "EKS.ListClusters").queryParam("Action", "ListClusters")
                .when().get("/access-policies")
                .then().statusCode(403);
        given().header("Authorization", auth(credentials.accessKeyId(), "eks"))
                .when().get("/access-policies;ignored=value")
                .then().statusCode(404);
    }

    private UserCredentials user(String policy) {
        String name = "eks-catalog-" + UUID.randomUUID().toString().substring(0, 8);
        UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), name);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), name, "catalog", policy);
        return credentials;
    }

    private EksClient client(UserCredentials credentials) {
        return EksClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        credentials.accessKeyId(), credentials.secretAccessKey())))
                .build();
    }
}
