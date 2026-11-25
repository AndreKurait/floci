package io.floci.aws.services.ssm;

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
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmDocumentDescriptionSdkIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sdkReadsCreationTimeForCurrentAndRetainedDocumentVersions(String region) {
        String name = "document-description-" + UUID.randomUUID();
        String original = """
                {"schemaVersion":"2.2","description":"version one",
                 "mainSteps":[{"action":"aws:runShellScript","name":"inspect",
                   "inputs":{"runCommand":["printf ready"]}}]}
                """;
        Instant before = Instant.now().minusSeconds(1);
        try (SsmClient client = client(region)) {
            client.createDocument(request -> request.name(name).documentType("Command").content(original));
            try {
                DocumentDescription created = client.describeDocument(request -> request.name(name)).document();
                assertEquals(name, created.name());
                assertEquals("1", created.documentVersion());
                assertNotNull(created.createdDate());
                assertTrue(created.createdDate().isAfter(before));
                assertFalse(created.createdDate().isAfter(Instant.now()));

                client.updateDocument(request -> request.name(name).documentVersion("$LATEST")
                        .content(original.replace("version one", "version two")));
                DocumentDescription latest = client.describeDocument(request -> request.name(name)
                        .documentVersion("$LATEST")).document();
                DocumentDescription retained = client.describeDocument(request -> request.name(name)
                        .documentVersion("1")).document();
                assertEquals("2", latest.documentVersion());
                assertEquals("1", retained.documentVersion());
                assertNotNull(latest.createdDate());
                assertNotNull(retained.createdDate());
                assertEquals(original, client.getDocument(request -> request.name(name)
                        .documentVersion("1")).content());
            } finally {
                client.deleteDocument(request -> request.name(name));
            }
            SsmException missing = assertThrows(SsmException.class,
                    () -> client.describeDocument(request -> request.name(name)));
            assertEquals("InvalidDocument", missing.awsErrorDetails().errorCode());
        }
    }

    private static SsmClient client(String region) {
        return SsmClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("830011110003", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
