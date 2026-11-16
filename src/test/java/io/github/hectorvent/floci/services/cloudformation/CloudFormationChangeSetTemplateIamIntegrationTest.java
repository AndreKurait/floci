package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.CreateChangeSetResponse;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CloudFormationChangeSetTemplateIamIntegrationTest {
    private static final Region REGION = Region.US_EAST_1;
    private static final String POLICY_NAME = "read-template";

    @TestHTTPResource
    URI endpoint;

    private CloudFormationClient owner;
    private IamClient iam;
    private final List<Executable> cleanup = new ArrayList<>();

    @BeforeEach
    void clients() {
        owner = cfn("test", "test");
        iam = IamClient.builder().endpointOverride(endpoint).region(REGION)
                .credentialsProvider(credentials("test", "test"))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    @AfterEach
    void cleanup() {
        try {
            assertAll(cleanup.reversed());
        } finally {
            owner.close();
            iam.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStackScopedGrantFollowsTheSelectedChangeSetAndPolicyUpdates(boolean arnOnly) {
        Preview first = preview("first");
        Preview second = preview("second");
        Caller caller = caller(allow(first.stackArn()));
        try (CloudFormationClient client = caller.client()) {
            assertEquals(first.body(), template(client, first, arnOnly));
            denied(client, second, arnOnly);

            iam.putUserPolicy(request -> request.userName(caller.name()).policyName(POLICY_NAME)
                    .policyDocument(allow(second.stackArn())));
            denied(client, first, arnOnly);
            assertEquals(second.body(), template(client, second, arnOnly));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExplicitStackDenyOverridesABroadAllowForEitherSelector(boolean arnOnly) {
        Preview denied = preview("denied");
        Preview allowed = preview("allowed");
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"cloudformation:GetTemplate","Resource":"*"},
                  {"Effect":"Deny","Action":"cloudformation:GetTemplate","Resource":"%s"}
                ]}
                """.formatted(denied.stackArn());
        Caller caller = caller(policy);
        try (CloudFormationClient client = caller.client()) {
            assertEquals(allowed.body(), template(client, allowed, arnOnly));
            denied(client, denied, arnOnly);
        }
    }

    private Preview preview(String label) {
        String stack = "template-iam-" + label + "-" + UUID.randomUUID();
        String body = """
                {"Resources":{"Parameter":{"Type":"AWS::SSM::Parameter",
                  "Properties":{"Name":"/template-iam/%s","Type":"String","Value":"%s"}}}}
                """.formatted(stack, label);
        CreateChangeSetResponse created = owner.createChangeSet(request -> request.stackName(stack)
                .changeSetName("preview").changeSetType("CREATE").templateBody(body));
        cleanup.add(() -> {
            owner.deleteStack(request -> request.stackName(stack));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                try {
                    owner.describeStacks(request -> request.stackName(stack));
                    return false;
                } catch (CloudFormationException error) {
                    assertEquals("ValidationError", error.awsErrorDetails().errorCode());
                    assertTrue(error.awsErrorDetails().errorMessage().contains("does not exist"));
                    return true;
                }
            });
        });
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals("CREATE_COMPLETE",
                owner.describeChangeSet(request -> request.changeSetName(created.id())).statusAsString()));
        assertTrue(created.stackId().contains(":stack/" + stack + "/"));
        assertTrue(created.id().contains(":changeSet/preview/"));
        return new Preview(stack, created.stackId(), created.id(), body);
    }

    private Caller caller(String policy) {
        String name = "template-reader-" + UUID.randomUUID();
        iam.createUser(request -> request.userName(name));
        cleanup.add(() -> {
            iam.deleteUser(request -> request.userName(name));
            assertThrows(NoSuchEntityException.class, () -> iam.getUser(request -> request.userName(name)));
        });
        AccessKey key = iam.createAccessKey(request -> request.userName(name)).accessKey();
        cleanup.add(() -> {
            iam.deleteAccessKey(request -> request.userName(name).accessKeyId(key.accessKeyId()));
            assertFalse(iam.listAccessKeys(request -> request.userName(name)).accessKeyMetadata().stream()
                    .anyMatch(item -> key.accessKeyId().equals(item.accessKeyId())));
        });
        iam.putUserPolicy(request -> request.userName(name).policyName(POLICY_NAME).policyDocument(policy));
        cleanup.add(() -> {
            iam.deleteUserPolicy(request -> request.userName(name).policyName(POLICY_NAME));
            assertFalse(iam.listUserPolicies(request -> request.userName(name)).policyNames().contains(POLICY_NAME));
        });
        return new Caller(name, cfn(key.accessKeyId(), key.secretAccessKey()));
    }

    private static String template(CloudFormationClient client, Preview preview, boolean arnOnly) {
        return client.getTemplate(request -> request.stackName(arnOnly ? null : preview.stack())
                .changeSetName(arnOnly ? preview.changeSetArn() : "preview")
                .templateStage("Original")).templateBody();
    }

    private static void denied(CloudFormationClient client, Preview preview, boolean arnOnly) {
        CloudFormationException error = assertThrows(CloudFormationException.class,
                () -> template(client, preview, arnOnly));
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
        assertTrue(error.awsErrorDetails().errorMessage().contains("cloudformation:GetTemplate"));
    }

    private static String allow(String resource) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"cloudformation:GetTemplate","Resource":"%s"}
                ]}
                """.formatted(resource);
    }

    private CloudFormationClient cfn(String key, String secret) {
        return CloudFormationClient.builder().endpointOverride(endpoint).region(REGION)
                .credentialsProvider(credentials(key, secret))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static StaticCredentialsProvider credentials(String key, String secret) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret));
    }

    private record Caller(String name, CloudFormationClient client) {}

    private record Preview(String stack, String stackArn, String changeSetArn, String body) {}
}
