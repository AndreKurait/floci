package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.AthenaException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class AthenaPreparedStatementIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"CreatePreparedStatement", "GetPreparedStatement", "UpdatePreparedStatement",
            "DeletePreparedStatement", "BatchGetPreparedStatement"})
    void exactActionAndWorkGroupArnAllowOnlyTheirOperationAndResource(String operation) {
        try (AthenaClient admin = AwsTestClients.create(AthenaClient.builder(), "test", Region.US_EAST_1)) {
            String group = "prepared-iam-" + UUID.randomUUID();
            String other = group + "-other";
            admin.createWorkGroup(r -> r.name(group));
            admin.createWorkGroup(r -> r.name(other));
            create(admin, group, "saved");
            create(admin, other, "saved");
            try (AthenaClient scoped = caller(statement("Allow", "athena:" + operation, arn(group)))) {
                invoke(scoped, operation, group);
                denied(() -> invoke(scoped, operation, other));
                for (String different : List.of("CreatePreparedStatement", "GetPreparedStatement", "UpdatePreparedStatement",
                        "DeletePreparedStatement", "BatchGetPreparedStatement")) {
                    if (!operation.equals(different)) {
                        denied(() -> invoke(scoped, different, group));
                    }
                }
            } finally {
                admin.deleteWorkGroup(r -> r.workGroup(group).recursiveDeleteOption(true));
                admin.deleteWorkGroup(r -> r.workGroup(other).recursiveDeleteOption(true));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreatePreparedStatement", "GetPreparedStatement", "UpdatePreparedStatement",
            "DeletePreparedStatement", "BatchGetPreparedStatement"})
    void explicitDenyOverridesWildcardAllowanceForEachOperation(String operation) {
        try (AthenaClient admin = AwsTestClients.create(AthenaClient.builder(), "test", Region.US_EAST_1)) {
            String group = "prepared-deny-" + UUID.randomUUID();
            admin.createWorkGroup(r -> r.name(group));
            create(admin, group, "saved");
            try (AthenaClient denied = caller(statement("Allow", "athena:*", "*") + ","
                    + statement("Deny", "athena:" + operation, arn(group)))) {
                denied(() -> invoke(denied, operation, group));
                assertEquals("original", admin.getPreparedStatement(r -> r.workGroup(group).statementName("saved"))
                        .preparedStatement().queryStatement());
            } finally {
                admin.deleteWorkGroup(r -> r.workGroup(group).recursiveDeleteOption(true));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"team-a", "", " "})
    void allFiveActionsUseLiveResourceTagsIncludingEmptyValues(String marker) {
        try (AthenaClient admin = AwsTestClients.create(AthenaClient.builder(), "test", Region.US_EAST_1)) {
            String group = "prepared-tags-" + UUID.randomUUID();
            String other = group + "-other";
            admin.createWorkGroup(r -> r.name(group));
            admin.createWorkGroup(r -> r.name(other));
            admin.tagResource(r -> r.resourceARN(arn(group)).tags(t -> t.key("Owner").value(marker)));
            admin.tagResource(r -> r.resourceARN(arn(other)).tags(t -> t.key("Owner").value("wrong")));
            create(admin, group, "saved");
            create(admin, other, "saved");
            String policy = """
                    {"Effect":"Allow","Action":["athena:CreatePreparedStatement","athena:GetPreparedStatement",
                      "athena:UpdatePreparedStatement","athena:DeletePreparedStatement","athena:BatchGetPreparedStatement"],
                     "Resource":"*","Condition":{"StringEquals":{"aws:ResourceTag/Owner":"%s"}}},
                    {"Effect":"Deny","Action":"athena:UpdatePreparedStatement","Resource":"*",
                     "Condition":{"StringEquals":{"aws:ResourceTag/Blocked":"yes"}}}
                    """.formatted(marker);
            try (AthenaClient scoped = caller(policy)) {
                for (String operation : List.of("GetPreparedStatement", "BatchGetPreparedStatement", "UpdatePreparedStatement",
                        "CreatePreparedStatement", "DeletePreparedStatement")) {
                    invoke(scoped, operation, group);
                    denied(() -> invoke(scoped, operation, other));
                }
                create(admin, group, "saved");
                admin.tagResource(r -> r.resourceARN(arn(group)).tags(t -> t.key("Blocked").value("yes")));
                denied(() -> invoke(scoped, "UpdatePreparedStatement", group));
                assertNotNull(scoped.getPreparedStatement(r -> r.workGroup(group).statementName("saved")).preparedStatement());
                admin.untagResource(r -> r.resourceARN(arn(group)).tagKeys("Owner"));
                for (String operation : List.of("CreatePreparedStatement", "GetPreparedStatement", "UpdatePreparedStatement",
                        "DeletePreparedStatement", "BatchGetPreparedStatement")) {
                    denied(() -> invoke(scoped, operation, group));
                }
            } finally {
                admin.deleteWorkGroup(r -> r.workGroup(group).recursiveDeleteOption(true));
                admin.deleteWorkGroup(r -> r.workGroup(other).recursiveDeleteOption(true));
            }
        }
    }

    @Test
    void resourceArnForAnotherAccountOrRegionCannotAuthorizeTheLocalWorkGroup() {
        try (AthenaClient admin = AwsTestClients.create(AthenaClient.builder(), "test", Region.US_EAST_1)) {
            String group = "prepared-scope-" + UUID.randomUUID();
            admin.createWorkGroup(r -> r.name(group));
            create(admin, group, "saved");
            try {
                for (String resource : List.of(arn(group).replace("000000000000", "222222222222"),
                        arn(group).replace("us-east-1", "us-west-2"), arn(group).replace("arn:aws:", "arn:aws-cn:"))) {
                    try (AthenaClient scoped = caller(statement("Allow", "athena:GetPreparedStatement", resource))) {
                        denied(() -> invoke(scoped, "GetPreparedStatement", group));
                    }
                }
            } finally {
                admin.deleteWorkGroup(r -> r.workGroup(group).recursiveDeleteOption(true));
            }
        }
    }

    private AthenaClient caller(String statements) {
        String user = "prepared-caller-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth("test", "iam"), user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("test", "iam"), user, "prepared",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        return AwsTestClients.create(AthenaClient.builder(), credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1);
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String arn(String workGroup) {
        return "arn:aws:athena:us-east-1:000000000000:workgroup/" + workGroup;
    }

    private static void denied(Runnable operation) {
        AthenaException error = assertThrows(AthenaException.class, operation::run);
        assertEquals(400, error.statusCode());
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }

    private static void create(AthenaClient client, String group, String name) {
        client.createPreparedStatement(r -> r.workGroup(group).statementName(name).queryStatement("original"));
    }

    private static void invoke(AthenaClient client, String operation, String group) {
        switch (operation) {
            case "CreatePreparedStatement" -> create(client, group, "newone");
            case "GetPreparedStatement" -> client.getPreparedStatement(r -> r.workGroup(group).statementName("saved"));
            case "UpdatePreparedStatement" -> client.updatePreparedStatement(r -> r.workGroup(group).statementName("saved")
                    .queryStatement("updated"));
            case "DeletePreparedStatement" -> client.deletePreparedStatement(r -> r.workGroup(group).statementName("saved"));
            case "BatchGetPreparedStatement" -> client.batchGetPreparedStatement(r -> r.workGroup(group).preparedStatementNames("saved"));
            default -> throw new IllegalArgumentException(operation);
        }
    }
}
