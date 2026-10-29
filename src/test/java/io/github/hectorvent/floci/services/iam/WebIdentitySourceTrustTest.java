package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebIdentitySourceTrustTest {
    private static final String PROVIDER = "arn:aws-us-gov:iam::111111111111:oidc-provider/issuer.example";
    private final WebIdentityTrustPolicyEvaluator evaluator = new WebIdentityTrustPolicyEvaluator(new ObjectMapper());
    private final Map<String, List<String>> claims = Map.of("sts:SourceIdentity", List.of("Person_A"));

    @Test
    void matchesWildcardActionAndGlobalConditionWithoutIssuerPrefix() {
        assertTrue(allows("\"Action\":\"STS:Set*\"", "sts:SourceIdentity", "Person_A", PROVIDER));
    }

    @Test
    void sourceIdentityValueRemainsCaseSensitive() {
        assertFalse(allows("\"Action\":\"sts:*\"", "sts:SourceIdentity", "person_a", PROVIDER));
    }

    @Test
    void anotherProviderCannotSetSourceIdentity() {
        assertFalse(allows("\"Action\":\"sts:*\"", "sts:SourceIdentity", "Person_A",
                "arn:aws-us-gov:iam::222222222222:oidc-provider/issuer.example"));
    }

    @Test
    void doesNotExposeSourceIdentityAsIssuerClaim() {
        assertFalse(allows("\"Action\":\"sts:*\"", "issuer.example:sourceidentity", "Person_A", PROVIDER));
    }

    @Test
    void notActionStillExcludesTheRequestedPermission() {
        assertFalse(allows("\"NotAction\":\"sts:SetSourceIdentity\"", "sts:SourceIdentity", "Person_A", PROVIDER));
        assertTrue(allows("\"NotAction\":\"sts:AssumeRoleWithWebIdentity\"", "sts:SourceIdentity", "Person_A", PROVIDER));
    }

    private boolean allows(String action, String condition, String value, String provider) {
        String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\"" + provider
                + "\"}," + action + ",\"Condition\":{\"StringEquals\":{\"" + condition + "\":\"" + value + "\"}}}}";
        return evaluator.allows(policy, PROVIDER, "issuer.example", claims, "sts:SetSourceIdentity");
    }
}
