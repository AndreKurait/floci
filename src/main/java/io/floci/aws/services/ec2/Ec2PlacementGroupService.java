package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.model.Ec2PlacementGroup;
import io.floci.aws.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Native placement-group metadata. Instance placement and mutable tags are not modeled here. */
@ApplicationScoped
public class Ec2PlacementGroupService {

    private static final Set<String> FILTERS = Set.of(
            "group-name", "group-id", "group-arn", "strategy", "state", "spread-level", "tag-key");

    private final AccountAwareStorageBackend<Ec2PlacementGroup> groups;
    private final RegionResolver regionResolver;
    private final Ec2PlacementGroupRequest request;
    private final Object registry = new Object();

    @Inject
    public Ec2PlacementGroupService(StorageFactory factory, RegionResolver regionResolver,
                                    Ec2PlacementGroupRequest request) {
        this(factory.create("ec2", "ec2-placement-groups.json",
                new TypeReference<Map<String, Ec2PlacementGroup>>() {}), regionResolver, request);
    }

    Ec2PlacementGroupService(AccountAwareStorageBackend<Ec2PlacementGroup> groups,
                             RegionResolver regionResolver, Ec2PlacementGroupRequest request) {
        this.groups = groups;
        this.regionResolver = regionResolver;
        this.request = request;
    }

    public record Scope(String accountId, String region, String partition) {
    }

    Scope scope(String region) {
        return new Scope(regionResolver.getAccountId(), region, regionResolver.partitionForRegion(region));
    }

    public Ec2PlacementGroup create(String region, String name, String strategy, Integer partitionCount,
                                    String spreadLevel, Map<String, String> tags, boolean dryRun) {
        Scope scope = scope(region);
        validateName(name);
        validateStrategy(strategy, partitionCount, spreadLevel);
        Map<String, String> copiedTags = Map.copyOf(tags);
        List<Tag> suppliedTags = copiedTags.entrySet().stream()
                .map(tag -> new Tag(tag.getKey(), tag.getValue())).toList();
        Ec2Service.validateNewResourceTags(suppliedTags);
        if (copiedTags.keySet().stream().anyMatch(Ec2Service::isAwsTagKey)) {
            throw new AwsException("InvalidParameterValue", "Tag keys starting with 'aws:' are reserved.", 400);
        }
        synchronized (registry) {
            if (groups.getForAccount(scope.accountId(), key(scope, name)).isPresent()) {
                throw new AwsException("InvalidPlacementGroup.Duplicate",
                        "A placement group named '" + name + "' already exists.", 400);
            }
            checkDryRun(dryRun);
            String id;
            List<Ec2PlacementGroup> current = ownedGroups(scope);
            do {
                id = "pg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
            } while (containsId(current, id));
            Ec2PlacementGroup group = new Ec2PlacementGroup(scope.accountId(), scope.region(), scope.partition(),
                    name, id, UUID.randomUUID().toString(), strategy, partitionCount, spreadLevel, copiedTags);
            groups.putAllAtomicallyForAccount(scope.accountId(), Map.of(key(scope, name), group));
            return group;
        }
    }

    public List<Ec2PlacementGroup> describe(String region, List<String> names, List<String> ids,
                                           List<Map.Entry<String, List<String>>> filters, boolean dryRun) {
        Scope scope = scope(region);
        if (!names.isEmpty() && !ids.isEmpty()) {
            throw new AwsException("UnsupportedOperation",
                    "Use GroupName or GroupId selectors separately for placement groups.", 400);
        }
        for (Map.Entry<String, List<String>> filter : filters) {
            if (!FILTERS.contains(filter.getKey()) && !filter.getKey().startsWith("tag:")) {
                throw new AwsException("InvalidParameterValue",
                        "The filter '" + filter.getKey() + "' is not supported for placement groups.", 400);
            }
            if (filter.getValue().isEmpty()) {
                throw new AwsException("InvalidParameterValue", "Each placement-group filter requires a value.", 400);
            }
        }
        synchronized (registry) {
            List<Ec2PlacementGroup> current = ownedGroups(scope);
            for (String name : names) {
                if (current.stream().noneMatch(group -> group.groupName().equals(name))) {
                    throw missing(name);
                }
            }
            for (String id : ids) {
                if (!containsId(current, id)) {
                    throw missing(id);
                }
            }
            List<Ec2PlacementGroup> result = current.stream()
                    .filter(group -> names.isEmpty() || names.contains(group.groupName()))
                    .filter(group -> ids.isEmpty() || ids.contains(group.groupId()))
                    .filter(group -> filters.stream().allMatch(filter -> matches(group, filter)))
                    .sorted(Comparator.comparing(Ec2PlacementGroup::groupName)).toList();
            checkDryRun(dryRun);
            return result;
        }
    }

