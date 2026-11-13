package io.github.hectorvent.floci.services.elb;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.ContainerCaBundle;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.NatGateway;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Also repeats the inherited private frontend and public NAT packet contracts with AWS API routing on. */
@QuarkusTest
@TestProfile(VpcPublicAwsApiPacketIntegrationTest.PublicAwsApis.class)
class VpcPublicAwsApiPacketIntegrationTest extends ElbClassicPublicNatPacketIntegrationTest {
    public static class PublicAwsApis extends PublicRouting {
        private static final String NAMESPACE = "vpc-api-packets-" + UUID.randomUUID();
        private static final Path TLS = prepareTls();

        private static Path prepareTls() {
            Path directory = Path.of("target", NAMESPACE, "tls").toAbsolutePath();
            try {
                Files.createDirectories(directory);
                FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(directory);
                CertificateGenerator.GeneratedCertificate leaf = ca.issueServerCertificate(
                        "ec2.us-east-1.amazonaws.com", List.of("ec2.us-east-1.amazonaws.com"),
                        KeyAlgorithm.RSA_2048, null);
                Files.writeString(directory.resolve("server.crt"), leaf.certificatePem());
                Files.writeString(directory.resolve("server.key"), leaf.privateKeyPem());
                ContainerCaBundle.writeJavaTrustStore(ContainerCaBundle.write(directory, ca.certificatePath()));
            } catch (IOException | GeneralSecurityException failure) {
                throw new IllegalStateException("Cannot prepare the public AWS API TLS fixture", failure);
            }
            return directory;
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.docker.resource-namespace", NAMESPACE);
            overrides.put("floci.network.vpc-routing.public-aws-api", "true");
            overrides.put("floci.services.ec2.container-ips-routable", "true");
            overrides.put("floci.dns.spoof-aws-endpoints", "true");
            overrides.put("floci.tls.enabled", "true");
            overrides.put("floci.tls.aws-https-port", "0");
            overrides.put("floci.port", "14566");
            overrides.put("floci.storage.persistent-path", TLS.getParent().toString());
            overrides.put("quarkus.tls.key-store.pem.0.cert", TLS.resolve("server.crt").toString());
            overrides.put("quarkus.tls.key-store.pem.0.key", TLS.resolve("server.key").toString());
            overrides.put("quarkus.http.insecure-requests", "enabled");
            overrides.put("floci.services.ec2.imds-port", "19169");
            // The production protocol proxy forwards to these two internal listeners.
            overrides.put("quarkus.http.test-port", "4510");
            overrides.put("quarkus.http.test-ssl-port", "4511");
            overrides.put("quarkus.http.proxy.use-proxy-protocol", "true");
            return overrides;
        }
    }

    @Override
    @BeforeEach
    void prepare() throws Exception {
        endpoint = URI.create(System.getProperty("audit.packet.control-endpoint",
                "http://127.0.0.1:" + config.port()));
        super.prepare();
    }

