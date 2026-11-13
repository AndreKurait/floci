package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.CompactJws;
import io.github.hectorvent.floci.services.eks.EksOidcService;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.PolicyDescriptorType;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class WebIdentitySourceIdentityIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @InjectSpy
    IamService iam;
    @Inject
    EksOidcService oidc;
    @Inject
    ObjectMapper mapper;

    @ParameterizedTest
    @CsvSource({"named,allow,allow", "named,missing,AccessDenied", "named,deny,AccessDenied",
            "named,condition,allow", "named,mismatch,AccessDenied", "absent,missing,allow",
            "absent,condition,AccessDenied", "short,allow,ValidationError",
            "blank,allow,ValidationError", "number,allow,InvalidIdentityToken",
            "null,allow,InvalidIdentityToken", "tampered,allow,InvalidIdentityToken",
            "managed-allow,allow,allow", "managed-deny,allow,allow"})
    void verifiedClaimRequiresTrustAndReachesSignedSession(String claimKind, String trustKind,
                                                         String expected) throws Exception {
        String name = "web-source-" + UUID.randomUUID();
        String policyArn = "arn:aws:iam::000000000000:policy/" + name;
        boolean managed = claimKind.startsWith("managed-");
        String issuer = oidc.newIssuerUrl("us-east-1");
        String provider = "arn:aws:iam::000000000000:oidc-provider/" + issuer.substring(8);
        String actions = "missing".equals(trustKind)
                ? "\"sts:AssumeRoleWithWebIdentity\""
                : "[\"sts:AssumeRoleWithWebIdentity\",\"sts:SetSourceIdentity\"]";
        String condition = switch (trustKind) {
            case "condition" -> ",\"Condition\":{\"StringEquals\":{\"sts:SourceIdentity\":\"Person_A\"}}";
            case "mismatch" -> ",\"Condition\":{\"StringEquals\":{\"sts:SourceIdentity\":\"Other\"}}";
            default -> "";
        };
        String statement = "{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\"" + provider
                + "\"},\"Action\":" + actions + condition + "}";
        String deny = "deny".equals(trustKind)
                ? ",{\"Effect\":\"Deny\",\"Principal\":{\"Federated\":\"" + provider
                        + "\"},\"Action\":\"sts:SetSourceIdentity\"}" : "";
        String trust = "{\"Statement\":[" + statement + deny + "]}";
        List<Credentials> sessions = new ArrayList<>();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "web-source-admin-", "admin",
                "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}");
             IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                             user.accessKeyId(), user.secretAccessKey()))).build();
             StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(AnonymousCredentialsProvider.create()).build();
             AutoCloseable managedCleanup = () -> {
                 try {
                     admin.deletePolicy(b -> b.policyArn(policyArn));
                 } catch (NoSuchEntityException expectedAbsent) {
                     // Only managed-policy cases create this known-name resource.
                 }
                 assertThrows(NoSuchEntityException.class, () -> admin.getPolicy(b -> b.policyArn(policyArn)));
             }) {
            try {
                Role role = admin.createRole(b -> b.roleName(name).path("/teams/web/")
                        .assumeRolePolicyDocument(trust)).role();
                admin.putRolePolicy(b -> b.roleName(name).policyName("source")
                        .policyDocument("{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"iam:ListRoles\","
                                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{"
                                + "\"aws:SourceIdentity\":\"Person_A\"}}}}"));
                if (managed) {
                    String action = "managed-deny".equals(claimKind) ? "iam:ListUsers" : "iam:ListRoles";
                    admin.createPolicy(b -> b.policyName(name).policyDocument(
                            "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"*\"}}"));
                }
                String token = token(name, issuer, managed ? "named" : claimKind);
                if (!"allow".equals(expected)) {
                    clearInvocations(iam);
                    StsException error = assertThrows(StsException.class, () -> sessions.add(sts
                            .assumeRoleWithWebIdentity(b -> b.roleArn(role.arn()).roleSessionName("source")
                                    .webIdentityToken(token)).credentials()));
                    assertEquals(expected, error.awsErrorDetails().errorCode());
                    verify(iam, never()).registerSession(anyString(), anyString(), anyString(), anyString(),
                            any(), any(), anyString(), anyString(), anyString(), anyMap(), anyMap(), any(), anyList());
                } else {
                    AssumeRoleWithWebIdentityResponse response = sts.assumeRoleWithWebIdentity(b ->
                            b.roleArn(role.arn()).roleSessionName("source").webIdentityToken(token)
                                    .policyArns(managed ? List.of(PolicyDescriptorType.builder().arn(policyArn).build())
                                            : List.of()));
                    Credentials credentials = response.credentials();
                    sessions.add(credentials);
                    assertEquals("absent".equals(claimKind) ? null : "Person_A", response.sourceIdentity());
                    assertTrue(response.assumedRoleUser().arn().contains(":assumed-role/" + name + "/source"));
                    assertEquals(response.sourceIdentity(), iam.findSourceIdentity(credentials.accessKeyId(),
                            credentials.sessionToken()));
                    assertNull(iam.findSourceIdentity(credentials.accessKeyId(), "wrong-token"));
                    try (IamClient caller = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                            .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                                    credentials.accessKeyId(), credentials.secretAccessKey(),
                                    credentials.sessionToken()))).build()) {
                        if ("absent".equals(claimKind) || "managed-deny".equals(claimKind)) {
                            assertEquals(403, assertThrows(IamException.class, caller::listRoles).statusCode());
                        } else {
                            assertDoesNotThrow(() -> caller.listRoles());
                            if ("allow".equals(trustKind) && !managed) {
                                assertChain(admin, role, credentials, sessions);
                            }
                        }
                    }
                }
            } finally {
                try {
                    for (Credentials session : sessions.reversed()) {
                        iam.unregisterSession("000000000000", session.accessKeyId());
                        assertTrue(iam.resolveCallerArns(session.accessKeyId()).isEmpty());
                    }
                } finally {
                    try {
                        oidc.deleteKey(name);
                        assertTrue(oidc.findKeyByCluster(name).isEmpty());
                    } finally {
                        // The role may have been created even when a later setup assertion failed.
                        if (iam.findRole("000000000000", name).isPresent()) {
                            try {
                                admin.deleteRolePolicy(b -> b.roleName(name).policyName("source"));
                            } catch (NoSuchEntityException ignored) {
                                // Setup can fail before the policy is created; the role is still owned.
                            } finally {
                                admin.deleteRole(b -> b.roleName(name));
                            }
                        }
                        assertTrue(iam.findRole("000000000000", name).isEmpty());
                    }
                }
            }
        }
    }

    private void assertChain(IamClient admin, Role parent, Credentials credentials,
                             List<Credentials> sessions) {
        String name = parent.roleName() + "-chain";
        String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + parent.arn()
                + "\"},\"Action\":[\"sts:AssumeRole\",\"sts:SetSourceIdentity\"],"
                + "\"Condition\":{\"StringEquals\":{\"sts:SourceIdentity\":\"Person_A\"}}}}";
        try {
            Role child = admin.createRole(b -> b.roleName(name).assumeRolePolicyDocument(trust)).role();
            admin.putRolePolicy(b -> b.roleName(parent.roleName()).policyName("chain")
                    .policyDocument("{\"Statement\":{\"Effect\":\"Allow\",\"Action\":["
                            + "\"sts:AssumeRole\",\"sts:SetSourceIdentity\"],\"Resource\":\"" + child.arn() + "\"}}"));
            try (StsClient caller = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                            credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken())))
                    .build()) {
                AssumeRoleResponse result = caller.assumeRole(b ->
                        b.roleArn(child.arn()).roleSessionName("chained"));
                sessions.add(result.credentials());
                assertEquals("Person_A", result.sourceIdentity());
                assertEquals("Person_A", iam.findSourceIdentity(result.credentials().accessKeyId(),
                        result.credentials().sessionToken()));
                StsException changed = assertThrows(StsException.class, () -> sessions.add(caller.assumeRole(b ->
                        b.roleArn(child.arn()).roleSessionName("changed").sourceIdentity("Other")).credentials()));
                assertEquals("AccessDenied", changed.awsErrorDetails().errorCode());
            }
        } finally {
            try {
                admin.deleteRolePolicy(b -> b.roleName(parent.roleName()).policyName("chain"));
            } catch (NoSuchEntityException ignored) {
                // Setup can fail before the chain policy exists.
            } finally {
                if (iam.findRole("000000000000", name).isPresent()) {
                    admin.deleteRole(b -> b.roleName(name));
                }
            }
        }
    }

    private String token(String name, String issuer, String kind) throws Exception {
        String privateKey = oidc.ensureKey(name, issuer).getPrivateKey();
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
        Map<String, Object> claims = new LinkedHashMap<>(Map.of("iss", issuer, "sub", "subject",
                "aud", "sts.amazonaws.com", "exp", Instant.now().plusSeconds(3600).getEpochSecond()));
        if (!"absent".equals(kind)) {
            claims.put("https://aws.amazon.com/source_identity", switch (kind) {
                case "short" -> "x";
                case "blank" -> "";
                case "number" -> 12;
                case "null" -> null;
                default -> "Person_A";
            });
        }
        String token = CompactJws.sign(mapper.writeValueAsBytes(Map.of("alg", "RS256")), mapper.writeValueAsBytes(claims),
                key, "SHA256withRSA");
        if ("tampered".equals(kind)) {
            claims.put("https://aws.amazon.com/source_identity", "Attacker");
            String[] parts = token.split("\\.", -1);
            return parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mapper.writeValueAsBytes(claims)) + "." + parts[2];
        }
        return token;
    }
}
