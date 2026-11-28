package io.floci.aws.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.services.iam.model.IamUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IamBootstrapPrincipalsTest {
    private static final String ACCOUNT = "222222222222";
    private static final String KEY = "AKIAABCDEFGHIJKLMNOP";
    private static final String SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN0123456789";
    @TempDir
    Path directory;
    private final AccountAwareStorageBackend<IamUser> users = AccountAwareStorageBackend.inMemory("111111111111");
    private final AccountAwareStorageBackend<AccessKey> keys = AccountAwareStorageBackend.inMemory("111111111111");
    private final AccountAwareStorageBackend<String> markers = AccountAwareStorageBackend.inMemory("111111111111");

    private String entry(String account, String key) {
        return """
                {"accountId":"%s","partition":"aws","userName":"bootstrap-admin","accessKeyId":"%s",
                 "secretAccessKey":"%s","grant":"AdministratorAccess"}
                """.formatted(account, key, SECRET);
    }

    private Path file(String content) throws Exception {
        Path file = directory.resolve("principals.json");
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private IamBootstrapPrincipals bootstrap(Path file) {
        return new IamBootstrapPrincipals(file.toString(), users, keys, markers);
    }

    @Test
    void seedsOnlyDeclaredAccountsAndKeepsIdentityOnRepeat() throws Exception {
        IamBootstrapPrincipals bootstrap = bootstrap(file("[" + entry(ACCOUNT, KEY) + "]"));
        bootstrap.validateStoredIdentities();
        bootstrap.seed();
        IamUser user = users.getForAccount(ACCOUNT, "bootstrap-admin").orElseThrow();
        assertEquals("arn:aws:iam::222222222222:user/bootstrap-admin", user.getArn());
        assertTrue(users.get("bootstrap-admin").isEmpty());
        assertEquals(SECRET, keys.getForAccount(ACCOUNT, KEY).orElseThrow().getSecretAccessKey());
        bootstrap.validateStoredIdentities();
        bootstrap.seed();
        assertSame(user, users.getForAccount(ACCOUNT, "bootstrap-admin").orElseThrow());
    }

    @Test
    void validatesAllConflictsBeforeCreatingAnyPrincipal() throws Exception {
        keys.putForAccount("333333333333", "AKIAQRSTUVWXYZABCDEF", new AccessKey("AKIAQRSTUVWXYZABCDEF", SECRET, "other"));
        IamBootstrapPrincipals bootstrap = bootstrap(file("[" + entry(ACCOUNT, KEY) + ","
                + entry("444444444444", "AKIAQRSTUVWXYZABCDEF") + "]"));
        assertThrows(IllegalStateException.class, bootstrap::validateStoredIdentities);
        assertTrue(users.getForAccount(ACCOUNT, "bootstrap-admin").isEmpty());
        assertTrue(markers.getForAccount(ACCOUNT, "bootstrap-admin").isEmpty());
    }

    @Test
    void deletedUserIsNotRecreatedAndInactiveKeyIsNotReactivated() throws Exception {
        IamBootstrapPrincipals bootstrap = bootstrap(file("[" + entry(ACCOUNT, KEY) + "]"));
        bootstrap.validateStoredIdentities();
        bootstrap.seed();
        AccessKey key = keys.getForAccount(ACCOUNT, KEY).orElseThrow();
        key.setStatus("Inactive");
        assertThrows(IllegalStateException.class, bootstrap::validateStoredIdentities);
        assertEquals("Inactive", key.getStatus());
        key.setStatus("Active");
        users.deleteForAccount(ACCOUNT, "bootstrap-admin");
        assertThrows(IllegalStateException.class, bootstrap::validateStoredIdentities);
        assertTrue(users.getForAccount(ACCOUNT, "bootstrap-admin").isEmpty());
    }

    @Test
    void changedSecretOrRemovedGrantFailsWithoutOverwrite() throws Exception {
        Path file = file("[" + entry(ACCOUNT, KEY) + "]");
        IamBootstrapPrincipals bootstrap = bootstrap(file);
        bootstrap.validateStoredIdentities();
        bootstrap.seed();
        String originalId = users.getForAccount(ACCOUNT, "bootstrap-admin").orElseThrow().getUserId();
        Files.writeString(file, Files.readString(file).replace(SECRET, new StringBuilder(SECRET).reverse().toString()));
        assertThrows(IllegalStateException.class, () -> bootstrap(file).validateStoredIdentities());
        assertEquals(SECRET, keys.getForAccount(ACCOUNT, KEY).orElseThrow().getSecretAccessKey());
        users.getForAccount(ACCOUNT, "bootstrap-admin").orElseThrow().getAttachedPolicyArns().clear();
        assertThrows(IllegalStateException.class, bootstrap::validateStoredIdentities);
        assertEquals(originalId, users.getForAccount(ACCOUNT, "bootstrap-admin").orElseThrow().getUserId());
    }

    @Test
    void unsafeFileAndMalformedInputFailWithoutLeakingContent() throws Exception {
        Path file = file("[" + entry(ACCOUNT, KEY) + "]");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        assertThrows(IllegalStateException.class, () -> bootstrap(file));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Path link = directory.resolve("link");
        Files.createSymbolicLink(link, file);
        assertThrows(IllegalStateException.class, () -> bootstrap(link));
        Files.writeString(file, "[" + SECRET);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> bootstrap(file));
        assertFalse(error.toString().contains(SECRET));
        assertNull(error.getCause());
        assertFalse(error.toString().contains(directory.toString()));
    }

    @Test
    void rejectsDuplicateKeysNumericKeysAndImplicitGrants() throws Exception {
        assertThrows(IllegalStateException.class, () -> bootstrap(file("[" + entry(ACCOUNT, KEY) + ","
                + entry("333333333333", KEY) + "]")));
        assertThrows(IllegalStateException.class, () -> bootstrap(file("[" + entry(ACCOUNT, ACCOUNT) + "]")));
        assertThrows(IllegalStateException.class, () -> bootstrap(file(("[" + entry(ACCOUNT, KEY) + "]")
                .replace("AdministratorAccess", "admin"))));
    }

    @Test
    void caseVariantCannotRecreateDeletedBootstrapUser() throws Exception {
        Path input = file("[" + entry(ACCOUNT, KEY) + "]");
        IamBootstrapPrincipals bootstrap = bootstrap(input);
        bootstrap.validateStoredIdentities();
        bootstrap.seed();
        users.deleteForAccount(ACCOUNT, "bootstrap-admin");
        Files.writeString(input, Files.readString(input).replace("bootstrap-admin", "Bootstrap-Admin"));
        IamBootstrapPrincipals renamed = bootstrap(input);
        assertThrows(IllegalStateException.class, renamed::validateStoredIdentities);
        assertTrue(users.getForAccount(ACCOUNT, "Bootstrap-Admin").isEmpty());
    }

    @Test
    void partitionIsPreserved() throws Exception {
        IamBootstrapPrincipals bootstrap = bootstrap(file(("[" + entry(ACCOUNT, KEY) + "]")
                .replace("\"aws\"", "\"aws-us-gov\"")));
        bootstrap.validateStoredIdentities();
        bootstrap.seed();
        IamUser user = users.getForAccount(ACCOUNT, "bootstrap-admin").orElseThrow();
        assertEquals("arn:aws-us-gov:iam::222222222222:user/bootstrap-admin", user.getArn());
        assertTrue(user.getAttachedPolicyArns().contains("arn:aws-us-gov:iam::aws:policy/AdministratorAccess"));
    }

    @Test
    void readRejectsReplacementSymlink() throws Exception {
        Path input = file("[]");
        Path replacement = directory.resolve("replacement.json");
        Files.writeString(replacement, "[]");
        Files.delete(input);
        Files.createSymbolicLink(input, replacement);
        assertThrows(IOException.class, () -> IamBootstrapPrincipals.readBytes(input));
    }

    @Test
    void readEnforcesLimitIncludingGrowthAfterMetadataCheck() throws Exception {
        Path input = file(" ".repeat(65536));
        assertEquals(65536, IamBootstrapPrincipals.readBytes(input).length);
        Files.writeString(input, " ".repeat(65537));
        assertThrows(IllegalStateException.class, () -> IamBootstrapPrincipals.readBytes(input));
    }

    @Test
    void canonicalPartitionSurvivesCorrectedSpellingOnRestart() throws Exception {
        Path input = file(("[" + entry(ACCOUNT, KEY) + "]").replace("\"aws\"", "\" AWS-US-GOV \""));
        IamBootstrapPrincipals first = bootstrap(input);
        first.validateStoredIdentities();
        first.seed();
        IamUser user = users.getForAccount(ACCOUNT, "bootstrap-admin").orElseThrow();
        assertEquals("arn:aws-us-gov:iam::222222222222:user/bootstrap-admin", user.getArn());
        assertTrue(user.getAttachedPolicyArns().contains("arn:aws-us-gov:iam::aws:policy/AdministratorAccess"));
        Files.writeString(input, Files.readString(input).replace(" AWS-US-GOV ", "aws-us-gov"));
        IamBootstrapPrincipals restart = bootstrap(input);
        restart.validateStoredIdentities();
        restart.seed();
        assertSame(user, users.getForAccount(ACCOUNT, "bootstrap-admin").orElseThrow());
    }

    @Test
    void persistentPublicDeployerBlocksBootstrapUntilDeactivated() throws Exception {
        Path input = file("[" + entry(ACCOUNT, KEY) + "]");
        Path keyFile = directory.resolve("public-keys.json");
        PersistentStorage<String, AccessKey> store = new PersistentStorage<>(keyFile, new TypeReference<>() {});
        AccountAwareStorageBackend<AccessKey> original = new AccountAwareStorageBackend<>(store, null, ACCOUNT);
        original.putAllDurablyForAccount("333333333333",
                Map.of("floci", new AccessKey("floci", "floci", "floci-deployer")));
        byte[] before = Files.readAllBytes(keyFile);
        PersistentStorage<String, AccessKey> reloaded = new PersistentStorage<>(keyFile, new TypeReference<>() {});
        reloaded.load();
        AccountAwareStorageBackend<AccessKey> persisted = new AccountAwareStorageBackend<>(reloaded, null, ACCOUNT);
        IamBootstrapPrincipals restart = new IamBootstrapPrincipals(input.toString(), users, persisted, markers);
        assertThrows(IllegalStateException.class, restart::validateStoredIdentities);
        assertArrayEquals(before, Files.readAllBytes(keyFile));
        assertTrue(users.getForAccount(ACCOUNT, "bootstrap-admin").isEmpty());
        assertTrue(markers.getForAccount(ACCOUNT, "bootstrap-admin").isEmpty());
        AccessKey publicKey = persisted.getForAccount("333333333333", "floci").orElseThrow();
        publicKey.setStatus("Inactive");
        persisted.putAllDurablyForAccount("333333333333", Map.of("floci", publicKey));
        restart.validateStoredIdentities();
        restart.seed();
        assertEquals("Inactive", persisted.getForAccount("333333333333", "floci").orElseThrow().getStatus());
        assertTrue(users.getForAccount(ACCOUNT, "bootstrap-admin").isPresent());
    }

    @Test
    void persistentRestartPreservesBytesAndDeletionTombstone() throws Exception {
        Path input = file("[" + entry(ACCOUNT, KEY) + "]");
        PersistentStorage<String, IamUser> userStore = new PersistentStorage<>(directory.resolve("users.json"), new TypeReference<>() {});
        PersistentStorage<String, AccessKey> keyStore = new PersistentStorage<>(directory.resolve("keys.json"), new TypeReference<>() {});
        PersistentStorage<String, String> markerStore = new PersistentStorage<>(directory.resolve("markers.json"), new TypeReference<>() {});
        IamBootstrapPrincipals first = new IamBootstrapPrincipals(input.toString(),
                new AccountAwareStorageBackend<>(userStore, null, "111111111111"),
                new AccountAwareStorageBackend<>(keyStore, null, "111111111111"),
                new AccountAwareStorageBackend<>(markerStore, null, "111111111111"));
        first.validateStoredIdentities();
        first.seed();
        byte[] before = Files.readAllBytes(directory.resolve("users.json"));
        byte[] keysBefore = Files.readAllBytes(directory.resolve("keys.json"));
        byte[] markersBefore = Files.readAllBytes(directory.resolve("markers.json"));
        PersistentStorage<String, IamUser> nextUsers = new PersistentStorage<>(directory.resolve("users.json"), new TypeReference<>() {});
        PersistentStorage<String, AccessKey> nextKeys = new PersistentStorage<>(directory.resolve("keys.json"), new TypeReference<>() {});
        PersistentStorage<String, String> nextMarkers = new PersistentStorage<>(directory.resolve("markers.json"), new TypeReference<>() {});
        nextUsers.load(); nextKeys.load(); nextMarkers.load();
        AccountAwareStorageBackend<IamUser> userView = new AccountAwareStorageBackend<>(nextUsers, null, "111111111111");
        IamBootstrapPrincipals restart = new IamBootstrapPrincipals(input.toString(), userView,
                new AccountAwareStorageBackend<>(nextKeys, null, "111111111111"),
                new AccountAwareStorageBackend<>(nextMarkers, null, "111111111111"));
        restart.validateStoredIdentities();
        restart.seed();
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("users.json")));
        assertArrayEquals(keysBefore, Files.readAllBytes(directory.resolve("keys.json")));
        assertArrayEquals(markersBefore, Files.readAllBytes(directory.resolve("markers.json")));
        userView.deleteForAccount(ACCOUNT, "bootstrap-admin");
        assertThrows(IllegalStateException.class, restart::validateStoredIdentities);
    }
}
