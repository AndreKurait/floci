package io.github.hectorvent.floci.services.s3tables;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.BadRequestException;
import software.amazon.awssdk.services.s3tables.model.NotFoundException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class S3TablesStorageClassIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void sdkUpdatesBucketDefaultsAndPreservesExistingTableClasses(String region) {
        try (S3TablesClient client = client(region, "test"); Fixture fixture = fixture(client, null)) {
            assertEquals("STANDARD", bucketClass(client, fixture.arn));
            fixture.createTable("before_change", null);
            assertEquals("STANDARD", fixture.tableClass("before_change"));

            assertEquals(200, client.putTableBucketStorageClass(request -> request.tableBucketARN(fixture.arn)
                    .storageClassConfiguration(config -> config.storageClass("INTELLIGENT_TIERING")))
                    .sdkHttpResponse().statusCode());
            assertEquals("INTELLIGENT_TIERING", bucketClass(client, fixture.arn));
            fixture.createTable("after_change", null);
            fixture.createTable("explicit_standard", "STANDARD");
            assertEquals("STANDARD", fixture.tableClass("before_change"));
            assertEquals("INTELLIGENT_TIERING", fixture.tableClass("after_change"));
            assertEquals("STANDARD", fixture.tableClass("explicit_standard"));

            for (int i = 0; i < 2; i++) {
                client.putTableBucketStorageClass(request -> request.tableBucketARN(fixture.arn)
                        .storageClassConfiguration(config -> config.storageClass("STANDARD")));
            }
            assertEquals("STANDARD", bucketClass(client, fixture.arn));
            assertEquals("INTELLIGENT_TIERING", fixture.tableClass("after_change"));
            fixture.createTable("after_reset", null);
            assertEquals("STANDARD", fixture.tableClass("after_reset"));
        }
    }

    @Test
    void sdkReadsTheClassProvidedAtBucketCreationAndTheTableOverride() {
        try (S3TablesClient client = client("us-east-1", "test");
                Fixture fixture = fixture(client, "INTELLIGENT_TIERING")) {
            assertEquals("INTELLIGENT_TIERING", bucketClass(client, fixture.arn));
            fixture.createTable("inherited", null);
            fixture.createTable("explicit_standard", "STANDARD");
            assertEquals("INTELLIGENT_TIERING", fixture.tableClass("inherited"));
            assertEquals("STANDARD", fixture.tableClass("explicit_standard"));
        }
    }

    @Test
    void sdkReceivesModeledErrorsWithoutChangingTheStoredDefault() {
        try (S3TablesClient client = client("us-east-1", "test"); Fixture fixture = fixture(client, null)) {
            for (String invalid : List.of("", "standard", "GLACIER")) {
                assertThrows(BadRequestException.class, () -> client.putTableBucketStorageClass(request -> request
                        .tableBucketARN(fixture.arn).storageClassConfiguration(config -> config.storageClass(invalid))));
            }
            assertEquals("STANDARD", bucketClass(client, fixture.arn));
            String missing = fixture.arn + "-missing";
            assertThrows(NotFoundException.class, () -> client.getTableBucketStorageClass(request -> request.tableBucketARN(missing)));
            assertThrows(NotFoundException.class, () -> client.putTableBucketStorageClass(request -> request
                    .tableBucketARN(missing).storageClassConfiguration(config -> config.storageClass("STANDARD"))));
            assertThrows(NotFoundException.class, () -> client.getTableStorageClass(request -> request
                    .tableBucketARN(fixture.arn).namespace("audit").name("missing")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"storageClassConfiguration\":null}",
        "{\"storageClassConfiguration\":{}}",
        "{\"storageClassConfiguration\":[]}",
        "{\"storageClassConfiguration\":\"STANDARD\"}",
        "{\"storageClassConfiguration\":{\"storageClass\":12}}"
    })
    void malformedConfigurationLeavesStoredStateUnchanged(String body) {
        try (S3TablesClient client = client("us-east-1", "test"); Fixture fixture = fixture(client, null)) {
            given().urlEncodingEnabled(false).contentType("application/json").body(body)
                    .put("/buckets/" + URLEncoder.encode(fixture.arn, StandardCharsets.UTF_8) + "/storage-class")
                    .then().statusCode(400);
            assertEquals("STANDARD", bucketClass(client, fixture.arn));
        }
    }

    @Test
    void defaultsAreIsolatedAcrossAccountsWithIdenticalBucketNames() {
        String name = name();
        try (S3TablesClient first = client("us-east-1", "111111111111");
                S3TablesClient second = client("us-east-1", "222222222222");
                Fixture firstFixture = fixture(first, name, null);
                Fixture secondFixture = fixture(second, name, "INTELLIGENT_TIERING")) {
            assertEquals("STANDARD", bucketClass(first, firstFixture.arn));
            assertEquals("INTELLIGENT_TIERING", bucketClass(second, secondFixture.arn));
            assertThrows(NotFoundException.class, () -> first.getTableBucketStorageClass(request -> request
                    .tableBucketARN(secondFixture.arn)));
            assertThrows(NotFoundException.class, () -> first.putTableBucketStorageClass(request -> request
                    .tableBucketARN(secondFixture.arn).storageClassConfiguration(config -> config.storageClass("STANDARD"))));
            assertEquals("INTELLIGENT_TIERING", bucketClass(second, secondFixture.arn));
        }
    }

    private String bucketClass(S3TablesClient client, String arn) {
        return client.getTableBucketStorageClass(request -> request.tableBucketARN(arn))
                .storageClassConfiguration().storageClassAsString();
    }

    private Fixture fixture(S3TablesClient client, String storageClass) {
        return fixture(client, name(), storageClass);
    }

    private Fixture fixture(S3TablesClient client, String name, String storageClass) {
        String arn = client.createTableBucket(request -> {
            request.name(name);
            if (storageClass != null) {
                request.storageClassConfiguration(config -> config.storageClass(storageClass));
            }
        }).arn();
        client.createNamespace(request -> request.tableBucketARN(arn).namespace("audit"));
        return new Fixture(client, arn);
    }

    private String name() {
        return "storage-class-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private S3TablesClient client(String region, String account) {
        return S3TablesClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static final class Fixture implements AutoCloseable {
        private final S3TablesClient client;
        private final String arn;
        private final List<String> names = new ArrayList<>();

        private Fixture(S3TablesClient client, String arn) {
            this.client = client;
            this.arn = arn;
        }

        private void createTable(String name, String storageClass) {
            client.createTable(request -> {
                request.tableBucketARN(arn).namespace("audit").name(name).format("ICEBERG");
                if (storageClass != null) {
                    request.storageClassConfiguration(config -> config.storageClass(storageClass));
                }
            });
            names.add(name);
        }

        private String tableClass(String name) {
            return client.getTableStorageClass(request -> request.tableBucketARN(arn).namespace("audit").name(name))
                    .storageClassConfiguration().storageClassAsString();
        }

        @Override
        public void close() {
            for (String name : names) {
                client.deleteTable(request -> request.tableBucketARN(arn).namespace("audit").name(name));
            }
            client.deleteNamespace(request -> request.tableBucketARN(arn).namespace("audit"));
            client.deleteTableBucket(request -> request.tableBucketARN(arn));
        }
    }
}
