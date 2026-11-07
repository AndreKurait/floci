package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IamPrincipalTagsServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final String ACCOUNT = "000000000000";
    private final IamService iam = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new RegionResolver("us-east-1", ACCOUNT), false, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void attachedUserTagsAreCurrentAndTheResolvedSnapshotIsImmutable() {
        iam.createUser("alice", "/team/");
        String key = iam.createAccessKey("alice").getAccessKeyId();
        iam.tagUser("alice", Map.of("department", "engineering", "empty", ""));
        IamService.CallerArns before = iam.resolveCallerArns(key).orElseThrow();
        assertEquals(List.of("engineering"), context(before).get("aws:PrincipalTag/department"));
        assertEquals(List.of(""), context(before).get("aws:PrincipalTag/empty"));
        iam.tagUser("alice", Map.of("department", "security"));
        assertEquals(List.of("security"), context(key).get("aws:PrincipalTag/department"));
        assertEquals(List.of("engineering"), context(before).get("aws:PrincipalTag/department"));
        assertThrows(UnsupportedOperationException.class, () -> before.principalTags().put("extra", "value"));
        iam.untagUser("alice", List.of("department"));
        assertFalse(context(key).containsKey("aws:PrincipalTag/department"));
    }

    @Test
    void userSessionKeepsTagsAndRecordedIssueTime() {
        iam.createUser("alice", "/team/");
        String key = iam.createAccessKey("alice").getAccessKeyId();
        iam.tagUser("alice", Map.of("department", "engineering"));
        iam.registerIssuedSession("ASIAUSER", "secret", "token", null, NOW.plusSeconds(3600), null, ACCOUNT, key);
        assertEquals(List.of("engineering"), context("ASIAUSER").get("aws:PrincipalTag/department"));
        assertEquals(List.of(NOW.toString()), context("ASIAUSER").get("aws:TokenIssueTime"));
        iam.untagUser("alice", List.of("department"));
        assertFalse(context("ASIAUSER").containsKey("aws:PrincipalTag/department"));
    }

    @Test
    void roleSessionUsesTheExactRolePathAndCurrentRoleTags() {
        IamRole role = iam.createRole("Worker", "/team/", "{}", null, 3600, null);
        iam.tagRole("Worker", Map.of("department", "engineering"));
        iam.registerSession("ASIASESSION", "secret", "token", role.getArn(), NOW.plusSeconds(3600), null,
                ACCOUNT, "worker", "AROAID:worker");
        assertEquals(List.of("engineering"), context("ASIASESSION").get("aws:PrincipalTag/department"));
        assertEquals(List.of(role.getArn()), context("ASIASESSION").get("aws:PrincipalArn"));
        assertEquals(List.of(NOW.toString()), context("ASIASESSION").get("aws:TokenIssueTime"));
        iam.untagRole("Worker", List.of("department"));
        assertFalse(context("ASIASESSION").containsKey("aws:PrincipalTag/department"));
    }

    @Test
    void aDifferentRolePathDoesNotSupplyTags() {
        IamRole role = iam.createRole("Worker", "/team/", "{}", null, 3600, null);
        iam.tagRole("Worker", Map.of("department", "engineering"));
        iam.registerSession("ASIAWRONG", role.getArn().replace("/team/", "/other/"), NOW.plusSeconds(3600));
        assertFalse(context("ASIAWRONG").containsKey("aws:PrincipalTag/department"));
    }

    @Test
    void simulatorStillRequiresExplicitTagContext() {
        String arn = iam.createUser("alice", "/").getArn();
        iam.tagUser("alice", Map.of("department", "engineering"));
        assertFalse(context(iam.simulationPrincipalKeys(arn).orElseThrow()).containsKey("aws:PrincipalTag/department"));
    }

    @Test
    void rootAndFederatedUserDoNotInheritIssuerTags() {
        iam.createUser("alice", "/");
        String key = iam.createAccessKey("alice").getAccessKeyId();
        iam.tagUser("alice", Map.of("department", "engineering"));
        iam.registerIssuedSession("ASIAFED", "secret", "token", "arn:aws:sts::" + ACCOUNT + ":federated-user/fed",
                NOW.plusSeconds(3600), null, ACCOUNT, key);
        assertFalse(context("ASIAFED").containsKey("aws:PrincipalTag/department"));
        assertTrue(IamService.CallerArns.accountRoot("arn:aws:iam::" + ACCOUNT + ":root", ACCOUNT).principalTags().isEmpty());
    }

    private Map<String, List<String>> context(String key) {
        return context(iam.resolveCallerArns(key).orElseThrow());
    }

    private static Map<String, List<String>> context(IamService.CallerArns caller) {
        Map<String, List<String>> context = new HashMap<>();
        caller.putPrincipalKeys(context);
        return context;
    }
}
