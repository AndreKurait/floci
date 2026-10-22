package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.services.lambda.launcher.ContainerHandle;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaRuntimeLauncher;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class LambdaProvisionedConcurrencyQuotaIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 "
            + "Credential=000000000411/20261021/us-east-1/lambda/aws4_request";
    private static final String FUNCTION = "provisioned-quota";
    private static final String PC = "/2019-09-30/functions/" + FUNCTION + "/provisioned-concurrency";
    private static final String RC = "/2017-10-31/functions/" + FUNCTION + "/concurrency";

    @InjectMock
    LambdaRuntimeLauncher launcher;

    @Test
    void wireQuotaAndAccountSettingsMatchTheLiveAwsReservationControls() throws Exception {
        when(launcher.supportsProvisionedConcurrency()).thenReturn(true);
        when(launcher.launchProvisioned(any())).thenAnswer(call -> {
            LambdaFunction fn = call.getArgument(0);
            RuntimeApiServer runtime = mock(RuntimeApiServer.class);
            when(runtime.isInitialized()).thenReturn(true);
            return new ContainerHandle(UUID.randomUUID().toString(), fn.getFunctionName(),
                    runtime, ContainerState.WARM);
        });
        String resource = "/2015-03-31/functions/" + FUNCTION;
        given().header("Authorization", AUTH).contentType("application/json")
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x",
                         "Role":"arn:aws:iam::000000000411:role/lambda-role","Handler":"index.handler",
                         "Publish":true,"Code":{"ZipFile":"%s"}}
                        """.formatted(FUNCTION, zip()))
                .post("/2015-03-31/functions").then().statusCode(201);
        try {
            for (int count : new int[]{1001, 901}) {
                given().header("Authorization", AUTH).contentType("application/json")
                        .queryParam("Qualifier", "1")
                        .body("{\"ProvisionedConcurrentExecutions\":" + count + "}")
                        .put(PC).then().statusCode(400)
                        .body("__type", equalTo("InvalidParameterValueException"))
                        .body("message", equalTo(count == 1001
                                ? "Specified ConcurrentExecutions for function is greater than account's "
                                        + "unreserved concurrency [1000]."
                                : "Specified ConcurrentExecutions for function decreases account's "
                                        + "UnreservedConcurrentExecution below its minimum value of [100]."));
            }
            verify(launcher, never()).launchProvisioned(any());
            given().header("Authorization", AUTH).queryParam("Qualifier", "1").get(PC).then().statusCode(404);
            given().header("Authorization", AUTH).contentType("application/json")
                    .queryParam("Qualifier", "1").body("{\"ProvisionedConcurrentExecutions\":1}")
                    .put(PC).then().statusCode(202);
            assertUnreserved(999);
            given().header("Authorization", AUTH).contentType("application/json")
                    .body("{\"ReservedConcurrentExecutions\":2}").put(RC).then().statusCode(200);
            assertUnreserved(998);
            given().header("Authorization", AUTH).delete(RC).then().statusCode(204);
            assertUnreserved(999);
            given().header("Authorization", AUTH).queryParam("Qualifier", "1").delete(PC).then().statusCode(204);
            assertUnreserved(1000);
        } finally {
            given().header("Authorization", AUTH).delete(resource).then().statusCode(204);
        }
    }

    private static void assertUnreserved(int expected) {
        given().header("Authorization", AUTH).get("/2016-08-19/account-settings/").then().statusCode(200)
                .body("AccountLimit.UnreservedConcurrentExecutions", equalTo(expected));
    }

    private static String zip() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("index.js"));
            archive.write("exports.handler=async(event)=>event;".getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
