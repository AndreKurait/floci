package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.AccessEntry;
import software.amazon.awssdk.services.eks.model.EksException;
import software.amazon.awssdk.services.iam.model.AccessKey;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.eks.EksAccessEntryUpdateSdkTest.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EksAccessEntryUpdateIamTest {
    @TestHTTPResource
    URI endpoint;

    @Test
    void exactEntryAllowRejectsSiblingClusterArnAndUnrelatedAction() {
        try (Fixture fixture = new Fixture(endpoint, "us-east-1")) {
            AccessEntry first = fixture.createEntry(fixture.principal, "STANDARD");
            AccessEntry sibling = fixture.createEntry(fixture.siblingPrincipal, "STANDARD");
            String username = fixture.name + "-caller";
            AccessKey key = user(fixture, username);
            try (EksClient caller = client(endpoint, "us-east-1", key.accessKeyId(), key.secretAccessKey())) {
                denied(caller, fixture, fixture.principal);
                given().port(endpoint.getPort()).header("Authorization", EnforcementFixtures.auth(key.accessKeyId(), "eks"))
                        .header("X-Amz-Target", "AmazonEKS.DescribeAccessEntry").contentType("application/json")
                        .urlEncodingEnabled(false).body("{\"username\":\"spoofed\"}").post(fixture.entryPath())
                        .then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
                assertEquals(first, fixture.read(fixture.principal));
                policy(fixture, username, allow(first.accessEntryArn(), "eks:DescribeAccessEntry", ""));
                denied(caller, fixture, fixture.principal);
                String clusterArn = fixture.eks.describeCluster(request -> request.name(fixture.name)).cluster().arn();
                policy(fixture, username, allow(clusterArn, "eks:UpdateAccessEntry", ""));
                denied(caller, fixture, fixture.principal);
                policy(fixture, username, allow(first.accessEntryArn(), "eks:UpdateAccessEntry", ""));
                caller.updateAccessEntry(request -> request.clusterName(fixture.name).principalArn(fixture.principal)
                        .username("allowed"));
                assertEquals("allowed", fixture.read(fixture.principal).username());
                denied(caller, fixture, fixture.siblingPrincipal);
                assertEquals(sibling, fixture.read(fixture.siblingPrincipal));
                AccessEntry beforeDeny = fixture.read(fixture.principal);
                policy(fixture, username, "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"eks:UpdateAccessEntry\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Deny\",\"Action\":\"eks:UpdateAccessEntry\",\"Resource\":\""
                        + first.accessEntryArn() + "\"}]}");
                denied(caller, fixture, fixture.principal);
                assertEquals(beforeDeny, fixture.read(fixture.principal));
            }
        }
    }

    @Test
    void requestKeysUseSuppliedUsernameGroupsAndEncodedPathIdentity() {
        try (Fixture fixture = new Fixture(endpoint, "us-east-1")) {
            AccessEntry first = fixture.createEntry(fixture.principal, "STANDARD");
            String username = fixture.name + "-request-keys";
            AccessKey key = user(fixture, username);
            String condition = "\"Condition\":{\"StringEquals\":{\"eks:clusterName\":\"" + fixture.name
                    + "\",\"eks:principalArn\":\"" + fixture.principal
                    + "\",\"eks:username\":\"request-user\"},"
                    + "\"ForAllValues:StringEquals\":{\"eks:kubernetesGroups\":[\"request-group\"]},"
                    + "\"Null\":{\"eks:kubernetesGroups\":\"false\"}}";
            policy(fixture, username, allow(first.accessEntryArn(), "eks:UpdateAccessEntry", condition));
            try (EksClient caller = client(endpoint, "us-east-1", key.accessKeyId(), key.secretAccessKey())) {
                AccessEntry after = caller.updateAccessEntry(request -> request.clusterName(fixture.name)
                        .principalArn(fixture.principal).username("request-user").kubernetesGroups("request-group")).accessEntry();
                assertEquals("request-user", after.username());
                assertEquals(List.of("request-group"), after.kubernetesGroups());
                assertDenied(() -> caller.updateAccessEntry(request -> request.clusterName(fixture.name)
                        .principalArn(fixture.principal).username("wrong").kubernetesGroups("request-group")));
                assertDenied(() -> caller.updateAccessEntry(request -> request.clusterName(fixture.name)
                        .principalArn(fixture.principal).username("request-user").kubernetesGroups("wrong")));
                // Local request-only semantics: omitted values never borrow the stored values to satisfy a condition.
                assertDenied(() -> caller.updateAccessEntry(request -> request.clusterName(fixture.name)
                        .principalArn(fixture.principal)));
                assertEquals(after, fixture.read(fixture.principal));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"platform", "", " "})
    void liveResourceTagsIncludingEmptyValuesDriveAllowAndExplicitDeny(String value) {
        try (Fixture fixture = new Fixture(endpoint, "us-east-1")) {
            AccessEntry first = fixture.createEntry(fixture.principal, "STANDARD");
            fixture.eks.tagResource(request -> request.resourceArn(first.accessEntryArn()).tags(Map.of("team", value)));
            String username = fixture.name + "-tags";
            AccessKey key = user(fixture, username);
            String condition = "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"" + value + "\"}}";
            policy(fixture, username, allow(first.accessEntryArn(), "eks:UpdateAccessEntry", condition));
            try (EksClient caller = client(endpoint, "us-east-1", key.accessKeyId(), key.secretAccessKey())) {
                caller.updateAccessEntry(request -> request.clusterName(fixture.name).principalArn(fixture.principal)
                        .username("tag-allowed"));
                AccessEntry before = fixture.read(fixture.principal);
                policy(fixture, username, "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"eks:UpdateAccessEntry\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Deny\",\"Action\":\"eks:UpdateAccessEntry\",\"Resource\":\""
                        + first.accessEntryArn() + "\"," + condition + "}]}");
                denied(caller, fixture, fixture.principal);
                assertEquals(before, fixture.read(fixture.principal));
                policy(fixture, username, allow(first.accessEntryArn(), "eks:UpdateAccessEntry", condition));
                fixture.eks.tagResource(request -> request.resourceArn(first.accessEntryArn()).tags(Map.of("team", "changed")));
                AccessEntry retagged = fixture.read(fixture.principal);
                denied(caller, fixture, fixture.principal);
                assertEquals(retagged, fixture.read(fixture.principal));
            }
        }
    }

    private static void denied(EksClient caller, Fixture fixture, String principal) {
        assertDenied(() -> caller.updateAccessEntry(request -> request.clusterName(fixture.name)
                .principalArn(principal).username("denied")));
    }

    private static void assertDenied(Runnable request) {
        EksException error = assertThrows(EksException.class, request::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }

    private static AccessKey user(Fixture fixture, String name) {
        fixture.iam.createUser(request -> request.userName(name));
        fixture.cleanup.push(() -> fixture.iam.deleteUser(request -> request.userName(name)));
        AccessKey key = fixture.iam.createAccessKey(request -> request.userName(name)).accessKey();
        fixture.cleanup.push(() -> fixture.iam.deleteAccessKey(request -> request.userName(name).accessKeyId(key.accessKeyId())));
        fixture.cleanup.push(() -> {
            if (fixture.iam.listUserPolicies(request -> request.userName(name)).policyNames().contains("update")) {
                fixture.iam.deleteUserPolicy(request -> request.userName(name).policyName("update"));
            }
        });
        return key;
    }

    private static void policy(Fixture fixture, String name, String policy) {
        fixture.iam.putUserPolicy(request -> request.userName(name).policyName("update").policyDocument(policy));
    }

    private static String allow(String arn, String action, String condition) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"" + action
                + "\",\"Resource\":\"" + arn + "\"" + (condition.isEmpty() ? "" : "," + condition) + "}]}";
    }
}
