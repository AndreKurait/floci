package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.codedeploy.CodeDeployService;
import io.floci.aws.services.codedeploy.model.Application;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code AWS::CodeDeploy::Application} in isolation, with only {@link CodeDeployService} mocked. */
class CodeDeployCfnProvisionerTest {

    private static final String ARN = "arn:aws:codedeploy:us-east-1:000000000000:application:my-app";

    private final CodeDeployService service = mock(CodeDeployService.class);
    private final CodeDeployService codeDeploy = service;
    private final CodeDeployCfnProvisioner provisioner = new CodeDeployCfnProvisioner(codeDeploy);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        return ctx(priorPhysicalId, null);
    }

    private ProvisionContext ctx(String priorPhysicalId, JsonNode priorProperties) {
        return new ProvisionContext(ProvisionContexts.passthroughEngine(), "us-east-1", "000000000000", "my-stack",
                priorPhysicalId, event -> { }, priorProperties);
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("App");
        r.setResourceType("AWS::CodeDeploy::Application");
        r.setAttributes(new HashMap<>());
        return r;
    }

    /** A provisioned application whose tag keys {@code keys} the stack set. */
    private static StackResource owning(String... keys) {
        StackResource r = resource();
        r.getAttributes().put(CodeDeployCfnProvisioner.TAG_KEYS_ATTR,
                "[" + String.join(",", Arrays.stream(keys).map(k -> "\"" + k + "\"").toList()) + "]");
        return r;
    }

    private static Application app(String name) {
        Application a = new Application();
        a.setApplicationName(name);
        return a;
    }

    @Test
    void createsTheApplicationWithItsComputePlatformAndTags() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq("my-app"), eq("Lambda"), any()))
                .thenReturn(app("my-app"));
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app").put("ComputePlatform", "Lambda");
        props.putArray("Tags").add(mapper.createObjectNode().put("Key", "team").put("Value", "core"));
        StackResource r = resource();

        provisioner.provision(r, props, ctx(null));

        verify(codeDeploy).createApplication("us-east-1", "my-app", "Lambda",
                List.of(Map.of("Key", "team", "Value", "core")));
        assertEquals("my-app", r.getPhysicalId());
    }

    @Test
    void anUnnamedApplicationGetsAGeneratedName() {
        when(codeDeploy.createApplication(eq("us-east-1"), anyString(), eq(null), any()))
                .thenAnswer(inv -> app(inv.getArgument(1)));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode(), ctx(null));

        assertTrue(r.getPhysicalId().startsWith("my-stack-App-"), r.getPhysicalId());
    }

    @Test
    void aBlankWrittenIntoTheTemplateReachesCodeDeployToBeRejected() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq(""), any(), any()))
                .thenThrow(new AwsException("ApplicationNameRequiredException", "required", 400));
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "").put("ComputePlatform", "");

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, ctx(null)));

        verify(codeDeploy).createApplication("us-east-1", "", "", List.of());
    }

    @Test
    void aPropertyThatResolvesToNoValueIsAbsent() {
        when(codeDeploy.createApplication(eq("us-east-1"), anyString(), eq(null), any()))
                .thenAnswer(inv -> app(inv.getArgument(1)));
        ObjectNode props = mapper.createObjectNode();
        props.putObject("ApplicationName").put("Ref", "AWS::NoValue");
        props.putObject("ComputePlatform").put("Ref", "AWS::NoValue");
        StackResource r = resource();

        provisioner.provision(r, props, ctx(null));

        assertTrue(r.getPhysicalId().startsWith("my-stack-App-"), r.getPhysicalId());
    }

    @Test
    void anUpdateReconcilesTagsOnTheSameApplication() {
        when(codeDeploy.applicationArn("us-east-1", "my-app")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of(
                Map.of("Key", "team", "Value", "core"), Map.of("Key", "owner", "Value", "alice")));
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app");
        props.putArray("Tags").add(mapper.createObjectNode().put("Key", "team").put("Value", "edge"));
        StackResource r = owning("team", "owner");

        provisioner.provision(r, props, ctx("my-app"));

        verify(codeDeploy, never()).createApplication(anyString(), anyString(), any(), any());
        verify(codeDeploy).untagResource(ARN, List.of("owner"));
        verify(codeDeploy).tagResource(ARN, List.of(Map.of("Key", "team", "Value", "edge")));
        assertEquals("my-app", r.getPhysicalId());
    }

    @Test
    void anUnnamedApplicationKeepsItsNameAcrossUpdates() {
        when(codeDeploy.applicationArn("us-east-1", "my-stack-App-0123456789ab")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of());
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode(), ctx("my-stack-App-0123456789ab"));

        verify(codeDeploy, never()).createApplication(anyString(), anyString(), any(), any());
        assertEquals("my-stack-App-0123456789ab", r.getPhysicalId());
    }

    @Test
    void changingTheComputePlatformOfAnUnnamedApplicationReplacesIt() {
        when(codeDeploy.createApplication(eq("us-east-1"), anyString(), eq("ECS"), any()))
                .thenAnswer(inv -> app(inv.getArgument(1)));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode().put("ComputePlatform", "ECS"),
                ctx("my-stack-App-0123456789ab", mapper.createObjectNode().put("ComputePlatform", "Server")));

        assertTrue(r.getPhysicalId().startsWith("my-stack-App-"), r.getPhysicalId());
        assertNotEquals("my-stack-App-0123456789ab", r.getPhysicalId());
        verify(codeDeploy).createApplication(eq("us-east-1"), eq(r.getPhysicalId()), eq("ECS"), any());
    }

    @Test
    void changingTheComputePlatformOfANamedApplicationIsRefused() {
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app").put("ComputePlatform", "ECS");
        ObjectNode prior = mapper.createObjectNode().put("ApplicationName", "my-app").put("ComputePlatform", "Server");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, ctx("my-app", prior)));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(codeDeploy, never()).createApplication(anyString(), anyString(), any(), any());
    }

    @Test
    void renamingANamedApplicationCreatesTheNewName() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq("new-app"), any(), any())).thenReturn(app("new-app"));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode().put("ApplicationName", "new-app"),
                ctx("my-app", mapper.createObjectNode().put("ApplicationName", "my-app")));

        assertEquals("new-app", r.getPhysicalId());
        verify(codeDeploy).createApplication(eq("us-east-1"), eq("new-app"), any(), any());
    }

    @Test
    void droppingTheNameOfANamedApplicationGeneratesOne() {
        when(codeDeploy.createApplication(eq("us-east-1"), anyString(), any(), any()))
                .thenAnswer(inv -> app(inv.getArgument(1)));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode(),
                ctx("my-app", mapper.createObjectNode().put("ApplicationName", "my-app")));

        assertTrue(r.getPhysicalId().startsWith("my-stack-App-"), r.getPhysicalId());
        verify(codeDeploy).createApplication(eq("us-east-1"), eq(r.getPhysicalId()), any(), any());
    }

    @Test
    void removingAllTagsUntagsEveryKey() {
        when(codeDeploy.applicationArn("us-east-1", "my-app")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of(
                Map.of("Key", "team", "Value", "core"), Map.of("Key", "owner", "Value", "alice")));
        StackResource r = owning("team", "owner");

        provisioner.provision(r, mapper.createObjectNode().put("ApplicationName", "my-app"), ctx("my-app"));

        verify(codeDeploy).untagResource(eq(ARN),
                argThat(keys -> keys.size() == 2 && keys.containsAll(List.of("team", "owner"))));
        verify(codeDeploy, never()).tagResource(anyString(), any());
    }

    @Test
    void aTagAddedOutsideTheStackStays() {
        when(codeDeploy.applicationArn("us-east-1", "my-app")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of(
                Map.of("Key", "team", "Value", "core"), Map.of("Key", "outside", "Value", "kept")));

        provisioner.provision(owning("team"), mapper.createObjectNode().put("ApplicationName", "my-app"),
                ctx("my-app"));

        verify(codeDeploy).untagResource(ARN, List.of("team"));
    }

    /** An in-place update of my-app from team=core, owner=alice (both the stack's) to team=edge. */
    private StackResource updatedInPlace() {
        when(codeDeploy.applicationArn("us-east-1", "my-app")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of(
                Map.of("Key", "team", "Value", "core"), Map.of("Key", "owner", "Value", "alice")));
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app");
        props.putArray("Tags").add(mapper.createObjectNode().put("Key", "team").put("Value", "edge"));
        StackResource r = owning("owner", "team");
        provisioner.provision(r, props, ctx("my-app"));
        return r;
    }

    @Test
    void rollingBackAnInPlaceUpdatePutsTheTagsBack() {
        StackResource r = updatedInPlace();

        assertTrue(provisioner.rollbackUpdate(r));

        verify(codeDeploy).tagResource(eq(ARN), argThat(tags -> tags.size() == 2
                && tags.containsAll(List.of(Map.of("Key", "team", "Value", "core"),
                        Map.of("Key", "owner", "Value", "alice")))));
        assertEquals("[\"owner\",\"team\"]", r.getAttributes().get(CodeDeployCfnProvisioner.TAG_KEYS_ATTR));
        assertFalse(r.getAttributes().containsKey(CodeDeployCfnProvisioner.SNAPSHOT_ATTR));
    }

    @Test
    void aCommittedInPlaceUpdateHasNothingToRollBack() {
        StackResource r = updatedInPlace();

        provisioner.completeUpdate(r);

        assertFalse(r.getAttributes().containsKey(CodeDeployCfnProvisioner.SNAPSHOT_ATTR));
        assertEquals("[\"team\"]", r.getAttributes().get(CodeDeployCfnProvisioner.TAG_KEYS_ATTR));
        assertFalse(provisioner.rollbackUpdate(r));
    }

    @Test
    void aTagUpdateThatFailsPutsTheTagsBackBeforeFailing() {
        when(codeDeploy.applicationArn("us-east-1", "my-app")).thenReturn(ARN);
        when(codeDeploy.listTagsForResource(ARN)).thenReturn(List.of(Map.of("Key", "team", "Value", "core")));
        doThrow(new AwsException("InvalidTagsToAddException", "bad", 400)).doNothing()
                .when(codeDeploy).tagResource(eq(ARN), any());
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app");
        props.putArray("Tags")
                .add(mapper.createObjectNode().put("Key", "team").put("Value", "edge"))
                .add(mapper.createObjectNode().put("Key", "added").put("Value", "x"));

        assertThrows(AwsException.class, () -> provisioner.provision(owning("team"), props, ctx("my-app")));

        verify(codeDeploy).untagResource(ARN, List.of("added"));
        verify(codeDeploy).tagResource(ARN, List.of(Map.of("Key", "team", "Value", "core")));
    }

    /** Replaces an unnamed application by changing its ComputePlatform; returns the resource. */
    private StackResource replacedUnnamedApplication() {
        when(codeDeploy.createApplication(eq("us-east-1"), anyString(), eq("ECS"), any()))
                .thenAnswer(inv -> app(inv.getArgument(1)));
        StackResource r = resource();
        provisioner.provision(r, mapper.createObjectNode().put("ComputePlatform", "ECS"),
                ctx("old-app", mapper.createObjectNode().put("ComputePlatform", "Server")));
        return r;
    }

    @Test
    void completingAReplacementDeletesThePriorApplication() {
        StackResource r = replacedUnnamedApplication();
        String replacement = r.getPhysicalId();

        provisioner.completeUpdate(r);

        verify(codeDeploy).deleteApplication("us-east-1", "old-app");
        verify(codeDeploy, never()).deleteApplication("us-east-1", replacement);
    }

    @Test
    void rollingBackAReplacementDeletesTheNewApplicationAndRestoresTheOld() {
        StackResource r = replacedUnnamedApplication();
        String replacement = r.getPhysicalId();

        assertTrue(provisioner.rollbackUpdate(r));

        verify(codeDeploy).deleteApplication("us-east-1", replacement);
        verify(codeDeploy, never()).deleteApplication("us-east-1", "old-app");
        assertEquals("old-app", r.getPhysicalId());
    }

    /** The dispatcher's stub for App, as a stack created before this provisioner holds it. */
    private static StackResource stub() {
        StackResource r = resource();
        r.getAttributes().put("Arn", "arn:aws:stub:::App");
        return r;
    }

    @Test
    void anUpdateOfAStubCreatesTheApplicationUnderTheStubsId() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq("App-1a2b3c4d"), eq("Lambda"), any()))
                .thenReturn(app("App-1a2b3c4d"));
        StackResource r = stub();
        ObjectNode props = mapper.createObjectNode().put("ComputePlatform", "Lambda");

        provisioner.provision(r, props, ctx("App-1a2b3c4d", props.deepCopy()));

        verify(codeDeploy).createApplication(eq("us-east-1"), eq("App-1a2b3c4d"), eq("Lambda"), any());
        verify(codeDeploy, never()).tagResource(anyString(), any());
        verify(codeDeploy, never()).untagResource(anyString(), any());
        assertEquals("App-1a2b3c4d", r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey("Arn"), "the stub's Arn names nothing that exists");
    }

    @Test
    void rollingBackAStubMigrationDeletesTheApplicationAndRestoresTheStub() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq("App-1a2b3c4d"), any(), any()))
                .thenReturn(app("App-1a2b3c4d"));
        StackResource r = stub();
        provisioner.provision(r, mapper.createObjectNode(), ctx("App-1a2b3c4d", mapper.createObjectNode()));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(codeDeploy).deleteApplication("us-east-1", "App-1a2b3c4d");
        assertEquals("App-1a2b3c4d", r.getPhysicalId());
        assertEquals("arn:aws:stub:::App", r.getAttributes().get("Arn"));
    }

    @Test
    void anUpdateOfAStubThatNamesTheApplicationCreatesThatNameAndRollsItBack() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq("my-app"), any(), any())).thenReturn(app("my-app"));
        StackResource r = stub();
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app");

        provisioner.provision(r, props, ctx("App-1a2b3c4d", props.deepCopy()));

        verify(codeDeploy).createApplication(eq("us-east-1"), eq("my-app"), any(), any());
        assertEquals("my-app", r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey("Arn"), "the stub's Arn names nothing that exists");

        assertTrue(provisioner.rollbackUpdate(r));

        verify(codeDeploy).deleteApplication("us-east-1", "my-app");
        assertEquals("App-1a2b3c4d", r.getPhysicalId());
        assertEquals("arn:aws:stub:::App", r.getAttributes().get("Arn"));
    }

    @Test
    void committingAnUpdateOfAStubDeletesNothing() {
        when(codeDeploy.createApplication(eq("us-east-1"), eq("my-app"), any(), any())).thenReturn(app("my-app"));
        StackResource r = stub();
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app");
        provisioner.provision(r, props, ctx("App-1a2b3c4d", props.deepCopy()));

        provisioner.completeUpdate(r);

        verify(codeDeploy, never()).deleteApplication(anyString(), anyString());
    }

    @Test
    void clearUpdateDropsTheTagSnapshot() {
        StackResource r = updatedInPlace();
        assertTrue(r.getAttributes().containsKey(CodeDeployCfnProvisioner.SNAPSHOT_ATTR));

        provisioner.clearUpdate(r);

        assertFalse(r.getAttributes().containsKey(CodeDeployCfnProvisioner.SNAPSHOT_ATTR));
        assertFalse(provisioner.rollbackUpdate(r));
    }

    @Test
    void anUpdateOverAVanishedApplicationCreatesItAndARollbackDeletesIt() {
        when(codeDeploy.getApplication("us-east-1", "my-app"))
                .thenThrow(new AwsException("ApplicationDoesNotExistException", "gone", 400));
        when(codeDeploy.createApplication(eq("us-east-1"), eq("my-app"), any(), any())).thenReturn(app("my-app"));
        ObjectNode props = mapper.createObjectNode().put("ApplicationName", "my-app");
        props.putArray("Tags").add(mapper.createObjectNode().put("Key", "team").put("Value", "core"));
        StackResource r = owning("team");

        provisioner.provision(r, props, ctx("my-app", props.deepCopy()));

        verify(codeDeploy).createApplication("us-east-1", "my-app", null,
                List.of(Map.of("Key", "team", "Value", "core")));
        verify(codeDeploy, never()).tagResource(anyString(), any());
        verify(codeDeploy, never()).untagResource(anyString(), any());
        assertEquals("my-app", r.getPhysicalId());

        assertTrue(provisioner.rollbackUpdate(r));

        verify(codeDeploy).deleteApplication("us-east-1", "my-app");
        assertEquals("my-app", r.getPhysicalId());
        assertFalse(r.getAttributes().containsKey(CodeDeployCfnProvisioner.SNAPSHOT_ATTR));
    }

    @Test
    void aFailureReadingThePriorApplicationPropagatesAndCreatesNothing() {
        when(codeDeploy.getApplication("us-east-1", "my-app"))
                .thenThrow(new AwsException("ThrottlingException", "slow down", 400));

        assertThrows(AwsException.class, () -> provisioner.provision(owning(),
                mapper.createObjectNode().put("ApplicationName", "my-app"), ctx("my-app")));

        verify(codeDeploy, never()).createApplication(anyString(), anyString(), any(), any());
    }

    @Test
    void deleteRemovesTheApplication() {
        provisioner.delete("AWS::CodeDeploy::Application", "my-app", "us-east-1");
        verify(codeDeploy).deleteApplication("us-east-1", "my-app");
    }

    @Test
    void deletingAnApplicationThatIsAlreadyGoneSucceeds() {
        doThrow(new AwsException("ApplicationDoesNotExistException", "gone", 400))
                .when(codeDeploy).deleteApplication("us-east-1", "my-app");
        assertDoesNotThrow(() -> provisioner.delete("AWS::CodeDeploy::Application", "my-app", "us-east-1"));
    }

    @Test
    void anyOtherDeleteFailurePropagates() {
        doThrow(new AwsException("InternalFailure", "boom", 500))
                .when(codeDeploy).deleteApplication("us-east-1", "my-app");
        assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::CodeDeploy::Application", "my-app", "us-east-1"));
    }


