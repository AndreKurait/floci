package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.AthenaException;
import software.amazon.awssdk.services.athena.model.QueryExecution;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AthenaBatchQueryIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();


    @Test
    void authorizationCannotReadOrMigrateAnotherAccountsPrefixedQueryIdentifier() {
        String foreignAccount = "742449000001";
        try (AthenaClient foreign = AwsTestClients.create(AthenaClient.builder(), foreignAccount, Region.US_EAST_1);
             AthenaClient caller = caller(statement("Allow", "athena:BatchGetQueryExecution", "*"))) {
            String id = foreign.startQueryExecution(request -> request.queryString("SELECT 37")).queryExecutionId();
            QueryExecution before = foreign.getQueryExecution(request -> request.queryExecutionId(id)).queryExecution();
            String alias = foreignAccount + "/" + id;
            assertTrue(caller.batchGetQueryExecution(request -> request.queryExecutionIds(alias)).queryExecutions().isEmpty());
            assertEquals(alias, caller.batchGetQueryExecution(request -> request.queryExecutionIds(alias))
                    .unprocessedQueryExecutionIds().getFirst().queryExecutionId());
            assertEquals(before, foreign.getQueryExecution(request -> request.queryExecutionId(id)).queryExecution());
            assertEquals(List.of(before), foreign.batchGetQueryExecution(
                    request -> request.queryExecutionIds(id)).queryExecutions());
        }
    }

    @Test
    void exactBatchActionOnTheStoredWorkGroupAllowsReadsAndRefusesSiblingTargetsAndOtherActions() {
        try (Fixture fixture = new Fixture();
             AthenaClient caller = caller(statement("Allow", "athena:BatchGetQueryExecution", arn(fixture.firstGroup)))) {
            assertEquals(List.of(fixture.first), caller.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.firstId)).queryExecutions());
            denied(() -> caller.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.secondId)));
            denied(() -> caller.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.firstId, fixture.secondId)));
            denied(() -> caller.getQueryExecution(r -> r.queryExecutionId(fixture.firstId)));
            denied(() -> caller.stopQueryExecution(r -> r.queryExecutionId(fixture.firstId)));
            assertEquals(fixture.first, fixture.admin.getQueryExecution(r -> r.queryExecutionId(fixture.firstId)).queryExecution());
        }
    }

    @Test
    void eachWorkGroupUsesItsOwnTagsAndAnExplicitDenyRefusesTheWholeMixedRequest() {
        try (Fixture fixture = new Fixture()) {
            fixture.tag(fixture.firstGroup, "red");
            fixture.tag(fixture.secondGroup, "blue");
            String allows = conditional("Allow", arn(fixture.firstGroup), "Owner", "red") + ","
                    + conditional("Allow", arn(fixture.secondGroup), "Owner", "blue");
            try (AthenaClient caller = caller(allows)) {
                assertEquals(List.of(fixture.first, fixture.second), caller.batchGetQueryExecution(r ->
                        r.queryExecutionIds(fixture.firstId, fixture.secondId)).queryExecutions());
            }
            String denied = allows + "," + conditional("Deny", arn(fixture.secondGroup), "Owner", "blue");
            try (AthenaClient caller = caller(denied)) {
                denied(() -> caller.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.firstId, fixture.secondId)));
                assertEquals(List.of(fixture.first), caller.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.firstId)).queryExecutions());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"allowed", "", " "})
    void liveEmptyAndWhitespaceTagsArePresentAndRemovedTagsCannotAuthorize(String marker) {
        try (Fixture fixture = new Fixture()) {
            fixture.tag(fixture.firstGroup, marker);
            fixture.tag(fixture.secondGroup, "other");
            try (AthenaClient caller = caller(conditional("Allow", "*", "Owner", marker))) {
                assertEquals(List.of(fixture.first), caller.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.firstId)).queryExecutions());
                denied(() -> caller.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.secondId)));
                fixture.admin.untagResource(r -> r.resourceARN(arn(fixture.firstGroup)).tagKeys("Owner"));
                denied(() -> caller.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.firstId)));
            }
        }
    }

    @Test
    void flociAllMissingPolicyRequiresWildcardResourceWhileMixedMissingChecksEachKnownWorkGroup() {
        try (Fixture fixture = new Fixture()) {
            String missing = UUID.randomUUID().toString();
            try (AthenaClient scoped = caller(statement("Allow", "athena:BatchGetQueryExecution", arn(fixture.firstGroup)))) {
                assertEquals(List.of(fixture.first), scoped.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.firstId, missing))
                        .queryExecutions());
                assertEquals("QUERY_EXECUTION_NOT_FOUND", scoped.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.firstId, missing))
                        .unprocessedQueryExecutionIds().getFirst().errorCode());
                denied(() -> scoped.batchGetQueryExecution(r -> r.queryExecutionIds(missing)));
                denied(() -> scoped.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.secondId, missing)));
            }
            try (AthenaClient wildcard = caller(statement("Allow", "athena:BatchGetQueryExecution", "*"))) {
                assertTrue(wildcard.batchGetQueryExecution(r -> r.queryExecutionIds(missing)).queryExecutions().isEmpty());
                assertEquals(missing, wildcard.batchGetQueryExecution(r -> r.queryExecutionIds(missing))
                        .unprocessedQueryExecutionIds().getFirst().queryExecutionId());
            }
        }
    }

    @Test
    void explicitDenyOverridesWildcardAndWrongAccountRegionOrPartitionArnsDoNotGrantAccess() {
        try (Fixture fixture = new Fixture()) {
            try (AthenaClient caller = caller(statement("Allow", "athena:*", "*") + ","
                    + statement("Deny", "athena:BatchGetQueryExecution", arn(fixture.firstGroup)))) {
                denied(() -> caller.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.firstId)));
            }
            for (String wrong : List.of(arn(fixture.firstGroup).replace("000000000000", "222222222222"),
                    arn(fixture.firstGroup).replace("us-east-1", "us-west-2"),
                    arn(fixture.firstGroup).replace("arn:aws:", "arn:aws-cn:"))) {
                try (AthenaClient caller = caller(statement("Allow", "athena:BatchGetQueryExecution", wrong))) {
                    denied(() -> caller.batchGetQueryExecution(r -> r.queryExecutionIds(fixture.firstId)));
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void storedWorkGroupAuthorizationArnUsesEachOfferedNoncommercialPartition(PartitionCase partitionCase) {
        assumeTrue(!AwsPartitions.publishesSomewhere("athena")
                || AwsPartitions.byId(partitionCase.partition()).offersSigningName("athena"));
        Region region = Region.of(partitionCase.region());
        String group = "batch-partition-" + UUID.randomUUID();
        String resource = "arn:" + partitionCase.partition() + ":athena:" + partitionCase.region()
                + ":000000000000:workgroup/" + group;
        try (AthenaClient admin = AwsTestClients.create(AthenaClient.builder(), "test", region)) {
            admin.createWorkGroup(r -> r.name(group));
            try {
                String id = admin.startQueryExecution(r -> r.queryString("SELECT 1").workGroup(group)).queryExecutionId();
                try (AthenaClient caller = caller(statement("Allow", "athena:BatchGetQueryExecution", resource), region)) {
                    assertEquals(id, caller.batchGetQueryExecution(r -> r.queryExecutionIds(id))
                            .queryExecutions().getFirst().queryExecutionId());
                }
                try (AthenaClient caller = caller(statement("Allow", "athena:BatchGetQueryExecution",
                        resource.replace("arn:" + partitionCase.partition() + ":", "arn:aws:")), region)) {
                    denied(() -> caller.batchGetQueryExecution(r -> r.queryExecutionIds(id)));
                }
            } finally {
                admin.deleteWorkGroup(r -> r.workGroup(group).recursiveDeleteOption(true));
            }
        }
    }

    private AthenaClient caller(String statements) {
        return caller(statements, Region.US_EAST_1);
    }

    private AthenaClient caller(String statements, Region region) {
        String iamAuth = auth("test", "iam").replace("/us-east-1/", "/" + region.id() + "/");
        String user = "batch-query-caller-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), iamAuth, user, "batch-query",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        return AwsTestClients.create(AthenaClient.builder(), credentials.accessKeyId(), credentials.secretAccessKey(), region);
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String conditional(String effect, String resource, String key, String value) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"athena:BatchGetQueryExecution\",\"Resource\":\"" + resource
                + "\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/" + key + "\":\"" + value + "\"}}}";
    }

    private static String arn(String group) {
        return "arn:aws:athena:us-east-1:000000000000:workgroup/" + group;
    }

    private static void denied(Runnable action) {
        AthenaException error = assertThrows(AthenaException.class, action::run);
        assertEquals(400, error.statusCode());
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }

    private static final class Fixture implements AutoCloseable {
        final AthenaClient admin = AwsTestClients.create(AthenaClient.builder(), "test", Region.US_EAST_1);
        final String firstGroup = "batch-query-" + UUID.randomUUID();
        final String secondGroup = firstGroup + "-other";
        final String firstId;
        final String secondId;
        final QueryExecution first;
        final QueryExecution second;

        Fixture() {
            admin.createWorkGroup(r -> r.name(firstGroup));
            admin.createWorkGroup(r -> r.name(secondGroup));
            firstId = admin.startQueryExecution(r -> r.queryString("SELECT 11").workGroup(firstGroup)).queryExecutionId();
            secondId = admin.startQueryExecution(r -> r.queryString("SELECT 29").workGroup(secondGroup)).queryExecutionId();
            first = admin.getQueryExecution(r -> r.queryExecutionId(firstId)).queryExecution();
            second = admin.getQueryExecution(r -> r.queryExecutionId(secondId)).queryExecution();
        }

        void tag(String group, String value) {
            admin.tagResource(r -> r.resourceARN(arn(group)).tags(t -> t.key("Owner").value(value)));
        }

        @Override
        public void close() {
            try {
                admin.deleteWorkGroup(r -> r.workGroup(firstGroup).recursiveDeleteOption(true));
                admin.deleteWorkGroup(r -> r.workGroup(secondGroup).recursiveDeleteOption(true));
            } finally {
                admin.close();
            }
        }
    }
}
