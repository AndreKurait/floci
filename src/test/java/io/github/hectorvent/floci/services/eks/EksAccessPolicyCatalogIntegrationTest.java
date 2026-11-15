package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.AccessPolicy;
import software.amazon.awssdk.services.eks.model.EksException;
import software.amazon.awssdk.services.eks.model.ListAccessPoliciesRequest;
import software.amazon.awssdk.services.eks.model.ListAccessPoliciesResponse;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EksAccessPolicyCatalogIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkListsPolicyMetadataAndPaginatesWithoutACluster() {
        try (EksClient client = client("000000000000", Region.US_EAST_1)) {
            ListAccessPoliciesResponse response = client.listAccessPolicies(ListAccessPoliciesRequest.builder().build());
            Set<String> names = response.accessPolicies().stream()
                    .map(AccessPolicy::name).collect(Collectors.toSet());
            assertTrue(names.containsAll(Set.of("AmazonEKSAdminPolicy", "AmazonEKSClusterAdminPolicy",
                    "AmazonEKSEditPolicy", "AmazonEKSViewPolicy", "AmazonEMRJobPolicy")));
            assertEquals(names.size(), response.accessPolicies().size());
            assertNull(response.nextToken());
            response.accessPolicies().forEach(policy -> assertEquals(
                    "arn:aws:eks::aws:cluster-access-policy/" + policy.name(), policy.arn()));
            List<AccessPolicy> paged = client.listAccessPoliciesPaginator(
                    request -> request.maxResults(2)).accessPolicies().stream().toList();
            assertEquals(new HashSet<>(response.accessPolicies()), new HashSet<>(paged));
            assertEquals(response.accessPolicies().size(), paged.size());
            assertEquals(1, client.listAccessPolicies(request -> request.maxResults(1)).accessPolicies().size());
            assertEquals(new HashSet<>(response.accessPolicies()), new HashSet<>(
                    client.listAccessPolicies(request -> request.maxResults(100)).accessPolicies()));
        }
    }

    @ParameterizedTest
    @CsvSource({"us-gov-west-1,aws-us-gov", "cn-north-1,aws-cn", "eu-isoe-west-1,aws-iso-e"})
    void sdkUsesTheRequestPartitionAndAwsPolicyAccount(String region, String partition) {
        try (EksClient client = client("123456789012", Region.of(region))) {
            List<AccessPolicy> policies = client.listAccessPolicies(ListAccessPoliciesRequest.builder().build()).accessPolicies();
            assertFalse(policies.isEmpty());
            policies.forEach(policy -> assertEquals(
                    "arn:" + partition + ":eks::aws:cluster-access-policy/" + policy.name(), policy.arn()));
        }
    }

    @Test
    void catalogDoesNotDependOnCallerAccount() {
        try (EksClient first = client("111111111111", Region.US_EAST_1);
             EksClient second = client("222222222222", Region.US_EAST_1)) {
            assertEquals(new HashSet<>(first.listAccessPolicies(ListAccessPoliciesRequest.builder().build()).accessPolicies()),
                    new HashSet<>(second.listAccessPolicies(ListAccessPoliciesRequest.builder().build()).accessPolicies()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void sdkReceivesAwsShapedPageSizeErrors(int size) {
        try (EksClient client = client("000000000000", Region.US_EAST_1)) {
            EksException error = assertThrows(EksException.class,
                    () -> client.listAccessPolicies(request -> request.maxResults(size)));
            assertEquals(400, error.statusCode());
            assertEquals("InvalidParameterException", error.awsErrorDetails().errorCode());
        }
    }

    @Test
    void malformedPageSizeUsesJsonRatherThanRouterNotFound() {
        given().queryParam("maxResults", "two")
                .when().get("/access-policies")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    private EksClient client(String account, Region region) {
        return EksClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "secret")))
                .build();
    }
}
