package io.floci.aws.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudtrail.model.DataResource;
import io.floci.aws.services.cloudtrail.model.EventSelector;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.services.iam.model.IamUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code userIdentity} CloudTrail records for each kind of caller: a role session is recorded as
 * AWS records it, as an {@code AssumedRole} with its issuing role, in management and data events alike.
 */
class CloudTrailUserIdentityTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "661100000003";
    private static final String TRAIL = "identity-trail";
    private static final String BUCKET = "identity-source";
    private static final String SESSION_KEY = "role-session-key";
    private static final String USER_KEY = "iam-user-key";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/service/audited-role";
    private static final String SESSION_ARN = "arn:aws:sts::" + ACCOUNT + ":assumed-role/audited-role/reader";
    private static final String ROLE_ID = "AROAEXAMPLEROLEID0001";
    private static final Instant ISSUED = Instant.parse("2026-11-16T06:00:00.750Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IamService iam;
    private CloudTrailService service;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        iam = mock(IamService.class);
        when(iam.resolveCallerArns(SESSION_KEY)).thenReturn(Optional.of(new IamService.CallerArns(
                SESSION_ARN, ROLE_ARN, IamService.CallerArns.TYPE_ASSUMED_ROLE, ROLE_ID + ":reader", null,
                false, Map.of(), new IamService.CallerArns.SessionMfa(false, 0)).withTokenIssueTime(ISSUED)));
        service = new CloudTrailService(factory, new RegionResolver(REGION, ACCOUNT), iam, MAPPER);
        service.createTrail(REGION, TRAIL, "identity-audit-logs", null, null, false, false, false, false);
        service.startLogging(REGION, TRAIL);
    }

    private JsonNode managementIdentity(String accessKeyId) {
        return service.emitManagementEvent(new CloudTrailService.ManagementEventInput(
                "secretsmanager.amazonaws.com", "PutSecretValue", REGION, accessKeyId,
                MAPPER.createObjectNode().put("secretId", "example"),
                MAPPER.createObjectNode().put("arn", "arn:aws:secretsmanager:" + REGION + ":" + ACCOUNT
                        + ":secret:example"))).orElseThrow().path("userIdentity");
    }

    private static void assertAssumedRole(JsonNode identity) {
        assertEquals("AssumedRole", identity.path("type").asText(), identity.toString());
        assertEquals(ROLE_ID + ":reader", identity.path("principalId").asText());
        assertEquals(SESSION_ARN, identity.path("arn").asText());
        assertEquals(ACCOUNT, identity.path("accountId").asText());
        assertEquals(SESSION_KEY, identity.path("accessKeyId").asText());
        JsonNode issuer = identity.path("sessionContext").path("sessionIssuer");
        assertEquals("Role", issuer.path("type").asText());
        assertEquals(ROLE_ID, issuer.path("principalId").asText());
        assertEquals(ROLE_ARN, issuer.path("arn").asText());
        assertEquals(ACCOUNT, issuer.path("accountId").asText());
        assertEquals("audited-role", issuer.path("userName").asText());
        JsonNode attributes = identity.path("sessionContext").path("attributes");
        assertEquals("2026-11-16T06:00:00Z", attributes.path("creationDate").asText());
        assertEquals("false", attributes.path("mfaAuthenticated").asText());
        assertTrue(identity.path("userName").isMissingNode(), "an assumed role has no userName: " + identity);
    }

    @Test
    void aRoleSessionsManagementEventRecordsTheAssumedRoleIdentity() {
        assertAssumedRole(managementIdentity(SESSION_KEY));
    }

    @Test
    void theSessionMfaStateIsRecordedAsCloudTrailsStringAttribute() {
        when(iam.resolveCallerArns(SESSION_KEY)).thenReturn(Optional.of(new IamService.CallerArns(
                SESSION_ARN, ROLE_ARN, IamService.CallerArns.TYPE_ASSUMED_ROLE, ROLE_ID + ":reader", null,
                false, Map.of(), new IamService.CallerArns.SessionMfa(true, 12)).withTokenIssueTime(ISSUED)));
        assertEquals("true", managementIdentity(SESSION_KEY).path("sessionContext").path("attributes")
                .path("mfaAuthenticated").asText());
    }

    @Test
    void aRoleSessionsS3DataEventRecordsTheAssumedRoleIdentity() {
        service.putEventSelectors(REGION, TRAIL, List.of(new EventSelector("All", false,
                List.of(new DataResource("AWS::S3::Object", List.of("arn:aws:s3:::" + BUCKET + "/"))), List.of())));
        service.emitS3DataEvent(CloudTrailService.S3EventInput.builder()
                .region(REGION).eventName("GetObject").bucketName(BUCKET).key("blocks/0")
                .accessKeyId(SESSION_KEY).bytesOut(5).eventTimeMillis(ISSUED.toEpochMilli()).build());
        List<ObjectNode> records = service.drainPendingRecords(new CloudTrailService.TrailKey(REGION, TRAIL, REGION));
        assertEquals(1, records.size(), records.toString());
        assertEquals("GetObject", records.getFirst().path("eventName").asText());
        assertAssumedRole(records.getFirst().path("userIdentity"));
    }

    @Test
    void anIamUsersKeyStillRecordsTheIamUserIdentity() {
        when(iam.findAccessKey(USER_KEY)).thenReturn(Optional.of(new AccessKey(USER_KEY, "secret", "alice")));
        when(iam.findUser("alice")).thenReturn(Optional.of(
                new IamUser("AIDAEXAMPLEUSERID0001", "alice", "/", "arn:aws:iam::" + ACCOUNT + ":user/alice")));
        JsonNode identity = managementIdentity(USER_KEY);
        assertEquals("IAMUser", identity.path("type").asText(), identity.toString());
        assertEquals("AIDAEXAMPLEUSERID0001", identity.path("principalId").asText());
        assertEquals("arn:aws:iam::" + ACCOUNT + ":user/alice", identity.path("arn").asText());
        assertEquals("alice", identity.path("userName").asText());
        assertTrue(identity.path("sessionContext").isMissingNode(), identity.toString());
        verify(iam, never()).resolveCallerArns(USER_KEY);
    }

    @Test
    void aKeyIamDoesNotKnowKeepsTheAnonymousIdentity() {
        JsonNode identity = managementIdentity("unknown-key");
        assertEquals("IAMUser", identity.path("type").asText(), identity.toString());
        assertEquals("anonymous", identity.path("userName").asText());
        assertEquals("arn:aws:iam::" + ACCOUNT + ":user/anonymous", identity.path("arn").asText());
        assertTrue(identity.path("sessionContext").isMissingNode(), identity.toString());
    }

    @Test
    void theLegacyRootStandInKeepsItsExistingIdentity() {
        JsonNode identity = managementIdentity("test");
        assertEquals("IAMUser", identity.path("type").asText());
        assertEquals("root", identity.path("userName").asText());
        assertEquals("arn:aws:iam::" + ACCOUNT + ":root", identity.path("arn").asText());
        assertTrue(identity.path("sessionContext").isMissingNode());
        verify(iam, never()).resolveCallerArns("test");
    }
}
