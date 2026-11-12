package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cognitoidentity.model.CognitoIdentity;
import io.github.hectorvent.floci.services.cognitoidentity.model.IdentityPool;
import io.github.hectorvent.floci.services.cognitoidentity.model.PrincipalTagAttributeMap;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.WebIdentityTrustPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CognitoIdentityCredentialsBoundaryTest {
    private static final String ACCOUNT = "123456789012";
    private static final String FOREIGN = "999999999999";
    private static final String POOL = "us-east-1:12345678-1234-1234-1234-123456789012";
    private static final String IDENTITY = "us-east-1:aaaaaaaa-1234-1234-1234-123456789012";
    private static final String PRINCIPAL = "cognito-identity.amazonaws.com";
    private final ObjectMapper mapper = new ObjectMapper();
    private final IamService iam = mock(IamService.class);
    private final CognitoIdentityService identities = mock(CognitoIdentityService.class);
    private final CognitoIdentityTokens tokens = new CognitoIdentityTokens(AccountAwareStorageBackend.inMemory(ACCOUNT),
            mapper, new WebIdentityTokenVerifier(mapper));
    private final CognitoIdentityCredentials credentials = new CognitoIdentityCredentials(identities, tokens, iam,
            new WebIdentityTrustPolicyEvaluator(mapper), mapper);

    @Test
    void enhancedRefusesForeignRoleButClassicRequiresAudienceConstrainedCrossAccountTrust() {
        IdentityPool pool = pool();
        CognitoIdentity identity = identity();
        String foreignArn = "arn:aws:iam::" + FOREIGN + ":role/foreign";
        IamRole role = new IamRole("AROAEXAMPLE", "foreign", "/", foreignArn, trust(false));
        when(iam.findRole(FOREIGN, "foreign")).thenReturn(Optional.of(role));
        when(identities.publicIdentity(IDENTITY, "us-east-1")).thenReturn(identity);
        when(identities.publicPool(POOL, "us-east-1")).thenReturn(pool);
        pool.setRoles(Map.of("unauthenticated", foreignArn));
        assertEquals("InvalidIdentityPoolConfigurationException", assertThrows(AwsException.class,
                () -> credentials.enhanced(IDENTITY, Map.of(), null, "us-east-1")).getErrorCode());
        String token = tokens.mint(pool, identity, 600);
        assertEquals("AccessDenied", assertThrows(AwsException.class,
                () -> credentials.assumeRole(token, foreignArn, "classic", 3600, null, 200)).getErrorCode());
        role.setAssumeRolePolicyDocument(trust(true));
        CognitoIdentityCredentials.Credentials session = credentials.assumeRole(token, foreignArn, "classic", 3600, null, 512);
        assertEquals("arn:aws:sts::" + FOREIGN + ":assumed-role/foreign/classic", session.assumedRoleArn());
        assertEquals(512, session.sessionToken().length());
        verify(iam).registerCognitoIdentitySession(eq(ACCOUNT), eq(session.accessKeyId()), anyString(), anyString(),
                eq(foreignArn), any(), isNull(), eq(ACCOUNT), eq("classic"), eq("AROAEXAMPLE:classic"),
                eq(Map.of("aud", List.of(POOL), "sub", List.of(IDENTITY), "amr", List.of("unauthenticated"))));
        assertTrue(credentials.recognizes("stripped-of-all-jwt-fields", foreignArn),
                "A Cognito role cannot fall through to the unrelated permissive legacy STS path");
    }

    @Test
    void tokenIdentitySelectsTheVerifierBeforeRoleTrust() {
        String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/mixed";
        IamRole role = new IamRole("AROAEXAMPLE", "mixed", "/", roleArn, trust(true));
        String external = Base64Url.encode("{\"alg\":\"RS256\"}") + "."
                + Base64Url.encode("{\"iss\":\"https://login.example.test\",\"sub\":\"app\"}") + ".signature";
        assertFalse(credentials.recognizes(external, roleArn, true));
        verifyNoInteractions(iam);
        when(iam.findRole(ACCOUNT, "mixed")).thenReturn(Optional.of(role));
        assertTrue(credentials.recognizes(external, roleArn, false));
        assertTrue(credentials.recognizes("stripped-of-all-jwt-fields", roleArn));
        String damaged = Base64Url.encode("{\"kid\":\"cognito-missing\"}") + "."
                + Base64Url.encode("{\"iss\":\"https://login.example.test\"}") + ".invalid";
        assertTrue(credentials.recognizes(damaged, roleArn, true));
        assertEquals("InvalidIdentityToken", assertThrows(AwsException.class,
                () -> credentials.assumeRole(damaged, roleArn, "damaged", 3600, null, 0)).getErrorCode());
    }

    @Test
    void unsupportedRoleMappingsAndAbacRefuseBeforeAnyRoleOrSessionLookup() throws Exception {
        IdentityPool pool = pool();
        when(identities.publicIdentity(IDENTITY, "us-east-1")).thenReturn(identity());
        when(identities.publicPool(POOL, "us-east-1")).thenReturn(pool);
        pool.setRoleMappings(mapper.readTree("{\"provider\":{\"Type\":\"Token\"}}"));
        assertThrows(AwsException.class, () -> credentials.enhanced(IDENTITY, Map.of(), null, "us-east-1"));
        pool.setRoleMappings(null);
        PrincipalTagAttributeMap mapping = new PrincipalTagAttributeMap();
        mapping.setUseDefaults(true);
        pool.setPrincipalTagAttributeMaps(Map.of("provider", mapping));
        assertThrows(AwsException.class, () -> credentials.enhanced(IDENTITY, Map.of(), null, "us-east-1"));
        verifyNoInteractions(iam);
    }

    @Test
    void persistedSessionClaimsRemainDefensiveAndLegacySessionsHaveNoProvenance() throws Exception {
        List<String> methods = new ArrayList<>(List.of("authenticated", "login.example.test"));
        SessionCredential session = new SessionCredential();
        session.setCognitoIdentityClaims(Map.of("amr", methods, "aud", List.of(POOL)));
        methods.clear();
        SessionCredential restored = mapper.readValue(mapper.writeValueAsBytes(session), SessionCredential.class);
        assertEquals(List.of("authenticated", "login.example.test"), restored.getCognitoIdentityClaims().get("amr"));
        assertThrows(UnsupportedOperationException.class, () -> restored.getCognitoIdentityClaims().put("sub", List.of("forged")));
        assertTrue(mapper.readValue("{}", SessionCredential.class).getCognitoIdentityClaims().isEmpty());
    }

    private IdentityPool pool() {
        IdentityPool pool = new IdentityPool();
        pool.setAccountId(ACCOUNT);
        pool.setIdentityPoolId(POOL);
        pool.setArn("arn:aws:cognito-identity:us-east-1:" + ACCOUNT + ":identitypool/" + POOL);
        pool.setAllowUnauthenticatedIdentities(true);
        pool.setAllowClassicFlow(true);
        return pool;
    }

    private CognitoIdentity identity() {
        return new CognitoIdentity(IDENTITY, POOL, ACCOUNT, "us-east-1", Instant.now(), Instant.now(), false, true, List.of());
    }

    private String trust(boolean audience) {
        return "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\"" + PRINCIPAL
                + "\"},\"Action\":\"sts:AssumeRoleWithWebIdentity\""
                + (audience ? ",\"Condition\":{\"StringEquals\":{\"" + PRINCIPAL + ":aud\":\"" + POOL + "\"}}" : "") + "}}";
    }
}
