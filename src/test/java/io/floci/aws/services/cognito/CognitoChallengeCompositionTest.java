package io.floci.aws.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmService;
import io.floci.aws.services.cognito.model.UserPool;
import io.floci.aws.services.cognito.model.UserPoolClient;
import io.floci.aws.services.cognito.verification.CognitoMessageDispatcher;
import io.floci.aws.services.cognito.verification.VerificationCode;
import io.floci.aws.services.cognito.verification.VerificationCodeService;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.testing.MutableClock;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class CognitoChallengeCompositionTest {
    private static final String EMAIL = "alice@example.com";
    private static final String OTHER_EMAIL = "bob@example.com";
    private static final String PASSWORD = "Perm1234!";
    private static final String DEFINE = "arn:aws:lambda:us-east-1:000000000000:function:define";
    private static final String CREATE = "arn:aws:lambda:us-east-1:000000000000:function:create";
    private static final String VERIFY = "arn:aws:lambda:us-east-1:000000000000:function:verify";
    private final ObjectMapper mapper = new ObjectMapper();
    private MutableClock clock;
    private CognitoService service;
    private CognitoMessageDispatcher dispatcher;
    private LambdaService lambda;
    private UserPool pool;
    private UserPoolClient client;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        lambda = mock(LambdaService.class);
        dispatcher = mock(CognitoMessageDispatcher.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(eq("cognito"), eq("cognito-verification-codes.json"), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        service = new CognitoService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", "cloudfront.net", new RegionResolver("us-east-1", "000000000000"),
                lambda, mock(AcmService.class), new VerificationCodeService(storageFactory, clock),
                dispatcher, null, clock);
        pool = service.createUserPool(Map.of("PoolName", "ChallengeComposition",
                "UsernameAttributes", List.of("email")), "us-east-1");
        for (String email : List.of(EMAIL, OTHER_EMAIL)) {
            service.adminCreateUser(pool.getId(), email,
                    Map.of("email", email, "phone_number", "+15555550123"), null);
            service.adminSetUserPassword(pool.getId(), email, PASSWORD, true);
        }
        assertNotEquals(EMAIL, service.adminGetUser(pool.getId(), EMAIL).getUsername());
        client = service.createUserPoolClient(pool.getId(), "app", false, false, List.of(), List.of());
        client.setExplicitAuthFlows(List.of("ALLOW_USER_PASSWORD_AUTH", "ALLOW_CUSTOM_AUTH"));
    }

    @Test
    void passwordChangeResolvesOnlyTheSessionUsersAlias() {
        service.adminSetUserPassword(pool.getId(), EMAIL, PASSWORD, false);
        Map<String, Object> challenge = login();
        assertEquals("NEW_PASSWORD_REQUIRED", challenge.get("ChallengeName"));
        String session = (String) challenge.get("Session");
        assertDenied("NEW_PASSWORD_REQUIRED", session, Map.of("USERNAME", OTHER_EMAIL, "NEW_PASSWORD", "Next1234!"));
        assertTokens(service.respondToAuthChallenge(client.getClientId(), "NEW_PASSWORD_REQUIRED", session,
                Map.of("USERNAME", EMAIL, "NEW_PASSWORD", "Next1234!")));
    }

    @Test
    void totpSetupAndCodeChallengesResolveOnlyTheSessionUsersAlias() {
        service.setUserPoolMfaConfig(pool.getId(), "ON", true, false);
        Map<String, Object> challenge = login();
        assertEquals("MFA_SETUP", challenge.get("ChallengeName"));
        Map<String, Object> association = service.associateSoftwareToken(null, (String) challenge.get("Session"));
        String secret = (String) association.get("SecretCode");
        Map<String, Object> verified = service.verifySoftwareToken(null, (String) association.get("Session"),
                CognitoTotp.code(secret, clock.instant()));
        String setupSession = (String) verified.get("Session");
        assertDenied("MFA_SETUP", setupSession, Map.of("USERNAME", OTHER_EMAIL));
        assertTokens(service.respondToAuthChallenge(client.getClientId(), "MFA_SETUP", setupSession,
                Map.of("USERNAME", EMAIL)));
        clock.advance(Duration.ofSeconds(30));
        Map<String, Object> later = login();
        assertEquals("SOFTWARE_TOKEN_MFA", later.get("ChallengeName"));
        String session = (String) later.get("Session");
        String code = CognitoTotp.code(secret, clock.instant());
        assertDenied("SOFTWARE_TOKEN_MFA", session,
                Map.of("USERNAME", OTHER_EMAIL, "SOFTWARE_TOKEN_MFA_CODE", code));
        assertTokens(service.respondToAuthChallenge(client.getClientId(), "SOFTWARE_TOKEN_MFA", session,
                Map.of("USERNAME", EMAIL, "SOFTWARE_TOKEN_MFA_CODE", code)));
    }

    @Test
    void smsChallengeResolvesOnlyTheSessionUsersAliasBeforeConsumingItsCode() {
        service.setUserPoolMfaConfig(pool.getId(), "ON", false, true);
        Map<String, Object> challenge = login();
        assertEquals("SMS_MFA", challenge.get("ChallengeName"));
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).dispatch(eq(pool), any(), eq(VerificationCode.Purpose.SMS_MFA),
                code.capture(), eq(List.of("SMS")), any());
        String session = (String) challenge.get("Session");
        assertDenied("SMS_MFA", session, Map.of("USERNAME", OTHER_EMAIL, "SMS_MFA_CODE", code.getValue()));
        assertTokens(service.respondToAuthChallenge(client.getClientId(), "SMS_MFA", session,
                Map.of("USERNAME", EMAIL, "SMS_MFA_CODE", code.getValue())));
    }

    @Test
    void customTriggerLabelSurvivesWhileTheClientAnswersWithCustomChallenge() throws Exception {
        pool.setLambdaConfig(Map.of("DefineAuthChallenge", DEFINE, "CreateAuthChallenge", CREATE,
                "VerifyAuthChallengeResponse", VERIFY));
        when(lambda.invoke(anyString(), eq(DEFINE), any(byte[].class), any())).thenAnswer(call -> {
            JsonNode history = mapper.readTree((byte[]) call.getArgument(2)).path("request").path("session");
            if (history.isEmpty()) {
                return ok(Map.of("challengeName", "COLOUR_QUESTION"));
            }
            assertEquals("COLOUR_QUESTION", history.get(0).path("challengeName").asText());
            assertTrue(history.get(0).path("challengeResult").asBoolean());
            return ok(Map.of("issueTokens", true));
        });
        when(lambda.invoke(anyString(), eq(CREATE), any(byte[].class), any())).thenReturn(ok(Map.of(
                "publicChallengeParameters", Map.of("question", "colour"),
                "privateChallengeParameters", Map.of("answer", "blue"))));
        when(lambda.invoke(anyString(), eq(VERIFY), any(byte[].class), any()))
                .thenReturn(ok(Map.of("answerCorrect", true)));
        Map<String, Object> challenge = service.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                Map.of("USERNAME", EMAIL));
        assertEquals("COLOUR_QUESTION", challenge.get("ChallengeName"));
        String session = (String) challenge.get("Session");
        assertDenied("CUSTOM_CHALLENGE", session, Map.of("USERNAME", OTHER_EMAIL, "ANSWER", "blue"));
        assertTokens(service.respondToAuthChallenge(client.getClientId(), "CUSTOM_CHALLENGE", session,
                Map.of("USERNAME", EMAIL, "ANSWER", "blue")));
    }

    private Map<String, Object> login() {
        return service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", EMAIL, "PASSWORD", PASSWORD));
    }

    private void assertDenied(String challenge, String session, Map<String, String> responses) {
        assertEquals("NotAuthorizedException", assertThrows(AwsException.class,
                () -> service.respondToAuthChallenge(client.getClientId(), challenge, session, responses)).getErrorCode());
    }

    private static void assertTokens(Map<String, Object> result) {
        assertNotNull(((Map<?, ?>) result.get("AuthenticationResult")).get("AccessToken"));
    }

    private InvokeResult ok(Map<String, Object> response) throws Exception {
        return new InvokeResult(200, null, mapper.writeValueAsBytes(Map.of("response", response)), null, "request");
    }
}
