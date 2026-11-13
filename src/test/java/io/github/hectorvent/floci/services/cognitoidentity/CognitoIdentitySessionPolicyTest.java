package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CognitoIdentitySessionPolicyTest {
    private static final String ALL = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CognitoIdentityCredentials credentials = new CognitoIdentityCredentials(null, null, null, null, mapper);
    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);

    @Test
    void guestPoliciesUnionInlineAndManagedAllowSetsThenIntersectRolePermissions() {
        CallerContext guest = new CallerContext(List.of(ALL), credentials.enhancedPolicy(false, "us-east-1"), null);
        assertEquals(Decision.ALLOW, decision(guest, "s3:GetObject"));
        assertEquals(Decision.ALLOW, decision(guest, "kms:Encrypt"));
        assertEquals(Decision.ALLOW, decision(guest, "rum:PutRumEvents"));
        assertEquals(Decision.DENY, decision(guest, "iam:GetRole"));
        assertEquals(Decision.DENY, decision(guest, "cognito-identity:ListIdentityPools"));
        assertEquals(Decision.DENY, decision(guest, "cognito-sync:ListDatasets"));
        CallerContext emptyRole = new CallerContext(List.of(), credentials.enhancedPolicy(false, "us-east-1"), null);
        assertEquals(Decision.DENY, decision(emptyRole, "s3:GetObject"));
    }

    @Test
    void authenticatedEnhancedSessionsRetainRoleGrantsButExcludeIdentityControlPlane() {
        CallerContext authenticated = new CallerContext(List.of(ALL), credentials.enhancedPolicy(true, "us-east-1"), null);
        assertEquals(Decision.ALLOW, decision(authenticated, "iam:GetRole"));
        assertEquals(Decision.DENY, decision(authenticated, "cognito-identity:DeleteIdentities"));
        assertEquals(Decision.DENY, decision(authenticated, "cognito-sync:ListDatasets"));
    }

    private Decision decision(CallerContext caller, String action) {
        return evaluator.evaluate(caller, null, action, "*", Map.of());
    }
}
