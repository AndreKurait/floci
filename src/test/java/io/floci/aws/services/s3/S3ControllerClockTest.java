package io.floci.aws.services.s3;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.cloudtrail.CloudTrailService;
import io.floci.aws.services.floci.ui.UiPages;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.model.MultipartUpload;
import io.floci.aws.services.s3.model.Part;
import io.floci.aws.services.sns.SnsQueryHandler;
import io.floci.aws.testing.ManualClock;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The times S3Controller writes itself come from its injected clock. */
class S3ControllerClockTest {

    private static final Instant NOW = Instant.parse("2031-05-06T07:08:09.123Z");

    @Test
    void aDataEventIsStampedFromTheInjectedClock() {
        String bucket = "trail-bucket";
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.listObjectsWithPrefixes(anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

        CurrentVertxRequest currentVertxRequest = mock(CurrentVertxRequest.class, RETURNS_DEEP_STUBS);
        when(currentVertxRequest.getCurrent().request().path()).thenReturn("/" + bucket);

        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/" + bucket));

        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(anyString())).thenReturn(null);

        CloudTrailService cloudTrailService = mock(CloudTrailService.class);
        when(cloudTrailService.hasTrails()).thenReturn(true);

        S3Controller controller = new S3Controller(
                s3Service,
                mock(S3SelectService.class),
                mock(RegionResolver.class),
                mock(SnsQueryHandler.class),
                currentVertxRequest,
                mock(UiPages.class),
                cloudTrailService,
                mock(AccountResolver.class),
                mock(RequestContext.class),
                mock(IamService.class),
                mock(IamEnforcementFilter.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                new ManualClock(NOW));

        Response response = controller.listObjects(bucket, null, null, null, null, null, null, null,
                null, null, null, uriInfo, headers);

        assertEquals(200, response.getStatus());
        ArgumentCaptor<CloudTrailService.S3EventInput> event =
                ArgumentCaptor.forClass(CloudTrailService.S3EventInput.class);
        verify(cloudTrailService).emitS3DataEvent(event.capture());
        assertEquals("ListObjects", event.getValue().eventName());
        assertEquals(NOW.toEpochMilli(), event.getValue().eventTimeMillis());
    }

    @Test
    void anUploadPartCopyResultIsStampedFromTheInjectedClock() {
        String bucket = "dest-bucket";
        String key = "dest-key";
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.uploadPartCopy(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(),
                any(), any(), any(), any(), any())).thenReturn(new Part(1, "\"etag\"", 5L));
        when(s3Service.listParts(bucket, key, "upload-1")).thenReturn(new MultipartUpload(bucket, key, null));

        CurrentVertxRequest currentVertxRequest = mock(CurrentVertxRequest.class, RETURNS_DEEP_STUBS);
        when(currentVertxRequest.getCurrent().request().path()).thenReturn("/" + bucket + "/" + key);

        MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        query.putSingle("uploadId", "upload-1");
        query.putSingle("partNumber", "1");
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(query);
        when(uriInfo.getRequestUri()).thenReturn(
                URI.create("http://localhost/" + bucket + "/" + key + "?uploadId=upload-1&partNumber=1"));

        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(anyString())).thenReturn(null);

        S3Controller controller = new S3Controller(
                s3Service,
                mock(S3SelectService.class),
                mock(RegionResolver.class),
                mock(SnsQueryHandler.class),
                currentVertxRequest,
                mock(UiPages.class),
                mock(CloudTrailService.class),
                mock(AccountResolver.class),
                mock(RequestContext.class),
                mock(IamService.class),
                mock(IamEnforcementFilter.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                new ManualClock(NOW));

        Response response = controller.putObject(bucket, key, null, null, null, "/source-bucket/source-key",
                null, null, null, "upload-1", "1", uriInfo, headers, new ByteArrayInputStream(new byte[0]));

        assertEquals(200, response.getStatus());
        assertThat(response.getEntity().toString(),
                containsString("<LastModified>2031-05-06T07:08:09Z</LastModified>"));
    }
}
