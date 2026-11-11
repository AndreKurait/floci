package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFormationYamlParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(String yaml) throws Exception {
        return new CloudFormationYamlParser(mapper).parse(yaml);
    }

    @Test
    void cidrShorthandExpandsToFnCidr() throws Exception {
        JsonNode cidr = parse("Value: !Cidr [\"10.0.0.0/16\", 4, 8]").get("Value").get("Fn::Cidr");
        assertTrue(cidr.isArray());
        assertEquals("10.0.0.0/16", cidr.get(0).asText());
        assertEquals(4, cidr.get(1).asInt());
        assertEquals(8, cidr.get(2).asInt());
    }

    @Test
    void getAzsShorthandExpandsToFnGetAZs() throws Exception {
        assertEquals("", parse("Value: !GetAZs \"\"").get("Value").get("Fn::GetAZs").asText());
    }

    @Test
    void cidrShorthandMatchesLongFormInNestedSelect() throws Exception {
        JsonNode shorthand = parse("CidrBlock: !Select [1, !Cidr [!GetAtt Vpc.CidrBlock, 4, 8]]");
        JsonNode longForm = parse("""
            CidrBlock:
              Fn::Select:
                - 1
                - Fn::Cidr:
                    - Fn::GetAtt: [Vpc, CidrBlock]
                    - 4
                    - 8
            """);
        assertEquals(longForm, shorthand);
    }

    @Test
    void getStackOutputShorthandExpandsToFnGetStackOutput() throws Exception {
        JsonNode shorthand = parse("""
            SubnetId: !GetStackOutput
              StackName: Producer
              OutputName: SubnetId
              Region: us-west-2
            """);
        JsonNode longForm = parse("""
            SubnetId:
              Fn::GetStackOutput:
                StackName: Producer
                OutputName: SubnetId
                Region: us-west-2
            """);
        assertEquals(longForm, shorthand);
        assertEquals("Producer", shorthand.get("SubnetId").get("Fn::GetStackOutput").get("StackName").asText());
    }

    @Test
    void getAttShorthandAcceptsTheSequenceForm() throws Exception {
        JsonNode shorthand = parse("Value: !GetAtt [Vpc, CidrBlock]\n");
        JsonNode longForm = parse("""
            Value:
              Fn::GetAtt: [Vpc, CidrBlock]
            """);
        assertEquals(longForm, shorthand);
        assertEquals("CidrBlock", shorthand.get("Value").get("Fn::GetAtt").get(1).asText());
    }

    @Test
    void getAttSequenceFormResolvesANestedIntrinsic() throws Exception {
        JsonNode shorthand = parse("Value: !GetAtt [Vpc, !Ref AttributeName]\n");
        assertEquals("AttributeName", shorthand.get("Value").get("Fn::GetAtt").get(1).get("Ref").asText());
    }

    @Test
    void unquotedDatesStayText() throws Exception {
        JsonNode node = parse("""
            AWSTemplateFormatVersion: 2010-09-09
            Resources:
              Policy:
                Properties:
                  PolicyDocument:
                    Version: 2012-10-17
            """);
        assertTrue(node.get("AWSTemplateFormatVersion").isTextual());
        assertEquals("2010-09-09", node.get("AWSTemplateFormatVersion").asText());
        JsonNode version = node.get("Resources").get("Policy").get("Properties").get("PolicyDocument").get("Version");
        assertTrue(version.isTextual());
        assertEquals("2012-10-17", version.asText());
    }
}
