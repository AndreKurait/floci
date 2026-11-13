package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudfront.model.CloudFrontFunction;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFrontFunctionMonitorIntegrationTest {

    @Inject
    CloudFrontService service;
    @Inject
    CloudFrontFunctionTesting testing;

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "publish", "delete", "tag", "untag"})
    void contextualReadMonitorExcludesEveryExistingFunctionAndTagWriter(String operation) throws Exception {
        assertTrue(service instanceof ClientProxy);
        String name = "monitor-" + UUID.randomUUID().toString().substring(0, 12);
        String extra = name + "-created";
        CloudFrontFunction original = service.createFunction(function(name, "original"));
        String etag = original.getEtag();
        String arn = AwsArnUtils.Arn.global("aws", "cloudfront", "000000000000", "function/" + name).toString();
        service.tagResource(arn, Map.of("owner", "original"));
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writerEntered = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CloudFrontFunctionSnapshot> snapshot = workers.submit(() -> service.readFunctionState(() -> {
                CloudFrontFunctionSnapshot value = testing.capture("000000000000", "aws", name, "DEVELOPMENT", etag);
                assertEquals(Map.of("owner", "original"), testing.currentTags(name));
                captured.countDown();
                await(release);
                return value;
            }));
            await(captured);
            Future<?> writer = workers.submit(() -> {
                writerEntered.countDown();
                switch (operation) {
                    case "create" -> service.createFunction(function(extra, "extra"));
                    case "update" -> service.updateFunction(name, etag, function(name, "replacement"));
                    case "publish" -> service.publishFunction(name, etag);
                    case "delete" -> service.deleteFunction(name, etag);
                    case "tag" -> service.tagResource(arn, Map.of("owner", "replacement"));
                    case "untag" -> service.untagResource(arn, List.of("owner"));
                    default -> throw new AssertionError(operation);
                }
            });
            await(writerEntered);
            assertThrows(TimeoutException.class, () -> writer.get(150, TimeUnit.MILLISECONDS));
            assertEquals(etag, service.describeFunction(name, "DEVELOPMENT").getEtag());
            release.countDown();
            CloudFrontFunctionSnapshot result = snapshot.get(5, TimeUnit.SECONDS);
            writer.get(5, TimeUnit.SECONDS);
            assertEquals(etag, result.etag());
            assertTrue(result.code().contains("original"));
            if (operation.equals("update")) {
                assertNotEquals(etag, service.describeFunction(name, "DEVELOPMENT").getEtag());
            }
        } finally {
            release.countDown();
            for (String owned : List.of(name, extra)) {
                try {
                    service.deleteFunction(owned, service.describeFunction(owned, "DEVELOPMENT").getEtag());
                } catch (AwsException absent) {
                    assertEquals(404, absent.getHttpStatus());
                }
            }
            service.untagResource(arn, List.of("owner"));
        }
    }

    private static CloudFrontFunction function(String name, String marker) {
        CloudFrontFunction value = new CloudFrontFunction();
        value.setName(name);
        value.setRuntime("cloudfront-js-2.0");
        value.setComment(marker);
        value.setFunctionCode(Base64.getEncoder().encodeToString(
                ("function handler(event){return {statusCode:200,body:'" + marker + "'};}").getBytes(StandardCharsets.UTF_8)));
        return value;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "bounded monitor control");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
