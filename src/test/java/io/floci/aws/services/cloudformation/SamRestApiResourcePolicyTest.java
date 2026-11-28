package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SamRestApiResourcePolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SamTransformProcessor processor = new SamTransformProcessor(mapper);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accountAllowListPreservesIntrinsicsAndScopesEveryMethodToTheStage(boolean swagger) throws Exception {
        ObjectNode template = template("""
                {"AwsAccountWhitelist":[{"Ref":"RouterAccountId"},"222222222222"]}
                """);
        ObjectNode body = body(template);
        if (swagger) {
            body.remove("openapi");
            body.put("swagger", "2.0");
        }
        JsonNode original = template.deepCopy();

        JsonNode expanded = expandBody(template);
        assertEquals(mapper.readTree("""
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Allow","Action":"execute-api:Invoke",
                  "Resource":[
                    {"Fn::Sub":["execute-api:/${__Stage__}/GET/items/*",{"__Stage__":{"Ref":"Stage"}}]},
                    {"Fn::Sub":["execute-api:/${__Stage__}/*/items/*",{"__Stage__":{"Ref":"Stage"}}]}
                  ],
                  "Principal":{"AWS":[{"Ref":"RouterAccountId"},"222222222222"]}
                }}
                """), expanded.path("x-amazon-apigateway-policy"));
        assertEquals(body.get("paths"), expanded.get("paths"));
        assertEquals(original, template);
    }

    @Test
    void accountPolicyCoexistsWithTheIamDefault() throws Exception {
        ObjectNode template = template("{\"AwsAccountWhitelist\":[\"111111111111\"]}");
        ((ObjectNode) template.at("/Resources/Api/Properties/Auth")).put("DefaultAuthorizer", "AWS_IAM");

        JsonNode expanded = expandBody(template);
        assertEquals("Allow", expanded.at("/x-amazon-apigateway-policy/Statement/Effect").asText());
        assertEquals(mapper.readTree("[{\"AWS_IAM\":[]}]"),
                expanded.at("/paths/~1items~1{item}/get/security"));
        assertEquals("awsSigv4", expanded.at("/components/securitySchemes/AWS_IAM/"
                + "x-amazon-apigateway-authtype").asText());
    }

    @Test
    void mergesAccountPoliciesAndCustomStatementsWithoutDroppingExistingDenials() throws Exception {
        ObjectNode template = template("""
                {"AwsAccountWhitelist":["111111111111"],"AwsAccountBlacklist":[{"Ref":"DeniedAccount"}],
                 "CustomStatements":[
                   {"Effect":"Deny","Principal":"*","Action":"execute-api:Invoke","Resource":"execute-api:/private/*"},
                   {"Effect":"Deny","Principal":"*","Action":"execute-api:Invoke","Resource":"*",
                    "Condition":{"Bool":{"aws:SecureTransport":"false"}}}
                 ]}
                """);
        JsonNode inline = mapper.readTree("""
                {"Version":"2012-10-17","Id":"retained","Statement":{
                  "Effect":"Deny","Principal":"*","Action":"execute-api:Invoke","Resource":"execute-api:/private/*"}}
                """);
        body(template).set("x-amazon-apigateway-policy", inline);
        JsonNode original = template.deepCopy();

        JsonNode policy = expandBody(template).path("x-amazon-apigateway-policy");
        assertEquals("retained", policy.path("Id").asText());
        assertEquals(4, policy.path("Statement").size());
        assertEquals(inline.get("Statement"), policy.at("/Statement/0"));
        assertEquals("Allow", policy.at("/Statement/1/Effect").asText());
        assertEquals(mapper.readTree("[\"111111111111\"]"), policy.at("/Statement/1/Principal/AWS"));
        assertEquals("Deny", policy.at("/Statement/2/Effect").asText());
        assertEquals(mapper.readTree("[{\"Ref\":\"DeniedAccount\"}]"), policy.at("/Statement/2/Principal/AWS"));
        assertEquals(original.at("/Resources/Api/Properties/Auth/ResourcePolicy/CustomStatements/1"),
                policy.at("/Statement/3"));
        assertEquals(original, template);
    }

    @Test
    void acceptsAnIntrinsicAccountListAndPreservesConditionalPaths() throws Exception {
        ObjectNode template = template("{\"AwsAccountBlacklist\":{\"Ref\":\"BlockedAccounts\"}}");
        ObjectNode paths = (ObjectNode) body(template).get("paths");
        JsonNode operations = paths.remove("/items/{item}");
        ObjectNode conditional = mapper.createObjectNode();
        conditional.putArray("Fn::If").add("Enabled").add(operations)
                .add(mapper.createObjectNode().put("Ref", "AWS::NoValue"));
        paths.set("/files/{proxy+}/", conditional);

        JsonNode policy = expandBody(template).path("x-amazon-apigateway-policy");
        assertEquals("Deny", policy.at("/Statement/Effect").asText());
        assertEquals(mapper.readTree("[{\"Ref\":\"BlockedAccounts\"}]"), policy.at("/Statement/Principal/AWS"));
        assertEquals("execute-api:/${__Stage__}/GET/files/*/", policy.at("/Statement/Resource/0/Fn::Sub/0").asText());
        assertEquals("execute-api:/${__Stage__}/*/files/*/", policy.at("/Statement/Resource/1/Fn::Sub/0").asText());
    }

    @Test
    void customStatementsCanSupplyThePolicyWithoutAnyPaths() throws Exception {
        ObjectNode template = template("""
                {"CustomStatements":{"Effect":"Deny","Principal":"*","Action":"execute-api:Invoke","Resource":"*"}}
                """);
        body(template).set("paths", mapper.createObjectNode());
        assertEquals(mapper.readTree("""
                {"Version":"2012-10-17","Statement":{
                  "Effect":"Deny","Principal":"*","Action":"execute-api:Invoke","Resource":"*"}}
                """), expandBody(template).path("x-amazon-apigateway-policy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}"})
    void anEmptyResourcePolicyLeavesTheInlinePolicyUnchanged(String policy) throws Exception {
        ObjectNode template = template(policy);
        JsonNode inline = mapper.readTree("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Principal":"*","Action":"execute-api:Invoke","Resource":"*"}]}
                """);
        body(template).set("x-amazon-apigateway-policy", inline);
        assertEquals(inline, expandBody(template).path("x-amazon-apigateway-policy"));
    }

    @Test
    void emptyAccountListsDoNotCreateAnAllowStatement() throws Exception {
        ObjectNode template = template("{\"AwsAccountWhitelist\":[],\"AwsAccountBlacklist\":null}");
        assertEquals(mapper.createObjectNode(), expandBody(template).path("x-amazon-apigateway-policy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"body", "policy"})
    void refusesMergeBoundariesWhoseIntrinsicWouldDiscardGeneratedPolicy(String boundary) throws Exception {
        ObjectNode template = template("{\"AwsAccountBlacklist\":[\"111111111111\"]}");
        ObjectNode target = body(template);
        if ("policy".equals(boundary)) {
            target = target.putObject("x-amazon-apigateway-policy");
        }
        target.putArray("Fn::If").add("Enabled").add(mapper.createObjectNode()).add(mapper.createObjectNode());
        JsonNode original = template.deepCopy();
        AwsException error = assertThrows(AwsException.class, () -> processor.expandSamTemplate(template));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(original, template);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"111111111111\"", "{\"AwsAccountWhitelist\":\"111111111111\"}",
            "{\"AwsAccountBlacklist\":42}", "{\"CustomStatements\":\"Allow\"}",
            "{\"IpRangeWhitelist\":[\"192.0.2.0/24\"]}", "{\"UnknownPolicy\":true}"
    })
    void refusesMalformedAndUnsupportedPoliciesWithoutChangingTheTemplate(String policy) throws Exception {
        ObjectNode template = template(policy);
        JsonNode original = template.deepCopy();
        AwsException error = assertThrows(AwsException.class, () -> processor.expandSamTemplate(template));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(original, template);
    }

    private JsonNode expandBody(ObjectNode template) {
        return processor.expandSamTemplate(template).at("/Resources/Api/Properties/Body");
    }

    private ObjectNode body(ObjectNode template) {
        return (ObjectNode) template.at("/Resources/Api/Properties/DefinitionBody");
    }

    private ObjectNode template(String policy) throws Exception {
        ObjectNode template = (ObjectNode) mapper.readTree("""
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{
                  "Api":{"Type":"AWS::Serverless::Api","Properties":{
                    "StageName":{"Ref":"Stage"},"Auth":{},
                    "DefinitionBody":{"openapi":"3.0.1","info":{"title":"resource-policy","version":"1"},
                      "paths":{"/items/{item}":{
                        "get":{"x-amazon-apigateway-integration":{"type":"mock"}},
                        "x-amazon-apigateway-any-method":{"x-amazon-apigateway-integration":{"type":"mock"}}
                      }}}
                  }}
                }}
                """);
        ((ObjectNode) template.at("/Resources/Api/Properties/Auth")).set("ResourcePolicy", mapper.readTree(policy));
        return template;
    }
}
