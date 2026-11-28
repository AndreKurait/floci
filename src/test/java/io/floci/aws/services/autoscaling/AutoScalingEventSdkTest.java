package io.floci.aws.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.floci.aws.services.autoscaling.AutoScalingEventSdkFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK and queue traffic with the standard EC2 mock profile; native teardown has its own class. */
@QuarkusTest
@Timeout(420)
class AutoScalingEventSdkTest {
    @Test
    void desiredCapacityPublishesOneSuccessPerInstanceAndNoUnchangedStateEvents() {
        try (AutoScalingEventSdkFixture owned = owned("desired-capacity")) {
            owned.create("ami-12345678");
            owned.desired(2);
            Set<String> launched = new HashSet<>(owned.ready(2));
            owned.receive(LAUNCH, launched);
            launched.forEach(id -> assertEquals("running", owned.instance(id).state().nameAsString()));
            owned.desired(2);
            owned.quiet();

            owned.desired(0);
            owned.ready(0);
            owned.receive(TERMINATE, launched);
            launched.forEach(id -> assertEquals("terminated", owned.instance(id).state().nameAsString()));
            owned.quiet();
            owned.success();
        }
    }

    @Test
    void explicitTerminationPublishesTerminationAndReplacementLaunch() {
        try (AutoScalingEventSdkFixture owned = owned("explicit-replacement")) {
            owned.create("ami-12345678");
            owned.desired(1);
            String original = owned.ready(1).getFirst();
            owned.receive(LAUNCH, Set.of(original));
            owned.scaling.terminateInstanceInAutoScalingGroup(b -> b.instanceId(original)
                    .shouldDecrementDesiredCapacity(false));
            owned.until(() -> owned.group().instances().size() == 1
                    && !original.equals(owned.group().instances().getFirst().instanceId()));
            String replacement = owned.ready(1).getFirst();
            assertNotEquals(original, replacement);
            owned.receiveBoth(original, replacement);
            owned.success();
        }
    }

    @Test
    void instanceRefreshPublishesNativeReplacementEvents() {
        try (AutoScalingEventSdkFixture owned = owned("refresh")) {
            owned.create("ami-12345678");
            owned.desired(1);
            String original = owned.ready(1).getFirst();
            owned.receive(LAUNCH, Set.of(original));
            String refresh = owned.scaling.startInstanceRefresh(b -> b.autoScalingGroupName(owned.name)
                    .preferences(p -> p.minHealthyPercentage(0).instanceWarmup(0).skipMatching(false)))
                    .instanceRefreshId();
            assertFalse(refresh.isBlank());
            owned.until(() -> owned.group().instances().size() == 1
                    && !original.equals(owned.group().instances().getFirst().instanceId()));
            String replacement = owned.ready(1).getFirst();
            owned.receiveBoth(original, replacement);
            owned.success();
        }
    }

    @Test
    void groupDeletionRetainsEventGroupIdentity() {
        try (AutoScalingEventSdkFixture owned = owned("delete-group")) {
            owned.create("ami-12345678");
            owned.desired(1);
            String instance = owned.ready(1).getFirst();
            owned.receive(LAUNCH, Set.of(instance));

            owned.deleteGroup();

            owned.receive(TERMINATE, Set.of(instance));
            assertEquals("terminated", owned.instance(instance).state().nameAsString());
            owned.success();
        }
    }

    @Test
    void standbyReturnAndRejectedCapacityDoNotPublishLaunchSuccess() {
        try (AutoScalingEventSdkFixture owned = owned("standby-rejection")) {
            owned.create("ami-12345678");
            owned.desired(1);
            String instance = owned.ready(1).getFirst();
            owned.receive(LAUNCH, Set.of(instance));
            AutoScalingException rejected = assertThrows(AutoScalingException.class, () -> owned.desired(3));
            assertEquals(400, rejected.statusCode());
            assertEquals("ValidationError", rejected.awsErrorDetails().errorCode());
            owned.scaling.enterStandby(b -> b.autoScalingGroupName(owned.name).instanceIds(instance)
                    .shouldDecrementDesiredCapacity(true));
            owned.until(() -> "Standby".equals(owned.group().instances().getFirst().lifecycleStateAsString()));
            owned.scaling.exitStandby(b -> b.autoScalingGroupName(owned.name).instanceIds(instance));
            assertEquals(List.of(instance), owned.ready(1));
            owned.quiet();
            owned.success();
        }
    }

    @Test
    void disabledRuleDoesNotReceiveLaunchButEnabledRuleReceivesTermination() {
        try (AutoScalingEventSdkFixture owned = owned("disabled-rule")) {
            owned.create("ami-12345678");
            owned.events.disableRule(b -> b.name(owned.marker));
            owned.desired(1);
            String instance = owned.ready(1).getFirst();
            owned.quiet();
            owned.events.enableRule(b -> b.name(owned.marker));
            owned.desired(0);
            owned.ready(0);
            owned.receive(TERMINATE, Set.of(instance));
            owned.success();
        }
    }

    @Test
    void defaultBusRuleFiltersTheDocumentedDetailEnvelope() {
        try (AutoScalingEventSdkFixture owned = owned("detail-filter")) {
            owned.create("ami-12345678");
            owned.events.putRule(b -> b.name(owned.marker).description(owned.marker).eventPattern("""
                    {"source":["aws.autoscaling"],"detail-type":["EC2 Instance Terminate Successful"],
                    "detail":{"AutoScalingGroupName":["%s"]}}
                    """.formatted(owned.name)));
            owned.desired(1);
            String instance = owned.ready(1).getFirst();
            owned.quiet();
            owned.desired(0);
            owned.ready(0);
            owned.receive(TERMINATE, Set.of(instance));
            owned.success();
        }
    }

    @Test
    void equalGroupNamesRemainIsolatedByAccountAndRegion() {
        String name = "scope-" + UUID.randomUUID().toString().replace("-", "");
        try (AutoScalingEventSdkFixture east = new AutoScalingEventSdkFixture(
                "scope-east", ACCOUNT, Region.US_EAST_1, name);
             AutoScalingEventSdkFixture other = new AutoScalingEventSdkFixture(
                     "scope-other-account", "815670000072", Region.US_EAST_1, name);
             AutoScalingEventSdkFixture west = new AutoScalingEventSdkFixture(
                     "scope-west", ACCOUNT, Region.US_WEST_2, name)) {
            List<AutoScalingEventSdkFixture> scopes = List.of(east, other, west);
            scopes.forEach(scope -> scope.create("ami-12345678"));
            scopes.forEach(scope -> scope.desired(1));
            for (AutoScalingEventSdkFixture scope : scopes) {
                scope.receive(LAUNCH, Set.copyOf(scope.ready(1)));
                scope.quiet();
            }
            scopes.forEach(scope -> scope.desired(0));
            for (AutoScalingEventSdkFixture scope : scopes) {
                scope.ready(0);
                scope.receive(TERMINATE, Set.copyOf(scope.instances));
                scope.quiet();
                scope.success();
            }
        }
    }

    private static AutoScalingEventSdkFixture owned(String name) {
        return new AutoScalingEventSdkFixture(name, ACCOUNT, Region.US_EAST_1, null);
    }
}
