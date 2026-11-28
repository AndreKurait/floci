package io.floci.aws.services.s3tables;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.GetTableBucketMetricsConfigurationResponse;
import software.amazon.awssdk.services.s3tables.model.NotFoundException;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class S3TablesMetricsIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "us-gov-west-1,aws-us-gov", "cn-north-1,aws-cn"})
    void sdkConfigurationLifecycleUsesExactBucketAndFixedId(String region, String partition) {
        try (S3TablesClient client = client(region, "111111111111");
                Bucket first = bucket(client, name()); Bucket second = bucket(client, name())) {
            assertTrue(first.arn.startsWith("arn:" + partition + ":s3tables:" + region + ":111111111111:"));
            missing(client, first.arn);
            assertEquals(204, client.deleteTableBucketMetricsConfiguration(r -> r.tableBucketARN(first.arn))
                    .sdkHttpResponse().statusCode());
            assertEquals(204, client.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(first.arn))
                    .sdkHttpResponse().statusCode());
            exact(client, first.arn);
            client.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(first.arn));
            exact(client, first.arn);
            missing(client, second.arn);
            client.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(second.arn));
            exact(client, second.arn);
            client.deleteTableBucketMetricsConfiguration(r -> r.tableBucketARN(first.arn));
            client.deleteTableBucketMetricsConfiguration(r -> r.tableBucketARN(first.arn));
            missing(client, first.arn);
            exact(client, second.arn);
            client.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(first.arn));
            exact(client, first.arn);
        }
    }

    @Test
    void sameNameBucketsAreIsolatedByAccountRegionAndExactArn() {
        String name = name();
        try (S3TablesClient first = client("us-east-1", "111111111111");
                S3TablesClient second = client("us-east-1", "222222222222");
                S3TablesClient west = client("us-west-2", "111111111111");
                Bucket a = bucket(first, name); Bucket b = bucket(second, name); Bucket c = bucket(west, name)) {
            first.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(a.arn));
            missing(second, b.arn);
            missing(west, c.arn);
            for (String foreign : new String[] {b.arn, c.arn, a.arn.replace("arn:aws:", "arn:aws-cn:")}) {
                assertThrows(NotFoundException.class,
                        () -> first.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(foreign)));
                assertThrows(NotFoundException.class,
                        () -> first.deleteTableBucketMetricsConfiguration(r -> r.tableBucketARN(foreign)));
                missing(first, foreign);
            }
            exact(first, a.arn);
            missing(second, b.arn);
            missing(west, c.arn);
        }
    }

    @Test
    void bucketDeletionRemovesConfigurationAndMalformedOrMissingBucketsAreRefused() {
        try (S3TablesClient client = client("us-east-1", "111111111111")) {
            String arn = client.createTableBucket(r -> r.name(name())).arn();
            client.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(arn));
            client.deleteTableBucket(r -> r.tableBucketARN(arn));
            for (String missingArn : new String[] {arn, arn + "-missing", "not-an-arn"}) {
                missing(client, missingArn);
                assertThrows(NotFoundException.class,
                        () -> client.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(missingArn)));
                assertThrows(NotFoundException.class,
                        () -> client.deleteTableBucketMetricsConfiguration(r -> r.tableBucketARN(missingArn)));
            }
        }
    }

    private void exact(S3TablesClient client, String arn) {
        GetTableBucketMetricsConfigurationResponse response =
                client.getTableBucketMetricsConfiguration(r -> r.tableBucketARN(arn));
        assertEquals(arn, response.tableBucketARN());
        assertEquals("EntireBucket", response.id());
    }

    private void missing(S3TablesClient client, String arn) {
        NotFoundException exception = assertThrows(NotFoundException.class,
                () -> client.getTableBucketMetricsConfiguration(r -> r.tableBucketARN(arn)));
        assertEquals(404, exception.statusCode());
    }

    private static String name() {
        return "metrics-sdk-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private S3TablesClient client(String region, String account) {
        return S3TablesClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static Bucket bucket(S3TablesClient client, String name) {
        return new Bucket(client, client.createTableBucket(r -> r.name(name)).arn());
    }

    private record Bucket(S3TablesClient client, String arn) implements AutoCloseable {
        @Override
        public void close() {
            client.deleteTableBucket(r -> r.tableBucketARN(arn));
        }
    }
}
