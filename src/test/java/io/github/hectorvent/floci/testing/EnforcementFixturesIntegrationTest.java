package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EnforcementFixturesIntegrationTest {

    @InjectSpy
    IamService iamService;

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void rejectedUserCreationPreservesExistingUser() {
        String name = "fixture-existing-user-" + UUID.randomUUID();
        PartitionCleanup attempted = new PartitionCleanup();
        iam("CreateUser", "UserName", name).statusCode(200);
        try {
            assertThrows(AssertionError.class, () -> EnforcementFixtures.createUser(
                    attempted, baseUri.getPort(), auth("test", "iam"), name));
            attempted.afterEach(mock(ExtensionContext.class));
            iam("GetUser", "UserName", name).statusCode(200);
        } finally {
            EnforcementFixtures.removeUser(baseUri.getPort(), auth("test", "iam"), name);
        }
    }

    @Test
    void rejectedRoleCreationPreservesExistingRole() {
        String name = "fixture-existing-role-" + UUID.randomUUID();
        PartitionCleanup attempted = new PartitionCleanup();
        iam("CreateRole", "RoleName", name, "AssumeRolePolicyDocument", trust()).statusCode(200);
        try {
            assertThrows(AssertionError.class, () -> EnforcementFixtures.createRole(
                    attempted, baseUri.getPort(), "000000000000", "/", name, trust(), allow()));
            attempted.afterEach(mock(ExtensionContext.class));
            iam("GetRole", "RoleName", name).statusCode(200);
        } finally {
            EnforcementFixtures.removeRole(baseUri.getPort(), auth("test", "iam"), name);
        }
    }

    @Test
    void successfulUserSetupRemovesOwnedUserAndKey() {
        String name = "fixture-owned-user-" + UUID.randomUUID();
        PartitionCleanup owned = new PartitionCleanup();
        try {
            EnforcementFixtures.createUser(owned, baseUri.getPort(), auth("test", "iam"), name);
            iam("GetUser", "UserName", name).statusCode(200);
            owned.afterEach(mock(ExtensionContext.class));
            iam("GetUser", "UserName", name).statusCode(404);
        } finally {
            EnforcementFixtures.removeUser(baseUri.getPort(), auth("test", "iam"), name);
        }
    }

    @Test
    void successfulRoleSetupRemovesOwnedRoleAndPolicy() {
        String name = "fixture-owned-role-" + UUID.randomUUID();
        PartitionCleanup owned = new PartitionCleanup();
        try {
            EnforcementFixtures.createRole(owned, baseUri.getPort(), "000000000000", "/", name,
                    trust(), allow());
            iam("GetRole", "RoleName", name).statusCode(200);
            owned.afterEach(mock(ExtensionContext.class));
            iam("GetRole", "RoleName", name).statusCode(404);
        } finally {
            EnforcementFixtures.removeRole(baseUri.getPort(), auth("test", "iam"), name);
        }
    }

    @Test
    void failedPolicySetupStillRemovesNewRole() {
        String name = "fixture-policy-failure-" + UUID.randomUUID();
        PartitionCleanup owned = new PartitionCleanup();
        doThrow(new AwsException("ServiceFailure", "Injected policy setup failure", 500))
                .when(iamService).putRolePolicy(eq(name), eq("p"), anyString());
        try {
            assertThrows(AssertionError.class, () -> EnforcementFixtures.createRole(
                    owned, baseUri.getPort(), "000000000000", "/", name, trust(), allow()));
            verify(iamService).putRolePolicy(eq(name), eq("p"), anyString());
            iam("GetRole", "RoleName", name).statusCode(200);
            owned.afterEach(mock(ExtensionContext.class));
            iam("GetRole", "RoleName", name).statusCode(404);
        } finally {
            EnforcementFixtures.removeRole(baseUri.getPort(), auth("test", "iam"), name);
        }
    }

    @Test
    void partiallyWrittenPolicyIsRemovedWithOwnedRole() {
        String name = "fixture-partial-policy-" + UUID.randomUUID();
        PartitionCleanup owned = new PartitionCleanup();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new AwsException("ServiceFailure", "Injected failure after policy write", 500);
        }).when(iamService).putRolePolicy(eq(name), eq("p"), anyString());
        try {
            assertThrows(AssertionError.class, () -> EnforcementFixtures.createRole(
                    owned, baseUri.getPort(), "000000000000", "/", name, trust(), allow()));
            verify(iamService).putRolePolicy(eq(name), eq("p"), anyString());
            iam("GetRolePolicy", "RoleName", name, "PolicyName", "p").statusCode(200);
            owned.afterEach(mock(ExtensionContext.class));
            verify(iamService).deleteRolePolicy(name, "p");
            iam("GetRole", "RoleName", name).statusCode(404);
        } finally {
            EnforcementFixtures.removeRole(baseUri.getPort(), auth("test", "iam"), name);
        }
    }

    private ValidatableResponse iam(String action, String... parameters) {
        RequestSpecification request = given().port(baseUri.getPort())
                .header("Authorization", auth("test", "iam"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action);
        for (int i = 0; i < parameters.length; i += 2) {
            request.formParam(parameters[i], parameters[i + 1]);
        }
        return request.post("/").then();
    }

    private static String trust() {
        return "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":"
                + "\"arn:aws:iam::000000000000:root\"},\"Action\":\"sts:AssumeRole\"}}";
    }

    private static String allow() {
        return "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"}}";
    }

    @Test
    void removeBucketPurgesNullVersionVersionsAndMarkersAcrossPages() {
        String bucket = "fixture-purge-" + UUID.randomUUID();
        String authorization = auth("test", "s3");
        String key = "/" + bucket + "/first.txt";

        given().header("Authorization", authorization).when().put("/" + bucket)
                .then().statusCode(200);
        given().header("Authorization", authorization).body("before versioning")
                .when().put("/" + bucket + "/null.txt").then().statusCode(200);
        given().header("Authorization", authorization)
                .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
                .queryParam("versioning", "")
                .when().put("/" + bucket).then().statusCode(200);
        for (int i = 0; i < 11; i++) {
            given().header("Authorization", authorization).body("version " + i)
                    .when().put(key).then().statusCode(200);
        }
        given().header("Authorization", authorization).when().delete(key).then().statusCode(204);
        given().header("Authorization", authorization).body("other key")
                .when().put("/" + bucket + "/second.txt").then().statusCode(200);
        given().urlEncodingEnabled(false).header("Authorization", authorization)
                .body("reserved key")
                .when().put("/" + bucket + "/dir/reserved%3Fhash%23%2Bpercent%25%20space.txt")
                .then().statusCode(200);

        given().header("Authorization", authorization).queryParam("versions", "")
                .queryParam("max-keys", 10)
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<IsTruncated>true</IsTruncated>"))
                .body(containsString("<NextKeyMarker>first.txt</NextKeyMarker>"));
        given().header("Authorization", authorization).queryParam("versions", "")
                .queryParam("prefix", "null.txt")
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<VersionId>null</VersionId>"));
        given().header("Authorization", authorization).queryParam("versions", "")
                .queryParam("prefix", "dir/")
                .when().get("/" + bucket).then().statusCode(200)
                .body(containsString("<Key>dir/reserved?hash#+percent% space.txt</Key>"));

        EnforcementFixtures.removeBucket(baseUri.getPort(), authorization, bucket);

        given().header("Authorization", authorization).when().head("/" + bucket)
                .then().statusCode(404);
    }
}
