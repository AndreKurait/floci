package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StsSourceIdentityTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "a", "has space", "aws:reserved", "not/allowed", "éé"})
    void invalidRequestedIdentityIsRejected(String value) {
        assertEquals("ValidationError", assertThrows(AwsException.class,
                () -> StsSourceIdentity.resolve(value, null)).getErrorCode());
    }

    @Test
    void lengthAndCharacterBoundaries() {
        assertEquals("a".repeat(64), StsSourceIdentity.resolve("a".repeat(64), null));
        assertThrows(AwsException.class, () -> StsSourceIdentity.resolve("a".repeat(65), null));
        assertEquals("_+=,.@-", StsSourceIdentity.resolve("_+=,.@-", null));
    }

    @Test
    void absentSameAndChangedIdentityRemainDistinct() {
        assertNull(StsSourceIdentity.resolve(null, null));
        assertEquals("original", StsSourceIdentity.resolve(null, "original"));
        assertEquals("original", StsSourceIdentity.resolve("original", "original"));
        assertEquals("AccessDenied", assertThrows(AwsException.class,
                () -> StsSourceIdentity.resolve("changed", "original")).getErrorCode());
    }

    @Test
    void metadataCopiesKeepIdentityWithoutInventingIt() throws Exception {
        IamService.CallerArns caller = new IamService.CallerArns("session", "role")
                .withSourceIdentity("original").withTokenIssueTime(Instant.EPOCH)
                .withPrincipalTags(Map.of("team", "one")).withMultiFactorAuthPresent(true).withMultiFactorAuthAge(10L);
        Map<String, List<String>> context = new HashMap<>();
        caller.putPrincipalKeys(context);
        assertEquals(List.of("original"), context.get("aws:SourceIdentity"));
        assertEquals(List.of("10"), context.get("aws:MultiFactorAuthAge"));
        assertEquals(List.of("one"), context.get("aws:PrincipalTag/team"));
        context.clear();
        new IamService.CallerArns("user", "user").putPrincipalKeys(context);
        assertFalse(context.containsKey("aws:SourceIdentity"));
        SessionCredential session = new SessionCredential();
        session.setSourceIdentity("original");
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("original", mapper.readValue(mapper.writeValueAsBytes(session), SessionCredential.class)
                .getSourceIdentity());
    }
}
