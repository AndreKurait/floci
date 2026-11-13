package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;
import software.amazon.awssdk.services.sts.model.Tag;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** Signed STS issuance and downstream SSM principal-tag authorization. */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AssumeRoleSessionTagsIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;

    @ParameterizedTest
    @ValueSource(strings = {"untagged", "attached-role", "invalid-signature", "missing-tag-permission",
            "explicit-tag-deny", "request-tag-condition", "session-tag-grant", "session-tag-override",
            "identity-tag-missing", "identity-tag-deny", "identity-request-tag", "foreign-session-tag",
            "case-override", "empty-value", "invalid-key", "duplicate-key", "direct-tag-grant"})
    void signedSessionTagContract(String scenario) throws Exception {
        String identity = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"sts:*\",\"Resource\":\"*\"}}";
        if ("identity-tag-missing".equals(scenario) || "direct-tag-grant".equals(scenario)) {
            identity = identity.replace("sts:*", "sts:AssumeRole");
        } else if ("identity-tag-deny".equals(scenario)) {
            identity = "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"sts:*\",\"Resource\":\"*\"},"
                    + "{\"Effect\":\"Deny\",\"Action\":\"sts:TagSession\",\"Resource\":\"*\"}]}";
        } else if ("identity-request-tag".equals(scenario)) {
            identity = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"sts:*\",\"Resource\":\"*\","
                    + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/Project\":\"session\"}}}}";
        }
        String roleAccount = "foreign-session-tag".equals(scenario) ? "111122223333" : ACCOUNT;
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "tag-audit-", "assume", identity);
             IamClient admin = admin(roleAccount);
             StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(user.accessKeyId(),
                             "invalid-signature".equals(scenario) ? "wrong-secret" : user.secretAccessKey()))).build()) {
            String actions = "missing-tag-permission".equals(scenario) || "untagged".equals(scenario)
                    ? "\"sts:AssumeRole\"" : "[\"sts:AssumeRole\",\"sts:TagSession\"]";
            String condition = "request-tag-condition".equals(scenario)
                    ? ",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/Project\":\"session\"}}" : "";
            String deny = "explicit-tag-deny".equals(scenario)
                    ? ",{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"" + ACCOUNT
                    + "\"},\"Action\":\"sts:TagSession\"}" : "";
            String trusted = "direct-tag-grant".equals(scenario)
                    ? "arn:aws:iam::" + ACCOUNT + ":user/" + user.name() : ACCOUNT;
            String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"AWS\":\"" + trusted + "\"},\"Action\":" + actions + condition + "}" + deny + "]}";
            Role role = admin.createRole(b -> b.roleName("tag-audit-" + UUID.randomUUID()).path("/teams/session-tags/")
                    .assumeRolePolicyDocument(trust)).role();
            AtomicReference<Credentials> issued = new AtomicReference<>();
            boolean policyCreated = false;
            try {
                admin.tagRole(b -> b.roleName(role.roleName()).tags(software.amazon.awssdk.services.iam.model.Tag.builder()
                        .key("Project").value("role").build())); // Tag clashes with the STS model Tag.
                String expectedTag = switch (scenario) {
                    case "untagged", "attached-role", "session-tag-override", "case-override" -> "role";
                    case "empty-value" -> "";
                    default -> "session";
                };
                admin.putRolePolicy(b -> b.roleName(role.roleName()).policyName("read")
                        .policyDocument("{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"ssm:DescribeParameters\","
                                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/Project\":\""
                                + expectedTag + "\"}}}}"));
                policyCreated = true;
                AssumeRoleRequest.Builder request = AssumeRoleRequest.builder().roleArn(role.arn())
                        .roleSessionName("signed-tag-audit");
                if (!"untagged".equals(scenario) && !"attached-role".equals(scenario)) {
                    request.tags(Tag.builder().key("invalid-key".equals(scenario) ? "" :
                                    "case-override".equals(scenario) ? "project" : "Project")
                            .value("empty-value".equals(scenario) ? "" : "session").build());
                    if ("duplicate-key".equals(scenario)) {
                        request.tags(Tag.builder().key("Project").value("session").build(),
                                Tag.builder().key("project").value("other").build());
                    }
                }
                if ("invalid-key".equals(scenario) || "duplicate-key".equals(scenario)) {
                    StsException error = assertThrows(StsException.class,
                            () -> issued.set(sts.assumeRole(request.build()).credentials()));
                    assertEquals("ValidationError", error.awsErrorDetails().errorCode());
                } else if ("missing-tag-permission".equals(scenario) || "explicit-tag-deny".equals(scenario)
                        || "identity-tag-missing".equals(scenario) || "identity-tag-deny".equals(scenario)) {
                    StsException error = assertThrows(StsException.class,
                            () -> issued.set(sts.assumeRole(request.build()).credentials()),
                            "Tagged AssumeRole must require sts:TagSession in target trust");
                    assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
                } else if ("invalid-signature".equals(scenario)) {
                    assertThrows(StsException.class, () -> issued.set(sts.assumeRole(request.build()).credentials()));
                } else {
                    issued.set(assertDoesNotThrow(() -> sts.assumeRole(request.build()),
                            "Valid request tags must be visible to trust evaluation").credentials());
                    assertNotNull(issued.get());
                    if (!"request-tag-condition".equals(scenario)) {
                        try (SsmClient ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                                        issued.get().accessKeyId(), issued.get().secretAccessKey(),
                                        issued.get().sessionToken()))).build()) {
                            if ("session-tag-override".equals(scenario) || "case-override".equals(scenario)) {
                                SsmException error = assertThrows(SsmException.class, ssm::describeParameters,
                                        "A session tag must replace the matching attached role tag");
                                assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
                            } else {
                                assertDoesNotThrow(() -> { ssm.describeParameters(); },
                                        "Issued session must expose effective principal tags to authorization");
                            }
                        }
                    }
                }
                assertEquals("role", admin.listRoleTags(b -> b.roleName(role.roleName())).tags().stream()
                        .filter(tag -> "Project".equals(tag.key())).findFirst().orElseThrow().value());
            } finally {
                try {
                    if (issued.get() != null) {
                        iam.unregisterSession(ACCOUNT, issued.get().accessKeyId());
                        iam.unregisterSession(roleAccount, issued.get().accessKeyId());
                        assertTrue(iam.resolveCallerArns(issued.get().accessKeyId()).isEmpty());
                    }
                } finally {
                    try {
                        if (policyCreated) {
                            admin.deleteRolePolicy(b -> b.roleName(role.roleName()).policyName("read"));
                        }
                    } finally {
                        admin.deleteRole(b -> b.roleName(role.roleName()));
                        assertTrue(iam.findRole(roleAccount, role.roleName()).isEmpty());
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tags.member.3", "tags.member.1", "Tag.member.1", "Tags.other.1"})
    void signedTrustAndIdentityConditionsIgnoreUnconsumedTagSpellings(String spelling) throws Exception {
        String identity = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"sts:*\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/Project\":\"session\"}}}}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "tag-audit-", "assume", identity);
             IamClient admin = admin(ACCOUNT)) {
            Role role = admin.createRole(b -> b.roleName("tag-audit-" + UUID.randomUUID())
                    .assumeRolePolicyDocument("{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\""
                            + ACCOUNT + "\"},\"Action\":\"sts:*\"}}")).role();
            String issuedKey = null;
            try {
                String body = "Action=AssumeRole&Version=2011-06-15&RoleSessionName=shape-check&RoleArn="
                        + URLEncoder.encode(role.arn(), StandardCharsets.UTF_8) + "&" + spelling + ".Key=Project&"
                        + spelling + ".Value=session";
                Response response = given().headers(user.signer("sts")
                                .headersFor("POST", endpoint, Map.of(), body.getBytes(StandardCharsets.UTF_8)))
                        .contentType("application/x-www-form-urlencoded").body(body).post(endpoint);
                if (response.statusCode() == 200) {
                    issuedKey = response.xmlPath().getString("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
                }
                assertEquals("Tags.member.3".equals(spelling) ? 200 : 403, response.statusCode());
            } finally {
                if (issuedKey != null) {
                    iam.unregisterSession(ACCOUNT, issuedKey);
                    assertTrue(iam.resolveCallerArns(issuedKey).isEmpty());
                }
                admin.deleteRole(b -> b.roleName(role.roleName()));
                assertTrue(iam.findRole(ACCOUNT, role.roleName()).isEmpty());
            }
        }
    }

    /** Foreign setup uses an account scope; management and STS calls use the issued IAM credentials. */
    private IamClient admin(String account) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create("test", "test");
        if (!ACCOUNT.equals(account)) {
            String name = "tag-audit-admin-" + UUID.randomUUID();
            cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUser(name)));
            RequestScopes.runAs(account, () -> iam.createUser(name, "/"));
            AccessKey key = RequestScopes.callAs(account, () -> iam.createAccessKey(name));
            cleanup.register(() -> RequestScopes.runAs(account,
                    () -> iam.deleteAccessKey(name, key.getAccessKeyId())));
            cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUserPolicy(name, "admin")));
            RequestScopes.runAs(account, () -> iam.putUserPolicy(name, "admin",
                    "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"iam:*\",\"Resource\":\"*\"}}"));
            credentials = AwsBasicCredentials.create(key.getAccessKeyId(), key.getSecretAccessKey());
        }
        return IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
    }
}
