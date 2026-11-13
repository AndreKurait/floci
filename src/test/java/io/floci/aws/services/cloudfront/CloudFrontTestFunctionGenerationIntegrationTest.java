package io.floci.aws.services.cloudfront;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.CreateFunctionResponse;
import software.amazon.awssdk.services.cloudfront.model.DescribeFunctionResponse;
import software.amazon.awssdk.services.cloudfront.model.FunctionConfig;
import software.amazon.awssdk.services.cloudfront.model.TestResult;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFrontTestFunctionGenerationIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    DockerCloudFrontFunctionRuntime runtime;
    @Inject
    ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"update", "recreate", "live"})
    void completionUsesCapturedSourceAndSummaryAfterTheStoredGenerationChanges(String mutation) throws Exception {
        try (CloudFrontClient client = CloudFrontClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45))).build()) {
            String name = "generation-" + UUID.randomUUID().toString().replace("-", "");
            FunctionConfig config = FunctionConfig.builder().comment(name).runtime("cloudfront-js-2.0").build();
            CreateFunctionResponse created = client.createFunction(r -> r.name(name).functionConfig(config)
                    .functionCode(SdkBytes.fromUtf8String("function handler(event){const end=Date.now()+1200;"
                            + "while(Date.now()<end){};return {statusCode:200,body:'captured'};}")));
            Instant ownedCreated = created.functionSummary().functionMetadata().createdTime();
            try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
                String stage = mutation.equals("live") ? "LIVE" : "DEVELOPMENT";
                if (mutation.equals("live")) {
                    client.publishFunction(r -> r.name(name).ifMatch(created.eTag()));
                }
                DescribeFunctionResponse captured = client.describeFunction(r -> r.name(name).stage(stage));
                Future<TestResult> pending = workers.submit(() -> client.testFunction(r -> r.name(name).stage(stage)
                        .ifMatch(captured.eTag()).eventObject(SdkBytes.fromUtf8String("{}"))).testResult());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (runtime.activeContainers().isEmpty() && !pending.isDone() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(!runtime.activeContainers().isEmpty(), "the immutable snapshot must already have created its child");
                String replacement = "function handler(event){return {statusCode:200,body:'replacement'};}";
                if (mutation.equals("recreate")) {
                    client.deleteFunction(r -> r.name(name).ifMatch(created.eTag()));
                    CreateFunctionResponse newer = client.createFunction(r -> r.name(name).functionConfig(config)
                            .functionCode(SdkBytes.fromUtf8String(replacement)));
                    ownedCreated = newer.functionSummary().functionMetadata().createdTime();
                } else {
                    String updated = client.updateFunction(r -> r.name(name).ifMatch(created.eTag()).functionConfig(config)
                            .functionCode(SdkBytes.fromUtf8String(replacement))).eTag();
                    if (mutation.equals("live")) {
                        client.publishFunction(r -> r.name(name).ifMatch(updated));
                    }
                }
                DescribeFunctionResponse newer = client.describeFunction(r -> r.name(name).stage(stage));
                assertNotEquals(captured.eTag(), newer.eTag());
                TestResult completed = pending.get(15, TimeUnit.SECONDS);
                assertEquals(captured.functionSummary(), completed.functionSummary());
                assertEquals("captured", mapper.readTree(completed.functionOutput()).path("response").path("body").asText());
                assertEquals(newer.eTag(), client.describeFunction(r -> r.name(name).stage(stage)).eTag());
                assertEquals(replacement, client.getFunction(r -> r.name(name).stage(stage)).functionCode().asUtf8String());
                TestResult next = client.testFunction(r -> r.name(name).stage(stage).ifMatch(newer.eTag())
                        .eventObject(SdkBytes.fromUtf8String("{}"))).testResult();
                assertEquals("replacement", mapper.readTree(next.functionOutput()).path("response").path("body").asText());
            } finally {
                DescribeFunctionResponse current = client.describeFunction(r -> r.name(name).stage("DEVELOPMENT"));
                assertEquals(ownedCreated, current.functionSummary().functionMetadata().createdTime());
                assertEquals(name, current.functionSummary().functionConfig().comment());
                client.deleteFunction(r -> r.name(name).ifMatch(current.eTag()));
                assertEquals(404, assertThrows(CloudFrontException.class,
                        () -> client.describeFunction(r -> r.name(name).stage("DEVELOPMENT"))).statusCode());
            }
        }
    }
}
