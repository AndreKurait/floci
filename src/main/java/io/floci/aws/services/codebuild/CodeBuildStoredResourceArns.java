package io.floci.aws.services.codebuild;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.codebuild.model.Build;
import io.floci.aws.services.codebuild.model.Project;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks CodeBuild of the resources a request names: the project the caller's build of an id belongs to, as
 * the service stores them, which CodeBuild checks a call naming the build on, and the tags of a project or a report
 * group.
 */
@ApplicationScoped
public class CodeBuildStoredResourceArns implements StoredResourceArns {

    private final CodeBuildService codeBuild;
    private final RegionResolver regionResolver;

    @Inject
    public CodeBuildStoredResourceArns(CodeBuildService codeBuild, RegionResolver regionResolver) {
        this.codeBuild = codeBuild;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return "codebuild";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /** The project of the caller's build of the id an {@code id} or {@code ids[]} gives; empty for one it has none of. */
    @Override
    public List<String> through(String member, String value, String region) {
        if (!"ids[]".equals(member) && !"id".equals(member)) {
            return List.of();
        }
        return codeBuild.batchGetBuilds(region, regionResolver.getAccountId(), List.of(value)).stream()
                .map(build -> projectArn(build, region))
                .distinct()
                .toList();
    }

    private String projectArn(Build build, String region) {
        List<Project> projects = codeBuild.batchGetProjects(region, List.of(build.getProjectName()));
        // Deleting a project retains its builds. Their project permission must still name the original project.
        return projects.isEmpty()
                ? regionResolver.buildArn("codebuild", region, "project/" + build.getProjectName())
                : projects.getFirst().getArn();
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        return codeBuild.storedTags(arn);
    }
}
