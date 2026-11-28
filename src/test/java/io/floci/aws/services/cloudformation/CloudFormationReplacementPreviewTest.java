package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.cloudformation.model.ChangeSet;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.CfnDynamicReferences;
import io.floci.aws.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.floci.aws.services.cloudformation.provisioners.SsmDocumentCfnProvisioner;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.ssm.SsmService;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudFormationReplacementPreviewTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String NAME = "replacement-preview";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CfnResourceDispatcher dispatcher = mock(CfnResourceDispatcher.class);
    private final CfnDynamicReferences dynamic = mock(CfnDynamicReferences.class);
    private CloudFormationService service;
    private Stack stack;

    @BeforeEach
    void createService() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn(ACCOUNT);
        when(dispatcher.referenceValue(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(dynamic.resolveDynamicReferences(anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new CloudFormationService(dispatcher, mock(S3Service.class), mock(SsmService.class),
                mock(GetStackOutputRoleAuthorizer.class), dynamic, mapper, config, resolver, Clock.systemUTC(),
                new InMemoryStorageFactory(ACCOUNT));
    }

    @AfterEach
    void closeService() {
        service.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetStackOutput", "ImportValue"})
    void externalOutputsStayConditionalWithoutMutatingTheDeployedResource(String function) throws Exception {
        seed(ruleTemplate("\"192.0.2.1/32\"", "old"));
        CloudFormationService.ResourceChange change = preview(ruleTemplate(external(function), "new")).get("Rule");
        assertEquals("Conditional", change.replacement());
        assertEquals("Rule-id", change.physicalResourceId());
        assertEquals("Rule-id", stack.getResources().get("Rule").getPhysicalId());
        assertEquals("CREATE_COMPLETE", stack.getStatus());
        assertTrue(change.details().stream().anyMatch(detail -> "CidrIp".equals(detail.name())
                && "Conditionally".equals(detail.requiresRecreation())));
        verify(dispatcher, never()).delete(any(StackResource.class), anyString());
    }

    @Test
    void unchangedWeakOutputIsConditionalWhenAnotherPropertyChanges() throws Exception {
        seed(ruleTemplate(external("GetStackOutput"), "old"));
        assertEquals("Conditional", preview(ruleTemplate(external("GetStackOutput"), "new")).get("Rule").replacement());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dependencyCertaintyAndExistingChangeDetailsArePreserved(boolean definite) throws Exception {
        seed(dependentTemplate("\"vpc-id\"", "group", "old"));
        Map<String, CloudFormationService.ResourceChange> changes = preview(dependentTemplate(
                external("GetStackOutput"), definite ? "other-group" : "group", "new"));
        String expected = definite ? "True" : "Conditional";
        assertEquals(expected, changes.get("Group").replacement());
        assertEquals(expected, changes.get("Rule").replacement());
        assertFalse(changes.get("Rule").details().isEmpty());
        assertTrue(changes.get("Rule").scope().contains("Properties"));
    }

    @Test
    void equivalentResourceReferenceRewriteDoesNotReplace() throws Exception {
        String original = """
                {"Resources":{"Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.0.0.0/16"}},
                  "Group":{"Type":"AWS::EC2::SecurityGroup","Properties":{
                    "GroupName":"group","GroupDescription":"description","Tags":[{"Key":"note","Value":"old"}],
                    "VpcId":{"Ref":"Vpc"}}}}}
                """;
        seed(original);
        String updated = original.replace("\"old\"", "\"new\"")
                .replace("\"VpcId\":{\"Ref\":\"Vpc\"}", "\"VpcId\":{\"Fn::Join\":[\"\",[{\"Ref\":\"Vpc\"}]]}");
        assertEquals("False", preview(updated).get("Group").replacement());
    }

    @Test
    void mutableSiblingOfANestedCreateOnlyPointerDoesNotReplace() throws Exception {
        String original = """
                {"Resources":{"Index":{"Type":"AWS::S3Vectors::Index","Properties":{
                  "IndexName":"index","MetadataConfiguration":{"NonFilterableMetadataKeys":["fixed"],
                  "MutableSibling":"old"}}}}}
                """;
        seed(original);
        assertEquals("False", preview(original.replace("\"old\"", "\"new\"")).get("Index").replacement());
        assertEquals("True", preview(original.replace("\"fixed\"", "\"other\"")).get("Index").replacement());
    }

    @ParameterizedTest
    @CsvSource({"Replace,True", "NewVersion,False"})
    void providerUpdateMethodDeterminesDocumentReplacement(String method, String expected) throws Exception {
        SsmDocumentCfnProvisioner owner = new SsmDocumentCfnProvisioner(mock(SsmService.class));
        when(dispatcher.conditionalReplacements(eq("AWS::SSM::Document"), any()))
                .thenAnswer(invocation -> owner.conditionalReplacements(invocation.getArgument(0), invocation.getArgument(1)));
        String original = """
                {"Resources":{"Document":{"Type":"AWS::SSM::Document","Properties":{
                  "Name":"document","DocumentType":"Command","UpdateMethod":"%s",
                  "Content":{"schemaVersion":"2.2","description":"old"}}}}}
                """.formatted(method);
        seed(original);
        assertEquals(expected, preview(original.replace("\"old\"", "\"new\"")).get("Document").replacement());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dynamicReferenceAndLiteralAreComparedByValue(boolean changed) throws Exception {
        String original = """
                {"Resources":{"Profile":{"Type":"AWS::AppConfig::ConfigurationProfile","Properties":{
                  "ApplicationId":"app","LocationUri":"hosted","Description":"old"}}}}
                """;
        seed(original);
        when(dynamic.resolveDynamicReferences(eq("{{resolve:ssm:/location}}"), eq(REGION), anyBoolean()))
                .thenReturn(changed ? "s3://bucket/key" : "hosted");
        String updated = original.replace("\"hosted\"", "\"{{resolve:ssm:/location}}\"")
                .replace("\"old\"", "\"new\"");
        assertEquals(changed ? "True" : "False", preview(updated).get("Profile").replacement());
    }

    @Test
    void missingOldDynamicDigestDoesNotHideAnotherDefiniteReplacement() throws Exception {
        String original = """
                {"Resources":{"Profile":{"Type":"AWS::AppConfig::ConfigurationProfile","Properties":{
                  "ApplicationId":"app","LocationUri":"{{resolve:ssm:/location}}","Description":"old"}}}}
                """;
        seed(original);
        when(dynamic.resolveDynamicReferences(eq("{{resolve:ssm:/location}}"), eq(REGION), anyBoolean()))
                .thenReturn("hosted");
        assertEquals("False", preview(original.replace("\"old\"", "\"new\"")).get("Profile").replacement());
        assertEquals("True", preview(original.replace("\"app\"", "\"other-app\"")).get("Profile").replacement());
    }

    private void seed(String template) throws Exception {
        service.createChangeSet(NAME, "seed", "CREATE", template, null, Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        stack = service.describeStacks(NAME, REGION, ACCOUNT).getFirst();
        stack.setTemplateBody(template);
        stack.setStatus("CREATE_COMPLETE");
        for (Map.Entry<String, JsonNode> entry : mapper.readTree(template).path("Resources").properties()) {
            StackResource resource = new StackResource();
            resource.setLogicalId(entry.getKey());
            resource.setResourceType(entry.getValue().path("Type").asText());
            resource.setPhysicalId(entry.getKey() + "-id");
            resource.setStatus("CREATE_COMPLETE");
            stack.getResources().put(entry.getKey(), resource);
        }
    }

    private Map<String, CloudFormationService.ResourceChange> preview(String template) {
        ChangeSet changeSet = new ChangeSet();
        changeSet.setStackName(NAME);
        changeSet.setChangeSetName("preview");
        changeSet.setChangeSetType("UPDATE");
        changeSet.setTemplateBody(template);
        changeSet.setParameters(Map.of());
        return service.computeChangeSetChanges(changeSet, REGION).stream()
                .collect(Collectors.toMap(CloudFormationService.ResourceChange::logicalResourceId, value -> value));
    }

    private static String external(String function) {
        return "GetStackOutput".equals(function)
                ? "{\"Fn::GetStackOutput\":{\"StackName\":\"producer\",\"OutputName\":\"Value\"}}"
                : "{\"Fn::ImportValue\":\"exported-value\"}";
    }

    private static String ruleTemplate(String cidr, String description) {
        return """
                {"Resources":{"Rule":{"Type":"AWS::EC2::SecurityGroupIngress","Properties":{
                  "GroupId":"group-id","IpProtocol":"tcp","FromPort":443,"ToPort":443,
                  "CidrIp":%s,"Description":"%s"}}}}
                """.formatted(cidr, description);
    }

    private static String dependentTemplate(String vpc, String groupName, String description) {
        return """
                {"Resources":{
                  "Group":{"Type":"AWS::EC2::SecurityGroup","Properties":{
                    "VpcId":%s,"GroupName":"%s","GroupDescription":"description"}},
                  "Rule":{"Type":"AWS::EC2::SecurityGroupIngress","Properties":{
                    "GroupId":{"Ref":"Group"},"IpProtocol":"tcp","FromPort":443,"ToPort":443,
                    "CidrIp":"192.0.2.1/32","Description":"%s"}}}}
                """.formatted(vpc, groupName, description);
    }
}
