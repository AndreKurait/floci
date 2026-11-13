package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CfnResponseContentTypeIntegrationTest {

    @Inject
    CustomResourceResponseStore store;

    @Inject
    ObjectMapper mapper;

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"empty,SUCCESS", "empty,FAILED", "json,SUCCESS", "absent,SUCCESS"})
    void callbackCompletesWithTheContentTypeSentByItsHandler(String contentType, String status) throws Exception {
        String token = store.register();
        JsonNode body = mapper.createObjectNode()
                .put("Status", status)
                .put("Reason", "handler result")
                .put("RequestId", "request")
                .put("StackId", "stack")
                .put("LogicalResourceId", "Notification")
                .put("PhysicalResourceId", "notification");
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint.resolve("/cfn-response/" + token))
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!"absent".equals(contentType)) {
            request.header("Content-Type", "empty".equals(contentType) ? "" : "application/json");
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response::body);
            assertEquals("", response.body());
            assertEquals(body, store.await(token, Duration.ofSeconds(1)));
        } finally {
            try {
                store.await(token, Duration.ZERO);
            } catch (TimeoutException | IllegalStateException expected) {
                // Remove an unanswered token after an HTTP failure; a successful await already removed it.
            }
        }
    }
}
