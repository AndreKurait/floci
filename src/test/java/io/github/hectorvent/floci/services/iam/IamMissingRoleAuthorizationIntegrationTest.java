package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class IamMissingRoleAuthorizationIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
            "Principal":{"Service":"lambda.amazonaws.com"}}]}
            """;

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void scopedLookupDistinguishesAnAbsentRoleFromDeniedNames() throws Exception {
        String name = "bootstrap-" + UUID.randomUUID();
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"iam:GetRole",
                "Resource":"arn:aws:iam::%s:role/*%s"}]}
                """.formatted(ACCOUNT, name);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "role-lookup-", "read", policy);
             IamClient caller = AwsTestClients.create(IamClient.builder(), user.accessKeyId(),
                     user.secretAccessKey(), Region.US_EAST_1, endpoint);
             IamClient admin = AwsTestClients.create(IamClient.builder(), "test", "test", Region.US_EAST_1, endpoint)) {
            assertFailure(caller, name, 404, "NoSuchEntity");
            assertFailure(caller, name + "-unrelated", 403, "AccessDenied");
            admin.createRole(request -> request.roleName(name).path("/bootstrap/")
                    .assumeRolePolicyDocument(TRUST));
            try {
                assertEquals("arn:aws:iam::" + ACCOUNT + ":role/bootstrap/" + name,
                        caller.getRole(request -> request.roleName(name)).role().arn());
            } finally {
                admin.deleteRole(request -> request.roleName(name));
            }
            assertFailure(caller, name, 404, "NoSuchEntity");
        }
    }

    @Test
    void explicitDenyStillAppliesWhenTheRoleDoesNotExist() throws Exception {
        String name = "denied-" + UUID.randomUUID();
        String policy = """
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"iam:GetRole","Resource":"*"},
                {"Effect":"Deny","Action":"iam:GetRole","Resource":"arn:aws:iam::%s:role/%s"}]}
                """.formatted(ACCOUNT, name);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "role-deny-", "read", policy);
             IamClient caller = AwsTestClients.create(IamClient.builder(), user.accessKeyId(),
                     user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            assertFailure(caller, name, 403, "AccessDenied");
            assertFailure(caller, "other-" + name, 404, "NoSuchEntity");
        }
    }

    @Test
    void anExistingRoleRetainsItsStoredPathForAuthorization() throws Exception {
        String name = "path-" + UUID.randomUUID();
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"iam:GetRole",
                "Resource":"arn:aws:iam::%s:role/%s"}]}
                """.formatted(ACCOUNT, name);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "role-path-", "read", policy);
             IamClient caller = AwsTestClients.create(IamClient.builder(), user.accessKeyId(),
                     user.secretAccessKey(), Region.US_EAST_1, endpoint);
             IamClient admin = AwsTestClients.create(IamClient.builder(), "test", "test", Region.US_EAST_1, endpoint)) {
            assertFailure(caller, name, 404, "NoSuchEntity");
            admin.createRole(request -> request.roleName(name).path("/restricted/")
                    .assumeRolePolicyDocument(TRUST));
            try {
                assertFailure(caller, name, 403, "AccessDenied");
            } finally {
                admin.deleteRole(request -> request.roleName(name));
            }
        }
    }

    private static void assertFailure(IamClient client, String role, int status, String code) {
        IamException error = assertThrows(IamException.class,
                () -> client.getRole(request -> request.roleName(role)));
        assertEquals(status, error.statusCode());
        assertEquals(code, error.awsErrorDetails().errorCode());
    }
}
