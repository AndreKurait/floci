package io.github.hectorvent.floci.services.elb;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.ec2.AmiImageResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.ResolvedAmiImage;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.ElasticLoadBalancingException;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;
import software.amazon.awssdk.services.iam.IamClient;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(ElbClassicVpcPacketIntegrationTest.ProtectedFrontend.class)
class ElbClassicVpcPacketIntegrationTest {
    public static class ProtectedFrontend implements QuarkusTestProfile {
        private static final String NAMESPACE = "classic-vpc-packets-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elb.mock", "false",
                    "floci.services.elb.vpc-frontends", "true",
                    "floci.services.ec2.mock", "false",
                    "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.services.ec2.aws-faithful-private-ip", "true",
                    "floci.network.security-group-enforcement.enabled", "true",
                    "floci.docker.resource-namespace", NAMESPACE);
        }
    }

    @Inject Ec2Service instances;
    @Inject ElbClassicDnsRecordSource dns;
    @Inject DockerClient docker;
    @Inject EmulatorConfig config;
    @InjectMock AmiImageResolver images;
    @TestHTTPResource URI endpoint;
    @TempDir Path imageContext;

    private final Deque<Executable> cleanup = new ArrayDeque<>();
    private Ec2Client ec2;
    private ElasticLoadBalancingClient elb;
    private IamClient iam;

    @BeforeEach
    void prepare() throws Exception {
        assumeTrue(DockerAvailability.isAvailable(), "Docker is required for protected Classic packets");
        Files.writeString(imageContext.resolve("Dockerfile"),
                "FROM " + TestImages.BUSYBOX + "\nRUN mkdir -p /var/lib\n");
        try (BuildImageResultCallback build = docker.buildImageCmd(imageContext.toFile()).withPull(false).start()) {
            String image = build.awaitImageId(60, TimeUnit.SECONDS);
            cleanup.addFirst(() -> docker.removeImageCmd(image).exec());
            when(images.resolveImage("ami-classic-packets")).thenReturn(ResolvedAmiImage.minimal(image));
        }
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        elb = ElasticLoadBalancingClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        cleanup.addFirst(ec2::close);
        cleanup.addFirst(elb::close);
        cleanup.addFirst(iam::close);
    }

    @AfterEach
    void deleteOwnedResources() {
        assertAll("Classic VPC packet resources", cleanup);
        assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.SERVICE_LABEL, "elb-classic")).exec().isEmpty(),
                "Deleting a load balancer must retire both its listener and firewall namespace");
    }

    @Test
    void frontendIngressControlsTlsBeforeAnyBackendRequestAndUsesTheLoadBalancerAsBackendPeer() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String vpc = ec2.createVpc(request -> request.cidrBlock("10.88.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.88.1.0/24"))
                .subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(request -> request.subnetId(subnet)));
        String callerGroup = group(vpc, "caller-" + suffix);
        String frontendGroup = group(vpc, "frontend-" + suffix);
        String backendGroup = group(vpc, "backend-" + suffix);
        IpPermission backendAccess = fromGroup(8080, frontendGroup);
        IpPermission frontendAccess = fromGroup(443, callerGroup);
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(backendGroup).ipPermissions(backendAccess));
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(frontendGroup).ipPermissions(frontendAccess));
        String userData = Base64.getEncoder().encodeToString("""
                #!/bin/sh
                set -eu
                mkdir -p /www/cgi-bin
                printf 'healthy' > /www/health
                printf '0' > /tmp/request-count
                cat > /www/cgi-bin/request <<'APP'
                #!/bin/sh
                count=$(cat /tmp/request-count)
                count=$((count + 1))
                printf '%s' "$count" > /tmp/request-count
                printf 'Content-Type: text/plain\\r\\n\\r\\n%s %s\\n' "$REMOTE_ADDR" "$count"
                APP
                chmod +x /www/cgi-bin/request
                httpd -p 8080 -h /www
                """.getBytes(StandardCharsets.UTF_8));
        String backend = launch(subnet, backendGroup, "10.88.1.10", userData);
        String caller = launch(subnet, callerGroup, "10.88.1.11", null);
        String backendGuest = localInstance(backend).getDockerContainerId();
        String callerHelper = namespace(localInstance(caller).getDockerContainerId()).getId();
        CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator().generateSelfSignedCertificate(
                "private-app.example.test", List.of(), KeyAlgorithm.EC_prime256v1);
        String certificateName = "classic-vpc-" + suffix;
        String certificateArn = iam.uploadServerCertificate(request -> request.serverCertificateName(certificateName)
                .certificateBody(certificate.certificatePem()).privateKey(certificate.privateKeyPem()))
                .serverCertificateMetadata().arn();
        cleanup.addFirst(() -> iam.deleteServerCertificate(request -> request.serverCertificateName(certificateName)));
        RetryingTarCopier.copyBytes(docker, callerHelper, "/tmp", "frontend-ca.pem",
                certificate.certificatePem().getBytes(StandardCharsets.UTF_8), 0600);
        String name = "classic-vpc-" + suffix;
        String hostname = elb.createLoadBalancer(request -> request.loadBalancerName(name).scheme("internal")
                .subnets(subnet).securityGroups(frontendGroup).listeners(Listener.builder().protocol("HTTPS")
                        .loadBalancerPort(443).instanceProtocol("HTTP").instancePort(8080)
                        .sslCertificateId(certificateArn).build())).dnsName();
        cleanup.addFirst(() -> elb.deleteLoadBalancer(request -> request.loadBalancerName(name)));
        elb.configureHealthCheck(request -> request.loadBalancerName(name).healthCheck(check -> check
                .target("HTTP:8080/health").interval(5).timeout(2).healthyThreshold(2).unhealthyThreshold(2)));
        elb.registerInstancesWithLoadBalancer(request -> request.loadBalancerName(name)
                .instances(instance -> instance.instanceId(backend)));
        await().atMost(Duration.ofSeconds(30)).until(() -> healthy(name, backend));
        String address = dns.resolveIpv4(hostname).orElseThrow().addresses().getFirst();
        assertTrue(ec2.describeNetworkInterfaces(request -> request.filters(filter ->
                        filter.name("private-ip-address").values(address))).networkInterfaces().stream()
                .anyMatch(eni -> eni.groups().stream().anyMatch(group -> frontendGroup.equals(group.groupId()))));
        ContainerExec.Result baseline = fetch(callerHelper, address);
        assertBackendResponse(baseline, address, 1);

        ec2.revokeSecurityGroupIngress(request -> request.groupId(frontendGroup).ipPermissions(frontendAccess));
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(backendGroup).ipPermissions(frontendAccess));
        long started = System.nanoTime();
        ContainerExec.Result blocked = fetch(callerHelper, address);
        assertNotEquals(0, blocked.exitCode(), "Putting the rule on the backend group must not open the frontend");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 1500,
                "The missing frontend permission must cause a real connection wait");
        assertEquals("1", exec(backendGuest, "cat", "/tmp/request-count").stdout().trim());
        assertTrue(healthy(name, backend), "A blocked frontend must not make a reachable backend unhealthy");
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(frontendGroup).ipPermissions(frontendAccess));
        ContainerExec.Result restored = fetch(callerHelper, address);
        assertBackendResponse(restored, address, 2);

        elb.deleteLoadBalancerListeners(request -> request.loadBalancerName(name).loadBalancerPorts(443));
        assertNotEquals(0, fetch(callerHelper, address).exitCode(), "Removing a listener must close its socket");
        String retainedEni = ec2.describeNetworkInterfaces(request -> request.filters(filter ->
                filter.name("description").values("Classic ELB " + name)))
                .networkInterfaces().getFirst().networkInterfaceId();
        String retainedHelper = docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
                ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true",
                "io.floci.elb-classic.network-interface", retainedEni)).exec().getFirst().getId();
        String helperName = docker.inspectContainerCmd(retainedHelper).exec().getName().substring(1);
        String consumer = docker.createContainerCmd(TestImages.BUSYBOX)
                .withName("floci-classic-consumer-" + suffix)
                .withLabels(Map.of(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)))
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode("container:" + helperName))
                .withCmd("sleep", "300").exec().getId();
        cleanup.addFirst(() -> removeContainer(consumer));
        docker.startContainerCmd(consumer).exec();
        ElasticLoadBalancingException deletion = assertThrows(ElasticLoadBalancingException.class,
                () -> elb.deleteLoadBalancer(request -> request.loadBalancerName(name)));
        assertEquals(500, deletion.statusCode());
        assertEquals(name, elb.describeLoadBalancers(request -> request.loadBalancerNames(name))
                .loadBalancerDescriptions().getFirst().loadBalancerName());
        assertEquals("in-use", ec2.describeNetworkInterfaces(request -> request.networkInterfaceIds(retainedEni))
                .networkInterfaces().getFirst().statusAsString(),
                "A failed namespace retirement must preserve the interface reservation");
        assertTrue(docker.inspectContainerCmd(retainedHelper).exec().getState().getRunning());
        removeContainer(retainedHelper);
        assertTrue(docker.inspectContainerCmd(consumer).exec().getState().getRunning());
        assertThrows(ElasticLoadBalancingException.class,
                () -> elb.deleteLoadBalancer(request -> request.loadBalancerName(name)));
        assertEquals("in-use", ec2.describeNetworkInterfaces(request -> request.networkInterfaceIds(retainedEni))
                .networkInterfaces().getFirst().statusAsString(),
                "A missing helper does not prove that its namespace has no remaining consumer");
        removeContainer(consumer);
        elb.deleteLoadBalancer(request -> request.loadBalancerName(name));
        assertTrue(dns.resolveIpv4(hostname).isEmpty());
        assertTrue(ec2.describeNetworkInterfaces(request -> request.filters(filter ->
                filter.name("description").values("Classic ELB " + name))).networkInterfaces().isEmpty());
    }

    private boolean healthy(String name, String instance) {
        return "InService".equals(elb.describeInstanceHealth(request -> request.loadBalancerName(name)
                .instances(value -> value.instanceId(instance))).instanceStates().getFirst().state());
    }

    private String group(String vpc, String name) {
        String id = ec2.createSecurityGroup(request -> request.vpcId(vpc).groupName(name).description(name)).groupId();
        cleanup.addFirst(() -> ec2.deleteSecurityGroup(request -> request.groupId(id)));
        return id;
    }

    private static IpPermission fromGroup(int port, String group) {
        return IpPermission.builder().ipProtocol("tcp").fromPort(port).toPort(port)
                .userIdGroupPairs(pair -> pair.groupId(group)).build();
    }

    private String launch(String subnet, String group, String address, String userData) {
        String id = ec2.runInstances(request -> request.imageId("ami-classic-packets").instanceType("t3.micro")
                .subnetId(subnet).securityGroupIds(group).privateIpAddress(address).userData(userData)
                .minCount(1).maxCount(1)).instances().getFirst().instanceId();
        cleanup.addFirst(() -> {
            ec2.terminateInstances(request -> request.instanceIds(id));
            await().atMost(Duration.ofSeconds(60)).until(() -> instanceState(id) == InstanceStateName.TERMINATED);
        });
        await().atMost(Duration.ofSeconds(60)).until(() -> instanceState(id) == InstanceStateName.RUNNING);
        return id;
    }

    private InstanceStateName instanceState(String id) {
        return ec2.describeInstances(request -> request.instanceIds(id)).reservations().getFirst()
                .instances().getFirst().state().name();
    }

    private Instance localInstance(String id) {
        return instances.describeInstances("us-east-1", List.of(id), Map.of()).getFirst().getInstances().getFirst();
    }

    private InspectContainerResponse namespace(String guest) {
        String mode = docker.inspectContainerCmd(guest).exec().getHostConfig().getNetworkMode();
        assertTrue(mode.startsWith("container:"));
        return docker.inspectContainerCmd(mode.substring("container:".length())).exec();
    }

    private ContainerExec.Result fetch(String helper, String address) {
        return exec(helper, "curl", "--silent", "--show-error", "--fail", "--max-time", "2",
                "--cacert", "/tmp/frontend-ca.pem", "--resolve", "private-app.example.test:443:" + address,
                "https://private-app.example.test/cgi-bin/request");
    }

    private static void assertBackendResponse(ContainerExec.Result response, String address, int requests) {
        assertEquals(0, response.exitCode(), response.summary());
        String[] fields = response.stdout().trim().split(" ");
        assertEquals(2, fields.length);
        String peer = fields[0].replace("[", "").replace("]", "");
        assertEquals(InetAddress.ofLiteral(address), InetAddress.ofLiteral(peer),
                "Backend traffic must originate from the load balancer's own VPC interface");
        assertEquals(Integer.toString(requests), fields[1]);
    }

    private ContainerExec.Result exec(String container, String... command) {
        return ContainerExec.run(docker, container, command, 10).throwIfTimedOut(container);
    }

    private void removeContainer(String container) {
        try {
            docker.removeContainerCmd(container).withForce(true).exec();
        } catch (NotFoundException ignored) {
            // The recovery assertion may already have retired this test-owned consumer.
        }
    }
}
