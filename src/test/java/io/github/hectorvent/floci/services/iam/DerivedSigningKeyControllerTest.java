package io.github.hectorvent.floci.services.iam;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How the controller maps each caller outcome: a full replay set must never fall through to a key. */
class DerivedSigningKeyControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final String PATH = DerivedSigningKeyController.DERIVE_PATH;
    private final DerivedSigningKeySettings settings = new DerivedSigningKeySettings(
            DerivedSigningKeyCallerAuthenticatorTest.CALLER, DerivedSigningKeyCallerAuthenticatorTest.SECRET,
            Set.of("us-east-1"), Set.of("example-svc"), Set.of("signing"));
    private final IamService iam = mock(IamService.class);
    private final DerivedSigningKeyCallerAuthenticator authenticator = new DerivedSigningKeyCallerAuthenticator(iam);
    private final DerivedSigningKeyController controller;

    DerivedSigningKeyControllerTest() {
        DerivedSigningKeyEndpoint endpoint = mock(DerivedSigningKeyEndpoint.class);
        when(endpoint.settings()).thenReturn(Optional.of(settings));
        IssuedSessionVerifier noSessions = new IssuedSessionVerifier(null) {
            @Override
            List<Snapshot> eligible(String accessKeyId) {
                return List.of();
            }
        };
        controller = new DerivedSigningKeyController(endpoint, authenticator, new DerivedSigningKeyService(noSessions),
                null, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Map<String, String> signed(byte[] body) {
        return DerivedSigningKeyTestSigner.sign("localhost:4566", PATH, body,
                DerivedSigningKeyCallerAuthenticatorTest.CALLER, DerivedSigningKeyCallerAuthenticatorTest.SECRET, null,
                "us-east-1", "floci", NOW);
    }

    private Response call(byte[] body, Map<String, String> signedHeaders) {
        MultivaluedMap<String, String> raw = new MultivaluedHashMap<>();
        signedHeaders.forEach(raw::add);
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(raw);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:4566" + PATH));
        return controller.derive(new ByteArrayInputStream(body), headers, uriInfo);
    }

    private static String code(Response response) {
        return ((Map<?, ?>) response.getEntity()).get("code").toString();
    }

    private static byte[] body(String correlationId) {
        return ("{\"correlationId\":\"" + correlationId + "\",\"accessKeyId\":\"ASIAAAAAAAAAAAAAAAAA\","
                + "\"sessionToken\":\"t\",\"date\":\"20261106\",\"region\":\"us-east-1\","
                + "\"service\":\"example-svc\",\"level\":\"signing\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void eachCallerOutcomeHasItsOwnAnswer() {
        byte[] body = body("c-1");
        Map<String, String> headers = signed(body);
        Response first = call(body, headers);
        assertEquals(403, first.getStatus());
        assertEquals("DerivedSigningKeyRefused", code(first), "an authenticated caller reaches the credential check");
        Response replay = call(body, headers);
        assertEquals(403, replay.getStatus());
        assertEquals("DerivedSigningKeyReplayed", code(replay));
        Map<String, String> wrong = new LinkedHashMap<>(headers);
        wrong.put("Authorization", headers.get("Authorization").replaceAll("Signature=[0-9a-f]+", "Signature="
                + "0".repeat(64)));
        assertEquals("DerivedSigningKeyCallerRefused", code(call(body, wrong)));
    }

    @Test
    void aFullReplaySetAnswers429AndNeverReachesAKey() {
        for (int i = 0; i < DerivedSigningKeyCallerAuthenticator.REPLAY_CAPACITY; i++) {
            byte[] filler = body("fill-" + i);
            Map<String, List<String>> raw = new LinkedHashMap<>();
            signed(filler).forEach((name, value) -> raw.put(name, List.of(value)));
            assertEquals(DerivedSigningKeyCallerAuthenticator.Outcome.ACCEPTED, authenticator.authenticate(settings,
                    "POST", PATH, null, DerivedSigningKeyCallerAuthenticator.headerLookup(raw), filler, NOW));
        }
        byte[] body = body("one-more");
        Response response = call(body, signed(body));
        assertEquals(429, response.getStatus());
        assertEquals("DerivedSigningKeyRateExceeded", code(response));
        assertEquals("no-store", String.valueOf(response.getHeaders().getFirst("Cache-Control")));
    }

    @Test
    void anExceptionWhileAuthenticatingIsARefusalNotAServerError() {
        when(iam.isKnownAccessKey(DerivedSigningKeyCallerAuthenticatorTest.CALLER))
                .thenThrow(new IllegalStateException("registry limit"));
        byte[] body = body("c-boom");
        Response response = call(body, signed(body));
        assertEquals(403, response.getStatus());
        assertEquals("DerivedSigningKeyCallerRefused", code(response));
    }
}
