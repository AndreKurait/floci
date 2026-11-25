package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.XmlParser;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.PolicySource;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IamSimulationResourceContextTest {
    private static final String PUBLIC = "arn:aws:s3:::simulation-bucket/public/item";
    private static final String PRIVATE = "arn:aws:s3:::simulation-bucket/private/item";
    private static final String ALLOW = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:Get*","Resource":"*"}]}
            """;
    private final IamService service = mock(IamService.class);
    private final IamQueryHandler handler = new IamQueryHandler(service,
            new IamPolicyEvaluator(new ObjectMapper()), null, null, null, null, null, null, null,
            new RequestContext(), null, mock(IamCloudTrailEvents.class));

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceArn", "aws:ARN"})
    void resourceConditionsKeepDetailedReportsAndAggregateDecisions(String key) throws Exception {
        MultivaluedMap<String, String> params = parameters(key);
        params.putSingle("ActionNames.member.2", "s3:GetObjectTagging");
        List<Element> results = results(simulate("SimulateCustomPolicy", params));
        assertEquals(2, results.size());
        for (Element result : results) {
            assertEquals("explicitDeny", text(result, "EvalDecision"));
            List<Element> resources = members(result, "ResourceSpecificResults");
            assertEquals(List.of(PUBLIC, PRIVATE),
                    resources.stream().map(resource -> text(resource, "EvalResourceName")).toList());
            assertEquals(List.of("allowed", "explicitDeny"), decisions(resources));
            assertEquals("PolicyInputList.2",
                    text(members(result, "MatchedStatements").getFirst(), "SourcePolicyId"));
            assertFalse(members(resources.getFirst(), "MatchedStatements").isEmpty());
            assertFalse(members(resources.getLast(), "MatchedStatements").isEmpty());
            assertTrue(members(result, "MissingContextValues").isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "aws:ResourceArn, aws:ResourceArn, allowed",
            "aws:ResourceArn, AWS:RESOURCEARN, allowed",
            "aws:ResourceArn, aws:resourcearn, allowed",
            "AWS:RESOURCEARN, AWS:RESOURCEARN, allowed",
            "aws:ResourceArn, aws:ARN, explicitDeny",
            "aws:ARN, aws:ARN, allowed",
            "aws:ARN, aws:arn, allowed",
            "aws:ARN, aws:Arn, allowed",
            "aws:ARN, AWS:ARN, explicitDeny",
            "AWS:ARN, aws:ARN, allowed",
            "AWS:ARN, AWS:ARN, explicitDeny",
            "aws:ARN, aws:ResourceArn, explicitDeny"
    })
    void explicitContextOverridesOnlyItsOwnAlias(String key, String contextKey, String expected) throws Exception {
        MultivaluedMap<String, String> params = parameters(key);
        params.putSingle("ContextEntries.member.1.ContextKeyName", contextKey);
        params.putSingle("ContextEntries.member.1.ContextKeyValues.member.1", PUBLIC);
        params.putSingle("ContextEntries.member.1.ContextKeyType", "string");
        Element result = results(simulate("SimulateCustomPolicy", params)).getFirst();
        assertEquals(expected, text(result, "EvalDecision"));
        List<Element> resources = members(result, "ResourceSpecificResults");
        assertEquals(PRIVATE, text(resources.getLast(), "EvalResourceName"));
        assertEquals(expected, text(resources.getLast(), "EvalResourceDecision"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceArn", "aws:ARN"})
    void principalAndBoundaryEvaluationsUseIndependentResourceContexts(String key) throws Exception {
        String policy = allowExact(key, PUBLIC);
        when(service.resolvePrincipalContext(anyString(), any(PolicyExclusions.class)))
                .thenReturn(CallerContext.named(List.of(policy), List.of(PolicySource.input("1")), null));
        MultivaluedMap<String, String> principal = new MultivaluedHashMap<>();
        principal.putSingle("PolicySourceArn", "arn:aws:iam::000000000000:user/simulation-user");
        principal.putSingle("ActionNames.member.1", "s3:GetObject");
        principal.putSingle("ResourceArns.member.1", PUBLIC);
        principal.putSingle("ResourceArns.member.2", PRIVATE);
        Element result = results(simulate("SimulatePrincipalPolicy", principal)).getFirst();
        assertEquals("implicitDeny", text(result, "EvalDecision"));
        assertEquals(List.of("allowed", "implicitDeny"), decisions(members(result, "ResourceSpecificResults")));
        MultivaluedMap<String, String> boundary = parameters(key);
        boundary.remove("PolicyInputList.member.2");
        boundary.putSingle("PermissionsBoundaryPolicyInputList.member.1", policy);
        Element bounded = results(simulate("SimulateCustomPolicy", boundary)).getFirst();
        assertEquals(List.of("allowed", "implicitDeny"), decisions(members(bounded, "ResourceSpecificResults")));
        assertEquals("false", text(child(bounded, "PermissionsBoundaryDecisionDetail"),
                "AllowedByPermissionsBoundary"));
    }

    @Test
    void actionPagesRetainAllIndependentResourceResults() throws Exception {
        MultivaluedMap<String, String> params = parameters("aws:ResourceArn");
        params.putSingle("ActionNames.member.2", "s3:GetObjectTagging");
        params.putSingle("MaxItems", "1");
        for (String action : List.of("s3:GetObject", "s3:GetObjectTagging")) {
            Document response = simulate("SimulateCustomPolicy", params);
            List<Element> results = results(response);
            assertEquals(1, results.size());
            assertEquals(action, text(results.getFirst(), "EvalActionName"));
            assertEquals(List.of("allowed", "explicitDeny"),
                    decisions(members(results.getFirst(), "ResourceSpecificResults")));
            boolean firstPage = action.equals("s3:GetObject");
            assertEquals(Boolean.toString(firstPage), response.getElementsByTagName("IsTruncated")
                    .item(0).getTextContent());
            if (firstPage) {
                params.putSingle("Marker", response.getElementsByTagName("Marker").item(0).getTextContent());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceArn", "aws:ARN"})
    void wildcardResourceIsAvailableWithoutInventingUnrelatedContext(String key) throws Exception {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ActionNames.member.1", "s3:GetObject");
        params.putSingle("PolicyInputList.member.1", allowExact(key, "*"));
        Element result = results(simulate("SimulateCustomPolicy", params)).getFirst();
        assertEquals("allowed", text(result, "EvalDecision"));
        assertNull(child(result, "ResourceSpecificResults"));
        params.putSingle("PolicyInputList.member.1", allowExact("aws:SourceArn", PUBLIC));
        Element missing = results(simulate("SimulateCustomPolicy", params)).getFirst();
        assertEquals("implicitDeny", text(missing, "EvalDecision"));
        assertEquals("aws:SourceArn", members(missing, "MissingContextValues").getFirst().getTextContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"arn:aws-us-gov:s3:::simulation-bucket/item", "arn:aws-cn:s3:::simulation-bucket/item"})
    void resourceContextKeepsTheRequestedPartition(String arn) throws Exception {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ActionNames.member.1", "s3:GetObject");
        params.putSingle("ResourceArns.member.1", arn);
        params.putSingle("PolicyInputList.member.1", allowExact("aws:ResourceArn", arn));
        Element result = results(simulate("SimulateCustomPolicy", params)).getFirst();
        assertEquals("allowed", text(result, "EvalDecision"));
        assertEquals(arn, text(members(result, "ResourceSpecificResults").getFirst(), "EvalResourceName"));
    }

    private Document simulate(String action, MultivaluedMap<String, String> params) throws Exception {
        try (Response response = handler.handle(action, params, null)) {
            assertEquals(200, response.getStatus());
            return XmlParser.parseDocument((String) response.getEntity());
        }
    }

    private static MultivaluedMap<String, String> parameters(String key) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ActionNames.member.1", "s3:GetObject");
        params.putSingle("ResourceArns.member.1", PUBLIC);
        params.putSingle("ResourceArns.member.2", PRIVATE);
        params.putSingle("PolicyInputList.member.1", ALLOW);
        params.putSingle("PolicyInputList.member.2", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Action":"s3:Get*","Resource":"*",
                   "Condition":{"StringNotLike":{"%s":"arn:aws:s3:::simulation-bucket/public/*"}}}]}
                """.formatted(key));
        return params;
    }

    private static String allowExact(String key, String value) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:Get*","Resource":"*",
                   "Condition":{"StringEquals":{"%s":"%s"}}}]}
                """.formatted(key, value);
    }

    private static List<Element> results(Document document) {
        return children(document.getElementsByTagName("EvaluationResults").item(0), "member");
    }

    private static List<String> decisions(List<Element> resources) {
        return resources.stream().map(resource -> text(resource, "EvalResourceDecision")).toList();
    }

    private static List<Element> members(Element parent, String name) {
        return children(child(parent, name), "member");
    }

    private static String text(Element parent, String name) {
        return child(parent, name).getTextContent();
    }

    private static Element child(Element parent, String name) {
        return children(parent, name).stream().findFirst().orElse(null);
    }

    private static List<Element> children(Node parent, String name) {
        List<Element> matches = new ArrayList<>();
        if (parent != null) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && name.equals(element.getLocalName())) {
                    matches.add(element);
                }
            }
        }
        return matches;
    }
}
