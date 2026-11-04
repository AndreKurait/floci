package io.github.hectorvent.floci.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElbV2OwnerRoutingTest {
    private static final String REGION = "us-east-1";
    private static final String OWNER = "111122223333";
    private static final String OTHER = "444455556666";
    private static final String LISTENER = arn(OWNER, "listener/net/owned/listener");
    private static final String GROUP = arn(OWNER, "targetgroup/owned/group");

    private ElbV2ServiceTest.SharedStorageFactory factory;
    private ElbV2HealthChecker healthChecker;
    private ElbV2Service service;
    private AccountAwareStorageBackend<Map<String, TargetGroup>> groups;
    private TargetGroup targetGroup;

    @BeforeEach
    void setUp() {
        factory = new ElbV2ServiceTest.SharedStorageFactory();
        healthChecker = mock(ElbV2HealthChecker.class);
        service = ElbV2ServiceTest.serviceWithStorage(factory, mock(ElbV2DataPlane.class), healthChecker);
        groups = factory.create("elbv2", "elbv2-target-groups.json",
                new TypeReference<Map<String, Map<String, TargetGroup>>>() {});
        targetGroup = new TargetGroup();
        targetGroup.setTargetGroupArn(GROUP);
        groups.putForAccount(OWNER, REGION, Map.of(GROUP, targetGroup));
    }

    @Test
    void socketLookupUsesTheBoundListenersOwnerWithoutChangingApiScope() {
        assertNull(service.getTargetGroup(REGION, GROUP));
        assertSame(targetGroup, service.getTargetGroupForListener(REGION, LISTENER, GROUP));
        assertNull(service.getTargetGroup(REGION, GROUP));
    }

    @Test
    void foreignOwnerCannotSelectTheStoredTargetGroup() {
        assertNull(service.getTargetGroupForListener(REGION, arn(OTHER, "listener/net/other/listener"), GROUP));
    }

    @Test
    void wrongRegionAndPartitionCannotSelectTheStoredTargetGroup() {
        assertNull(service.getTargetGroupForListener("us-west-2", LISTENER, GROUP));
        assertNull(service.getTargetGroupForListener(REGION,
                LISTENER.replace("arn:aws:", "arn:aws-cn:"), GROUP.replace("arn:aws:", "arn:aws-cn:")));
    }

    @Test
    void malformedListenerCannotSelectTheStoredTargetGroup() {
        assertNull(service.getTargetGroupForListener(REGION, "not-an-arn", GROUP));
    }

    @Test
    void listenerAndBalancerAttributesUseTheSameOwner() {
        String balancerArn = arn(OWNER, "loadbalancer/net/owned/balancer");
        LoadBalancer balancer = new LoadBalancer();
        balancer.setLoadBalancerArn(balancerArn);
        Listener listener = new Listener();
        listener.setListenerArn(LISTENER);
        listener.setLoadBalancerArn(balancerArn);
        AccountAwareStorageBackend<Map<String, LoadBalancer>> balancers =
                factory.create("elbv2", "elbv2-load-balancers.json",
                        new TypeReference<Map<String, Map<String, LoadBalancer>>>() {});
        AccountAwareStorageBackend<Map<String, Listener>> listeners =
                factory.create("elbv2", "elbv2-listeners.json",
                        new TypeReference<Map<String, Map<String, Listener>>>() {});
        balancers.putForAccount(OWNER, REGION, Map.of(balancerArn, balancer));
        listeners.putForAccount(OWNER, REGION, Map.of(LISTENER, listener));

        assertSame(balancer, service.getLoadBalancerForListener(REGION, LISTENER, balancerArn));
        assertSame(listener, service.getDataPlaneListener(REGION, LISTENER));
        assertNull(service.getLoadBalancerForListener(REGION, arn(OTHER, "listener/net/other/listener"),
                balancerArn));
    }

    @Test
    void runtimeRestoreStartsHealthCheckingOutsideTheDefaultAccount() {
        service.restorePersistedRuntime();

        verify(healthChecker).startMonitoring(targetGroup);
    }

    @Test
    void runtimeRestoreStartsHealthCheckingForTheDefaultAccount() {
        TargetGroup defaultGroup = new TargetGroup();
        String defaultArn = arn("000000000000", "targetgroup/default/group");
        defaultGroup.setTargetGroupArn(defaultArn);
        groups.putForAccount("000000000000", REGION, Map.of(defaultArn, defaultGroup));

        service.restorePersistedRuntime();

        verify(healthChecker).startMonitoring(defaultGroup);
    }

    @Test
    void configuredAccountNamesArePreservedDuringInitializationAndRestore() {
        String account = "local-owner";
        String groupArn = arn(account, "targetgroup/configured/group");
        TargetGroup configuredGroup = new TargetGroup();
        configuredGroup.setTargetGroupArn(groupArn);
        groups.putForAccount(account, REGION, Map.of(groupArn, configuredGroup));

        service.initializeStorage();
        service.restorePersistedRuntime();

        assertSame(configuredGroup, service.getTargetGroupForListener(REGION,
                arn(account, "listener/net/configured/listener"), groupArn));
        assertTrue(groups.getForAccount(account, REGION).orElseThrow() instanceof ConcurrentHashMap);
        verify(healthChecker).startMonitoring(configuredGroup);
    }

    @Test
    void legacyRegionsMigrateToTheConfiguredDefaultWithoutLosingAnotherOwner() {
        InMemoryStorage<String, Map<String, TargetGroup>> raw = installLegacyStore();
        raw.put(REGION, Map.of(GROUP, targetGroup));
        TargetGroup otherGroup = new TargetGroup();
        String otherArn = arn(OTHER, "targetgroup/other/group");
        otherGroup.setTargetGroupArn(otherArn);
        groups.putForAccount(OTHER, REGION, Map.of(otherArn, otherGroup));

        service.initializeStorage();
        service.restorePersistedRuntime();

        assertFalse(raw.get(REGION).isPresent());
        assertSame(targetGroup, groups.getForAccount(OWNER, REGION).orElseThrow().get(GROUP));
        assertSame(otherGroup, groups.getForAccount(OTHER, REGION).orElseThrow().get(otherArn));
        verify(healthChecker).startMonitoring(targetGroup);
        verify(healthChecker).startMonitoring(otherGroup);
    }

    @Test
    void scopedDefaultRegionTakesPrecedenceOverLegacyData() {
        InMemoryStorage<String, Map<String, TargetGroup>> raw = installLegacyStore();
        TargetGroup stale = new TargetGroup();
        stale.setTargetGroupArn(GROUP);
        raw.put(REGION, Map.of(GROUP, stale));
        groups.putForAccount(OWNER, REGION, Map.of(GROUP, targetGroup));

        service.initializeStorage();

        assertFalse(raw.get(REGION).isPresent());
        assertSame(targetGroup, groups.getForAccount(OWNER, REGION).orElseThrow().get(GROUP));
    }

    private InMemoryStorage<String, Map<String, TargetGroup>> installLegacyStore() {
        InMemoryStorage<String, Map<String, TargetGroup>> raw = new InMemoryStorage<>();
        groups = new AccountAwareStorageBackend<>(raw, null, OWNER);
        StorageFactory replacement = mock(StorageFactory.class);
        doAnswer(ignored -> AccountAwareStorageBackend.inMemory(OWNER))
                .when(replacement).create(any(), any(), any());
        doReturn(groups).when(replacement).create(eq("elbv2"), eq("elbv2-target-groups.json"), any());
        service.storageFactory = replacement;
        service.config = mock(EmulatorConfig.class);
        when(service.config.defaultAccountId()).thenReturn(OWNER);
        return raw;
    }

    private static String arn(String accountId, String resource) {
        return AwsArnUtils.Arn.of("elasticloadbalancing", REGION, accountId, resource).toString();
    }
}
