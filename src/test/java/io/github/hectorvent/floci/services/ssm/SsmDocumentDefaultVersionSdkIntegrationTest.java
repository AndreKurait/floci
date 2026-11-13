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
import software.amazon.awssdk.services.ssm.model.DocumentDescription;
import software.amazon.awssdk.services.ssm.model.GetDocumentResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.UpdateDocumentDefaultVersionResponse;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class SsmDocumentDefaultVersionSdkIntegrationTest {

    private static final String ORIGINAL = """
            {"schemaVersion":"2.2","description":"original",
             "mainSteps":[{"action":"aws:runShellScript","name":"inspect",
               "inputs":{"runCommand":["printf ready"]}}]}
            """;
    private static final String SECOND = ORIGINAL.replace("original", "second");
    private static final String THIRD = ORIGINAL.replace("original", "third");

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void publishingAnotherVersionPreservesTheDefault(String region) {
        String name = "document-default-" + UUID.randomUUID();
        try (SsmClient client = client(region)) {
            client.createDocument(request -> request.name(name).documentType("Command").content(ORIGINAL));
            try {
                DocumentDescription updated = client.updateDocument(request -> request.name(name)
                        .documentVersion("$LATEST").content(SECOND)).documentDescription();
                assertEquals("2", updated.documentVersion());
                assertEquals("2", updated.latestVersion());
                assertEquals("1", updated.defaultVersion());
                assertVersion(client, name, "$DEFAULT", "1", ORIGINAL);
                assertVersion(client, name, "$LATEST", "2", SECOND);
                assertVersion(client, name, null, "1", ORIGINAL);
                DocumentDescription described = client.describeDocument(request -> request.name(name)
                        .documentVersion("$DEFAULT")).document();
                assertEquals("1", described.defaultVersion());
                assertEquals("2", described.latestVersion());
            } finally {
                client.deleteDocument(request -> request.name(name));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void selectingAndRevertingTheDefaultPreservesEveryRetainedVersion(String region) {
        String name = "document-select-" + UUID.randomUUID();
        try (SsmClient client = client(region)) {
            client.createDocument(request -> request.name(name).documentType("Command").content(ORIGINAL));
            try {
                client.updateDocument(request -> request.name(name).documentVersion("$LATEST").content(SECOND));
                UpdateDocumentDefaultVersionResponse selected = client.updateDocumentDefaultVersion(
                        request -> request.name(name).documentVersion("2"));
                assertEquals(name, selected.description().name());
                assertEquals("2", selected.description().defaultVersion());
                assertVersion(client, name, "$DEFAULT", "2", SECOND);

                client.updateDocument(request -> request.name(name).documentVersion("$LATEST").content(THIRD));
                assertVersion(client, name, "$DEFAULT", "2", SECOND);
                assertVersion(client, name, "$LATEST", "3", THIRD);
                SsmException missing = assertThrows(SsmException.class, () -> client.updateDocumentDefaultVersion(
                        request -> request.name(name).documentVersion("99")));
                assertEquals("InvalidDocumentVersion", missing.awsErrorDetails().errorCode());
                for (String invalid : List.of("0", "01", "-1", "$LATEST", "")) {
                    SsmException malformed = assertThrows(SsmException.class,
                            () -> client.updateDocumentDefaultVersion(
                                    request -> request.name(name).documentVersion(invalid)));
                    assertEquals("ValidationException", malformed.awsErrorDetails().errorCode());
                }
                SsmException overflowing = assertThrows(SsmException.class,
                        () -> client.updateDocumentDefaultVersion(
                                request -> request.name(name).documentVersion("9223372036854775808")));
                assertEquals("InvalidDocumentVersion", overflowing.awsErrorDetails().errorCode());
                SsmException absent = assertThrows(SsmException.class,
                        () -> client.updateDocumentDefaultVersion(request -> request.name(name)));
                assertEquals("ValidationException", absent.awsErrorDetails().errorCode());
                assertVersion(client, name, "$DEFAULT", "2", SECOND);

                client.updateDocumentDefaultVersion(request -> request.name(name).documentVersion("1"));
                assertVersion(client, name, "$DEFAULT", "1", ORIGINAL);
                assertVersion(client, name, "2", "2", SECOND);
                assertVersion(client, name, "$LATEST", "3", THIRD);
            } finally {
                client.deleteDocument(request -> request.name(name));
            }
            SsmException deleted = assertThrows(SsmException.class, () -> client.updateDocumentDefaultVersion(
                    request -> request.name(name).documentVersion("1")));
            assertEquals("InvalidDocument", deleted.awsErrorDetails().errorCode());
        }
    }

    private static void assertVersion(SsmClient client, String name, String selector,
                                      String expectedVersion, String expectedContent) {
        GetDocumentResponse body = client.getDocument(request -> request.name(name).documentVersion(selector));
        assertEquals(expectedVersion, body.documentVersion());
        assertEquals(expectedContent, body.content());
        assertEquals(expectedVersion, client.describeDocument(request -> request.name(name)
                .documentVersion(selector)).document().documentVersion());
    }

    private static SsmClient client(String region) {
        return SsmClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("830011110004", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
