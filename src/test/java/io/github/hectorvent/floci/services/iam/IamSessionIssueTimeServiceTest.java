package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IamSessionIssueTimeServiceTest {
    private static final Instant ISSUED = Instant.parse("2026-10-26T12:00:00.123456Z");
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/team/Role";
    private final InMemoryStorage<String, SessionCredential> sessions = new InMemoryStorage<>();
    private final IamService iam = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            sessions, new RegionResolver("us-east-1", ACCOUNT), false, Clock.fixed(ISSUED, ZoneOffset.UTC));

    @Test
    void registeredRoleSessionPublishesStoredIssueTime() {
        iam.registerSession("ASIASESSION", "secret", "token", ROLE, ISSUED.plusSeconds(3600), null,
                ACCOUNT, "session", "AROAID:session");
        assertEquals(List.of(ISSUED.toString()), context("ASIASESSION").get("aws:TokenIssueTime"));
        assertEquals(List.of(ROLE), context("ASIASESSION").get("aws:PrincipalArn"));
        assertEquals(ISSUED, sessions.get("ASIASESSION").orElseThrow().getIssuedAt());
    }

    @Test
    void issuedUserRootAndFederatedSessionsRetainTheirOriginalPrincipals() {
        iam.createUser("alice", "/team/");
        String key = iam.createAccessKey("alice").getAccessKeyId();
        iam.registerIssuedSession("ASIAUSER", "secret", "token", null, ISSUED.plusSeconds(3600), null, ACCOUNT, key);
        iam.registerIssuedSession("ASIAROOT", "secret", "token", null, ISSUED.plusSeconds(3600), null, ACCOUNT, "test");
        iam.registerIssuedSession("ASIAFEDERATED", "secret", "token", "arn:aws:sts::" + ACCOUNT + ":federated-user/fed",
                ISSUED.plusSeconds(3600), null, ACCOUNT, key);
        for (String session : List.of("ASIAUSER", "ASIAROOT", "ASIAFEDERATED")) {
            assertEquals(List.of(ISSUED.toString()), context(session).get("aws:TokenIssueTime"));
        }
        assertEquals(List.of("alice"), context("ASIAUSER").get("aws:username"));
        assertFalse(context(key).containsKey("aws:TokenIssueTime"));
        assertFalse(context("ASIAFEDERATED").containsKey("aws:username"));
    }

    @Test
    void timestampSurvivesSerializationWithoutBeingRecreatedForLegacyStorage() throws Exception {
        iam.registerSession("ASIASESSION", ROLE, ISSUED.plusSeconds(3600));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SessionCredential restored = mapper.readValue(mapper.writeValueAsBytes(sessions.get("ASIASESSION").orElseThrow()),
                SessionCredential.class);
        sessions.put("ASIASESSION", restored);
        assertEquals(List.of(ISSUED.toString()), context("ASIASESSION").get("aws:TokenIssueTime"));
        ObjectNode oldRecord = mapper.valueToTree(restored);
        oldRecord.remove("issuedAt");
        SessionCredential legacy = mapper.treeToValue(oldRecord, SessionCredential.class);
        sessions.put("ASIASESSION", legacy);
        assertFalse(context("ASIASESSION").containsKey("aws:TokenIssueTime"));
        assertNull(legacy.getIssuedAt());
    }

    @Test
    void explicitAccountAndRuntimeRegistrationsRecordOnce() {
        iam.registerSessionForAccount(ACCOUNT, "ASIAACCOUNT", "secret", "token", ROLE, ISSUED.plusSeconds(3600), null);
        iam.registerLambdaExecutionRoleSession(ACCOUNT, "ASIALAMBDA", "secret", "token", ROLE);
        SessionCredential ec2 = new SessionCredential("ASIAEC2", "secret", "token", ROLE,
                ISSUED.plusSeconds(3600), null, ACCOUNT);
        ec2.setEc2InstanceId("i-owned");
        iam.registerEc2InstanceSession(ec2);
        SessionCredential ecs = new SessionCredential("ASIAECS", "secret", "token", ROLE,
                ISSUED.plusSeconds(3600), null, ACCOUNT);
        ecs.setEcsTaskArn("arn:aws:ecs:us-east-1:" + ACCOUNT + ":task/cluster/task");
        ecs.setIssuedAt(ISSUED.minusSeconds(30));
        iam.registerEcsTaskRoleSession(ecs);
        iam.registerEcsTaskRoleSession(ecs);
        for (String key : List.of("ASIAACCOUNT", "ASIALAMBDA", "ASIAEC2")) {
            assertEquals(ISSUED, sessions.get(key).orElseThrow().getIssuedAt());
        }
        assertEquals(ISSUED.minusSeconds(30), sessions.get("ASIAECS").orElseThrow().getIssuedAt());
    }

    @Test
    void expiredSessionStillHasNoPrincipalContext() {
        iam.registerSession("ASIAEXPIRED", ROLE, ISSUED);
        assertTrue(iam.resolveCallerArns("ASIAEXPIRED").isEmpty());
    }

    private Map<String, List<String>> context(String accessKey) {
        Map<String, List<String>> result = new HashMap<>();
        iam.resolveCallerArns(accessKey).orElseThrow().putPrincipalKeys(result);
        return result;
    }
}
