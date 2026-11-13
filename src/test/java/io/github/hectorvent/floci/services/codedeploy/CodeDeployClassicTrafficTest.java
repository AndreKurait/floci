package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import io.github.hectorvent.floci.services.elb.ElbClassicHealthChecker;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeDeployClassicTrafficTest {
    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";
    private static final String ROLE = "arn:aws:iam::111122223333:role/deploy";
    private static final String NAME = "frontend";
    private static final String INSTANCE = "i-deploy";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PHASE_LIMIT = Duration.ofHours(1);
    private static final Duration POLL = Duration.ofMillis(200);
    private final TestTime time = TestTime.create();
    private ElbClassicService loadBalancers;
    private ServiceRoleCallAuthorizer authorizer;
    private CodeDeployClassicTraffic traffic;
    private ClassicLoadBalancer loadBalancer;
    private CodeDeployClassicTraffic.Plan plan;
    private AtomicBoolean registered;
    private AtomicBoolean draining;
    private AtomicReference<String> health;

    @BeforeEach
    void setUp() {
        loadBalancers = mock(ElbClassicService.class);
        authorizer = mock(ServiceRoleCallAuthorizer.class);
        traffic = new CodeDeployClassicTraffic(loadBalancers, authorizer, new ObjectMapper(), new TimelineTimer(time));
        plan = new CodeDeployClassicTraffic.Plan(ACCOUNT, REGION, ROLE, List.of(NAME));
        registered = new AtomicBoolean(true);
        draining = new AtomicBoolean(false);
        health = new AtomicReference<>("InService");
        loadBalancer = new ClassicLoadBalancer();
        loadBalancer.setLoadBalancerName(NAME);
        loadBalancer.setAccountId(ACCOUNT);
        loadBalancer.setRegion(REGION);
        loadBalancer.setTags(Map.of("Stage", "prod"));
        when(loadBalancers.requireLoadBalancer(REGION, NAME)).thenAnswer(call -> {
            loadBalancer.setInstanceIds(new ArrayList<>(registered.get() ? List.of(INSTANCE) : List.of()));
            return loadBalancer;
        });
        when(loadBalancers.registerInstances(REGION, NAME, List.of(INSTANCE))).thenAnswer(call -> {
            registered.set(true);
            return List.of(INSTANCE);
        });
        when(loadBalancers.deregisterInstances(REGION, NAME, List.of(INSTANCE))).thenAnswer(call -> {
            registered.set(false);
            return List.of();
        });
        when(loadBalancers.deregisteringInstances(REGION, NAME)).thenAnswer(call ->
                draining.get() ? Set.of(INSTANCE) : Set.of());
        when(loadBalancers.describeInstanceHealth(REGION, NAME, List.of(INSTANCE))).thenAnswer(call ->
                Map.of(INSTANCE, new ElbClassicHealthChecker.InstanceHealth(health.get(), "N/A", "")));
        when(authorizer.authorizeWithoutSourceContext(eq("codedeploy"), eq(ROLE), anyString(), anyString(),
                eq(ACCOUNT), eq("aws"), anyMap())).thenReturn(Decision.ALLOW);
        when(authorizer.authorizeUnscopedWithoutSourceContext("codedeploy", ROLE,
                "elasticloadbalancing:DescribeInstanceHealth", ACCOUNT, REGION)).thenReturn(Decision.ALLOW);
    }

    @Test
    void drainAndHealthGateActualCompletion() throws Exception {
        draining.set(true);
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<Void> blocked = change(false, cancelled);
        try {
            time.awaitSleepers(1);
            verify(loadBalancers, atLeastOnce()).describeInstanceHealth(REGION, NAME, List.of(INSTANCE));
            assertFalse(registered.get());
            time.advance(Duration.ofMinutes(1));
            assertFalse(blocked.isDone());
            draining.set(false);
            time.advance(POLL.minusMillis(1));
            assertFalse(blocked.isDone());
            time.advance(Duration.ofMillis(1));
            blocked.get(5, TimeUnit.SECONDS);
            health.set("OutOfService");
            CompletableFuture<Void> allowed = change(true, cancelled);
            try {
                time.awaitSleepers(1);
                assertTrue(registered.get());
                time.advance(Duration.ofMinutes(1));
                assertFalse(allowed.isDone());
                health.set("InService");
                time.advance(POLL.minusMillis(1));
                assertFalse(allowed.isDone());
                time.advance(Duration.ofMillis(1));
                allowed.get(5, TimeUnit.SECONDS);
            } finally {
                cancelled.set(true);
                allowed.handle((value, error) -> null).get(5, TimeUnit.SECONDS);
            }
        } finally {
            cancelled.set(true);
            blocked.handle((value, error) -> null).get(5, TimeUnit.SECONDS);
        }
        verify(authorizer).authorizeWithoutSourceContext("codedeploy", ROLE,
                "elasticloadbalancing:DeregisterInstancesFromLoadBalancer",
                "arn:aws:elasticloadbalancing:eu-west-1:111122223333:loadbalancer/frontend", ACCOUNT, "aws",
                Map.of("aws:ResourceTag/Stage", List.of("prod"),
                        "elasticloadbalancing:ResourceTag/Stage", List.of("prod")));
    }

    @Test
    void cancellationDuringDrainDoesNotRestoreRegistration() throws Exception {
        draining.set(true);
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<Void> blocked = change(false, cancelled);
        try {
            time.awaitSleepers(1);
            assertFalse(registered.get());
            cancelled.set(true);
            time.advance(POLL.minusMillis(1));
            assertFalse(blocked.isDone());
            time.advance(Duration.ofMillis(1));
            assertThrows(CancellationException.class, () -> blocked.get(5, TimeUnit.SECONDS));
            verify(loadBalancers, never()).registerInstances(anyString(), anyString(), anyList());
            assertFalse(registered.get());
        } finally {
            cancelled.set(true);
            blocked.handle((value, error) -> null).get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompleteTrafficStateFailsItsDeadline(boolean allow) {
        draining.set(!allow);
        health.set("OutOfService");
        Instant started = time.now();
        AtomicReference<Duration> lastPoll = new AtomicReference<>();
        when(loadBalancers.deregisteringInstances(REGION, NAME)).thenAnswer(call -> {
            lastPoll.set(Duration.between(started, time.now()));
            return draining.get() ? Set.of(INSTANCE) : Set.of();
        });

        TimeoutException failure = assertThrows(TimeoutException.class, () ->
                onTestThread().change(plan, INSTANCE, allow, PHASE_LIMIT, () -> false));

        assertEquals(allow ? "Classic load balancer health did not become InService"
                : "Classic load balancer connections did not finish draining", failure.getMessage());
        // The state is still polled at the limit itself, and the failure comes no earlier and no later.
        assertEquals(PHASE_LIMIT, lastPoll.get());
        assertEquals(PHASE_LIMIT, Duration.between(started, time.now()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trafficStateReachedAtTheDeadlineStillCompletes(boolean allow) throws Exception {
        Instant deadline = time.now().plus(PHASE_LIMIT);
        when(loadBalancers.deregisteringInstances(REGION, NAME)).thenAnswer(call ->
                !allow && time.now().isBefore(deadline) ? Set.of(INSTANCE) : Set.of());
        when(loadBalancers.describeInstanceHealth(REGION, NAME, List.of(INSTANCE))).thenAnswer(call ->
                Map.of(INSTANCE, new ElbClassicHealthChecker.InstanceHealth(
                        time.now().isBefore(deadline) ? "OutOfService" : "InService", "N/A", "")));

        onTestThread().change(plan, INSTANCE, allow, PHASE_LIMIT, () -> false);

        assertEquals(deadline, time.now());
    }

    /** For a change the test runs on its own thread: each poll interval moves the timeline on. */
    private CodeDeployClassicTraffic onTestThread() {
        return new CodeDeployClassicTraffic(loadBalancers, authorizer, new ObjectMapper(),
                TimelineTimer.selfAdvancing(time));
    }

    @Test
    void writeDenialDoesNotChangeMembership() {
        when(authorizer.authorizeWithoutSourceContext(eq("codedeploy"), eq(ROLE), anyString(), anyString(),
                eq(ACCOUNT), eq("aws"), anyMap())).thenReturn(Decision.DENY);
        AwsException failure = assertThrows(AwsException.class, () ->
                traffic.change(plan, INSTANCE, false, TIMEOUT, () -> false));
        assertEquals("IAM_ROLE_PERMISSIONS", failure.getErrorCode());
        verify(loadBalancers, never()).deregisterInstances(anyString(), anyString(), anyList());
        assertTrue(registered.get());
    }

    @Test
    void missingHealthPermissionCannotReportSuccessfulTrafficChange() {
        when(authorizer.authorizeUnscopedWithoutSourceContext("codedeploy", ROLE,
                "elasticloadbalancing:DescribeInstanceHealth", ACCOUNT, REGION)).thenReturn(Decision.DENY);
        AwsException failure = assertThrows(AwsException.class, () ->
                traffic.change(plan, INSTANCE, true, TIMEOUT, () -> false));
        assertEquals("IAM_ROLE_PERMISSIONS", failure.getErrorCode());
        verify(loadBalancers, never()).describeInstanceHealth(anyString(), anyString(), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"elbInfoList\":[]}", "{\"elbInfoList\":[{}]}",
            "{\"elbInfoList\":[{\"name\":\"frontend\"}],\"targetGroupInfoList\":[{\"name\":\"target\"}]}",
            "{\"elbInfoList\":[{\"name\":\"frontend\"}],\"targetGroupPairInfoList\":\"unsupported\"}"})
    @SuppressWarnings("unchecked")
    void unsupportedTrafficDeclarationsFailBeforeAnyMutation(String info) throws Exception {
        DeploymentGroup group = new DeploymentGroup();
        group.setLoadBalancerInfo(new ObjectMapper().readValue(info, Map.class));
        assertEquals("InvalidLoadBalancerInfoException", assertThrows(AwsException.class, () ->
                traffic.prepare(ACCOUNT, REGION, group)).getErrorCode());
        verifyNoInteractions(loadBalancers, authorizer);
    }

    @Test
    void selectedRoleAndBalancerNamesStayBoundToTheDeployment() {
        DeploymentGroup group = new DeploymentGroup();
        group.setServiceRoleArn(ROLE);
        group.setLoadBalancerInfo(Map.of("elbInfoList", List.of(Map.of("name", NAME))));
        CodeDeployClassicTraffic.Plan selected = traffic.prepare(ACCOUNT, REGION, group);
        group.setServiceRoleArn("arn:aws:iam::444455556666:role/other");
        group.setLoadBalancerInfo(Map.of("elbInfoList", List.of(Map.of("name", "other"))));
        assertEquals(plan, selected);
        assertThrows(UnsupportedOperationException.class, () -> selected.loadBalancers().add("other"));
    }

    private CompletableFuture<Void> change(boolean allow, AtomicBoolean cancelled) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                traffic.change(plan, INSTANCE, allow, PHASE_LIMIT, cancelled::get);
                result.complete(null);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }
}
