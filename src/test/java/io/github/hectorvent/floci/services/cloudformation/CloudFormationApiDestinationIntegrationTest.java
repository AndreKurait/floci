package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.Connection;
import io.github.hectorvent.floci.services.eventbridge.model.ConnectionState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationApiDestinationIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261002/us-east-1/cloudformation/aws4_request";
    private static final String EVENTS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261002/us-east-1/events/aws4_request";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EventBridgeService events;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void stackProvisionsDynamicCredentialsDeliversUpdatesAndDeletes() throws Exception {
        String id = "cfn-api-" + Long.toString(System.nanoTime(), 36);
        LinkedBlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
        HttpServer sink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sink.createContext("/", exchange -> {
            Delivery delivery = new Delivery(exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("X-Key"),
                    exchange.getRequestHeaders().getFirst("X-Target"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            deliveries.add(delivery);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        sink.start();
        try {
            String endpoint = "http://127.0.0.1:" + sink.getAddress().getPort();
            cfn("CreateStack", id, template(id, endpoint + "/events/*", 42, false)).then().statusCode(200);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(id, CFN_AUTH).status());
            Map<String, String> original = outputs(id);
            assertOutputs(original);
            String connectionName = original.get("ConnectionName");
            String destinationName = original.get("DestinationName");
            assertEquals(original.get("ConnectionArn"), events.describeConnection(connectionName, REGION).getConnectionArn());
            assertEquals(events.describeConnection(connectionName, REGION).getSecretArn(), original.get("SecretArn"));
            assertEquals(original.get("DestinationArn"), events.describeApiDestination(destinationName, REGION).getArn());
            assertEquals(42, events.describeApiDestination(destinationName, REGION).getInvocationRateLimitPerSecond());
            assertTargets(id, original.get("DestinationArn"));
            publish(id);
            assertDelivery(deliveries, "/events/item?version=2");
            cfn("UpdateStack", id, template(id, endpoint + "/events/*", 42, false)).then().statusCode(400);
            assertEquals(original, outputs(id));

            cfn("UpdateStack", id, template(id, endpoint + "/updated/*", 84, false)).then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(id, CFN_AUTH).status());
            assertEquals(original, outputs(id));
            assertEquals(84, events.describeApiDestination(destinationName, REGION).getInvocationRateLimitPerSecond());
            assertEquals("rate-84", events.describeConnection(connectionName, REGION).getDescription());
            publish(id);
            assertDelivery(deliveries, "/updated/item?version=2");

            cfn("UpdateStack", id, template(id, endpoint + "/replaced/*", 84, true)).then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(id, CFN_AUTH).status());
            Map<String, String> replaced = outputs(id);
            assertOutputs(replaced);
            assertEquals(id + "-auth", replaced.get("ConnectionName"));
            assertEquals(id + "-destination", replaced.get("DestinationName"));
            assertMissing("DescribeConnection", connectionName);
            assertMissing("DescribeApiDestination", destinationName);
            publish(id);
            assertDelivery(deliveries, "/replaced/item?version=2");

            cfn("DeleteStack", id, null).then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(id, CFN_AUTH);
            assertMissing("DescribeConnection", replaced.get("ConnectionName"));
            assertMissing("DescribeApiDestination", replaced.get("DestinationName"));
            eventRequest("DescribeRule", Map.of("Name", id)).then().statusCode(400);
        } finally {
            cfn("DeleteStack", id, null);
            sink.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterResourceFailureRestoresMutableUpdatesAndReplacements(boolean rename) throws Exception {
        String id = "cfn-rollback-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", id, template(id, "https://example.com/original/*", 42, false))
                .then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(id, CFN_AUTH).status());
        Map<String, String> original = outputs(id);
        String connectionName = original.get("ConnectionName");
        String destinationName = original.get("DestinationName");
        Connection prior = events.describeConnection(connectionName, REGION);
        String originalAuth = prior.getAuthParameters();
        Instant created = prior.getCreationTime();
        Instant modified = Instant.parse("2026-01-02T03:04:05Z");
        prior.setConnectionState(ConnectionState.DEAUTHORIZED);
        prior.setStateReason("Synthetic deauthorization");
        prior.setLastModifiedTime(modified);
        prior.setLastAuthorizedTime(null);
        try {
            ObjectNode failed = (ObjectNode) MAPPER.readTree(template(id, "https://example.com/changed/*", 84, rename));
            ObjectNode resources = (ObjectNode) failed.get("Resources");
            ObjectNode auth = (ObjectNode) resources.at("/Connection/Properties/AuthParameters/ApiKeyAuthParameters");
            auth.put("ApiKeyValue", "synthetic-changed-key");
            resources.set("Broken", MAPPER.readTree("""
                    {"Type":"AWS::Events::Connection","DependsOn":"Rule","Properties":{
                      "AuthorizationType":"INVALID","AuthParameters":{}}}
                    """));
            cfn("UpdateStack", id, failed.toString()).then().statusCode(200);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(id, CFN_AUTH).status());

            assertEquals(original, outputs(id));
            assertEquals("rate-42", events.describeConnection(connectionName, REGION).getDescription());
            Connection restored = events.describeConnection(connectionName, REGION);
            assertEquals(originalAuth, restored.getAuthParameters());
            assertEquals(ConnectionState.DEAUTHORIZED, restored.getConnectionState());
            assertEquals("Synthetic deauthorization", restored.getStateReason());
            assertEquals(created, restored.getCreationTime());
            assertEquals(modified, restored.getLastModifiedTime());
            assertNull(restored.getLastAuthorizedTime());
            assertEquals("https://example.com/original/*",
                    events.describeApiDestination(destinationName, REGION).getInvocationEndpoint());
            assertEquals(42, events.describeApiDestination(destinationName, REGION).getInvocationRateLimitPerSecond());
            assertTargets(id, original.get("DestinationArn"));
            if (rename) {
                assertMissing("DescribeConnection", id + "-auth");
                assertMissing("DescribeApiDestination", id + "-destination");
            }
        } finally {
            cfn("DeleteStack", id, null).then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(id, CFN_AUTH);
        }
        assertMissing("DescribeConnection", connectionName);
        assertMissing("DescribeApiDestination", destinationName);
    }

    private void assertTargets(String ruleName, String destinationArn) {
        List<Target> targets = events.listTargetsByRule(ruleName, null, REGION);
        assertEquals(1, targets.size());
        Target target = targets.getFirst();
        assertEquals(destinationArn, target.getArn());
        assertEquals("arn:aws:iam::000000000000:role/synthetic", target.getRoleArn());
        assertEquals(List.of("item"), target.getHttpParameters().getPathParameterValues());
        assertEquals("yes", target.getHttpParameters().getHeaderParameters().get("X-Target"));
        assertEquals("2", target.getHttpParameters().getQueryStringParameters().get("version"));
        assertEquals(0, target.getRetryPolicy().maximumRetryAttempts());
        assertEquals(60, target.getRetryPolicy().maximumEventAgeInSeconds());
    }

    private static void assertDelivery(LinkedBlockingQueue<Delivery> deliveries, String path) throws Exception {
        Delivery delivery = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull(delivery, "EventBridge did not deliver to the CFN API destination");
        assertEquals(path, delivery.path());
        assertEquals("synthetic-key", delivery.apiKey());
        assertEquals("yes", delivery.targetHeader());
        JsonNode body = MAPPER.readTree(delivery.body());
        assertEquals("ok", body.path("detail").path("value").asText());
    }

    private static void assertOutputs(Map<String, String> outputs) {
        String prefix = "arn:aws:events:us-east-1:000000000000:";
        assertEquals(prefix + "connection/" + outputs.get("ConnectionName"), outputs.get("ConnectionPolicyArn"));
        assertTrue(outputs.get("ConnectionArn").startsWith(outputs.get("ConnectionPolicyArn") + "/"));
        assertEquals(prefix + "api-destination/" + outputs.get("DestinationName"), outputs.get("DestinationPolicyArn"));
        assertTrue(outputs.get("DestinationArn").startsWith(outputs.get("DestinationPolicyArn") + "/"));
        assertTrue(outputs.get("SecretArn").startsWith("arn:aws:secretsmanager:us-east-1:000000000000:secret:"));
    }

    private static Map<String, String> outputs(String stack) {
        String xml = cfn("DescribeStacks", stack, null).then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }

    private static void publish(String source) {
        eventRequest("PutEvents", Map.of("Entries", List.of(Map.of("Source", source,
                "DetailType", "test", "Detail", "{\"value\":\"ok\"}"))))
                .then().statusCode(200);
    }

    private static void assertMissing(String action, String name) {
        eventRequest(action, Map.of("Name", name)).then().statusCode(400);
    }

    private static Response eventRequest(String action, Object body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", EVENTS_AUTH).header("X-Amz-Target", "AWSEvents." + action)
                .body(body).when().post("/");
    }

    private static Response cfn(String action, String stack, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH).formParam("Action", action).formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        return request.when().post("/");
    }

    private static String template(String id, String endpoint, int rate, boolean named) {
        String connectionName = named ? "\"Name\":\"" + id + "-auth\"," : "";
        String destinationName = named ? "\"Name\":\"" + id + "-destination\"," : "";
        return """
                {"Resources":{
                  "Secret":{"Type":"AWS::SecretsManager::Secret","Properties":{
                    "Name":"%1$s-secret","SecretString":"{\\"key\\":\\"synthetic-key\\"}"}},
                  "Connection":{"Type":"AWS::Events::Connection","DependsOn":"Secret","Properties":{
                    %2$s"AuthorizationType":"API_KEY","Description":"rate-%5$d","AuthParameters":{
                      "ApiKeyAuthParameters":{"ApiKeyName":"X-Key",
                        "ApiKeyValue":"{{resolve:secretsmanager:%1$s-secret:SecretString:key}}"}}}},
                  "Destination":{"Type":"AWS::Events::ApiDestination","Properties":{
                    %3$s"ConnectionArn":{"Fn::GetAtt":["Connection","Arn"]},"HttpMethod":"POST",
                    "InvocationEndpoint":"%4$s","InvocationRateLimitPerSecond":%5$d}},
                  "Rule":{"Type":"AWS::Events::Rule","Properties":{"Name":"%1$s",
                    "EventPattern":{"source":["%1$s"]},"Targets":[{"Id":"http",
                      "Arn":{"Fn::GetAtt":["Destination","Arn"]},
                      "RoleArn":"arn:aws:iam::000000000000:role/synthetic",
                      "HttpParameters":{"PathParameterValues":["item"],"HeaderParameters":{"X-Target":"yes"},
                        "QueryStringParameters":{"version":"2"}},
                      "RetryPolicy":{"MaximumRetryAttempts":0,"MaximumEventAgeInSeconds":60}}]}}},
                 "Outputs":{
                   "ConnectionName":{"Value":{"Ref":"Connection"}},
                   "ConnectionArn":{"Value":{"Fn::GetAtt":["Connection","Arn"]}},
                   "ConnectionPolicyArn":{"Value":{"Fn::GetAtt":["Connection","ArnForPolicy"]}},
                   "SecretArn":{"Value":{"Fn::GetAtt":["Connection","SecretArn"]}},
                   "DestinationName":{"Value":{"Ref":"Destination"}},
                   "DestinationArn":{"Value":{"Fn::GetAtt":["Destination","Arn"]}},
                   "DestinationPolicyArn":{"Value":{"Fn::GetAtt":["Destination","ArnForPolicy"]}}}}
                """.formatted(id, connectionName, destinationName, endpoint, rate);
    }

    private record Delivery(String path, String apiKey, String targetHeader, String body) { }
}
