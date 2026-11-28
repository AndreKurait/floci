package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.ResourceArnBuilder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SsmSourceSyncResourceArnTest {
    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-us-gov,us-gov-west-1", "aws-cn,cn-north-1", "aws-us-gov,unknown-source-region-1"})
    void matchedOperationsUseSyncNameAndBufferTheWholeBodyRegardlessOfDecoyMembers(String partition, String region)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ResourceArnBuilder builder = new ResourceArnBuilder(mapper);
        String text = "{\"SyncName\":\" named source \",\"SyncType\":\"SyncFromSource\","
                + "\"Name\":\"decoy-parameter\",\"ResourceArn\":\"arn:aws:ssm:us-east-1:111111111111:parameter/decoy\","
                + "\"Action\":\"GetParameter\"}";
        for (String operation : List.of("CreateResourceDataSync", "UpdateResourceDataSync",
                "DeleteResourceDataSync", "ListResourceDataSync")) {
            ContainerRequestContext context = mock(ContainerRequestContext.class);
            UriInfo uriInfo = mock(UriInfo.class);
            when(context.getUriInfo()).thenReturn(uriInfo);
            when(uriInfo.getPath()).thenReturn("/");
            Map<String, Object> properties = new HashMap<>();
            when(context.getProperty(any())).thenAnswer(invocation -> properties.get(invocation.getArgument(0)));
            doAnswer(invocation -> { properties.put(invocation.getArgument(0), invocation.getArgument(1)); return null; })
                    .when(context).setProperty(any(), any());
            when(context.getEntityStream()).thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            doAnswer(invocation -> { when(context.getEntityStream()).thenReturn(invocation.getArgument(0)); return null; })
                    .when(context).setEntityStream(any(InputStream.class));
            when(context.getHeaderString("X-Amz-Target")).thenReturn("AmazonSSM.GetParameter");
            List<String> expected = List.of(operation.startsWith("List") ? "*"
                    : "arn:" + partition + ":ssm:" + region + ":873033370000:resource-data-sync/ named source ");
            assertEquals(expected, builder.buildResources("ssm", "ssm:" + operation, context,
                    region, "873033370000", partition));
            JsonNode actualBody = mapper.readTree(context.getEntityStream());
            assertEquals(mapper.readTree(text), actualBody);
            assertEquals(mapper.readTree(text), properties.get("floci.bufferedJsonBody"));
        }
    }
}
