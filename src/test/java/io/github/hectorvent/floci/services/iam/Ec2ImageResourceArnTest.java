package io.github.hectorvent.floci.services.iam;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ec2ImageResourceArnTest {

    @Test
    void copyChecksSourceAndDestinationImagesAndDestinationSnapshots() {
        assertEquals(List.of("arn:aws:ec2:us-east-1::image/ami-12345678",
                        "arn:aws:ec2:eu-west-1::image/*", "arn:aws:ec2:eu-west-1::snapshot/*"),
                resources("CopyImage", "SourceRegion=us-east-1&SourceImageId=ami-12345678", "eu-west-1", "aws"));
    }

    @Test
    void copyPreservesTheSourceRegionAndPartition() {
        assertEquals(List.of("arn:aws-cn:ec2:cn-north-1::image/ami-12345678",
                        "arn:aws-cn:ec2:cn-northwest-1::image/*",
                        "arn:aws-cn:ec2:cn-northwest-1::snapshot/*"),
                resources("CopyImage", "SourceRegion=cn-north-1&SourceImageId=ami-12345678",
                        "cn-northwest-1", "aws-cn"));
    }

    @Test
    void attributePermissionsNameTheImageOrSnapshot() {
        assertEquals(List.of("arn:aws:ec2:eu-west-1::image/ami-12345678"),
                resources("ModifyImageAttribute", "ImageId=ami-12345678", "eu-west-1", "aws"));
        assertEquals(List.of("arn:aws:ec2:eu-west-1::snapshot/snap-12345678"),
                resources("ModifySnapshotAttribute", "SnapshotId=snap-12345678", "eu-west-1", "aws"));
    }

    @Test
    void everyImageAndSnapshotInATagRequestNeedsPermission() {
        assertEquals(List.of("arn:aws:ec2:eu-west-1::image/ami-12345678",
                        "arn:aws:ec2:eu-west-1::snapshot/snap-12345678"),
                resources("CreateTags", "ResourceId.1=ami-12345678&ResourceId.2=snap-12345678",
                        "eu-west-1", "aws"));
    }

    @Test
    void imageGrantCannotAuthorizeAnotherResourceInTheSameTagRequest() {
        assertEquals(List.of("arn:aws:ec2:eu-west-1::image/ami-12345678", "*"),
                resources("CreateTags", "ResourceId.1=ami-12345678&ResourceId.2=i-12345678",
                        "eu-west-1", "aws"));
    }

    @Test
    void describeImagesRemainsAnUnscopedActionEvenWithAnImageId() {
        assertEquals(List.of("*"),
                resources("DescribeImages", "ImageId.1=ami-12345678", "eu-west-1", "aws"));
    }

    @Test
    void theDispatchedActionCannotBeReplacedWithABodySelector() {
        assertEquals(List.of("*"),
                resources("DescribeImages", "Action=CopyImage&SourceRegion=us-east-1&SourceImageId=ami-12345678",
                        "eu-west-1", "aws"));
    }

    private List<String> resources(String operation, String body, String region, String partition) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        Map<String, Object> properties = new HashMap<>();
        when(context.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("/");
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(context.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        when(context.getEntityStream()).thenReturn(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        doAnswer(invocation -> {
            when(context.getEntityStream()).thenReturn(invocation.getArgument(0));
            return null;
        }).when(context).setEntityStream(any());
        when(context.getProperty(any())).thenAnswer(invocation -> properties.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            properties.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(context).setProperty(any(), any());
        return new ResourceArnBuilder().buildResources("ec2", "ec2:" + operation, context,
                region, "111122223333", partition);
    }
}
