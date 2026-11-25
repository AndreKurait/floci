package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TagOnCreatePartialNamespaceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOCUMENT = """
            {"namespaces":{"batch":["batch"]}, "notCovered":{}, "otherNamespaces":[],
             "partialNamespaces":{"batch":["CreateSchedulingPolicy"]},
             "operations":{"batch:CreateSchedulingPolicy":{"tagging":["batch:TagResource"],
                "requestTags":true,"tags":[{"member":"tags{}"}]}}}
            """;

    @Test
    void exactPartialEntryLoadsAndNeverAddsAnotherBatchCreate() throws Exception {
        TagOnCreateMembers.Table table = parse(DOCUMENT);
        assertEquals(Set.of("batch:CreateSchedulingPolicy"), table.entries().keySet());
        assertFalse(table.entries().containsKey("batch:CreateJobQueue"));
        assertFalse(table.entries().containsKey("batch:CreateComputeEnvironment"));
        assertFalse(table.entries().containsKey("batch:RegisterJobDefinition"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"batch\"", "{\"unknown\":[\"CreateSchedulingPolicy\"]}",
            "{\"batch\":[]}", "{\"batch\":\"CreateSchedulingPolicy\"}", "{\"batch\":[null]}",
            "{\"batch\":[1]}", "{\"batch\":[\"CreateSchedulingPolicy\",\"CreateSchedulingPolicy\"]}",
            "{\"batch\":[\"CreateJobQueue\"]}", "{\"batch\":[\"batch:CreateSchedulingPolicy\"]}"})
    void invalidPartialScopeCannotBecomeFullNamespaceCoverage(String partial) throws Exception {
        ObjectNode document = (ObjectNode) MAPPER.readTree(DOCUMENT);
        document.set("partialNamespaces", MAPPER.readTree(partial));
        assertThrows(IllegalStateException.class, () -> parse(document.toString()));
    }

    @Test
    void anAdditionalOperationOutsideTheDeclaredPartialSetIsRejected() throws Exception {
        ObjectNode document = (ObjectNode) MAPPER.readTree(DOCUMENT);
        ObjectNode operations = (ObjectNode) document.path("operations");
        operations.set("batch:CreateJobQueue", operations.path("batch:CreateSchedulingPolicy").deepCopy());
        assertThrows(IllegalStateException.class, () -> parse(document.toString()));
    }

    @Test
    void legacyDocumentWithoutPartialMetadataKeepsItsExplicitEntries() throws Exception {
        ObjectNode document = (ObjectNode) MAPPER.readTree(DOCUMENT);
        document.remove("partialNamespaces");
        assertEquals(parse(DOCUMENT).entries(), parse(document.toString()).entries());
    }

    private static TagOnCreateMembers.Table parse(String document) throws Exception {
        return TagOnCreateMembers.parse(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
    }
}
