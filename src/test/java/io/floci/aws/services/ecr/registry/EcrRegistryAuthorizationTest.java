package io.floci.aws.services.ecr.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.CallerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EcrRegistryAuthorizationTest {
    private static final String REGION = "us-west-2";
    private static final String ACCOUNT = "111111111111";
    private static final String ARN = "arn:aws:iam::111111111111:user/publisher";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"ecr:*\",\"Resource\":\"*\"}}";
    private static final String RESOURCE = "arn:aws:ecr:us-west-2:137112412989:repository/amazonlinux";
    private AccountAwareStorageBackend<EcrRegistryAuthorization.TokenRecord> store;
    private IamService iam;
    private RequestContext request;
    private EmulatorConfig config;
    private EcrRegistryAuthorization auth;
    private Clock clock;

    @BeforeEach
    void setUp() {
        store = AccountAwareStorageBackend.inMemory(ACCOUNT);
        iam = mock(IamService.class);
        request = mock(RequestContext.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(request.getAccessKeyId()).thenReturn("AKIAISSUED");
        when(request.isSignatureVerifiedFor("AKIAISSUED")).thenReturn(true);
        IamService.CallerArns caller = new IamService.CallerArns(ARN, ARN, "User", "AIDAISSUED", "publisher", false,
                Map.of());
        when(iam.resolveCallerArns("AKIAISSUED")).thenReturn(Optional.of(caller));
        when(iam.resolveCallerContext(eq("AKIAISSUED"))).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(iam.resolveCallerContext(eq("AKIAISSUED"), any())).thenReturn(CallerContext.of(List.of(ALLOW)));
        clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        auth = new EcrRegistryAuthorization(store, iam, new IamPolicyEvaluator(new ObjectMapper()), request, config, clock);
    }

    @Test
    void issuedTokenResolvesCallerAndStoresOnlyDigest() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue(REGION);
        assertEquals(clock.instant().plusSeconds(43200), token.expiresAt());
        assertEquals(ARN, auth.authenticate("Basic " + token.authorizationToken(), REGION).caller().principalArn());
        assertEquals(1, store.keys().size());
        assertTrue(store.keys().iterator().next().matches("[0-9a-f]{64}"));
        assertFalse(store.keys().contains(token.authorizationToken()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"us-east-1", "eu-west-1"})
    void tokenRejectsOtherRegionsWithoutChangingItsCallerOrStoredRecord(String region) {
        EcrRegistryAuthorization.IssuedToken token = auth.issue(REGION);
        clearInvocations(iam);
        assertEquals(401, assertThrows(AwsException.class,
                () -> auth.authenticate("Basic " + token.authorizationToken(), region)).getHttpStatus());
        verifyNoInteractions(iam);
        assertEquals(1, store.keys().size());
        EcrRegistryAuthorization.Identity caller = auth.authenticate("Basic " + token.authorizationToken(), REGION);
        assertEquals(ARN, caller.caller().principalArn());
        assertEquals(REGION, caller.token().region());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Basic garbage", "Bearer wrong", "Basic QVdTOmZsb2Np", "Basic Zm9vOmJhcg=="})
    void rejectsMissingMalformedAndLegacyTokens(String header) {
        assertEquals(401, assertThrows(AwsException.class, () -> auth.authenticate(header, REGION)).getHttpStatus());
        verifyNoInteractions(iam);
    }

    @Test
    void issuanceRequiresVerifiedCallerWithoutRootFallback() {
        when(request.isSignatureVerifiedFor("AKIAISSUED")).thenReturn(false);
        assertEquals(403, assertThrows(AwsException.class, () -> auth.issue(REGION)).getHttpStatus());
        assertTrue(store.isEmpty());
    }

    @Test
    void disabledCompatibilityTokenCannotSurviveEnablingEnforcement() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        EcrRegistryAuthorization.IssuedToken token = auth.issue(REGION);
        assertNotNull(auth.authenticate("Basic " + token.authorizationToken(), REGION));
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        assertEquals(401, assertThrows(AwsException.class,
                () -> auth.authenticate("Basic " + token.authorizationToken(), REGION)).getHttpStatus());
    }

    @Test
    void expiredTokenAndRevokedCredentialAreDenied() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue(REGION);
        EcrRegistryAuthorization later = new EcrRegistryAuthorization(store, iam, new IamPolicyEvaluator(new ObjectMapper()), request,
                config, Clock.offset(clock, Duration.ofHours(12)));
        assertEquals(401, assertThrows(AwsException.class,
                () -> later.authenticate("Basic " + token.authorizationToken(), REGION)).getHttpStatus());
        when(iam.isInactiveAccessKey("AKIAISSUED")).thenReturn(true);
        assertEquals(401, assertThrows(AwsException.class,
                () -> auth.authenticate("Basic " + token.authorizationToken(), REGION)).getHttpStatus());
    }

    @Test
    void crossAccountRequiresResourceGrantAndScpStillDenies() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue(REGION);
        EcrRegistryAuthorization.Identity identity = auth.authenticate("Basic " + token.authorizationToken(), REGION);
        assertThrows(AwsException.class, () -> auth.requirePermission(identity, "ecr:BatchGetImage", RESOURCE, null));
        String grant = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + ARN
                + "\"},\"Action\":\"ecr:BatchGetImage\",\"Resource\":\"*\"}}";
        assertDoesNotThrow(() -> auth.requirePermission(identity, "ecr:BatchGetImage", RESOURCE, grant));
        when(iam.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(
                "{\"Statement\":{\"Effect\":\"Deny\",\"Action\":\"ecr:*\",\"Resource\":\"*\"}}")));
        EcrRegistryAuthorization.Identity restricted = auth.authenticate("Basic " + token.authorizationToken(), REGION);
        assertThrows(AwsException.class, () -> auth.requirePermission(restricted, "ecr:BatchGetImage", RESOURCE, grant));
    }

    @Test
    void tokenReevaluatesCurrentIdentityPolicy() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue(REGION);
        when(iam.resolveCallerContext(eq("AKIAISSUED"), any())).thenReturn(CallerContext.of(List.of(
                "{\"Statement\":{\"Effect\":\"Deny\",\"Action\":\"ecr:PutImage\",\"Resource\":\"*\"}}")));
        EcrRegistryAuthorization.Identity identity = auth.authenticate("Basic " + token.authorizationToken(), REGION);
        assertThrows(AwsException.class, () -> auth.requirePermission(identity, "ecr:PutImage",
                "arn:aws:ecr:us-west-2:111111111111:repository/output", null));
    }

    @Test
    void nativeSkopeoVerbsMapToSpecificActionsAndUnknownFails() {
        String repo = "137112412989/us-west-2/amazonlinux";
        assertEquals(List.of("ecr:BatchGetImage"), EcrRegistryDataPlane.actions("HEAD", "/v2/" + repo + "/manifests/sha256:a", repo));
        assertEquals(List.of("ecr:BatchCheckLayerAvailability"), EcrRegistryDataPlane.actions("HEAD", "/v2/" + repo + "/blobs/sha256:a", repo));
        assertEquals(List.of("ecr:InitiateLayerUpload"), EcrRegistryDataPlane.actions("POST", "/v2/" + repo + "/blobs/uploads/?mount=a", repo));
        assertEquals(List.of("ecr:UploadLayerPart"), EcrRegistryDataPlane.actions("PATCH", "/v2/" + repo + "/blobs/uploads/id", repo));
        assertEquals(List.of("ecr:CompleteLayerUpload"), EcrRegistryDataPlane.actions("PUT", "/v2/" + repo + "/blobs/uploads/id?digest=a", repo));
        assertThrows(AwsException.class, () -> EcrRegistryDataPlane.actions("GET", "/v2/_catalog", ""));
    }
    @Test
    void recreatedPrincipalCannotReviveOldTokenAndCallerAccountIsRestored() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue(REGION);
        clearInvocations(request);
        auth.authenticate("Basic " + token.authorizationToken(), REGION);
        InOrder order = inOrder(request);
        order.verify(request).getAccountId();
        order.verify(request).setAccountId(ACCOUNT);
        order.verify(request).setAccountId(null);
        when(iam.resolveCallerArns("AKIAISSUED")).thenReturn(Optional.of(new IamService.CallerArns(
                ARN, ARN, "User", "AIDARECREATED", "publisher", false, Map.of())));
        assertEquals(401, assertThrows(AwsException.class,
                () -> auth.authenticate("Basic " + token.authorizationToken(), REGION)).getHttpStatus());
    }

    @Test
    void actualTransportAndResourceTagConditionsAreEvaluated() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue(REGION);
        when(iam.resolveCallerContext(eq("AKIAISSUED"), any())).thenReturn(CallerContext.of(List.of(
                "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"ecr:BatchGetImage\",\"Resource\":\"*\","
                        + "\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"true\"},"
                        + "\"StringEquals\":{\"aws:ResourceTag/team\":\"build\"}}}}")));
        EcrRegistryAuthorization.Identity identity = auth.authenticate("Basic " + token.authorizationToken(), REGION);
        String resource = "arn:aws:ecr:us-west-2:111111111111:repository/output";
        assertThrows(AwsException.class, () -> auth.requirePermission(identity, "ecr:BatchGetImage", resource, null));
        assertDoesNotThrow(() -> auth.requirePermission(identity, "ecr:BatchGetImage", resource, null,
                Map.of("aws:SecureTransport", List.of("true"), "aws:ResourceTag/team", List.of("build"))));
    }

    @Test
    void ordinaryStoredSessionCapsExpiryWithoutVerifiedSessionObject() {
        Instant expiry = clock.instant().plusSeconds(900);
        when(iam.findSessionExpiration("AKIAISSUED")).thenReturn(Optional.of(expiry));
        assertEquals(expiry, auth.issue(REGION).expiresAt());
    }

    @Test
    void tokenSurvivesNativePersistenceReloadAndExpiredRowsArePruned(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("registry-tokens.json");
        TypeReference<Map<String, EcrRegistryAuthorization.TokenRecord>> type = new TypeReference<>() {};
        PersistentStorage<String, EcrRegistryAuthorization.TokenRecord> disk = new PersistentStorage<>(file, type);
        AccountAwareStorageBackend<EcrRegistryAuthorization.TokenRecord> durable =
                new AccountAwareStorageBackend<>(disk, null, ACCOUNT);
        EcrRegistryAuthorization writer = new EcrRegistryAuthorization(durable, iam,
                new IamPolicyEvaluator(new ObjectMapper()), request, config, clock);
        EcrRegistryAuthorization.IssuedToken token = writer.issue(REGION);
        assertFalse(Files.readString(file).contains(token.authorizationToken()));
        PersistentStorage<String, EcrRegistryAuthorization.TokenRecord> reloaded = new PersistentStorage<>(file, type);
        reloaded.load();
        AccountAwareStorageBackend<EcrRegistryAuthorization.TokenRecord> reopened =
                new AccountAwareStorageBackend<>(reloaded, null, ACCOUNT);
        EcrRegistryAuthorization reader = new EcrRegistryAuthorization(reopened, iam,
                new IamPolicyEvaluator(new ObjectMapper()), request, config, clock);
        assertEquals(ARN, reader.authenticate("Basic " + token.authorizationToken(), REGION).caller().principalArn());
        assertEquals(401, assertThrows(AwsException.class,
                () -> reader.authenticate("Basic " + token.authorizationToken(), "us-east-1")).getHttpStatus());
        EcrRegistryAuthorization later = new EcrRegistryAuthorization(reopened, iam,
                new IamPolicyEvaluator(new ObjectMapper()), request, config, Clock.offset(clock, Duration.ofHours(13)));
        assertThrows(AwsException.class, () -> later.authenticate("Basic " + token.authorizationToken(), REGION));
        later.issue(REGION);
        assertEquals(1, reopened.keys().size());
    }

    @Test
    void persistedRecordWithoutIssuingRegionRequiresANewToken(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("legacy-tokens.json");
        TypeReference<Map<String, EcrRegistryAuthorization.TokenRecord>> type = new TypeReference<>() {};
        PersistentStorage<String, EcrRegistryAuthorization.TokenRecord> disk = new PersistentStorage<>(file, type);
        EcrRegistryAuthorization writer = new EcrRegistryAuthorization(
                new AccountAwareStorageBackend<>(disk, null, ACCOUNT), iam,
                new IamPolicyEvaluator(new ObjectMapper()), request, config, clock);
        EcrRegistryAuthorization.IssuedToken token = writer.issue(REGION);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode legacy = mapper.readTree(Files.readString(file));
        for (JsonNode record : legacy) {
            ((ObjectNode) record).remove("region");
        }
        Files.writeString(file, mapper.writeValueAsString(legacy));
        PersistentStorage<String, EcrRegistryAuthorization.TokenRecord> reloaded = new PersistentStorage<>(file, type);
        reloaded.load();
        AccountAwareStorageBackend<EcrRegistryAuthorization.TokenRecord> reopened =
                new AccountAwareStorageBackend<>(reloaded, null, ACCOUNT);
        EcrRegistryAuthorization reader = new EcrRegistryAuthorization(reopened, iam,
                new IamPolicyEvaluator(new ObjectMapper()), request, config, clock);
        clearInvocations(iam);
        for (String region : List.of(REGION, "us-east-1")) {
            assertEquals(401, assertThrows(AwsException.class,
                    () -> reader.authenticate("Basic " + token.authorizationToken(), region)).getHttpStatus());
        }
        verifyNoInteractions(iam);
        assertEquals(1, reopened.keys().size());
        EcrRegistryAuthorization.IssuedToken replacement = reader.issue(REGION);
        assertEquals(ARN, reader.authenticate("Basic " + replacement.authorizationToken(), REGION)
                .caller().principalArn());
    }

}
