package io.floci.aws.services.s3;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ClosedAccountLookup;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.cloudtrail.CloudTrailService;
import io.floci.aws.services.floci.ui.UiPages;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.model.PutObjectOptions;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.services.sns.SnsQueryHandler;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3PostEncryptionContextTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void postPreservesEncryptionAndRestoresPresentedCredentialsAfterTheWrite(boolean writeFails) {
        String bucket = "post-bucket";
        String keyArn = "arn:aws:kms:us-east-1:111111111111:key/post";
        String credential = "form-key/20261125/us-east-1/s3/aws4_request";
        RequestContext request = new RequestContext();
        request.setAccessKeyId("outer-key");
        request.setSessionToken("outer-token");
        request.setAccountId("111111111111");
        S3Service service = mock(S3Service.class);
        when(service.bucketPartition(bucket)).thenReturn("aws");
        AtomicBoolean wrote = new AtomicBoolean();
        when(service.postObject(eq(bucket), eq("object"), any(byte[].class), anyString(), any(),
                any(PutObjectOptions.class))).thenAnswer(call -> {
                    wrote.set(true);
                    assertEquals("form-key", request.getAccessKeyId());
                    assertEquals("form-token", request.getSessionToken());
                    PutObjectOptions options = call.getArgument(5);
                    assertEquals("aws:kms", options.getServerSideEncryption());
                    assertEquals(keyArn, options.getSseKmsKeyId());
                    if (writeFails) {
                        throw new AwsException("AccessDenied", "KMS refused", 403);
                    }
                    return new S3Object(bucket, "object", new byte[] {1}, "text/plain");
                });
        IamEnforcementFilter authorization = mock(IamEnforcementFilter.class);
        AtomicBoolean authorized = new AtomicBoolean();
        doAnswer(call -> {
            authorized.set(true);
            Map<String, List<String>> conditions = call.getArgument(3);
            assertEquals(List.of("aws:kms"), conditions.get("s3:x-amz-server-side-encryption"));
            assertEquals(List.of(keyArn), conditions.get("s3:x-amz-server-side-encryption-aws-kms-key-id"));
            assertEquals(List.of("AWS4-HMAC-SHA256"), conditions.get("s3:signatureversion"));
            return null;
        }).when(authorization).authorizePostObject(
                eq("AWS4-HMAC-SHA256 Credential=" + credential), eq("arn:aws:s3:::post-bucket/object"),
                eq("us-east-1"), any());
        Instance<ClosedAccountLookup> closed = mock(Instance.class);
        S3Controller controller = new S3Controller(service, mock(S3SelectService.class),
                new RegionResolver("us-east-1", "111111111111"), mock(SnsQueryHandler.class),
                mock(CurrentVertxRequest.class), mock(UiPages.class), mock(CloudTrailService.class),
                new AccountResolver("111111111111"), request, mock(IamService.class), authorization, closed,
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), new S3VirtualHostFilter(), Clock.systemUTC());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", "object");
        fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
        fields.put("x-amz-credential", credential);
        fields.put("x-amz-date", "20261125T120000Z");
        fields.put("x-amz-signature", "signature-not-validated-in-this-configuration");
        fields.put("x-amz-security-token", "form-token");
        fields.put("x-amz-server-side-encryption", "aws:kms");
        fields.put("x-amz-server-side-encryption-aws-kms-key-id", keyArn);
        fields.put("policy", Base64.getEncoder().encodeToString(
                "{\"conditions\":[]}".getBytes(StandardCharsets.UTF_8)));
        String boundary = "post-encryption-context";
        StringBuilder form = new StringBuilder();
        fields.forEach((name, value) -> form.append("--").append(boundary)
                .append("\r\nContent-Disposition: form-data; name=\"").append(name)
                .append("\"\r\n\r\n").append(value).append("\r\n"));
        form.append("--").append(boundary)
                .append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"object\"")
                .append("\r\nContent-Type: text/plain\r\n\r\npayload\r\n--").append(boundary).append("--\r\n");
        UriInfo uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost:4566/post-bucket"));

        try (Response response = controller.handleBucketPost(bucket, "multipart/form-data; boundary=" + boundary,
                mock(HttpHeaders.class), uri, form.toString().getBytes(StandardCharsets.UTF_8))) {
            assertEquals(writeFails ? 403 : 204, response.getStatus());
        }
        assertTrue(authorized.get());
        assertTrue(wrote.get());
        assertEquals("outer-key", request.getAccessKeyId());
        assertEquals("outer-token", request.getSessionToken());
        assertEquals("111111111111", request.getAccountId());
    }
}
