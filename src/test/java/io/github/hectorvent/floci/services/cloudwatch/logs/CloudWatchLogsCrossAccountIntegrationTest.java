package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.AccountPolicy;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeDestinationsResponse;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.KinesisException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudWatchLogsCrossAccountIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260904/us-east-1/logs/aws4_request";

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Inject
    StorageFactory storageFactory;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void destinationAndAccountPolicyLifecycle() {
        try (Owned owned = new Owned()) {
            owned.prepareDestination("cross-account");
            owned.prepareAccountPolicy("central-subscription", "SUBSCRIPTION_FILTER_POLICY");
            json("Logs_20140328.PutDestination",
                    """
                    {"destinationName":"cross-account","targetArn":"%s","roleArn":"%s"}
                    """.formatted(owned.targetArn, owned.roleArn))
                    .then().statusCode(200).body("destination.destinationName", equalTo("cross-account"));
            json("Logs_20140328.PutDestinationPolicy",
                    "{\"destinationName\":\"cross-account\",\"accessPolicy\":\"{}\"}")
                    .then().statusCode(200);
            json("Logs_20140328.PutAccountPolicy",
                    "{\"policyName\":\"central-subscription\",\"policyDocument\":\"{}\",\"policyType\":\"SUBSCRIPTION_FILTER_POLICY\",\"scope\":\"ALL\"}")
                    .then().statusCode(200).body("accountPolicy.policyName", equalTo("central-subscription"));
            json("Logs_20140328.DescribeAccountPolicies",
                    "{\"policyType\":\"SUBSCRIPTION_FILTER_POLICY\"}")
                    .then().statusCode(200).body("accountPolicies", hasSize(1));
        }
    }

    @Test
    void transformerPolicyAcceptsProcessorArrayDocument() {
        try (Owned owned = new Owned()) {
            owned.prepareAccountPolicy("account-transformer", "TRANSFORMER_POLICY");
            json("Logs_20140328.PutAccountPolicy",
                    "{\"policyName\":\"account-transformer\",\"policyDocument\":\"[{\\\"parseJSON\\\":{}}]\","
                            + "\"policyType\":\"TRANSFORMER_POLICY\",\"scope\":\"ALL\"}")
                    .then().statusCode(200)
                    .body("accountPolicy.policyType", equalTo("TRANSFORMER_POLICY"))
                    .body("accountPolicy.policyDocument", equalTo("[{\"parseJSON\":{}}]"));
        }
    }

    @Test
    void destinationPolicyRequiresExistingDestination() {
        try (Owned owned = new Owned()) {
            owned.assertDestinationAbsent("missing");
            json("Logs_20140328.PutDestinationPolicy",
                    "{\"destinationName\":\"missing\",\"accessPolicy\":\"{}\"}")
                    .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
        }
    }


    private final class Owned implements AutoCloseable {
        private final String marker = "logs-cross-account-" + UUID.randomUUID();
        private final String targetArn = "arn:aws:kinesis:" + REGION + ":" + ACCOUNT + ":stream/" + marker;
        private final String roleArn = "arn:aws:iam::" + ACCOUNT + ":role/" + marker;
        private final CloudWatchLogsClient logs = client(CloudWatchLogsClient.builder());
        private final KinesisClient kinesis = client(KinesisClient.builder());
        private final IamClient iam = client(IamClient.builder());
        private final Set<String> destinations = new LinkedHashSet<>();
        private final Map<String, String> policies = new LinkedHashMap<>();
        private boolean roleAttempted;
        private boolean rolePolicyAttempted;
        private boolean streamAttempted;

        private void prepareDestination(String name) {
            assertDestinationAbsent(name);
            destinations.add(name);
            if (!roleAttempted) {
                missingRole(assertThrows(IamException.class,
                        () -> iam.getRole(request -> request.roleName(marker))));
                roleAttempted = true;
                iam.createRole(request -> request.roleName(marker).assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"Service":"logs.amazonaws.com"},"Action":"sts:AssumeRole",
                        "Condition":{"StringEquals":{"aws:SourceAccount":"%s"},
                        "ArnLike":{"aws:SourceArn":"arn:aws:logs:%s:%s:*"}}}]}
                        """.formatted(ACCOUNT, REGION, ACCOUNT)));
                rolePolicyAttempted = true;
                iam.putRolePolicy(request -> request.roleName(marker).policyName("delivery")
                        .policyDocument("""
                                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                                "Action":"kinesis:PutRecord","Resource":"%s"}]}
                                """.formatted(targetArn)));
                missingStream(assertThrows(KinesisException.class,
                        () -> kinesis.describeStream(request -> request.streamName(marker))));
                streamAttempted = true;
                kinesis.createStream(request -> request.streamName(marker).shardCount(1));
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("ACTIVE",
                        kinesis.describeStream(request -> request.streamName(marker))
                                .streamDescription().streamStatusAsString()));
            }
        }

        private void prepareAccountPolicy(String name, String type) {
            missingLogs(assertThrows(CloudWatchLogsException.class, () -> logs.describeAccountPolicies(
                    request -> request.policyName(name).policyType(type))));
            policies.put(name, type);
        }

        private void assertDestinationAbsent(String name) {
            Set<String> tokens = new LinkedHashSet<>();
            String token = null;
            do {
                String next = token;
                DescribeDestinationsResponse page = logs.describeDestinations(request ->
                        request.destinationNamePrefix(name).limit(50).nextToken(next));
                assertTrue(page.destinations().stream().noneMatch(value -> name.equals(value.destinationName())));
                token = page.nextToken();
                assertTrue(token == null || tokens.add(token), "destination inventory token must advance");
                assertTrue(tokens.size() < 20, "owned destination inventory exceeded its bound");
            } while (token != null);
        }

        @Override
        public void close() {
            IllegalStateException failures = new IllegalStateException("Owned cross-account fixture cleanup failed");
            for (String name : destinations) {
                cleanup(() -> {
                    try {
                        logs.deleteDestination(request -> request.destinationName(name));
                    } catch (CloudWatchLogsException expected) {
                        missingLogs(expected);
                    }
                    assertDestinationAbsent(name);
                }, failures);
            }
            for (Map.Entry<String, String> policy : policies.entrySet()) {
                cleanup(() -> {
                    // DeleteAccountPolicy is not implemented; remove only this case's pre-proven absent key.
                    StorageBackend<String, AccountPolicy> backend = storageFactory.create("cloudwatchlogs",
                            "cwlogs-account-policies.json", new TypeReference<Map<String, AccountPolicy>>() {});
                    RequestScopes.runAs(ACCOUNT, REGION, () -> backend.delete(
                            REGION + "::" + policy.getValue() + "::" + policy.getKey()));
                    missingLogs(assertThrows(CloudWatchLogsException.class, () -> logs.describeAccountPolicies(
                            request -> request.policyName(policy.getKey()).policyType(policy.getValue()))));
                }, failures);
            }
            if (streamAttempted) {
                cleanup(() -> {
                    try {
                        kinesis.deleteStream(request -> request.streamName(marker));
                    } catch (KinesisException expected) {
                        missingStream(expected);
                    }
                    missingStream(assertThrows(KinesisException.class,
                            () -> kinesis.describeStream(request -> request.streamName(marker))));
                }, failures);
            }
            if (rolePolicyAttempted) {
                cleanup(() -> {
                    try {
                        iam.deleteRolePolicy(request -> request.roleName(marker).policyName("delivery"));
                    } catch (IamException expected) {
                        missingRole(expected);
                    }
                    missingRole(assertThrows(IamException.class,
                            () -> iam.getRolePolicy(request -> request.roleName(marker).policyName("delivery"))));
                }, failures);
            }
            if (roleAttempted) {
                cleanup(() -> {
                    try {
                        iam.deleteRole(request -> request.roleName(marker));
                    } catch (IamException expected) {
                        missingRole(expected);
                    }
                    missingRole(assertThrows(IamException.class,
                            () -> iam.getRole(request -> request.roleName(marker))));
                }, failures);
            }
            cleanup(logs::close, failures);
            cleanup(kinesis::close, failures);
            cleanup(iam::close, failures);
            if (failures.getSuppressed().length > 0) {
                throw failures;
            }
        }
    }

    private static <B extends AwsClientBuilder<B, T> & AwsSyncClientBuilder<B, T>, T> T client(B builder) {
        builder.overrideConfiguration(config -> config.retryPolicy(RetryPolicy.none())
                .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10)));
        return AwsTestClients.create(builder, "AKID", Region.US_EAST_1);
    }

    private static void missingLogs(CloudWatchLogsException expected) {
        assertEquals(400, expected.statusCode());
        assertEquals("ResourceNotFoundException", expected.awsErrorDetails().errorCode());
    }

    private static void missingRole(IamException expected) {
        assertEquals(404, expected.statusCode());
        assertEquals("NoSuchEntity", expected.awsErrorDetails().errorCode());
    }

    private static void missingStream(KinesisException expected) {
        assertEquals(400, expected.statusCode());
        assertEquals("ResourceNotFoundException", expected.awsErrorDetails().errorCode());
    }

    private static void cleanup(Runnable action, IllegalStateException failures) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (failure != failures) {
                failures.addSuppressed(failure);
            }
        }
    }

    private static Response json(String target, String body) {
        return given().contentType(CONTENT_TYPE).header("Authorization", AUTH).header("X-Amz-Target", target)
                .body(body).post("/");
    }
}
