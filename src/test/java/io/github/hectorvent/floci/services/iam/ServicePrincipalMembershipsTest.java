package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServicePrincipalMembershipsTest {

    private static final String ACCOUNT = "246813579012";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/workloads/worker";
    private static final String SESSION = "arn:aws:sts::" + ACCOUNT + ":assumed-role/worker/i-123";
    private static final String SERVICE = "example.aws.internal";
    private static final String ALLOW = """
            {"Statement":{"Effect":"Allow","Action":"execute-api:Invoke","Resource":"*"}}
            """;
    private static final String DENY = ALLOW.replace("\"Allow\"", "\"Deny\"");
    private static final String FIXTURE = """
            {"arn:aws:iam::246813579012:root":["example.aws.internal","example.amazonaws.com",
              "feature_with_underscore.example.internal"],
             "arn:aws:iam::246813579014:root":["example.aws.internal"],
             "arn:aws-cn:iam::246813579012:root":["china.example.aws.internal"]}
            """;

    @TempDir
    Path directory;

    private ServicePrincipalMemberships load(String json) throws Exception {
        return new ServicePrincipalMemberships(Files.writeString(directory.resolve("memberships.json"), json));
    }

    @Test
    void membershipsAreManyToManyAndScopedToTheCurrentAccountAndPartition() throws Exception {
        ServicePrincipalMemberships memberships = load(FIXTURE);
        assertTrue(memberships.contains(SESSION, SERVICE));
        assertTrue(memberships.contains(ROLE, "example.amazonaws.com"));
        assertTrue(memberships.contains(ROLE, "feature_with_underscore.example.internal"));
        assertTrue(memberships.contains("arn:aws:iam::246813579014:user/worker", SERVICE));
        assertFalse(memberships.contains("arn:aws:iam::135792468013:role/customer-role", SERVICE));
        assertFalse(memberships.contains("arn:aws-cn:sts::" + ACCOUNT + ":assumed-role/worker/s", SERVICE));
        assertTrue(memberships.contains("arn:aws-cn:sts::" + ACCOUNT + ":assumed-role/worker/s",
                "china.example.aws.internal"));
        assertFalse(memberships.contains(null, SERVICE));
        assertFalse(memberships.contains("arn:aws:ec2:us-east-1:" + ACCOUNT + ":instance/i-123", SERVICE));
    }

    @Test
    void membershipNamesAreExactWithNoWildcardOrRegionalFolding() throws Exception {
        ServicePrincipalMemberships memberships = load(FIXTURE);
        for (String name : List.of("*", "*.aws.internal", "Example.aws.internal", "example.eu-west-1.amazonaws.com",
                "example.amazonaws.com.cn", "other.aws.internal")) {
            assertFalse(memberships.contains(SESSION, name), name);
        }
    }

    @Test
    void defaultConfigurationDoesNotCreateMemberships() {
        assertFalse(new ServicePrincipalMemberships().contains(SESSION, SERVICE));
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        assertEquals(ResourcePolicyDecision.NEUTRAL, resourceDecision(evaluator, "Allow"));
        assertEquals(Decision.ALLOW, evaluator.evaluateServicePrincipal(
                List.of(policy("Allow")), SERVICE, "execute-api:Invoke", "*", Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "[]", "{} {}", "{\"arn:aws:iam::246813579012:root\":null}",
            "{\"246813579012\":[]}", "{\"arn:aws:iam::246813579012:user/worker\":[]}",
            "{\"arn:aws:iam:us-east-1:246813579012:root\":[]}", "{\"arn:unknown:iam::246813579012:root\":[]}",
            "{\"arn:aws:iam::123:root\":[]}", "{\"arn:AWS:iam::246813579012:root\":[]}",
            "{\"arn:aws:iam::246813579012:root\":[1]}", "{\"arn:aws:iam::246813579012:root\":[\"*\"]}",
            "{\"arn:aws:iam::246813579012:root\":[\"example.aws.internal\",\"example.aws.internal\"]}",
            "{\"arn:aws:iam::246813579012:root\":[\" example.aws.internal\"]}",
            "{\"arn:aws:iam::246813579012:root\":[],\"arn:aws:iam::246813579012:root\":[\"other.aws.internal\"]}"
    })
    void malformedFixtureIsRejectedInsteadOfGrantingPartialMemberships(String json) {
        assertThrows(IllegalArgumentException.class, () -> load(json));
    }

    @Test
    void oversizedAndMissingFilesAreRejectedAndLoadedMembershipsAreImmutable() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ServicePrincipalMemberships(directory.resolve("missing")));
        assertThrows(IllegalArgumentException.class,
                () -> load(" ".repeat(ServicePrincipalMemberships.MAX_FILE_BYTES + 1)));
        ServicePrincipalMemberships memberships = load(FIXTURE);
        load("{}");
        assertTrue(memberships.contains(SESSION, SERVICE), "The fixture is a startup snapshot");
    }

    @Test
    void membershipDoesNotBypassIdentitySessionBoundaryOrScpPolicies() throws Exception {
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper(), load(FIXTURE));
        ResourcePolicyDecision resource = resourceDecision(evaluator, "Allow");
        assertEquals(ResourcePolicyDecision.ALLOW_ACCOUNT, resource);
        for (ResourceAccountRelationship relationship : ResourceAccountRelationship.values()) {
            assertEquals(Decision.ALLOW, decide(evaluator, CallerContext.of(List.of(ALLOW)), resource, relationship));
            for (CallerContext restricted : List.of(
                    CallerContext.of(List.of()),
                    CallerContext.of(List.of(ALLOW, DENY)),
                    new CallerContext(List.of(ALLOW), "{\"Statement\":[]}", null),
                    new CallerContext(List.of(ALLOW), DENY, null),
                    new CallerContext(List.of(ALLOW), null, "{\"Statement\":[]}"),
                    new CallerContext(List.of(ALLOW), null, DENY),
                    CallerContext.of(List.of(ALLOW)).withScpLevels(List.of(List.of(DENY))))) {
                assertEquals(Decision.DENY, decide(evaluator, restricted, resource, relationship));
            }
        }
        ResourcePolicyDecision denied = resourceDecision(evaluator, "Deny");
        assertEquals(ResourcePolicyDecision.EXPLICIT_DENY, denied);
        assertEquals(Decision.DENY, decide(evaluator, CallerContext.of(List.of(ALLOW)), denied,
                ResourceAccountRelationship.CROSS_ACCOUNT));
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicyFor(
                List.of(policy("Allow")), RequestPrincipal.anonymous(), "execute-api:Invoke", "*", Map.of()));
    }

    @Test
    void trustPoliciesMatchMembershipWithoutHidingCallerConditionsOrExplicitDenies() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AssumeRolePolicyEvaluator evaluator =
                new AssumeRolePolicyEvaluator(mapper, new IamPolicyEvaluator(mapper, load(FIXTURE)));
        IamService.CallerArns caller = new IamService.CallerArns(SESSION, ROLE);
        String trust = """
                {"Version":"2012-10-17","Statement":{"Effect":"Allow","Action":"sts:AssumeRole",
                "Principal":{"Service":["other.aws.internal","example.aws.internal"]},
                "Condition":{"ArnEquals":{"aws:PrincipalArn":"%s"}}}}
                """.formatted(ROLE);
        assertEquals(AssumeRolePolicyEvaluator.TrustDecision.ALLOW_ACCOUNT,
                evaluator.decide(trust, caller, ACCOUNT, Map.of()));
        assertEquals(AssumeRolePolicyEvaluator.TrustDecision.EXPLICIT_DENY,
                evaluator.decide(trust.replace("\"Allow\"", "\"Deny\""), caller, ACCOUNT, Map.of()));
        assertEquals(AssumeRolePolicyEvaluator.TrustDecision.NEUTRAL,
                evaluator.decide(trust.replace(ROLE, ROLE + "-other"), caller, ACCOUNT, Map.of()));
        assertEquals(AssumeRolePolicyEvaluator.TrustDecision.NEUTRAL,
                evaluator.decide(trust, new IamService.CallerArns(
                        "arn:aws:sts::135792468013:assumed-role/customer/s",
                        "arn:aws:iam::135792468013:role/customer"), "135792468013", Map.of()));
    }

    private static String policy(String effect) {
        return """
                {"Statement":{"Effect":"%s","Principal":{"Service":"%s"},
                "Action":"execute-api:Invoke","Resource":"*"}}
                """.formatted(effect, SERVICE);
    }

    private static ResourcePolicyDecision resourceDecision(IamPolicyEvaluator evaluator, String effect) {
        return evaluator.evaluateResourcePolicyFor(List.of(policy(effect)), RequestPrincipal.roleSession(SESSION, ROLE),
                "execute-api:Invoke", "*", Map.of());
    }

    private static Decision decide(IamPolicyEvaluator evaluator, CallerContext caller, ResourcePolicyDecision resource,
                                   ResourceAccountRelationship relationship) {
        return evaluator.evaluateResolvedResourcePolicy(caller, resource, relationship, "execute-api:Invoke", "*", Map.of());
    }
}
