package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.GetStackOutputRoleAuthorizer.ExecutionIdentity;
import io.github.hectorvent.floci.services.cloudformation.provisioners.S3CfnProvisioner;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CloudFormationExecutionIdentityIntegrationTest {
    @InjectSpy
    S3CfnProvisioner buckets;

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void sdkSignedCallerReachesActualAsyncProviderAndForgedSignatureDoesNot() throws Exception {
        CopyOnWriteArrayList<ExecutionIdentity> observed = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<ExecutionIdentity> deleted = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            observed.add(CloudFormationExecutionScope.current().orElseThrow());
            return invocation.callRealMethod();
        }).when(buckets).provision(any(), any(), any());
        doAnswer(invocation -> {
            deleted.add(CloudFormationExecutionScope.current().orElseThrow());
            return invocation.callRealMethod();
        }).when(buckets).delete(anyString(), anyString(), anyString());
        String stack = "verified-operation-" + UUID.randomUUID();
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":[\"cloudformation:*\",\"s3:*\"],\"Resource\":\"*\"}]}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "cfn-verified-", "stack", policy);
             SignedIamUsers.User deleter = SignedIamUsers.createUser(endpoint, "cfn-delete-", "stack", policy);
             CloudFormationClient client = client(user.accessKeyId(), user.secretAccessKey());
             CloudFormationClient deleteClient = client(deleter.accessKeyId(), deleter.secretAccessKey());
             CloudFormationClient forged = client(user.accessKeyId(), "wrong-secret")) {
            String template = "{\"Resources\":{\"Bucket\":{\"Type\":\"AWS::S3::Bucket\"}}}";
            assertThrows(CloudFormationException.class, () ->
                    forged.createStack(r -> r.stackName(stack + "-forged").templateBody(template)));
            assertTrue(observed.isEmpty());
            client.createStack(r -> r.stackName(stack).templateBody(template));
            client.waiter().waitUntilStackCreateComplete(r -> r.stackName(stack),
                            configuration -> configuration.waitTimeout(Duration.ofSeconds(60))
                                    .backoffStrategyV2(BackoffStrategy.fixedDelayWithoutJitter(Duration.ofMillis(100))))
                    .matched().response().orElseThrow();
            assertEquals(1, observed.size());
            ExecutionIdentity identity = observed.getFirst();
            assertTrue(identity.signatureVerified());
            assertTrue(identity.caller().principalArn().endsWith(":user/" + user.name()));
            assertNotNull(identity.policies());
            assertTrue(CloudFormationExecutionScope.current().isEmpty());
            deleteClient.deleteStack(r -> r.stackName(stack));
            deleteClient.waiter().waitUntilStackDeleteComplete(r -> r.stackName(stack),
                    configuration -> configuration.waitTimeout(Duration.ofSeconds(60))
                            .backoffStrategyV2(BackoffStrategy.fixedDelayWithoutJitter(Duration.ofMillis(100))));
            assertEquals(1, deleted.size());
            assertTrue(deleted.getFirst().signatureVerified());
            assertTrue(deleted.getFirst().caller().principalArn().endsWith(":user/" + deleter.name()));
            assertNotEquals(identity.caller(), deleted.getFirst().caller());
        }
    }

    private CloudFormationClient client(String key, String secret) {
        return CloudFormationClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                .build();
    }
}
