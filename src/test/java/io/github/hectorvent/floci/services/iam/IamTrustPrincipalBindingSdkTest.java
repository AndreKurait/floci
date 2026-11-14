package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.AccessKeyMetadata;
import software.amazon.awssdk.services.iam.model.EntityType;
import software.amazon.awssdk.services.iam.model.GetAccountAuthorizationDetailsResponse;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.ListAccessKeysResponse;
import software.amazon.awssdk.services.iam.model.ListRolePoliciesResponse;
import software.amazon.awssdk.services.iam.model.ListRolesResponse;
import software.amazon.awssdk.services.iam.model.ListUserPoliciesResponse;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.iam.model.RoleDetail;
import software.amazon.awssdk.services.iam.model.Tag;
import software.amazon.awssdk.services.iam.model.User;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises trust-principal identity through signed public IAM and STS requests. */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
@Timeout(90)
class IamTrustPrincipalBindingSdkTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String ROOT_ARN = "arn:aws:iam::" + ACCOUNT + ":root";
    private static final String ASSUME_POLICY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"}]}
            """;
    private static final Set<String> TERMINAL = Set.of("CREATE_COMPLETE", "CREATE_FAILED", "ROLLBACK_COMPLETE",
            "ROLLBACK_FAILED", "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE", "UPDATE_ROLLBACK_FAILED",
            "DELETE_COMPLETE", "DELETE_FAILED");

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    IamService service;

    @ParameterizedTest
    @ValueSource(strings = {"role", "user"})
    void missingPrincipalCannotCreateARole(String kind) {
        try (Owned owned = new Owned("missing-create-" + kind)) {
            String target = owned.name("target");
            IamException error = assertThrows(IamException.class, () ->
                    owned.createRole(target, trust(owned.arn(kind, "missing"))));
            malformed(error);
            missing(assertThrows(IamException.class, () -> owned.admin.getRole(b -> b.roleName(target))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "user"})
    void rejectedArrayUpdatePreservesTheEntireHeldRole(String kind) {
        try (Owned owned = new Owned("missing-update-" + kind)) {
            Role source = owned.createRole(owned.name("source"), trust(ROOT_ARN));
            Role target = owned.createRole(owned.name("target"), trust(ROOT_ARN));
            owned.rolePolicy(target.roleName());
            Role before = owned.role(target.roleName());
            IamException error = assertThrows(IamException.class, () -> owned.admin.updateAssumeRolePolicy(b ->
                    b.roleName(target.roleName()).policyDocument(trust(source.arn(), owned.arn(kind, "missing")))));
            malformed(error);
            assertRoleState(before, owned.role(target.roleName()));
            assertEquals(json(ASSUME_POLICY), json(owned.admin.getRolePolicy(b ->
                    b.roleName(target.roleName()).policyName("assume")).policyDocument()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "user"})
    void liveIdsAreAcceptedAndDeletedIdsCannotBeSavedAgain(String kind) {
        try (Owned owned = new Owned("explicit-id-" + kind)) {
            String name = owned.name("source");
            Role sourceRole = "role".equals(kind) ? owned.createRole(name, trust(ROOT_ARN)) : null;
            User sourceUser = "user".equals(kind) ? owned.createUser(name) : null;
            String id = sourceRole == null ? sourceUser.userId() : sourceRole.roleId();
            String arn = sourceRole == null ? sourceUser.arn() : sourceRole.arn();
            Role target = owned.createRole(owned.name("target"), trust(id));
            assertEquals(Set.of(arn), principals(owned.role(target.roleName())));
            owned.admin.updateAssumeRolePolicy(b -> b.roleName(target.roleName()).policyDocument(trust(ROOT_ARN)));
            owned.admin.updateAssumeRolePolicy(b -> b.roleName(target.roleName()).policyDocument(trust(id)));
            assertEquals(Set.of(arn), principals(owned.role(target.roleName())));
            if (sourceRole != null) {
                owned.rolePolicy(name);
            }
            AwsCredentials oldCaller = sourceRole == null ? owned.userCaller(name)
                    : owned.assume(owned.root, arn, "explicit-id-old");
            owned.assume(oldCaller, target.arn(), "explicit-id-target-old");

            if (sourceRole == null) {
                owned.deleteUser(name);
            } else {
                owned.deleteRole(name);
            }
            Role before = owned.role(target.roleName());
            assertEquals(Set.of(id), principals(before));
            malformed(assertThrows(IamException.class, () -> owned.admin.updateAssumeRolePolicy(b ->
                    b.roleName(target.roleName()).policyDocument(trust(id)))));
            assertRoleState(before, owned.role(target.roleName()));
            String refused = owned.name("refused");
            malformed(assertThrows(IamException.class, () -> owned.createRole(refused, trust(id))));
            missing(assertThrows(IamException.class, () -> owned.admin.getRole(b -> b.roleName(refused))));

            Role replacementRole = sourceRole == null ? null : owned.createRole(name, trust(ROOT_ARN));
            User replacementUser = sourceRole == null ? owned.createUser(name) : null;
            String newId = replacementRole == null ? replacementUser.userId() : replacementRole.roleId();
            assertNotEquals(id, newId);
            assertEquals(arn, replacementRole == null ? replacementUser.arn() : replacementRole.arn());
            if (replacementRole != null) {
                owned.rolePolicy(name);
            }
            AwsCredentials newCaller = replacementRole == null ? owned.userCaller(name)
                    : owned.assume(owned.root, arn, "explicit-id-new");
            denied(owned, newCaller, target.arn());
            malformed(assertThrows(IamException.class, () -> owned.admin.updateAssumeRolePolicy(b ->
                    b.roleName(target.roleName()).policyDocument(trust(id)))));
            assertRoleState(before, owned.role(target.roleName()));
            owned.admin.updateAssumeRolePolicy(b -> b.roleName(target.roleName()).policyDocument(trust(newId)));
            assertEquals(Set.of(arn), principals(owned.role(target.roleName())));
            owned.assume(newCaller, target.arn(), "explicit-id-target-new");
        }
    }

    @Test
    void targetIdentityErrorsPrecedePrincipalAdmission() {
        try (Owned owned = new Owned("target-precedence")) {
            Role target = owned.createRole(owned.name("target"), trust(ROOT_ARN));
            Role before = owned.role(target.roleName());
            String missingPrincipal = owned.arn("role", "missing-principal");
            IamException duplicate = assertThrows(IamException.class, () ->
                    owned.createRole(target.roleName(), trust(missingPrincipal)));
            assertEquals(409, duplicate.statusCode());
            assertEquals("EntityAlreadyExists", duplicate.awsErrorDetails().errorCode());
            assertRoleState(before, owned.role(target.roleName()));

            String missingTarget = owned.name("missing-target");
            owned.ownRole(missingTarget);
            missing(assertThrows(IamException.class, () -> owned.admin.updateAssumeRolePolicy(b ->
                    b.roleName(missingTarget).policyDocument(trust(missingPrincipal)))));
            missing(assertThrows(IamException.class, () -> owned.admin.getRole(b -> b.roleName(missingTarget))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "user"})
    void principalLookupRequiresItsActualPath(String kind) {
        try (Owned owned = new Owned("wrong-path-" + kind)) {
            String name = owned.name("source");
            String arn = "role".equals(kind) ? owned.createRole(name, trust(ROOT_ARN)).arn()
                    : owned.createUser(name).arn();
            String wrong = arn.replace(owned.path, "/wrong-owned-path/");
            assertNotEquals(arn, wrong);
            String target = owned.name("target");
            malformed(assertThrows(IamException.class, () -> owned.createRole(target, trust(wrong))));
            missing(assertThrows(IamException.class, () -> owned.admin.getRole(b -> b.roleName(target))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "user"})
    void partitionBoundariesApplyToArnsAndExplicitIds(String kind) {
        try (Owned owned = new Owned("wrong-partition-" + kind)) {
            Role sourceRole = "role".equals(kind) ? owned.createRole(owned.name("source"), trust(ROOT_ARN)) : null;
            User sourceUser = "user".equals(kind) ? owned.createUser(owned.name("source")) : null;
            String arn = sourceRole == null ? sourceUser.arn() : sourceRole.arn();
            String id = sourceRole == null ? sourceUser.userId() : sourceRole.roleId();
            String wrong = arn.replace("arn:aws:", "arn:aws-cn:");
            String refused = owned.name("refused");
            malformed(assertThrows(IamException.class, () -> owned.createRole(refused, trust(wrong))));
            missing(assertThrows(IamException.class, () -> owned.admin.getRole(b -> b.roleName(refused))));
            Role target = owned.createRole(owned.name("target"), trust(ROOT_ARN));
            Role before = owned.role(target.roleName());
            malformed(assertThrows(IamException.class, () -> owned.admin.updateAssumeRolePolicy(b ->
                    b.roleName(target.roleName()).policyDocument(trust(wrong)))));
            assertRoleState(before, owned.role(target.roleName()));

            String otherRefused = owned.name("other-refused");
            malformed(assertThrows(IamException.class, () ->
                    owned.createChinaRole(otherRefused, trust(id))));
            missing(assertThrows(IamException.class, () -> owned.admin.getRole(b -> b.roleName(otherRefused))));
            Role other = owned.createChinaRole(owned.name("other"), trust(ROOT_ARN.replace("arn:aws:", "arn:aws-cn:")));
            Role otherBefore = owned.role(other.roleName());
            for (String principal : List.of(id, arn)) {
                malformed(assertThrows(IamException.class, () -> owned.admin.updateAssumeRolePolicy(b ->
                        b.roleName(other.roleName()).policyDocument(trust(principal)))));
                assertRoleState(otherBefore, owned.role(other.roleName()));
            }
            malformed(assertThrows(IamException.class, () -> owned.admin.updateAssumeRolePolicy(b ->
                    b.roleName(target.roleName()).policyDocument(trust(other.roleId())))));
            assertRoleState(before, owned.role(target.roleName()));
        }
    }

    @Test
    void recreatedRoleNeedsAnExplicitTrustSaveBeforeItsNewSessionCanAssume() {
        try (Owned owned = new Owned("role-recreation")) {
            Role source = owned.createRole(owned.name("source"), trust(ROOT_ARN));
            owned.rolePolicy(source.roleName());
            Role target = owned.createRole(owned.name("target"), trust(source.arn()));
            assertEquals(Set.of(source.arn()), principals(owned.role(target.roleName())));
            AwsCredentials oldCaller = owned.assume(owned.root, source.arn(), "source-old");
            owned.assume(oldCaller, target.arn(), "target-old");

            owned.deleteRole(source.roleName());
            assertEquals(Set.of(source.roleId()), principals(owned.role(target.roleName())));
            Role recreated = owned.createRole(source.roleName(), trust(ROOT_ARN));
            owned.rolePolicy(recreated.roleName());
            assertEquals(source.arn(), recreated.arn());
            assertNotEquals(source.roleId(), recreated.roleId());
            AwsCredentials newCaller = owned.assume(owned.root, recreated.arn(), "source-new");
            assertEquals(Set.of(source.roleId()), principals(owned.role(target.roleName())));
            denied(owned, newCaller, target.arn());

            owned.admin.updateAssumeRolePolicy(b -> b.roleName(target.roleName())
                    .policyDocument(trust(recreated.arn())));
            assertEquals(Set.of(recreated.arn()), principals(owned.role(target.roleName())));
            owned.assume(newCaller, target.arn(), "target-repaired");
        }
    }

    @Test
    void recreatedUserNeedsAnExplicitTrustSaveBeforeItsNewKeyCanAssume() {
        try (Owned owned = new Owned("user-recreation")) {
            User source = owned.createUser(owned.name("source"));
            AwsCredentials oldCaller = owned.userCaller(source.userName());
            Role target = owned.createRole(owned.name("target"), trust(source.arn()));
            owned.assume(oldCaller, target.arn(), "target-old");
            owned.deleteUser(source.userName());
            assertEquals(Set.of(source.userId()), principals(owned.role(target.roleName())));

            User recreated = owned.createUser(source.userName());
            AwsCredentials newCaller = owned.userCaller(recreated.userName());
            assertEquals(source.arn(), recreated.arn());
            assertNotEquals(source.userId(), recreated.userId());
            assertEquals(Set.of(source.userId()), principals(owned.role(target.roleName())));
            denied(owned, newCaller, target.arn());
            owned.admin.updateAssumeRolePolicy(b -> b.roleName(target.roleName())
                    .policyDocument(trust(recreated.arn())));
            assertEquals(Set.of(recreated.arn()), principals(owned.role(target.roleName())));
            owned.assume(newCaller, target.arn(), "target-repaired");
        }
    }

    @Test
    void principalArnConditionContinuesMatchingTheRecreatedRole() {
        try (Owned owned = new Owned("condition-arn")) {
            Role source = owned.createRole(owned.name("source"), trust(ROOT_ARN));
            owned.rolePolicy(source.roleName());
            ObjectNode policy = (ObjectNode) json(trust(ROOT_ARN));
            ((ObjectNode) policy.path("Statement").get(0)).putObject("Condition")
                    .putObject("ArnEquals").put("aws:PrincipalArn", source.arn());
            Role target = owned.createRole(owned.name("target"), policy.toString());
            owned.assume(owned.assume(owned.root, source.arn(), "source-old"), target.arn(), "target-old");
            owned.deleteRole(source.roleName());
            Role recreated = owned.createRole(source.roleName(), trust(ROOT_ARN));
            owned.rolePolicy(recreated.roleName());
            assertNotEquals(source.roleId(), recreated.roleId());
            assertEquals(policy, json(owned.role(target.roleName()).assumeRolePolicyDocument()));
            owned.assume(owned.assume(owned.root, recreated.arn(), "source-new"), target.arn(), "target-new");
        }
    }

    @Test
    void renamedUserKeepsItsIdentityAndDisplaysItsCurrentArn() {
        try (Owned owned = new Owned("user-rename")) {
            User source = owned.createUser(owned.name("source"));
            AwsCredentials caller = owned.userCaller(source.userName());
            Role target = owned.createRole(owned.name("target"), trust(source.arn()));
            owned.assume(caller, target.arn(), "target-before");
            String renamed = owned.name("renamed");
            owned.ownUser(renamed);
            owned.ids.put("user:" + renamed, source.userId());
            owned.admin.updateUser(b -> b.userName(source.userName()).newUserName(renamed));
            User current = owned.user(renamed);
            assertEquals(source.userId(), current.userId());
            assertNotEquals(source.arn(), current.arn());
            missing(assertThrows(IamException.class, () -> owned.admin.getUser(b -> b.userName(source.userName()))));
            assertEquals(Set.of(current.arn()), principals(owned.role(target.roleName())));
            owned.assume(caller, target.arn(), "target-after");
        }
    }

    @Test
    void listAndAuthorizationDetailsResolveLiveAndDeletedIdsAcrossRegions() {
        try (Owned owned = new Owned("all-projections")) {
            Role role = owned.createRole(owned.name("source"), trust(ROOT_ARN));
            User user = owned.createUser(owned.name("user"));
            Role target = owned.createRole(owned.name("target"), trust(role.arn(), user.arn()));
            try (IamClient regional = owned.iam(Region.EU_WEST_1)) {
                owned.checkProjections(regional, target.roleName(), Set.of(role.arn(), user.arn()));
                owned.deleteRole(role.roleName());
                owned.deleteUser(user.userName());
                owned.checkProjections(regional, target.roleName(), Set.of(role.roleId(), user.userId()));
                Role recreatedRole = owned.createRole(role.roleName(), trust(ROOT_ARN));
                User recreatedUser = owned.createUser(user.userName());
                assertNotEquals(role.roleId(), recreatedRole.roleId());
                assertNotEquals(user.userId(), recreatedUser.userId());
                owned.checkProjections(regional, target.roleName(), Set.of(role.roleId(), user.userId()));
            }
        }
    }

    @Test
    void bindingLeavesConditionsAccountsServicesAndOtherStatementFieldsIntact() {
        try (Owned owned = new Owned("mixed-document")) {
            Role source = owned.createRole(owned.name("source"), trust(ROOT_ARN));
            User user = owned.createUser(owned.name("user"));
            ObjectNode document = (ObjectNode) json(trust(source.arn(), user.arn(), ROOT_ARN));
            ObjectNode statement = (ObjectNode) document.path("Statement").get(0);
            statement.put("Sid", "PreservedStatement");
            ((ObjectNode) statement.path("Principal")).put("Service", "lambda.amazonaws.com");
            statement.putObject("Condition").putObject("ArnEquals").put("aws:PrincipalArn", source.arn());
            Role target = owned.createRole(owned.name("target"), document.toString());
            assertEquals(normalizedPrincipalOrder(document.toString()),
                    normalizedPrincipalOrder(owned.role(target.roleName()).assumeRolePolicyDocument()));
            owned.deleteRole(source.roleName());
            owned.deleteUser(user.userName());
            ObjectNode expected = document.deepCopy();
            ArrayNode expectedAws = ((ObjectNode) expected.path("Statement").get(0).path("Principal")).putArray("AWS");
            expectedAws.add(source.roleId()).add(user.userId()).add(ROOT_ARN);
            assertEquals(normalizedPrincipalOrder(expected.toString()),
                    normalizedPrincipalOrder(owned.role(target.roleName()).assumeRolePolicyDocument()));
        }
    }

    @Test
    void cloudFormationCreatesTheReferencedRoleBeforeSavingItsTrust() {
        try (Owned owned = new Owned("cfn-dependency")) {
            ObjectNode template = MAPPER.createObjectNode();
            ObjectNode resources = template.putObject("Resources");
            ObjectNode source = owned.cfnRole(resources, "Source", "source");
            source.set("AssumeRolePolicyDocument", json(trust(ROOT_ARN)));
            source.putArray("Policies").addObject().put("PolicyName", "assume")
                    .set("PolicyDocument", json(ASSUME_POLICY));
            ObjectNode target = owned.cfnRole(resources, "Target", "target");
            ObjectNode targetTrust = (ObjectNode) json(trust(ROOT_ARN));
            ((ObjectNode) targetTrust.path("Statement").get(0).path("Principal")).putObject("AWS")
                    .putArray("Fn::GetAtt").add("Source").add("Arn");
            target.set("AssumeRolePolicyDocument", targetTrust);

            List<StackEvent> events = owned.createStack(template.toString(), "CREATE_COMPLETE");
            Role actualSource = owned.role(owned.name("source"));
            Role actualTarget = owned.role(owned.name("target"));
            assertEquals(Set.of(actualSource.arn()), principals(actualTarget));
            for (String logical : List.of("Source", "Target")) {
                String name = owned.name("Source".equals(logical) ? "source" : "target");
                assertEquals(name, owned.cfn().describeStackResource(b -> b.stackName(owned.stackId)
                        .logicalResourceId(logical)).stackResourceDetail().physicalResourceId());
                assertTrue(events.stream().anyMatch(event -> logical.equals(event.logicalResourceId())
                        && name.equals(event.physicalResourceId()) && "AWS::IAM::Role".equals(event.resourceType())
                        && "CREATE_COMPLETE".equals(event.resourceStatusAsString())));
            }
            owned.assume(owned.assume(owned.root, actualSource.arn(), "cfn-source"),
                    actualTarget.arn(), "cfn-target");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "user"})
    void cloudFormationRejectsAnUncreatedPrincipalAndRollsBack(String kind) {
        try (Owned owned = new Owned("cfn-missing-" + kind)) {
            ObjectNode template = MAPPER.createObjectNode();
            ObjectNode target = owned.cfnRole(template.putObject("Resources"), "Target", "target");
            String missingPrincipal = owned.arn(kind, "missing");
            target.set("AssumeRolePolicyDocument", json(trust(missingPrincipal)));
            List<StackEvent> events = owned.createStack(template.toString(), "ROLLBACK_COMPLETE");
            assertTrue(events.stream().anyMatch(event -> "Target".equals(event.logicalResourceId())
                    && "AWS::IAM::Role".equals(event.resourceType())
                    && "CREATE_FAILED".equals(event.resourceStatusAsString())
                    && event.resourceStatusReason() != null
                    && event.resourceStatusReason().contains("Invalid principal in policy")
                    && event.resourceStatusReason().contains(missingPrincipal)), events.toString());
            missing(assertThrows(IamException.class, () ->
                    owned.admin.getRole(b -> b.roleName(owned.name("target")))));
        }
    }

    private final class Owned implements AutoCloseable {
        private final String caseName;
        private final String marker = "trust-sdk-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        private final String path = "/trust-sdk/" + marker + "/";
        private final List<Tag> tags = List.of(Tag.builder().key("audit-owner").value(marker).build());
        private final Set<String> roles = new LinkedHashSet<>();
        private final Set<String> cfnRoles = new LinkedHashSet<>();
        private final Set<String> users = new LinkedHashSet<>();
        private final Map<String, String> ids = new LinkedHashMap<>();
        private final Map<String, String> rolePartitions = new LinkedHashMap<>();
        private final List<Credentials> sessions = new ArrayList<>();
        private final WireReceipt wire = new WireReceipt();
        private final AwsCredentials root = AwsBasicCredentials.create("test", "test");
        private final IamClient admin;
        private CloudFormationClient cloudFormation;
        private String stackId;
        private boolean stackAttempted;
        private boolean stackDeleted;

        private Owned(String caseName) {
            this.caseName = caseName;
            admin = iam(Region.US_EAST_1);
        }

        private String name(String label) {
            return marker + "-" + label;
        }

        private String arn(String kind, String label) {
            return "arn:aws:iam::" + ACCOUNT + ":" + kind + path + name(label);
        }

        private IamClient iam(Region region) {
            return IamClient.builder().endpointOverride(endpoint)
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).region(region)
                    .credentialsProvider(StaticCredentialsProvider.create(root))
                    .overrideConfiguration(configuration ->
                    configuration.addExecutionInterceptor(wire).retryPolicy(RetryPolicy.none())
                            .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
        }

        private StsClient sts(AwsCredentials caller) {
            return StsClient.builder().endpointOverride(endpoint)
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(caller))
                    .overrideConfiguration(configuration ->
                    configuration.addExecutionInterceptor(wire).retryPolicy(RetryPolicy.none())
                            .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
        }

        private CloudFormationClient cfn() {
            if (cloudFormation == null) {
                cloudFormation = CloudFormationClient.builder().endpointOverride(endpoint)
                        .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(root))
                        .overrideConfiguration(config ->
                        config.addExecutionInterceptor(wire).retryPolicy(RetryPolicy.none())
                                .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
            }
            return cloudFormation;
        }

        private ObjectNode cfnRole(ObjectNode resources, String logical, String label) {
            String roleName = name(label);
            ownRole(roleName);
            cfnRoles.add(roleName);
            return resources.putObject(logical).put("Type", "AWS::IAM::Role").putObject("Properties")
                    .put("RoleName", roleName).put("Path", path);
        }

        private List<StackEvent> createStack(String template, String expected) {
            if (!stackAttempted) {
                missingStack(assertThrows(CloudFormationException.class, () ->
                        cfn().describeStacks(b -> b.stackName(marker))), marker);
            }
            stackAttempted = true;
            stackId = cfn().createStack(b -> b.stackName(marker).templateBody(template)
                    .capabilities(Capability.CAPABILITY_NAMED_IAM)
                    .tags(tag -> tag.key("audit-owner").value(marker))).stackId();
            stack(stackId);
            return awaitEvents(Set.of(), expected);
        }

        private Stack stack(String lookup) {
            DescribeStacksResponse response = cfn().describeStacks(b -> b.stackName(lookup));
            assertEquals(1, response.stacks().size());
            Stack current = response.stacks().getFirst();
            assertEquals(marker, current.stackName());
            assertTrue(current.stackId().startsWith(
                    "arn:aws:cloudformation:us-east-1:" + ACCOUNT + ":stack/" + marker + "/"));
            assertTrue(current.tags().stream().anyMatch(tag ->
                    "audit-owner".equals(tag.key()) && marker.equals(tag.value())));
            if (stackId != null) {
                assertEquals(stackId, current.stackId());
            }
            stackId = current.stackId();
            return current;
        }

        private List<StackEvent> events() {
            List<StackEvent> result = new ArrayList<>();
            Set<String> cursors = new LinkedHashSet<>();
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                String next = cursor;
                DescribeStackEventsResponse response = cfn().describeStackEvents(b ->
                        b.stackName(stackId).nextToken(next));
                result.addAll(response.stackEvents());
                cursor = response.nextToken();
                if (cursor == null) {
                    assertEquals(result.size(), eventIds(result).size());
                    assertTrue(result.stream().allMatch(event -> stackId.equals(event.stackId())));
                    return result;
                }
                assertTrue(cursors.add(cursor), "Repeated event cursor");
            }
            throw new AssertionError("Owned event history exceeded twenty pages");
        }

        private List<StackEvent> awaitEvents(Set<String> before, String expected) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            List<StackEvent> latest = List.of();
            while (System.nanoTime() < deadline) {
                latest = events().stream().filter(event -> !before.contains(event.eventId())).toList();
                for (StackEvent event : latest) {
                    if (stackId.equals(event.stackId()) && stackId.equals(event.physicalResourceId())
                            && marker.equals(event.logicalResourceId())
                            && "AWS::CloudFormation::Stack".equals(event.resourceType())
                            && TERMINAL.contains(event.resourceStatusAsString())) {
                        assertEquals(expected, event.resourceStatusAsString(), latest.toString());
                        assertEquals(expected, stack(stackId).stackStatusAsString());
                        return latest;
                    }
                }
                pause();
            }
            throw new AssertionError("Owned stack did not reach " + expected + ": " + latest);
        }

        private void cleanupStack() {
            if (!stackAttempted || stackDeleted) {
                return;
            }
            String lookup = stackId == null ? marker : stackId;
            try {
                stack(lookup);
            } catch (CloudFormationException absent) {
                missingStack(absent, lookup);
                stackDeleted = true;
                return;
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                String status = stack(stackId).stackStatusAsString();
                if (TERMINAL.contains(status)) {
                    if (!"DELETE_COMPLETE".equals(status)) {
                        Set<String> before = eventIds(events());
                        cfn().deleteStack(b -> b.stackName(stackId));
                        awaitEvents(before, "DELETE_COMPLETE");
                    }
                    missingStack(assertThrows(CloudFormationException.class, () ->
                            cfn().describeStacks(b -> b.stackName(marker))), marker);
                    stackDeleted = true;
                    return;
                }
                pause();
            }
            throw new AssertionError("Owned stack remained in progress during cleanup");
        }

        private void ownRole(String name) {
            if (!roles.contains(name)) {
                missing(assertThrows(IamException.class, () -> admin.getRole(b -> b.roleName(name))));
                roles.add(name);
            }
        }

        private void ownUser(String name) {
            if (!users.contains(name)) {
                missing(assertThrows(IamException.class, () -> admin.getUser(b -> b.userName(name))));
                users.add(name);
            }
        }

        private Role createRole(String name, String policy) {
            ownRole(name);
            Role created = admin.createRole(b -> b.roleName(name).path(path).tags(tags)
                    .description("owned trust principal fixture").maxSessionDuration(3600)
                    .assumeRolePolicyDocument(policy)).role();
            ids.put("role:" + name, created.roleId());
            assertRoleState(created, role(name));
            return created;
        }

        private Role createChinaRole(String name, String policy) {
            ownRole(name);
            rolePartitions.put(name, "aws-cn");
            try (IamClient regional = iam(Region.CN_NORTH_1)) {
                Role created = regional.createRole(b -> b.roleName(name).path(path).tags(tags)
                        .description("owned trust principal fixture").maxSessionDuration(3600)
                        .assumeRolePolicyDocument(policy)).role();
                ids.put("role:" + name, created.roleId());
                assertRoleState(created, role(name));
                return created;
            }
        }

        private User createUser(String name) {
            ownUser(name);
            User created = admin.createUser(b -> b.userName(name).path(path).tags(tags)).user();
            ids.put("user:" + name, created.userId());
            assertEquals(created, user(name));
            return created;
        }

        private Role role(String name) {
            Role value = admin.getRole(b -> b.roleName(name)).role();
            assertEquals(name, value.roleName());
            assertEquals(path, value.path());
            assertEquals("arn:" + rolePartitions.getOrDefault(name, "aws") + ":iam::"
                    + ACCOUNT + ":role" + path + name, value.arn());
            assertEquals(cfnRoles.contains(name) ? List.of() : tags, value.tags());
            assertTrue(roles.contains(name));
            String previous = ids.putIfAbsent("role:" + name, value.roleId());
            if (previous != null) {
                assertEquals(previous, value.roleId());
            }
            return value;
        }

        private User user(String name) {
            User value = admin.getUser(b -> b.userName(name)).user();
            assertEquals(name, value.userName());
            assertEquals(path, value.path());
            assertEquals("arn:aws:iam::" + ACCOUNT + ":user" + path + name, value.arn());
            assertEquals(tags, value.tags());
            assertTrue(users.contains(name));
            String previous = ids.putIfAbsent("user:" + name, value.userId());
            if (previous != null) {
                assertEquals(previous, value.userId());
            }
            return value;
        }

        private void rolePolicy(String name) {
            role(name);
            admin.putRolePolicy(b -> b.roleName(name).policyName("assume").policyDocument(ASSUME_POLICY));
        }

        private AwsCredentials userCaller(String name) {
            user(name);
            admin.putUserPolicy(b -> b.userName(name).policyName("assume").policyDocument(ASSUME_POLICY));
            AccessKey key = admin.createAccessKey(b -> b.userName(name)).accessKey();
            return AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey());
        }

        private AwsCredentials assume(AwsCredentials caller, String roleArn, String name) {
            try (StsClient client = sts(caller)) {
                Credentials issued = client.assumeRole(b -> b.roleArn(roleArn).roleSessionName(name)).credentials();
                sessions.add(issued);
                AwsCredentials credentials = AwsSessionCredentials.create(issued.accessKeyId(),
                        issued.secretAccessKey(), issued.sessionToken());
                try (StsClient session = sts(credentials)) {
                    assertEquals(ACCOUNT, session.getCallerIdentity().account());
                }
                return credentials;
            }
        }

        private void checkProjections(IamClient client, String name, Set<String> expected) {
            assertEquals(expected, principals(client.getRole(b -> b.roleName(name)).role()));
            List<Role> listed = new ArrayList<>();
            String marker = null;
            Set<String> seen = new LinkedHashSet<>();
            do {
                String next = marker;
                ListRolesResponse page = client.listRoles(b -> b.pathPrefix(path).maxItems(1).marker(next));
                listed.addAll(page.roles());
                marker = page.isTruncated() ? page.marker() : null;
                assertTrue(marker == null || seen.add(marker));
                assertTrue(seen.size() < 30);
            } while (marker != null);
            Role listedTarget = listed.stream().filter(value -> name.equals(value.roleName())).findFirst().orElseThrow();
            assertEquals(expected, principals(listedTarget));
            List<RoleDetail> detailed = new ArrayList<>();
            marker = null;
            seen.clear();
            do {
                String next = marker;
                GetAccountAuthorizationDetailsResponse page = client.getAccountAuthorizationDetails(b ->
                        b.filter(EntityType.ROLE).maxItems(1).marker(next));
                detailed.addAll(page.roleDetailList());
                marker = page.isTruncated() ? page.marker() : null;
                assertTrue(marker == null || seen.add(marker));
                assertTrue(seen.size() < 300);
            } while (marker != null);
            RoleDetail detail = detailed.stream().filter(value -> name.equals(value.roleName())).findFirst().orElseThrow();
            assertEquals(expected, principals(detail.assumeRolePolicyDocument()));
        }

        private void deleteRole(String name) {
            try {
                role(name);
            } catch (IamException failure) {
                missing(failure);
                return;
            }
            ListRolePoliciesResponse policies = admin.listRolePolicies(b -> b.roleName(name));
            assertFalse(policies.isTruncated());
            assertTrue(policies.policyNames().stream().allMatch("assume"::equals));
            for (String policy : policies.policyNames()) {
                admin.deleteRolePolicy(b -> b.roleName(name).policyName(policy));
            }
            assertTrue(admin.listAttachedRolePolicies(b -> b.roleName(name)).attachedPolicies().isEmpty());
            admin.deleteRole(b -> b.roleName(name));
            missing(assertThrows(IamException.class, () -> admin.getRole(b -> b.roleName(name))));
            ids.remove("role:" + name);
        }

        private void deleteUser(String name) {
            try {
                user(name);
            } catch (IamException failure) {
                missing(failure);
                return;
            }
            ListAccessKeysResponse keys = admin.listAccessKeys(b -> b.userName(name));
            assertFalse(keys.isTruncated());
            for (AccessKeyMetadata key : keys.accessKeyMetadata()) {
                assertEquals(name, key.userName());
                admin.deleteAccessKey(b -> b.userName(name).accessKeyId(key.accessKeyId()));
            }
            ListUserPoliciesResponse policies = admin.listUserPolicies(b -> b.userName(name));
            assertFalse(policies.isTruncated());
            assertTrue(policies.policyNames().stream().allMatch("assume"::equals));
            for (String policy : policies.policyNames()) {
                admin.deleteUserPolicy(b -> b.userName(name).policyName(policy));
            }
            assertTrue(admin.listAttachedUserPolicies(b -> b.userName(name)).attachedPolicies().isEmpty());
            admin.deleteUser(b -> b.userName(name));
            missing(assertThrows(IamException.class, () -> admin.getUser(b -> b.userName(name))));
            ids.remove("user:" + name);
        }

        @Override
        public void close() {
            IllegalStateException failures = new IllegalStateException("Owned IAM fixture cleanup failed");
            // STS has no public session-revocation operation; remove only credentials returned to this fixture.
            for (Credentials session : sessions.reversed()) {
                cleanup(() -> {
                    service.unregisterSession(ACCOUNT, session.accessKeyId());
                    assertTrue(service.resolveCallerArns(session.accessKeyId()).isEmpty());
                }, failures);
            }
            cleanup(this::cleanupStack, failures);
            for (String name : new ArrayList<>(roles).reversed()) {
                cleanup(() -> deleteRole(name), failures);
            }
            for (String name : new ArrayList<>(users).reversed()) {
                cleanup(() -> deleteUser(name), failures);
            }
            cleanup(admin::close, failures);
            if (cloudFormation != null) {
                cleanup(cloudFormation::close, failures);
            }
            cleanup(() -> wire.save(caseName, marker, failures.getSuppressed().length == 0), failures);
            if (failures.getSuppressed().length != 0) {
                throw failures;
            }
        }
    }

    private static String trust(String... arns) {
        ObjectNode document = MAPPER.createObjectNode().put("Version", "2012-10-17");
        ObjectNode statement = document.putArray("Statement").addObject()
                .put("Effect", "Allow").put("Action", "sts:AssumeRole");
        ObjectNode principal = statement.putObject("Principal");
        if (arns.length == 1) {
            principal.put("AWS", arns[0]);
        } else {
            ArrayNode values = principal.putArray("AWS");
            for (String arn : arns) {
                values.add(arn);
            }
        }
        return document.toString();
    }

    private static JsonNode json(String document) {
        try {
            return MAPPER.readTree(document.startsWith("%")
                    ? URLDecoder.decode(document, StandardCharsets.UTF_8) : document);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void assertRoleState(Role expected, Role actual) {
        assertEquals(expected.toBuilder().assumeRolePolicyDocument(
                        normalizedPrincipalOrder(expected.assumeRolePolicyDocument()).toString()).build(),
                actual.toBuilder().assumeRolePolicyDocument(
                        normalizedPrincipalOrder(actual.assumeRolePolicyDocument()).toString()).build());
    }

    private static JsonNode normalizedPrincipalOrder(String document) {
        JsonNode root = json(document);
        JsonNode statements = root.path("Statement");
        Iterable<JsonNode> entries = statements.isArray() ? statements : List.of(statements);
        for (JsonNode statement : entries) {
            JsonNode aws = statement.path("Principal").path("AWS");
            if (aws instanceof ArrayNode array) {
                List<JsonNode> values = new ArrayList<>();
                array.forEach(values::add);
                values.sort(Comparator.comparing(JsonNode::toString));
                array.removeAll().addAll(values);
            }
        }
        return root;
    }

    private static Set<String> principals(Role role) {
        return principals(role.assumeRolePolicyDocument());
    }

    private static Set<String> principals(String document) {
        JsonNode aws = json(document).path("Statement").get(0).path("Principal").path("AWS");
        Set<String> values = new LinkedHashSet<>();
        if (aws.isArray()) {
            aws.forEach(value -> values.add(value.asText()));
        } else {
            values.add(aws.asText());
        }
        return values;
    }

    private static void denied(Owned owned, AwsCredentials caller, String targetArn) {
        try (StsClient client = owned.sts(caller)) {
            StsException error = assertThrows(StsException.class, () -> owned.sessions.add(
                    client.assumeRole(b -> b.roleArn(targetArn).roleSessionName("must-be-denied")).credentials()));
            assertEquals(403, error.statusCode());
            assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
        }
    }

    private static void malformed(IamException error) {
        assertEquals(400, error.statusCode());
        assertEquals("MalformedPolicyDocument", error.awsErrorDetails().errorCode());
    }

    private static void missing(IamException error) {
        assertEquals(404, error.statusCode());
        assertEquals("NoSuchEntity", error.awsErrorDetails().errorCode());
    }

    private static Set<String> eventIds(List<StackEvent> events) {
        Set<String> result = new LinkedHashSet<>();
        for (StackEvent event : events) {
            assertTrue(event.eventId() != null && !event.eventId().isBlank());
            assertTrue(result.add(event.eventId()), "Duplicate event id");
        }
        return result;
    }

    private static void missingStack(CloudFormationException error, String lookup) {
        assertEquals(400, error.statusCode());
        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
        assertEquals("Stack with id " + lookup + " does not exist", error.awsErrorDetails().errorMessage());
    }

    private static void pause() {
        try {
            Thread.sleep(40);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static void cleanup(Runnable action, IllegalStateException failures) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (failure != failures) {
                failures.addSuppressed(failure);
            }
        }
    }

    private static final class WireReceipt implements ExecutionInterceptor {
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("iam-trust-principal-wire-id");
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            assertTrue(calls.size() < 3000);
            attributes.putAttribute(ID, calls.size());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", calls.size());
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("requestModel", model(context.request()));
            Map<String, List<String>> headers = new LinkedHashMap<>(context.httpRequest().headers());
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("authorization")
                    || name.equalsIgnoreCase("x-amz-security-token"));
            row.put("requestHeaders", headers);
            row.put("requestBodyAvailable", context.requestBody().isPresent());
            try (InputStream stream = context.requestBody().isPresent()
                    ? context.requestBody().orElseThrow().contentStreamProvider().newStream()
                    : InputStream.nullInputStream()) {
                row.put("requestBase64", encode(stream));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            calls.add(row);
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            Map<String, Object> row = calls.get(attributes.getAttribute(ID));
            row.put("status", context.httpResponse().statusCode());
            row.put("responseHeaders", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElseGet(InputStream::nullInputStream)) {
                String encoded = encode(stream);
                row.put("responseBase64", encoded);
                return Optional.of(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
            calls.get(attributes.getAttribute(ID)).put("responseModel", model(context.response()));
        }

        @Override
        public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
            Integer id = attributes.getAttribute(ID);
            if (id == null) {
                return;
            }
            Map<String, Object> row = calls.get(id);
            row.put("exceptionType", context.exception().getClass().getName());
            if (context.exception() instanceof AwsServiceException failure) {
                row.put("errorCode", failure.awsErrorDetails().errorCode());
                row.put("errorMessage", failure.awsErrorDetails().errorMessage());
                row.put("errorStatus", failure.statusCode());
            }
        }

        private static Object model(Object value) {
            if (value instanceof SdkPojo pojo) {
                Map<String, Object> fields = new LinkedHashMap<>();
                pojo.sdkFields().forEach(field -> {
                    Object member = field.getValueOrDefault(pojo);
                    if (member != null) {
                        fields.put(field.memberName(), model(member));
                    }
                });
                return fields;
            }
            if (value instanceof List<?> list) {
                return list.stream().map(WireReceipt::model).toList();
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> fields = new LinkedHashMap<>();
                map.forEach((key, member) -> fields.put(key.toString(), model(member)));
                return fields;
            }
            if (value instanceof SdkBytes bytes) {
                return Base64.getEncoder().encodeToString(bytes.asByteArray());
            }
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                return value;
            }
            return value.toString();
        }

        private static String encode(InputStream stream) throws IOException {
            byte[] bytes = stream.readNBytes(1_048_577);
            assertTrue(bytes.length <= 1_048_576);
            return Base64.getEncoder().encodeToString(bytes);
        }

        private void save(String caseName, String marker, boolean cleanupComplete) {
            try {
                Path directory = Path.of(System.getProperty("audit.iam.trust.principal.directory",
                        "target/iam-trust-principal-wire"));
                Files.createDirectories(directory);
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(),
                        Map.of("case", caseName, "marker", marker, "cleanupComplete", cleanupComplete, "calls", calls));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "every attempted transmission has a complete captured response");
        }
    }
}
