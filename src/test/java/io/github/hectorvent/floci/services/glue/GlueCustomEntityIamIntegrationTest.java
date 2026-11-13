package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;
import software.amazon.awssdk.services.glue.model.GlueException;

import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class GlueCustomEntityIamIntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void everyNativeActionRequiresItsOwnPermissionBeforeReadingOrChangingTheStore() {
        String account = "000000000000";
        String userName = "custom-user-" + UUID.randomUUID();
        String pattern = "custom-owned-" + UUID.randomUUID();
        try (GlueClient root = AwsTestClients.create(GlueClient.builder(), account, Region.US_EAST_1)) {
            EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                    cleanup, RestAssured.port, auth(account, "iam"), userName);
            try (GlueClient user = AwsTestClients.create(GlueClient.builder(),
                    credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1)) {
                policy(account, userName, "GetCustomEntityType");
                forbidden(() -> user.createCustomEntityType(r -> r.name(pattern).regexString("denied")));
                assertThrows(EntityNotFoundException.class, () -> root.getCustomEntityType(r -> r.name(pattern)));
                policy(account, userName, "CreateCustomEntityType");
                user.createCustomEntityType(r -> r.name(pattern).regexString("stored"));
                forbidden(() -> user.getCustomEntityType(r -> r.name(pattern)));
                forbidden(() -> user.batchGetCustomEntityTypes(r -> r.names(pattern)));
                forbidden(() -> user.listCustomEntityTypes(r -> r.maxResults(1)));
                forbidden(() -> user.deleteCustomEntityType(r -> r.name(pattern)));
                assertEquals("stored", root.getCustomEntityType(r -> r.name(pattern)).regexString());

                policy(account, userName, "GetCustomEntityType");
                assertEquals("stored", user.getCustomEntityType(r -> r.name(pattern)).regexString());
                forbidden(() -> user.batchGetCustomEntityTypes(r -> r.names(pattern)));
                forbidden(() -> user.deleteCustomEntityType(r -> r.name(pattern)));

                policy(account, userName, "BatchGetCustomEntityTypes");
                assertEquals(pattern, user.batchGetCustomEntityTypes(r -> r.names(pattern))
                        .customEntityTypes().getFirst().name());
                forbidden(() -> user.getCustomEntityType(r -> r.name(pattern)));
                forbidden(() -> user.listCustomEntityTypes(r -> r.maxResults(1)));

                policy(account, userName, "ListCustomEntityTypes");
                assertNotNull(user.listCustomEntityTypes(r -> r.maxResults(1)).customEntityTypes());
                forbidden(() -> user.batchGetCustomEntityTypes(r -> r.names(pattern)));
                forbidden(() -> user.deleteCustomEntityType(r -> r.name(pattern)));
                assertEquals("stored", root.getCustomEntityType(r -> r.name(pattern)).regexString());

                policy(account, userName, "DeleteCustomEntityType");
                assertEquals(pattern, user.deleteCustomEntityType(r -> r.name(pattern)).name());
                forbidden(() -> user.createCustomEntityType(r -> r.name(pattern).regexString("denied")));
                assertThrows(EntityNotFoundException.class, () -> root.getCustomEntityType(r -> r.name(pattern)));
            } finally {
                try {
                    root.deleteCustomEntityType(r -> r.name(pattern));
                } catch (EntityNotFoundException absent) {
                    // The action under test normally deleted this exact fixture-owned pattern.
                }
                assertThrows(EntityNotFoundException.class, () -> root.getCustomEntityType(r -> r.name(pattern)));
            }
        }
    }

    @Test
    void requestTagConditionsAreCheckedBeforeCreateAndAResourceArnDoesNotGrantAnUnscopedAction() {
        String account = "000000000000";
        String userName = "custom-tags-user-" + UUID.randomUUID();
        String name = "custom-tags-" + UUID.randomUUID();
        try (GlueClient root = AwsTestClients.create(GlueClient.builder(), account, Region.US_EAST_1)) {
            EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                    cleanup, RestAssured.port, auth(account, "iam"), userName);
            try (GlueClient user = AwsTestClients.create(GlueClient.builder(),
                    credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1)) {
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(account, "iam"), userName, "custom-tags", """
                        {"Version":"2012-10-17","Statement":[
                         {"Effect":"Allow","Action":"glue:CreateCustomEntityType","Resource":"*",
                          "Condition":{"StringEquals":{"aws:RequestTag/fixture":"allowed"},
                            "ForAllValues:StringEquals":{"aws:TagKeys":["fixture"]}}},
                         {"Effect":"Allow","Action":"glue:TagResource","Resource":"*"}]}
                        """);
                forbidden(() -> user.createCustomEntityType(r -> r.name(name).regexString("missing-tag")));
                forbidden(() -> user.createCustomEntityType(r -> r.name(name).regexString("wrong-tag")
                        .tags(Map.of("fixture", "wrong"))));
                forbidden(() -> user.createCustomEntityType(r -> r.name(name).regexString("extra-tag")
                        .tags(Map.of("fixture", "allowed", "extra", "denied"))));
                assertThrows(EntityNotFoundException.class, () -> root.getCustomEntityType(r -> r.name(name)));
                user.createCustomEntityType(r -> r.name(name).regexString("accepted")
                        .tags(Map.of("fixture", "allowed")));
                assertEquals("accepted", root.getCustomEntityType(r -> r.name(name)).regexString());
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(account, "iam"), userName, "custom-resource", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"glue:GetCustomEntityType",
                          "Resource":"arn:aws:glue:us-east-1:000000000000:customEntityType/%s"}]}
                        """.formatted(name));
                forbidden(() -> user.getCustomEntityType(r -> r.name(name)));
                assertEquals("accepted", root.getCustomEntityType(r -> r.name(name)).regexString());
            } finally {
                try {
                    root.deleteCustomEntityType(r -> r.name(name));
                } catch (EntityNotFoundException absent) {
                    // A refused create leaves this generated name absent.
                }
                assertThrows(EntityNotFoundException.class, () -> root.getCustomEntityType(r -> r.name(name)));
            }
        }
    }

    private void policy(String account, String user, String action) {
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(account, "iam"), user, "custom-pattern-actions", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"glue:%s","Resource":"*"}]}
                """.formatted(action));
    }

    private static void forbidden(Executable action) {
        GlueException error = assertThrows(GlueException.class, action);
        assertEquals(400, error.statusCode());
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }
}
