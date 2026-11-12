package io.floci.aws.services.cognitoidentity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cognitoidentity.model.CognitoIdentity;
import io.floci.aws.services.cognitoidentity.model.IdentityPool;
import io.floci.aws.services.cognitoidentity.model.PrincipalTagAttributeMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Amazon Cognito Identity (federated identity pools) management plane.
 *
 * <p>Pool ids follow the AWS format {@code <region>:<uuid>} and pool ARNs the documented
 * {@code arn:aws:cognito-identity:<region>:<account>:identitypool/<poolId>} form, so a caller
 * that parses either gets the same structure it would from AWS.
 */
@ApplicationScoped
public class CognitoIdentityService {

    private static final Logger LOG = Logger.getLogger(CognitoIdentityService.class);

    private static final Pattern IDENTITY_POOL_NAME = Pattern.compile("[\\w\\s+=,.@-]+");
    private static final Pattern DEVELOPER_PROVIDER_NAME = Pattern.compile("[\\w._-]+");
    private static final Set<String> ROLE_TYPES = Set.of("authenticated", "unauthenticated");
    private static final Set<String> ROLE_MAPPING_TYPES = Set.of("Token", "Rules");
    private static final String ARN_RESOURCE_PREFIX = "identitypool/";
    private static final int MAX_LIST_RESULTS = 60;

    private final StorageBackend<String, IdentityPool> pools;
    private final RegionResolver regionResolver;
    private final StorageBackend<String, CognitoIdentity> identities;

