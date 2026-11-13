package io.github.hectorvent.floci.services.ssm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Association;
import software.amazon.awssdk.services.ssm.model.AssociationDescription;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.Target;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmAssociationPendingIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void associationCrudDoesNotInventExecutionResults(String region) {
        String document = "pending-association-" + UUID.randomUUID();
        String associationId = null;
        try (SsmClient client = client("830011110001", region);
             SsmClient otherAccount = client("830011110002", region);
             SsmClient otherRegion = client("830011110001", "eu-west-2")) {
            client.createDocument(request -> request.name(document).documentType("Command").content("""
                    {"schemaVersion":"2.2","mainSteps":[{"action":"aws:runShellScript","name":"configure",
                      "inputs":{"runCommand":["printf 'configuration applied'"]}}]}
                    """));
            try {
                int commandsBefore = client.listCommands().commands().size();
                AssociationDescription created = client.createAssociation(request -> request.name(document)
                        .targets(Target.builder().key("tag:Configuration").values(document).build())
                        .scheduleExpression("rate(12 hours)")).associationDescription();
                associationId = created.associationId();
                String id = associationId;
                assertPending(created, "1");
                assertNotNull(created.date());
                assertPending(client.describeAssociation(request -> request.associationId(id))
                        .associationDescription(), "1");

                Association listed = client.listAssociations().associations().stream()
                        .filter(item -> id.equals(item.associationId())).findFirst().orElseThrow();
                assertEquals("Pending", listed.overview().status());
                assertNull(listed.lastExecutionDate());
                assertTrue(otherAccount.listAssociations().associations().stream()
                        .noneMatch(item -> id.equals(item.associationId())));
                assertTrue(otherRegion.listAssociations().associations().stream()
                        .noneMatch(item -> id.equals(item.associationId())));

                AssociationDescription updated = client.updateAssociation(request -> request.associationId(id)
                        .scheduleExpression("rate(6 hours)")).associationDescription();
                assertPending(updated, "2");
                assertEquals(created.date(), updated.date());
                assertEquals("rate(6 hours)", updated.scheduleExpression());
                assertPending(client.describeAssociation(request -> request.associationId(id))
                        .associationDescription(), "2");
                assertEquals(commandsBefore, client.listCommands().commands().size());
            } finally {
                if (associationId != null) {
                    String id = associationId;
                    client.deleteAssociation(request -> request.associationId(id));
                    SsmException missing = assertThrows(SsmException.class,
                            () -> client.describeAssociation(request -> request.associationId(id)));
                    assertEquals("AssociationDoesNotExist", missing.awsErrorDetails().errorCode());
                }
                client.deleteDocument(request -> request.name(document));
                SsmException missing = assertThrows(SsmException.class,
                        () -> client.getDocument(request -> request.name(document)));
                assertEquals("InvalidDocument", missing.awsErrorDetails().errorCode());
            }
        }
    }

    private static void assertPending(AssociationDescription association, String version) {
        assertEquals(version, association.associationVersion());
        assertEquals("Pending", association.status().nameAsString());
        assertNotNull(association.status().date());
        assertEquals("Pending", association.overview().status());
        assertNull(association.lastExecutionDate());
        assertNull(association.lastSuccessfulExecutionDate());
    }

    private static SsmClient client(String account, String region) {
        return SsmClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
