package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.LambdaException;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaRecursionConfigIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void readerWriterResourceScopeAndExplicitDenyApplyToNativeSdkRoutes() throws Exception {
        try (LambdaClient admin = AwsTestClients.create(LambdaClient.builder(), "test", Region.US_EAST_1)) {
            String name = "recursion-iam-" + UUID.randomUUID();
            String other = name + "-other";
            String arn = create(admin, name);
            create(admin, other);
            try {
                String allowRead = statement("Allow", "lambda:GetFunctionRecursionConfig", arn);
                try (LambdaClient reader = caller(allowRead)) {
                    for (String selector : List.of(name, "000000000000:function:" + name, arn)) {
                        assertEquals("Terminate", reader.getFunctionRecursionConfig(r -> r.functionName(selector)).recursiveLoopAsString());
                    }
                    denied(() -> reader.getFunctionRecursionConfig(r -> r.functionName(other)));
                    denied(() -> reader.putFunctionRecursionConfig(r -> r.functionName(name).recursiveLoop("Allow")));
                }
                try (LambdaClient writer = caller(statement("Allow", "lambda:PutFunctionRecursionConfig", arn))) {
                    assertEquals("Allow", writer.putFunctionRecursionConfig(r -> r.functionName(arn).recursiveLoop("Allow")).recursiveLoopAsString());
                    denied(() -> writer.getFunctionRecursionConfig(r -> r.functionName(name)));
                    denied(() -> writer.putFunctionRecursionConfig(r -> r.functionName(other).recursiveLoop("Allow")));
                }
                try (LambdaClient denied = caller(statement("Allow", "lambda:*", "*") + ","
                        + statement("Deny", "lambda:PutFunctionRecursionConfig", arn))) {
                    denied(() -> denied.putFunctionRecursionConfig(r -> r.functionName(name).recursiveLoop("Terminate")));
                    denied.putFunctionRecursionConfig(r -> r.functionName(other).recursiveLoop("Allow"));
                }
                assertEquals("Allow", admin.getFunctionRecursionConfig(r -> r.functionName(name)).recursiveLoopAsString());
                assertEquals("Allow", admin.getFunctionRecursionConfig(r -> r.functionName(other)).recursiveLoopAsString());
            } finally {
                admin.deleteFunction(r -> r.functionName(other));
                admin.deleteFunction(r -> r.functionName(name));
            }
        }
    }

    @Test
    void liveResourceTagsGateReadsWritesAndExplicitDenies() throws Exception {
        try (LambdaClient admin = AwsTestClients.create(LambdaClient.builder(), "test", Region.US_EAST_1)) {
            String name = "recursion-tag-" + UUID.randomUUID();
            String other = name + "-other";
            String arn = create(admin, name);
            String otherArn = create(admin, other);
            try {
                admin.tagResource(r -> r.resource(arn).tags(Map.of("Owner", "team-a")));
                admin.tagResource(r -> r.resource(otherArn).tags(Map.of("Owner", "team-b")));
                String statements = """
                        {"Effect":"Allow","Action":["lambda:GetFunctionRecursionConfig","lambda:PutFunctionRecursionConfig"],
                         "Resource":"*","Condition":{"StringEquals":{"aws:ResourceTag/Owner":"team-a"}}},
                        {"Effect":"Deny","Action":"lambda:PutFunctionRecursionConfig","Resource":"*",
                         "Condition":{"StringEquals":{"aws:ResourceTag/Blocked":"yes"}}}
                        """;
                try (LambdaClient caller = caller(statements)) {
                    assertEquals("Terminate", caller.getFunctionRecursionConfig(r -> r.functionName(name)).recursiveLoopAsString());
                    caller.putFunctionRecursionConfig(r -> r.functionName(arn).recursiveLoop("Allow"));
                    assertEquals("Allow", caller.getFunctionRecursionConfig(r -> r.functionName("000000000000:function:" + name))
                            .recursiveLoopAsString());
                    denied(() -> caller.getFunctionRecursionConfig(r -> r.functionName(other)));
                    denied(() -> caller.putFunctionRecursionConfig(r -> r.functionName(other).recursiveLoop("Allow")));
                    denied(() -> caller.putFunctionRecursionConfig(r -> r.functionName(arn.replace("000000000000", "222222222222"))
                            .recursiveLoop("Terminate")));
                    admin.tagResource(r -> r.resource(arn).tags(Map.of("Blocked", "yes")));
                    denied(() -> caller.putFunctionRecursionConfig(r -> r.functionName(name).recursiveLoop("Terminate")));
                    assertEquals("Allow", caller.getFunctionRecursionConfig(r -> r.functionName(name)).recursiveLoopAsString());
                    admin.untagResource(r -> r.resource(arn).tagKeys("Owner"));
                    denied(() -> caller.getFunctionRecursionConfig(r -> r.functionName(name)));
                    assertEquals("Allow", admin.getFunctionRecursionConfig(r -> r.functionName(name)).recursiveLoopAsString());
                    assertEquals("Terminate", admin.getFunctionRecursionConfig(r -> r.functionName(other)).recursiveLoopAsString());
                }
            } finally {
                admin.deleteFunction(r -> r.functionName(other));
                admin.deleteFunction(r -> r.functionName(name));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void emptyAndWhitespaceTagValuesRemainPresentForAllowAndDenyConditions(String value) throws Exception {
        try (LambdaClient admin = AwsTestClients.create(LambdaClient.builder(), "test", Region.US_EAST_1)) {
            String name = "recursion-empty-tag-" + UUID.randomUUID();
            String arn = create(admin, name);
            try {
                admin.tagResource(r -> r.resource(arn).tags(Map.of("Marker", value)));
                String statements = """
                        {"Effect":"Allow","Action":"lambda:GetFunctionRecursionConfig","Resource":"%s",
                         "Condition":{"StringEquals":{"aws:ResourceTag/Marker":"%s"}}},
                        {"Effect":"Allow","Action":"lambda:PutFunctionRecursionConfig","Resource":"%s"},
                        {"Effect":"Deny","Action":"lambda:PutFunctionRecursionConfig","Resource":"%s",
                         "Condition":{"StringEquals":{"aws:ResourceTag/Marker":"%s"}}}
                        """.formatted(arn, value, arn, arn, value);
                try (LambdaClient caller = caller(statements)) {
                    assertEquals("Terminate", caller.getFunctionRecursionConfig(r -> r.functionName(name)).recursiveLoopAsString());
                    denied(() -> caller.putFunctionRecursionConfig(r -> r.functionName(name).recursiveLoop("Allow")));
                    admin.tagResource(r -> r.resource(arn).tags(Map.of("Marker", "changed")));
                    denied(() -> caller.getFunctionRecursionConfig(r -> r.functionName(name)));
                    caller.putFunctionRecursionConfig(r -> r.functionName(name).recursiveLoop("Allow"));
                    assertEquals("Allow", admin.getFunctionRecursionConfig(r -> r.functionName(name)).recursiveLoopAsString());
                }
            } finally {
                admin.deleteFunction(r -> r.functionName(name));
            }
        }
    }

    private LambdaClient caller(String statements) {
        String user = "recursion-caller-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth("test", "iam"), user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("test", "iam"), user, "recursion",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
        return AwsTestClients.create(LambdaClient.builder(), credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1);
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static void denied(Runnable operation) {
        LambdaException error = assertThrows(LambdaException.class, operation::run);
        assertEquals(403, error.statusCode());
    }

    private static String create(LambdaClient client, String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => ({});".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return client.createFunction(r -> r.functionName(name).runtime("nodejs22.x").handler("index.handler")
                .role("arn:aws:iam::000000000000:role/recursion-test")
                .code(c -> c.zipFile(SdkBytes.fromByteArray(bytes.toByteArray())))).functionArn();
    }
}
