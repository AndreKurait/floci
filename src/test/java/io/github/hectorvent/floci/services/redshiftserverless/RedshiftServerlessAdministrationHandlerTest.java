package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.RedshiftIamDbUserResolver;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the metadata-only Redshift Serverless actions through the handler, so both the stored
 * behavior and the response shape the SDKs deserialize are checked.
 */
class RedshiftServerlessAdministrationHandlerTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:123456789012:certificate/11111111-2222-3333-4444-555555555555";

    private final ObjectMapper mapper = new ObjectMapper();
    private RedshiftServerlessJsonHandler handler;
    private RedshiftServerlessService serverlessService;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        for (String file : List.of("namespaces", "workgroups", "snapshots", "usagelimits", "resourcepolicies",
                "endpointaccess", "snapshotcopyconfigurations", "scheduledactions", "customdomains")) {
            when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-" + file + ".json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend)
                AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
        }
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(eq("redshift-serverless"), any(String.class), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:redshift-serverless:"
                        + invocation.getArgument(1, String.class) + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        RedshiftServerlessEndpoints endpoints = mock(RedshiftServerlessEndpoints.class);
        when(endpoints.allocate()).thenReturn(new Endpoint("localhost", 7100));
        RedshiftServerlessRuntime runtime = mock(RedshiftServerlessRuntime.class);
        when(runtime.generatePassword()).thenReturn("Generated123");
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(System.getProperty("java.io.tmpdir"));
        serverlessService =
                new RedshiftServerlessService(storageFactory, regionResolver, endpoints, runtime, config);
        handler = new RedshiftServerlessJsonHandler(serverlessService, mapper, mock(RedshiftIamDbUserResolver.class),
                new RedshiftServerlessUsageLimitService(storageFactory, regionResolver, serverlessService),
                new RedshiftServerlessAdminJsonHandler(mapper,
                        new RedshiftServerlessResourcePolicyService(storageFactory, serverlessService, mapper)));
    }

    @Test
    void resourcePolicyIsStoredPerSnapshotAndDroppedWithIt() {
        String snapshotArn = snapshot("policy-ns", "policy-snap");
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

        JsonNode put = ok("PutResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":"
                + mapper.valueToTree(policy) + "}").get("resourcePolicy");
        assertEquals(snapshotArn, put.get("resourceArn").textValue());
        assertEquals(policy, put.get("policy").textValue());
        assertEquals(policy, ok("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}")
                .get("resourcePolicy").get("policy").textValue());

        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":\"not json\"}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":\"{}\\ngarbage\"}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":\"{}{}\"}"));
        assertEquals("ResourceNotFoundException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID
                        + ":snapshot/missing\",\"policy\":" + mapper.valueToTree(policy) + "}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\",\"policy\":"
                        + mapper.valueToTree(policy) + "}"));
        assertEquals("resourceArn does not identify a snapshot in this account and Region.",
                errorMessage("PutResourcePolicy",
                        "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\",\"policy\":"
                                + mapper.valueToTree(policy) + "}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"policy\":" + mapper.valueToTree(policy) + "}"));
        assertEquals("ValidationException", errorType("PutResourcePolicy",
                "{\"resourceArn\":\"" + snapshotArn + "\"}"));
        assertEquals("ValidationException", errorType("GetResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\"}"));
        assertEquals("resourceArn does not identify a snapshot in this account and Region.",
                errorMessage("GetResourcePolicy",
                        "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\"}"));
        assertEquals("ValidationException", errorType("DeleteResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\"}"));
        assertEquals("resourceArn does not identify a snapshot in this account and Region.",
                errorMessage("DeleteResourcePolicy",
                        "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/x\"}"));
        assertEquals("ValidationException", errorType("GetResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/x\"}"));
        assertEquals("resourceArn does not identify a snapshot in this account and Region.",
                errorMessage("GetResourcePolicy",
                        "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/x\"}"));
        assertEquals("ValidationException", errorType("DeleteResourcePolicy",
                "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/x\"}"));
        assertEquals("resourceArn does not identify a snapshot in this account and Region.",
                errorMessage("DeleteResourcePolicy",
                        "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/x\"}"));
        assertEquals("ValidationException", errorType("GetResourcePolicy", "{}"));
        assertEquals("ValidationException", errorType("DeleteResourcePolicy", "{}"));

        assertEquals(0, ok("DeleteResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}").size());
        assertEquals("ResourceNotFoundException",
                errorType("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}"));

        ok("PutResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\",\"policy\":"
                + mapper.valueToTree(policy) + "}");
        ok("DeleteSnapshot", "{\"snapshotName\":\"policy-snap\"}");
        assertEquals("ResourceNotFoundException",
                errorType("GetResourcePolicy", "{\"resourceArn\":\"" + snapshotArn + "\"}"));
    }

    @Test
    void tracksAreTheTwoAwsReleaseTracks() {
        JsonNode track = ok("GetTrack", "{\"trackName\":\"trailing\"}").get("track");
        assertEquals("trailing", track.get("trackName").textValue());
        assertEquals(0, track.get("updateTargets").size());
        assertEquals("ResourceNotFoundException", errorType("GetTrack", "{\"trackName\":\"nightly\"}"));
        assertEquals("ValidationException", errorType("GetTrack", "{\"trackName\":\"not valid!\"}"));

        assertEquals("ResourceNotFoundException", errorType("GetTrack", "{\"trackName\":\"preview\"}"));
        assertEquals(2, ok("ListTracks", "{}").get("tracks").size());
        JsonNode page = ok("ListTracks", "{\"maxResults\":1}");
        assertEquals("current", page.get("tracks").get(0).get("trackName").textValue());
        String next = page.get("nextToken").textValue();
        JsonNode rest = ok("ListTracks", "{\"nextToken\":\"" + next + "\"}");
        assertEquals(1, rest.get("tracks").size());
        assertEquals("trailing", rest.get("tracks").get(0).get("trackName").textValue());
        assertFalse(rest.has("nextToken"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"maxResults\":\"x\"}"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"maxResults\":101}"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"nextToken\":\"bad-token\"}"));
    }

    @Test
    void managedWorkgroupsAndReservationsAreEmptyBecauseFlociModelsNone() {
        assertEquals(0, ok("ListManagedWorkgroups", "{}").get("managedWorkgroups").size());
        assertEquals("ValidationException", errorType("ListManagedWorkgroups", "{\"sourceArn\":\"nope\"}"));
        assertEquals(0, ok("ListManagedWorkgroups", "{\"sourceArn\":\"arn:aws:glue:us-east-1:123456789012:"
                + "database/db\"}").get("managedWorkgroups").size());
        assertEquals("ValidationException", errorType("ListManagedWorkgroups", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListManagedWorkgroups", "{\"maxResults\":101}"));

        assertEquals(0, ok("ListReservations", "{}").get("reservationsList").size());
        assertEquals("ValidationException", errorType("ListReservations", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListReservations", "{\"maxResults\":101}"));

        assertEquals(0, ok("ListReservationOfferings", "{}").get("reservationOfferingsList").size());
        assertEquals("ValidationException", errorType("ListReservationOfferings", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListReservationOfferings", "{\"maxResults\":101}"));

        assertEquals("ValidationException",
                errorType("ListManagedWorkgroups", "{\"nextToken\":\"bad-token\"}"));
        assertEquals("ValidationException",
                errorType("ListReservations", "{\"nextToken\":\"bad-token\"}"));
        assertEquals("ValidationException",
                errorType("ListReservationOfferings", "{\"nextToken\":\"bad-token\"}"));

        assertEquals("ResourceNotFoundException", errorType("GetReservation", "{\"reservationId\":\"r-1\"}"));
        assertEquals("ValidationException", errorType("GetReservation", "{}"));
        assertEquals("ValidationException", errorType("GetReservation", "{\"reservationId\":\"\"}"));
        assertEquals("ResourceNotFoundException", errorType("GetReservationOffering", "{\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException", errorType("GetReservationOffering", "{}"));
        assertEquals("ValidationException", errorType("GetReservationOffering", "{\"offeringId\":\"\"}"));
        assertEquals("ResourceNotFoundException",
                errorType("CreateReservation", "{\"capacity\":128,\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException", errorType("CreateReservation", "{\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException",
                errorType("CreateReservation", "{\"capacity\":\"nope\",\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException",
                errorType("CreateReservation", "{\"capacity\":128,\"offeringId\":\"o-1\",\"clientToken\":123}"));
    }

    @Test
    void aSnapshotRecreatedUnderTheSameNameDoesNotInheritItsPolicy() {
        String arn = snapshot("recreate-snap-ns", "recreate-snap");
        ok("PutResourcePolicy", "{\"resourceArn\":\"" + arn + "\",\"policy\":\"{}\"}");

        ok("DeleteSnapshot", "{\"snapshotName\":\"recreate-snap\"}");
        ok("CreateSnapshot", "{\"snapshotName\":\"recreate-snap\",\"namespaceName\":\"recreate-snap-ns\"}");

        assertEquals("ResourceNotFoundException", errorType("GetResourcePolicy", "{\"resourceArn\":\"" + arn + "\"}"));
    }

    @Test
    void anUnknownActionIsStillRejected() {
        assertEquals("UnknownOperationException", errorType("NoSuchOperation", "{}"));
    }

    private void namespace(String name) {
        ok("CreateNamespace", "{\"namespaceName\":\"" + name + "\",\"adminUsername\":\"admin\"}");
    }

    private void workgroup(String namespaceName, String workgroupName) {
        namespace(namespaceName);
        ok("CreateWorkgroup", "{\"workgroupName\":\"" + workgroupName + "\",\"namespaceName\":\""
                + namespaceName + "\"}");
    }

    private String snapshot(String namespaceName, String snapshotName) {
        namespace(namespaceName);
        return ok("CreateSnapshot", "{\"snapshotName\":\"" + snapshotName + "\",\"namespaceName\":\""
                + namespaceName + "\"}").get("snapshot").get("snapshotArn").textValue();
    }

    private JsonNode ok(String action, String json) {
        Response response = invoke(action, json);
        assertEquals(200, response.getStatus(), action + " failed: " + response.getEntity());
        return mapper.valueToTree(response.getEntity());
    }

    private String errorType(String action, String json) {
        Response response = invoke(action, json);
        assertEquals(400, response.getStatus(), action + " should be rejected: " + response.getEntity());
        return mapper.valueToTree(response.getEntity()).path("__type").asText(null);
    }

    private String errorMessage(String action, String json) {
        Response response = invoke(action, json);
        assertEquals(400, response.getStatus(), action + " should be rejected: " + response.getEntity());
        return mapper.valueToTree(response.getEntity()).path("message").asText(null);
    }

    private Response invoke(String action, String json) {
        try {
            return handler.handle(action, mapper.readTree(json), REGION);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
