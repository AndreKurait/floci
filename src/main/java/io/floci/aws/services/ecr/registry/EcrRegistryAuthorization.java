package io.floci.aws.services.ecr.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.model.AuthConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamConditionContextResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.RequestPrincipal;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Native ECR passwords identify their issuing caller; registryIds never grant permissions. */
@ApplicationScoped
public class EcrRegistryAuthorization {
    @RegisterForReflection
    public record TokenRecord(String accessKeyId, String accountId, String principalArn, String userId,
                              Instant expiresAt, boolean enforcementDisabled, String region) {}
    public record IssuedToken(String authorizationToken, Instant expiresAt) {}
    public record PullScope(String resourceArn, String sourceArn, String roleId, Instant issuedAt,
                            boolean authenticatedPull, boolean lambdaPull, String servicePrincipal) {}
    public record Identity(TokenRecord token, IamService.CallerArns caller, CallerContext policies, PullScope pullScope) {
        public Identity(TokenRecord token, IamService.CallerArns caller, CallerContext policies) {
            this(token, caller, policies, null);
        }
    }

    private record PullGrant(TokenRecord token, PullScope scope) {}

    /** A Java-only credential for one authorized repository pull, revoked when the pull finishes. */
    public final class PullLease implements AutoCloseable {
        private final String digest;
        private final AuthConfig credentials;

        private PullLease(String digest, AuthConfig credentials) {
            this.digest = digest;
            this.credentials = credentials;
        }

        public AuthConfig credentials() {
            return credentials;
        }

        @Override
        public void close() {
            pullGrants.remove(digest);
            credentials.withPassword(null);
        }
    }

    private final AccountAwareStorageBackend<TokenRecord> tokens;
    private final IamService iam;
    private final IamPolicyEvaluator evaluator;
    private final RequestContext request;
    private final EmulatorConfig config;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PullGrant> pullGrants = new ConcurrentHashMap<>();
    private String pruneCursor;

    /**
     * Called only after the owning service has resolved and validated its image-pull role.
     * Current repository permissions are checked before the caller can consult Docker's cache.
     */
    public PullLease issueRolePull(String roleArn, String sourceArn, String resourceArn,
                                   String resourcePolicy, Map<String, List<String>> observedContext,
                                   String registryAddress) {
        return issueRolePull(roleArn, sourceArn, resourceArn, resourcePolicy, observedContext, registryAddress, false, false);
    }

    PullLease issueAuthenticatedRolePull(String roleArn, String sourceArn, String resourceArn,
                                         String resourcePolicy, Map<String, List<String>> observedContext,
                                         String registryAddress) {
        return issueRolePull(roleArn, sourceArn, resourceArn, resourcePolicy, observedContext, registryAddress, true, false);
    }

    PullLease issueLambdaPull(String roleArn, String sourceArn, String resourceArn,
                              String resourcePolicy, Map<String, List<String>> observedContext,
                              String registryAddress) {
        return issueRolePull(roleArn, sourceArn, resourceArn, resourcePolicy, observedContext, registryAddress, false, true);
    }

    PullLease issueEmrServerlessPull(String sourceArn, String resourceArn, String resourcePolicy,
                                     Map<String, List<String>> observedContext, String registryAddress) {
        AwsArnUtils.Arn source = checkedArn(sourceArn);
        AwsArnUtils.Arn resource = checkedArn(resourceArn);
        if (!"emr-serverless".equals(source.service()) || !source.resource().startsWith("/applications/")
                || source.resource().substring("/applications/".length()).isBlank()
                || source.resource().substring("/applications/".length()).contains("/")
                || !"ecr".equals(resource.service()) || !resource.resource().startsWith("repository/")
                || resource.resource().equals("repository/") || resource.region().isBlank()
                || !source.region().equals(resource.region()) || !source.partition().equals(resource.partition())
                || registryAddress == null || registryAddress.isBlank()) {
            throw deniedPull();
        }
        Instant issuedAt = clock.instant();
        String principal = ServicePrincipals.of("emr-serverless");
        TokenRecord token = new TokenRecord(null, source.accountId(), principal, null,
                issuedAt.plusSeconds(300), false, resource.region());
        PullGrant grant = new PullGrant(token, new PullScope(
                resourceArn, sourceArn, null, issuedAt, false, false, principal));
        Identity identity = roleIdentity(grant);
        if (!requiresRegistryContext(identity, resourcePolicy, observedContext)) {
            requirePullPermissions(identity, resourceArn, resourcePolicy, observedContext);
        }
        return lease(grant, registryAddress);
    }