private static final String REGION = "us-east-1";

@Test
    void replacementRollbackRetainsApplicationIdentityThroughFailedCleanupAndRetry() throws Exception {
        when(service.deploymentGroupArn(anyString(), anyString(), anyString()))
                .thenAnswer(call -> "arn:aws:codedeploy:us-east-1:000000000000:deploymentgroup:"
                        + call.getArgument(1) + "/" + call.getArgument(2));
        when(service.listTagsForResource(anyString())).thenReturn(List.of());
        StackResource resource = groupResource();
        provisioner.provision(resource, group("old-application", "old-group"), context(null));
        provisioner.provision(resource, group("new-application", "new-group"), context("old-group"));
        doThrow(new IllegalStateException("delete failed")).when(service)
                .deleteDeploymentGroup(REGION, "new-application", "new-group");

        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        assertEquals("old-group", resource.getPhysicalId());
        assertEquals("old-application", resource.getAttributes()
                .get(CfnRollback.USES_ATTR_PREFIX + "CodeDeployApplication"));
        verify(service, never()).deleteDeploymentGroup(REGION, "old-application", "old-group");

        doNothing().when(service).deleteDeploymentGroup(REGION, "new-application", "new-group");
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(service, times(2)).deleteDeploymentGroup(REGION, "new-application", "new-group");
        provisioner.clearUpdate(resource);
        assertEquals("{\"old-group\":\"old-application\"}", resource.getAttributes()
                .get(CfnRollback.CODEDEPLOY_GROUP_APPLICATIONS_ATTR));
        provisioner.delete(resource, REGION);
        verify(service).deleteDeploymentGroup(REGION, "old-application", "old-group");
    }

