package io.floci.aws.services.ecr.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamConditionContextResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.RequestPrincipal;
import io.floci.aws.services.iam.model.CallerContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Native ECR passwords identify their issuing caller; registryIds never grant permissions. */
@ApplicationScoped
public class EcrRegistryAuthorization {
    public record TokenRecord(String accessKeyId, String accountId, String principalArn, String userId,
                              Instant expiresAt, boolean enforcementDisabled) {}
    public record IssuedToken(String authorizationToken, Instant expiresAt) {}
    public record Identity(TokenRecord token, IamService.CallerArns caller, CallerContext policies) {}

    private final AccountAwareStorageBackend<TokenRecord> tokens;
    private final IamService iam;
    private final IamPolicyEvaluator evaluator;
    private final RequestContext request;
    private final EmulatorConfig config;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private String pruneCursor;

    @Inject
    public EcrRegistryAuthorization(StorageFactory storage, IamService iam, IamPolicyEvaluator evaluator,
                                    RequestContext request, EmulatorConfig config) {
        this(storage.create("ecr", "registry-tokens.json",
                new TypeReference<Map<String, TokenRecord>>() {}), iam, evaluator, request, config, Clock.systemUTC());
    }

    EcrRegistryAuthorization(AccountAwareStorageBackend<TokenRecord> tokens, IamService iam,
                             IamPolicyEvaluator evaluator, RequestContext request, EmulatorConfig config, Clock clock) {
        this.tokens = tokens;
        this.iam = iam;
        this.evaluator = evaluator;
        this.request = request;
        this.config = config;
        this.clock = clock;
    }

    public IssuedToken issue() {
        boolean disabled = !config.services().iam().enforcementEnabled();
        String key = request.getAccessKeyId();
        IamService.CallerArns caller = key == null ? null : iam.resolveCallerArns(key).orElse(null);
        if (!disabled && (caller == null || !request.isSignatureVerifiedFor(key)
                || iam.isInactiveAccessKey(key) || iam.resolveCallerContext(key) == null)) {
            throw new AwsException("AccessDeniedException", "A verified IAM caller is required", 403);
        }
        String account = caller == null ? config.defaultAccountId()
                : AwsArnUtils.parse(caller.principalArn()).accountId();
        Instant expiry = clock.instant().plusSeconds(12 * 60 * 60);
        Instant storedExpiry = key == null ? null : iam.findSessionExpiration(key).orElse(null);
        if (!disabled && IamService.isTemporaryAccessKey(key) && storedExpiry == null) {
            throw new AwsException("ExpiredTokenException", "The issuing session is unavailable", 403);
        }
        if (storedExpiry != null && storedExpiry.isBefore(expiry)) {
            expiry = storedExpiry;
        }
        if (!expiry.isAfter(clock.instant())) {
            throw new AwsException("ExpiredTokenException", "The issuing session has expired", 403);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pruneExpired();
        tokens.putForAccount(account, hash(password), new TokenRecord(key, account,
                caller == null ? null : caller.principalArn(), caller == null ? null : caller.userId(), expiry, disabled));
        return new IssuedToken(Base64.getEncoder().encodeToString(
                ("AWS:" + password).getBytes(StandardCharsets.UTF_8)), expiry);
    }

    /** Bounded sweep; expired records are never accepted even before their sweep turn. */
    private synchronized void pruneExpired() {
        Map<String, AccountAwareStorageBackend.AccountEntry<TokenRecord>> page = tokens.scanAllAccountEntriesAfter(pruneCursor, 128);
        for (Map.Entry<String, AccountAwareStorageBackend.AccountEntry<TokenRecord>> entry : page.entrySet()) {
            pruneCursor = entry.getKey();
            AccountAwareStorageBackend.AccountEntry<TokenRecord> item = entry.getValue();
            if (!item.value().expiresAt().isAfter(clock.instant())) {
                tokens.deleteForAccount(item.accountId(), item.key());
            }
        }
        if (page.size() < 128) {
            pruneCursor = null;
        }
    }

    @ActivateRequestContext
    public Identity authenticate(String authorization) {
        String password = password(authorization);
        if (password == null) {
            throw unauthorized();
        }
        AccountAwareStorageBackend.OwnedEntry<TokenRecord> stored = tokens.findAnyAccountEntry(hash(password)).orElseThrow(EcrRegistryAuthorization::unauthorized);
        TokenRecord token = stored.value();
        if (!stored.account().equals(token.accountId()) || !token.expiresAt().isAfter(clock.instant())) {
            throw unauthorized();
        }
        if (token.enforcementDisabled()) {
            if (config.services().iam().enforcementEnabled()) {
                throw unauthorized();
            }
            return new Identity(token, null, null);
        }
        String previous = request.getAccountId();
        try {
            request.setAccountId(token.accountId());
            if (iam.isInactiveAccessKey(token.accessKeyId()) || iam.isExpiredSession(token.accessKeyId(), clock.instant())) {
                throw unauthorized();
            }
            IamService.CallerArns caller = iam.resolveCallerArns(token.accessKeyId()).orElseThrow(EcrRegistryAuthorization::unauthorized);
            CallerContext policies = iam.resolveCallerContext(token.accessKeyId(), clock.instant());
            if (policies == null || !caller.principalArn().equals(token.principalArn())
                    || !Objects.equals(caller.userId(), token.userId())) {
                throw unauthorized();
            }
            return new Identity(token, caller, policies.withScpLevels(iam.effectiveScpLevels(token.accountId())));
        } finally {
            request.setAccountId(previous);
        }
    }

    /** Only explicitly disabled IAM enforcement preserves the original unauthenticated emulator mode. */
    public boolean enforced() {
        return config.services().iam().enforcementEnabled();
    }

    public void requirePermission(Identity identity, String action, String resource, String resourcePolicy) {
        requirePermission(identity, action, resource, resourcePolicy, Map.of());
    }

    public void requirePermission(Identity identity, String action, String resource, String resourcePolicy,
                                  Map<String, List<String>> observedContext) {
        if (identity.token().enforcementDisabled() && !config.services().iam().enforcementEnabled()) {
            return;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
        if (!AwsArnUtils.parse(identity.caller().principalArn()).partition().equals(arn.partition())) {
            throw new AwsException("DENIED", "Cross-partition registry access is denied", 403);
        }
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(observedContext, resource, arn.region(),
                identity.token().accountId(), arn.accountId());
        identity.caller().putPrincipalKeys(context);
        IamPolicyEvaluator.ResourcePolicyDecision resourceDecision = evaluator.evaluateResourcePolicyFor(
                resourcePolicy == null ? List.of() : List.of(resourcePolicy),
                RequestPrincipal.caller(identity.caller()),
                action, resource, context);
        IamPolicyEvaluator.ResourceAccountRelationship relationship = identity.token().accountId().equals(arn.accountId())
                ? IamPolicyEvaluator.ResourceAccountRelationship.SAME_ACCOUNT
                : IamPolicyEvaluator.ResourceAccountRelationship.CROSS_ACCOUNT;
        if (evaluator.evaluateResolvedResourcePolicy(identity.policies(), resourceDecision,
                relationship, action, resource, context) != IamPolicyEvaluator.Decision.ALLOW) {
            throw new AwsException("DENIED", "Requested access to the resource is denied", 403);
        }
    }

    private static String password(String header) {
        if (header == null || header.length() > 1024 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            return decoded.startsWith("AWS:") && decoded.length() > 4 ? decoded.substring(4) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AwsException unauthorized() {
        return new AwsException("UNAUTHORIZED", "Authentication required", 401);
    }
}
