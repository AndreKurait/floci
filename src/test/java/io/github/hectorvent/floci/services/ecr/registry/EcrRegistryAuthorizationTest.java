package io.github.hectorvent.floci.services.ecr.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
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
                null, Map.of(), false);
        when(iam.resolveCallerArns("AKIAISSUED")).thenReturn(Optional.of(caller));
        when(iam.resolveCallerContext(eq("AKIAISSUED"))).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(iam.resolveCallerContext(eq("AKIAISSUED"), any())).thenReturn(CallerContext.of(List.of(ALLOW)));
        clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
        auth = new EcrRegistryAuthorization(store, iam, new IamPolicyEvaluator(new ObjectMapper()), request, config, clock);
    }

    @Test
    void issuedTokenResolvesCallerAndStoresOnlyDigest() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue();
        assertEquals(clock.instant().plusSeconds(43200), token.expiresAt());
        assertEquals(ARN, auth.authenticate("Basic " + token.authorizationToken()).caller().principalArn());
        assertEquals(1, store.keys().size());
        assertTrue(store.keys().iterator().next().matches("[0-9a-f]{64}"));
        assertFalse(store.keys().contains(token.authorizationToken()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Basic garbage", "Bearer wrong", "Basic QVdTOmZsb2Np", "Basic Zm9vOmJhcg=="})
    void rejectsMissingMalformedAndLegacyTokens(String header) {
        assertEquals(401, assertThrows(AwsException.class, () -> auth.authenticate(header)).getHttpStatus());
        verifyNoInteractions(iam);
    }

    @Test
    void issuanceRequiresVerifiedCallerWithoutRootFallback() {
        when(request.isSignatureVerifiedFor("AKIAISSUED")).thenReturn(false);
        assertEquals(403, assertThrows(AwsException.class, auth::issue).getHttpStatus());
        assertTrue(store.isEmpty());
    }

    @Test
    void disabledCompatibilityTokenCannotSurviveEnablingEnforcement() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        EcrRegistryAuthorization.IssuedToken token = auth.issue();
        assertNotNull(auth.authenticate("Basic " + token.authorizationToken()));
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        assertEquals(401, assertThrows(AwsException.class,
                () -> auth.authenticate("Basic " + token.authorizationToken())).getHttpStatus());
    }

    @Test
    void expiredTokenAndRevokedCredentialAreDenied() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue();
        EcrRegistryAuthorization later = new EcrRegistryAuthorization(store, iam, new IamPolicyEvaluator(new ObjectMapper()), request,
                config, Clock.offset(clock, Duration.ofHours(12)));
        assertEquals(401, assertThrows(AwsException.class,
                () -> later.authenticate("Basic " + token.authorizationToken())).getHttpStatus());
        when(iam.isInactiveAccessKey("AKIAISSUED")).thenReturn(true);
        assertEquals(401, assertThrows(AwsException.class,
                () -> auth.authenticate("Basic " + token.authorizationToken())).getHttpStatus());
    }

    @Test
    void crossAccountRequiresResourceGrantAndScpStillDenies() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue();
        EcrRegistryAuthorization.Identity identity = auth.authenticate("Basic " + token.authorizationToken());
        assertThrows(AwsException.class, () -> auth.requirePermission(identity, "ecr:BatchGetImage", RESOURCE, null));
        String grant = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + ARN
                + "\"},\"Action\":\"ecr:BatchGetImage\",\"Resource\":\"*\"}}";
        assertDoesNotThrow(() -> auth.requirePermission(identity, "ecr:BatchGetImage", RESOURCE, grant));
        when(iam.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(
                "{\"Statement\":{\"Effect\":\"Deny\",\"Action\":\"ecr:*\",\"Resource\":\"*\"}}")));
        EcrRegistryAuthorization.Identity restricted = auth.authenticate("Basic " + token.authorizationToken());
        assertThrows(AwsException.class, () -> auth.requirePermission(restricted, "ecr:BatchGetImage", RESOURCE, grant));
    }

    @Test
    void tokenReevaluatesCurrentIdentityPolicy() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue();
        when(iam.resolveCallerContext(eq("AKIAISSUED"), any())).thenReturn(CallerContext.of(List.of(
                "{\"Statement\":{\"Effect\":\"Deny\",\"Action\":\"ecr:PutImage\",\"Resource\":\"*\"}}")));
        EcrRegistryAuthorization.Identity identity = auth.authenticate("Basic " + token.authorizationToken());
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
        EcrRegistryAuthorization.IssuedToken token = auth.issue();
        clearInvocations(request);
        auth.authenticate("Basic " + token.authorizationToken());
        InOrder order = inOrder(request);
        order.verify(request).getAccountId();
        order.verify(request).setAccountId(ACCOUNT);
        order.verify(request).setAccountId(null);
        when(iam.resolveCallerArns("AKIAISSUED")).thenReturn(Optional.of(new IamService.CallerArns(
                ARN, ARN, "User", "AIDARECREATED", "publisher", false, null, Map.of(), false)));
        assertEquals(401, assertThrows(AwsException.class,
                () -> auth.authenticate("Basic " + token.authorizationToken())).getHttpStatus());
    }

    @Test
    void actualTransportAndResourceTagConditionsAreEvaluated() {
        EcrRegistryAuthorization.IssuedToken token = auth.issue();
        when(iam.resolveCallerContext(eq("AKIAISSUED"), any())).thenReturn(CallerContext.of(List.of(
                "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"ecr:BatchGetImage\",\"Resource\":\"*\","
                        + "\"Condition\":{\"Bool\":{\"aws:SecureTransport\":\"true\"},"
                        + "\"StringEquals\":{\"aws:ResourceTag/team\":\"build\"}}}}")));
        EcrRegistryAuthorization.Identity identity = auth.authenticate("Basic " + token.authorizationToken());
        String resource = "arn:aws:ecr:us-west-2:111111111111:repository/output";
        assertThrows(AwsException.class, () -> auth.requirePermission(identity, "ecr:BatchGetImage", resource, null));
        assertDoesNotThrow(() -> auth.requirePermission(identity, "ecr:BatchGetImage", resource, null,
                Map.of("aws:SecureTransport", List.of("true"), "aws:ResourceTag/team", List.of("build"))));
    }

    @Test
    void nativeVerifiedSessionBoundsTokenExpiry() {
        SessionCredential session = new SessionCredential();
        session.setExpiration(clock.instant().plusSeconds(60));
        when(request.getVerifiedSession()).thenReturn(session);
        assertEquals(session.getExpiration(), auth.issue().expiresAt());
    }

    @Test
    void ordinaryStoredSessionCapsExpiryWithoutVerifiedSessionObject() {
        Instant expiry = clock.instant().plusSeconds(900);
        when(iam.findSessionExpiration("AKIAISSUED")).thenReturn(Optional.of(expiry));
        assertNull(request.getVerifiedSession());
        assertEquals(expiry, auth.issue().expiresAt());
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
        EcrRegistryAuthorization.IssuedToken token = writer.issue();
        assertFalse(Files.readString(file).contains(token.authorizationToken()));
        PersistentStorage<String, EcrRegistryAuthorization.TokenRecord> reloaded = new PersistentStorage<>(file, type);
        reloaded.load();
        AccountAwareStorageBackend<EcrRegistryAuthorization.TokenRecord> reopened =
                new AccountAwareStorageBackend<>(reloaded, null, ACCOUNT);
        EcrRegistryAuthorization reader = new EcrRegistryAuthorization(reopened, iam,
                new IamPolicyEvaluator(new ObjectMapper()), request, config, clock);
        assertEquals(ARN, reader.authenticate("Basic " + token.authorizationToken()).caller().principalArn());
        EcrRegistryAuthorization later = new EcrRegistryAuthorization(reopened, iam,
                new IamPolicyEvaluator(new ObjectMapper()), request, config, Clock.offset(clock, Duration.ofHours(13)));
        assertThrows(AwsException.class, () -> later.authenticate("Basic " + token.authorizationToken()));
        later.issue();
        assertEquals(1, reopened.keys().size());
    }

}
