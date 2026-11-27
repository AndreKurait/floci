package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.floci.aws.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KmsGrantPolicyCeilingsTest {
    private static final String RESOURCE = "arn:aws:kms:us-east-1:000000000311:key/example";
    private static final String DENY = """
            {"Statement":[{"Effect":"Deny","Action":"kms:Encrypt","Resource":"*"}]}""";
    private static final String OTHER = """
            {"Statement":[{"Effect":"Allow","Action":"s3:*","Resource":"*"}]}""";
    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    @Test
    void grantDoesNotNeedAnIdentityPolicyAllowForTheForeignKey() {
        assertFalse(denied(new CallerContext(List.of(OTHER), null, null, null), ResourcePolicyDecision.NEUTRAL));
    }

    @Test
    void explicitIdentitySessionBoundaryAndKeyPolicyDeniesEachWin() {
        assertTrue(denied(new CallerContext(List.of(DENY), null, null, null), ResourcePolicyDecision.NEUTRAL));
        assertTrue(denied(new CallerContext(List.of(), DENY, null, null), ResourcePolicyDecision.NEUTRAL));
        assertTrue(denied(new CallerContext(List.of(), null, DENY, null), ResourcePolicyDecision.NEUTRAL));
        assertTrue(denied(new CallerContext(List.of(), null, null, null), ResourcePolicyDecision.EXPLICIT_DENY));
    }

    @Test
    void scpWithholdingTheOperationOrExplicitlyDenyingItWins() {
        assertTrue(denied(new CallerContext(List.of(), null, null, List.of(List.of(OTHER))),
                ResourcePolicyDecision.NEUTRAL));
        assertTrue(denied(new CallerContext(List.of(), null, null, List.of(List.of(DENY))),
                ResourcePolicyDecision.NEUTRAL));
    }

    private boolean denied(CallerContext caller, ResourcePolicyDecision resource) {
        return evaluator.deniesKmsGrant(caller, resource, "kms:Encrypt", RESOURCE, Map.of());
    }
}
