package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Derivation vectors, the date window, strict parsing and the fail-closed settings. */
class DerivedSigningKeyServiceTest {

    @TempDir
    Path dir;

    @Test
    void signingLevelMatchesTheAwsDocumentedVector() throws Exception {
        byte[] key = DerivedSigningKeyService.derive("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "20150830", "us-east-1", "iam", "signing");
        assertEquals("c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9",
                SigV4RequestValidator.hexEncode(key));
        assertArrayEquals(SigV4RequestValidator.deriveSigningKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "20150830", "us-east-1", "iam"), key);
    }

    @Test
    void serviceLevelIsTheKeyOneStepBeforeSigning() throws Exception {
        byte[] kService = DerivedSigningKeyService.derive("secret", "20261106", "us-east-1", "example-svc",
                "service");
        assertArrayEquals(DerivedSigningKeyService.derive("secret", "20261106", "us-east-1", "example-svc",
                "signing"), SigV4RequestValidator.hmacSha256(kService, "aws4_request"));
    }

    @Test
    void theDateWindowIsTodayPlusTheNeighbourOnlyNearMidnight() {
        assertEquals(Set.of("20261106"), DerivedSigningKeyService.allowedDates(Instant.parse("2026-11-06T12:00:00Z")));
        assertEquals(Set.of("20261105", "20261106"),
                DerivedSigningKeyService.allowedDates(Instant.parse("2026-11-06T00:14:00Z")));
        assertEquals(Set.of("20261106", "20261107"),
                DerivedSigningKeyService.allowedDates(Instant.parse("2026-11-06T23:46:00Z")));
        assertEquals(Set.of("20261106"), DerivedSigningKeyService.allowedDates(Instant.parse("2026-11-06T00:16:00Z")));
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static final String VALID = "{\"correlationId\":\"c-1\",\"accessKeyId\":\"ASIAAAAAAAAAAAAAAAAA\","
            + "\"sessionToken\":\"t\",\"date\":\"20261106\",\"region\":\"us-east-1\",\"service\":\"example-svc\","
            + "\"level\":\"signing\"}";

    @Test
    void strictParseAcceptsExactlyTheSevenFields() {
        assertEquals("ASIAAAAAAAAAAAAAAAAA", DerivedSigningKeyService.parse(body(VALID)).get("accessKeyId"));
        for (String bad : List.of(
                "{}", "[]", VALID + "{}", VALID.replace("}", ",\"extra\":\"x\"}"),
                VALID.replace("\"level\":\"signing\"", "\"level\":1"),
                VALID.replace("{\"correlationId\":\"c-1\",", "{\"correlationId\":\"c-1\",\"correlationId\":\"c-2\","),
                VALID.replace(",\"sessionToken\":\"t\"", ""),
                VALID.replace("\"sessionToken\":\"t\"", "\"sessionToken\":\"\""),
                VALID.replace("ASIAAAAAAAAAAAAAAAAA", "AKIAAAAAAAAAAAAAAAAA"),
                VALID.replace("20261106", "20261332"), VALID.replace("c-1", "bad id"),
                VALID.replace("us-east-1", "us east 1"))) {
            DerivedSigningKeyService.Refused refused = assertThrows(DerivedSigningKeyService.Refused.class,
                    () -> DerivedSigningKeyService.parse(body(bad)), bad);
            assertEquals(DerivedSigningKeyService.Refusal.INVALID_REQUEST, refused.refusal);
        }
    }

    record Config(boolean enabled, Optional<String> callerAccessKeyId, Optional<String> callerSecretAccessKeyFile,
                  Optional<List<String>> allowedRegions, Optional<List<String>> allowedServices,
                  List<String> allowedLevels) implements EmulatorConfig.DerivedSigningKeysConfig {
    }

    private Path secretFile(String secret, String permissions) throws Exception {
        Path file = Files.createTempFile(dir, "caller", ".secret");
        Files.writeString(file, secret + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        return file;
    }

    private Config config(Path secret, List<String> regions, List<String> services, List<String> levels) {
        return new Config(true, Optional.of(DerivedSigningKeyCallerAuthenticatorTest.CALLER),
                Optional.ofNullable(secret).map(Path::toString), Optional.of(regions), Optional.of(services), levels);
    }

    @Test
    void validSettingsLoadAndNeverPrintTheSecret() throws Exception {
        Path file = secretFile(DerivedSigningKeyCallerAuthenticatorTest.SECRET, "rw-------");
        DerivedSigningKeySettings settings = DerivedSigningKeySettings.from(
                config(file, List.of("us-east-1"), List.of("example-svc"), List.of("signing", "service")),
                true, name -> false);
        assertEquals(DerivedSigningKeyCallerAuthenticatorTest.SECRET, settings.callerSecret());
        assertFalse(settings.toString().contains(DerivedSigningKeyCallerAuthenticatorTest.SECRET));
    }

    @Test
    void everyInvalidSettingRefusesStartupWithoutNamingTheSecret() throws Exception {
        String secret = DerivedSigningKeyCallerAuthenticatorTest.SECRET;
        Path good = secretFile(secret, "rw-------");
        List<Runnable> cases = List.of(
                () -> DerivedSigningKeySettings.from(config(good, List.of("us-east-1"), List.of("svc"),
                        List.of("signing")), false, name -> false),
                () -> DerivedSigningKeySettings.from(new Config(true, Optional.empty(), Optional.of(good.toString()),
                        Optional.of(List.of("us-east-1")), Optional.of(List.of("svc")), List.of("signing")),
                        true, name -> false),
                () -> DerivedSigningKeySettings.from(new Config(true, Optional.of("lowercase-id"),
                        Optional.of(good.toString()), Optional.of(List.of("us-east-1")), Optional.of(List.of("svc")),
                        List.of("signing")), true, name -> false),
                () -> DerivedSigningKeySettings.from(config(null, List.of("us-east-1"), List.of("svc"),
                        List.of("signing")), true, name -> false),
                () -> DerivedSigningKeySettings.from(config(dir.resolve("missing"), List.of("us-east-1"),
                        List.of("svc"), List.of("signing")), true, name -> false),
                () -> DerivedSigningKeySettings.from(config(good, List.of(), List.of("svc"), List.of("signing")),
                        true, name -> false),
                () -> DerivedSigningKeySettings.from(config(good, List.of("mars-east-9"), List.of("svc"),
                        List.of("signing")), true, name -> false),
                () -> DerivedSigningKeySettings.from(config(good, List.of("us-east-1"), List.of(), List.of("signing")),
                        true, name -> false),
                () -> DerivedSigningKeySettings.from(config(good, List.of("us-east-1"), List.of("Svc*"),
                        List.of("signing")), true, name -> false),
                () -> DerivedSigningKeySettings.from(config(good, List.of("us-east-1"), List.of("svc"),
                        List.of("root")), true, name -> false));
        for (Runnable attempt : cases) {
            IllegalStateException error = assertThrows(IllegalStateException.class, attempt::run);
            assertTrue(error.getMessage().contains("floci.services.iam.derived-signing-keys."), error.getMessage());
            assertFalse(error.getMessage().contains(secret));
        }
    }

    @Test
    void aSigningNameFlociServesIsRefused() throws Exception {
        Path file = secretFile(DerivedSigningKeyCallerAuthenticatorTest.SECRET, "rw-------");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> DerivedSigningKeySettings.from(
                config(file, List.of("us-east-1"), List.of("example-svc", "sts"), List.of("signing")),
                true, "sts"::equals));
        assertTrue(error.getMessage().contains("allowed-services"));
    }

    @Test
    void aSymlinkedSecretIsJudgedByItsTarget() throws Exception {
        String secret = DerivedSigningKeyCallerAuthenticatorTest.SECRET;
        Path ownerOnly = Files.createSymbolicLink(dir.resolve("link-ok"), secretFile(secret, "rw-------"));
        assertEquals(secret, DerivedSigningKeySettings.from(config(ownerOnly, List.of("us-east-1"), List.of("svc"),
                List.of("signing")), true, name -> false).callerSecret());
        Path readable = Files.createSymbolicLink(dir.resolve("link-bad"), secretFile(secret, "rw-r--r--"));
        assertThrows(IllegalStateException.class, () -> DerivedSigningKeySettings.from(config(readable,
                List.of("us-east-1"), List.of("svc"), List.of("signing")), true, name -> false));
    }

    @Test
    void aGroupOrWorldReadableOrWeakSecretFileIsRefused() throws Exception {
        String secret = DerivedSigningKeyCallerAuthenticatorTest.SECRET;
        for (Path file : List.of(secretFile(secret, "rw-r-----"), secretFile(secret, "rw----r--"),
                secretFile("a".repeat(40), "rw-------"), secretFile("short", "rw-------"))) {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> DerivedSigningKeySettings.from(
                    config(file, List.of("us-east-1"), List.of("svc"), List.of("signing")), true, name -> false));
            assertTrue(error.getMessage().contains("caller-secret-access-key-file"));
        }
    }

