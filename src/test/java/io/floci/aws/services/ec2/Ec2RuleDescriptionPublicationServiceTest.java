package io.floci.aws.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.SecurityGroupRule;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2RuleDescriptionPublicationServiceTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedDescriptionUpdateCannotRestoreARuleAfterGroupDeletion(boolean egress) throws Exception {
        Fixture fixture = new Fixture(egress);
        JsonNode retainedRules = fixture.retainedRules();
        fixture.rules.arm(fixture.rule.getSecurityGroupRuleId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> modifierThread = new AtomicReference<>();
        AtomicReference<Thread> deletingThread = new AtomicReference<>();
        try {
            Future<?> modified = executor.submit(() -> {
                modifierThread.set(Thread.currentThread());
                fixture.modify();
            });
            assertTrue(fixture.rules.capturedSignal.await(5, TimeUnit.SECONDS), "The real rule read must be captured");
            assertNotNull(fixture.rules.captured);
            assertSame(fixture.rule, fixture.rules.captured, "The stored rule must supply the captured value");
            Future<?> deleted = executor.submit(() -> {
                deletingThread.set(Thread.currentThread());
                fixture.service.deleteSecurityGroup(REGION, fixture.group);
            });
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    deleted.isDone() || blockedBy(deletingThread.get(), modifierThread.get()));
            if (deleted.isDone()) {
                deleted.get(5, TimeUnit.SECONDS);
                fixture.assertGroupAndRulesAbsent();
            } else {
                assertTrue(blockedBy(deletingThread.get(), modifierThread.get()),
                        "A waiting deletion must be blocked by the captured writer");
            }
            fixture.rules.release.countDown();
            modified.get(5, TimeUnit.SECONDS);
            deleted.get(5, TimeUnit.SECONDS);
            assertEquals(retainedRules, fixture.retainedRules());
            assertEquals(1, fixture.service.describeSecurityGroups(REGION, List.of(fixture.retainedGroup),
                    List.of(), Map.of()).size());
            fixture.assertGroupAndRulesAbsent();
        } finally {
            fixture.rules.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Both owned workers must stop");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serialDescriptionUpdateIsObservableAndPreservesUnrelatedRules(boolean egress) {
        Fixture fixture = new Fixture(egress);
        JsonNode retainedRules = fixture.retainedRules();
        assertEquals("before", fixture.rule.getDescription());
        fixture.modify();
        assertEquals("after", fixture.service.describeSecurityGroupRules(REGION, List.of(fixture.group),
                List.of(fixture.rule.getSecurityGroupRuleId())).getFirst().getDescription());
        assertEquals(retainedRules, fixture.retainedRules());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateAfterDeletionDoesNotRecreateTheRule(boolean egress) {
        Fixture fixture = new Fixture(egress);
        JsonNode retainedRules = fixture.retainedRules();
        fixture.service.deleteSecurityGroup(REGION, fixture.group);
        fixture.assertGroupAndRulesAbsent();
        fixture.modify();
        fixture.assertGroupAndRulesAbsent();
        assertEquals(retainedRules, fixture.retainedRules());
    }

    private static boolean blockedBy(Thread waiting, Thread owner) {
        if (waiting == null || owner == null) {
            return false;
        }
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiting.threadId());
        return info != null && info.getLockOwnerId() == owner.threadId()
                && (info.getThreadState() == Thread.State.BLOCKED || info.getThreadState() == Thread.State.WAITING);
    }

    private static final class CapturingRules extends InMemoryStorage<String, SecurityGroupRule> {
        private final CountDownLatch capturedSignal = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean();
        private volatile String target;
        private volatile SecurityGroupRule captured;

        private void arm(String ruleId) {
            target = ruleId;
            armed.set(true);
        }

        @Override
        public Optional<SecurityGroupRule> get(String key) {
            Optional<SecurityGroupRule> actual = super.get(key);
            if (actual.isPresent() && actual.orElseThrow().getSecurityGroupRuleId().equals(target)
                    && armed.compareAndSet(true, false)) {
                captured = actual.orElseThrow();
                capturedSignal.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "Captured read must be released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Captured rule read interrupted", failure);
                }
            }
            return actual;
        }
    }

    private static final class Fixture {
        private final CapturingRules rules = new CapturingRules();
        private final Ec2Service service;
        private final String group;
        private final String retainedGroup;
        private final SecurityGroupRule rule;

        private Fixture(boolean egress) {
            InMemoryStorageFactory stores = new InMemoryStorageFactory("000000000000")
                    .withStore("ec2-security-group-rules.json", rules);
            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(config.services()).thenReturn(services);
            when(services.ec2()).thenReturn(ec2);
            when(ec2.mock()).thenReturn(true);
            when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
            service = new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                    mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), stores);
            String vpc = service.createVpc(REGION, "10.66.0.0/16", false).getVpcId();
            group = service.createSecurityGroup(REGION, "owned", "owned", vpc).getGroupId();
            retainedGroup = service.createSecurityGroup(REGION, "retained", "retained", vpc).getGroupId();
            IpPermission permission = new IpPermission();
            permission.setIpProtocol("tcp");
            permission.setFromPort(443);
            permission.setToPort(443);
            permission.setIpRanges(List.of(new IpRange("10.66.0.0/16", "before")));
            List<SecurityGroupRule> added = egress
                    ? service.authorizeSecurityGroupEgress(REGION, group, List.of(permission))
                    : service.authorizeSecurityGroupIngress(REGION, group, List.of(permission));
            rule = added.getFirst();
        }

        private void modify() {
            service.modifySecurityGroupRules(REGION, group, List.of(
                    Map.of("SecurityGroupRuleId", rule.getSecurityGroupRuleId(), "Description", "after")));
        }

        private JsonNode retainedRules() {
            return MAPPER.valueToTree(service.describeSecurityGroupRules(REGION, List.of(retainedGroup), List.of()));
        }

        private void assertGroupAndRulesAbsent() {
            assertTrue(service.describeSecurityGroups(REGION, List.of(), List.of(), Map.of()).stream()
                    .noneMatch(candidate -> candidate.getGroupId().equals(group)), "Deleted group stays absent");
            assertTrue(service.describeSecurityGroupRules(REGION, List.of(group), List.of()).isEmpty(),
                    "Description update must not restore a rule whose group was deleted");
        }
    }
}
