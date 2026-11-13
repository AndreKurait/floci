package io.floci.aws.services.cloudfront;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.CreateFunctionResponse;
import software.amazon.awssdk.services.cloudfront.model.DescribeFunctionResponse;
import software.amazon.awssdk.services.cloudfront.model.FunctionConfig;
import software.amazon.awssdk.services.cloudfront.model.Tag;
import software.amazon.awssdk.services.cloudfront.model.Tags;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CloudFrontTestFunctionIamIntegrationTest {

    private static final String EVENT = "{\"request\":{\"uri\":\"/allowed\",\"headers\":{}}}";
    private static final String CODE = "function handler(event){return event.request;}";
    private final AwsRequestSigner admin = AwsRequestSigner.signedAs("test", "test", "cloudfront");

    @TestHTTPResource("/")
    URI endpoint;
    @InjectSpy
    DockerCloudFrontFunctionRuntime runtime;
    private CloudFrontClient root;

    @BeforeEach
    void open() {
        root = client(AwsBasicCredentials.create("test", "test"));
    }

    @AfterEach
    void close() {
        root.close();
    }

    @Test
    void absentPermissionRefusesBeforeRuntimeAdmission() throws Exception {
        try (Owned function = create(); SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "cf-test-none-")) {
            refused(user, function);
        }
    }

    @Test
    void exactFunctionAllowDoesNotAuthorizeAnotherFunction() throws Exception {
        try (Owned allowed = create(); Owned other = create(); SignedIamUsers.User user = user(
                statement("Allow", "cloudfront:TestFunction", allowed.arn))) {
            allowed(user, allowed);
            refused(user, other);
        }
    }

    @Test
    void explicitDenyOverridesABroadAllow() throws Exception {
        try (Owned function = create(); SignedIamUsers.User user = user(
                statement("Allow", "cloudfront:*", "*") + "," + statement("Deny", "cloudfront:TestFunction", function.arn))) {
            refused(user, function);
        }
    }

    @Test
    void currentResourceTagsGateBothStagesAndAreReadAgainOnTheNextRequest() throws Exception {
        try (Owned function = create()) {
            root.tagResource(r -> r.resource(function.arn).tags(Tags.builder()
                    .items(Tag.builder().key("owner").value("allowed").build()).build()));
            root.publishFunction(r -> r.name(function.name).ifMatch(function.etag));
            String live = root.describeFunction(r -> r.name(function.name).stage("LIVE")).eTag();
            try (SignedIamUsers.User user = user("{\"Effect\":\"Allow\",\"Action\":\"cloudfront:TestFunction\","
                    + "\"Resource\":\"" + function.arn + "\",\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/owner\":\"allowed\"}}}")) {
                allowed(user, function);
                try (CloudFrontClient caller = client(AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
                    assertNull(caller.testFunction(r -> r.name(function.name).stage("LIVE").ifMatch(live)
                            .eventObject(SdkBytes.fromUtf8String(EVENT))).testResult().functionErrorMessage());
                }
                root.tagResource(r -> r.resource(function.arn).tags(Tags.builder()
                        .items(Tag.builder().key("owner").value("denied").build()).build()));
                refused(user, function);
                root.untagResource(r -> r.resource(function.arn).tagKeys(k -> k.items("owner")));
                refused(user, function);
            }
        }
    }

    @Test
    void aPermissionsBoundaryRemainsACeilingOnTheNewAction() throws Exception {
        try (Owned function = create(); SignedIamUsers.User user = user(statement("Allow", "cloudfront:*", "*"))) {
            String name = "cf-test-boundary-" + UUID.randomUUID().toString().substring(0, 8);
            Response created = SignedIamUsers.iam(endpoint, "CreatePolicy", Map.of("PolicyName", name,
                    "PolicyDocument", policy(statement("Allow", "cloudfront:DescribeFunction", "*"))));
            assertEquals(200, created.statusCode(), created.asString());
            String arn = created.xmlPath().getString("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
            try {
                SignedIamUsers.iam(endpoint, "PutUserPermissionsBoundary", Map.of("UserName", user.name(),
                        "PermissionsBoundary", arn)).then().statusCode(200);
                refused(user, function);
                SignedIamUsers.iam(endpoint, "DeleteUserPermissionsBoundary", Map.of("UserName", user.name()))
                        .then().statusCode(200);
                allowed(user, function);
            } finally {
                SignedIamUsers.iam(endpoint, "DeleteUserPermissionsBoundary", Map.of("UserName", user.name()));
                SignedIamUsers.iam(endpoint, "DeletePolicy", Map.of("PolicyArn", arn)).then().statusCode(200);
            }
        }
    }

    @Test
    void anIssuedStsSessionPolicyCanNarrowTheRolesFunctionPermission() throws Exception {
        try (Owned function = create()) {
            String role = "cf-test-session-" + UUID.randomUUID().toString().substring(0, 8);
            String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},\"Action\":\"sts:AssumeRole\"}]}";
            Response created = SignedIamUsers.iam(endpoint, "CreateRole", Map.of("RoleName", role,
                    "AssumeRolePolicyDocument", trust));
            assertEquals(200, created.statusCode(), created.asString());
            String arn = created.xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
            try {
                SignedIamUsers.iam(endpoint, "PutRolePolicy", Map.of("RoleName", role, "PolicyName", "test",
                        "PolicyDocument", policy(statement("Allow", "cloudfront:TestFunction", function.arn))))
                        .then().statusCode(200);
                try (StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                        .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                    Credentials denied = sts.assumeRole(r -> r.roleArn(arn).roleSessionName("limited")
                            .policy(policy(statement("Allow", "cloudfront:DescribeFunction", "*")))).credentials();
                    try (CloudFrontClient caller = client(AwsSessionCredentials.create(denied.accessKeyId(),
                            denied.secretAccessKey(), denied.sessionToken()))) {
                        clearInvocations(runtime);
                        CloudFrontException error = assertThrows(CloudFrontException.class,
                                () -> caller.testFunction(r -> r.name(function.name).ifMatch(function.etag)
                                        .eventObject(SdkBytes.fromUtf8String(EVENT))));
                        assertEquals(403, error.statusCode());
                        verify(runtime, never()).evaluate(any(), anyString());
                    }
                    Credentials allowed = sts.assumeRole(r -> r.roleArn(arn).roleSessionName("allowed")).credentials();
                    try (CloudFrontClient caller = client(AwsSessionCredentials.create(allowed.accessKeyId(),
                            allowed.secretAccessKey(), allowed.sessionToken()))) {
                        assertTrue(caller.testFunction(r -> r.name(function.name).ifMatch(function.etag)
                                .eventObject(SdkBytes.fromUtf8String(EVENT))).testResult().functionOutput().contains("/allowed"));
                    }
                }
            } finally {
                SignedIamUsers.iam(endpoint, "DeleteRolePolicy", Map.of("RoleName", role, "PolicyName", "test"));
                SignedIamUsers.iam(endpoint, "DeleteRole", Map.of("RoleName", role)).then().statusCode(200);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<Wrong><EventObject>e30=</EventObject></Wrong>",
            "<TestFunctionRequest><EventObject>e30=</EventObject><EventObject>e30=</EventObject></TestFunctionRequest>",
            "<TestFunctionRequest><Stage>LIVE</Stage><Stage>DEVELOPMENT</Stage><EventObject>e30=</EventObject></TestFunctionRequest>",
            "<TestFunctionRequest><Other>e30=</Other></TestFunctionRequest>",
            "<TestFunctionRequest><EventObject><Nested/></EventObject></TestFunctionRequest>",
            "<TestFunctionRequest><EventObject>e30=</EventObject></TestFunctionRequest><Extra/>",
            "<TestFunctionRequest><EventObject>e30=</EventObject></TestFunctionRequest>trailing",
            "<!DOCTYPE TestFunctionRequest [<!ENTITY x SYSTEM 'file:///etc/hostname'>]>"
                    + "<TestFunctionRequest><EventObject>&x;</EventObject></TestFunctionRequest>",
            "<!DOCTYPE TestFunctionRequest [<!ENTITY x SYSTEM 'http://127.0.0.1:1/never'>]>"
                    + "<TestFunctionRequest><EventObject>&x;</EventObject></TestFunctionRequest>"
    })
    void malformedAndExternalEntityXmlNeverAdmitsAnInvocation(String body) throws Exception {
        try (Owned function = create()) {
            clearInvocations(runtime);
            Response response = send(admin, function, body, Map.of());
            assertEquals(400, response.statusCode(), response.asString());
            assertTrue(response.asString().contains("InvalidArgument"));
            verify(runtime, never()).evaluate(any(), anyString());
        }
    }

    @Test
    void decodedEventLimitsUtf8AndTrailingJsonAreEnforcedBeforeAdmission() throws Exception {
        try (Owned function = create()) {
            for (byte[] event : List.of(new byte[] {(byte) 0xc3, (byte) 0x28}, "{}{}".getBytes(StandardCharsets.UTF_8),
                    ("{\"padding\":\"" + "x".repeat(40960) + "\"}").getBytes(StandardCharsets.UTF_8))) {
                clearInvocations(runtime);
                String body = "<TestFunctionRequest><EventObject>" + Base64.getEncoder().encodeToString(event)
                        + "</EventObject></TestFunctionRequest>";
                Response response = send(admin, function, body, Map.of());
                assertEquals(400, response.statusCode(), response.asString());
                verify(runtime, never()).evaluate(any(), anyString());
            }
        }
    }

    private void allowed(SignedIamUsers.User user, Owned function) {
        try (CloudFrontClient caller = client(AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            assertTrue(caller.testFunction(r -> r.name(function.name).ifMatch(function.etag)
                    .eventObject(SdkBytes.fromUtf8String(EVENT))).testResult().functionOutput().contains("/allowed"));
        }
    }

    private void refused(SignedIamUsers.User user, Owned function) {
        try (CloudFrontClient caller = client(AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            clearInvocations(runtime);
            CloudFrontException error = assertThrows(CloudFrontException.class,
                    () -> caller.testFunction(r -> r.name(function.name).ifMatch(function.etag)
                            .eventObject(SdkBytes.fromUtf8String(EVENT))));
            assertEquals(403, error.statusCode());
            assertTrue(error.getMessage().contains("cloudfront:TestFunction"), error.getMessage());
            verify(runtime, never()).evaluate(any(), anyString());
        }
    }

    private SignedIamUsers.User user(String statements) throws Exception {
        return SignedIamUsers.createUser(endpoint, "cf-test-", "test", policy(statements));
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String policy(String statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}";
    }

    private CloudFrontClient client(AwsCredentials credentials) {
        return CloudFrontClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45))).build();
    }

    private Owned create() {
        String name = "iam-test-" + UUID.randomUUID().toString().replace("-", "");
        CreateFunctionResponse created = root.createFunction(r -> r.name(name).functionCode(SdkBytes.fromUtf8String(CODE))
                .functionConfig(FunctionConfig.builder().comment(name).runtime("cloudfront-js-2.0").build()));
        return new Owned(name, created.eTag(), created.functionSummary().functionMetadata().functionARN(),
                created.functionSummary().functionMetadata().createdTime());
    }

    private Response send(AwsRequestSigner signer, Owned function, String body, Map<String, String> extra) throws Exception {
        URI uri = endpoint.resolve("/2020-05-31/function/" + function.name + "/test");
        Map<String, String> headers = new LinkedHashMap<>(extra);
        headers.put("if-match", function.etag);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        RequestSpecification request = given().urlEncodingEnabled(false).headers(headers).contentType("application/xml");
        if (signer != null) {
            request.headers(signer.headersFor("POST", uri, headers, bytes));
        }
        return request.body(bytes).post(uri);
    }

    private final class Owned implements AutoCloseable {
        private final String name;
        private final String etag;
        private final String arn;
        private final Instant created;

        private Owned(String name, String etag, String arn, Instant created) {
            this.name = name;
            this.etag = etag;
            this.arn = arn;
            this.created = created;
        }

        @Override
        public void close() {
            DescribeFunctionResponse current = root.describeFunction(r -> r.name(name).stage("DEVELOPMENT"));
            assertEquals(created, current.functionSummary().functionMetadata().createdTime());
            assertEquals(name, current.functionSummary().functionConfig().comment());
            root.deleteFunction(r -> r.name(name).ifMatch(current.eTag()));
            for (String stage : List.of("DEVELOPMENT", "LIVE")) {
                assertEquals(404, assertThrows(CloudFrontException.class,
                        () -> root.describeFunction(r -> r.name(name).stage(stage))).statusCode());
            }
        }
    }
}