@Test
    void missingParentIdentityFailsWithoutSearchingOrDeletingAnotherApplication() {
        StackResource resource = groupResource();
        resource.setPhysicalId("shared-name");
        assertThrows(IllegalStateException.class, () -> provisioner.delete(resource, REGION));
        verifyNoInteractions(service);
    }

@Test
    void deletionToleratesOnlyMissingResourcesAndDoesNotHideAccessDenial() {
        StackResource resource = groupResource();
        resource.setPhysicalId("group");
        resource.getAttributes().put(CfnRollback.CODEDEPLOY_GROUP_APPLICATIONS_ATTR,
                "{\"group\":\"application\"}");
        doThrow(new AwsException("AccessDeniedException", "not allowed", 403)).when(service)
                .deleteDeploymentGroup(REGION, "application", "group");
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete(resource, REGION));
        assertEquals("AccessDeniedException", failure.getErrorCode());
        doThrow(new AwsException("DeploymentGroupDoesNotExistException", "already gone", 400)).when(service)
                .deleteDeploymentGroup(REGION, "application", "group");
        provisioner.delete(resource, REGION);
    }

@Test
    void configurationDeletionToleratesOnlyTheMissingConfigurationError() {
        StackResource resource = configurationResource();
        resource.setPhysicalId("config");
        AwsException denied = new AwsException("AccessDeniedException", "not allowed", 403);
        doThrow(denied).when(service).deleteDeploymentConfig(REGION, "config");
        assertSame(denied, assertThrows(AwsException.class, () -> provisioner.delete(resource, REGION)));
        doThrow(new AwsException("DeploymentConfigDoesNotExistException", "already gone", 400))
                .when(service).deleteDeploymentConfig(REGION, "config");
        provisioner.delete(resource, REGION);
    }

