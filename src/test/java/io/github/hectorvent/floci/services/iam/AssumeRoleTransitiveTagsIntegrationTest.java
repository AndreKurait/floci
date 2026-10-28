package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;
import software.amazon.awssdk.services.sts.model.Tag;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AssumeRoleTransitiveTagsIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String TRUST = "{\"Statement\":{\"Effect\":\"Allow\","
            + "\"Principal\":{\"AWS\":\"" + ACCOUNT + "\"},\"Action\":\"sts:*\"}}";
    private static final String POLICY = "{\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"sts:*\",\"Resource\":\"*\"},{\"Effect\":\"Allow\","
            + "\"Action\":\"ssm:DescribeParameters\",\"Resource\":\"*\","
            + "\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/Project\":\"session\"}}}]}";
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;
    Supplier<String> roleNames = () -> "chain-tags-" + UUID.randomUUID();
    Supplier<String> adminNames = () -> "chain-admin-" + UUID.randomUUID();

    @ParameterizedTest
    @ValueSource(strings = {"direct", "nontransitive", "role-tag", "one-hop", "two-hops",
            "collision", "case-collision", "role-override", "trust-key-allow", "trust-key-deny",
            "identity-key-deny", "inherited-tag-permission", "inherited-not-request-tag", "foreign-hop", "key-case-condition"})
    void signedRoleChainRetainsOnlyTransitiveSessionTags(String scenario) throws Exception {
        String roleAccount = "foreign-hop".equals(scenario) ? "111122223333" : ACCOUNT;
        List<String> names = new ArrayList<>();
        List<String> policies = new ArrayList<>();
        List<Credentials> sessions = new ArrayList<>();
        String identity = "identity-key-deny".equals(scenario) || "key-case-condition".equals(scenario)
                ? "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"sts:*\",\"Resource\":\"*\","
                        + "\"Condition\":{\"ForAllValues:StringEquals\":{\"sts:TransitiveTagKeys\":[\""
                        + ("key-case-condition".equals(scenario) ? "Project" : "Allowed") + "\"]}}}}"
                : POLICY;
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "chain-tags-", "assume", identity);
             IamClient admin = admin(roleAccount);
             StsClient firstClient = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build()) {
            try {
                List<Role> roles = new ArrayList<>();
                for (int index = 0; index < 3; index++) {
                    String name = roleNames.get();
                    String trust = "foreign-hop".equals(scenario)
                            ? TRUST.replace("\"AWS\":\"" + ACCOUNT + "\"", "\"AWS\":[\"" + ACCOUNT
                                    + "\",\"" + roleAccount + "\"]") : TRUST;
                    if (index == 0 && ("trust-key-allow".equals(scenario) || "trust-key-deny".equals(scenario))) {
                        trust = TRUST.replace("\"Action\":\"sts:*\"", "\"Action\":\"sts:*\",\"Condition\":{"
                                + "\"ForAllValues:StringEquals\":{\"sts:TransitiveTagKeys\":[\""
                                + ("trust-key-allow".equals(scenario) ? "Project" : "Allowed") + "\"]}} ");
                    } else if (index == 1 && "inherited-tag-permission".equals(scenario)) {
                        trust = TRUST.replace("sts:*", "sts:AssumeRole");
                    } else if (index == 1 && "inherited-not-request-tag".equals(scenario)) {
                        trust = TRUST.replace("\"Action\":\"sts:*\"", "\"Action\":\"sts:*\","
                                + "\"Condition\":{\"Null\":{\"aws:RequestTag/Project\":\"true\"}}");
                    }
                    Role role = admin.createRole(CreateRoleRequest.builder()
                            .roleName(name).path("/teams/chained-tags/").assumeRolePolicyDocument(trust).build()).role();
                    names.add(name);
                    roles.add(role);
                    admin.putRolePolicy(b -> b.roleName(name).policyName("chain").policyDocument(POLICY));
                    policies.add(name);
                }
                if ("role-tag".equals(scenario) || "role-override".equals(scenario)) {
                    admin.tagRole(b -> b.roleName(roles.get("role-tag".equals(scenario) ? 0 : 1).roleName())
                            .tags(software.amazon.awssdk.services.iam.model.Tag.builder()
                                    .key("Project").value("role-tag".equals(scenario) ? "session" : "role").build())); // IAM and STS both name their tag model Tag.
                }
                AssumeRoleRequest.Builder firstRequest = AssumeRoleRequest.builder()
                        .roleArn(roles.getFirst().arn()).roleSessionName("first");
                if (!"role-tag".equals(scenario)) {
                    firstRequest.tags(Tag.builder().key("Project").value("session").build());
                }
                if (!"nontransitive".equals(scenario) && !"role-tag".equals(scenario)) {
                    firstRequest.transitiveTagKeys("Project");
                }
                if ("key-case-condition".equals(scenario)) {
                    firstRequest.transitiveTagKeys("Project", "project");
                }
                if ("trust-key-deny".equals(scenario) || "identity-key-deny".equals(scenario)
                        || "key-case-condition".equals(scenario)) {
                    assertThrows(StsException.class, () -> sessions.add(
                            firstClient.assumeRole(firstRequest.build()).credentials()),
                            "Requested transitive keys must constrain both trust and identity policy decisions");
                    return;
                }
                Credentials first = firstClient.assumeRole(firstRequest.build()).credentials();
                sessions.add(first);
                assertCanRead(first);
                if ("direct".equals(scenario) || "trust-key-allow".equals(scenario)) {
                    return;
                }
                Credentials second;
                try (StsClient secondClient = sts(first)) {
                    AssumeRoleRequest.Builder secondRequest = AssumeRoleRequest.builder()
                            .roleArn(roles.get(1).arn()).roleSessionName("second");
                    if ("inherited-tag-permission".equals(scenario)) {
                        assertThrows(StsException.class, () -> sessions.add(
                                secondClient.assumeRole(secondRequest.build()).credentials()),
                                "A role receiving inherited tags must allow TagSession");
                        return;
                    }
                    if ("collision".equals(scenario) || "case-collision".equals(scenario)) {
                        secondRequest.tags(Tag.builder().key("case-collision".equals(scenario)
                                ? "project" : "Project").value("replacement").build());
                        assertThrows(StsException.class, () -> {
                            Credentials unexpected = secondClient.assumeRole(secondRequest.build()).credentials();
                            sessions.add(unexpected);
                        }, "An explicit session tag must not replace an inherited transitive tag");
                        return;
                    }
                    second = secondClient.assumeRole(secondRequest.build()).credentials();
                    sessions.add(second);
                }
                if ("nontransitive".equals(scenario) || "role-tag".equals(scenario)) {
                    try (SsmClient ssm = ssm(second)) {
                        assertThrows(SsmException.class, ssm::describeParameters,
                                "Unmarked session tags and attached role tags must not become inherited tags");
                    }
                    return;
                }
                if ("two-hops".equals(scenario)) {
                    try (StsClient thirdClient = sts(second)) {
                        Credentials third = thirdClient.assumeRole(b -> b.roleArn(roles.get(2).arn())
                                .roleSessionName("third")).credentials();
                        sessions.add(third);
                        assertCanRead(third);
                    }
                } else {
                    assertCanRead(second);
                }
            } finally {
                Collections.reverse(sessions);
                for (Credentials session : sessions) {
                    iam.unregisterSession(ACCOUNT, session.accessKeyId());
                    iam.unregisterSession(roleAccount, session.accessKeyId());
                    assertTrue(iam.resolveCallerArns(session.accessKeyId()).isEmpty());
                }
                Collections.reverse(names);
                for (String name : names) {
                    if (iam.findRole(roleAccount, name).isPresent()) {
                        try {
                            if (policies.contains(name)) {
                                admin.deleteRolePolicy(b -> b.roleName(name).policyName("chain"));
                            }
                        } finally {
                            admin.deleteRole(b -> b.roleName(name));
                            assertTrue(iam.findRole(roleAccount, name).isEmpty());
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedRoleCreationPreservesUnownedRoleAndRemovesEarlierOwnedRoles(boolean createFirst) {
        String existing = "chain-existing-" + UUID.randomUUID();
        String first = "chain-owned-" + UUID.randomUUID();
        RequestScopes.runAs(ACCOUNT, () -> iam.createRole(existing, "/teams/chained-tags/", TRUST, null, 3600, Map.of()));
        cleanup.register(() -> {
            if (iam.findRole(ACCOUNT, existing).isPresent()) {
                RequestScopes.runAs(ACCOUNT, () -> iam.deleteRole(existing));
            }
        });
        AssumeRoleTransitiveTagsIntegrationTest fixture = nestedFixture();
        AtomicInteger requests = new AtomicInteger();
        fixture.roleNames = () -> createFirst && requests.getAndIncrement() == 0 ? first : existing;

        assertThrows(EntityAlreadyExistsException.class,
                () -> fixture.signedRoleChainRetainsOnlyTransitiveSessionTags("direct"));

        assertTrue(iam.findRole(ACCOUNT, existing).isPresent(),
                "Rejected creation must not transfer cleanup ownership of an existing role");
        assertTrue(iam.findRole(ACCOUNT, first).isEmpty(),
                "A role created before a later rejection must still be removed");
    }

    @Test
    void rejectedAdminCreationPreservesPreexistingForeignUser() {
        String account = "111122223333";
        String existing = "chain-existing-admin-" + UUID.randomUUID();
        RequestScopes.runAs(account, () -> iam.createUser(existing, "/"));
        AssumeRoleTransitiveTagsIntegrationTest fixture = nestedFixture();
        fixture.adminNames = () -> existing;
        try {
            assertThrows(AwsException.class, () -> fixture.admin(account));
            fixture.cleanup.afterEach(null);
            assertDoesNotThrow(() -> RequestScopes.callAs(account, () -> iam.getUser(existing)),
                    "Failed admin creation must not register deletion of the existing user");
        } finally {
            if (iam.findUser(account, existing).isPresent()) {
                RequestScopes.runAs(account, () -> iam.deleteUser(existing));
            }
        }
    }

    private AssumeRoleTransitiveTagsIntegrationTest nestedFixture() {
        AssumeRoleTransitiveTagsIntegrationTest fixture = new AssumeRoleTransitiveTagsIntegrationTest();
        fixture.iam = iam;
        fixture.endpoint = endpoint;
        return fixture;
    }

    private IamClient admin(String account) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create("test", "test");
        if (!ACCOUNT.equals(account)) {
            String name = adminNames.get();
            RequestScopes.runAs(account, () -> iam.createUser(name, "/"));
            cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUser(name)));
            AccessKey key = RequestScopes.callAs(account, () -> iam.createAccessKey(name));
            cleanup.register(() -> RequestScopes.runAs(account,
                    () -> iam.deleteAccessKey(name, key.getAccessKeyId())));
            RequestScopes.runAs(account, () -> iam.putUserPolicy(name, "admin",
                    "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"iam:*\",\"Resource\":\"*\"}}"));
            cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUserPolicy(name, "admin")));
            credentials = AwsBasicCredentials.create(key.getAccessKeyId(), key.getSecretAccessKey());
        }
        return IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
    }

    private void assertCanRead(Credentials credentials) {
        try (SsmClient ssm = ssm(credentials)) {
            assertDoesNotThrow(() -> { ssm.describeParameters(); },
                    "The issued session must retain Project=session for downstream policy evaluation");
        }
    }

    private StsClient sts(Credentials credentials) {
        return StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(provider(credentials)).build();
    }

    private SsmClient ssm(Credentials credentials) {
        return SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(provider(credentials)).build();
    }

    private static StaticCredentialsProvider provider(Credentials credentials) {
        return StaticCredentialsProvider.create(AwsSessionCredentials.create(credentials.accessKeyId(),
                credentials.secretAccessKey(), credentials.sessionToken()));
    }
}
