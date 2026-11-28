package io.floci.aws.services.iam;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.AthenaException;
import software.amazon.awssdk.services.athena.model.InvalidRequestException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AthenaDirectQueryIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"GetQueryExecution", "GetQueryResults", "StopQueryExecution", "ListQueryExecutions"})
    void exactActionUsesTheStoredWorkGroupAndDoesNotGrantSiblingOrBatchAccess(String action) {
        try (Fixture fixture = new Fixture();
             AthenaClient caller = caller(statement("Allow", action, arn(fixture.firstGroup)))) {
            invoke(caller, action, fixture.firstId, fixture.firstGroup);
            denied(() -> invoke(caller, action, fixture.secondId, fixture.secondGroup));
            denied(() -> caller.batchGetQueryExecution(request -> request.queryExecutionIds(fixture.firstId)));
            assertEquals(fixture.firstId, fixture.admin.getQueryExecution(
                    request -> request.queryExecutionId(fixture.firstId)).queryExecution().queryExecutionId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetQueryExecution", "GetQueryResults", "StopQueryExecution", "ListQueryExecutions"})
    void liveResourceTagsPreserveEmptyValuesAndCannotBeBorrowedFromAnotherWorkGroup(String action) {
        try (Fixture fixture = new Fixture()) {
            for (String value : List.of("green", "", " ")) {
                fixture.tag(fixture.firstGroup, value);
                fixture.tag(fixture.secondGroup, "other");
                try (AthenaClient caller = caller(conditional("Allow", action, "*", value))) {
                    invoke(caller, action, fixture.firstId, fixture.firstGroup);
                    denied(() -> invoke(caller, action, fixture.secondId, fixture.secondGroup));
                    fixture.admin.untagResource(request -> request.resourceARN(arn(fixture.firstGroup)).tagKeys("Owner"));
                    denied(() -> invoke(caller, action, fixture.firstId, fixture.firstGroup));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetQueryExecution", "GetQueryResults", "StopQueryExecution", "ListQueryExecutions"})
    void exactResourceDenyWinsAndAnotherAccountRegionOrPartitionArnCannotGrantAccess(String action) {
        try (Fixture fixture = new Fixture()) {
            try (AthenaClient caller = caller(statement("Allow", action, "*") + ","
                    + statement("Deny", action, arn(fixture.firstGroup)))) {
                denied(() -> invoke(caller, action, fixture.firstId, fixture.firstGroup));
            }
            for (String resource : List.of(
                    arn(fixture.firstGroup).replace("000000000000", "222222222222"),
                    arn(fixture.firstGroup).replace("us-east-1", "us-west-2"),
                    arn(fixture.firstGroup).replace("arn:aws:", "arn:aws-cn:"))) {
                try (AthenaClient caller = caller(statement("Allow", action, resource))) {
                    denied(() -> invoke(caller, action, fixture.firstId, fixture.firstGroup));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetQueryExecution", "GetQueryResults", "StopQueryExecution"})
    void flociMissingIdentifierPolicyRequiresActionOnWildcardResource(String action) {
        try (Fixture fixture = new Fixture()) {
            String absent = UUID.randomUUID().toString();
            try (AthenaClient scoped = caller(statement("Allow", action, arn(fixture.firstGroup)))) {
                denied(() -> invoke(scoped, action, absent, fixture.firstGroup));
            }
            try (AthenaClient wildcard = caller(statement("Allow", action, "*"))) {
                assertThrows(InvalidRequestException.class, () -> invoke(wildcard, action, absent, fixture.firstGroup));
            }
            try (AthenaClient denied = caller(statement("Allow", action, "*") + ","
                    + statement("Deny", action, "*"))) {
                denied(() -> invoke(denied, action, absent, fixture.firstGroup));
            }
        }
    }

    private AthenaClient caller(String statements) {
        String user = "direct-query-caller-" + UUID.randomUUID();
        String iamAuth = auth("test", "iam");
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), iamAuth, user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), iamAuth, user, "direct-query",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        return AwsTestClients.create(AthenaClient.builder(), credentials.accessKeyId(),
                credentials.secretAccessKey(), Region.US_EAST_1);
    }

    private static Object invoke(AthenaClient client, String action, String id, String group) {
        return switch (action) {
            case "GetQueryExecution" -> {
                String actual = client.getQueryExecution(request -> request.queryExecutionId(id)).queryExecution().queryExecutionId();
                assertEquals(id, actual);
                yield actual;
            }
            case "GetQueryResults" -> client.getQueryResults(request -> request.queryExecutionId(id));
            case "StopQueryExecution" -> client.stopQueryExecution(request -> request.queryExecutionId(id));
            case "ListQueryExecutions" -> {
                List<String> ids = client.listQueryExecutions(request -> request.workGroup(group)).queryExecutionIds();
                assertEquals(List.of(id), ids);
                yield ids;
            }
            default -> throw new IllegalArgumentException(action);
        };
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"athena:" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String conditional(String effect, String action, String resource, String value) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"athena:" + action + "\",\"Resource\":\"" + resource
                + "\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/Owner\":\"" + value + "\"}}}";
    }

    private static String arn(String group) {
        return "arn:aws:athena:us-east-1:000000000000:workgroup/" + group;
    }

    private static void denied(Runnable request) {
        AthenaException error = assertThrows(AthenaException.class, request::run);
        assertEquals(400, error.statusCode());
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }

    private static final class Fixture implements AutoCloseable {
        final AthenaClient admin = AwsTestClients.create(AthenaClient.builder(), "test", Region.US_EAST_1);
        final String firstGroup = "direct-query-" + UUID.randomUUID();
        final String secondGroup = firstGroup + "-other";
        final String firstId;
        final String secondId;

        Fixture() {
            admin.createWorkGroup(request -> request.name(firstGroup));
            admin.createWorkGroup(request -> request.name(secondGroup));
            firstId = admin.startQueryExecution(request -> request.queryString("SELECT 11").workGroup(firstGroup)).queryExecutionId();
            secondId = admin.startQueryExecution(request -> request.queryString("SELECT 29").workGroup(secondGroup)).queryExecutionId();
        }

        void tag(String group, String value) {
            admin.tagResource(request -> request.resourceARN(arn(group)).tags(tag -> tag.key("Owner").value(value)));
        }

        @Override
        public void close() {
            try {
                admin.deleteWorkGroup(request -> request.workGroup(firstGroup).recursiveDeleteOption(true));
                admin.deleteWorkGroup(request -> request.workGroup(secondGroup).recursiveDeleteOption(true));
            } finally {
                admin.close();
            }
        }
    }
}
