package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Ec2PlacementGroup;
import jakarta.enterprise.context.RequestScoped;

/** Carries the exact generation whose immutable tags authorized this request's deletion. */
@RequestScoped
public class Ec2PlacementGroupRequest {

    private DeleteTarget deleteTarget;

    public DeleteTarget deleteTarget() {
        return deleteTarget;
    }

    DeleteTarget capture(Ec2PlacementGroupService.Scope scope, String name, Ec2PlacementGroup group) {
        if (deleteTarget == null) {
            deleteTarget = new DeleteTarget(scope, name, group);
        }
        return deleteTarget;
    }

    public record DeleteTarget(Ec2PlacementGroupService.Scope scope, String name, Ec2PlacementGroup group) {
    }
}
