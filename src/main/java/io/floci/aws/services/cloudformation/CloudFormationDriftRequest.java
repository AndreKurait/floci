package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.provisioners.CfnDriftProjection;
import jakarta.enterprise.context.RequestScoped;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared only by the drift authorization and handler of this HTTP request. */
@RequestScoped
public class CloudFormationDriftRequest {
    private CloudFormationDriftSnapshot stack;
    private List<String> selectedIds;
    private List<CfnDriftProjection> projections;

    public CloudFormationDriftSnapshot stack(String name, String region, String account,
                                             Supplier<CloudFormationDriftSnapshot> capture) {
        if (stack == null) {
            stack = capture.get();
        } else if (!Objects.equals(name, stack.requestedName())
                || !Objects.equals(region, stack.region()) || !Objects.equals(account, stack.accountId())) {
            throw new IllegalStateException("Drift request cannot change its stack or caller scope");
        }
        return stack;
    }

    public List<CfnDriftProjection> projections(List<String> ids, Supplier<List<CfnDriftProjection>> capture) {
        if (projections == null) {
            selectedIds = List.copyOf(ids);
            projections = List.copyOf(capture.get());
        } else if (!selectedIds.equals(ids)) {
            throw new IllegalStateException("Drift request cannot change its selected resources");
        }
        return projections;
    }
}
