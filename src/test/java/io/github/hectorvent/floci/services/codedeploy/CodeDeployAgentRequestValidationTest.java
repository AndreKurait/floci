package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeDeployAgentRequestValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeDeployService service = mock(CodeDeployService.class);
    private final CodeDeployJsonHandler handler = new CodeDeployJsonHandler(service, mapper,
            mock(CodeDeployAgentJsonHandler.class));

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"deploymentMode\":\"RESTART\"}",
            "{\"deploymentMode\":42}",
            "{\"fileExistsBehavior\":\"unknown\"}",
            "{\"autoRollbackConfiguration\":{\"enabled\":false}}",
            "{\"overrideAlarmConfiguration\":{\"enabled\":false}}",
            "{\"targetInstances\":{\"autoScalingGroups\":[\"replacement\"]}}",
            "{\"ec2DeploymentOptions\":{\"autoScalingGroupProcessesToSuspend\":[\"Terminate\"]}}",
            "{\"ignoreApplicationStopFailures\":true}",
            "{\"updateOutdatedInstancesOnly\":true}",
            "{\"ignoreApplicationStopFailures\":\"false\"}",
            "{\"updateOutdatedInstancesOnly\":1}"
    })
    void unsupportedOrInvalidOptionsCannotQueueADeployment(String options) throws Exception {
        ObjectNode request = request();
        request.setAll((ObjectNode) mapper.readTree(options));
        assertThrows(AwsException.class, () -> handler.handle("CreateDeployment", request, "us-east-1"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISALLOW", "OVERWRITE", "RETAIN"})
    void theSelectedFileBehaviorReachesTheDeploymentService(String behavior) throws Exception {
        ObjectNode request = request();
        request.put("fileExistsBehavior", behavior);
        accepts(behavior, request);
    }

    @Test
    void explicitDefaultFlagsUseTheNormalDeploymentPath() throws Exception {
        ObjectNode request = request();
        request.put("deploymentMode", "STANDARD");
        request.put("ignoreApplicationStopFailures", false);
        request.put("updateOutdatedInstancesOnly", false);
        accepts("DISALLOW", request);
    }

    private ObjectNode request() {
        return mapper.valueToTree(Map.of("applicationName", "app", "deploymentGroupName", "group",
                "revision", Map.of("revisionType", "S3",
                        "s3Location", Map.of("bucket", "revisions", "key", "revision.zip", "bundleType", "zip"))));
    }

    private void accepts(String behavior, ObjectNode request) throws Exception {
        when(service.createDeployment(eq("us-east-1"), eq("app"), eq("group"), isNull(),
                anyMap(), isNull(), anyString())).thenReturn("d-created");
        handler.handle("CreateDeployment", request, "us-east-1").close();
        verify(service).createDeployment(eq("us-east-1"), eq("app"), eq("group"), isNull(),
                anyMap(), isNull(), eq(behavior));
    }
}
