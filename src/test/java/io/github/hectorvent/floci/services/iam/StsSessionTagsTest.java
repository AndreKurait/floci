package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StsSessionTagsTest {
    @ParameterizedTest
    @ValueSource(strings = {"missing-key", "missing-value", "empty-key", "long-key", "long-value",
            "invalid-key", "invalid-value", "reserved-key", "reserved-value", "duplicate", "case-duplicate", "too-many"})
    void modeledConstraintsRejectInvalidTags(String scenario) {
        Map<String, String> form = new HashMap<>(Map.of("Tags.member.1.Key", "Project", "Tags.member.1.Value", ""));
        switch (scenario) {
            case "missing-key" -> form.remove("Tags.member.1.Key");
            case "missing-value" -> form.remove("Tags.member.1.Value");
            case "empty-key" -> form.put("Tags.member.1.Key", "");
            case "long-key" -> form.put("Tags.member.1.Key", "k".repeat(129));
            case "long-value" -> form.put("Tags.member.1.Value", "v".repeat(257));
            case "invalid-key" -> form.put("Tags.member.1.Key", "bad*");
            case "invalid-value" -> form.put("Tags.member.1.Value", "bad?");
            case "reserved-key" -> form.put("Tags.member.1.Key", "AWS:internal");
            case "reserved-value" -> form.put("Tags.member.1.Value", "aws:internal");
            case "duplicate", "case-duplicate" -> {
                form.put("Tags.member.2.Key", scenario.equals("duplicate") ? "Project" : "project");
                form.put("Tags.member.2.Value", "other");
            }
            case "too-many" -> {
                for (int i = 2; i <= 51; i++) {
                    form.put("Tags.member." + i + ".Key", "key" + i);
                    form.put("Tags.member." + i + ".Value", "");
                }
            }
            default -> throw new AssertionError(scenario);
        }
        AwsException error = assertThrows(AwsException.class, () -> StsSessionTags.readForm(form));
        assertEquals("ValidationError", error.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "long", "invalid", "too-many"})
    void transitiveKeyConstraintsAreValidated(String scenario) {
        Map<String, String> form = new HashMap<>();
        form.put("TransitiveTagKeys.member.1", switch (scenario) {
            case "empty" -> "";
            case "long" -> "k".repeat(129);
            case "invalid" -> "bad*";
            default -> "Project";
        });
        if ("too-many".equals(scenario)) {
            for (int index = 2; index <= 51; index++) {
                form.put("TransitiveTagKeys.member." + index, "key" + index);
            }
        }
        assertThrows(AwsException.class, () -> StsSessionTags.transitiveKeys(form));
    }

    @Test
    void transitiveKeysUseOnlyConsumedMembersAndRetainAnImmutableSubset() throws Exception {
        List<String> keys = StsSessionTags.transitiveKeys(Map.of("TransitiveTagKeys.member.3", "Project",
                "TransitiveTagKeys.9", "部署", "Unrelated.TransitiveTagKeys.member.1", "ignored"));
        assertEquals(List.of("Project", "部署"), keys);
        Map<String, String> inherited = Map.of("Earlier", "retained");
        Map<String, String> requested = Map.of("Project", "session", "Local", "not-transitive");
        Map<String, String> transitive = StsSessionTags.transitive(requested, keys, inherited);
        assertEquals(Map.of("Earlier", "retained", "Project", "session"), transitive);
        assertEquals(Map.of("Earlier", "retained", "Project", "session", "Local", "not-transitive"),
                StsSessionTags.inherit(requested, inherited));
        assertThrows(AwsException.class, () -> StsSessionTags.inherit(Map.of("earlier", "replacement"), inherited));
        assertThrows(UnsupportedOperationException.class, () -> transitive.put("new", "value"));
        SessionCredential session = new SessionCredential();
        session.setTransitiveSessionTags(transitive);
        ObjectMapper mapper = new ObjectMapper();
        SessionCredential restored = mapper.readValue(mapper.writeValueAsBytes(session), SessionCredential.class);
        assertEquals(transitive, restored.getTransitiveSessionTags());
        assertThrows(UnsupportedOperationException.class,
                () -> restored.getTransitiveSessionTags().put("new", "value"));
        assertEquals(Map.of(), mapper.readValue("{}", SessionCredential.class).getTransitiveSessionTags());
        assertEquals(Map.of(), mapper.readValue("{\"transitiveSessionTags\":null}",
                SessionCredential.class).getTransitiveSessionTags());
    }

    @Test
    void queryFormsGapsUnicodeAndEmptyValuesPreserveMembers() {
        Map<String, String> tags = StsSessionTags.readForm(Map.of(
                "Tags.member.3.Key", "部署", "Tags.member.3.Value", "",
                "Tags.9.Key", "Department", "Tags.9.Value", "Engineering",
                "Unrelated.Tags.member.1.Key", "unused"));
        assertEquals(Map.of("部署", "", "Department", "Engineering"), tags);
        assertEquals(Map.of(), StsSessionTags.readForm(Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> tags.put("later", "changed"));
        assertEquals(2, StsSessionTags.context(tags).get("aws:TagKeys").size());
    }

    @Test
    void maximumTagsAndMemberLengthsAreAccepted() {
        Map<String, String> form = new HashMap<>();
        for (int i = 1; i <= 50; i++) {
            form.put("Tags.member." + i + ".Key", i == 1 ? "k".repeat(128) : "key" + i);
            form.put("Tags.member." + i + ".Value", "v".repeat(256));
        }
        assertEquals(50, StsSessionTags.readForm(form).size());
    }

    @Test
    void sessionTagsRoundTripAndOverrideWithoutMutatingRoleOrInput() throws Exception {
        Map<String, String> role = new HashMap<>(Map.of("Project", "role", "team", "retained"));
        Map<String, String> tags = new HashMap<>(Map.of("project", "session"));
        SessionCredential session = new SessionCredential();
        session.setSessionTags(tags);
        tags.put("project", "changed");
        ObjectMapper mapper = new ObjectMapper();
        SessionCredential restored = mapper.readValue(mapper.writeValueAsBytes(session), SessionCredential.class);
        assertEquals(Map.of("project", "session", "team", "retained"),
                StsSessionTags.effective(role, restored.getSessionTags()));
        assertEquals("role", role.get("Project"));
        assertThrows(UnsupportedOperationException.class, () -> restored.getSessionTags().put("new", "value"));
        assertEquals(Map.of(), mapper.readValue("{}", SessionCredential.class).getSessionTags());
        assertEquals(Map.of(), mapper.readValue("{\"sessionTags\":null}", SessionCredential.class).getSessionTags());
    }
}