    @Test
    void scopeOutsideTheListsIsRefusedBeforeAnyCredentialLookup() {
        DerivedSigningKeySettings settings = new DerivedSigningKeySettings("AKIDDERIVEDKEYCALLER01",
                DerivedSigningKeyCallerAuthenticatorTest.SECRET, Set.of("us-east-1"), Set.of("example-svc"),
                Set.of("signing"));
        IssuedSessionVerifier unreachable = new IssuedSessionVerifier(null) {
            @Override
            List<Snapshot> eligible(String accessKeyId) {
                throw new AssertionError("a scope refusal must not look up credentials");
            }
        };
        DerivedSigningKeyService service = new DerivedSigningKeyService(unreachable);
        Instant noon = Instant.parse("2026-11-06T12:00:00Z");
        for (Map.Entry<String, String> change : Map.of(
                "\"date\":\"20261106\"", "\"date\":\"20261105\"",
                "\"region\":\"us-east-1\"", "\"region\":\"us-west-2\"",
                "\"service\":\"example-svc\"", "\"service\":\"EXAMPLE-SVC\"",
                "\"level\":\"signing\"", "\"level\":\"service\"").entrySet()) {
            DerivedSigningKeyService.Refused refused = assertThrows(DerivedSigningKeyService.Refused.class,
                    () -> service.derive(settings, body(VALID.replace(change.getKey(), change.getValue())), noon));
            assertEquals(DerivedSigningKeyService.Refusal.SCOPE_NOT_ALLOWED, refused.refusal, change.getValue());
        }
        DerivedSigningKeyService.Refused future = assertThrows(DerivedSigningKeyService.Refused.class,
                () -> service.derive(settings, body(VALID.replace("20261106", "20261113")), noon));
        assertEquals(DerivedSigningKeyService.Refusal.SCOPE_NOT_ALLOWED, future.refusal);
    }

