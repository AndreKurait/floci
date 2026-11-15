package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsPartition;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.SignedMarkers;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.account.model.AccountRegion;
import io.github.hectorvent.floci.services.account.model.AccountRegionTransition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Account-wide Region state within the request's partition, independent of its signing Region. */
@ApplicationScoped
public class AccountRegionService implements Resettable {
    private static final Set<String> STATUSES = Set.of(
            "ENABLED", "ENABLING", "DISABLING", "DISABLED", "ENABLED_BY_DEFAULT");
    /** A local emulator schedule, not an AWS completion-time guarantee. */
    private static final long TRANSITION_MILLIS = 500;

    private final AccountAwareStorageBackend<AccountRegionTransition> transitions;
    private final AccountService accounts;
    private final RegionResolver regions;
    private final Clock clock;
    private SignedMarkers markers = new SignedMarkers(1000);
    private boolean resetting;

    @Inject
    public AccountRegionService(StorageFactory storageFactory, AccountService accounts, RegionResolver regions) {
        this(storageFactory, accounts, regions, Clock.systemUTC());
    }

    AccountRegionService(StorageFactory storageFactory, AccountService accounts, RegionResolver regions, Clock clock) {
        this.transitions = storageFactory.create("account", "account-region-transitions.json",
                new TypeReference<Map<String, AccountRegionTransition>>() {});
        this.accounts = accounts;
        this.regions = regions;
        this.clock = clock;
    }

    public synchronized PaginatedResult<AccountRegion> listRegions(String caller, JsonNode request) {
        admit(request);
        String owner = accounts.resolveTargetAccount(caller, request);
        String partition = regions.getPartition();
        Integer requestedSize = Pagination.parseMaxResults(request, "MaxResults", "ValidationException");
        if (requestedSize != null && (requestedSize < 1 || requestedSize > 50)) {
            throw validation("MaxResults must be between 1 and 50.");
        }
        int size = requestedSize == null ? 50 : requestedSize;
        String token = Pagination.parseNextToken(request, "NextToken", "ValidationException");
        if (token != null && token.length() > 1000) {
            throw validation("NextToken must not exceed 1000 characters.");
        }
        Set<String> filters = filters(request);
        List<AccountRegion> selected = new ArrayList<>();
        StringBuilder scope = new StringBuilder(owner).append('/').append(partition)
                .append('/').append(size).append('/').append(filters);
        long now = clock.millis();
        for (AwsPartition.Region region : catalog(partition)) {
            AccountRegionTransition transition = transition(owner, partition, region);
            String status = status(region, transition, now);
            scope.append('/').append(region.id()).append(':').append(status);
            if (transition != null) {
                scope.append(':').append(transition.revision()).append(':').append(transition.deadlineMillis());
            }
            if (filters.isEmpty() || filters.contains(status)) {
                selected.add(new AccountRegion(region.id(), status));
            }
        }
        return Pagination.slice(selected, AccountRegion::regionName, size,
                token == null || token.isEmpty() ? null : token, markers, scope.toString(),
                ignored -> validation("Invalid NextToken."));
    }

    public synchronized AccountRegion getRegionOptStatus(String caller, JsonNode request) {
        admit(request);
        String owner = accounts.resolveTargetAccount(caller, request);
        String partition = regions.getPartition();
        AwsPartition.Region region = target(partition, request);
        return new AccountRegion(region.id(), status(region, transition(owner, partition, region), clock.millis()));
    }

    public synchronized void enableRegion(String caller, JsonNode request) {
        change(caller, request, "ENABLED");
    }

    public synchronized void disableRegion(String caller, JsonNode request) {
        change(caller, request, "DISABLED");
    }

