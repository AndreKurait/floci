package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.Target;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EventBridgeLambdaPermissionIntegrationTest {

    private static final String SOURCE = "121212121212";
    private static final String TARGET = SOURCE;
    private static final String REGION = "us-east-1";
    private static final String FUNCTIONS = "/2015-03-31/functions/";

    @InjectSpy
    LambdaService lambda;

    @TestHTTPResource("/")
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void putEventsUsesStoredQualifiedPermissionAndHonorsRevocation(boolean customBus) throws Exception {
        EventBridgeClient events = EventBridgeClient.builder().endpointOverride(baseUri).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(SOURCE, "test")))
                .build();
        cleanup.register(events::close);
        String name = "event-permission-" + UUID.randomUUID().toString().substring(0, 8);
        String bus = customBus ? name + "-bus" : "default";
        String ruleArn = "arn:aws:events:" + REGION + ":" + SOURCE + ":rule/"
                + (customBus ? bus + "/" : "") + name;
        String functionArn = "arn:aws:lambda:" + REGION + ":" + TARGET + ":function:" + name;
        String endpoint = functionArn + ":live";
        cleanup.register(() -> lambdaRequest().delete(FUNCTIONS + name).then().statusCode(204));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => ({});".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        lambdaRequest().body(Map.of("FunctionName", name, "Runtime", "nodejs20.x", "Handler", "index.handler",
                "Role", "arn:aws:iam::" + TARGET + ":role/lambda-role",
                "Code", Map.of("ZipFile", Base64.getEncoder().encodeToString(bytes.toByteArray()))))
                .post(FUNCTIONS).then().statusCode(201);
        lambdaRequest().body("{}").post(FUNCTIONS + name + "/versions").then().statusCode(201);
        lambdaRequest().body(Map.of("Name", "live", "FunctionVersion", "1"))
                .post(FUNCTIONS + name + "/aliases").then().statusCode(201);
        if (customBus) {
            cleanup.register(() -> events.deleteEventBus(request -> request.name(bus)));
            events.createEventBus(request -> request.name(bus));
        }
        cleanup.register(() -> events.deleteRule(request -> request.name(name).eventBusName(bus)));
        events.putRule(request -> request.name(name).eventBusName(bus)
                .eventPattern("{\"source\":[\"permission-test\"]}"));
        cleanup.register(() -> events.removeTargets(request -> request.rule(name).eventBusName(bus).ids("receiver")));
        events.putTargets(request -> request.rule(name).eventBusName(bus).targets(
                Target.builder().id("receiver").arn(endpoint).build()));
        doReturn(new InvokeResult()).when(lambda).invokeArn(eq(endpoint), any(byte[].class),
                eq(InvocationType.Event));

        publish(events, bus);
        verify(lambda, times(0)).invokeArn(eq(endpoint), any(byte[].class), eq(InvocationType.Event));
        permission(name, null, "unqualified", ruleArn);
        publish(events, bus);
        verify(lambda, times(0)).invokeArn(eq(endpoint), any(byte[].class), eq(InvocationType.Event));
        permission(name, "live", "qualified", ruleArn);
        publish(events, bus);
        verify(lambda, times(1)).invokeArn(eq(endpoint), any(byte[].class), eq(InvocationType.Event));
        lambdaRequest().queryParam("Qualifier", "live").delete(FUNCTIONS + name + "/policy/qualified")
                .then().statusCode(204);
        publish(events, bus);
        verify(lambda, times(1)).invokeArn(eq(endpoint), any(byte[].class), eq(InvocationType.Event));
    }

    private static void permission(String function, String qualifier, String statement, String ruleArn) {
        RequestSpecification request = lambdaRequest();
        if (qualifier != null) {
            request.queryParam("Qualifier", qualifier);
        }
        request.body(Map.of("StatementId", statement, "Action", "lambda:InvokeFunction",
                "Principal", "events.amazonaws.com", "SourceArn", ruleArn, "SourceAccount", SOURCE))
                .post(FUNCTIONS + function + "/policy").then().statusCode(201);
    }

    private static void publish(EventBridgeClient events, String bus) {
        events.putEvents(request -> request.entries(PutEventsRequestEntry.builder().eventBusName(bus)
                .source("permission-test").detailType("permission-check").detail("{}").build()));
    }

    private static RequestSpecification lambdaRequest() {
        return given().header("Authorization", EnforcementFixtures.auth(TARGET, "lambda"))
                .contentType("application/json");
    }
}