    private PullLease issueRolePull(String roleArn, String sourceArn, String resourceArn,
                                    String resourcePolicy, Map<String, List<String>> observedContext,
                                    String registryAddress, boolean authenticatedPull, boolean lambdaPull) {
        AwsArnUtils.Arn source = checkedArn(sourceArn);
        AwsArnUtils.Arn principal = checkedArn(roleArn);
        AwsArnUtils.Arn resource = checkedArn(resourceArn);
        if (!"iam".equals(principal.service()) || !principal.resource().startsWith("role/")
                || !principal.accountId().equals(source.accountId())
                || !principal.partition().equals(source.partition())
                || !"ecr".equals(resource.service()) || !resource.resource().startsWith("repository/")
                || resource.region().isBlank() || !principal.partition().equals(resource.partition())
                || registryAddress == null || registryAddress.isBlank()) {
            throw deniedPull();
        }
        if (lambdaPull && (!"lambda".equals(source.service()) || !source.resource().startsWith("function:")
                || source.resource().equals("function:") || !source.region().equals(resource.region()))) {
            throw deniedPull();
        }
        IamRole role = currentRole(roleArn);
        Instant issuedAt = clock.instant();
        TokenRecord token = new TokenRecord(null, principal.accountId(), roleArn, null,
                issuedAt.plusSeconds(300), false, resource.region());
        PullScope scope = new PullScope(resourceArn, sourceArn, role.getRoleId(), issuedAt, authenticatedPull, lambdaPull, null);
        PullGrant grant = new PullGrant(token, scope);
        Identity identity = roleIdentity(grant);
        if (!requiresRegistryContext(identity, resourcePolicy, observedContext)) {
            requirePullPermissions(identity, resourceArn, resourcePolicy, observedContext);
        }
        return lease(grant, registryAddress);
    }

    private boolean requiresRegistryContext(Identity identity, String resourcePolicy,
                                             Map<String, List<String>> observedContext) {
        List<String> documents = new ArrayList<>();
        if (resourcePolicy != null) {
            documents.add(resourcePolicy);
        }
        CallerContext policies = identity.policies();
        if (policies != null) {
            documents.addAll(policies.identityPolicies());
            if (policies.sessionPolicyDocument() != null) {
                documents.add(policies.sessionPolicyDocument());
            }
            if (policies.boundaryPolicyDocument() != null) {
                documents.add(policies.boundaryPolicyDocument());
            }
            if (policies.scpLevels() != null) {
                policies.scpLevels().forEach(documents::addAll);
            }
        }
        Set<String> provided = new LinkedHashSet<>();
        observedContext.keySet().forEach(key -> provided.add(key.toLowerCase(Locale.ROOT)));
        return evaluator.contextKeysReferencedIn(documents).stream()
                .map(key -> key.toLowerCase(Locale.ROOT))
                .anyMatch(key -> Set.of("aws:securetransport", "aws:sourceip", "aws:sourcevpc", "aws:sourcevpce")
                        .contains(key) && !provided.contains(key));
    }

