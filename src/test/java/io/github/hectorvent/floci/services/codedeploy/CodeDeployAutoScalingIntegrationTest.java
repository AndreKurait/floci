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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.LifecycleHook;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.CodeDeployException;
import software.amazon.awssdk.services.codedeploy.model.DeploymentInfo;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocation;
import software.amazon.awssdk.services.iam.IamClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signed SDK control-plane checks with explicit mailbox replies, without executing a guest application. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CodeDeployAutoScalingIntegrationTest {
    private static final String ACCOUNT = "717171717171";
    private static final String REGION = "eu-west-1";
    private static final Set<String> TERMINAL = Set.of("Succeeded", "Failed", "Stopped");
    private static final List<String> PHASES = List.of("ApplicationStop", "DownloadBundle", "BeforeInstall",
            "Install", "AfterInstall", "ApplicationStart", "ValidateService");
    private static final List<String> LAUNCH_PHASES = List.of("ApplicationStop", "DownloadBundle", "BeforeInstall",
            "Install", "AfterInstall", "ApplicationStart", "ValidateService", "BeforeAllowTraffic", "AfterAllowTraffic");
    private final Deque<Executable> cleanup = new ArrayDeque<>();

    @Inject CodeDeployAgentCommands commands;
    @TestHTTPResource URI endpoint;

    @AfterEach
    void removeOwnedResources() {
        assertAll("Managed CodeDeploy launch resources", cleanup);
    }

    @Test
    void firstLaunchWithoutARevisionCanReachServiceWithoutADeployment() {
        Fixture selected = fixture(0);
        LifecycleHook hook = managedHook(selected);
        assertEquals("autoscaling:EC2_INSTANCE_LAUNCHING", hook.lifecycleTransition());
        assertEquals(600, hook.heartbeatTimeout());
        assertEquals("ABANDON", hook.defaultResult());
        String metadata = selected.deployments().getDeploymentGroup(request -> request.applicationName(selected.name())
                .deploymentGroupName(selected.name())).deploymentGroupInfo().autoScalingGroups().getFirst().hook();
        assertEquals(hook.lifecycleHookName(), metadata);

        capacity(selected, 1);
        awaitState(selected, 1, "InService");
        assertTrue(ids(selected).isEmpty());
    }

    @Test
    void scaleOutInstallsTheSuccessfulRevisionOnOnlyTheNewInstance() {
        Fixture selected = fixture(1);
        String original = group(selected).instances().getFirst().instanceId();
        String initial = deploy(selected, "good");
        succeed(selected, original, PHASES);
        assertEquals("Succeeded", terminal(selected, initial).statusAsString());

        capacity(selected, 2);
        String automatic = automatic(selected, Set.of(initial));
        DeploymentInfo info = info(selected, automatic);
        assertEquals(revision("good"), info.revision());
        assertEquals("OVERWRITE", info.fileExistsBehaviorAsString());
        String launched = target(selected, automatic);
        assertNotEquals(original, launched);
        assertEquals("Pending:Wait", instanceState(selected, launched));
        succeed(selected, launched, LAUNCH_PHASES);
        assertEquals("Succeeded", terminal(selected, automatic).statusAsString());
        awaitState(selected, 2, "InService");
        assertEquals(Set.of(initial, automatic), Set.copyOf(ids(selected)));
    }

    @Test
    void aLateOldLaunchDoesNotReplaceTheNewRevisionAndGetsAFollowOnUpdate() {
        Fixture selected = fixture(1);
        String original = group(selected).instances().getFirst().instanceId();
        String initial = deploy(selected, "old");
        succeed(selected, original, PHASES);
        terminal(selected, initial);
        String current = deploy(selected, "new");

        capacity(selected, 2);
        String launch = automatic(selected, Set.of(initial, current));
        String launched = target(selected, launch);
        assertEquals(revision("old"), info(selected, launch).revision());
        complete(selected, launched, "ApplicationStop", true);
        succeed(selected, original, PHASES);
        assertEquals("Succeeded", terminal(selected, current).statusAsString());

        succeed(selected, launched, LAUNCH_PHASES.subList(1, LAUNCH_PHASES.size()));
        assertEquals("Succeeded", terminal(selected, launch).statusAsString());
        String followup = await().atMost(Duration.ofSeconds(20)).until(
                () -> ids(selected).stream().filter(id -> "CodeDeploy".equals(info(selected, id).creatorAsString()))
                        .findFirst().orElse(null), id -> id != null);
        assertEquals(revision("new"), info(selected, followup).revision());
        assertEquals(launched, target(selected, followup));
        succeed(selected, launched, PHASES);
        assertEquals("Succeeded", terminal(selected, followup).statusAsString());
        awaitState(selected, 2, "InService");

        Set<String> previous = Set.copyOf(ids(selected));
        capacity(selected, 3);
        String next = automatic(selected, previous);
        assertEquals(revision("new"), info(selected, next).revision());
        succeed(selected, target(selected, next), LAUNCH_PHASES);
        assertEquals("Succeeded", terminal(selected, next).statusAsString());
        awaitState(selected, 3, "InService");
    }

    @Test
    void failedAutomaticDeploymentAbandonsAndReplacesTheWaitingInstance() {
        Fixture selected = fixture(1);
        String initial = deploy(selected, "good");
        succeed(selected, group(selected).instances().getFirst().instanceId(), PHASES);
        terminal(selected, initial);
        capacity(selected, 2);
        String automatic = automatic(selected, Set.of(initial));
        String abandoned = target(selected, automatic);
        complete(selected, abandoned, "ApplicationStop", false);
        assertEquals("Failed", terminal(selected, automatic).statusAsString());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            AutoScalingGroup group = group(selected);
            assertEquals(2, group.instances().size());
            assertFalse(group.instances().stream().anyMatch(instance -> abandoned.equals(instance.instanceId())));
            assertTrue(group.instances().stream().anyMatch(instance -> "Pending:Wait".equals(instance.lifecycleStateAsString())));
        });
    }

    @ParameterizedTest
    @CsvSource({"727272727272,eu-west-1", "717171717171,us-west-2"})
    void sameNamedGroupsKeepAutomaticTargetsAndRevisionHistoryInTheirOwner(String account, String region) {
        String name = "auto-scope-" + UUID.randomUUID();
        Fixture owner = fixture(ACCOUNT, REGION, name, 1);
        Fixture other = fixture(account, region, name, 1);
        String ownerInitial = deploy(owner, "owner");
        succeed(owner, group(owner).instances().getFirst().instanceId(), PHASES);
        terminal(owner, ownerInitial);
        String otherInitial = deploy(other, "other");
        succeed(other, group(other).instances().getFirst().instanceId(), PHASES);
        terminal(other, otherInitial);
        capacity(owner, 2);
        capacity(other, 2);
        String ownerLaunch = automatic(owner, Set.of(ownerInitial));
        String otherLaunch = automatic(other, Set.of(otherInitial));
        assertEquals(revision("owner"), info(owner, ownerLaunch).revision());
        assertEquals(revision("other"), info(other, otherLaunch).revision());
        assertNotEquals(target(owner, ownerLaunch), target(other, otherLaunch));
        assertEquals("DeploymentDoesNotExistException",
                assertThrows(CodeDeployException.class, () -> info(other, ownerLaunch)).awsErrorDetails().errorCode());
        succeed(owner, target(owner, ownerLaunch), LAUNCH_PHASES);
        succeed(other, target(other, otherLaunch), LAUNCH_PHASES);
        assertEquals("Succeeded", terminal(owner, ownerLaunch).statusAsString());
        assertEquals("Succeeded", terminal(other, otherLaunch).statusAsString());
        awaitState(owner, 2, "InService");
        awaitState(other, 2, "InService");
    }

    @Test
    void detachDeletesTheHookAndInvalidOrDeniedAttachmentsLeaveTheGroupUnchanged() {
        Fixture selected = fixture(0);
        assertTrue(selected.deployments().updateDeploymentGroup(request -> request.applicationName(selected.name())
                .currentDeploymentGroupName(selected.name()).autoScalingGroups(List.of())).hooksNotCleanedUp().isEmpty());
        assertTrue(hooks(selected).isEmpty());

        policy(selected, "autoscaling:PutLifecycleHook");
        CodeDeployException denied = assertThrows(CodeDeployException.class, () ->
                selected.deployments().updateDeploymentGroup(request -> request.applicationName(selected.name())
                        .currentDeploymentGroupName(selected.name()).autoScalingGroups(selected.name())));
        assertEquals("InvalidRoleException", denied.awsErrorDetails().errorCode());
        assertTrue(hooks(selected).isEmpty());
        CodeDeployException missing = assertThrows(CodeDeployException.class, () ->
                selected.deployments().updateDeploymentGroup(request -> request.applicationName(selected.name())
                        .currentDeploymentGroupName(selected.name()).autoScalingGroups("absent-" + selected.name())));
        assertEquals("InvalidAutoScalingGroupException", missing.awsErrorDetails().errorCode());
        assertTrue(selected.deployments().getDeploymentGroup(request -> request.applicationName(selected.name())
                .deploymentGroupName(selected.name())).deploymentGroupInfo().autoScalingGroups().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deniedHookCleanupIsReportedAndUnrelatedHooksArePreserved(boolean delete) {
        Fixture selected = fixture(0);
        String managed = managedHook(selected).lifecycleHookName();
        selected.scaling().putLifecycleHook(request -> request.autoScalingGroupName(selected.name())
                .lifecycleHookName("custom").lifecycleTransition("autoscaling:EC2_INSTANCE_LAUNCHING")
                .heartbeatTimeout(300).defaultResult("CONTINUE"));
        policy(selected, "autoscaling:DeleteLifecycleHook");
        // CodeDeploy's AutoScalingGroup describes a hook reference, unlike the fleet model imported above.
        List<software.amazon.awssdk.services.codedeploy.model.AutoScalingGroup> remaining = delete
                ? selected.deployments().deleteDeploymentGroup(request -> request.applicationName(selected.name())
                        .deploymentGroupName(selected.name())).hooksNotCleanedUp()
                : selected.deployments().updateDeploymentGroup(request -> request.applicationName(selected.name())
                        .currentDeploymentGroupName(selected.name()).autoScalingGroups(List.of())).hooksNotCleanedUp();
        assertEquals(1, remaining.size());
        assertEquals(selected.name(), remaining.getFirst().name());
        assertEquals(managed, remaining.getFirst().hook());
        assertEquals(Set.of(managed, "custom"),
                Set.copyOf(hooks(selected).stream().map(LifecycleHook::lifecycleHookName).toList()));
        selected.scaling().deleteLifecycleHook(request -> request.autoScalingGroupName(selected.name())
                .lifecycleHookName(managed));
        assertEquals(List.of("custom"), hooks(selected).stream().map(LifecycleHook::lifecycleHookName).toList());
    }

    private Fixture fixture(int desired) {
        return fixture(ACCOUNT, REGION, "auto-launch-" + UUID.randomUUID(), desired);
    }

    private Fixture fixture(String account, String region, String name, int desired) {
        String roleName = name + "-" + region;
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"));
        AutoScalingClient scaling = AutoScalingClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        CodeDeployClient deployments = CodeDeployClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.addFirst(scaling::close);
        cleanup.addFirst(deployments::close);
        cleanup.addFirst(iam::close);
        scaling.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
        cleanup.addFirst(() -> scaling.deleteLaunchConfiguration(request -> request.launchConfigurationName(name)));
        scaling.createAutoScalingGroup(request -> request.autoScalingGroupName(name).launchConfigurationName(name)
                .minSize(0).maxSize(3).desiredCapacity(desired).availabilityZones(region + "a"));
        cleanup.addFirst(() -> scaling.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true)));
        String role = iam.createRole(request -> request.roleName(roleName).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                "Principal":{"Service":"codedeploy.amazonaws.com"}}]}""")).role().arn();
        cleanup.addFirst(() -> iam.deleteRole(request -> request.roleName(roleName)));
        Fixture selected = new Fixture(account, region, name, roleName, scaling, deployments, iam);
        policy(selected, null);
        cleanup.addFirst(() -> iam.deleteRolePolicy(request -> request.roleName(roleName).policyName("launch")));
        deployments.createApplication(request -> request.applicationName(name).computePlatform("Server"));
        cleanup.addFirst(() -> deployments.deleteApplication(request -> request.applicationName(name)));
        awaitState(selected, desired, "InService");
        deployments.createDeploymentGroup(request -> request.applicationName(name).deploymentGroupName(name)
                .serviceRoleArn(role).autoScalingGroups(name));
        cleanup.addFirst(() -> await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<String> active = ids(selected).stream().filter(id -> !TERMINAL.contains(info(selected, id).statusAsString()))
                    .toList();
            active.forEach(id -> deployments.stopDeployment(request -> request.deploymentId(id).autoRollbackEnabled(false)));
            assertTrue(active.isEmpty(), "Stop owned deployments before deleting their application");
        }));
        cleanup.addFirst(() -> capacity(selected, 0));
        return selected;
    }

    private static void policy(Fixture fixture, String deny) {
        String denial = deny == null ? "" : ",{\"Effect\":\"Deny\",\"Action\":\"" + deny + "\",\"Resource\":\"*\"}";
        fixture.iam().putRolePolicy(request -> request.roleName(fixture.roleName()).policyName("launch")
                .policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"autoscaling:*\",\"Resource\":\"*\"}" + denial + "]}"));
    }

    private static void capacity(Fixture fixture, int desired) {
        fixture.scaling().setDesiredCapacity(request -> request.autoScalingGroupName(fixture.name()).desiredCapacity(desired));
    }

    private static AutoScalingGroup group(Fixture fixture) {
        return fixture.scaling().describeAutoScalingGroups(request -> request.autoScalingGroupNames(fixture.name()))
                .autoScalingGroups().getFirst();
    }

    private static void awaitState(Fixture fixture, int count, String state) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            AutoScalingGroup group = group(fixture);
            assertEquals(count, group.instances().size());
            assertTrue(group.instances().stream().allMatch(instance -> state.equals(instance.lifecycleStateAsString())));
        });
    }

    private static String instanceState(Fixture fixture, String instance) {
        return group(fixture).instances().stream().filter(value -> instance.equals(value.instanceId()))
                .findFirst().orElseThrow().lifecycleStateAsString();
    }

    private static List<LifecycleHook> hooks(Fixture fixture) {
        return fixture.scaling().describeLifecycleHooks(request -> request.autoScalingGroupName(fixture.name())).lifecycleHooks();
    }

    private static LifecycleHook managedHook(Fixture fixture) {
        assertEquals(1, hooks(fixture).size());
        return hooks(fixture).getFirst();
    }

    private static RevisionLocation revision(String version) {
        return RevisionLocation.builder().revisionType("S3").s3Location(location -> location.bucket("revisions")
                .key("application.zip").bundleType("zip").version(version).eTag("etag-" + version)).build();
    }

    private static String deploy(Fixture fixture, String version) {
        return fixture.deployments().createDeployment(request -> request.applicationName(fixture.name())
                .deploymentGroupName(fixture.name()).revision(revision(version)).fileExistsBehavior("OVERWRITE")).deploymentId();
    }

    private static List<String> ids(Fixture fixture) {
        return fixture.deployments().listDeployments(request -> request.applicationName(fixture.name())).deployments();
    }

    private static DeploymentInfo info(Fixture fixture, String id) {
        return fixture.deployments().getDeployment(request -> request.deploymentId(id)).deploymentInfo();
    }

    private static DeploymentInfo terminal(Fixture fixture, String id) {
        return await().atMost(Duration.ofSeconds(20)).until(
                () -> info(fixture, id), deployment -> TERMINAL.contains(deployment.statusAsString()));
    }

    private static String automatic(Fixture fixture, Set<String> previous) {
        return await().atMost(Duration.ofSeconds(20)).until(() -> ids(fixture).stream()
                .filter(id -> !previous.contains(id) && "autoScaling".equals(info(fixture, id).creatorAsString()))
                .findFirst().orElse(null), id -> id != null);
    }

    private static String target(Fixture fixture, String deployment) {
        List<String> targets = fixture.deployments().listDeploymentTargets(request -> request.deploymentId(deployment)).targetIds();
        assertEquals(1, targets.size());
        return targets.getFirst();
    }

    private void succeed(Fixture fixture, String instance, List<String> phases) {
        phases.forEach(phase -> complete(fixture, instance, phase, true));
    }

    private void complete(Fixture fixture, String instance, String phase, boolean succeeded) {
        String host = AwsArnUtils.Arn.of("ec2", fixture.region(), fixture.account(), "instance/" + instance).toString();
        Map<String, Object> response = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> commands.poll(fixture.account(), fixture.region(), host));
        Map<?, ?> command = (Map<?, ?>) response.get("HostCommand");
        assertNotNull(command, phase);
        assertEquals(phase, command.get("CommandName"));
        String id = (String) command.get("HostCommandIdentifier");
        commands.acknowledge(fixture.account(), fixture.region(), id);
        commands.complete(fixture.account(), fixture.region(), id, succeeded ? "Succeeded" : "Failed",
                succeeded ? "{\"error_code\":0}" : "{\"error_code\":4,\"message\":\"fixture hook failed\"}");
    }

    private record Fixture(String account, String region, String name, String roleName,
                            AutoScalingClient scaling, CodeDeployClient deployments, IamClient iam) {}
}
