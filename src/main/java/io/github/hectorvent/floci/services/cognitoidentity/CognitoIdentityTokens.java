package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.CompactJws;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.WebIdentityToken;
import io.github.hectorvent.floci.core.common.WebIdentityTokenVerifier;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cognitoidentity.model.CognitoIdentity;
import io.github.hectorvent.floci.services.cognitoidentity.model.CognitoIdentityKey;
import io.github.hectorvent.floci.services.cognitoidentity.model.IdentityPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Locally signed Cognito identity tokens, verified independently of IAM enforcement mode. */
@ApplicationScoped
public class CognitoIdentityTokens {
    static final Set<String> GLOBAL_ISSUER_REGIONS = Set.of(
            "us-east-1", "us-east-2", "us-west-1", "us-west-2", "ap-northeast-1", "ap-northeast-2", // partition-literal: Published legacy global identity issuer region set.
            "ap-northeast-3", "ap-south-1", "ap-southeast-1", "ap-southeast-2", "ca-central-1", // partition-literal: Published legacy global identity issuer region set.
            "eu-central-1", "eu-north-1", "eu-west-1", "eu-west-2", "eu-west-3", "sa-east-1"); // partition-literal: Published legacy global identity issuer region set.
    private final StorageBackend<String, CognitoIdentityKey> keys;
    private final ObjectMapper mapper;
    private final WebIdentityTokenVerifier verifier;

    @Inject
    public CognitoIdentityTokens(StorageFactory factory, ObjectMapper mapper, WebIdentityTokenVerifier verifier) {
        this(factory.create("cognitoidentity", "cognitoidentity-token-keys.json",
                new TypeReference<Map<String, CognitoIdentityKey>>() {}), mapper, verifier);
    }

    CognitoIdentityTokens(StorageBackend<String, CognitoIdentityKey> keys, ObjectMapper mapper,
                          WebIdentityTokenVerifier verifier) {
        this.keys = keys;
        this.mapper = mapper;
        this.verifier = verifier;
    }

    public static String issuer(String region) {
        return GLOBAL_ISSUER_REGIONS.contains(region) ? ServicePrincipals.of("cognito-identity")
                : "cognito-identity." + region + "." + AwsRegions.dnsSuffixFor(region);
    }

    public record Verified(String issuer, String poolId, String identityId, String accountId,
                           String region, List<String> methods) {
        public Verified {
            methods = List.copyOf(methods);
        }

        public Map<String, List<String>> trustClaims() {
            return Map.of("aud", List.of(poolId), "sub", List.of(identityId), "amr", methods);
        }
    }

