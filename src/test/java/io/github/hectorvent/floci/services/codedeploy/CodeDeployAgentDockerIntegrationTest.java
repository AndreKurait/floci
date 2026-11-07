package io.github.hectorvent.floci.services.codedeploy;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.DeploymentInfo;
import software.amazon.awssdk.services.codedeploy.model.DeploymentStatus;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocation;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stock AWS agent protocol, revision selection, script failures and recovery. */
@QuarkusTest
@TestProfile(CodeDeployAgentDockerFixture.AgentProfile.class)
class CodeDeployAgentDockerIntegrationTest extends CodeDeployAgentDockerFixture {
    @ParameterizedTest
    @ValueSource(strings = {"CodeDeployDefault.OneAtATime", "CodeDeployDefault.HalfAtATime"})
    void autoScalingGuestsInstallTheSelectedS3VersionReportHookFailureAndRecover(String configName) throws Exception {
        Fleet fleet = fleet();
        String name = fleet.name();
        S3Client s3 = fleet.s3();
        CodeDeployClient deployments = fleet.deployments();
        deployments.updateDeploymentGroup(request -> request.applicationName(name).currentDeploymentGroupName(name)
                .deploymentConfigName(configName));
        List<String> targets = fleet.targets();
        RevisionLocation original = upload(s3, name, "revision.zip", bundle("original", false));
        upload(s3, name, "revision.zip", bundle("unselected-newer-version", false));
        for (String target : targets) {
            output(target, "sh", "-c", "mkdir -p /opt/floci-agent-proof"
                    + " && printf before-deployment > /opt/floci-agent-proof/payload.txt");
        }
        String first = deploy(deployments, name, original, "OVERWRITE");
        assertDeployment(deployments, first, DeploymentStatus.SUCCEEDED);
        for (String target : targets) {
            assertInstalledPayload(target, "original");
            assertEvents(target, first, List.of("BeforeInstall", "AfterInstall", "ApplicationStart", "ValidateService"));
        }
        assertEquals(2, deployments.listDeploymentTargets(request -> request.deploymentId(first)).targetIds().size());
        assertEquals("OVERWRITE", deployments.getDeployment(request -> request.deploymentId(first))
                .deploymentInfo().fileExistsBehaviorAsString());

        for (String target : targets) {
            output(target, "sh", "-c", "printf existing-unmanaged > /opt/floci-agent-proof/unmanaged.txt");
        }
        RevisionLocation conflict = upload(s3, name, "conflict.zip", bundle("conflict", false, true));
        String disallowed = deploy(deployments, name, conflict);
        assertDeployment(deployments, disallowed, DeploymentStatus.FAILED);
        for (String target : targets) {
            assertEquals("existing-unmanaged", output(target, "cat", "/opt/floci-agent-proof/unmanaged.txt"));
            assertFalse(output(target, "cat", "/var/lib/floci-agent-events")
                    .contains(disallowed + " ValidateService"));
        }
        String retained = deploy(deployments, name, conflict, "RETAIN");
        assertDeployment(deployments, retained, DeploymentStatus.SUCCEEDED);
        for (String target : targets) {
            assertEquals("conflict", output(target, "cat", "/opt/floci-agent-proof/payload.txt").trim());
            assertEquals("existing-unmanaged", output(target, "cat", "/opt/floci-agent-proof/unmanaged.txt"));
        }

        String failed = deploy(deployments, name, upload(s3, name, "failed.zip", bundle("failed", true)));
        assertDeployment(deployments, failed, DeploymentStatus.FAILED);
        List<String> failedTargets = deployments.listDeploymentTargets(request -> request.deploymentId(failed)).targetIds();
        assertTrue(failedTargets.stream().map(target -> deployments.getDeploymentTarget(request ->
                        request.deploymentId(failed).targetId(target)).deploymentTarget().instanceTarget())
                .flatMap(target -> target.lifecycleEvents().stream())
                .anyMatch(event -> "AfterInstall".equals(event.lifecycleEventName())
                        && event.diagnostics() != null && "ScriptFailed".equals(event.diagnostics().errorCodeAsString())));
        for (String target : targets) {
            assertFalse(output(target, "cat", "/var/lib/floci-agent-events").contains(failed + " ValidateService"));
        }

        String recovered = deploy(deployments, name, upload(s3, name, "recovered.zip", bundle("recovered", false)));
        assertDeployment(deployments, recovered, DeploymentStatus.SUCCEEDED);
        for (String target : targets) {
            assertInstalledPayload(target, "recovered");
            assertEvents(target, recovered,
                    List.of("ApplicationStop", "BeforeInstall", "AfterInstall", "ApplicationStart", "ValidateService"));
            String[] environment = docker.inspectContainerCmd(localInstance(target).getDockerContainerId())
                    .exec().getConfig().getEnv();
            assertNotNull(environment);
            for (String variable : List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY",
                    "AWS_SESSION_TOKEN", "AWS_HOST_IDENTIFIER")) {
                assertFalse(Arrays.stream(environment).anyMatch(value -> value.startsWith(variable + "=")),
                        "The agent must obtain its own instance identity and credentials");
            }
        }

