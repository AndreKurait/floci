package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AssumeRoleSourceIdentityIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ALL = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"sts:*\",\"Resource\":\"*\"}}";
    private static final String TRUST = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\""
            + ACCOUNT + "\"},\"Action\":\"sts:*\"}}";
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;

    @ParameterizedTest
    @ValueSource(strings = {"direct", "absent", "trust-missing", "identity-missing", "trust-condition",
            "identity-condition", "chain", "chain-changed", "chain-trust-missing", "chain-identity-missing",
            "downstream-deny", "invalid-value", "wrong-token", "required-missing", "chain-same", "chain-both-keys"})
    void signedIssuanceAndChainingPreserveSourceIdentity(String scenario) throws Exception {
        String identity = "identity-missing".equals(scenario) ? ALL.replace("sts:*", "sts:AssumeRole") : ALL;
        if ("identity-condition".equals(scenario)) {
            identity = ALL.replace("\"Resource\":\"*\"", "\"Resource\":\"*\","
                    + "\"Condition\":{\"StringEquals\":{\"sts:SourceIdentity\":\"original\"}}");
        }
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "source-id-", "assume", identity);
             IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                     .build();
             StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build();
             Resources resources = new Resources(admin)) {
            String trust = "trust-missing".equals(scenario) ? TRUST.replace("sts:*", "sts:AssumeRole") : TRUST;
            if ("trust-condition".equals(scenario) || "required-missing".equals(scenario)) {
                trust = TRUST.replace("\"Action\":\"sts:*\"", "\"Action\":\"sts:*\","
                        + "\"Condition\":{\"StringEquals\":{\"sts:SourceIdentity\":\"original\"}}");
            }
            Role first = resources.role(trust);
            String source = ("absent".equals(scenario) || "required-missing".equals(scenario)) ? null : "invalid-value".equals(scenario) ? "bad value" : "original";
            AssumeRoleRequest request = AssumeRoleRequest.builder().roleArn(first.arn()).roleSessionName("first")
                    .sourceIdentity(source).build();
            if (List.of("trust-missing", "identity-missing", "invalid-value", "required-missing").contains(scenario)) {
                StsException error = assertThrows(StsException.class, () -> resources.issue(sts, request));
                assertEquals("invalid-value".equals(scenario) ? 400 : 403, error.statusCode());
                return;
            }
            AssumeRoleResponse issued = resources.issue(sts, request);
            assertEquals(source, issued.sourceIdentity());
            Credentials credentials = issued.credentials();
            assertEquals(source, iam.findSourceIdentity(credentials.accessKeyId(), credentials.sessionToken()));
            assertNull(iam.findSourceIdentity(credentials.accessKeyId(), "wrong"));
            if (scenario.startsWith("chain") || "wrong-token".equals(scenario)) {
                String secondTrust = "chain-trust-missing".equals(scenario)
                        ? TRUST.replace("sts:*", "sts:AssumeRole") : TRUST;
                if ("chain-both-keys".equals(scenario)) {
                    secondTrust = TRUST.replace("\"Action\":\"sts:*\"", "\"Action\":\"sts:*\","
                            + "\"Condition\":{\"StringEquals\":{\"sts:SourceIdentity\":\"original\","
                            + "\"aws:SourceIdentity\":\"original\"}}");
                }
                Role second = resources.role(secondTrust);
                resources.policy(first, "chain-identity-missing".equals(scenario)
                        ? ALL.replace("sts:*", "sts:AssumeRole") : ALL);
                try (StsClient chained = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                                credentials.accessKeyId(), credentials.secretAccessKey(), "wrong-token".equals(scenario)
                                        ? "wrong" : credentials.sessionToken()))).build()) {
                    AssumeRoleRequest next = AssumeRoleRequest.builder().roleArn(second.arn()).roleSessionName("second")
                            .sourceIdentity("chain-changed".equals(scenario) ? "changed"
                                    : "chain-same".equals(scenario) ? "original" : null).build();
                    if (!List.of("chain", "chain-same", "chain-both-keys").contains(scenario)) {
                        assertEquals(403, assertThrows(StsException.class, () -> resources.issue(chained, next)).statusCode());
                        return;
                    }
                    AssumeRoleResponse nextSession = resources.issue(chained, next);
                    assertEquals("original", nextSession.sourceIdentity());
                    credentials = nextSession.credentials();
                    first = second;
                }
            }
            String expected = "downstream-deny".equals(scenario) ? "other" : "original";
            String condition = "absent".equals(scenario)
                    ? "\"Null\":{\"aws:SourceIdentity\":\"true\"}"
                    : "\"StringEquals\":{\"aws:SourceIdentity\":\"" + expected + "\"}";
            resources.policy(first, "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"ssm:DescribeParameters\","
                    + "\"Resource\":\"*\",\"Condition\":{" + condition + "}}}");
            try (SsmClient ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                            credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()))).build()) {
                if ("downstream-deny".equals(scenario)) {
                    assertEquals("AccessDeniedException", assertThrows(SsmException.class,
                            () -> ssm.describeParameters()).awsErrorDetails().errorCode());
                } else {
                    assertDoesNotThrow(() -> ssm.describeParameters());
                }
            }
        }
    }

    private final class Resources implements AutoCloseable {
        private final IamClient admin;
        private final List<String> names = new ArrayList<>();
        private final List<Credentials> sessions = new ArrayList<>();
        private final List<String> policies = new ArrayList<>();

        private Resources(IamClient admin) {
            this.admin = admin;
        }

        private Role role(String trust) {
            String name = "source-id-" + UUID.randomUUID();
            names.add(name);
            return admin.createRole(b -> b.roleName(name).path("/source/identity/").assumeRolePolicyDocument(trust)).role();
        }

        private void policy(Role role, String document) {
            if (!policies.contains(role.roleName())) {
                policies.add(role.roleName());
            }
            admin.putRolePolicy(b -> b.roleName(role.roleName()).policyName("source").policyDocument(document));
        }

        private AssumeRoleResponse issue(StsClient sts, AssumeRoleRequest request) {
            AssumeRoleResponse response = sts.assumeRole(request);
            sessions.add(response.credentials());
            return response;
        }

        @Override
        public void close() {
            List<Throwable> failures = new ArrayList<>();
            for (Credentials session : sessions.reversed()) {
                try {
                    iam.unregisterSession(ACCOUNT, session.accessKeyId());
                    assertTrue(iam.resolveCallerArns(session.accessKeyId()).isEmpty());
                } catch (RuntimeException | AssertionError failure) {
                    failures.add(failure);
                }
            }
            for (String name : names.reversed()) {
                if (policies.contains(name)) {
                    try {
                        admin.deleteRolePolicy(b -> b.roleName(name).policyName("source"));
                    } catch (NoSuchEntityException expected) {
                        // A rejected policy assignment has nothing to delete.
                    } catch (RuntimeException | AssertionError failure) {
                        failures.add(failure);
                    }
                }
                try {
                    admin.deleteRole(b -> b.roleName(name));
                    assertTrue(iam.findRole(ACCOUNT, name).isEmpty());
                } catch (NoSuchEntityException expected) {
                    // The registered unique name might not have been created by a rejected request.
                } catch (RuntimeException | AssertionError failure) {
                    failures.add(failure);
                }
            }
            if (!failures.isEmpty()) {
                AssertionError failure = new AssertionError("Source identity fixture cleanup failed");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }
    }
}