    @Inject
    public CognitoIdentityService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.pools = storageFactory.create("cognitoidentity", "cognitoidentity-pools.json",
                new TypeReference<Map<String, IdentityPool>>() {});
        this.regionResolver = regionResolver;
        this.identities = storageFactory.create("cognitoidentity", "cognitoidentity-identities.json",
                new TypeReference<Map<String, CognitoIdentity>>() {});
    }

    // ──────────────────────────── Identity pools ────────────────────────────

    public IdentityPool createIdentityPool(IdentityPool spec, String region) {
        validatePoolConfiguration(spec);

        String identityPoolId = region + ":" + UUID.randomUUID();
        spec.setIdentityPoolId(identityPoolId);
        spec.setArn(regionResolver.buildArn("cognito-identity", region, ARN_RESOURCE_PREFIX + identityPoolId));
        spec.setAccountId(regionResolver.getAccountId());
        spec.setCreatedAt(Instant.now());

        pools.put(storageKey(region, identityPoolId), spec);
        LOG.infov("Created Cognito identity pool: {0}", identityPoolId);
        return spec;
    }

    public IdentityPool describeIdentityPool(String identityPoolId, String region) {
        requireIdentityPoolId(identityPoolId);
        return pools.get(storageKey(region, identityPoolId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "IdentityPool '" + identityPoolId + "' not found.", 404));
    }

    /**
     * {@code UpdateIdentityPool} takes the whole {@code IdentityPool} shape and replaces the
     * stored pool with it: a member the caller omits is reset to its default rather than
     * carried over. The roles, role mappings and principal-tag maps are set by their own
     * operations and are not part of the shape, so they survive the replace.
     */
    public IdentityPool updateIdentityPool(IdentityPool spec, String region) {
        IdentityPool existing = describeIdentityPool(spec.getIdentityPoolId(), region);
        validatePoolConfiguration(spec);

        spec.setArn(existing.getArn());
        spec.setAccountId(existing.getAccountId());
        spec.setCreatedAt(existing.getCreatedAt());
        spec.setRoles(existing.getRoles());
        spec.setRoleMappings(existing.getRoleMappings());
        spec.setPrincipalTagAttributeMaps(existing.getPrincipalTagAttributeMaps());

        pools.put(storageKey(region, spec.getIdentityPoolId()), spec);
        LOG.infov("Updated Cognito identity pool: {0}", spec.getIdentityPoolId());
        return spec;
    }

    public synchronized void deleteIdentityPool(String identityPoolId, String region) {
        IdentityPool pool = describeIdentityPool(identityPoolId, region);
        Set<String> identityKeys = new LinkedHashSet<>();
        poolIdentities(pool, region).forEach(identity -> identityKeys.add(identity.identityId()));
        deleteForAccount(pool.getAccountId(), identityKeys);
        pools.delete(storageKey(region, identityPoolId));
        LOG.infov("Deleted Cognito identity pool: {0}", identityPoolId);
    }

    /**
     * Returns one page of pools ordered by pool id. The pagination token is the index of the
     * first pool of the next page, which keeps the token stable for the common case of a pool
     * set that only grows.
     */
    public Page listIdentityPools(Integer maxResults, String nextToken, String region) {
        int limit = maxResults == null ? MAX_LIST_RESULTS : maxResults;
        if (limit < 1 || limit > MAX_LIST_RESULTS) {
            throw new AwsException("InvalidParameterException",
                    "MaxResults must be between 1 and " + MAX_LIST_RESULTS + ".", 400);
        }
        int offset = parseToken(nextToken);

        String regionPrefix = region + "::";
        List<IdentityPool> ordered = pools.scan(key -> key.startsWith(regionPrefix)).stream()
                .sorted(Comparator.comparing(IdentityPool::getIdentityPoolId))
                .toList();
        if (offset >= ordered.size()) {
            return new Page(List.of(), null);
        }
        int end = Math.min(offset + limit, ordered.size());
        return new Page(ordered.subList(offset, end), end < ordered.size() ? String.valueOf(end) : null);
    }

    // ──────────────────────────── Pool roles ────────────────────────────

    public IdentityPool setIdentityPoolRoles(String identityPoolId, Map<String, String> roles,
                                             JsonNode roleMappings, String region) {
        IdentityPool pool = describeIdentityPool(identityPoolId, region);
        if (roles == null || roles.isEmpty()) {
            throw new AwsException("InvalidParameterException", "Roles is required.", 400);
        }
        for (Map.Entry<String, String> entry : roles.entrySet()) {
            if (!ROLE_TYPES.contains(entry.getKey())) {
                throw new AwsException("InvalidParameterException",
                        "Role key '" + entry.getKey() + "' must be one of authenticated, unauthenticated.", 400);
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new AwsException("InvalidParameterException",
                        "Role ARN for '" + entry.getKey() + "' must not be empty.", 400);
            }
        }
        validateRoleMappings(roleMappings);

        pool.setRoles(new LinkedHashMap<>(roles));
        pool.setRoleMappings(roleMappings != null && roleMappings.isObject() ? roleMappings : null);
        pools.put(storageKey(region, identityPoolId), pool);
        return pool;
    }

    public IdentityPool getIdentityPoolRoles(String identityPoolId, String region) {
        return describeIdentityPool(identityPoolId, region);
    }

    // ──────────────────────── Principal tag attribute maps ────────────────────────

    public PrincipalTagAttributeMap setPrincipalTagAttributeMap(String identityPoolId, String identityProviderName,
                                                                Boolean useDefaults, Map<String, String> principalTags,
                                                                String region) {
        IdentityPool pool = describeIdentityPool(identityPoolId, region);
        if (identityProviderName == null || identityProviderName.isBlank()) {
            throw new AwsException("InvalidParameterException", "IdentityProviderName is required.", 400);
        }

        PrincipalTagAttributeMap attributeMap = new PrincipalTagAttributeMap();
        attributeMap.setIdentityProviderName(identityProviderName);
        attributeMap.setUseDefaults(useDefaults != null && useDefaults);
        attributeMap.setPrincipalTags(principalTags != null ? new LinkedHashMap<>(principalTags) : new LinkedHashMap<>());

        pool.getPrincipalTagAttributeMaps().put(identityProviderName, attributeMap);
        pools.put(storageKey(region, identityPoolId), pool);
        return attributeMap;
    }

    /**
     * A provider with no attribute map is not an error in AWS. The read returns the provider
     * with an empty map, so an unset provider yields defaults rather than a not-found.
     */
    public PrincipalTagAttributeMap getPrincipalTagAttributeMap(String identityPoolId, String identityProviderName,
                                                                String region) {
        IdentityPool pool = describeIdentityPool(identityPoolId, region);
        if (identityProviderName == null || identityProviderName.isBlank()) {
            throw new AwsException("InvalidParameterException", "IdentityProviderName is required.", 400);
        }
        PrincipalTagAttributeMap attributeMap = pool.getPrincipalTagAttributeMaps().get(identityProviderName);
        if (attributeMap != null) {
            return attributeMap;
        }
        PrincipalTagAttributeMap empty = new PrincipalTagAttributeMap();
        empty.setIdentityProviderName(identityProviderName);
        return empty;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        return findByArn(resourceArn, region).getIdentityPoolTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        if (tags == null || tags.isEmpty()) {
            throw new AwsException("InvalidParameterException", "Tags is required.", 400);
        }
        IdentityPool pool = findByArn(resourceArn, region);
        pool.getIdentityPoolTags().putAll(tags);
        pools.put(storageKey(pool), pool);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        if (tagKeys == null || tagKeys.isEmpty()) {
            throw new AwsException("InvalidParameterException", "TagKeys is required.", 400);
        }
        IdentityPool pool = findByArn(resourceArn, region);
        tagKeys.forEach(pool.getIdentityPoolTags()::remove);
        pools.put(storageKey(pool), pool);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /** One page of {@code ListIdentityPools} results plus the token for the page after it. */
    public record Page(List<IdentityPool> identityPools, String nextToken) {}

    private void validatePoolConfiguration(IdentityPool spec) {
        String name = spec.getIdentityPoolName();
        if (name == null || name.isBlank() || name.length() > 128 || !IDENTITY_POOL_NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterException",
                    "IdentityPoolName must be 1-128 characters matching [\\w\\s+=,.@-]+.", 400);
        }
        String developerProviderName = spec.getDeveloperProviderName();
        if (developerProviderName != null
                && (developerProviderName.length() > 128
                    || !DEVELOPER_PROVIDER_NAME.matcher(developerProviderName).matches())) {
            throw new AwsException("InvalidParameterException",
                    "DeveloperProviderName must be 1-128 characters matching [\\w._-]+.", 400);
        }
    }

    private void validateRoleMappings(JsonNode roleMappings) {
        if (roleMappings == null || !roleMappings.isObject()) {
            return;
        }
        roleMappings.fields().forEachRemaining(entry -> {
            JsonNode mapping = entry.getValue();
            String type = mapping.path("Type").asText(null);
            if (type == null || !ROLE_MAPPING_TYPES.contains(type)) {
                throw new AwsException("InvalidParameterException",
                        "RoleMapping Type for '" + entry.getKey() + "' must be one of Token, Rules.", 400);
            }
            if ("Rules".equals(type)) {
                JsonNode rules = mapping.path("RulesConfiguration").path("Rules");
                if (!rules.isArray() || rules.isEmpty()) {
                    throw new AwsException("InvalidParameterException",
                            "RoleMapping '" + entry.getKey() + "' of type Rules requires RulesConfiguration.Rules.",
                            400);
                }
            }
        });
    }

    private void requireIdentityPoolId(String identityPoolId) {
        if (identityPoolId == null || identityPoolId.isBlank()) {
            throw new AwsException("InvalidParameterException", "IdentityPoolId is required.", 400);
        }
    }

    private IdentityPool findByArn(String resourceArn, String region) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "Invalid ResourceArn: " + resourceArn, 400);
        }
        if (!"cognito-identity".equals(arn.service()) || !arn.resource().startsWith(ARN_RESOURCE_PREFIX)) {
            throw new AwsException("InvalidParameterException", "Invalid ResourceArn: " + resourceArn, 400);
        }
        String identityPoolId = arn.resource().substring(ARN_RESOURCE_PREFIX.length());
        String arnRegion = arn.region().isEmpty() ? region : arn.region();
        return pools.get(storageKey(arnRegion, identityPoolId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "IdentityPool '" + identityPoolId + "' not found.", 404));
    }

    private int parseToken(String nextToken) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(nextToken);
            if (offset < 0) {
                throw new NumberFormatException(nextToken);
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterException", "Invalid NextToken: " + nextToken, 400);
        }
    }

    private String storageKey(String region, String identityPoolId) {
        return region + "::" + identityPoolId;
    }

    private String storageKey(IdentityPool pool) {
        return storageKey(AwsArnUtils.parse(pool.getArn()).region(), pool.getIdentityPoolId());
    }

    public static boolean isIdentityId(String value) {
        return value != null && value.matches("[\\w-]+:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    public IdentityPool publicPool(String poolId, String region) {
        requireIdentity(poolId, region);
        String key = storageKey(region, poolId);
        List<IdentityPool> matches = pools instanceof AccountAwareStorageBackend<IdentityPool> aware
                ? aware.findAllAccountEntries(key).stream()
                        .filter(entry -> entry.accountId().equals(entry.value().getAccountId()))
                        .map(entry -> entry.value()).toList()
                : pools.get(key).stream().toList();
        if (matches.size() != 1) {
            throw identityMissing();
        }
        return matches.getFirst();
    }

    public synchronized CognitoIdentity getId(String poolId, String accountId, Map<String, String> logins,
                                               String region) {
        IdentityPool pool = publicPool(poolId, region);
        if (accountId != null && !pool.getAccountId().equals(accountId)) {
            throw identityMissing();
        }
        rejectLogins(logins);
        requireGuest(pool, null);
        CognitoIdentity identity = newIdentity(pool, region, false, List.of());
        putIdentity(identity);
        return identity;
    }

    public CognitoIdentity publicIdentity(String identityId, String region) {
        requireIdentity(identityId, region);
        List<CognitoIdentity> matches = identities instanceof AccountAwareStorageBackend<CognitoIdentity> aware
                ? aware.findAllAccountEntries(identityId).stream()
                        .filter(entry -> entry.accountId().equals(entry.value().accountId()))
                        .map(entry -> entry.value()).toList()
                : identities.get(identityId).stream().toList();
        if (matches.size() != 1 || !region.equals(matches.getFirst().region())) {
            throw identityMissing();
        }
        CognitoIdentity identity = matches.getFirst();
        IdentityPool pool = publicPool(identity.identityPoolId(), region);
        if (!pool.getAccountId().equals(identity.accountId())) {
            throw identityMissing();
        }
        return identity;
    }

    public CognitoIdentity describeIdentity(String identityId, String region) {
        requireIdentity(identityId, region);
        CognitoIdentity identity = identities.get(identityId).orElseThrow(CognitoIdentityService::identityMissing);
        if (!identity.accountId().equals(regionResolver.getAccountId()) || !identity.region().equals(region)) {
            throw identityMissing();
        }
        describeIdentityPool(identity.identityPoolId(), region);
        return identity;
    }

    public record IdentityPage(List<CognitoIdentity> identities, String nextToken) {}

    public IdentityPage listIdentities(String poolId, Integer maxResults, String nextToken,
                                       boolean hideDisabled, String region) {
        IdentityPool pool = describeIdentityPool(poolId, region);
        List<CognitoIdentity> values = poolIdentities(pool, region).stream()
                .filter(identity -> !hideDisabled || identity.active())
                .sorted(Comparator.comparing(CognitoIdentity::identityId)).toList();
        int limit = pageSize(maxResults);
        String cursor = cursor(nextToken, poolId + ":identities:" + hideDisabled);
        List<CognitoIdentity> remaining = values.stream()
                .filter(identity -> cursor == null || identity.identityId().compareTo(cursor) > 0).toList();
        List<CognitoIdentity> page = remaining.stream().limit(limit).toList();
        return new IdentityPage(page, remaining.size() > limit
                ? nextToken(poolId + ":identities:" + hideDisabled, page.getLast().identityId()) : null);
    }

    public CognitoIdentity developerIdentity(String poolId, String requestedIdentityId,
                                              Map<String, String> logins, String region) {
        return developerIdentity(poolId, requestedIdentityId, logins, region, Function.identity());
    }

    public synchronized <T> T developerIdentity(String poolId, String requestedIdentityId,
                                                Map<String, String> logins, String region,
                                                Function<CognitoIdentity, T> beforePublish) {
        IdentityPool pool = describeIdentityPool(poolId, region);
        String provider = pool.getDeveloperProviderName();
        if (provider == null || logins.size() != 1 || !logins.containsKey(provider)
                || logins.get(provider) == null || logins.get(provider).isBlank()) {
            throw new AwsException("InvalidParameterException", "A configured developer provider login is required.", 400);
        }
        String user = logins.get(provider);
        CognitoIdentity linked = poolIdentities(pool, region).stream()
                .filter(identity -> identity.developerUserIdentifiers().contains(user)).findFirst().orElse(null);
        CognitoIdentity selected = requestedIdentityId == null ? linked : describeIdentity(requestedIdentityId, region);
        if (selected != null && (!selected.identityPoolId().equals(poolId) || !selected.active())) {
            throw identityMissing();
        }
        if (linked != null && selected != null && !linked.identityId().equals(selected.identityId())) {
            throw new AwsException("DeveloperUserAlreadyRegisteredException",
                    "The developer user is already registered with another identity.", 400);
        }
        if (selected == null) {
            selected = newIdentity(pool, region, true, List.of(user));
        } else {
            List<String> users = new ArrayList<>(selected.developerUserIdentifiers());
            if (!users.contains(user)) {
                users.add(user);
            }
            selected = selected.withDeveloperUsers(users, Instant.now());
        }
        T result = beforePublish.apply(selected);
        putIdentity(selected);
        return result;
    }

    public record DeveloperLookup(String identityId, List<String> developerUserIdentifiers, String nextToken) {}

    public DeveloperLookup lookupDeveloper(String poolId, String identityId, String developerUser,
                                           Integer maxResults, String nextToken, String region) {
        IdentityPool pool = describeIdentityPool(poolId, region);
        if (identityId == null && developerUser == null) {
            throw new AwsException("InvalidParameterException", "An identity or developer user is required.", 400);
        }
        CognitoIdentity identity = identityId == null ? poolIdentities(pool, region).stream()
                .filter(value -> value.developerUserIdentifiers().contains(developerUser))
                .findFirst().orElseThrow(CognitoIdentityService::identityMissing) : describeIdentity(identityId, region);
        if (!poolId.equals(identity.identityPoolId())) {
            throw identityMissing();
        }
        if (developerUser != null && !identity.developerUserIdentifiers().contains(developerUser)) {
            throw new AwsException("ResourceConflictException", "The developer user does not match the identity.", 400);
        }
        int limit = pageSize(maxResults);
        String scope = poolId + ":developer:" + identity.identityId();
        String cursor = cursor(nextToken, scope);
        List<String> remaining = identity.developerUserIdentifiers().stream().sorted()
                .filter(value -> cursor == null || value.compareTo(cursor) > 0).toList();
        List<String> page = remaining.stream().limit(limit).toList();
        return new DeveloperLookup(identity.identityId(), page,
                remaining.size() > limit ? nextToken(scope, page.getLast()) : null);
    }

    public synchronized void unlinkDeveloper(String poolId, String identityId, String provider,
                                               String developerUser, String region) {
        IdentityPool pool = describeIdentityPool(poolId, region);
        CognitoIdentity identity = describeIdentity(identityId, region);
        if (!poolId.equals(identity.identityPoolId()) || provider == null
                || !provider.equals(pool.getDeveloperProviderName())
                || !identity.developerUserIdentifiers().contains(developerUser)) {
            throw new AwsException("ResourceConflictException", "The developer login is not linked to this identity.", 400);
        }
        List<String> users = new ArrayList<>(identity.developerUserIdentifiers());
        users.remove(developerUser);
        putIdentity(identity.withDeveloperUsers(users, Instant.now()));
    }

    public synchronized List<String> deleteIdentities(List<String> identityIds, String region) {
        if (identityIds == null || identityIds.isEmpty() || identityIds.size() > 60) {
            throw new AwsException("InvalidParameterException", "Between 1 and 60 identities are required.", 400);
        }
        identityIds.forEach(value -> requireIdentity(value, region));
        Set<String> existing = new LinkedHashSet<>();
        List<String> unprocessed = new ArrayList<>();
        for (String id : identityIds) {
            CognitoIdentity identity = identities.get(id).orElse(null);
            if (identity != null && identity.accountId().equals(regionResolver.getAccountId())
                    && identity.region().equals(region)) {
                existing.add(id);
            }
        }
        deleteForAccount(regionResolver.getAccountId(), existing);
        return unprocessed;
    }

    public static void requireGuest(IdentityPool pool, CognitoIdentity identity) {
        if (!pool.isAllowUnauthenticatedIdentities()
                || (identity != null && (identity.authenticated() || !identity.active()))) {
            throw new AwsException("NotAuthorizedException", "Unauthenticated access is not supported.", 400);
        }
    }

    public static void rejectLogins(Map<String, String> logins) {
        if (logins != null && !logins.isEmpty()) {
            throw new AwsException("NotAuthorizedException", "External provider token validation is not supported.", 400);
        }
    }

    public static void requireSupportedRoleConfiguration(IdentityPool pool) {
        if ((pool.getRoleMappings() != null && !pool.getRoleMappings().isEmpty())
                || (pool.getPrincipalTagAttributeMaps() != null && !pool.getPrincipalTagAttributeMaps().isEmpty())) {
            throw new AwsException("InvalidIdentityPoolConfigurationException",
                    "Credential issuance with role mappings or principal-tag mappings is not supported.", 400);
        }
    }

    private List<CognitoIdentity> poolIdentities(IdentityPool pool, String region) {
        List<CognitoIdentity> values = identities instanceof AccountAwareStorageBackend<CognitoIdentity> aware
                ? aware.scanForAccount(pool.getAccountId(), key -> true) : identities.scan(key -> true);
        return values.stream().filter(value -> pool.getAccountId().equals(value.accountId())
                && pool.getIdentityPoolId().equals(value.identityPoolId()) && region.equals(value.region())).toList();
    }

    private void putIdentity(CognitoIdentity identity) {
        if (identities instanceof AccountAwareStorageBackend<CognitoIdentity> aware) {
            aware.putAllAtomicallyForAccount(identity.accountId(), Map.of(identity.identityId(), identity));
        } else {
            identities.putAllAtomically(Map.of(identity.identityId(), identity));
        }
    }

    private void deleteForAccount(String account, Set<String> keys) {
        if (identities instanceof AccountAwareStorageBackend<CognitoIdentity> aware) {
            aware.deleteAllAtomicallyForAccounts(Map.of(account, keys));
        } else {
            identities.deleteAllAtomically(keys);
        }
    }

    private static CognitoIdentity newIdentity(IdentityPool pool, String region, boolean authenticated,
                                               List<String> developerUsers) {
        Instant now = Instant.now();
        return new CognitoIdentity(region + ":" + UUID.randomUUID(), pool.getIdentityPoolId(), pool.getAccountId(),
                region, now, now, authenticated, true, developerUsers);
    }

    private static void requireIdentity(String id, String region) {
        if (!isIdentityId(id) || !id.startsWith(region + ":")) {
            throw new AwsException("InvalidParameterException", "A valid identity identifier in this region is required.", 400);
        }
    }

    private static AwsException identityMissing() {
        return new AwsException("ResourceNotFoundException", "The identity or identity pool was not found.", 400);
    }

    private static int pageSize(Integer requested) {
        int value = requested == null ? 60 : requested;
        if (value < 1 || value > 60) {
            throw new AwsException("InvalidParameterException", "MaxResults must be between 1 and 60.", 400);
        }
        return value;
    }

    private static String nextToken(String scope, String last) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((scope + "\n" + last).getBytes(StandardCharsets.UTF_8));
    }

    private static String cursor(String token, String scope) {
        if (token == null) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (decoded.startsWith(scope + "\n") && decoded.length() > scope.length() + 1) {
                return decoded.substring(scope.length() + 1);
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid or foreign pagination tokens are refused below.
        }
        throw new AwsException("InvalidParameterException", "The pagination token is invalid for this request.", 400);
    }
}
