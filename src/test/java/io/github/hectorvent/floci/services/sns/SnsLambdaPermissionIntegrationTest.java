package io.github.hectorvent.floci.services.sns;

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
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
class SnsLambdaPermissionIntegrationTest {

    private static final String SOURCE = "121212121212";
    private static final String TARGET = "343434343434";
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
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void publishUsesTheStoredQualifiedPermissionAndHonorsRevocation(boolean json, boolean batch) throws Exception {
        SnsClient sns = SnsClient.builder().endpointOverride(baseUri).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(SOURCE, "test")))
                .build();
        cleanup.register(sns::close);
        String name = "sns-permission-" + UUID.randomUUID().toString().substring(0, 8);
        String topicName = name + "-topic";
        String topicArn = "arn:aws:sns:" + REGION + ":" + SOURCE + ":" + topicName;
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
        cleanup.register(() -> sns.deleteTopic(request -> request.topicArn(topicArn)));
        sns.createTopic(request -> request.name(topicName));
        sns.subscribe(request -> request.topicArn(topicArn).protocol("lambda").endpoint(endpoint));
        doReturn(new InvokeResult()).when(lambda).invoke(eq(REGION), eq(endpoint), any(byte[].class),
                eq(InvocationType.Event));

        publish(sns, topicArn, json, batch);
        verify(lambda, times(0)).invoke(eq(REGION), eq(endpoint), any(byte[].class), eq(InvocationType.Event));
        permission(name, null, "unqualified", topicArn);
        publish(sns, topicArn, json, batch);
        verify(lambda, times(0)).invoke(eq(REGION), eq(endpoint), any(byte[].class), eq(InvocationType.Event));
        permission(name, "live", "qualified", topicArn);
        publish(sns, topicArn, json, batch);
        verify(lambda, times(1)).invoke(eq(REGION), eq(endpoint), any(byte[].class), eq(InvocationType.Event));
        lambdaRequest().queryParam("Qualifier", "live").delete(FUNCTIONS + name + "/policy/qualified")
                .then().statusCode(204);
        publish(sns, topicArn, json, batch);
        verify(lambda, times(1)).invoke(eq(REGION), eq(endpoint), any(byte[].class), eq(InvocationType.Event));
    }

    private static void permission(String function, String qualifier, String statement, String topicArn) {
        RequestSpecification request = lambdaRequest();
        if (qualifier != null) {
            request.queryParam("Qualifier", qualifier);
        }
        request.body(Map.of("StatementId", statement, "Action", "lambda:InvokeFunction",
                "Principal", "sns.amazonaws.com", "SourceArn", topicArn, "SourceAccount", SOURCE))
                .post(FUNCTIONS + function + "/policy").then().statusCode(201);
    }

    private static void publish(SnsClient sns, String topicArn, boolean json, boolean batch) {
        String action = batch ? "PublishBatch" : "Publish";
        RequestSpecification request = snsRequest();
        if (json) {
            Map<String, Object> body = batch
                    ? Map.of("TopicArn", topicArn, "PublishBatchRequestEntries",
                            List.of(Map.of("Id", "first", "Message", "payload")))
                    : Map.of("TopicArn", topicArn, "Message", "payload");
            request.contentType("application/x-amz-json-1.0")
                    .header("X-Amz-Target", "SNS_20100331." + action)
                    .body(body);
        } else {
            if (batch) {
                sns.publishBatch(builder -> builder.topicArn(topicArn).publishBatchRequestEntries(
                        PublishBatchRequestEntry.builder().id("first").message("payload").build()));
            } else {
                sns.publish(builder -> builder.topicArn(topicArn).message("payload"));
            }
            return;
        }
        request.post("/").then().statusCode(200);
    }

    private static RequestSpecification snsRequest() {
        return given().header("Authorization", EnforcementFixtures.auth(SOURCE, "sns"))
                .contentType("application/x-www-form-urlencoded");
    }

    private static RequestSpecification lambdaRequest() {
        return given().header("Authorization", EnforcementFixtures.auth(TARGET, "lambda"))
                .contentType("application/json");
    }
}
