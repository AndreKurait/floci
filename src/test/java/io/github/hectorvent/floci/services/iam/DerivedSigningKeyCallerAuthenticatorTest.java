package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.Digests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static io.github.hectorvent.floci.services.iam.DerivedSigningKeyCallerAuthenticator.Outcome.ACCEPTED;
import static io.github.hectorvent.floci.services.iam.DerivedSigningKeyCallerAuthenticator.Outcome.BUSY;
import static io.github.hectorvent.floci.services.iam.DerivedSigningKeyCallerAuthenticator.Outcome.REFUSED;
import static io.github.hectorvent.floci.services.iam.DerivedSigningKeyCallerAuthenticator.Outcome.REPLAYED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The one-caller SigV4 gate: every refusal class, and the replay set. */
class DerivedSigningKeyCallerAuthenticatorTest {

    static final String CALLER = "AKIDDERIVEDKEYCALLER01";
    static final String SECRET = "Zq8Xv2Lm4Np6Rt0Yw3Ub5Ec7Gi9Ka1Od2Sf4Hj6M";
    private static final String HOST = "localhost:4566";
    private static final String PATH = DerivedSigningKeyController.DERIVE_PATH;
    private static final byte[] BODY = "{\"correlationId\":\"c-1\"}".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    private IamService iam;
    private DerivedSigningKeyCallerAuthenticator authenticator;
    private final DerivedSigningKeySettings settings = new DerivedSigningKeySettings(CALLER, SECRET,
            Set.of("us-east-1"), Set.of("example-svc"), Set.of("signing"));

    @BeforeEach
    void setUp() {
        iam = mock(IamService.class);
        authenticator = new DerivedSigningKeyCallerAuthenticator(iam);
    }

    private Map<String, String> signed(String key, String secret, String token, String region, String service,
                                       Instant at, byte[] body) {
        return DerivedSigningKeyTestSigner.sign(HOST, PATH, body, key, secret, token, region, service, at);
    }

    private Map<String, String> signed() {
        return signed(CALLER, SECRET, null, "us-east-1", "floci", NOW, BODY);
    }

