package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigTagOnCreateMetadataTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> OPERATIONS = Set.of(
            "appconfig:CreateApplication", "appconfig:CreateDeploymentStrategy");

    @Test
    void onlyTheTwoDeclaredOperationsAreReadAndTheRemainingNamespaceStaysUncovered() throws Exception {
        ObjectNode document = document();
        assertEquals(OPERATIONS, TagOnCreateMembers.table().entries().keySet().stream()
                .filter(operation -> operation.startsWith("appconfig:")).collect(Collectors.toSet()));
        assertEquals(List.of("CreateApplication", "CreateDeploymentStrategy"),
                MAPPER.convertValue(document.path("partialNamespaces").path("appconfig"), List.class));
        assertTrue(document.path("notCovered").path("appconfig").asText().contains("other creation tags remain uncovered"));
        for (String other : List.of("CreateConfigurationProfile", "CreateEnvironment", "CreateExtension",
                "CreateExtensionAssociation", "CreateExperimentDefinition", "StartDeployment", "StartExperimentRun",
                "TagResource", "createApplication")) {
            assertNull(TagOnCreateMembers.of("appconfig:" + other), other);
        }
        assertNull(TagOnCreateMembers.of("CreateApplication"));
    }

    @Test
    void partialInventoryRejectsMissingExtraAndDuplicateOperationNames() throws Exception {
        for (List<String> names : List.of(List.of("CreateApplication"),
                List.of("CreateApplication", "CreateDeploymentStrategy", "CreateEnvironment"),
                List.of("CreateApplication", "CreateApplication", "CreateDeploymentStrategy"))) {
            ObjectNode invalid = document();
            ((ObjectNode) invalid.path("partialNamespaces")).set("appconfig", MAPPER.valueToTree(names));
            assertThrows(IllegalStateException.class, () -> parse(invalid));
        }
        ObjectNode invalid = document();
        ((ObjectNode) invalid.path("operations")).set("appconfig:CreateEnvironment",
                invalid.path("operations").path("appconfig:CreateApplication").deepCopy());
        assertThrows(IllegalStateException.class, () -> parse(invalid));
        ObjectNode valid = document();
        parse(valid);
    }

    @ParameterizedTest
    @CsvSource({"CreateApplication,application", "CreateDeploymentStrategy,deploymentstrategy"})
    void tagsAreReadFromTheUncachedSdkBodyAndTheExactBodyRemainsAvailable(String operation, String type) throws Exception {
        byte[] bytes = "{\"Name\":\"retained\",\"Tags\":{\"audit\":\"owned\",\"scope\":\"value\"}}"
                .getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext request = request(bytes);
        TagOnCreate.Tagging tagging = TagOnCreate.of("appconfig:" + operation, request).orElseThrow();
        assertEquals("appconfig:TagResource", tagging.action());
        assertTrue(tagging.requestTags());
        assertEquals(List.of(new TagOnCreate.TagSet(null, Map.of("audit", "owned", "scope", "value"))),
                tagging.checked());
        assertArrayEquals(bytes, request.getEntityStream().readAllBytes());
        Map<String, List<String>> context = TagOnCreate.conditionContext(
                tagging, tagging.checked().getFirst(), "appconfig:" + operation);
        assertEquals(Set.of("aws:RequestTag/audit", "aws:RequestTag/scope", "aws:TagKeys"), context.keySet());
        assertEquals(List.of("owned"), context.get("aws:RequestTag/audit"));
        assertEquals(List.of("value"), context.get("aws:RequestTag/scope"));
        assertEquals(Set.of("audit", "scope"), Set.copyOf(context.get("aws:TagKeys")));
    }

    @ParameterizedTest
    @CsvSource({"CreateApplication,application,aws,us-east-1",
            "CreateDeploymentStrategy,deploymentstrategy,aws,us-east-1",
            "CreateApplication,application,aws-cn,polygondwanaland-west-1",
            "CreateDeploymentStrategy,deploymentstrategy,aws-cn,polygondwanaland-west-1",
            "CreateApplication,application,aws-us-gov,polygondwanaland-west-1",
            "CreateDeploymentStrategy,deploymentstrategy,aws-us-gov,polygondwanaland-west-1"})
    void onlyTheSecondaryActionUsesTheMintedTypeAndPassedScope(String operation, String type,
                                                              String partition, String region) {
        ContainerRequestContext request = request("{\"Tags\":{\"audit\":\"owned\"}}".getBytes(StandardCharsets.UTF_8));
        String resolved = "appconfig:" + operation;
        String account = "123456789012";
        assertEquals(List.of(), RequestResources.of(resolved, request, partition, region, account));
        List<String> created = RequestResources.created(
                resolved, "appconfig:TagResource", request, partition, region, account, Map.of());
        List<String> resources = created.isEmpty() ? List.of("*") : created;
        assertEquals(List.of("arn:" + partition + ":appconfig:" + region + ":" + account + ":" + type + "/*"),
                TagOnCreate.resources(resolved, new TagOnCreate.TagSet(null, Map.of("audit", "owned")),
                        partition, region, account, resources));
        assertEquals(List.of("retained"), TagOnCreate.resources("appconfig:CreateEnvironment",
                new TagOnCreate.TagSet(null, Map.of()), partition, region, account, List.of("retained")));
    }

    private static ObjectNode document() throws Exception {
        try (InputStream input = AppConfigTagOnCreateMetadataTest.class.getClassLoader()
                .getResourceAsStream(TagOnCreateMembers.RESOURCE_NAME)) {
            return (ObjectNode) MAPPER.readTree(input);
        }
    }

    private static TagOnCreateMembers.Table parse(JsonNode document) throws Exception {
        return TagOnCreateMembers.parse(new ByteArrayInputStream(MAPPER.writeValueAsBytes(document)));
    }

    private static ContainerRequestContext request(byte[] bytes) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        AtomicReference<InputStream> stream = new AtomicReference<>(new ByteArrayInputStream(bytes));
        Map<String, Object> properties = new HashMap<>();
        when(request.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        when(request.getEntityStream()).thenAnswer(invocation -> stream.get());
        when(request.getProperty(anyString()))
                .thenAnswer(invocation -> properties.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            stream.set(invocation.getArgument(0));
            return null;
        }).when(request).setEntityStream(any());
        doAnswer(invocation -> {
            properties.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(request).setProperty(anyString(), any());
        return request;
    }
}
