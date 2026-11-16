package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/** The caller's stored stack ARN, selected by its name or reached through an exact change-set ARN. */
@ApplicationScoped
public class CloudFormationStoredResourceArns implements StoredResourceArns {

    private final CloudFormationService stacks;

    @Inject
    public CloudFormationStoredResourceArns(CloudFormationService stacks) {
        this.stacks = stacks;
    }

    @Override
    public String namespace() {
        return "cloudformation";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return "stack".equals(type) && names.size() == 1
                ? stacks.stackArn(names.getFirst(), region) : Optional.empty();
    }

    @Override
    public List<String> through(String member, String value, String region) {
        return "ChangeSetName".equals(member) ? stacks.changeSetStackArn(value, region).stream().toList() : List.of();
    }
}
