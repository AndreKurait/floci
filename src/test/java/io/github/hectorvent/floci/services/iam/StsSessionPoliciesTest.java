package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StsSessionPoliciesTest {
    private static final String ACCOUNT = "111122223333";
    private static final String ARN = "arn:aws:iam::" + ACCOUNT + ":policy/path/limited";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"iam:ListRoles\",\"Resource\":\"*\"}}";
    private final IamService iam = mock(IamService.class);
    private final MultivaluedMap<String, String> params = new MultivaluedHashMap<>();

    @Test
    void noManagedPolicyPreservesOriginalInlineDocument() {
        assertNull(resolve().inline());
        assertTrue(resolve().managedArns().isEmpty());
        params.putSingle("Policy", ALLOW);
        assertEquals(ALLOW, resolve().inline());
        verifyNoInteractions(iam);
    }

    @Test
    void inlineAndMultipleManagedStatementsFormOneSessionRestriction() throws Exception {
        params.putSingle("Policy", ALLOW);
        params.putSingle("PolicyArns.member.1.arn", ARN);
        params.putSingle("PolicyArns.member.3.arn", ARN + "2");
        when(iam.sessionPolicyDocument(ACCOUNT, ARN)).thenReturn(
                "{\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"iam:ListRoles\",\"Resource\":\"*\"}]}");
        when(iam.sessionPolicyDocument(ACCOUNT, ARN + "2")).thenReturn(ALLOW);
        StsSessionPolicies.Resolved combined = resolve();
        assertEquals(ALLOW, combined.inline());
        assertEquals(2, combined.managedArns().size());
        assertEquals(List.of(ARN, ARN + "2"), combined.managedArns());
        verify(iam).sessionPolicyDocument(ACCOUNT, ARN);
        verify(iam).sessionPolicyDocument(ACCOUNT, ARN + "2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-an-arn", "arn:aws:iam::999988887777:policy/foreign",
            "arn:aws-cn:iam::111122223333:policy/foreign", "arn:aws:iam:us-east-1:111122223333:policy/regional",
            "arn:aws:iam::111122223333:role/wrong", "arn:aws:s3::111122223333:policy/wrong"})
    void invalidOwnerPartitionOrShapeNeverResolvesPolicy(String arn) {
        params.putSingle("PolicyArns.member.1.arn", arn);
        assertThrows(AwsException.class, this::resolve);
        verifyNoInteractions(iam);
    }

    @Test
    void maximumTenPoliciesIsAcceptedAndElevenRejected() {
        when(iam.sessionPolicyDocument(ACCOUNT, ARN)).thenReturn(ALLOW);
        IntStream.rangeClosed(1, 10).forEach(i -> params.putSingle("PolicyArns.member." + i + ".arn", ARN));
        assertDoesNotThrow(this::resolve);
        clearInvocations(iam);
        params.putSingle("PolicyArns.member.11.arn", ARN);
        assertEquals("ValidationError", assertThrows(AwsException.class, this::resolve).getErrorCode());
        verifyNoInteractions(iam);
    }

    @Test
    void combinedTextLengthIsValidatedBeforeLookup() {
        params.putSingle("PolicyArns.member.1.arn", ARN);
        params.putSingle("Policy", " ".repeat(2048 - ARN.length() - ALLOW.length()) + ALLOW);
        when(iam.sessionPolicyDocument(ACCOUNT, ARN)).thenReturn(ALLOW);
        assertDoesNotThrow(this::resolve);
        clearInvocations(iam);
        params.putSingle("Policy", params.getFirst("Policy") + " ");
        assertEquals("ValidationError", assertThrows(AwsException.class, this::resolve).getErrorCode());
        verifyNoInteractions(iam);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}", "{\"Statement\":false}", "{\"Statement\":[1]}"})
    void malformedDocumentCannotBecomeUnrestrictedSession(String document) {
        params.putSingle("PolicyArns.member.1.arn", ARN);
        when(iam.sessionPolicyDocument(ACCOUNT, ARN)).thenReturn(document);
        assertEquals("MalformedPolicyDocument", assertThrows(AwsException.class, this::resolve).getErrorCode());
    }

    private StsSessionPolicies.Resolved resolve() {
        return StsSessionPolicies.resolve(params, ACCOUNT, "aws", iam);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2008-10-17", "2012-10-17"})
    void eachDocumentRetainsItsOwnPolicyVariableVersion(String version) {
        String variableAllow = "{\"Version\":\"" + version + "\",\"Statement\":{\"Effect\":\"Allow\","
                + "\"Action\":\"iam:ListRoles\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:SourceIdentity\":\"${aws:username}\"}}}}";
        params.putSingle("PolicyArns.member.1.arn", ARN);
        when(iam.sessionPolicyDocument(ACCOUNT, ARN)).thenReturn(variableAllow);
        StsSessionPolicies.Resolved resolved = resolve();
        CallerContext caller = new CallerContext(List.of(ALLOW), resolved.inline(), null)
                .withManagedSessionPolicies(List.of(iam.sessionPolicyDocument(ACCOUNT, resolved.managedArns().getFirst())))
                .withPrincipalArn("arn:aws:iam::" + ACCOUNT + ":role/example")
                .withScpLevels(null);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        assertEquals("2012-10-17".equals(version) ? IamPolicyEvaluator.Decision.ALLOW : IamPolicyEvaluator.Decision.DENY,
                evaluator.evaluate(caller, List.of(), "iam:ListRoles", "*",
                        Map.of("aws:SourceIdentity", List.of("Alice"), "aws:username", List.of("Alice"))));
        assertEquals(variableAllow, caller.managedSessionPolicyDocuments().getFirst());
    }

    @Test
    void resolvesPolicyFromSessionAccountInsteadOfRequestAccount() {
        AccountAwareStorageBackend<IamPolicy> policies = AccountAwareStorageBackend.inMemory(ACCOUNT);
        IamService service = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), policies, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver("us-east-1", ACCOUNT));
        IamPolicy policy = service.createPolicy("limited", "/path/", null, ALLOW, null);
        policies.delete(policy.getArn());
        policies.putForAccount("999988887777", policy.getArn(), policy);
        assertEquals(ALLOW, service.sessionPolicyDocument("999988887777", policy.getArn()));
        assertThrows(AwsException.class, () -> service.sessionPolicyDocument(ACCOUNT, policy.getArn()));
    }

    @Test
    void managedAllowsAreUnitedBeforeIntersectionWithIdentity() {
        String users = ALLOW.replace("iam:ListRoles", "iam:ListUsers");
        CallerContext caller = CallerContext.of(List.of(ALLOW)).withManagedSessionPolicies(List.of(ALLOW, users));
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        assertEquals(IamPolicyEvaluator.Decision.ALLOW,
                evaluator.evaluate(caller, List.of(), "iam:ListRoles", "*", Map.of()));
        assertEquals(IamPolicyEvaluator.Decision.DENY,
                evaluator.evaluate(caller, List.of(), "iam:ListUsers", "*", Map.of()));
        CallerContext expandedIdentity = CallerContext.of(List.of(ALLOW, users))
                .withManagedSessionPolicies(List.of(ALLOW, users));
        assertEquals(IamPolicyEvaluator.Decision.ALLOW,
                evaluator.evaluate(expandedIdentity, List.of(), "iam:ListUsers", "*", Map.of()));
    }

    @Test
    void storedPolicyArnsRoundTripWithoutAliasing() throws Exception {
        List<String> input = new ArrayList<>(List.of(ARN));
        SessionCredential session = new SessionCredential();
        session.setManagedSessionPolicyArns(input);
        input.clear();
        assertEquals(List.of(ARN), session.getManagedSessionPolicyArns());
        assertThrows(UnsupportedOperationException.class, () -> session.getManagedSessionPolicyArns().clear());
        ObjectMapper mapper = new ObjectMapper();
        SessionCredential restored = mapper.readValue(mapper.writeValueAsBytes(session), SessionCredential.class);
        assertEquals(List.of(ARN), restored.getManagedSessionPolicyArns());
    }
}