    private static IssuedSessionVerifier.Snapshot snapshot(String account) {
        return new IssuedSessionVerifier.Snapshot("ASSUMED_ROLE", "ASIAAAAAAAAAAAAAAAAA", "secret", "t", account,
                account, "arn:aws:iam::" + account + ":role/r", "AROAEXAMPLE", "s",
                "arn:aws:sts::" + account + ":assumed-role/r/s", "AROAEXAMPLE:s",
                Instant.parse("2026-11-06T13:00:00Z"), false);
    }

    private static DerivedSigningKeyService serviceReturning(List<IssuedSessionVerifier.Snapshot> sessions) {
        return new DerivedSigningKeyService(new IssuedSessionVerifier(null) {
            @Override
            List<Snapshot> eligible(String accessKeyId) {
                return sessions;
            }
        });
    }

    private static final DerivedSigningKeySettings OPEN = new DerivedSigningKeySettings("AKIDDERIVEDKEYCALLER01",
            DerivedSigningKeyCallerAuthenticatorTest.SECRET, Set.of("us-east-1"), Set.of("example-svc"),
            Set.of("signing"));

    @Test
    void exactlyOneEligibleSessionIsRequired() {
        Instant noon = Instant.parse("2026-11-06T12:00:00Z");
        assertEquals("ASSUMED_ROLE", serviceReturning(List.of(snapshot("111111111111")))
                .derive(OPEN, body(VALID), noon).get("credentialKind"));
        for (List<IssuedSessionVerifier.Snapshot> sessions : List.of(List.<IssuedSessionVerifier.Snapshot>of(),
                List.of(snapshot("111111111111"), snapshot("222222222222")))) {
            DerivedSigningKeyService.Refused refused = assertThrows(DerivedSigningKeyService.Refused.class,
                    () -> serviceReturning(sessions).derive(OPEN, body(VALID), noon));
            assertEquals(DerivedSigningKeyService.Refusal.CREDENTIAL_REFUSED, refused.refusal);
        }
    }
}