    public String mint(IdentityPool pool, CognitoIdentity identity, long durationSeconds) {
        if (durationSeconds < 1 || durationSeconds > 86400) {
            throw new AwsException("InvalidParameterException", "TokenDuration must be between 1 and 86400.", 400);
        }
        CognitoIdentityKey key = keyFor(pool, identity.region());
        List<String> methods = identity.authenticated()
                ? List.of("authenticated", pool.getDeveloperProviderName()) : List.of("unauthenticated");
        Instant now = Instant.now();
        try {
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.privateKey())));
            return CompactJws.sign(mapper.writeValueAsBytes(Map.of("alg", "RS512", "typ", "JWT", "kid", key.keyId())),
                    mapper.writeValueAsBytes(Map.of("iss", issuer(identity.region()), "sub", identity.identityId(),
                            "aud", pool.getIdentityPoolId(), "amr", methods, "iat", now.getEpochSecond(),
                            "exp", now.plusSeconds(durationSeconds).getEpochSecond())),
                    privateKey, "SHA512withRSA");
        } catch (GeneralSecurityException | JsonProcessingException e) {
            throw new IllegalStateException("Unable to sign Cognito identity token", e);
        }
    }

    /** Routes recognizable Cognito tokens before STS's unrelated legacy unknown-issuer fallback. */
    public boolean recognizes(String token) {
        JsonNode header = part(token, 0);
        JsonNode claims = part(token, 1);
        return header.path("kid").asText().startsWith("cognito-")
                || claims.path("iss").asText().contains("cognito-identity.")
                || (CognitoIdentityService.isIdentityId(claims.path("aud").asText())
                    && CognitoIdentityService.isIdentityId(claims.path("sub").asText()));
    }

    public Verified verify(String token) {
        String keyId = part(token, 0).path("kid").asText();
        List<CognitoIdentityKey> matches = keys instanceof AccountAwareStorageBackend<CognitoIdentityKey> aware
                ? aware.findAllAccountEntries(keyId).stream()
                        .filter(e -> e.accountId().equals(e.value().accountId())).map(e -> e.value()).toList()
                : keys.get(keyId).stream().toList();
        if (matches.size() != 1) {
            throw invalid("The Cognito token signing key is not trusted.");
        }
        CognitoIdentityKey key = matches.getFirst();
        WebIdentityToken verified;
        try {
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(key.publicKey())));
            verified = verifier.verifyRs512(token, publicKey, issuer(key.region()), key.identityPoolId());
        } catch (WebIdentityTokenVerifier.ExpiredTokenException e) {
            throw new AwsException("ExpiredTokenException", "The Cognito identity token has expired.", 400);
        } catch (GeneralSecurityException | WebIdentityTokenVerifier.InvalidTokenException | IllegalArgumentException e) {
            throw invalid("The Cognito identity token is invalid.");
        }
        JsonNode claimSet = part(token, 1);
        JsonNode amr = claimSet.path("amr");
        List<String> methods = new ArrayList<>();
        if (amr.isArray()) {
            amr.forEach(value -> {
                if (value.isTextual()) {
                    methods.add(value.asText());
                }
            });
        }
        boolean authenticated = methods.contains("authenticated");
        boolean unauthenticated = methods.contains("unauthenticated");
        if (!CognitoIdentityService.isIdentityId(verified.subject())
                || !verified.subject().startsWith(key.region() + ":")
                || authenticated == unauthenticated || methods.size() != amr.size()
                || (unauthenticated && methods.size() != 1)
                || !claimSet.path("iat").isIntegralNumber() || !claimSet.path("iat").canConvertToLong()
                || !claimSet.path("exp").isIntegralNumber() || !claimSet.path("exp").canConvertToLong()
                || claimSet.path("exp").asLong() <= claimSet.path("iat").asLong()
                || claimSet.path("exp").asLong() - claimSet.path("iat").asLong() > 86400
                || claimSet.path("iat").asLong() > Instant.now().plusSeconds(60).getEpochSecond()) {
            throw invalid("The Cognito identity token claims are invalid.");
        }
        return new Verified(verified.issuer(), key.identityPoolId(), verified.subject(),
                key.accountId(), key.region(), methods);
    }

    private synchronized CognitoIdentityKey keyFor(IdentityPool pool, String region) {
        List<CognitoIdentityKey> owned = keys instanceof AccountAwareStorageBackend<CognitoIdentityKey> aware
                ? aware.scanForAccount(pool.getAccountId(), value -> true) : keys.scan(value -> true);
        for (CognitoIdentityKey key : owned) {
            if (pool.getIdentityPoolId().equals(key.identityPoolId()) && pool.getAccountId().equals(key.accountId())) {
                return key;
            }
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            CognitoIdentityKey key = new CognitoIdentityKey("cognito-" + UUID.randomUUID(), pool.getIdentityPoolId(),
                    pool.getAccountId(), region, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            if (keys instanceof AccountAwareStorageBackend<CognitoIdentityKey> aware) {
                aware.putAllAtomicallyForAccount(pool.getAccountId(), Map.of(key.keyId(), key));
            } else {
                keys.putAllAtomically(Map.of(key.keyId(), key));
            }
            return key;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to create Cognito identity signing key", e);
        }
    }

    private JsonNode part(String token, int index) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length == 3) {
                JsonNode value = mapper.readTree(Base64.getUrlDecoder().decode(parts[index]));
                return value == null ? mapper.createObjectNode() : value;
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // An untrusted probe is not authorization; the verifier supplies the refusal.
        }
        return mapper.createObjectNode();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidIdentityToken", message, 400);
    }
}
