package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.ApplicationAutoScalingException;
import software.amazon.awssdk.services.applicationautoscaling.model.RegisterScalableTargetRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTarget;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.AccessKeyMetadata;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(DynamoDbRegistrationIamIntegrationTest.Profile.class)
@Timeout(120)
class DynamoDbRegistrationIamIntegrationTest {
    private final Deque<Runnable> cleanup = new ArrayDeque<>();

    @AfterEach
    void removeEveryOwnedResourceAndAssertAbsence() {
        List<Executable> attempts = new ArrayList<>();
        while (!cleanup.isEmpty()) {
            Runnable attempt = cleanup.pop();
            attempts.add(attempt::run);
        }
        assertAll("Every reserved resource must be removed independently", attempts);
    }

    public static class Profile extends IamEnforcementProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> values = new HashMap<>(super.getConfigOverrides());
            values.put("floci.default-region", "cn-north-1");
            values.put("floci.partitions.allow-unknown-regions", "true");
            return values;
        }
    }

    enum Target {
        TABLE_READ(false, true), TABLE_WRITE(false, false), INDEX_READ(true, true), INDEX_WRITE(true, false);
        final boolean index;
        final boolean read;
        Target(boolean index, boolean read) {
            this.index = index;
            this.read = read;
        }
        String dimension() {
            return "dynamodb:" + (index ? "index:" : "table:") + (read ? "ReadCapacityUnits" : "WriteCapacityUnits");
        }
        String resource(String table) {
            return "table/" + table + (index ? "/index/selected" : "");
        }
        long capacity(TableDescription table) {
            ProvisionedThroughput throughput = index
                    ? ProvisionedThroughput.builder()
                            .readCapacityUnits(table.globalSecondaryIndexes().getFirst().provisionedThroughput().readCapacityUnits())
                            .writeCapacityUnits(table.globalSecondaryIndexes().getFirst().provisionedThroughput().writeCapacityUnits()).build()
                    : ProvisionedThroughput.builder().readCapacityUnits(table.provisionedThroughput().readCapacityUnits())
                            .writeCapacityUnits(table.provisionedThroughput().writeCapacityUnits()).build();
            return read ? throughput.readCapacityUnits() : throughput.writeCapacityUnits();
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void registrationUsesStoredTargetPermissionWithoutGrantingDynamoDbUpdate(Target target) {
        exercise(target, Region.of("cn-north-1"));
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void unknownRegionNewTargetUsesTheDeploymentPartition(Target target) {
        exercise(target, Region.of("ddb-local-region-1"));
    }

    private void exercise(Target target, Region region) {
        String account = "760000000001";
        String otherAccount = "760000000002";
        String marker = "ddb-iam-" + UUID.randomUUID();
        String otherTable = marker + "-other";
        Region otherRegion = Region.of("cn-northwest-1");
        String userName = marker + "-user";
        String roleName = marker + "-role";
        Credentials credentials = createUser(account, userName);
        try (DynamoDbClient tables = AwsTestClients.create(bounded(DynamoDbClient.builder()), account, region);
             DynamoDbClient otherOwner = AwsTestClients.create(bounded(DynamoDbClient.builder()), otherAccount, region);
             DynamoDbClient otherPlace = AwsTestClients.create(bounded(DynamoDbClient.builder()), account, otherRegion);
             ApplicationAutoScalingClient root = AwsTestClients.create(bounded(ApplicationAutoScalingClient.builder()), account, region);
             ApplicationAutoScalingClient user = AwsTestClients.create(bounded(ApplicationAutoScalingClient.builder()),
                     credentials.accessKeyId(), credentials.secretAccessKey(), region);
             IamClient iam = AwsTestClients.create(bounded(IamClient.builder()), account, Region.of("cn-north-1"))) {
            reserveTable(account, region, marker);
            reserveTable(account, region, otherTable);
            reserveTable(otherAccount, region, marker);
            reserveTable(account, otherRegion, marker);
            create(tables, marker);
            create(tables, otherTable);
            create(otherOwner, marker);
            create(otherPlace, marker);
            cleanup.push(() -> {
                try (IamClient closer = AwsTestClients.create(bounded(IamClient.builder()), account, Region.of("cn-north-1"))) {
                    try {
                        closer.deleteRole(b -> b.roleName(roleName));
                    } catch (NoSuchEntityException expected) {
                        // The owned role may not have been created if setup failed.
                    }
                    assertThrows(NoSuchEntityException.class, () -> closer.getRole(b -> b.roleName(roleName)));
                }
            });
            String roleArn = iam.createRole(b -> b.roleName(roleName).assumeRolePolicyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Principal":{"Service":"dynamodb.application-autoscaling.amazonaws.com"},
                    "Action":"sts:AssumeRole"}]}
                    """)).role().arn();
            for (String name : List.of(marker, otherTable)) {
                for (Target reserved : Target.values()) {
                    reserveTarget(account, region, reserved, name);
                }
            }
            String wildcard = "arn:aws-cn:application-autoscaling:" + region.id() + ":" + account + ":scalable-target/*";
            putPolicy(account, userName, wildcard.replace("arn:aws-cn:", "arn:aws:"), null, false);
            Snapshot before = snapshot(tables, otherOwner, otherPlace, root, marker, otherTable);
            deny(user, request -> request.serviceNamespace("dynamodb").resourceId(target.resource(marker))
                    .scalableDimension(target.dimension()).minCapacity(7).maxCapacity(12));
            assertEquals(before, snapshot(tables, otherOwner, otherPlace, root, marker, otherTable));

            putPolicy(account, userName, wildcard, null, false);
            for (int maximum : List.of(5, 0)) {
                rejectZero(user, b -> b.serviceNamespace("dynamodb").resourceId(target.resource(marker))
                        .scalableDimension(target.dimension()).minCapacity(0).maxCapacity(maximum));
                assertEquals(before, snapshot(tables, otherOwner, otherPlace, root, marker, otherTable));
            }
            String targetArn = user.registerScalableTarget(b -> b.serviceNamespace("dynamodb")
                    .resourceId(target.resource(marker)).scalableDimension(target.dimension())
                    .minCapacity(7).maxCapacity(12)).scalableTargetARN();
            assertTrue(targetArn.startsWith(wildcard.substring(0, wildcard.length() - 1)));
            assertEquals(7, target.capacity(tables.describeTable(b -> b.tableName(marker)).table()));
            assertEquals(before.tables().get("otherTable"), tables.describeTable(b -> b.tableName(otherTable)).table());
            assertEquals(before.tables().get("otherAccount"), otherOwner.describeTable(b -> b.tableName(marker)).table());
            assertEquals(before.tables().get("otherRegion"), otherPlace.describeTable(b -> b.tableName(marker)).table());

            putPolicy(account, userName, targetArn, null, false);
            Snapshot registered = snapshot(tables, otherOwner, otherPlace, root, marker, otherTable);
            rejectZero(user, b -> b.serviceNamespace("dynamodb").resourceId(target.resource(marker))
                    .scalableDimension(target.dimension()).minCapacity(0));
            assertEquals(registered, snapshot(tables, otherOwner, otherPlace, root, marker, otherTable));
            user.registerScalableTarget(b -> b.serviceNamespace("dynamodb").resourceId(target.resource(marker))
                    .scalableDimension(target.dimension()).minCapacity(9));
            assertEquals(9, target.capacity(tables.describeTable(b -> b.tableName(marker)).table()));
            Snapshot restricted = snapshot(tables, otherOwner, otherPlace, root, marker, otherTable);
            deny(user, b -> b.serviceNamespace("dynamodb").resourceId(target.resource(otherTable))
                    .scalableDimension(target.dimension()).minCapacity(7).maxCapacity(12));
            Target opposite = Target.values()[target.ordinal() ^ 1];
            deny(user, b -> b.serviceNamespace("dynamodb").resourceId(opposite.resource(marker))
                    .scalableDimension(opposite.dimension()).minCapacity(7).maxCapacity(12));
            deny(user, b -> b.serviceNamespace("dynamodb").resourceId(target.resource(marker))
                    .scalableDimension(target.dimension()).minCapacity(10).roleARN(roleArn));
            assertEquals(restricted, snapshot(tables, otherOwner, otherPlace, root, marker, otherTable));

            putPolicy(account, userName, targetArn, roleArn, false);
            user.registerScalableTarget(b -> b.serviceNamespace("dynamodb").resourceId(target.resource(marker))
                    .scalableDimension(target.dimension()).minCapacity(10).roleARN(roleArn));
            assertEquals(10, target.capacity(tables.describeTable(b -> b.tableName(marker)).table()));
            assertEquals(roleArn, root.describeScalableTargets(b -> b.serviceNamespace("dynamodb")
                    .resourceIds(target.resource(marker)).scalableDimension(target.dimension()))
                    .scalableTargets().getFirst().roleARN());
            Snapshot beforeTags = snapshot(tables, otherOwner, otherPlace, root, marker, otherTable);
            deny(user, b -> b.serviceNamespace("dynamodb").resourceId(target.resource(marker))
                    .scalableDimension(target.dimension()).minCapacity(11).tags(Map.of("change", "allowed")));
            assertEquals(beforeTags, snapshot(tables, otherOwner, otherPlace, root, marker, otherTable));
            assertTrue(root.listTagsForResource(b -> b.resourceARN(targetArn)).tags().isEmpty());
            putPolicy(account, userName, targetArn, roleArn, true);
            user.registerScalableTarget(b -> b.serviceNamespace("dynamodb").resourceId(target.resource(marker))
                    .scalableDimension(target.dimension()).minCapacity(11).tags(Map.of("change", "allowed")));
            assertEquals(11, target.capacity(tables.describeTable(b -> b.tableName(marker)).table()));
            assertEquals(Map.of("change", "allowed"), root.listTagsForResource(b -> b.resourceARN(targetArn)).tags());
        }
    }

    private static void putPolicy(String account, String user, String resource, String role, boolean tags) {
        String actions = tags
                ? "[\"application-autoscaling:RegisterScalableTarget\",\"application-autoscaling:TagResource\"]"
                : "\"application-autoscaling:RegisterScalableTarget\"";
        String passRole = role == null ? "" : """
                ,{"Effect":"Allow","Action":"iam:PassRole","Resource":"%s"}
                """.formatted(role);
        try (IamClient iam = AwsTestClients.create(bounded(IamClient.builder()), account, Region.of("cn-north-1"))) {
            iam.putUserPolicy(b -> b.userName(user).policyName("registration").policyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":%s,"Resource":"%s"}%s]}
                    """.formatted(actions, resource, passRole)));
        }
    }

    record Credentials(String accessKeyId, String secretAccessKey) {}

    private static <B extends AwsClientBuilder<B, C> & AwsSyncClientBuilder<B, C>, C> B bounded(B builder) {
        return builder.overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))
                .apiCallAttemptTimeout(Duration.ofSeconds(8)).retryPolicy(RetryPolicy.none()));
    }

    private Credentials createUser(String account, String userName) {
        cleanup.push(() -> {
            try (IamClient iam = AwsTestClients.create(bounded(IamClient.builder()), account, Region.of("cn-north-1"))) {
                try {
                    iam.getUser(b -> b.userName(userName));
                } catch (NoSuchEntityException expected) {
                    return;
                }
                List<Executable> owned = new ArrayList<>();
                owned.add(() -> {
                    for (String policy : iam.listUserPolicies(b -> b.userName(userName)).policyNames()) {
                        iam.deleteUserPolicy(b -> b.userName(userName).policyName(policy));
                    }
                });
                owned.add(() -> {
                    for (AccessKeyMetadata key : iam.listAccessKeys(b -> b.userName(userName)).accessKeyMetadata()) {
                        iam.deleteAccessKey(b -> b.userName(userName).accessKeyId(key.accessKeyId()));
                    }
                });
                owned.add(() -> iam.deleteUser(b -> b.userName(userName)));
                owned.add(() -> assertThrows(NoSuchEntityException.class, () -> iam.getUser(b -> b.userName(userName))));
                assertAll("Owned user, credentials and inline policy cleanup", owned);
            }
        });
        try (IamClient iam = AwsTestClients.create(bounded(IamClient.builder()), account, Region.of("cn-north-1"))) {
            iam.createUser(b -> b.userName(userName));
            AccessKey key = iam.createAccessKey(b -> b.userName(userName)).accessKey();
            return new Credentials(key.accessKeyId(), key.secretAccessKey());
        }
    }

    private static void deny(ApplicationAutoScalingClient user, Consumer<RegisterScalableTargetRequest.Builder> request) {
        ApplicationAutoScalingException error = assertThrows(ApplicationAutoScalingException.class,
                () -> user.registerScalableTarget(request));
        // Preserve the JSON1.1 envelope; the commercial table read/write denial oracle returned 400.
        assertEquals(400, error.statusCode());
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }

    private static void rejectZero(ApplicationAutoScalingClient user, Consumer<RegisterScalableTargetRequest.Builder> request) {
        ApplicationAutoScalingException error = assertThrows(ApplicationAutoScalingException.class,
                () -> user.registerScalableTarget(request));
        assertEquals(400, error.statusCode());
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
        assertEquals("Minimum capacity cannot be less than 1", error.awsErrorDetails().errorMessage());
    }

    record Snapshot(Map<String, TableDescription> tables, Map<String, ScalableTarget> targets) {}

    private static Snapshot snapshot(DynamoDbClient tables, DynamoDbClient otherOwner, DynamoDbClient otherPlace,
                                     ApplicationAutoScalingClient scaling, String table, String otherTable) {
        return new Snapshot(Map.of(
                "selected", tables.describeTable(b -> b.tableName(table)).table(),
                "otherTable", tables.describeTable(b -> b.tableName(otherTable)).table(),
                "otherAccount", otherOwner.describeTable(b -> b.tableName(table)).table(),
                "otherRegion", otherPlace.describeTable(b -> b.tableName(table)).table()),
                scaling.describeScalableTargets(b -> b.serviceNamespace("dynamodb").resourceIds(
                        "table/" + table, "table/" + table + "/index/selected",
                        "table/" + otherTable, "table/" + otherTable + "/index/selected"))
                        .scalableTargets().stream().collect(Collectors.toMap(ScalableTarget::scalableTargetARN, value -> value)));
    }

    private static void create(DynamoDbClient client, String name) {
        KeySchemaElement key = KeySchemaElement.builder().attributeName("pk").keyType("HASH").build();
        ProvisionedThroughput throughput = ProvisionedThroughput.builder().readCapacityUnits(3L).writeCapacityUnits(3L).build();
        client.createTable(b -> b.tableName(name).billingMode("PROVISIONED").keySchema(key)
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType("S").build())
                .provisionedThroughput(throughput).globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("selected").keySchema(key).projection(p -> p.projectionType("ALL"))
                        .provisionedThroughput(throughput).build()));
    }

    private void reserveTarget(String account, Region region, Target target, String name) {
        cleanup.push(() -> {
            try (ApplicationAutoScalingClient client = AwsTestClients.create(bounded(ApplicationAutoScalingClient.builder()), account, region)) {
                try {
                    client.deregisterScalableTarget(b -> b.serviceNamespace("dynamodb")
                            .resourceId(target.resource(name)).scalableDimension(target.dimension()));
                } catch (ApplicationAutoScalingException error) {
                    if (!"ObjectNotFoundException".equals(error.awsErrorDetails().errorCode())) {
                        throw error;
                    }
                }
                assertTrue(client.describeScalableTargets(b -> b.serviceNamespace("dynamodb")
                        .resourceIds(target.resource(name)).scalableDimension(target.dimension())).scalableTargets().isEmpty());
            }
        });
    }

    private void reserveTable(String account, Region region, String name) {
        cleanup.push(() -> {
            try (DynamoDbClient client = AwsTestClients.create(bounded(DynamoDbClient.builder()), account, region)) {
                try {
                    client.deleteTable(b -> b.tableName(name));
                } catch (ResourceNotFoundException absent) {
                    return;
                }
                for (int attempt = 0; attempt < 80; attempt++) {
                    try {
                        client.describeTable(b -> b.tableName(name));
                    } catch (ResourceNotFoundException absent) {
                        return;
                    }
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted during owned table absence polling", error);
                    }
                }
                fail("Owned table did not reach absence");
            }
        });
    }
}