@Test
    void configurationReplacementRetriesFailedCleanupWithoutDeletingThePriorConfiguration() throws Exception {
        StackResource resource = configurationResource();
        provisioner.provision(resource, mapper.readTree("{\"DeploymentConfigName\":\"old\"}"), context(null));
        provisioner.provision(resource, mapper.readTree("{\"DeploymentConfigName\":\"new\"}"), context("old"));
        doThrow(new IllegalStateException("delete failed")).when(service).deleteDeploymentConfig(REGION, "new");
        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        assertEquals("old", resource.getPhysicalId());
        verify(service, never()).deleteDeploymentConfig(REGION, "old");
        doNothing().when(service).deleteDeploymentConfig(REGION, "new");
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(service, times(2)).deleteDeploymentConfig(REGION, "new");
        provisioner.clearUpdate(resource);
        provisioner.delete(resource, REGION);
        verify(service).deleteDeploymentConfig(REGION, "old");
    }

@Test
    void configurationRejectsInvalidNestedShapesBeforeCreatingAnything() throws Exception {
        for (String properties : List.of(
                "{\"MinimumHealthyHosts\":{\"Value\":1}}",
                "{\"MinimumHealthyHosts\":{\"Type\":\"HOST_COUNT\",\"Value\":\"not-a-number\"}}",
                "{\"TrafficRoutingConfig\":{\"Type\":\"TimeBasedCanary\",\"TimeBasedCanary\":[]}}",
                "{\"TrafficRoutingConfig\":{\"Type\":\"TimeBasedLinear\",\"Extra\":true}}")) {
            JsonNode parsed = mapper.readTree(properties);
            assertThrows(AwsException.class,
                    () -> provisioner.provision(configurationResource(), parsed, context(null)));
        }
        verifyNoInteractions(service);
    }

@Test
    void configurationPreservesTheSchemasInt64ZoneDuration() throws Exception {
        provisioner.provision(configurationResource(), mapper.readTree("""
                {"DeploymentConfigName":"config",
                 "ZonalConfig":{"MonitorDurationInSeconds":"4294967296"}}
                """), context(null));
        verify(service).createDeploymentConfig(eq(REGION), eq("config"), isNull(), eq("Server"), isNull(),
                eq(Map.of("monitorDurationInSeconds", 4294967296L)));
    }

private JsonNode group(String application, String group) {
        return mapper.createObjectNode().put("ApplicationName", application)
                .put("DeploymentGroupName", group).put("ServiceRoleArn", "arn:aws:iam::000000000000:role/deploy");
    }

private ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(call -> {
            JsonNode node = call.getArgument(0);
            return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(call -> call.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "stack", prior);
    }

private static StackResource configurationResource() {
        StackResource resource = groupResource();
        resource.setLogicalId("Config");
        resource.setResourceType("AWS::CodeDeploy::DeploymentConfig");
        return resource;
    }
private static StackResource groupResource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Group");
        resource.setResourceType("AWS::CodeDeploy::DeploymentGroup");
        resource.setAttributes(new HashMap<>());
        return resource;
    }
}
