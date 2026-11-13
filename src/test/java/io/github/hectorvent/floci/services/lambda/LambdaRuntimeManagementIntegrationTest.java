package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetRuntimeManagementConfigResponse;
import software.amazon.awssdk.services.lambda.model.InvalidParameterValueException;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class LambdaRuntimeManagementIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1", "us-gov-west-1"})
    void nativeSdkStoresBothApisInEveryTestedPartition(String region) throws Exception {
        try (Owned fixture = new Owned(endpoint, region)) {
            String pin = fixture.arn.replace(":000000000000:function:" + fixture.name, "::runtime:opaque-build");
            assertEquals("Auto", fixture.get(null).updateRuntimeOnAsString());
            assertEquals(fixture.arn, fixture.get("$LATEST").functionArn());
            assertNull(fixture.get(null).runtimeVersionArn());
            assertEquals("Manual", fixture.lambda.putRuntimeManagementConfig(r -> r.functionName(fixture.name)
                    .updateRuntimeOn("Manual").runtimeVersionArn(pin)).updateRuntimeOnAsString());
            assertEquals(pin, fixture.get(null).runtimeVersionArn());
            assertEquals(pin, fixture.lambda.getRuntimeManagementConfig(r -> r.functionName(fixture.arn))
                    .runtimeVersionArn());
            assertEquals(pin, fixture.lambda.getRuntimeManagementConfig(
                    r -> r.functionName("000000000000:function:" + fixture.name)).runtimeVersionArn());
            fixture.lambda.putRuntimeManagementConfig(r -> r.functionName(fixture.name)
                    .updateRuntimeOn("FunctionUpdate").runtimeVersionArn(pin));
            assertEquals("FunctionUpdate", fixture.get(null).updateRuntimeOnAsString());
            assertNull(fixture.get(null).runtimeVersionArn());
            fixture.lambda.putRuntimeManagementConfig(r -> r.functionName(fixture.name).updateRuntimeOn("Auto"));
            assertEquals("Auto", fixture.get(null).updateRuntimeOnAsString());
        }
    }

    @Test
    void publishedVersionAndAliasSettingsFollowTheDocumentedLocalPolicy() throws Exception {
        try (Owned fixture = new Owned(endpoint, "us-east-1")) {
            fixture.lambda.putRuntimeManagementConfig(r -> r.functionName(fixture.name).updateRuntimeOn("FunctionUpdate"));
            String version = fixture.lambda.publishVersion(r -> r.functionName(fixture.name)).version();
            assertEquals("Auto", fixture.get(version).updateRuntimeOnAsString());
            fixture.lambda.createAlias(r -> r.functionName(fixture.name).name("live").functionVersion(version));
            fixture.lambda.putRuntimeManagementConfig(r -> r.functionName(fixture.name).qualifier("live")
                    .updateRuntimeOn("Manual").runtimeVersionArn(LambdaRuntimeManagementServiceTest.PIN));
            assertEquals("Manual", fixture.get(version).updateRuntimeOnAsString());
            assertEquals(fixture.arn + ":live", fixture.get("live").functionArn());
            assertEquals("FunctionUpdate", fixture.get(null).updateRuntimeOnAsString());
            assertThrows(ResourceNotFoundException.class, () -> fixture.get("$LATEST.PUBLISHED"));
            assertThrows(InvalidParameterValueException.class, () -> fixture.lambda.putRuntimeManagementConfig(
                    r -> r.functionName(fixture.name).updateRuntimeOn("Manual")));
            assertEquals("FunctionUpdate", fixture.get(null).updateRuntimeOnAsString());
            fixture.lambda.deleteAlias(r -> r.functionName(fixture.name).name("live"));
            fixture.lambda.deleteFunction(r -> r.functionName(fixture.name).qualifier(version));
            assertThrows(ResourceNotFoundException.class, () -> fixture.get(version));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{} {}", "[]", "null", "{\"UpdateRuntimeOn\":false}",
            "{\"UpdateRuntimeOn\":\"Auto\",\"RuntimeVersionArn\":{}}"})
    void malformedNativeBodiesAreModeledAndCannotChangeSettings(String body) throws Exception {
        try (Owned fixture = new Owned(endpoint, "us-east-1")) {
            fixture.lambda.putRuntimeManagementConfig(r -> r.functionName(fixture.name).updateRuntimeOn("FunctionUpdate"));
            given().contentType("application/json").body(body)
                    .put("/2021-07-20/functions/" + fixture.name + "/runtime-management-config")
                    .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
            assertEquals("FunctionUpdate", fixture.get(null).updateRuntimeOnAsString());
        }
    }

    static final class Owned implements AutoCloseable {
        final String name = "runtime-" + UUID.randomUUID().toString().substring(0, 12);
        final IamClient iam;
        final LambdaClient lambda;
        final String arn;
        private boolean roleCreated;
        private boolean functionCreated;

        Owned(URI endpoint, String region) throws Exception {
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test"));
            iam = IamClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials).build();
            lambda = LambdaClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials).build();
            try {
                String role = iam.createRole(r -> r.roleName(name).assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                        """)).role().arn();
                roleCreated = true;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                    zip.putNextEntry(new ZipEntry("index.js"));
                    zip.write("exports.handler = async event => event;".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                arn = lambda.createFunction(r -> r.functionName(name).role(role).runtime("nodejs20.x")
                        .handler("index.handler").code(FunctionCode.builder()
                                .zipFile(SdkBytes.fromByteArray(bytes.toByteArray())).build())).functionArn();
                functionCreated = true;
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        GetRuntimeManagementConfigResponse get(String qualifier) {
            return lambda.getRuntimeManagementConfig(r -> r.functionName(name).qualifier(qualifier));
        }

        @Override
        public void close() {
            try {
                if (functionCreated) {
                    lambda.deleteFunction(r -> r.functionName(name));
                    assertThrows(ResourceNotFoundException.class, () -> lambda.getFunction(r -> r.functionName(name)));
                }
            } finally {
                try {
                    if (roleCreated) {
                        iam.deleteRole(r -> r.roleName(name));
                    }
                } finally {
                    lambda.close();
                    iam.close();
                }
            }
        }
    }
}