    /** Check the entire internal retrieval, even when Docker already has all image layers. */
    public void requirePullPermissions(Identity identity, String resource, String resourcePolicy,
                                       Map<String, List<String>> observedContext) {
        if (identity.pullScope() == null) {
            throw deniedPull();
        }
        identity = roleIdentity(new PullGrant(identity.token(), identity.pullScope()));
        requirePermission(identity, "ecr:BatchGetImage", resource, resourcePolicy, observedContext);
        requirePermission(identity, "ecr:GetDownloadUrlForLayer", resource, resourcePolicy, observedContext);
        if (identity.pullScope().servicePrincipal() != null) {
            requirePermission(identity, "ecr:DescribeImages", resource, resourcePolicy, observedContext);
        } else if (identity.pullScope().authenticatedPull()) {
            requirePermission(identity, "ecr:BatchCheckLayerAvailability", resource, resourcePolicy, observedContext);
            AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
            Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                    observedContext, "*", arn.region(), identity.token().accountId(), null, "ecr:GetAuthorizationToken");
            identity.caller().putPrincipalKeys(context);
            if (evaluator.evaluate(identity.policies(), List.of(),
                    "ecr:GetAuthorizationToken", "*", context) != IamPolicyEvaluator.Decision.ALLOW) {
                throw deniedPull();
            }
        }
    }

    private PullLease lease(PullGrant grant, String registryAddress) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String digest = hash(password);
        synchronized (pullGrants) {
            pullGrants.entrySet().removeIf(entry -> !entry.getValue().token().expiresAt().isAfter(clock.instant()));
            if (pullGrants.size() >= 1024) {
                throw new AwsException("UNAVAILABLE", "Too many concurrent registry pulls", 503);
            }
            pullGrants.put(digest, grant);
        }
        return new PullLease(digest, new AuthConfig().withRegistryAddress(registryAddress)
                .withUsername("AWS").withPassword(password));
    }

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

    public IssuedToken issue(String region) {
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
                caller == null ? null : caller.principalArn(), caller == null ? null : caller.userId(),
                expiry, disabled, region));
        return new IssuedToken(Base64.getEncoder().encodeToString(
                ("AWS:" + password).getBytes(StandardCharsets.UTF_8)), expiry);
    }

    /** Bounded sweep; expired records are never accepted even before their sweep turn. */
    private synchronized void pruneExpired() {
        Map<String, AccountAwareStorageBackend.AccountEntry<TokenRecord>> page = tokens.scanAllAccountEntriesAfter(pruneCursor, 128);
        Map<String, Set<String>> expiredByAccount = new LinkedHashMap<>();
        String nextCursor = pruneCursor;
        for (Map.Entry<String, AccountAwareStorageBackend.AccountEntry<TokenRecord>> entry : page.entrySet()) {
            nextCursor = entry.getKey();
            AccountAwareStorageBackend.AccountEntry<TokenRecord> item = entry.getValue();
            if (!item.value().expiresAt().isAfter(clock.instant())) {
                expiredByAccount.computeIfAbsent(item.accountId(), account -> new LinkedHashSet<>()).add(item.key());
            }
        }
        if (!expiredByAccount.isEmpty()) {
            tokens.deleteAllAtomicallyForAccounts(expiredByAccount);
        }
        pruneCursor = page.size() < 128 ? null : nextCursor;
    }

    @ActivateRequestContext
    public Identity authenticate(String authorization, String region) {
        if (region == null || region.isBlank()) {
            throw unauthorized();
        }
        return authenticatedIdentity(authorization, region);
    }

    /** Path-style login has no repository Region; repository requests remain Region-bound. */
    @ActivateRequestContext
    public Identity authenticateLoginProbe(String authorization) {
        return authenticatedIdentity(authorization, null);
    }

    private Identity authenticatedIdentity(String authorization, String region) {
        String password = password(authorization);
        if (password == null) {
            throw unauthorized();
        }
        PullGrant grant = pullGrants.get(hash(password));
        if (grant != null) {
            if (region != null && !grant.token().region().equals(region)) {
                throw unauthorized();
            }
            return roleIdentity(grant);
        }
        AccountAwareStorageBackend.OwnedEntry<TokenRecord> stored = tokens.findAnyAccountEntry(hash(password)).orElseThrow(EcrRegistryAuthorization::unauthorized);
        TokenRecord token = stored.value();
        if (!stored.account().equals(token.accountId()) || !token.expiresAt().isAfter(clock.instant())
                || token.region() == null || token.region().isBlank()
                || region != null && !token.region().equals(region)) {
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
        if (identity.pullScope() != null) {
            boolean servicePull = identity.pullScope().servicePrincipal() != null;
            if (!identity.pullScope().resourceArn().equals(resource)
                    || !Set.of("ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
                    "ecr:BatchCheckLayerAvailability").contains(action)
                    && !(servicePull && "ecr:DescribeImages".equals(action))) {
                throw deniedPull();
            }
            identity = roleIdentity(new PullGrant(identity.token(), identity.pullScope()));
            if ("ecr:BatchCheckLayerAvailability".equals(action) && !identity.pullScope().authenticatedPull()) {
                action = "ecr:GetDownloadUrlForLayer";
            }
            if (servicePull) {
                requireServicePermission(identity, action, resource, resourcePolicy, observedContext);
                return;
            }
        }
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
        IamPolicyEvaluator.Decision roleDecision = evaluator.evaluateResolvedResourcePolicy(
                identity.policies(), resourceDecision, relationship, action, resource, context);
        if (identity.pullScope() != null && identity.pullScope().lambdaPull()) {
            String principal = ServicePrincipals.of("lambda");
            Map<String, List<String>> serviceContext = IamConditionContextResolver.serviceCallContext(
                    principal, resource, arn.accountId(), identity.pullScope().sourceArn(),
                    identity.token().accountId(), action);
            observedContext.forEach((key, values) -> {
                if (key.startsWith("aws:ResourceTag/") || key.startsWith("ecr:ResourceTag/")
                        || Set.of("aws:SecureTransport", "aws:SourceIp", "aws:SourceVpc", "aws:SourceVpce").contains(key)) {
                    serviceContext.put(key, values);
                }
            });
            IamPolicyEvaluator.ResourcePolicyDecision serviceDecision = evaluator.evaluateResourcePolicyFor(
                    resourcePolicy == null ? List.of() : List.of(resourcePolicy),
                    RequestPrincipal.service(principal), action, resource, serviceContext);
            boolean serviceAllowed = serviceDecision == IamPolicyEvaluator.ResourcePolicyDecision.ALLOW;
            boolean roleAllowed = roleDecision == IamPolicyEvaluator.Decision.ALLOW;
            if (relationship == IamPolicyEvaluator.ResourceAccountRelationship.SAME_ACCOUNT
                    ? roleAllowed || serviceAllowed : roleAllowed && serviceAllowed) {
                return;
            }
        } else if (roleDecision == IamPolicyEvaluator.Decision.ALLOW) {
            return;
        }
        throw new AwsException("DENIED", "Requested access to the resource is denied", 403);
    }

    private void requireServicePermission(Identity identity, String action, String resource, String resourcePolicy,
                                           Map<String, List<String>> observedContext) {
        String principal = identity.pullScope().servicePrincipal();
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
        Map<String, List<String>> context = IamConditionContextResolver.serviceCallContext(
                principal, resource, arn.accountId(), identity.pullScope().sourceArn(),
                identity.token().accountId(), action);
        observedContext.forEach((key, values) -> {
            if (key.startsWith("aws:ResourceTag/") || key.startsWith("ecr:ResourceTag/")
                    || Set.of("aws:SecureTransport", "aws:SourceIp", "aws:SourceVpc", "aws:SourceVpce").contains(key)) {
                context.put(key, values);
            }
        });
        if (evaluator.evaluateResourcePolicyFor(resourcePolicy == null ? List.of() : List.of(resourcePolicy),
                RequestPrincipal.service(principal), action, resource, context)
                != IamPolicyEvaluator.ResourcePolicyDecision.ALLOW) {
            throw deniedPull();
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

    private Identity roleIdentity(PullGrant grant) {
        if (!grant.token().expiresAt().isAfter(clock.instant())) {
            throw unauthorized();
        }
        if (grant.scope().servicePrincipal() != null) {
            return new Identity(grant.token(), null, null, grant.scope());
        }
        IamRole role = currentRole(grant.token().principalArn());
        if (!Objects.equals(role.getRoleId(), grant.scope().roleId())) {
            throw unauthorized();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(role.getArn());
        String sessionArn = AwsArnUtils.Arn.global(arn.partition(), "sts", arn.accountId(),
                "assumed-role/" + role.getRoleName() + "/ecr-image-pull").toString();
        CallerContext policies = iam.resolvePrincipalContext(role.getArn())
                .withScpLevels(iam.effectiveScpLevels(arn.accountId()));
        IamService.CallerArns caller = new IamService.CallerArns(sessionArn, role.getArn(), "Role",
                role.getRoleId() + ":ecr-image-pull", null, policies.boundaryPolicyDocument() != null,
                role.getTags(), null, iam.organizationContextOf(arn.accountId()).orElse(null), grant.scope().issuedAt());
        return new Identity(grant.token(), caller, policies, grant.scope());
    }

    private IamRole currentRole(String roleArn) {
        AwsArnUtils.Arn arn = checkedArn(roleArn);
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        IamRole role = iam.findRole(arn.accountId(), name).orElseThrow(EcrRegistryAuthorization::unauthorized);
        if (!roleArn.equals(role.getArn()) || role.getRoleId() == null || role.getRoleId().isBlank()) {
            throw unauthorized();
        }
        return role;
    }

    private static AwsArnUtils.Arn checkedArn(String value) {
        if (!AwsArnUtils.isArn(value)) {
            throw deniedPull();
        }
        return AwsArnUtils.parse(value);
    }

    private static AwsException deniedPull() {
        return new AwsException("DENIED", "Requested image pull is denied", 403);
    }
}
