package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.SfnException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.MAPPER;
import static io.github.hectorvent.floci.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.PASS;
import static io.github.hectorvent.floci.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.TRUST;
import static io.github.hectorvent.floci.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.iam;
import static io.github.hectorvent.floci.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.states;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class StepFunctionsTestStateIamIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    private IamClient admin;
    private String name;
    private String roleArn;
    private boolean userCreated;
    private String accessKey;
    private String boundaryArn;

    @BeforeEach
    void createTheOwnedRole() {
        admin = iam(endpoint, "test", "test", "us-east-1");
        name = "state-iam-" + UUID.randomUUID().toString().substring(0, 12);
        roleArn = admin.createRole(request -> request.roleName(name).path("/test-state/")
                .assumeRolePolicyDocument(TRUST)).role().arn();
    }

    @AfterEach
    void deleteOwnedIdentitiesAndPolicies() {
        try {
            if (accessKey != null) {
                admin.deleteAccessKey(request -> request.userName(name).accessKeyId(accessKey));
            }
            if (userCreated) {
                if (boundaryArn != null) {
                    admin.deleteUserPermissionsBoundary(request -> request.userName(name));
                }
                for (String policy : admin.listUserPolicies(request -> request.userName(name)).policyNames()) {
                    admin.deleteUserPolicy(request -> request.userName(name).policyName(policy));
                }
                admin.deleteUser(request -> request.userName(name));
                assertThrows(NoSuchEntityException.class, () -> admin.getUser(request -> request.userName(name)));
            }
            if (boundaryArn != null) {
                admin.deletePolicy(request -> request.policyArn(boundaryArn));
            }
            if (roleArn != null) {
                assertEquals(roleArn, admin.getRole(request -> request.roleName(name)).role().arn());
                admin.deleteRole(request -> request.roleName(name));
                assertThrows(NoSuchEntityException.class, () -> admin.getRole(request -> request.roleName(name)));
            }
        } finally {
            if (admin != null) {
                admin.close();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"allow,true", "no-test,false", "no-pass,false", "wrong-role,false",
            "wrong-service,false", "deny-test,false", "deny-pass,false"})
    void testStateAndPassRoleAreBothRequired(String mode, boolean allowed) throws Exception {
        List<Map<String, Object>> statements = new ArrayList<>();
        if (!"no-test".equals(mode)) {
            statements.add(statement("Allow", "states:TestState", "*"));
        }
        if (!"no-pass".equals(mode)) {
            Map<String, Object> pass = Map.of("Effect", "Allow", "Action", "iam:PassRole",
                    "Resource", "wrong-role".equals(mode) ? roleArn + "-other" : roleArn,
                    "Condition", Map.of("StringEquals", Map.of("iam:PassedToService",
                            "wrong-service".equals(mode) ? "lambda.amazonaws.com" : "states.amazonaws.com")));
            statements.add(pass);
        }
        if ("deny-test".equals(mode) || "deny-pass".equals(mode)) {
            statements.add(statement("Deny", "deny-test".equals(mode) ? "states:TestState" : "iam:PassRole", "*"));
        }
        try (SfnClient actor = actor(statements)) {
            if (allowed) {
                assertEquals("SUCCEEDED", actor.testState(request -> request.definition(PASS)
                        .roleArn(roleArn)).statusAsString());
            } else {
                assertDenied(actor, roleArn, false);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revealSecretsRequiresItsOwnPermissionEvenForAPureState(boolean permitted) throws Exception {
        List<Map<String, Object>> statements = new ArrayList<>(basePermissions());
        if (permitted) {
            statements.add(statement("Allow", "states:RevealSecrets", "*"));
        }
        try (SfnClient actor = actor(statements)) {
            if (permitted) {
                assertEquals("SUCCEEDED", actor.testState(request -> request.definition(PASS).roleArn(roleArn)
                        .revealSecrets(true)).statusAsString());
            } else {
                assertDenied(actor, roleArn, true);
                assertEquals("SUCCEEDED", actor.testState(request -> request.definition(PASS).roleArn(roleArn)
                        .revealSecrets(false)).statusAsString());
            }
        }
    }

    @Test
    void aBoundaryCannotBeBypassedByTheSecondaryPassRoleCheck() throws Exception {
        try (SfnClient actor = actor(basePermissions())) {
            String policy = MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement",
                    List.of(statement("Allow", "states:*", "*"))));
            boundaryArn = admin.createPolicy(request -> request.policyName(name).policyDocument(policy)).policy().arn();
            admin.putUserPermissionsBoundary(request -> request.userName(name).permissionsBoundary(boundaryArn));
            assertDenied(actor, roleArn, false);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-path", "untrusted"})
    void aPureStateCannotIgnoreItsExecutionRole(String mode) throws Exception {
        String selected = switch (mode) {
            case "missing" -> roleArn + "-missing";
            case "wrong-path" -> roleArn.replace("/test-state/", "/wrong-path/");
            default -> roleArn;
        };
        if ("untrusted".equals(mode)) {
            admin.updateAssumeRolePolicy(request -> request.roleName(name)
                    .policyDocument(TRUST.replace("states.amazonaws.com", "lambda.amazonaws.com")));
        }
        try (SfnClient actor = actor(List.of(statement("Allow", "states:TestState", "*"),
                statement("Allow", "iam:PassRole", "*")))) {
            assertDenied(actor, selected, false);
        }
    }

    private List<Map<String, Object>> basePermissions() {
        return List.of(statement("Allow", "states:TestState", "*"), statement("Allow", "iam:PassRole", roleArn));
    }

    private SfnClient actor(List<Map<String, Object>> statements) throws Exception {
        admin.createUser(request -> request.userName(name));
        userCreated = true;
        String policy = MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements));
        admin.putUserPolicy(request -> request.userName(name).policyName("test-state").policyDocument(policy));
        AccessKey key = admin.createAccessKey(request -> request.userName(name)).accessKey();
        accessKey = key.accessKeyId();
        return states(endpoint, key.accessKeyId(), key.secretAccessKey(), "us-east-1");
    }

    private static Map<String, Object> statement(String effect, String action, String resource) {
        return Map.of("Effect", effect, "Action", action, "Resource", resource);
    }

    private static void assertDenied(SfnClient actor, String role, boolean reveal) {
        SfnException denied = assertThrows(SfnException.class, () -> actor.testState(request -> request.definition(PASS)
                .roleArn(role).revealSecrets(reveal)));
        assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
    }
}
