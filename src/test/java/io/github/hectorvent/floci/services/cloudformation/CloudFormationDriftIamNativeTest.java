package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.User;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudFormationDriftIamNativeTest {
    private static final String ACCOUNT = "135791357913";
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"cloudformation:DetectStackDrift", "cloudformation:DetectStackResourceDrift",
            "cloudformation:BatchDescribeTypeConfigurations", "sns:GetTopicAttributes"})
    void wholeDetectionRequiresEveryCallerPermissionAndDenialsPublishNothing(String denied) {
        try (CfnDriftNativeFixture f = new CfnDriftNativeFixture(ACCOUNT, Region.US_EAST_1);
             Caller caller = new Caller()) {
            caller.policy(List.of(allow("cloudformation:*", f.stack.stackId()),
                    allow("cloudformation:BatchDescribeTypeConfigurations", "*"),
                    allow("sns:GetTopicAttributes", List.copyOf(f.topics.values())),
                    statement("Deny", denied, "*")));
            denied(() -> caller.client.detectStackDrift(request -> request.stackName(f.name)), denied);
            assertTrue(f.cfn.describeStackResourceDrifts(request -> request.stackName(f.name)).stackResourceDrifts().isEmpty());
            caller.policy(List.of(allow("cloudformation:*", f.stack.stackId()),
                    allow("cloudformation:BatchDescribeTypeConfigurations", "*"),
                    allow("sns:GetTopicAttributes", List.copyOf(f.topics.values()))));
            assertDoesNotThrow(() -> caller.client.detectStackDrift(request -> request.stackName(f.stack.stackId())));
        }
    }

    @Test
    void singleDetectAndListUseExactStackTagsButStatusUsesWildcard() {
        try (CfnDriftNativeFixture f = new CfnDriftNativeFixture(ACCOUNT, Region.US_EAST_1);
             Caller caller = new Caller()) {
            Map<String, Object> scoped = Map.of("Effect", "Allow", "Action", "cloudformation:*",
                    "Resource", f.stack.stackId(), "Condition",
                    Map.of("StringEquals", Map.of("aws:ResourceTag/OwnedDrift", f.marker)));
            caller.policy(List.of(scoped, allow("sns:GetTopicAttributes", f.topics.get("A"))));
            assertDoesNotThrow(() -> caller.client.detectStackResourceDrift(request ->
                    request.stackName(f.name).logicalResourceId("A")));
            assertEquals(1, caller.client.describeStackResourceDrifts(request -> request.stackName(f.name))
                    .stackResourceDrifts().size());
            String id = f.detect();
            denied(() -> caller.client.describeStackDriftDetectionStatus(request -> request.stackDriftDetectionId(id)),
                    "cloudformation:DescribeStackDriftDetectionStatus");
            caller.policy(List.of(scoped, allow("cloudformation:DescribeStackDriftDetectionStatus", "*")));
            assertEquals(id, caller.client.describeStackDriftDetectionStatus(request ->
                    request.stackDriftDetectionId(id)).stackDriftDetectionId());
        }
    }

    @Test
    void stackTagsCannotSatisfyNativeTopicTagConditionsAndFreshTopicTagsDo() {
        try (CfnDriftNativeFixture f = new CfnDriftNativeFixture(ACCOUNT, Region.US_EAST_1);
             Caller caller = new Caller()) {
            caller.policy(List.of(allow("cloudformation:DetectStackResourceDrift", f.stack.stackId()),
                    Map.of("Effect", "Allow", "Action", "sns:GetTopicAttributes", "Resource", f.topics.get("A"),
                            "Condition", Map.of("StringEquals", Map.of("aws:ResourceTag/OwnedDrift", f.marker)))));
            denied(() -> caller.client.detectStackResourceDrift(request ->
                    request.stackName(f.name).logicalResourceId("A")), "sns:GetTopicAttributes");
            f.sns.tagResource(request -> request.resourceArn(f.topics.get("A"))
                    .tags(tag -> tag.key("OwnedDrift").value(f.marker)));
            assertDoesNotThrow(() -> caller.client.detectStackResourceDrift(request ->
                    request.stackName(f.name).logicalResourceId("A")));
            f.sns.untagResource(request -> request.resourceArn(f.topics.get("A")).tagKeys("OwnedDrift"));
            denied(() -> caller.client.detectStackResourceDrift(request ->
                    request.stackName(f.name).logicalResourceId("A")), "sns:GetTopicAttributes");
        }
    }

    @Test
    void topicPolicyExplicitDenyIsAppliedAndDeletedTopicStillNeedsReadPermission() {
        try (CfnDriftNativeFixture f = new CfnDriftNativeFixture(ACCOUNT, Region.US_EAST_1);
             Caller caller = new Caller()) {
            caller.policy(List.of(allow("cloudformation:*", f.stack.stackId()), allow("sns:GetTopicAttributes", "*")));
            f.sns.setTopicAttributes(request -> request.topicArn(f.topics.get("A")).attributeName("Policy")
                    .attributeValue(json(Map.of("Version", "2012-10-17", "Statement", List.of(Map.of(
                            "Effect", "Deny", "Principal", "*", "Action", "sns:GetTopicAttributes",
                            "Resource", f.topics.get("A")))))));
            denied(() -> caller.client.detectStackResourceDrift(request ->
                    request.stackName(f.name).logicalResourceId("A")), "sns:GetTopicAttributes");
            f.sns.deleteTopic(request -> request.topicArn(f.topics.get("A")));
            caller.policy(List.of(allow("cloudformation:*", f.stack.stackId())));
            denied(() -> caller.client.detectStackResourceDrift(request ->
                    request.stackName(f.name).logicalResourceId("A")), "sns:GetTopicAttributes");
            caller.policy(List.of(allow("cloudformation:*", f.stack.stackId()), allow("sns:GetTopicAttributes", f.topics.get("A"))));
            assertEquals("DELETED", caller.client.detectStackResourceDrift(request ->
                    request.stackName(f.name).logicalResourceId("A")).stackResourceDrift().stackResourceDriftStatusAsString());
        }
    }

    @Test
    void permissionsBoundaryStillCapsTheSupplementalNativeRead() {
        try (CfnDriftNativeFixture f = new CfnDriftNativeFixture(ACCOUNT, Region.US_EAST_1);
             Caller caller = new Caller()) {
            caller.policy(List.of(allow("*", "*")));
            String policy = caller.iam.createPolicy(request -> request.policyName(caller.name)
                    .policyDocument(json(Map.of("Version", "2012-10-17", "Statement",
                            List.of(allow("*", "*"), statement("Deny", "sns:GetTopicAttributes", "*")))))).policy().arn();
            try {
                caller.iam.putUserPermissionsBoundary(request -> request.userName(caller.name).permissionsBoundary(policy));
                denied(() -> caller.client.detectStackResourceDrift(request ->
                        request.stackName(f.name).logicalResourceId("A")), "sns:GetTopicAttributes");
                assertTrue(f.cfn.describeStackResourceDrifts(request -> request.stackName(f.name)).stackResourceDrifts().isEmpty());
            } finally {
                caller.iam.deleteUserPermissionsBoundary(request -> request.userName(caller.name));
                caller.iam.deletePolicy(request -> request.policyArn(policy));
            }
            assertDoesNotThrow(() -> caller.client.detectStackResourceDrift(request ->
                    request.stackName(f.name).logicalResourceId("A")));
        }
    }

    @ParameterizedTest
    @CsvSource({"single,identity-deny", "whole,identity-deny", "single,identity-allow", "whole,identity-allow",
            "single,resource-deny", "whole,resource-deny"})
    void supplementalSnsReadRetainsActualHttpTransportForIdentityAndResourcePolicies(String operation, String policy) {
        try (CfnDriftNativeFixture f = new CfnDriftNativeFixture(ACCOUNT, Region.US_EAST_1);
             Caller caller = new Caller()) {
            List<Map<String, Object>> permissions = new ArrayList<>(List.of(
                    allow("cloudformation:*", f.stack.stackId()),
                    allow("cloudformation:BatchDescribeTypeConfigurations", "*")));
            if ("identity-allow".equals(policy)) {
                permissions.add(transportStatement("Allow", List.copyOf(f.topics.values()), "true"));
            } else {
                permissions.add(allow("sns:GetTopicAttributes", List.copyOf(f.topics.values())));
                if ("identity-deny".equals(policy)) {
                    permissions.add(transportStatement("Deny", "*", "false"));
                } else {
                    topicTransportDeny(f, "false");
                }
            }
            caller.policy(permissions);
            AtomicReference<URI> transmitted = new AtomicReference<>();
            Runnable detect = () -> detectCapturingTransport(caller, f, operation, transmitted);
            denied(detect, "sns:GetTopicAttributes");
            assertEquals("http", transmitted.get().getScheme());
            assertTrue(f.cfn.describeStackResourceDrifts(request -> request.stackName(f.name)).stackResourceDrifts().isEmpty());
            permissions = new ArrayList<>(List.of(allow("cloudformation:*", f.stack.stackId()),
                    allow("cloudformation:BatchDescribeTypeConfigurations", "*"),
                    transportStatement("Allow", List.copyOf(f.topics.values()), "false")));
            if ("identity-deny".equals(policy)) {
                permissions.add(transportStatement("Deny", "*", "true"));
            } else if ("resource-deny".equals(policy)) {
                topicTransportDeny(f, "true");
            }
            caller.policy(permissions);
            transmitted.set(null);
            assertDoesNotThrow(detect::run);
            assertEquals("http", transmitted.get().getScheme());
            assertEquals("whole".equals(operation) ? 2 : 1,
                    f.cfn.describeStackResourceDrifts(request -> request.stackName(f.name)).stackResourceDrifts().size());
        }
    }

    private static Map<String, Object> transportStatement(String effect, Object resource, String secure) {
        return Map.of("Effect", effect, "Action", "sns:GetTopicAttributes", "Resource", resource,
                "Condition", Map.of("Bool", Map.of("aws:SecureTransport", secure)));
    }

    private static void topicTransportDeny(CfnDriftNativeFixture fixture, String secure) {
        fixture.sns.setTopicAttributes(request -> request.topicArn(fixture.topics.get("A")).attributeName("Policy")
                .attributeValue(json(Map.of("Version", "2012-10-17", "Statement", List.of(Map.of(
                        "Effect", "Deny", "Principal", "*", "Action", "sns:GetTopicAttributes",
                        "Resource", fixture.topics.get("A"),
                        "Condition", Map.of("Bool", Map.of("aws:SecureTransport", secure))))))));
    }

    private static void detectCapturingTransport(Caller caller, CfnDriftNativeFixture fixture,
                                                 String operation, AtomicReference<URI> transmitted) {
        ExecutionInterceptor capture = new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
                transmitted.set(context.httpRequest().getUri());
            }
        };
        try (CloudFormationClient client = AwsTestClients.create(CloudFormationClient.builder()
                .overrideConfiguration(configuration -> configuration.addExecutionInterceptor(capture)),
                caller.key.accessKeyId(), caller.key.secretAccessKey(), Region.US_EAST_1)) {
            if ("whole".equals(operation)) {
                client.detectStackDrift(request -> request.stackName(fixture.name));
            } else {
                client.detectStackResourceDrift(request -> request.stackName(fixture.name).logicalResourceId("A"));
            }
        }
    }

    private static Map<String, Object> allow(String action, Object resource) {
        return statement("Allow", action, resource);
    }

    private static Map<String, Object> statement(String effect, String action, Object resource) {
        return Map.of("Effect", effect, "Action", action, "Resource", resource);
    }

    private static void denied(Runnable operation, String action) {
        CloudFormationException error = assertThrows(CloudFormationException.class, operation::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
        assertTrue(error.awsErrorDetails().errorMessage().contains(action), error.getMessage());
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class Caller implements AutoCloseable {
        private final String name = "drift-reader-" + UUID.randomUUID();
        private final IamClient iam = AwsTestClients.create(IamClient.builder(), ACCOUNT, Region.US_EAST_1);
        private final User user = iam.createUser(request -> request.userName(name)).user();
        private final AccessKey key = iam.createAccessKey(request -> request.userName(name)).accessKey();
        private final CloudFormationClient client = AwsTestClients.create(CloudFormationClient.builder(),
                key.accessKeyId(), key.secretAccessKey(), Region.US_EAST_1);

        private void policy(List<Map<String, Object>> statements) {
            iam.putUserPolicy(request -> request.userName(name).policyName("native-read")
                    .policyDocument(json(Map.of("Version", "2012-10-17", "Statement", new ArrayList<>(statements)))));
        }

        @Override
        public void close() {
            try {
                User current = iam.getUser(request -> request.userName(name)).user();
                assertEquals(user.userId(), current.userId());
                assertEquals(user.arn(), current.arn());
                iam.deleteUserPolicy(request -> request.userName(name).policyName("native-read"));
                iam.deleteAccessKey(request -> request.userName(name).accessKeyId(key.accessKeyId()));
                iam.deleteUser(request -> request.userName(name));
            } finally {
                client.close();
                iam.close();
            }
        }
    }
}
