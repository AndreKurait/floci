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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
            overrides.put("floci.dns.spoof-aws-endpoints", "true");
            overrides.put("floci.tls.enabled", "true");
            overrides.put("floci.tls.aws-https-port", "0");
            overrides.put("floci.port", "14566");
            overrides.put("floci.storage.persistent-path", TLS.getParent().toString());
            overrides.put("quarkus.tls.key-store.pem.0.cert", TLS.resolve("server.crt").toString());
            overrides.put("quarkus.tls.key-store.pem.0.key", TLS.resolve("server.key").toString());
            overrides.put("quarkus.http.insecure-requests", "enabled");
            overrides.put("quarkus.http.proxy.use-proxy-protocol", "true");
            overrides.put("floci.services.ec2.imds-port", "19169");
            // The production protocol proxy forwards to these two internal listeners.
            overrides.put("quarkus.http.test-port", "4510");
            overrides.put("quarkus.http.test-ssl-port", "4511");
            return overrides;
        }
    }

    @Override
    @BeforeEach
    void prepare() throws Exception {
        endpoint = URI.create("http://127.0.0.1:" + config.port());
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
