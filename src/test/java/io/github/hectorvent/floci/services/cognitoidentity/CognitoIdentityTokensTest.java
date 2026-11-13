package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CompactJws;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.cognitoidentity.model.CognitoIdentity;
import io.github.hectorvent.floci.services.cognitoidentity.model.CognitoIdentityKey;
import io.github.hectorvent.floci.services.cognitoidentity.model.IdentityPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitoIdentityTokensTest {
    private static final String ACCOUNT = "123456789012";
    private static final String POOL = "us-east-1:12345678-1234-1234-1234-123456789012";
    private static final String IDENTITY = "us-east-1:aaaaaaaa-1234-1234-1234-123456789012";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AccountAwareStorageBackend<CognitoIdentityKey> keys = AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final WebIdentityTokenVerifier verifier = new WebIdentityTokenVerifier(mapper);
    private final CognitoIdentityTokens tokens = new CognitoIdentityTokens(keys, mapper, verifier);

    @TempDir
    Path directory;

    @Test
    void issuedTokenHasIndependentlyVerifiedRs512SignatureAndBoundClaims() throws Exception {
        String token = tokens.mint(pool(), identity(true), 900);
        String[] parts = token.split("\\.");
        JsonNode header = decode(parts[0]);
        JsonNode claims = decode(parts[1]);
        assertEquals("RS512", header.path("alg").asText());
        CognitoIdentityKey key = keys.get(header.path("kid").asText()).orElseThrow();
        RSAPublicKey publicKey = publicKey(key);
        Signature signature = Signature.getInstance("SHA512withRSA");
        signature.initVerify(publicKey);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])));
        assertEquals(POOL, claims.path("aud").asText());
        assertEquals(IDENTITY, claims.path("sub").asText());
        assertEquals(List.of("authenticated", "login.example.test"),
                mapper.convertValue(claims.path("amr"), new TypeReference<List<String>>() {}));
        assertEquals(900, claims.path("exp").asLong() - claims.path("iat").asLong());
        assertEquals(ACCOUNT, tokens.verify(token).accountId());
        assertThrows(WebIdentityTokenVerifier.InvalidTokenException.class,
                () -> verifier.verify(token, publicKey, CognitoIdentityTokens.issuer("us-east-1"), POOL),
                "The original EKS verifier must remain RS256-only");
    }

    @Test
    void refusesUnsignedTamperedWrongAudienceExpiredAndUnknownKeyTokens() throws Exception {
        String token = tokens.mint(pool(), identity(false), 600);
        String[] parts = token.split("\\.");
        CognitoIdentityKey key = keys.get(decode(parts[0]).path("kid").asText()).orElseThrow();
        assertThrows(AwsException.class, () -> tokens.verify(parts[0] + "." + parts[1] + "."));
        ObjectNode changed = (ObjectNode) decode(parts[1]);
        changed.put("sub", "us-east-1:bbbbbbbb-1234-1234-1234-123456789012");
        String tampered = parts[0] + "." + encode(changed) + "." + parts[2];
        assertThrows(AwsException.class, () -> tokens.verify(tampered));
        changed.put("aud", "us-east-1:cccccccc-1234-1234-1234-123456789012");
        assertThrows(AwsException.class, () -> tokens.verify(sign(key, changed, "RS512", "SHA512withRSA")));
        changed.put("aud", POOL).put("exp", Instant.now().minusSeconds(120).getEpochSecond());
        AwsException expired = assertThrows(AwsException.class,
                () -> tokens.verify(sign(key, changed, "RS512", "SHA512withRSA")));
        assertEquals("ExpiredTokenException", expired.getErrorCode());
        changed.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
        assertThrows(AwsException.class, () -> tokens.verify(sign(key, changed, "RS256", "SHA256withRSA")));
        ObjectNode header = (ObjectNode) decode(parts[0]);
        header.put("alg", "none");
        assertThrows(AwsException.class, () -> tokens.verify(encode(header) + "." + parts[1] + "."));
        header.put("kid", "cognito-unknown");
        assertThrows(AwsException.class, () -> tokens.verify(encode(header) + "." + parts[1] + "." + parts[2]));
        assertTrue(tokens.recognizes(tampered));
        assertFalse(tokens.recognizes("opaque-unrelated-token"));
    }

    @Test
    void storageOwnerMustMatchSigningKeyOwnerAndCannotBeSuppliedByClaims() {
        String token = tokens.mint(pool(), identity(false), 600);
        CognitoIdentityKey key = keys.scan(value -> true).getFirst();
        AccountAwareStorageBackend<CognitoIdentityKey> moved = AccountAwareStorageBackend.inMemory("999999999999");
        moved.put(key.keyId(), key);
        CognitoIdentityTokens other = new CognitoIdentityTokens(moved, mapper, verifier);
        assertThrows(AwsException.class, () -> other.verify(token));
    }

    @Test
    void persistedKeyVerifiesExistingTokensAfterStorageReload() {
        Path file = directory.resolve("keys.json");
        TypeReference<Map<String, CognitoIdentityKey>> type = new TypeReference<>() {};
        PersistentStorage<String, CognitoIdentityKey> first = new PersistentStorage<>(file, type);
        first.load();
        CognitoIdentityTokens original = new CognitoIdentityTokens(
                new AccountAwareStorageBackend<>(first, null, ACCOUNT), mapper, verifier);
        String token = original.mint(pool(), identity(false), 600);
        PersistentStorage<String, CognitoIdentityKey> second = new PersistentStorage<>(file, type);
        second.load();
        CognitoIdentityTokens restarted = new CognitoIdentityTokens(
                new AccountAwareStorageBackend<>(second, null, ACCOUNT), mapper, verifier);
        assertEquals(IDENTITY, restarted.verify(token).identityId());
        assertEquals(ACCOUNT, restarted.verify(token).accountId());
        assertEquals(1, second.scan(value -> true).size());
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,cognito-identity.amazonaws.com", "ap-northeast-3,cognito-identity.amazonaws.com",
            "eu-south-1,cognito-identity.eu-south-1.amazonaws.com",
            "cn-north-1,cognito-identity.cn-north-1.amazonaws.com.cn",
            "us-gov-west-1,cognito-identity.us-gov-west-1.amazonaws.com"})
    void issuerUsesPublishedGlobalAndRegionalForms(String region, String expected) {
        assertEquals(expected, CognitoIdentityTokens.issuer(region));
    }

    private String sign(CognitoIdentityKey key, JsonNode claims, String algorithm, String jca) throws Exception {
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.privateKey())));
        return CompactJws.sign(mapper.writeValueAsBytes(Map.of("alg", algorithm, "kid", key.keyId())),
                mapper.writeValueAsBytes(claims), privateKey, jca);
    }

    private RSAPublicKey publicKey(CognitoIdentityKey key) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(key.publicKey())));
    }

    private JsonNode decode(String part) throws Exception {
        return mapper.readTree(Base64.getUrlDecoder().decode(part));
    }

    private String encode(JsonNode value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(value));
    }

    private IdentityPool pool() {
        IdentityPool pool = new IdentityPool();
        pool.setIdentityPoolId(POOL);
        pool.setAccountId(ACCOUNT);
        pool.setDeveloperProviderName("login.example.test");
        return pool;
    }

    private CognitoIdentity identity(boolean authenticated) {
        Instant now = Instant.now();
        return new CognitoIdentity(IDENTITY, POOL, ACCOUNT, "us-east-1", now, now, authenticated, true,
                authenticated ? List.of("alice") : List.of());
    }
}