    private DerivedSigningKeyCallerAuthenticator.Outcome check(Map<String, String> headers, byte[] body,
                                                              String query) {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        headers.forEach((name, value) -> raw.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value));
        return authenticator.authenticate(settings, "POST", PATH, query,
                DerivedSigningKeyCallerAuthenticator.headerLookup(raw), body, NOW);
    }

    private DerivedSigningKeyCallerAuthenticator.Outcome check(Map<String, String> headers) {
        return check(headers, BODY, null);
    }

    private static Map<String, String> with(Map<String, String> headers, String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        if (value != null) {
            copy.put(name, value);
        }
        return copy;
    }

    private static String authorization(Map<String, String> headers) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Authorization"))
                .findFirst().orElseThrow().getValue();
    }

    @Test
    void stockSdkSignedRequestFromTheCallerIsAccepted() {
        assertEquals(ACCEPTED, check(signed()));
    }

    @Test
    void anyOtherCorrectlySigningKeyIsRefused() {
        assertEquals(REFUSED, check(signed("AKIAOTHERFLOCIUSER01", SECRET, null, "us-east-1", "floci", NOW, BODY)));
        assertEquals(REFUSED, check(signed("test", "test", null, "us-east-1", "floci", NOW, BODY)));
        assertEquals(REFUSED, check(signed("floci", "floci", null, "us-east-1", "floci", NOW, BODY)));
        assertEquals(REFUSED, check(signed("ASIAAAAAAAAAAAAAAAAA", SECRET, "token", "us-east-1", "floci", NOW, BODY)));
    }

    @Test
    void wrongSecretUnsignedAndMalformedAreRefused() {
        assertEquals(REFUSED, check(signed(CALLER, "x" + SECRET.substring(1), null, "us-east-1", "floci", NOW, BODY)));
        assertEquals(REFUSED, check(with(signed(), "Authorization", null)));
        assertEquals(REFUSED, check(with(signed(), "Authorization", "AWS4-ECDSA-P256-SHA256 Credential=" + CALLER
                + "/20261106/floci/aws4_request, SignedHeaders=host;x-amz-date, Signature=" + "a".repeat(64))));
    }

    @Test
    void scopeMustBeExactlyFlociInAKnownRegion() {
        assertEquals(REFUSED, check(signed(CALLER, SECRET, null, "us-east-1", "iam", NOW, BODY)));
        assertEquals(REFUSED, check(signed(CALLER, SECRET, null, "us-east-1", "FLOCI", NOW, BODY)));
        assertEquals(REFUSED, check(signed(CALLER, SECRET, null, "aws-global", "floci", NOW, BODY)));
        assertEquals(REFUSED, check(signed(CALLER, SECRET, null, "mars-east-9", "floci", NOW, BODY)));
    }

    @Test
    void clockSkewBeyondFifteenMinutesIsRefused() {
        assertEquals(REFUSED, check(signed(CALLER, SECRET, null, "us-east-1", "floci",
                NOW.minus(Duration.ofMinutes(16)), BODY)));
        assertEquals(REFUSED, check(signed(CALLER, SECRET, null, "us-east-1", "floci",
                NOW.plus(Duration.ofMinutes(16)), BODY)));
        assertEquals(ACCEPTED, check(signed(CALLER, SECRET, null, "us-east-1", "floci",
                NOW.minus(Duration.ofMinutes(14)), BODY)));
    }

    @Test
    void theBodyIsAlwaysCoveredAndTheDeclaredHashMustMatchIt() {
        assertEquals(REFUSED, check(signed(), "{\"correlationId\":\"c-2\"}".getBytes(StandardCharsets.UTF_8), null));
        String hash = Digests.sha256Hex(BODY);
        assertEquals(REFUSED, check(with(signed(), "x-amz-content-sha256", "UNSIGNED-PAYLOAD")));
        assertEquals(REFUSED, check(with(signed(), "x-amz-content-sha256", "STREAMING-AWS4-HMAC-SHA256-PAYLOAD")));
        assertEquals(REFUSED, check(with(signed(), "x-amz-content-sha256", hash.toUpperCase())));
        assertEquals(REFUSED, check(with(signed(), "x-amz-content-sha256", "0".repeat(64))));
    }

    @Test
    void aCallerThatSignsAnUnsignedPayloadIsRefusedSoNoBodyCanBeSwapped() {
        Map<String, String> unsigned = DerivedSigningKeyTestSigner.sign(HOST, PATH, BODY, "application/json", CALLER,
                SECRET, null, "us-east-1", "floci", NOW, false);
        assertEquals("UNSIGNED-PAYLOAD", unsigned.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("x-amz-content-sha256")).findFirst().orElseThrow().getValue());
        byte[] swapped = "{\"correlationId\":\"swapped\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(REFUSED, check(unsigned, swapped, null));
        assertEquals(REFUSED, check(unsigned, BODY, null));
    }

    @Test
    void presignedCredentialsEncodingAndTokenHeadersAreRefused() {
        assertEquals(REFUSED, check(signed(), BODY, "X-Amz-Credential=" + CALLER));
        assertEquals(REFUSED, check(with(signed(), "Content-Encoding", "gzip")));
        assertEquals(REFUSED, check(with(signed(), "X-Amz-Security-Token", "token")));
    }

    @Test
    void hostAndDateMustBeSignedAndSingle() {
        Map<String, String> headers = signed();
        String auth = authorization(headers);
        UnaryOperator<String> dropHost = value -> value.replaceFirst("(SignedHeaders=[^,]*?)host;", "$1");
        assertEquals(REFUSED, check(with(headers, "Authorization", dropHost.apply(auth))));
        Map<String, List<String>> raw = new LinkedHashMap<>();
        headers.forEach((name, value) -> raw.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value));
        raw.get("X-Amz-Date").add(headers.get("X-Amz-Date"));
        assertEquals(REFUSED, authenticator.authenticate(settings, "POST", PATH, null,
                DerivedSigningKeyCallerAuthenticator.headerLookup(raw), BODY, NOW));
    }

    @Test
    void aChangedUnsignedHeaderDoesNotChangeTheOutcome() {
        assertEquals(ACCEPTED, check(with(signed(), "X-Unsigned-Extra", "anything")));
    }

    @Test
    void anExactReplayIsRefusedAndAResignedRetrySucceeds() {
        Map<String, String> first = signed();
        assertEquals(ACCEPTED, check(first));
        assertEquals(REPLAYED, check(first));
        assertEquals(ACCEPTED, check(signed(CALLER, SECRET, null, "us-east-1", "floci", NOW.plusSeconds(1), BODY)));
    }

    private DerivedSigningKeyCallerAuthenticator.Outcome checkAt(Map<String, String> headers, byte[] body,
                                                                Instant now) {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        headers.forEach((name, value) -> raw.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value));
        return authenticator.authenticate(settings, "POST", PATH, null,
                DerivedSigningKeyCallerAuthenticator.headerLookup(raw), body, now);
    }

    @Test
    void aSignatureIsRememberedForAsLongAsItIsAcceptable() {
        Map<String, String> first = signed();
        assertEquals(ACCEPTED, checkAt(first, BODY, NOW));
        assertEquals(REPLAYED, checkAt(first, BODY, NOW.plus(Duration.ofMinutes(10))));
        assertEquals(REPLAYED, checkAt(first, BODY, NOW.plus(Duration.ofMinutes(15))));
        assertEquals(REFUSED, checkAt(first, BODY, NOW.plus(Duration.ofMinutes(15)).plusNanos(1)));
    }

    @Test
    void aFullSetRecoversOnceItsEntriesCanNoLongerBeReplayed() {
        for (int i = 0; i < DerivedSigningKeyCallerAuthenticator.REPLAY_CAPACITY; i++) {
            byte[] body = ("{\"correlationId\":\"r-" + i + "\"}").getBytes(StandardCharsets.UTF_8);
            assertEquals(ACCEPTED, check(signed(CALLER, SECRET, null, "us-east-1", "floci", NOW, body), body, null));
        }
        Instant later = NOW.plus(Duration.ofMinutes(16));
        byte[] body = "{\"correlationId\":\"after\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(ACCEPTED, checkAt(signed(CALLER, SECRET, null, "us-east-1", "floci", later, body), body, later));
    }

    @Test
    void aFullReplaySetFailsClosed() {
        for (int i = 0; i < DerivedSigningKeyCallerAuthenticator.REPLAY_CAPACITY; i++) {
            byte[] body = ("{\"correlationId\":\"c-" + i + "\"}").getBytes(StandardCharsets.UTF_8);
            assertEquals(ACCEPTED, check(signed(CALLER, SECRET, null, "us-east-1", "floci", NOW, body), body, null));
        }
        byte[] body = "{\"correlationId\":\"one-more\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(BUSY, check(signed(CALLER, SECRET, null, "us-east-1", "floci", NOW, body), body, null));
    }

    @Test
    void theCallerIdNamingAFlociCredentialRefusesTheCall() {
        when(iam.isKnownAccessKey(CALLER)).thenReturn(true);
        assertEquals(REFUSED, check(signed()));
    }
}
