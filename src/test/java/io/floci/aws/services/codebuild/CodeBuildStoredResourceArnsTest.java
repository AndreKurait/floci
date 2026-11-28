package io.floci.aws.services.codebuild;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.services.codebuild.model.Build;
import io.floci.aws.services.codebuild.model.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeBuildStoredResourceArnsTest {
    private static final String ACCOUNT = "111122223333";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-cn,cn-north-1", "aws-us-gov,us-gov-west-1"})
    void retainedBuildsKeepTheirProjectPermissionAfterProjectDeletion(String partition, String region) throws Exception {
        CodeBuildService service = mock(CodeBuildService.class);
        Build build = new Build();
        build.setProjectName("app");
        when(service.batchGetBuilds(region, ACCOUNT, List.of("app:7"))).thenReturn(List.of(build));
        when(service.batchGetProjects(region, List.of("app"))).thenReturn(List.of());
        CodeBuildStoredResourceArns stored = new CodeBuildStoredResourceArns(service, new RegionResolver(region, ACCOUNT));
        String project = "arn:" + partition + ":codebuild:" + region + ":" + ACCOUNT + ":project/app";
        for (String operation : List.of("BatchGetBuilds", "BatchDeleteBuilds", "StopBuild", "RetryBuild")) {
            String body = operation.startsWith("Batch") ? "{\"ids\":[\"app:7\"]}" : "{\"id\":\"app:7\"}";
            assertEquals(List.of(project), RequestResources.ofBody("codebuild:" + operation, MAPPER.readTree(body),
                    partition, region, ACCOUNT, stored), operation);
        }
        assertEquals(List.of(), stored.through("id", "missing:1", region));
        CodeBuildStoredResourceArns anotherAccount =
                new CodeBuildStoredResourceArns(service, new RegionResolver(region, "999900001111"));
        assertEquals(List.of(), anotherAccount.through("id", "app:7", region));
    }

    @Test
    void anExistingProjectKeepsItsStoredCanonicalArn() {
        CodeBuildService service = mock(CodeBuildService.class);
        Build build = new Build();
        build.setProjectName("app");
        Project project = new Project();
        project.setArn("arn:aws:codebuild:us-east-1:" + ACCOUNT + ":project/StoredApp");
        when(service.batchGetBuilds("us-east-1", ACCOUNT, List.of("app:7"))).thenReturn(List.of(build));
        when(service.batchGetProjects("us-east-1", List.of("app"))).thenReturn(List.of(project));
        CodeBuildStoredResourceArns stored =
                new CodeBuildStoredResourceArns(service, new RegionResolver("us-east-1", ACCOUNT));
        assertEquals(List.of(project.getArn()), stored.through("id", "app:7", "us-east-1"));
    }
}