    /** Reads without migration or writes, and preserves absence against a later creation of the same name. */
    public Ec2PlacementGroup authorizationTarget(String region, String name) {
        Scope scope = scope(region);
        synchronized (registry) {
            Ec2PlacementGroup group = find(scope, name);
            Ec2PlacementGroupRequest.DeleteTarget target = request.capture(scope, name, group);
            if (!scope.equals(target.scope()) || !Objects.equals(name, target.name())) {
                throw missing(name);
            }
            return target.group();
        }
    }

    public void delete(String region, String name, boolean dryRun) {
        Scope scope = scope(region);
        validateName(name);
        synchronized (registry) {
            Ec2PlacementGroup current = find(scope, name);
            Ec2PlacementGroupRequest.DeleteTarget target = request.deleteTarget();
            if (target != null && (!scope.equals(target.scope()) || !name.equals(target.name())
                    || !Objects.equals(generation(current), generation(target.group())))) {
                throw missing(name);
            }
            if (current == null) {
                throw missing(name);
            }
            checkDryRun(dryRun);
            groups.deleteAllAtomicallyForAccounts(Map.of(scope.accountId(), Set.of(key(scope, name))));
        }
    }

    private Ec2PlacementGroup find(Scope scope, String name) {
        if (name == null) {
            return null;
        }
        return groups.getForAccount(scope.accountId(), key(scope, name))
                .filter(group -> owned(scope, group) && name.equals(group.groupName())).orElse(null);
    }

    private List<Ec2PlacementGroup> ownedGroups(Scope scope) {
        return groups.scanForAccount(scope.accountId(), key -> key.startsWith(scope.region() + "::"))
                .stream().filter(group -> owned(scope, group)).toList();
    }

    private static boolean owned(Scope scope, Ec2PlacementGroup group) {
        return scope.accountId().equals(group.accountId()) && scope.region().equals(group.region())
                && scope.partition().equals(group.partition()) && group.groupName() != null
                && group.groupId() != null && group.generation() != null;
    }

    private static String key(Scope scope, String name) {
        return scope.region() + "::" + name;
    }

    private static String generation(Ec2PlacementGroup group) {
        return group == null ? null : group.generation();
    }

    private static boolean containsId(List<Ec2PlacementGroup> groups, String id) {
        return groups.stream().anyMatch(group -> group.groupId().equals(id));
    }

    private static boolean matches(Ec2PlacementGroup group, Map.Entry<String, List<String>> filter) {
        String name = filter.getKey();
        List<String> values = filter.getValue();
        if ("tag-key".equals(name)) {
            return group.tags().keySet().stream().anyMatch(key -> Ec2Service.matchesFilterValue(values, key));
        }
        String value = switch (name) {
            case "group-name" -> group.groupName();
            case "group-id" -> group.groupId();
            case "group-arn" -> group.arn();
            case "strategy" -> group.strategy();
            case "state" -> "available";
            case "spread-level" -> group.spreadLevel();
            default -> group.tags().get(name.substring("tag:".length()));
        };
        return value != null && Ec2Service.matchesFilterValue(values, value);
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("MissingParameter", "GroupName is required.", 400);
        }
        if (name.length() > 255 || name.chars().anyMatch(character -> character > 127)) {
            throw new AwsException("InvalidParameterValue",
                    "GroupName must contain at most 255 ASCII characters.", 400);
        }
    }

    private static void validateStrategy(String strategy, Integer partitionCount, String spreadLevel) {
        if (strategy == null || strategy.isBlank()) {
            throw new AwsException("MissingParameter", "Strategy is required.", 400);
        }
        if (!Set.of("cluster", "partition", "spread").contains(strategy)) {
            throw new AwsException("UnsupportedOperation", "This placement strategy is not supported.", 400);
        }
        if ("partition".equals(strategy)) {
            if (partitionCount == null || partitionCount < 1 || partitionCount > 7 || spreadLevel != null) {
                throw new AwsException("InvalidParameterValue",
                        "Partition placement requires an explicit PartitionCount from 1 to 7 and no SpreadLevel.", 400);
            }
        } else if (partitionCount != null) {
            throw new AwsException("InvalidParameterCombination", "PartitionCount requires partition strategy.", 400);
        }
        if ("spread".equals(strategy)) {
            if (!"rack".equals(spreadLevel)) {
                throw new AwsException("UnsupportedOperation",
                        "Spread placement requires explicit rack SpreadLevel.", 400);
            }
        } else if (spreadLevel != null) {
            throw new AwsException("InvalidParameterCombination", "SpreadLevel requires spread strategy.", 400);
        }
    }

    private static void checkDryRun(boolean dryRun) {
        if (dryRun) {
            throw new AwsException("DryRunOperation", "Request would have succeeded, but DryRun flag is set.", 412);
        }
    }

    private static AwsException missing(String name) {
        return new AwsException("InvalidPlacementGroup.Unknown",
                "The placement group '" + name + "' does not exist in this request's scope.", 400);
    }
}
