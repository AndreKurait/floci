package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.AutoRollbackConfiguration;
import software.amazon.awssdk.services.codedeploy.model.CodeDeployException;
import software.amazon.awssdk.services.codedeploy.model.DeploymentInfo;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocation;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.iam.IamClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signed SDK orchestration with explicit agent mailbox replies; no guest application is executed. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CodeDeployAgentRollbackIntegrationTest {
    private static final String ACCOUNT = "333344445555";
    private static final String REGION = "eu-west-1";
    private static final List<String> PHASES = List.of("ApplicationStop", "DownloadBundle", "BeforeInstall",
            "Install", "AfterInstall", "ApplicationStart", "ValidateService");
    private static final List<String> TERMINAL = List.of("Succeeded", "Failed", "Stopped");
    private final Deque<Executable> cleanup = new ArrayDeque<>();

    @Inject CodeDeployAgentCommands commands;
    @TestHTTPResource URI endpoint;

    @AfterEach
    void deleteOwnedResources() {
        assertAll("CodeDeploy rollback resources", cleanup);
    }

    @Test
    void failureRedeploysTheSuccessfulVersionAndFileBehaviorWithNewLinkedIds() {
        Fixture selected = fixture(ACCOUNT, REGION, "rollback-" + UUID.randomUUID());
        Fixture otherAccount = fixture("666677778888", REGION, selected.name());
        Fixture otherRegion = fixture(ACCOUNT, "us-west-2", selected.name());
        RevisionLocation good = revision("good");
        String first = deploy(selected, good, "OVERWRITE", null);
        succeed(selected);
        assertEquals("Succeeded", terminal(selected, first).statusAsString());

        String failed = deploy(selected, revision("bad"), "RETAIN", null);
        selected.client().updateDeploymentGroup(request -> request.applicationName(selected.name())
                .currentDeploymentGroupName(selected.name())
                .autoRollbackConfiguration(configuration -> configuration.enabled(false)));
        fail(selected);
        DeploymentInfo original = terminal(selected, failed);
        assertEquals("Failed", original.statusAsString());
        assertNotNull(original.errorInformation());
        String rollback = original.rollbackInfo().rollbackDeploymentId();
        assertNotNull(rollback);
        assertNotEquals(first, rollback);
        assertNotEquals(failed, rollback);
        DeploymentInfo recovery = info(selected, rollback);
        assertEquals("codeDeployRollback", recovery.creatorAsString());
        assertEquals(failed, recovery.rollbackInfo().rollbackTriggeringDeploymentId());
        assertEquals(good, recovery.revision());
        assertEquals("OVERWRITE", recovery.fileExistsBehaviorAsString());
        succeed(selected);
        assertEquals("Succeeded", terminal(selected, rollback).statusAsString());
        assertEquals("Failed", info(selected, failed).statusAsString());
        for (Fixture other : List.of(otherAccount, otherRegion)) {
            assertTrue(ids(other).isEmpty());
            CodeDeployException denied = assertThrows(CodeDeployException.class, () -> info(other, rollback));
            assertEquals("DeploymentDoesNotExistException", denied.awsErrorDetails().errorCode());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExplicitDeploymentOverrideControlsFailureRecovery(boolean enabled) {
        Fixture selected = fixture();
        String first = deploy(selected, revision("good"), "DISALLOW", null);
        succeed(selected);
        terminal(selected, first);
        String failed = deploy(selected, revision("bad"), "DISALLOW", AutoRollbackConfiguration.builder()
                .enabled(enabled).eventsWithStrings("DEPLOYMENT_FAILURE").build());
        fail(selected);
        DeploymentInfo result = terminal(selected, failed);
        assertEquals("Failed", result.statusAsString());
        assertEquals(enabled, result.autoRollbackConfiguration().enabled());
        if (enabled) {
            succeed(selected);
            assertEquals("Succeeded", terminal(selected, result.rollbackInfo().rollbackDeploymentId()).statusAsString());
        } else {
            assertNull(result.rollbackInfo());
            assertEquals(2, ids(selected).size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExplicitStopOnlyStartsRecoveryWhenRequested(boolean rollbackRequested) {
        Fixture selected = fixture();
        String first = deploy(selected, revision("good"), "DISALLOW", null);
        succeed(selected);
        terminal(selected, first);
        String stopped = deploy(selected, revision("pending"), "DISALLOW", null);
        poll(selected, "ApplicationStop");
        assertEquals("Pending", selected.client().stopDeployment(request -> request.deploymentId(stopped)
                .autoRollbackEnabled(rollbackRequested)).statusAsString());
        DeploymentInfo result = terminal(selected, stopped);
        assertEquals("Stopped", result.statusAsString());
        if (rollbackRequested) {
            String recovery = result.rollbackInfo().rollbackDeploymentId();
            succeed(selected);
            assertEquals("Succeeded", terminal(selected, recovery).statusAsString());
        } else {
            assertNull(result.rollbackInfo());
            assertEquals(2, ids(selected).size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anActiveHookKeepsStopPendingAndTheGroupReservedUntilItsAgentCompletes(boolean rollbackRequested) {
        Fixture selected = fixture();
        String first = deploy(selected, revision("good"), "DISALLOW", null);
        succeed(selected);
        terminal(selected, first);
        String stopped = deploy(selected, revision("active"), "DISALLOW", null);
        String command = (String) poll(selected, "ApplicationStop").get("HostCommandIdentifier");
        commands.acknowledge(selected.account(), selected.region(), command);
        assertEquals("Pending", selected.client().stopDeployment(request -> request.deploymentId(stopped)
                .autoRollbackEnabled(rollbackRequested)).statusAsString());
        try {
            await().during(Duration.ofMillis(350)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertEquals("InProgress", info(selected, stopped).statusAsString());
                assertNull(info(selected, stopped).rollbackInfo());
                assertEquals(2, ids(selected).size());
            });
            CodeDeployException conflict = assertThrows(CodeDeployException.class,
                    () -> deploy(selected, revision("overlap"), "DISALLOW", null));
            assertEquals("DeploymentLimitExceededException", conflict.awsErrorDetails().errorCode());
        } finally {
            if ("InProgress".equals(commands.update(selected.account(), selected.region(), command)
                    .get("CommandStatus"))) {
                commands.complete(selected.account(), selected.region(), command, "Succeeded", "{\"error_code\":0}");
            }
        }
        DeploymentInfo result = terminal(selected, stopped);
        assertEquals("Stopped", result.statusAsString());
        if (rollbackRequested) {
            String recovery = result.rollbackInfo().rollbackDeploymentId();
            succeed(selected);
            assertEquals("Succeeded", terminal(selected, recovery).statusAsString());
        } else {
            assertNull(result.rollbackInfo());
            assertEquals(2, ids(selected).size());
        }
    }

    @Test
    void theFirstFailureHasNoPreviousRevisionToRedeploy() {
        Fixture selected = fixture();
        String failed = deploy(selected, revision("first-bad"), "DISALLOW", null);
        fail(selected);
        DeploymentInfo result = terminal(selected, failed);
        assertEquals("Failed", result.statusAsString());
        assertNull(result.rollbackInfo().rollbackDeploymentId());
        assertTrue(result.rollbackInfo().rollbackMessage().contains("No successful"));
        assertEquals(List.of(failed), ids(selected));
    }

    @Test
    void aFailedRollbackDoesNotRecurseOrReplaceTheLastSuccessfulRevision() {
        Fixture selected = fixture();
        String first = deploy(selected, revision("good"), "DISALLOW", null);
        succeed(selected);
        terminal(selected, first);
        String failed = deploy(selected, revision("bad"), "DISALLOW", null);
        fail(selected);
        String recovery = terminal(selected, failed).rollbackInfo().rollbackDeploymentId();
        fail(selected);
        assertEquals("Failed", terminal(selected, recovery).statusAsString());
        assertEquals(3, ids(selected).size());
        assertEquals(recovery, info(selected, recovery).rollbackInfo().rollbackDeploymentId());

        String second = deploy(selected, revision("another-bad"), "DISALLOW", null);
        fail(selected);
        String nextRecovery = terminal(selected, second).rollbackInfo().rollbackDeploymentId();
        assertEquals(revision("good"), info(selected, nextRecovery).revision());
        succeed(selected);
        assertEquals("Succeeded", terminal(selected, nextRecovery).statusAsString());
    }

    @Test
    void deletionDuringFailureCannotStartRecoveryInAReplacementGroupWithTheSameName() {
        Fixture selected = fixture();
        String first = deploy(selected, revision("good"), "DISALLOW", null);
        succeed(selected);
        terminal(selected, first);
        String failed = deploy(selected, revision("bad"), "DISALLOW", null);
        selected.client().deleteDeploymentGroup(request -> request.applicationName(selected.name())
                .deploymentGroupName(selected.name()));
        createGroup(selected);
        fail(selected);
        DeploymentInfo result = terminal(selected, failed);
        assertNull(result.rollbackInfo().rollbackDeploymentId());
        assertTrue(result.rollbackInfo().rollbackMessage().contains("no longer exists"));
        String replacement = deploy(selected, revision("replacement-bad"), "DISALLOW", null);
        fail(selected);
        assertNull(terminal(selected, replacement).rollbackInfo().rollbackDeploymentId());
        assertEquals(3, ids(selected).size());
    }

    @Test
    void aSecondDeploymentCannotOverlapAnActiveGroupAndStopReleasesTheGroup() {
        Fixture selected = fixture();
        String active = deploy(selected, revision("active"), "DISALLOW", null);
        poll(selected, "ApplicationStop");
        CodeDeployException rejected = assertThrows(CodeDeployException.class, () ->
                deploy(selected, revision("overlap"), "DISALLOW", null));
        assertEquals("DeploymentLimitExceededException", rejected.awsErrorDetails().errorCode());
        assertEquals(List.of(active), ids(selected));
        selected.client().stopDeployment(request -> request.deploymentId(active).autoRollbackEnabled(false));
        assertEquals("Stopped", terminal(selected, active).statusAsString());
        String next = deploy(selected, revision("next"), "DISALLOW", null);
        succeed(selected);
        assertEquals("Succeeded", terminal(selected, next).statusAsString());
    }

    @Test
    void groupRenameRetainsItsSuccessfulRevision() {
        Fixture selected = fixture();
        String first = deploy(selected, revision("good"), "DISALLOW", null);
        succeed(selected);
        terminal(selected, first);
        String renamed = selected.name() + "-renamed";
        selected.client().updateDeploymentGroup(request -> request.applicationName(selected.name())
                .currentDeploymentGroupName(selected.name()).newDeploymentGroupName(renamed));
        String failed = selected.client().createDeployment(request -> request.applicationName(selected.name())
                .deploymentGroupName(renamed).revision(revision("bad"))).deploymentId();
        fail(selected);
        String recovery = terminal(selected, failed).rollbackInfo().rollbackDeploymentId();
        assertEquals(renamed, info(selected, recovery).deploymentGroupName());
        succeed(selected);
        assertEquals("Succeeded", terminal(selected, recovery).statusAsString());
    }

    private Fixture fixture() {
        return fixture(ACCOUNT, REGION, "rollback-" + UUID.randomUUID());
    }

    private Fixture fixture(String account, String region, String name) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"));
        Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        CodeDeployClient client = CodeDeployClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        cleanup.addFirst(ec2::close);
        cleanup.addFirst(iam::close);
        cleanup.addFirst(client::close);
        String vpc = ec2.createVpc(request -> request.cidrBlock("10.97.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.97.1.0/24")).subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(request -> request.subnetId(subnet)));
        String instance = ec2.runInstances(request -> request.imageId("ami-rollback-fixture").instanceType("t3.micro")
                .subnetId(subnet).minCount(1).maxCount(1).tagSpecifications(tags -> tags.resourceType("instance")
                        .tags(tag -> tag.key("Deployment").value(name)))).instances().getFirst().instanceId();
        cleanup.addFirst(() -> ec2.terminateInstances(request -> request.instanceIds(instance)));
        String role = iam.createRole(request -> request.roleName(name + "-" + region).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"Service":"codedeploy.amazonaws.com"}}]}""")).role().arn();
        cleanup.addFirst(() -> iam.deleteRole(request -> request.roleName(name + "-" + region)));
        client.createApplication(request -> request.applicationName(name).computePlatform("Server"));
        cleanup.addFirst(() -> client.deleteApplication(request -> request.applicationName(name)));
        Fixture selected = new Fixture(account, region, name, instance, role, client);
        createGroup(selected);
        cleanup.addFirst(() -> await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<String> active = ids(selected).stream().filter(id -> !TERMINAL.contains(info(selected, id).statusAsString()))
                    .toList();
            active.forEach(id -> client.stopDeployment(request -> request.deploymentId(id).autoRollbackEnabled(false)));
            assertTrue(active.isEmpty(), "Active deployments must stop before deleting their application");
        }));
        return selected;
    }

    private static void createGroup(Fixture selected) {
        selected.client().createDeploymentGroup(request -> request.applicationName(selected.name())
                .deploymentGroupName(selected.name()).serviceRoleArn(selected.role())
                .ec2TagFilters(tag -> tag.key("Deployment").value(selected.name()).type("KEY_AND_VALUE"))
                .autoRollbackConfiguration(configuration -> configuration.enabled(true)
                        .eventsWithStrings("DEPLOYMENT_FAILURE")));
    }

    private static RevisionLocation revision(String version) {
        return RevisionLocation.builder().revisionType("S3").s3Location(location -> location.bucket("revisions")
                .key("revision.zip").bundleType("zip").version(version).eTag("etag-" + version)).build();
    }

    private static String deploy(Fixture selected, RevisionLocation revision, String behavior,
                                 AutoRollbackConfiguration override) {
        return selected.client().createDeployment(request -> request.applicationName(selected.name())
                .deploymentGroupName(selected.name()).revision(revision).fileExistsBehavior(behavior)
                .autoRollbackConfiguration(override)).deploymentId();
    }

    private Map<?, ?> poll(Fixture selected, String phase) {
        String host = AwsArnUtils.Arn.of("ec2", selected.region(), selected.account(),
                "instance/" + selected.instance()).toString();
        Map<String, Object> result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> commands.poll(selected.account(), selected.region(), host));
        Map<?, ?> command = (Map<?, ?>) result.get("HostCommand");
        assertNotNull(command, phase);
        assertEquals(phase, command.get("CommandName"));
        return command;
    }

    private void complete(Fixture selected, String phase, boolean success) {
        String id = (String) poll(selected, phase).get("HostCommandIdentifier");
        commands.acknowledge(selected.account(), selected.region(), id);
        commands.complete(selected.account(), selected.region(), id, success ? "Succeeded" : "Failed",
                success ? "{\"error_code\":0}" : "{\"error_code\":4,\"message\":\"application hook failed\"}");
    }

    private void succeed(Fixture selected) {
        PHASES.forEach(phase -> complete(selected, phase, true));
    }

    private void fail(Fixture selected) {
        for (String phase : PHASES.subList(0, 5)) {
            complete(selected, phase, !"AfterInstall".equals(phase));
        }
    }

    private static DeploymentInfo info(Fixture selected, String id) {
        return selected.client().getDeployment(request -> request.deploymentId(id)).deploymentInfo();
    }

    private static DeploymentInfo terminal(Fixture selected, String id) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> info(selected, id),
                deployment -> TERMINAL.contains(deployment.statusAsString()));
    }

    private static List<String> ids(Fixture selected) {
        return selected.client().listDeployments(request -> request.applicationName(selected.name())).deployments();
    }

    private record Fixture(String account, String region, String name, String instance,
                           String role, CodeDeployClient client) {}
}
