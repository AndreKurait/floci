package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.LoadBalancerProxySources;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.net.VpcRoutingManager;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class ElbV2FrontendScopeIntegrationTest {
    private final InheritableThreadLocal<String> inheritedCaller = new InheritableThreadLocal<>();

    @Inject
    RegionResolver regions;

    @Inject
    RequestContext request;

    @Test
    void backgroundFrontendsKeepTheirResourceOwnerWhenBothOriginatingRequestsEnd() throws Exception {
        CountDownLatch workersEntered = new CountDownLatch(2);
        CountDownLatch requestsEnded = new CountDownLatch(2);
        Owner first = new Owner("111122223333", "cn-north-1", "aws-cn", "first");
        Owner second = new Owner("444455556666", "eu-west-1", "aws", "second");
        CompletableFuture<Void> firstWork = fromRequest(first, workersEntered, requestsEnded);
        CompletableFuture<Void> secondWork = fromRequest(second, workersEntered, requestsEnded);
        CompletableFuture.allOf(firstWork, secondWork).get(15, TimeUnit.SECONDS);
    }

    private CompletableFuture<Void> fromRequest(Owner owner, CountDownLatch workersEntered,
                                                CountDownLatch requestsEnded) {
        ElbV2FrontendManager manager = manager(owner, workersEntered, requestsEnded);
        return CompletableFuture.supplyAsync(() -> {
            ManagedContext context = Arc.container().requestContext();
            context.activate();
            request.setAccountId("999900001111");
            request.setRegion("us-east-1");
            request.setAccessKeyId("request-only-" + owner.name());
            inheritedCaller.set(owner.name());
            try {
                CompletableFuture<Void> work = ElbV2DataPlane.frontendTask(() -> {
                    assertFalse(Arc.container().requestContext().isActive());
                    assertNull(inheritedCaller.get());
                    manager.reconcile("eni-" + owner.name());
                    assertFalse(Arc.container().requestContext().isActive(),
                            "The worker must terminate the resource scope it opened");
                    manager.retire("eni-" + owner.name());
                    assertFalse(Arc.container().requestContext().isActive());
                });
                work.whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        workersEntered.countDown();
                    }
                });
                await(workersEntered);
                assertEquals("999900001111", request.getAccountId());
                assertEquals("us-east-1", request.getRegion());
                assertEquals("request-only-" + owner.name(), request.getAccessKeyId());
                return work;
            } finally {
                context.terminate();
                inheritedCaller.remove();
                requestsEnded.countDown();
            }
        }, command -> Thread.ofVirtual().inheritInheritableThreadLocals(false).start(command))
                .thenCompose(work -> work);
    }

    private ElbV2FrontendManager manager(Owner owner, CountDownLatch workersEntered,
                                         CountDownLatch requestsEnded) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        VpcRoutingManager routing = mock(VpcRoutingManager.class);
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().awsFaithfulPrivateIp()).thenReturn(true);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().awsHttpsPort()).thenReturn(443);
        when(routing.enabled()).thenReturn(true);
        String eniId = "eni-" + owner.name();
        String helperId = "helper-" + owner.name();
        when(routing.publicFrontendTransport(owner.account(), owner.region(), eniId))
                .thenReturn(Optional.of("240.0.0.10"));
        doAnswer(invocation -> {
            workersEntered.countDown();
            assertEquals(owner.account(), regions.getAccountId());
            assertEquals(owner.region(), regions.getRegion());
            assertEquals(owner.partition(), regions.getPartition());
            assertNull(request.getAccessKeyId(), "The originating caller's authentication must not follow the job");
            await(requestsEnded);
            assertEquals(owner.account(), regions.getAccountId());
            assertEquals(owner.region(), regions.getRegion());
            assertEquals(owner.partition(), regions.getPartition());
            return null;
        }).when(routing).publishFrontendListeners(eq(owner.account()), eq(owner.region()), eq(eniId),
                eq(helperId), anySet(), eq("172.28.0.10"));
        LoadBalancer balancer = new LoadBalancer();
        balancer.setLoadBalancerArn("arn:" + owner.partition() + ":elasticloadbalancing:" + owner.region()
                + ":" + owner.account() + ":loadbalancer/app/" + owner.name() + "/1");
        balancer.setRegion(owner.region());
        balancer.setVpcId("vpc-" + owner.name());
        balancer.setType("application");
        balancer.setScheme("internet-facing");
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId(eniId);
        eni.setPrivateIpAddress("10.0.1.10");
        eni.setVpcId(balancer.getVpcId());
        ElbV2FrontendManager manager = new ElbV2FrontendManager(config, mock(ContainerLifecycleManager.class),
                routing, new LoadBalancerProxySources());
        manager.prepare(balancer, eni, new ElbV2VpcNetworkManager.Attachment(
                eniId, helperId, "172.28.0.10", "172.28.0.2"));
        assertEquals(List.of(), manager.addresses(balancer, null));
        return manager;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Native request/worker ordering timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private record Owner(String account, String region, String partition, String name) {}
}
