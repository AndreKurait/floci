package io.github.hectorvent.floci.services.elb;

import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.elasticloadbalancing.model.InstanceState;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Held HTTP requests traverse a real NAT, TLS frontend and protected backend. */
@QuarkusTest
@TestProfile(ElbClassicDrainingPacketIntegrationTest.DrainingRouting.class)
class ElbClassicDrainingPacketIntegrationTest extends ElbClassicPublicNatPacketIntegrationTest {
    public static class DrainingRouting extends PublicRouting {}

    @ParameterizedTest
    @ValueSource(strings = {"scale-in", "refresh", "explicit"})
    void autoScalingKeepsTheGuestAliveUntilItsInflightRequestFinishes(String operation) throws Exception {
        Topology topology = topology(20);
        try (Held request = new Held(topology)) {
            addSecondBackend(topology, request);
            AutoScalingClient scaling = scalingGroup(topology);
            if ("scale-in".equals(operation)) {
                scaling.setDesiredCapacity(change -> change.autoScalingGroupName(topology.name()).desiredCapacity(0));
            } else if ("refresh".equals(operation)) {
                scaling.startInstanceRefresh(change -> change.autoScalingGroupName(topology.name())
                        .preferences(preferences -> preferences.minHealthyPercentage(0).instanceWarmup(0)));
            } else {
                scaling.terminateInstanceInAutoScalingGroup(change -> change.instanceId(topology.first())
                        .shouldDecrementDesiredCapacity(true));
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> scaling.describeAutoScalingGroups(
                    query -> query.autoScalingGroupNames(topology.name())).autoScalingGroups().getFirst()
                    .instances().stream().anyMatch(instance -> topology.first().equals(instance.instanceId())
                            && "Terminating".equals(instance.lifecycleStateAsString())));
            assertEquals("running", ec2Instance(topology.first()).state().nameAsString());
            assertFalse(request.future.isDone(), "Auto Scaling must not stop a guest that is still draining");
            assertSecondBackend(topology);

            request.release();
            assertEquals(0, request.result().exitCode());
            assertEquals("first", request.result().stdout().trim());
            await().atMost(Duration.ofSeconds(30)).until(() ->
                    "terminated".equals(ec2Instance(topology.first()).state().nameAsString()));
            if ("refresh".equals(operation)) {
                await().atMost(Duration.ofSeconds(45)).until(() -> scaling.describeInstanceRefreshes(
                        query -> query.autoScalingGroupName(topology.name())).instanceRefreshes().getFirst()
                        .statusAsString().equals("Successful"));
            } else {
                assertTrue(scaling.describeAutoScalingGroups(query -> query.autoScalingGroupNames(topology.name()))
                        .autoScalingGroups().getFirst().instances().isEmpty());
            }
            assertFrontendUnchanged(topology);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void autoScalingHonorsTheDrainDeadlineAndDisabledDraining(boolean enabled) throws Exception {
        Topology topology = topology(3);
        try (Held request = new Held(topology)) {
            addSecondBackend(topology, request);
            AutoScalingClient scaling = scalingGroup(topology);
            draining(topology.name(), enabled, 3);
            long started = System.nanoTime();
            scaling.setDesiredCapacity(change -> change.autoScalingGroupName(topology.name()).desiredCapacity(0));

            ContainerExec.Result result = request.result();
            assertNotEquals(0, result.exitCode());
            assertNotEquals(28, result.exitCode(), "The load balancer must close before curl's timeout");
            if (enabled) {
                assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 2500);
            }
            await().atMost(Duration.ofSeconds(30)).until(() ->
                    "terminated".equals(ec2Instance(topology.first()).state().nameAsString()));
            assertSecondBackend(topology);
        }
    }

    @Test
    void newBackendsAndDeregistrationPreserveInflightWorkAndExistingHealthyMembers() throws Exception {
        Topology topology = topology(20);
        try (Held request = new Held(topology)) {
            addSecondBackend(topology, request);
            deregister(topology);
            InstanceState pending = state(topology);
            assertEquals("InService", pending.state());
            assertEquals("Instance deregistration currently in progress", pending.description());
            assertFalse(request.future.isDone());
            assertSecondBackend(topology);

            request.release();
            assertEquals(0, request.result().exitCode());
            assertEquals("first", request.result().stdout().trim());
            await().atMost(Duration.ofSeconds(5)).until(() -> "OutOfService".equals(state(topology).state()));
            assertEquals("Instance is not currently registered with the LoadBalancer.", state(topology).description());
            assertSecondBackend(topology);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theConfiguredDeadlineOrDisabledDrainingClosesHeldConnections(boolean enabled) throws Exception {
        Topology topology = topology(3);
        try (Held request = new Held(topology)) {
            addSecondBackend(topology, request);
            if (!enabled) {
                draining(topology.name(), false, 3);
            }
            long started = System.nanoTime();
            deregister(topology);
            assertSecondBackend(topology);
            ContainerExec.Result result = request.result();
            assertNotEquals(0, result.exitCode());
            assertNotEquals(28, result.exitCode(), "The frontend must close the connection before curl's timeout");
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (enabled) {
                assertTrue(elapsedMillis >= 2500, "The configured drain deadline must elapse");
            }
            assertTrue(elapsedMillis < 12000, "Deregistration must not wait for the blocked application");
            await().atMost(Duration.ofSeconds(5)).until(() -> "OutOfService".equals(state(topology).state()));
            assertSecondBackend(topology);
        }
    }

    @Test
    void reregistrationCancelsRetirementWithoutReusingPreviousHealthAdmission() throws Exception {
        Topology topology = topology(3);
        try (Held request = new Held(topology)) {
            addSecondBackend(topology, request);
            long started = System.nanoTime();
            deregister(topology);
            elb.registerInstancesWithLoadBalancer(change -> change.loadBalancerName(topology.name())
                    .instances(instance -> instance.instanceId(topology.first())));
            assertEquals("OutOfService", state(topology).state(), "A new registration needs fresh health checks");
            assertSecondBackend(topology);
            await().atMost(Duration.ofSeconds(20)).until(() ->
                    healthy(topology.name(), topology.first()) || request.future.isDone());
            assertFalse(request.future.isDone(), "The earlier deregistration must not close a re-registered backend");
            assertTrue(healthy(topology.name(), topology.first()));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() >= 3,
                    "Fresh health admission must also observe the cancelled deadline passing");
            request.release();
            assertEquals(0, request.result().exitCode());
            assertEquals("first", request.result().stdout().trim());
            assertFrontendUnchanged(topology);
        }
    }

    @Test
    void anUnhealthyBackendFinishesInflightWorkWithoutUsingTheDeregistrationTimeout() throws Exception {
        Topology topology = topology(3);
        try (Held request = new Held(topology)) {
            addSecondBackend(topology, request);
            assertEquals(0, exec(topology.firstGuest(), "rm", "/www/health").exitCode());
            long started = System.nanoTime();
            await().atMost(Duration.ofSeconds(20)).until(() ->
                    "Instance".equals(state(topology).reasonCode()) || request.future.isDone());
            assertFalse(request.future.isDone());
            assertEquals("OutOfService", state(topology).state());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() >= 3);
            assertSecondBackend(topology);
            request.release();
            assertEquals(0, request.result().exitCode());
            assertEquals("first", request.result().stdout().trim());
        }
    }

    private Topology topology(int drainSeconds) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Ec2Client callerAccount = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("111111111111", "test"))).build();
        cleanup.addFirst(callerAccount::close);
        Network callerVpc = network(callerAccount, "10.91.0.0/16", "10.91.2.0/24");
        String callerSubnet = subnet(callerAccount, callerVpc.vpc(), "10.91.1.0/24");
        String callerRoutes = routes(callerAccount, callerVpc.vpc(), callerSubnet);
        NatGateway gateway = nat(callerAccount, callerVpc.publicSubnet());
        callerAccount.createRoute(change -> change.routeTableId(callerRoutes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(gateway.natGatewayId()));
        String callerGroup = group(callerAccount, callerVpc.vpc(), "drain-caller-" + suffix);
        String caller = launch(callerAccount, callerSubnet, callerGroup, "10.91.1.10", null);
        String callerHelper = namespace(instances.findInstanceById("111111111111", caller)
                .getDockerContainerId()).getId();

        Network serverVpc = network(ec2, "10.92.0.0/16", "10.92.1.0/24");
        String frontendGroup = group(serverVpc.vpc(), "drain-frontend-" + suffix);
        String backendGroup = group(serverVpc.vpc(), "drain-backend-" + suffix);
        ec2.authorizeSecurityGroupIngress(change -> change.groupId(frontendGroup)
                .ipPermissions(fromAddress(443, gateway.natGatewayAddresses().getFirst().publicIp())));
        ec2.authorizeSecurityGroupIngress(change -> change.groupId(backendGroup)
                .ipPermissions(fromGroup(8080, frontendGroup)));
        String first = launch(serverVpc.publicSubnet(), backendGroup, "10.92.1.10", application("first"));
        String second = launch(serverVpc.publicSubnet(), backendGroup, "10.92.1.11", application("second"));
        CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator().generateSelfSignedCertificate(
                "drain-app.example.test", List.of(), KeyAlgorithm.EC_prime256v1);
        String certificateName = "classic-drain-" + suffix;
        String arn = iam.uploadServerCertificate(change -> change.serverCertificateName(certificateName)
                .certificateBody(certificate.certificatePem()).privateKey(certificate.privateKeyPem()))
                .serverCertificateMetadata().arn();
        cleanup.addFirst(() -> iam.deleteServerCertificate(change -> change.serverCertificateName(certificateName)));
        RetryingTarCopier.copyBytes(docker, callerHelper, "/tmp", "drain-ca.pem",
                certificate.certificatePem().getBytes(StandardCharsets.UTF_8), 0600);
        String name = "classic-drain-" + suffix;
        String hostname = elb.createLoadBalancer(change -> change.loadBalancerName(name).scheme("internet-facing")
                .subnets(serverVpc.publicSubnet()).securityGroups(frontendGroup).listeners(Listener.builder()
                        .protocol("HTTPS").loadBalancerPort(443).instanceProtocol("HTTP").instancePort(8080)
                        .sslCertificateId(arn).build())).dnsName();
        cleanup.addFirst(() -> elb.deleteLoadBalancer(change -> change.loadBalancerName(name)));
        elb.configureHealthCheck(change -> change.loadBalancerName(name).healthCheck(check -> check
                .target("HTTP:8080/health").interval(5).timeout(2).healthyThreshold(2).unhealthyThreshold(2)));
        draining(name, true, drainSeconds);
        elb.registerInstancesWithLoadBalancer(change -> change.loadBalancerName(name)
                .instances(instance -> instance.instanceId(first)));
        await().atMost(Duration.ofSeconds(30)).until(() -> healthy(name, first));
        List<String> addresses = dns.resolveIpv4(hostname).orElseThrow().addresses();
        Topology topology = new Topology(name, hostname, addresses, callerHelper, first, second,
                localInstance(first).getDockerContainerId());
        List<String> versionCommand = new ArrayList<>(List.of(curl(topology, "/identity", "3")));
        versionCommand.addAll(List.of("--http2", "--output", "/dev/null", "--write-out", "%{http_version}"));
        ContainerExec.Result version = exec(callerHelper, versionCommand.toArray(String[]::new));
        assertEquals(0, version.exitCode(), version.summary());
        assertEquals("1.1", version.stdout(), "Classic must not negotiate HTTP/2 even when the client offers it");
        return topology;
    }

    private Instance ec2Instance(String id) {
        return ec2.describeInstances(query -> query.instanceIds(id)).reservations().getFirst().instances().getFirst();
    }

    private AutoScalingClient scalingGroup(Topology topology) {
        AutoScalingClient scaling = AutoScalingClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
        cleanup.addFirst(scaling::close);
        Instance first = ec2Instance(topology.first());
        scaling.createLaunchConfiguration(change -> change.launchConfigurationName(topology.name())
                .imageId(first.imageId()).instanceType(first.instanceTypeAsString())
                .securityGroups(first.securityGroups().stream().map(group -> group.groupId()).toList())
                .userData(application("replacement")));
        cleanup.addFirst(() -> scaling.deleteLaunchConfiguration(
                change -> change.launchConfigurationName(topology.name())));
        scaling.createAutoScalingGroup(change -> change.autoScalingGroupName(topology.name())
                .launchConfigurationName(topology.name()).minSize(0).maxSize(2).desiredCapacity(0)
                .vpcZoneIdentifier(first.subnetId()).loadBalancerNames(topology.name()));
        cleanup.addFirst(() -> scaling.deleteAutoScalingGroup(
                change -> change.autoScalingGroupName(topology.name()).forceDelete(true)));
        scaling.attachInstances(change -> change.autoScalingGroupName(topology.name())
                .instanceIds(topology.first()));
        assertEquals(1, scaling.describeAutoScalingGroups(query -> query.autoScalingGroupNames(topology.name()))
                .autoScalingGroups().getFirst().desiredCapacity());
        return scaling;
    }

    private void addSecondBackend(Topology topology, Held request) {
        elb.registerInstancesWithLoadBalancer(change -> change.loadBalancerName(topology.name())
                .instances(instance -> instance.instanceId(topology.second())));
        assertFalse(request.future.isDone(), "Registering a backend must preserve existing connections");
        assertTrue(healthy(topology.name(), topology.first()), "Existing backend health must not reset");
        await().atMost(Duration.ofSeconds(20)).until(() ->
                healthy(topology.name(), topology.second()) || request.future.isDone());
        assertFalse(request.future.isDone());
        assertTrue(healthy(topology.name(), topology.second()));
        assertFrontendUnchanged(topology);
    }

    private void deregister(Topology topology) {
        elb.deregisterInstancesFromLoadBalancer(change -> change.loadBalancerName(topology.name())
                .instances(instance -> instance.instanceId(topology.first())));
        assertFrontendUnchanged(topology);
    }

    private void draining(String name, boolean enabled, int timeout) {
        elb.modifyLoadBalancerAttributes(change -> change.loadBalancerName(name)
                .loadBalancerAttributes(attributes -> attributes.connectionDraining(
                        draining -> draining.enabled(enabled).timeout(timeout))));
    }

    private InstanceState state(Topology topology) {
        return elb.describeInstanceHealth(query -> query.loadBalancerName(topology.name())
                .instances(instance -> instance.instanceId(topology.first()))).instanceStates().getFirst();
    }

    private void assertSecondBackend(Topology topology) {
        assertTrue(healthy(topology.name(), topology.second()));
        for (int index = 0; index < 3; index++) {
            ContainerExec.Result response = exec(topology.callerHelper(), curl(topology, "/identity", "3"));
            assertEquals(0, response.exitCode(), response.summary());
            assertEquals("second", response.stdout().trim());
        }
        assertFrontendUnchanged(topology);
    }

    private void assertFrontendUnchanged(Topology topology) {
        assertEquals(topology.addresses(), dns.resolveIpv4(topology.hostname()).orElseThrow().addresses(),
                "Membership and draining changes must preserve the frontend addresses");
    }

    private static String[] curl(Topology topology, String path, String timeout) {
        return new String[]{"curl", "--silent", "--show-error", "--fail", "--max-time", timeout,
                "--cacert", "/tmp/drain-ca.pem", "--resolve",
                "drain-app.example.test:443:" + topology.addresses().getFirst(),
                "https://drain-app.example.test" + path};
    }

    private static String application(String identity) {
        String script = """
                #!/bin/sh
                set -eu
                mkdir -p /www/cgi-bin
                printf 'healthy' > /www/health
                printf '%s' > /www/identity
                mkfifo /tmp/release
                cat > /www/cgi-bin/hold <<'APP'
                #!/bin/sh
                printf 'waiting' > /tmp/holding
                read ignored < /tmp/release
                rm /tmp/holding
                printf 'Content-Type: text/plain\\r\\n\\r\\n%s\\n'
                APP
                chmod +x /www/cgi-bin/hold
                httpd -p 8080 -h /www
                """.formatted(identity, identity);
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
    }

    private final class Held implements AutoCloseable {
        private final Topology topology;
        private final CompletableFuture<ContainerExec.Result> future;
        private boolean released;

        private Held(Topology topology) {
            this.topology = topology;
            future = CompletableFuture.supplyAsync(() -> ContainerExec.run(docker, topology.callerHelper(),
                    curl(topology, "/cgi-bin/hold", "45"), 50).throwIfTimedOut(topology.callerHelper()));
            await().atMost(Duration.ofSeconds(5)).until(() -> future.isDone()
                    || exec(topology.firstGuest(), "test", "-f", "/tmp/holding").exitCode() == 0);
            assertFalse(future.isDone(), "The application must receive and hold the request before mutation");
        }

        private ContainerExec.Result result() throws Exception {
            return future.get(15, TimeUnit.SECONDS);
        }

        private void release() {
            if (released) {
                return;
            }
            released = true;
            try {
                if (!Boolean.TRUE.equals(docker.inspectContainerCmd(topology.firstGuest()).exec()
                        .getState().getRunning())) {
                    assertTrue(future.isDone(), "An exited guest must not leave its request hanging");
                    return;
                }
            } catch (NotFoundException removed) {
                assertTrue(future.isDone(), "A retired guest must not leave its request hanging");
                return;
            }
            ContainerExec.Result result = exec(topology.firstGuest(), "sh", "-c",
                    "if test -f /tmp/holding; then printf 'continue\\n' > /tmp/release; fi");
            assertEquals(0, result.exitCode(), result.summary());
        }

        @Override
        public void close() throws Exception {
            release();
            future.get(10, TimeUnit.SECONDS);
        }
    }

    private record Topology(String name, String hostname, List<String> addresses, String callerHelper,
                            String first, String second, String firstGuest) {}
}
