package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamTrustPrincipalBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String ACCOUNT = "000000000000";
    private static final String OTHER = "111122223333";
    private static final String ROOT = "arn:aws:iam::" + ACCOUNT + ":root";
    private static final String EMPTY = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void deletedIdsSurviveRecreationAndStorageReload(String mode) {
        String stored;
        String targetId;
        String originalRoleId;
        String originalUserId;
        try (Fixture first = new Fixture(directory, mode)) {
            IamRole source = role(first.iam, "source", trust(ROOT));
            IamUser user = first.iam.createUser("user", "/team/");
            IamRole target = role(first.iam, "target", trust(source.getArn(), user.getArn()));
            originalRoleId = source.getRoleId();
            originalUserId = user.getUserId();
            targetId = target.getRoleId();
            stored = trust(originalRoleId, originalUserId);
            assertPolicy(stored, target.getAssumeRolePolicyDocument());
            assertPolicy(trust(source.getArn(), user.getArn()),
                    first.iam.renderAssumeRolePolicy(target.getAssumeRolePolicyDocument()));
            first.iam.deleteRole("source");
            first.iam.deleteUser("user");
            IamRole replacement = role(first.iam, "source", trust(ROOT));
            IamUser replacementUser = first.iam.createUser("user", "/team/");
            assertNotEquals(originalRoleId, replacement.getRoleId());
            assertNotEquals(originalUserId, replacementUser.getUserId());
            assertPolicy(stored, first.iam.renderAssumeRolePolicy(target.getAssumeRolePolicyDocument()));
            rejectStaleAndPreserve(first.iam, originalRoleId, originalUserId);
        }
        if (!"memory".equals(mode)) {
            try (Fixture reopened = new Fixture(directory, mode)) {
                IamRole target = reopened.iam.getRole("target");
                assertEquals(targetId, target.getRoleId());
                assertPolicy(stored, target.getAssumeRolePolicyDocument());
                assertPolicy(stored, reopened.iam.renderAssumeRolePolicy(target.getAssumeRolePolicyDocument()));
                rejectStaleAndPreserve(reopened.iam, originalRoleId, originalUserId);
                IamRole currentRole = reopened.iam.getRole("source");
                IamUser currentUser = reopened.iam.getUser("user");
                reopened.iam.updateAssumeRolePolicy("target", trust(currentRole.getArn(), currentUser.getArn()));
                assertPolicy(trust(currentRole.getRoleId(), currentUser.getUserId()),
                        reopened.iam.getRole("target").getAssumeRolePolicyDocument());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void liveIdsResolveAfterReloadAndUserRename(String mode) {
        String sourceId;
        String userId;
        try (Fixture first = new Fixture(directory, mode)) {
            IamRole source = role(first.iam, "source", trust(ROOT));
            IamUser user = first.iam.createUser("user", "/team/");
            sourceId = source.getRoleId();
            userId = user.getUserId();
            role(first.iam, "target", trust(sourceId, userId));
        }
        try (Fixture reopened = new Fixture(directory, mode)) {
            IamRole source = reopened.iam.getRole("source");
            IamUser user = reopened.iam.getUser("user");
            assertEquals(sourceId, source.getRoleId());
            assertEquals(userId, user.getUserId());
            String raw = reopened.iam.getRole("target").getAssumeRolePolicyDocument();
            assertPolicy(trust(sourceId, userId), raw);
            assertPolicy(trust(source.getArn(), user.getArn()), reopened.iam.renderAssumeRolePolicy(raw));
            reopened.iam.updateUser("user", "renamed", "/renamed/");
            assertEquals(userId, reopened.iam.getUser("renamed").getUserId());
            assertPolicy(trust(source.getArn(), reopened.iam.getUser("renamed").getArn()),
                    reopened.iam.renderAssumeRolePolicy(raw));
            assertEquals(raw, reopened.iam.getRole("target").getAssumeRolePolicyDocument());
        }
    }

    @Test
    void invalidLaterPrincipalAndTargetErrorsPreserveTheWholeRole() {
        IamService iam = memory();
        IamRole source = role(iam, "source", trust(ROOT));
        IamRole target = role(iam, "target", trust(ROOT));
        iam.putRolePolicy("target", "existing", EMPTY);
        JsonNode before = MAPPER.valueToTree(target);
        String missing = "arn:aws:iam::" + OTHER + ":role/team/source";
        malformed(assertThrows(AwsException.class,
                () -> iam.updateAssumeRolePolicy("target", trust(source.getArn(), missing))), missing);
        assertEquals(before, MAPPER.valueToTree(iam.getRole("target")));
        AwsException duplicate = assertThrows(AwsException.class,
                () -> role(iam, "target", trust(missing)));
        assertEquals("EntityAlreadyExists", duplicate.getErrorCode());
        assertEquals(409, duplicate.getHttpStatus());
        AwsException absent = assertThrows(AwsException.class,
                () -> iam.updateAssumeRolePolicy("absent", trust(missing)));
        assertEquals("NoSuchEntity", absent.getErrorCode());
        assertEquals(404, absent.getHttpStatus());
        assertEquals(before, MAPPER.valueToTree(iam.getRole("target")));
        malformed(assertThrows(AwsException.class, () -> role(iam, "refused", trust(missing))), missing);
        assertEquals("NoSuchEntity", assertThrows(AwsException.class, () -> iam.getRole("refused")).getErrorCode());
    }

    @Test
    void accountAndPathLookupNeverFallsBackToASameNamedIdentity() {
        InMemoryStorage<String, IamRole> rawRoles = new InMemoryStorage<>();
        InMemoryStorage<String, IamUser> rawUsers = new InMemoryStorage<>();
        IamService iam = service(new AccountAwareStorageBackend<>(rawUsers, null, ACCOUNT),
                new AccountAwareStorageBackend<>(rawRoles, null, ACCOUNT));
        IamRole local = role(iam, "source", trust(ROOT));
        IamRole foreign = new IamRole("AROAFOREIGN", "source", "/other/",
                "arn:aws:iam::" + OTHER + ":role/other/source", EMPTY);
        rawRoles.put(OTHER + "/source", foreign);
        IamUser foreignUser = new IamUser("AIDAFOREIGN", "user", "/other/",
                "arn:aws:iam::" + OTHER + ":user/other/user");
        rawUsers.put(OTHER + "/user", foreignUser);
        IamRole target = role(iam, "target", trust(foreign.getArn(), foreignUser.getArn()));
        assertPolicy(trust(foreign.getRoleId(), foreignUser.getUserId()), target.getAssumeRolePolicyDocument());
        assertPolicy(trust(foreign.getArn(), foreignUser.getArn()),
                iam.renderAssumeRolePolicy(target.getAssumeRolePolicyDocument()));
        assertNotEquals(local.getRoleId(), foreign.getRoleId());
        String wrongPath = "arn:aws:iam::" + OTHER + ":role/team/source";
        malformed(assertThrows(AwsException.class, () -> iam.updateAssumeRolePolicy("target", trust(wrongPath))),
                wrongPath);
        rawRoles.put(OTHER + "/corrupt", new IamRole("AROACORRUPT", "corrupt", "/",
                "arn:aws:iam::" + ACCOUNT + ":role/corrupt", EMPTY));
        malformed(assertThrows(AwsException.class, () -> iam.updateAssumeRolePolicy("target", trust("AROACORRUPT"))),
                "AROACORRUPT");
        assertPolicy(trust(foreign.getRoleId(), foreignUser.getUserId()), target.getAssumeRolePolicyDocument());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws-cn", "aws-us-gov"})
    void newTrustSavesRequireTheSourceAndTargetPartitionsToMatch(String partition) {
        InMemoryStorage<String, IamRole> rawRoles = new InMemoryStorage<>();
        IamService iam = service(new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawRoles, null, ACCOUNT));
        IamRole source = role(iam, "source", trust(ROOT));
        IamUser user = iam.createUser("user", "/team/");
        IamRole target = role(iam, "target", trust(ROOT));
        JsonNode before = MAPPER.valueToTree(target);
        for (String arn : List.of(source.getArn(), user.getArn())) {
            String wrongPartition = arn.replace("arn:aws:", "arn:" + partition + ":");
            malformed(assertThrows(AwsException.class,
                    () -> iam.updateAssumeRolePolicy("target", trust(wrongPartition))), wrongPartition);
            assertEquals(before, MAPPER.valueToTree(target));
        }

        IamRole otherTarget = new IamRole("AROAOTHERPARTITION", "other", "/team/",
                "arn:" + partition + ":iam::" + ACCOUNT + ":role/team/other", EMPTY);
        rawRoles.put(ACCOUNT + "/other", otherTarget);
        JsonNode otherBefore = MAPPER.valueToTree(otherTarget);
        for (String principal : List.of(source.getRoleId(), user.getUserId(), source.getArn(), user.getArn())) {
            malformed(assertThrows(AwsException.class,
                    () -> iam.updateAssumeRolePolicy("other", trust(principal))), principal);
            assertEquals(otherBefore, MAPPER.valueToTree(otherTarget));
        }
        malformed(assertThrows(AwsException.class,
                () -> iam.updateAssumeRolePolicy("target", trust(otherTarget.getRoleId()))), otherTarget.getRoleId());
        assertEquals(before, MAPPER.valueToTree(target));

        IamRole unknown = new IamRole("AROAUNKNOWNPARTITION", "unknown", "/",
                "arn:aws-unpublished:iam::" + ACCOUNT + ":role/unknown", EMPTY);
        rawRoles.put(ACCOUNT + "/unknown", unknown);
        malformed(assertThrows(AwsException.class,
                () -> iam.updateAssumeRolePolicy("target", trust(unknown.getRoleId()))), unknown.getRoleId());
        assertEquals(trust(unknown.getRoleId()), iam.renderAssumeRolePolicy(trust(unknown.getRoleId())));
        assertEquals(before, MAPPER.valueToTree(target));
    }

    @Test
    void legacyLookupsAreReadOnlyAndExplicitRowsShadowLegacyIds() {
        InMemoryStorage<String, IamRole> raw = new InMemoryStorage<>();
        IamRole legacy = new IamRole("AROALEGACY", "source", "/team/",
                "arn:aws:iam::" + ACCOUNT + ":role/team/source", EMPTY);
        raw.put("source", legacy);
        IamService iam = service(new InMemoryStorage<>(), new AccountAwareStorageBackend<>(raw, null, ACCOUNT));
        IamRole target = role(iam, "target", trust(legacy.getArn()));
        assertPolicy(trust(legacy.getRoleId()), target.getAssumeRolePolicyDocument());
        assertPolicy(trust(legacy.getArn()), iam.renderAssumeRolePolicy(target.getAssumeRolePolicyDocument()));
        assertEquals(Set.of("source", ACCOUNT + "/target"), raw.keys());

        IamRole current = new IamRole("AROACURRENT", "source", "/team/", legacy.getArn(), EMPTY);
        raw.put(ACCOUNT + "/source", current);
        assertPolicy(trust(legacy.getRoleId()), iam.renderAssumeRolePolicy(target.getAssumeRolePolicyDocument()));
        malformed(assertThrows(AwsException.class,
                () -> iam.updateAssumeRolePolicy("target", trust(legacy.getRoleId()))), legacy.getRoleId());
        iam.updateAssumeRolePolicy("target", trust(current.getArn()));
        assertPolicy(trust(current.getRoleId()), target.getAssumeRolePolicyDocument());
        assertEquals(Set.of("source", ACCOUNT + "/source", ACCOUNT + "/target"), raw.keys());
        assertEquals(legacy, raw.get("source").orElseThrow());
    }

    @Test
    void rawRestorationKeepsHistoricalPoliciesAndChecksTheTargetId() {
        IamService iam = memory();
        IamRole target = role(iam, "target", trust(ROOT));
        IamRole live = role(iam, "live", trust(ROOT));
        assertPolicy(trust(live.getArn()), iam.renderAssumeRolePolicy(trust(live.getRoleId())));
        for (String historical : List.of("historical non-json", trust("AROASTALE"),
                trust("arn:aws:iam::" + ACCOUNT + ":role/deleted"),
                trust(live.getRoleId()) + " {}", trust(live.getRoleId()) + " trailing-garbage")) {
            iam.restoreAssumeRolePolicy("target", historical, target.getRoleId());
            assertEquals(historical, target.getAssumeRolePolicyDocument());
            assertEquals(historical, iam.renderAssumeRolePolicy(historical));
        }
        String before = target.getAssumeRolePolicyDocument();
        malformed(assertThrows(AwsException.class,
                () -> iam.updateAssumeRolePolicy("target", trust("AROASTALE"), target.getRoleId())), "AROASTALE");
        assertEquals(before, target.getAssumeRolePolicyDocument());
        iam.deleteRole("target");
        IamRole replacement = role(iam, "target", trust(ROOT));
        AwsException error = assertThrows(AwsException.class,
                () -> iam.restoreAssumeRolePolicy("target", before, target.getRoleId()));
        assertEquals("EntityAlreadyExists", error.getErrorCode());
        assertEquals(trust(ROOT), replacement.getAssumeRolePolicyDocument());
    }

    @Test
    void bindingPreservesConditionsAndOtherPrincipalTypes() throws Exception {
        IamService iam = memory();
        IamRole source = role(iam, "source", trust(ROOT));
        String policy = """
                {"Version":"2012-10-17","Statement":{"Sid":"unchanged","Effect":"Allow",
                 "Action":["sts:AssumeRole"],"Principal":{"AWS":["%s","%s","%s",
                 "arn:aws:sts::000000000000:assumed-role/source/session"],
                 "Service":"lambda.amazonaws.com","Federated":"example.invalid"},
                 "Condition":{"ArnEquals":{"aws:PrincipalArn":"%s"}}}}
                """.formatted(source.getArn(), ROOT, ACCOUNT, source.getArn());
        String expected = policy.replace("\"AWS\":[\"" + source.getArn(), "\"AWS\":[\"" + source.getRoleId());
        IamRole target = role(iam, "target", policy);
        assertPolicy(expected, target.getAssumeRolePolicyDocument());
        assertPolicy(policy, iam.renderAssumeRolePolicy(target.getAssumeRolePolicyDocument()));
        iam.deleteRole("source");
        assertEquals(source.getArn(), MAPPER.readTree(target.getAssumeRolePolicyDocument())
                .path("Statement").path("Condition").path("ArnEquals").path("aws:PrincipalArn").textValue());
        assertPolicy(expected, iam.renderAssumeRolePolicy(target.getAssumeRolePolicyDocument()));
    }

    @Test
    @Timeout(10)
    void reciprocalTrustSavesDoNotAcquireNestedIdentityLocks() throws Exception {
        IamService iam = memory();
        IamRole first = role(iam, "first", trust(ROOT));
        IamRole second = role(iam, "second", trust(ROOT));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            Future<?> left = workers.submit(() -> concurrentSave(iam, first, second, ready, start));
            Future<?> right = workers.submit(() -> concurrentSave(iam, second, first, ready, start));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            left.get(2, TimeUnit.SECONDS);
            right.get(2, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
        assertPolicy(trust(second.getRoleId()), first.getAssumeRolePolicyDocument());
        assertPolicy(trust(first.getRoleId()), second.getAssumeRolePolicyDocument());
    }

    private static void concurrentSave(IamService iam, IamRole target, IamRole source,
                                       CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            assertTrue(start.await(2, TimeUnit.SECONDS));
            iam.updateAssumeRolePolicy(target.getRoleName(), trust(source.getArn()));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static void rejectStaleAndPreserve(IamService iam, String roleId, String userId) {
        JsonNode before = MAPPER.valueToTree(iam.getRole("target"));
        for (String id : List.of(roleId, userId)) {
            malformed(assertThrows(AwsException.class, () -> iam.updateAssumeRolePolicy("target", trust(id))), id);
            assertEquals(before, MAPPER.valueToTree(iam.getRole("target")));
        }
    }

    private static IamRole role(IamService iam, String name, String policy) {
        return iam.createRole(name, "/team/", policy, "retained", 7200, Map.of("owner", "fixture"));
    }

    private static IamService memory() {
        return service(new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    private static IamService service(StorageBackend<String, IamUser> users, StorageBackend<String, IamRole> roles) {
        return new IamService(users, new InMemoryStorage<>(), roles, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", ACCOUNT));
    }

    private static String trust(String... principals) {
        String values = principals.length == 1 ? "\"" + principals[0] + "\""
                : "[\"" + String.join("\",\"", principals) + "\"]";
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":" + values + "},\"Action\":\"sts:AssumeRole\"}]}";
    }

    private static void assertPolicy(String expected, String actual) {
        try {
            assertEquals(MAPPER.readTree(expected), MAPPER.readTree(actual));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void malformed(AwsException error, String principal) {
        assertEquals("MalformedPolicyDocument", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("Invalid principal in policy: \"AWS\":\"" + principal + "\"", error.getMessage());
    }

    private static final class Fixture implements AutoCloseable {
        private final StorageFactory factory;
        private final IamService iam;

        private Fixture(Path directory, String mode) {
            EmulatorConfig config = StorageFactories.config(directory, ACCOUNT);
            factory = StorageFactories.open(config, mode);
            iam = new IamService(factory, config, new RegionResolver("us-east-1", ACCOUNT), Clock.systemUTC());
        }

        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
}