    @Test
    void standardAwsHostnameRequiresEgressNatAndInternetRoutesForHttpAndVerifiedTls() throws Exception {
        Network network = network(ec2, "10.87.0.0/16", "10.87.2.0/24");
        String privateSubnet = subnet(ec2, network.vpc(), "10.87.1.0/24");
        String privateRoutes = routes(ec2, network.vpc(), privateSubnet);
        NatGateway nat = nat(ec2, network.publicSubnet());
        ec2.createRoute(request -> request.routeTableId(privateRoutes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.natGatewayId()));
        String group = group(network.vpc(), "aws-api-" + UUID.randomUUID());
        String instance = launch(privateSubnet, group, "10.87.1.10", null);
        String helper = namespace(localInstance(instance).getDockerContainerId()).getId();
        RetryingTarCopier.copyBytes(docker, helper, "/tmp", "api-root-ca.pem",
                Files.readAllBytes(Path.of(config.storage().persistentPath(), "tls", "floci-root-ca.crt")), 0600);

        assertVpcResponse(helper, network.vpc(), false);
        assertVpcResponse(helper, network.vpc(), true);
        assertEquals("192.0.2.1", exec(helper, "getent", "ahostsv4", "ec2.us-east-1.amazonaws.com")
                .stdout().lines().findFirst().orElseThrow().split("\\s+")[0]);
        String bridge = apiBridge();
        String sourceIp = nat.natGatewayAddresses().getFirst().publicIp();
        assertTrue(logs(bridge).contains("AF=2 " + sourceIp + ":"),
                "The public API socket must receive the allocated NAT EIP");

        List<IpPermission> defaultEgress = ec2.describeSecurityGroups(request -> request.groupIds(group))
                .securityGroups().getFirst().ipPermissionsEgress();
        ec2.revokeSecurityGroupEgress(request -> request.groupId(group).ipPermissions(defaultEgress));
        assertBlocked(helper, network.vpc(), "A public AWS API is subject to workload egress rules");
        IpPermission https = fromAddress(443, "192.0.2.1");
        ec2.authorizeSecurityGroupEgress(request -> request.groupId(group).ipPermissions(https));
        assertVpcResponse(helper, network.vpc(), true);
        assertNotEquals(0, request(helper, network.vpc(), false).exitCode(), "HTTPS egress cannot admit HTTP");

        ec2.deleteRoute(request -> request.routeTableId(privateRoutes).destinationCidrBlock("0.0.0.0/0"));
        assertBlocked(helper, network.vpc(), "The private subnet's NAT route is required");
        ec2.createRoute(request -> request.routeTableId(privateRoutes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.natGatewayId()));
        assertVpcResponse(helper, network.vpc(), true);
        ec2.deleteRoute(request -> request.routeTableId(network.publicRoutes()).destinationCidrBlock("0.0.0.0/0"));
        assertBlocked(helper, network.vpc(), "The NAT subnet's internet gateway route is required");
        ec2.createRoute(request -> request.routeTableId(network.publicRoutes()).destinationCidrBlock("0.0.0.0/0")
                .gatewayId(network.gateway()));
        assertVpcResponse(helper, network.vpc(), true);
        NatGateway replacement = nat(ec2, network.publicSubnet());
        ec2.replaceRoute(request -> request.routeTableId(privateRoutes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(replacement.natGatewayId()));
        assertVpcResponse(helper, network.vpc(), true);
        assertTrue(logs(bridge).contains("AF=2 " + replacement.natGatewayAddresses().getFirst().publicIp() + ":"));
        ec2.deleteNatGateway(request -> request.natGatewayId(replacement.natGatewayId()));
        ec2.deleteNatGateway(request -> request.natGatewayId(nat.natGatewayId()));
        assertBlocked(helper, network.vpc(), "Retiring the NATs must withdraw the public API destination");
        assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(apiLabels()).exec().isEmpty(),
                "The last NAT deletion must retire the API bridge");
    }

