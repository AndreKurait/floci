package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamSessionRetirementStorageTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111122223333";
    private static final String ACCESS_KEY = "ASIARETIREMENTFIXTURE";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void retirementPreservesOtherAccountAndSurvivesReload(String mode) {
        EmulatorConfig config = StorageFactories.config(directory, DEFAULT_ACCOUNT);
        StorageFactory factory = StorageFactories.open(config, mode);
        try {
            IamService iam = new IamService(factory, config,
                    new RegionResolver("us-east-1", DEFAULT_ACCOUNT), Clock.systemUTC());
            AccountAwareStorageBackend<SessionCredential> sessions = factory.create(
                    "iam", "iam-sessions.json", new TypeReference<>() {});
            iam.registerLambdaExecutionRoleSession(DEFAULT_ACCOUNT, ACCESS_KEY, "fixture-secret",
                    "arn:aws:iam::000000000000:role/default");
            iam.registerLambdaExecutionRoleSession(OTHER_ACCOUNT, ACCESS_KEY, "fixture-secret",
                    "arn:aws:iam::111122223333:role/other");

            iam.unregisterSession(OTHER_ACCOUNT, null);
            iam.unregisterSession(OTHER_ACCOUNT, " ");
            assertTrue(sessions.getForAccount(OTHER_ACCOUNT, ACCESS_KEY).isPresent());
            iam.unregisterSession(OTHER_ACCOUNT, ACCESS_KEY);
            iam.unregisterSession(OTHER_ACCOUNT, ACCESS_KEY);

            assertTrue(sessions.getForAccount(OTHER_ACCOUNT, ACCESS_KEY).isEmpty());
            assertTrue(sessions.getForAccount(DEFAULT_ACCOUNT, ACCESS_KEY).isPresent());
        } finally {
            factory.shutdownAll();
        }
        if (!"memory".equals(mode)) {
            StorageFactory reopened = StorageFactories.open(config, mode);
            try {
                IamService iam = new IamService(reopened, config,
                        new RegionResolver("us-east-1", DEFAULT_ACCOUNT), Clock.systemUTC());
                AccountAwareStorageBackend<SessionCredential> sessions = reopened.create(
                        "iam", "iam-sessions.json", new TypeReference<>() {});
                assertTrue(sessions.getForAccount(OTHER_ACCOUNT, ACCESS_KEY).isEmpty());
                assertTrue(sessions.getForAccount(DEFAULT_ACCOUNT, ACCESS_KEY).isPresent());
                iam.unregisterSession(null, ACCESS_KEY);
                assertTrue(sessions.getForAccount(DEFAULT_ACCOUNT, ACCESS_KEY).isEmpty());
            } finally {
                reopened.shutdownAll();
            }
        }
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedPersistentRetirementPreservesSessionUntilRetry(boolean explicitAccount) throws Exception {
        EmulatorConfig config = StorageFactories.config(directory, DEFAULT_ACCOUNT);
        StorageFactory factory = StorageFactories.open(config, "persistent");
        Path obstruction = directory.resolve("iam-sessions.json.tmp");
        String account = explicitAccount ? OTHER_ACCOUNT : DEFAULT_ACCOUNT;
        try {
            IamService iam = new IamService(factory, config,
                    new RegionResolver("us-east-1", DEFAULT_ACCOUNT), Clock.systemUTC());
            AccountAwareStorageBackend<SessionCredential> sessions = factory.create(
                    "iam", "iam-sessions.json", new TypeReference<>() {});
            iam.registerLambdaExecutionRoleSession(account, ACCESS_KEY, "fixture-secret",
                    "arn:aws:iam::" + account + ":role/retirement");
            Path snapshot = directory.resolve("iam-sessions.json");
            byte[] before = Files.readAllBytes(snapshot);
            Files.createDirectory(obstruction);

            assertThrows(UncheckedIOException.class,
                    () -> iam.unregisterSession(explicitAccount ? account : null, ACCESS_KEY));

            assertArrayEquals(before, Files.readAllBytes(snapshot));
            assertTrue(sessions.getForAccount(account, ACCESS_KEY).isPresent());
            Files.delete(obstruction);
            iam.unregisterSession(explicitAccount ? account : null, ACCESS_KEY);
            assertTrue(sessions.getForAccount(account, ACCESS_KEY).isEmpty());
        } finally {
            Files.deleteIfExists(obstruction);
            factory.shutdownAll();
        }
        StorageFactory reopened = StorageFactories.open(config, "persistent");
        try {
            AccountAwareStorageBackend<SessionCredential> sessions = reopened.create(
                    "iam", "iam-sessions.json", new TypeReference<>() {});
            assertTrue(sessions.getForAccount(account, ACCESS_KEY).isEmpty());
        } finally {
            reopened.shutdownAll();
        }
    }

}