        String stopped = deploy(deployments, name,
                upload(s3, name, "blocked.zip", bundle("blocked", false, false, true)));
        String blockedTarget = await().atMost(Duration.ofSeconds(30)).until(() ->
                        targets.stream().filter(target -> guest(target, "test", "-p",
                                "/var/lib/floci-agent-gate").exitCode() == 0).findFirst().orElse(null),
                Objects::nonNull);
        assertEquals("Pending", deployments.stopDeployment(request -> request.deploymentId(stopped)
                .autoRollbackEnabled(true)).statusAsString());
        try {
            await().during(Duration.ofMillis(350)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertEquals(DeploymentStatus.IN_PROGRESS, deployments.getDeployment(
                            request -> request.deploymentId(stopped)).deploymentInfo().status()));
        } finally {
            // The native agent finishes its running script before rollback can start.
            output(blockedTarget, "sh", "-c", "printf resume > /var/lib/floci-agent-gate");
        }
        assertDeployment(deployments, stopped, DeploymentStatus.STOPPED);
        for (String target : targets) {
            String events = output(target, "cat", "/var/lib/floci-agent-events");
            assertFalse(events.contains(stopped + " ApplicationStart"));
            assertFalse(events.contains(stopped + " ValidateService"));
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals("released", output(blockedTarget, "cat", "/var/lib/floci-agent-released")));
        String stoppedRollback = deployments.getDeployment(request -> request.deploymentId(stopped))
                .deploymentInfo().rollbackInfo().rollbackDeploymentId();
        assertNotNull(stoppedRollback);
        assertDeployment(deployments, stoppedRollback, DeploymentStatus.SUCCEEDED);
        String afterStop = deploy(deployments, name,
                upload(s3, name, "after-stop.zip", bundle("after-stop", false)));
        assertDeployment(deployments, afterStop, DeploymentStatus.SUCCEEDED);
        for (String target : targets) {
            assertInstalledPayload(target, "after-stop");
            assertFalse(output(target, "cat", "/var/lib/floci-agent-events")
                    .contains(stopped + " ValidateService"));
        }

        deployments.updateDeploymentGroup(request -> request.applicationName(name).currentDeploymentGroupName(name)
                .autoRollbackConfiguration(configuration -> configuration.enabled(true)
                        .eventsWithStrings("DEPLOYMENT_FAILURE")));
        RevisionLocation lastGood = deployments.getDeployment(request -> request.deploymentId(afterStop))
                .deploymentInfo().revision();
        upload(s3, name, "after-stop.zip", bundle("unselected-rollback-version", false));
        String rollbackTrigger = deploy(deployments, name,
                upload(s3, name, "rollback-failure.zip", bundle("rollback-failure", true)), "OVERWRITE");
        assertDeployment(deployments, rollbackTrigger, DeploymentStatus.FAILED);
        String rollback = deployments.getDeployment(request -> request.deploymentId(rollbackTrigger))
                .deploymentInfo().rollbackInfo().rollbackDeploymentId();
        assertNotNull(rollback);
        assertDeployment(deployments, rollback, DeploymentStatus.SUCCEEDED);
        DeploymentInfo recovery = deployments.getDeployment(request -> request.deploymentId(rollback)).deploymentInfo();
        assertEquals("codeDeployRollback", recovery.creatorAsString());
        assertEquals(rollbackTrigger, recovery.rollbackInfo().rollbackTriggeringDeploymentId());
        assertEquals(lastGood, recovery.revision());
        assertEquals("DISALLOW", recovery.fileExistsBehaviorAsString());
        for (String target : targets) {
            assertInstalledPayload(target, "after-stop");
            assertEvents(target, rollback,
                    List.of("ApplicationStop", "BeforeInstall", "AfterInstall", "ApplicationStart", "ValidateService"));
        }
    }
}