    @Test
    void publicInstanceRequiresPublicAddressInternetRouteAndEgressForVerifiedAwsTls() throws Exception {
        Network network = network(ec2, "10.85.0.0/16", "10.85.1.0/24");
        ec2.modifySubnetAttribute(request -> request.subnetId(network.publicSubnet())
                .mapPublicIpOnLaunch(value -> value.value(true)));
        String group = group(network.vpc(), "public-api-" + UUID.randomUUID());
        String instance = launch(network.publicSubnet(), group, "10.85.1.10", null);
        String helper = namespace(localInstance(instance).getDockerContainerId()).getId();
        String publicIp = ec2.describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().publicIpAddress();
        assertNotNull(publicIp, "The public caller must hold an automatic public address");
        assertNotEquals("127.0.0.1", publicIp, "This packet test requires the native Docker public address");
        assertEquals("192.0.2.1", exec(helper, "getent", "ahostsv4", "ec2.us-east-1.amazonaws.com")
                .stdout().lines().findFirst().orElseThrow().split("\\s+")[0]);
        RetryingTarCopier.copyBytes(docker, helper, "/tmp", "api-root-ca.pem",
                Files.readAllBytes(Path.of(config.storage().persistentPath(), "tls", "floci-root-ca.crt")), 0600);

        assertVpcResponse(helper, network.vpc(), true);
        assertTrue(logs(apiBridge()).contains("AF=2 " + publicIp + ":"),
                "The public API socket must receive the instance's public address without a NAT gateway");
        assertTrue(ec2.describeNatGateways(request -> request.filter(
                filter -> filter.name("vpc-id").values(network.vpc()))).natGateways().isEmpty());

        List<IpPermission> defaultEgress = ec2.describeSecurityGroups(request -> request.groupIds(group))
                .securityGroups().getFirst().ipPermissionsEgress();
        ec2.revokeSecurityGroupEgress(request -> request.groupId(group).ipPermissions(defaultEgress));
        assertBlocked(helper, network.vpc(), "A public address cannot bypass the instance's egress policy");
        ec2.authorizeSecurityGroupEgress(request -> request.groupId(group)
                .ipPermissions(fromAddress(443, "192.0.2.1")));
        assertVpcResponse(helper, network.vpc(), true);

        ec2.deleteRoute(request -> request.routeTableId(network.publicRoutes()).destinationCidrBlock("0.0.0.0/0"));
        assertNotEquals(0, request(helper, network.vpc(), true).exitCode(),
                "A public address still requires an internet-gateway route");
        ec2.createRoute(request -> request.routeTableId(network.publicRoutes()).destinationCidrBlock("0.0.0.0/0")
                .gatewayId(network.gateway()));
        assertVpcResponse(helper, network.vpc(), true);

        ec2.modifySubnetAttribute(request -> request.subnetId(network.publicSubnet())
                .mapPublicIpOnLaunch(value -> value.value(false)));
        String privateInstance = launch(network.publicSubnet(), group, "10.85.1.11", null);
        String privateHelper = namespace(localInstance(privateInstance).getDockerContainerId()).getId();
        assertNull(ec2.describeInstances(request -> request.instanceIds(privateInstance))
                .reservations().getFirst().instances().getFirst().publicIpAddress());
        RetryingTarCopier.copyBytes(docker, privateHelper, "/tmp", "api-root-ca.pem",
                Files.readAllBytes(Path.of(config.storage().persistentPath(), "tls", "floci-root-ca.crt")), 0600);
        assertNotEquals(0, request(privateHelper, network.vpc(), true).exitCode(),
                "An internet-gateway route cannot give a private instance its neighbor's public address");
        assertVpcResponse(helper, network.vpc(), true);

        ec2.stopInstances(request -> request.instanceIds(instance));
        awaitInstanceState(instance, "stopped");
        assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(apiLabels()).exec().isEmpty(),
                "Stopping the last public caller must retire its API bridge");
        ec2.startInstances(request -> request.instanceIds(instance));
        awaitInstanceState(instance, "running");
        String restartedHelper = namespace(localInstance(instance).getDockerContainerId()).getId();
        RetryingTarCopier.copyBytes(docker, restartedHelper, "/tmp", "api-root-ca.pem",
                Files.readAllBytes(Path.of(config.storage().persistentPath(), "tls", "floci-root-ca.crt")), 0600);
        assertVpcResponse(restartedHelper, network.vpc(), true);
        assertNotEquals(0, request(privateHelper, network.vpc(), true).exitCode(),
                "Restarting the public caller must not give the private neighbor public API access");
    }

    @Test
    void elasticIpAssociationAndRemovalUpdatePublicApiReachability() throws Exception {
        Network network = network(ec2, "10.84.0.0/16", "10.84.1.0/24");
        String group = group(network.vpc(), "elastic-api-" + UUID.randomUUID());
        String instance = launch(network.publicSubnet(), group, "10.84.1.10", null);
        String helper = namespace(localInstance(instance).getDockerContainerId()).getId();
        RetryingTarCopier.copyBytes(docker, helper, "/tmp", "api-root-ca.pem",
                Files.readAllBytes(Path.of(config.storage().persistentPath(), "tls", "floci-root-ca.crt")), 0600);
        assertNull(ec2.describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().publicIpAddress());
        assertBlocked(helper, network.vpc(), "An IGW route alone cannot give a private caller public API access");
        String allocation = ec2.allocateAddress(request -> request.domain("vpc")).allocationId();
        cleanup.addFirst(() -> {
            Address current = ec2.describeAddresses(request -> request.allocationIds(allocation))
                    .addresses().getFirst();
            if (current.associationId() != null) {
                ec2.disassociateAddress(request -> request.associationId(current.associationId()));
            }
            ec2.releaseAddress(request -> request.allocationId(allocation));
        });
        String association = ec2.associateAddress(request -> request.allocationId(allocation).instanceId(instance))
                .associationId();
        String publicIp = ec2.describeAddresses(request -> request.allocationIds(allocation))
                .addresses().getFirst().publicIp();
        assertVpcResponse(helper, network.vpc(), true);
        assertTrue(logs(apiBridge()).contains("AF=2 " + publicIp + ":"));

        ec2.stopInstances(request -> request.instanceIds(instance));
        awaitInstanceState(instance, "stopped");
        assertEquals(association, ec2.describeAddresses(request -> request.allocationIds(allocation))
                .addresses().getFirst().associationId(), "Stop must retain the Elastic IP association");
        assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(apiLabels()).exec().isEmpty());
        ec2.startInstances(request -> request.instanceIds(instance));
        awaitInstanceState(instance, "running");
        String resumedHelper = namespace(localInstance(instance).getDockerContainerId()).getId();
        RetryingTarCopier.copyBytes(docker, resumedHelper, "/tmp", "api-root-ca.pem",
                Files.readAllBytes(Path.of(config.storage().persistentPath(), "tls", "floci-root-ca.crt")), 0600);
        assertVpcResponse(resumedHelper, network.vpc(), true);

        ec2.disassociateAddress(request -> request.associationId(association));
        assertNull(ec2.describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().publicIpAddress());
        assertBlocked(resumedHelper, network.vpc(), "Removing the EIP must withdraw the caller's public route");
        assertTrue(docker.listContainersCmd().withShowAll(true).withLabelFilter(apiLabels()).exec().isEmpty());

        ec2.associateAddress(request -> request.allocationId(allocation).instanceId(instance));
        assertVpcResponse(resumedHelper, network.vpc(), true);
    }

    private void awaitInstanceState(String instance, String state) {
        await().atMost(Duration.ofSeconds(60)).until(() -> state.equals(ec2.describeInstances(
                request -> request.instanceIds(instance)).reservations().getFirst().instances().getFirst()
                .state().nameAsString()));
    }

    private ContainerExec.Result request(String helper, String vpc, boolean tls) {
        return exec(helper, "curl", "--silent", "--show-error", "--fail-with-body", "--max-time", "2",
                "--cacert", "/tmp/api-root-ca.pem", "--aws-sigv4", "aws:amz:us-east-1:ec2",
                "--user", "test:test", "--data", "Action=DescribeVpcs&Version=2016-11-15&VpcId.1=" + vpc,
                (tls ? "https" : "http") + "://ec2.us-east-1.amazonaws.com/");
    }

    private void assertVpcResponse(String helper, String vpc, boolean tls) {
        ContainerExec.Result result = request(helper, vpc, tls);
        assertEquals(0, result.exitCode(), () -> result.summary() + "\n" + result.stdout() + networkDiagnostics());
        assertTrue(result.stdout().contains("<vpcId>" + vpc + "</vpcId>"), result.stdout());
    }

    private void assertBlocked(String helper, String vpc, String reason) {
        long started = System.nanoTime();
        ContainerExec.Result result = request(helper, vpc, true);
        assertEquals(28, result.exitCode(), reason + ": " + result.summary());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 1500, reason);
    }

    private Map<String, String> apiLabels() {
        return Map.of(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config),
                ContainerStorageHelper.SERVICE_LABEL, "vpc-aws-api");
    }

    private String apiBridge() {
        return docker.listContainersCmd().withLabelFilter(apiLabels()).exec().getFirst().getId();
    }

    private String logs(String container) throws IOException, InterruptedException {
        StringBuilder output = new StringBuilder();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        }) {
            docker.logContainerCmd(container).withStdErr(true).withStdOut(true).withFollowStream(false).exec(callback);
            assertTrue(callback.awaitCompletion(5, TimeUnit.SECONDS), "Public API socket logs did not complete");
        }
        return output.toString();
    }
}
