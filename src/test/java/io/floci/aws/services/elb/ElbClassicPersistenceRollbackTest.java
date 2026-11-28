package io.floci.aws.services.elb;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.elb.model.ClassicHealthCheck;
import io.floci.aws.services.elb.model.ClassicListener;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElbClassicPersistenceRollbackTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private AccountAwareStorageBackend<Map<String, ClassicLoadBalancer>> storage;
    private ElbClassicFrontendManager frontends;
    private ElbClassicHealthChecker health;
    private ElbClassicService service;
    private ClassicLoadBalancer previous;
    private ClassicLoadBalancer updated;

    @BeforeEach
    void setUp() {
        storage = spy(AccountAwareStorageBackend.<Map<String, ClassicLoadBalancer>>inMemory(ACCOUNT));
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(storage).when(factory).create(eq("elb"), eq("elb-load-balancers.json"), any());
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getAccountId()).thenReturn(ACCOUNT);
        frontends = mock(ElbClassicFrontendManager.class);
        when(frontends.enabled()).thenReturn(true);
        health = mock(ElbClassicHealthChecker.class);
        previous = new ClassicLoadBalancer();
        previous.setLoadBalancerName("web");
        previous.setRegion(REGION);
        previous.setAccountId(ACCOUNT);
        previous.setHealthCheck(ClassicHealthCheck.defaults());
        updated = new ClassicLoadBalancer();
        updated.setLoadBalancerName("web");
        updated.setRegion(REGION);
        updated.setAccountId(ACCOUNT);
        updated.setHealthCheck(previous.getHealthCheck());
        when(frontends.copy(previous)).thenReturn(updated);
        when(frontends.reconcile(updated)).thenReturn(true);
        storage.put(REGION, new ConcurrentHashMap<>(Map.of("web", previous)));
        service = new ElbClassicService(mock(Ec2Service.class), health, factory, null,
                resolver, null, null, frontends);
    }

    @Test
    void legacyConstructorFailedUpdateRetainsOriginalHealthCheck() {
        service = new ElbClassicService(mock(Ec2Service.class), health, factory(), null);
        ClassicHealthCheck original = previous.getHealthCheck();
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        doThrow(failure).doCallRealMethod().when(storage).putAllAtomically(anyMap());

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.configureHealthCheck(REGION, "web", ClassicHealthCheck.defaults())));

        assertSame(original, service.requireLoadBalancer(REGION, "web").getHealthCheck());
        verifyNoInteractions(health);
    }

    @Test
    void legacyConstructorFailedRegistrationRetainsOriginalInstances() {
        service = new ElbClassicService(mock(Ec2Service.class), health, factory(), null);
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        doThrow(failure).doCallRealMethod().when(storage).putAllAtomically(anyMap());

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.registerInstances(REGION, "web", List.of("i-backend"))));

        assertTrue(service.requireLoadBalancer(REGION, "web").getInstanceIds().isEmpty());
        verifyNoInteractions(health);
    }

    @Test
    void legacyConstructorSuccessfulRegistrationRebindsHealth() {
        service = new ElbClassicService(mock(Ec2Service.class), health, factory(), null);

        assertEquals(List.of("i-backend"), service.registerInstances(REGION, "web", List.of("i-backend")));

        ClassicLoadBalancer stored = service.requireLoadBalancer(REGION, "web");
        assertTrue(previous.getInstanceIds().isEmpty());
        verify(health).healthCheckChanged(stored);
        verify(health).registerInstances(stored, List.of("i-backend"));
        verify(health, never()).stopMonitoring(any());
    }

    @Test
    void failedPolicyCreationRestoresStorageAndFrontend() {
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        doThrow(failure).doCallRealMethod().when(storage).putAllAtomically(anyMap());

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.createLoadBalancerPolicy(REGION, "web", "chosen", "SSLNegotiationPolicyType",
                        Map.of("Reference-Security-Policy", ClassicSslPolicies.DEFAULT))));

        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        assertTrue(previous.getPolicies().isEmpty());
        assertTrue(updated.getPolicies().containsKey("chosen"));
        verify(frontends).reconcile(previous);
    }

    @Test
    void failedPolicyDeletionRestoresStorageAndFrontend() {
        previous.setPolicies(ClassicSslPolicies.predefined());
        updated.setPolicies(previous.getPolicies());
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        doThrow(failure).doCallRealMethod().when(storage).putAllAtomically(anyMap());

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.deleteLoadBalancerPolicy(REGION, "web", ClassicSslPolicies.DEFAULT)));

        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        assertTrue(previous.getPolicies().containsKey(ClassicSslPolicies.DEFAULT));
        assertFalse(updated.getPolicies().containsKey(ClassicSslPolicies.DEFAULT));
        verify(frontends).reconcile(previous);
    }

    @Test
    void failedPolicyBindingRestoresStorageAndFrontend() {
        ClassicListener listener = new ClassicListener();
        listener.setProtocol("HTTPS");
        listener.setLoadBalancerPort(443);
        updated.setListeners(List.of(listener));
        previous.setPolicies(ClassicSslPolicies.predefined());
        updated.setPolicies(previous.getPolicies());
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        doThrow(failure).doCallRealMethod().when(storage).putAllAtomically(anyMap());

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.setLoadBalancerPoliciesOfListener(REGION, "web", 443,
                        List.of(ClassicSslPolicies.DEFAULT))));

        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        assertTrue(previous.getListenerPolicies().isEmpty());
        assertEquals(List.of(ClassicSslPolicies.DEFAULT), updated.getListenerPolicies().get(443));
        verify(frontends).reconcile(previous);
    }

    private StorageFactory factory() {
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(storage).when(factory).create(eq("elb"), eq("elb-load-balancers.json"), any());
        return factory;
    }

    @Test
    void disabledRuntimeFailedUpdateRetainsOriginalHealthCheck() {
        when(frontends.enabled()).thenReturn(false);
        when(frontends.reconcile(updated)).thenReturn(false);
        ClassicHealthCheck original = previous.getHealthCheck();
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        doThrow(failure).doCallRealMethod().when(storage).putAllAtomically(anyMap());

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.configureHealthCheck(REGION, "web", ClassicHealthCheck.defaults())));

        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        assertSame(original, previous.getHealthCheck());
        verifyNoInteractions(health);
    }

    @Test
    void disabledRuntimeFailedRollbackRetainsOriginalDeclaration() {
        when(frontends.enabled()).thenReturn(false);
        when(frontends.reconcile(updated)).thenReturn(false);
        ClassicHealthCheck original = previous.getHealthCheck();
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        IllegalStateException rollback = new IllegalStateException("rollback unavailable");
        doThrow(failure).doThrow(rollback).when(storage).putAllAtomically(anyMap());

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.configureHealthCheck(REGION, "web", ClassicHealthCheck.defaults())));

        assertArrayEquals(new Throwable[]{rollback}, failure.getSuppressed());
        assertSame(original, service.requireLoadBalancer(REGION, "web").getHealthCheck());
        verifyNoInteractions(health);
    }

    @Test
    void disabledRuntimeSuccessfulUpdateNotifiesHealthChecker() {
        when(frontends.enabled()).thenReturn(false);
        when(frontends.reconcile(updated)).thenReturn(false);
        ClassicHealthCheck requested = ClassicHealthCheck.defaults();

        assertSame(requested, service.configureHealthCheck(REGION, "web", requested));

        assertSame(requested, service.requireLoadBalancer(REGION, "web").getHealthCheck());
        verify(health).healthCheckChanged(service.requireLoadBalancer(REGION, "web"));
    }

    @Test
    void disabledRuntimeInstanceUpdateRebindsHealthWithoutResettingState() {
        when(frontends.enabled()).thenReturn(false);
        when(frontends.reconcile(updated)).thenReturn(false);

        assertEquals(List.of("i-backend"), service.registerInstances(REGION, "web", List.of("i-backend")));

        assertSame(updated, service.requireLoadBalancer(REGION, "web"));
        assertTrue(previous.getInstanceIds().isEmpty());
        verify(health).healthCheckChanged(updated);
        verify(health).registerInstances(updated, List.of("i-backend"));
        verify(health, never()).stopMonitoring(any());
    }

    @Test
    void failedPersistenceRestoresDeclarationAndFrontend() {
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        doThrow(failure).doCallRealMethod().when(storage).putAllAtomically(anyMap());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.configureHealthCheck(REGION, "web", ClassicHealthCheck.defaults())));
        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        verify(frontends).reconcile(previous);
        verifyNoInteractions(health);
    }

    @Test
    void failedFrontendCompensationPreservesStorageFailureAndDeclaration() {
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        IllegalStateException compensation = new IllegalStateException("frontend unavailable");
        doThrow(failure).doCallRealMethod().when(storage).putAllAtomically(anyMap());
        doThrow(compensation).when(frontends).reconcile(previous);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.configureHealthCheck(REGION, "web", ClassicHealthCheck.defaults())));
        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        assertArrayEquals(new Throwable[]{compensation}, failure.getSuppressed());
        verifyNoInteractions(health);
    }

    @Test
    void successfulUpdateRetainsNewDeclarationAndRefreshesHealth() {
        service.configureHealthCheck(REGION, "web", ClassicHealthCheck.defaults());
        assertSame(updated, service.requireLoadBalancer(REGION, "web"));
        verify(frontends, never()).reconcile(previous);
        verify(health, never()).stopMonitoring(updated);
        verify(health).startMonitoring(updated);
        verify(health).reconcileInstances(updated);
    }

    @Test
    void failedDeletePersistenceRestoresDeclarationAndFrontend() {
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        doThrow(failure).doCallRealMethod().when(storage).putAllAtomically(anyMap());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.deleteLoadBalancer(REGION, "web")));
        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        verify(frontends).remove(previous);
        verify(frontends).reconcile(previous);
        verifyNoInteractions(health);
    }

    @Test
    void failedDeleteCompensationsAreIndependentAndPreserveOriginalFailure() {
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        IllegalStateException storageFailure = new IllegalStateException("storage still unavailable");
        IllegalStateException frontendFailure = new IllegalStateException("frontend unavailable");
        doThrow(failure).doThrow(storageFailure).when(storage).putAllAtomically(anyMap());
        doThrow(frontendFailure).when(frontends).reconcile(previous);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.deleteLoadBalancer(REGION, "web")));
        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        assertArrayEquals(new Throwable[]{storageFailure, frontendFailure}, failure.getSuppressed());
        verifyNoInteractions(health);
    }

    @Test
    void partialFrontendRemovalRestoresRuntimeWithoutChangingDeclaration() {
        IllegalStateException failure = new IllegalStateException("helper removal failed after proxy stopped");
        doThrow(failure).when(frontends).remove(previous);
        clearInvocations(storage);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.deleteLoadBalancer(REGION, "web")));
        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        verify(frontends).reconcile(previous);
        verify(storage, never()).putAllAtomically(anyMap());
        verifyNoInteractions(health);
    }

    @Test
    void failedPartialRemovalRestorationPreservesOriginalFailureAndAllowsRetry() {
        IllegalStateException failure = new IllegalStateException("helper removal failed after proxy stopped");
        IllegalStateException compensation = new IllegalStateException("frontend restoration failed");
        doThrow(failure).doNothing().when(frontends).remove(previous);
        doThrow(compensation).when(frontends).reconcile(previous);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.deleteLoadBalancer(REGION, "web")));
        assertArrayEquals(new Throwable[]{compensation}, failure.getSuppressed());
        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        verifyNoInteractions(health);
        service.deleteLoadBalancer(REGION, "web");
        assertFalse(service.hasLoadBalancer(REGION, "web"));
        verify(health).stopMonitoring(previous);
    }

    @Test
    void successfulDeleteRemovesDeclarationAndStopsHealth() {
        service.deleteLoadBalancer(REGION, "web");
        assertTrue(service.describeLoadBalancers(REGION, null).isEmpty());
        verify(frontends).remove(previous);
        verify(frontends, never()).reconcile(previous);
        verify(health).stopMonitoring(previous);
    }

    @Test
    void failedCreatePersistenceRemovesUnpublishedDeclaration() {
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        doThrow(failure).doCallRealMethod().when(storage).putAllAtomically(anyMap());
        assertSame(failure, assertThrows(IllegalStateException.class, this::createNewBalancer));
        assertEquals(List.of(previous), service.describeLoadBalancers(REGION, null));
        verify(frontends).reconcile(any());
        verify(frontends).remove(any());
        verifyNoInteractions(health);
    }

    @Test
    void failedCreateCompensationPreservesOriginalError() {
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        IllegalStateException compensation = new IllegalStateException("storage still unavailable");
        doThrow(failure).doThrow(compensation).when(storage).putAllAtomically(anyMap());
        assertSame(failure, assertThrows(IllegalStateException.class, this::createNewBalancer));
        assertEquals(List.of(previous), service.describeLoadBalancers(REGION, null));
        assertArrayEquals(new Throwable[]{compensation}, failure.getSuppressed());
        verify(frontends).reconcile(any());
        verify(frontends).remove(any());
        verifyNoInteractions(health);
    }

    @Test
    void successfulCreatePersistsAndStartsRuntime() {
        ClassicLoadBalancer created = createNewBalancer();
        assertSame(created, service.requireLoadBalancer(REGION, "new-web"));
        assertSame(previous, service.requireLoadBalancer(REGION, "web"));
        verify(frontends).reconcile(created);
        verify(health).startMonitoring(created);
    }

    @Test
    void failedFrontendStartRemovesDeclarationAndDnsIndex() {
        IllegalStateException failure = new IllegalStateException("frontend startup failed");
        doAnswer(invocation -> {
            ClassicLoadBalancer created = invocation.getArgument(0);
            assertTrue(service.findByDnsName(created.getDnsName()).isEmpty());
            throw failure;
        }).when(frontends).reconcile(any());
        assertSame(failure, assertThrows(IllegalStateException.class, this::createNewBalancer));
        assertEquals(List.of(previous), service.describeLoadBalancers(REGION, null));
        verifyNoInteractions(health);
    }

    @Test
    void failedFrontendStartDoesNotAttemptPersistence() {
        IllegalStateException failure = new IllegalStateException("frontend startup failed");
        IllegalStateException compensation = new IllegalStateException("storage unavailable");
        doThrow(failure).when(frontends).reconcile(any());
        doCallRealMethod().doThrow(compensation).when(storage).putAllAtomically(anyMap());
        assertSame(failure, assertThrows(IllegalStateException.class, this::createNewBalancer));
        assertEquals(0, failure.getSuppressed().length);
        verify(storage, never()).putAllAtomically(anyMap());
        assertEquals(List.of(previous), service.describeLoadBalancers(REGION, null));
        verifyNoInteractions(health);
    }

    @Test
    void failedCommitDoesNotIndexDnsBeforeDurability() {
        IllegalStateException failure = new IllegalStateException("storage unavailable");
        doAnswer(invocation -> {
            ClassicLoadBalancer created = invocation.getArgument(0);
            doAnswer(write -> {
                assertTrue(service.findByDnsName(created.getDnsName()).isEmpty());
                throw failure;
            }).when(storage).putAllAtomically(anyMap());
            return true;
        }).when(frontends).reconcile(any());
        assertSame(failure, assertThrows(IllegalStateException.class, this::createNewBalancer));
        assertEquals(List.of(previous), service.describeLoadBalancers(REGION, null));
        verify(frontends).remove(any());
        verifyNoInteractions(health);
    }

    private ClassicLoadBalancer createNewBalancer() {
        ClassicListener listener = new ClassicListener();
        listener.setProtocol("HTTP");
        listener.setLoadBalancerPort(80);
        listener.setInstanceProtocol("HTTP");
        listener.setInstancePort(8080);
        return service.createLoadBalancer(REGION, "new-web", List.of(listener), List.of(),
                List.of(), List.of(), "internet-facing", Map.of());
    }

}