    private void change(String caller, JsonNode request, String terminalStatus) {
        admit(request);
        String owner = accounts.resolveTargetAccount(caller, request);
        String partition = regions.getPartition();
        AwsPartition.Region region = target(partition, request);
        if (!region.optIn()) {
            throw validation("A Region enabled by default cannot be enabled or disabled.");
        }
        long now = clock.millis();
        AccountRegionTransition prior = transition(owner, partition, region);
        String current = status(region, prior, now);
        if (terminalStatus.equals(current)) {
            return;
        }
        if ("ENABLING".equals(current) || "DISABLING".equals(current)) {
            if (prior.terminalStatus().equals(terminalStatus)) {
                return;
            }
            throw new AwsException("ConflictException", "The Region has a transition in progress.", 409);
        }
        AccountRegionTransition next = new AccountRegionTransition(owner, partition, region.id(),
                terminalStatus, now + TRANSITION_MILLIS, UUID.randomUUID().toString());
        transitions.putAllAtomicallyForAccount(owner, Map.of(key(partition, region.id()), next));
    }

    private void admit(JsonNode request) {
        if (resetting) {
            throw new AwsException("InternalServerException", "Account Region state is being reset.", 500);
        }
        if (request == null || !request.isObject()) {
            throw new AwsException("SerializationException", "The request must be a JSON object.", 400);
        }
    }

    private AccountRegionTransition transition(String owner, String partition, AwsPartition.Region region) {
        AccountRegionTransition value = transitions.getForAccount(owner, key(partition, region.id())).orElse(null);
        if (value != null && (!region.optIn() || !owner.equals(value.accountId())
                || !partition.equals(value.partition()) || !region.id().equals(value.regionName())
                || !("ENABLED".equals(value.terminalStatus()) || "DISABLED".equals(value.terminalStatus()))
                || value.deadlineMillis() < 0 || value.revision() == null || value.revision().isBlank())) {
            throw new AwsException("InternalServerException", "Stored Account Region identity is invalid.", 500);
        }
        return value;
    }

    private static String status(AwsPartition.Region region, AccountRegionTransition transition, long now) {
        if (!region.optIn()) {
            return "ENABLED_BY_DEFAULT";
        }
        if (transition == null) {
            return "DISABLED";
        }
        if (now >= transition.deadlineMillis()) {
            return transition.terminalStatus();
        }
        return "ENABLED".equals(transition.terminalStatus()) ? "ENABLING" : "DISABLING";
    }

    private static AwsPartition.Region target(String partition, JsonNode request) {
        JsonNode value = request.get("RegionName");
        if (value == null || value.isNull()) {
            throw validation("RegionName is required.");
        }
        if (!value.isTextual()) {
            throw new AwsException("SerializationException", "RegionName must be a string.", 400);
        }
        String name = value.textValue();
        if (name.isEmpty() || name.length() > 50) {
            throw validation("RegionName must be between 1 and 50 characters.");
        }
        return catalog(partition).stream().filter(region -> region.id().equals(name)).findFirst()
                .orElseThrow(() -> validation("The specified Region is not in this account's partition."));
    }

    private static List<AwsPartition.Region> catalog(String partition) {
        return AwsPartitions.byId(partition).regions().stream()
                .sorted(Comparator.comparing(AwsPartition.Region::id)).toList();
    }

    private static Set<String> filters(JsonNode request) {
        JsonNode values = request.get("RegionOptStatusContains");
        Set<String> result = new TreeSet<>();
        if (values == null || values.isNull()) {
            return result;
        }
        if (!values.isArray()) {
            throw new AwsException("SerializationException", "RegionOptStatusContains must be a list.", 400);
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || !STATUSES.contains(value.textValue())) {
                throw validation("RegionOptStatusContains contains an invalid status.");
            }
            result.add(value.textValue());
        }
        return result;
    }

    private static String key(String partition, String region) {
        return partition + "/" + region;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    @Override
    public synchronized void beforeReset() {
        resetting = true;
    }

    @Override
    public synchronized void clear() {
        transitions.clear();
        markers = new SignedMarkers(1000);
    }

    @Override
    public synchronized void afterReset() {
        resetting = false;
    }
}
