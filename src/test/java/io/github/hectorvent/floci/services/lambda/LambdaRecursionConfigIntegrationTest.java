package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class LambdaRecursionConfigIntegrationTest {
    private static final String ACCOUNT = "741681401001";
    private static final Region REGION = Region.US_EAST_1;

    @Test
    void nativeSdkSettingsAreIndependentUnqualifiedAndSeparateFromFunctionConfiguration() throws Exception {
        try (LambdaClient client = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, REGION)) {
            String name = "recursion-" + UUID.randomUUID();
            String other = name + "-other";
            String arn = create(client, name, ACCOUNT);
            try {
                create(client, other, ACCOUNT);
                assertSetting(client, name, "Terminate");
                for (String setting : List.of("Allow", "Terminate", "Allow")) {
                    assertEquals(setting, client.putFunctionRecursionConfig(r -> r.functionName(name)
                            .recursiveLoop(setting)).recursiveLoopAsString());
                    for (String selector : List.of(name, ACCOUNT + ":function:" + name, arn)) {
                        assertSetting(client, selector, setting);
                    }
                    assertSetting(client, other, "Terminate");
                }
                String version = client.publishVersion(r -> r.functionName(name)).version();
                client.createAlias(r -> r.functionName(name).name("live").functionVersion(version));
                client.updateFunctionConfiguration(r -> r.functionName(name).description("unrelated update"));
                assertSetting(client, name, "Allow");
                for (String selector : List.of(name + ":" + version, name + ":live", name + ":$LATEST", arn + ":live")) {
                    assertValidation(() -> client.getFunctionRecursionConfig(r -> r.functionName(selector)));
                    assertValidation(() -> client.putFunctionRecursionConfig(r -> r.functionName(selector).recursiveLoop("Terminate")));
                }
                for (String setting : List.of("allow", "Unknown", "")) {
                    assertValidation(() -> client.putFunctionRecursionConfig(r -> r.functionName(name).recursiveLoop(setting)));
                }
                assertSetting(client, name, "Allow");
                given().header("Authorization", auth()).get("/2015-03-31/functions/" + name + "/configuration")
                        .then().statusCode(200).body("$", not(hasKey("RecursiveLoop")));
                given().header("Authorization", auth()).get("/2015-03-31/functions/" + name)
                        .then().statusCode(200).body("Configuration", not(hasKey("RecursiveLoop")));
                for (String body : List.of("[]", "null", "{", "{\"RecursiveLoop\":true}", "{\"RecursiveLoop\":{}}")) {
                    given().header("Authorization", auth()).contentType("application/json").body(body)
                            .put("/2024-08-31/functions/" + name + "/recursion-config").then().statusCode(400);
                    assertSetting(client, name, "Allow");
                }
            } finally {
                client.deleteFunction(r -> r.functionName(other));
                client.deleteFunction(r -> r.functionName(name));
            }
            assertThrows(ResourceNotFoundException.class, () -> client.getFunctionRecursionConfig(r -> r.functionName(name)));
            create(client, name, ACCOUNT);
            try {
                assertSetting(client, name, "Terminate");
            } finally {
                client.deleteFunction(r -> r.functionName(name));
            }
        }
    }

    @Test
    void nativeSdkCannotMutateAnotherAccountOrRegionThroughAnArn() throws Exception {
        String otherAccount = "741681401002";
        String name = "recursion-scope-" + UUID.randomUUID();
        try (LambdaClient client = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, REGION);
             LambdaClient foreign = AwsTestClients.create(LambdaClient.builder(), otherAccount, REGION);
             LambdaClient west = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.US_WEST_2)) {
            create(client, name, ACCOUNT);
            String foreignArn = create(foreign, name, otherAccount);
            String westArn = create(west, name, ACCOUNT);
            try {
                client.putFunctionRecursionConfig(r -> r.functionName(name).recursiveLoop("Allow"));
                for (String arn : List.of(foreignArn, westArn)) {
                    assertThrows(ResourceNotFoundException.class, () -> client.getFunctionRecursionConfig(r -> r.functionName(arn)));
                    assertThrows(ResourceNotFoundException.class, () -> client.putFunctionRecursionConfig(r -> r.functionName(arn)
                            .recursiveLoop("Allow")));
                }
                assertSetting(client, name, "Allow");
                assertSetting(foreign, name, "Terminate");
                assertSetting(west, name, "Terminate");
            } finally {
                client.deleteFunction(r -> r.functionName(name));
                foreign.deleteFunction(r -> r.functionName(name));
                west.deleteFunction(r -> r.functionName(name));
            }
        }
    }

    private static String create(LambdaClient client, String name, String account) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => ({});".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return client.createFunction(r -> r.functionName(name).runtime("nodejs22.x").handler("index.handler")
                .role("arn:aws:iam::" + account + ":role/recursion-test")
                .code(c -> c.zipFile(SdkBytes.fromByteArray(bytes.toByteArray())))).functionArn();
    }

    private static void assertSetting(LambdaClient client, String name, String setting) {
        assertEquals(setting, client.getFunctionRecursionConfig(r -> r.functionName(name)).recursiveLoopAsString());
    }

    private static void assertValidation(Runnable request) {
        LambdaException error = assertThrows(LambdaException.class, request::run);
        assertEquals(400, error.statusCode());
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
    }

    private static String auth() {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261112/us-east-1/lambda/aws4_request";
    }
}
