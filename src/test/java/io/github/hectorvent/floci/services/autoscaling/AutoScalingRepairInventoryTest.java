package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingRepairInventoryTest {
    @ParameterizedTest
    @ValueSource(strings = {"region", "name", "both"})
    void malformedPersistedCoordinatesDoNotSuppressValidRepairs(String missing) {
        AutoScalingGroup malformed = group("us-east-1", "malformed");
        if (!"name".equals(missing)) {
            malformed.setRegion(null);
        }
        if (!"region".equals(missing)) {
            malformed.setAutoScalingGroupName(null);
        }
        checkRepairs(List.of(malformed, group("us-east-1", "first"), group("us-west-2", "second")));
    }

    @Test
    void validInventorySchedulesEachGroup() {
        checkRepairs(List.of(group("us-east-1", "first"), group("us-west-2", "second")));
    }

    @Test
    void repairReachesTailAcrossAccountsAndRegionsAndWrapsAfterExhaustion() {
        InMemoryStorage<String, AutoScalingGroup> raw = new InMemoryStorage<>();
        for (int index = 0; index < 4200; index++) {
            String owner = index < 4100 ? "111111111111" : "222222222222";
            String region = index < 4100 ? "us-east-1" : "us-gov-west-1";
            raw.put(owner + "/" + String.format("%05d", index), group(region, "group-" + index));
        }
        AccountAwareStorageBackend<AutoScalingGroup> storage =
                new AccountAwareStorageBackend<>(raw, null, "111111111111");
        AutoScalingService service = mock(AutoScalingService.class);
        when(service.repairInventoryPage(nullable(String.class), eq(4096)))
                .thenAnswer(call -> storage.scanAllAccountEntriesAfter(call.getArgument(0), call.getArgument(1)));
        // Supply the same inventory to the original API for source controls.
        when(service.autoScalingGroupAccountIds())
                .thenReturn(new LinkedHashSet<>(List.of("111111111111", "222222222222")));
        when(service.describeAutoScalingGroups(null, null)).thenReturn(
                raw.scan(key -> key.startsWith("111111111111/")),
                raw.scan(key -> key.startsWith("222222222222/")));
        Set<String> repaired = ConcurrentHashMap.newKeySet();
        when(service.describeAutoScalingGroups(anyString(), anyList())).thenAnswer(call -> {
            List<String> names = call.getArgument(1);
            repaired.add(call.getArgument(0) + "/" + names.getFirst());
            return List.of();
        });
        AutoScalingReconciler reconciler = new AutoScalingReconciler(
                service, mock(Ec2Service.class), mock(ElbV2Service.class));
        try {
            reconciler.requestAll();
            verify(service, never()).describeAutoScalingGroups(null, null);
            await().atMost(Duration.ofSeconds(5)).until(() -> repaired.size() == 4096);
            reconciler.requestAll();
            await().atMost(Duration.ofSeconds(5)).until(() -> repaired.size() == 4200);
            assertTrue(repaired.contains("us-gov-west-1/group-4199"));
            reconciler.requestAll();
            reconciler.requestAll();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    verify(service, atLeast(2)).describeAutoScalingGroups("us-east-1", List.of("group-0")));
            verify(service, never()).autoScalingGroupAccountIds();
            verify(service, never()).describeAutoScalingGroups(null, null);
        } finally {
            reconciler.stop();
        }
    }

    @Test
    void fullQueueRetriesRejectedInventoryRowWithoutSkippingIt() throws Exception {
        AutoScalingService service = mock(AutoScalingService.class);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(4096);
        when(service.describeAutoScalingGroups(eq("us-east-1"), anyList())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            drained.countDown();
            return List.of();
        });
        Map<String, AccountAwareStorageBackend.AccountEntry<AutoScalingGroup>> page = Map.of(
                "222222222222/tail", new AccountAwareStorageBackend.AccountEntry<>("222222222222", "tail",
                        group("us-west-2", "tail")));
        when(service.repairInventoryPage(null, 4096)).thenReturn(page);
        when(service.autoScalingGroupAccountIds()).thenReturn(Set.of("222222222222"));
        when(service.describeAutoScalingGroups(null, null)).thenReturn(List.of(group("us-west-2", "tail")));
        AutoScalingReconciler reconciler = new AutoScalingReconciler(
                service, mock(Ec2Service.class), mock(ElbV2Service.class));
        try {
            for (int index = 0; index < 4096; index++) {
                reconciler.onGroupChanged(new AutoScalingGroupChanged("111111111111", "us-east-1", "busy-" + index));
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            reconciler.requestAll();
            verify(service, never()).describeAutoScalingGroups("us-west-2", List.of("tail"));
            release.countDown();
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            reconciler.requestAll();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    verify(service).describeAutoScalingGroups("us-west-2", List.of("tail")));
            verify(service).describeAutoScalingGroups("us-west-2", List.of("tail"));
        } finally {
            release.countDown();
            reconciler.stop();
        }
    }

    private void checkRepairs(List<AutoScalingGroup> inventory) {
        AutoScalingService service = mock(AutoScalingService.class);
        when(service.autoScalingGroupAccountIds()).thenReturn(Set.of("000000000001"));
        when(service.describeAutoScalingGroups(null, null)).thenReturn(inventory);
        Map<String, AccountAwareStorageBackend.AccountEntry<AutoScalingGroup>> page = new LinkedHashMap<>();
        for (int index = 0; index < inventory.size(); index++) {
            page.put("000000000001/" + index,
                    new AccountAwareStorageBackend.AccountEntry<>("000000000001", "key-" + index, inventory.get(index)));
        }
        when(service.repairInventoryPage(null, 4096)).thenReturn(page);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(
                service, mock(Ec2Service.class), mock(ElbV2Service.class));
        try {
            reconciler.requestAll();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                verify(service).describeAutoScalingGroups("us-east-1", List.of("first"));
                verify(service).describeAutoScalingGroups("us-west-2", List.of("second"));
            });
        } finally {
            reconciler.stop();
        }
    }

    private AutoScalingGroup group(String region, String name) {
        AutoScalingGroup group = new AutoScalingGroup();
        group.setRegion(region);
        group.setAutoScalingGroupName(name);
        return group;
    }
}
