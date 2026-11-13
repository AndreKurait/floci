package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.elasticloadbalancing.model.ElasticLoadBalancingException;
import software.amazon.awssdk.services.elasticloadbalancing.model.PolicyNotFoundException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ElbClassicTlsPolicyPacketIntegrationTest.TlsPolicyFrontend.class)
class ElbClassicTlsPolicyPacketIntegrationTest extends ElbClassicVpcPacketIntegrationTest {
    private static final String FIRST = "ECDHE-RSA-AES128-GCM-SHA256";
    private static final String SECOND = "ECDHE-RSA-AES256-GCM-SHA384";
    private static final String PREFERENCE = SECOND + ":" + FIRST;

    public static class TlsPolicyFrontend extends ProtectedFrontend {}

    @Test
    void policyControlsRealHandshakeVersionsCiphersAndPreferenceBeforeTheBackend() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String vpc = ec2.createVpc(request -> request.cidrBlock("10.94.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.94.1.0/24"))
                .subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(request -> request.subnetId(subnet)));
        String callerGroup = group(vpc, "policy-caller-" + suffix);
        String frontendGroup = group(vpc, "policy-frontend-" + suffix);
        String backendGroup = group(vpc, "policy-backend-" + suffix);
        ec2.authorizeSecurityGroupIngress(request ->
                request.groupId(frontendGroup).ipPermissions(fromGroup(443, callerGroup)));
        ec2.authorizeSecurityGroupIngress(request ->
                request.groupId(backendGroup).ipPermissions(fromGroup(8080, frontendGroup)));
        String backend = launch(subnet, backendGroup, "10.94.1.10", Base64.getEncoder().encodeToString("""
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
                printf 'Content-Type: text/plain\\r\\n\\r\\naccepted %s\\n' "$count"
                APP
                chmod +x /www/cgi-bin/request
                httpd -p 8080 -h /www
                """.getBytes(StandardCharsets.UTF_8)));
        String caller = launch(subnet, callerGroup, "10.94.1.11", null);
        String callerHelper = namespace(localInstance(caller).getDockerContainerId()).getId();
        String backendGuest = localInstance(backend).getDockerContainerId();
        CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator()
                .generateSelfSignedCertificate("policy.example.test", List.of(), KeyAlgorithm.RSA_2048);
        String certificateName = "classic-policy-" + suffix;
        String arn = iam.uploadServerCertificate(request -> request.serverCertificateName(certificateName)
                .certificateBody(certificate.certificatePem()).privateKey(certificate.privateKeyPem()))
                .serverCertificateMetadata().arn();
        cleanup.addFirst(() -> iam.deleteServerCertificate(request -> request.serverCertificateName(certificateName)));
        RetryingTarCopier.copyBytes(docker, callerHelper, "/tmp", "policy-ca.pem",
                certificate.certificatePem().getBytes(StandardCharsets.UTF_8), 0600);
        String name = "classic-policy-" + suffix;
        String hostname = elb.createLoadBalancer(request -> request.loadBalancerName(name).scheme("internal")
                .subnets(subnet).securityGroups(frontendGroup).listeners(value -> value.protocol("HTTPS")
                        .loadBalancerPort(443).instanceProtocol("HTTP").instancePort(8080).sslCertificateId(arn)))
                .dnsName();
        cleanup.addFirst(() -> elb.deleteLoadBalancer(request -> request.loadBalancerName(name)));
        elb.createLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName("tls12")
                .policyTypeName("SSLNegotiationPolicyType").policyAttributes(value ->
                        value.attributeName("Reference-Security-Policy")
                                .attributeValue("ELBSecurityPolicy-TLS-1-2-2017-01")));
        elb.setLoadBalancerPoliciesOfListener(request ->
                request.loadBalancerName(name).loadBalancerPort(443).policyNames("tls12"));
        elb.configureHealthCheck(request -> request.loadBalancerName(name).healthCheck(check ->
                check.target("HTTP:8080/health").interval(5).timeout(2).healthyThreshold(2).unhealthyThreshold(2)));
        elb.registerInstancesWithLoadBalancer(request ->
                request.loadBalancerName(name).instances(value -> value.instanceId(backend)));
        await().atMost(Duration.ofSeconds(30)).until(() -> healthy(name, backend));
        String address = dns.resolveIpv4(hostname).orElseThrow().addresses().getFirst();

        response(fetch(callerHelper, address, "1.2", FIRST), 1, FIRST);
        ContainerExec.Result tls13 = fetch(callerHelper, address, "1.3", FIRST);
        assertEquals(35, tls13.exitCode(), tls13.summary());
        assertTrue(tls13.stderr().contains("protocol version"), tls13.summary());
        ContainerExec.Result excluded = fetch(callerHelper, address, "1.2", "ECDHE-RSA-AES128-SHA");
        assertEquals(35, excluded.exitCode(), excluded.summary());
        assertEquals("1", exec(backendGuest, "cat", "/tmp/request-count").stdout().strip());
        assertTrue(healthy(name, backend));
        response(fetch(callerHelper, address, "1.2", PREFERENCE), 2, FIRST);

        assertThrows(PolicyNotFoundException.class, () -> elb.setLoadBalancerPoliciesOfListener(request ->
                request.loadBalancerName(name).loadBalancerPort(443).policyNames("missing")));
        ElasticLoadBalancingException unsupported = assertThrows(ElasticLoadBalancingException.class,
                () -> elb.setLoadBalancerPoliciesOfListener(request ->
                        request.loadBalancerName(name).loadBalancerPort(443).policyNames(List.of())));
        assertEquals("UnsupportedOperation", unsupported.awsErrorDetails().errorCode());
        assertEquals(address, dns.resolveIpv4(hostname).orElseThrow().addresses().getFirst());
        assertEquals(List.of("tls12"), elb.describeLoadBalancers(request -> request.loadBalancerNames(name))
                .loadBalancerDescriptions().getFirst().listenerDescriptions().getFirst().policyNames());
        response(fetch(callerHelper, address, "1.2", FIRST), 3, FIRST);

        elb.createLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName("client-order")
                .policyTypeName("SSLNegotiationPolicyType").policyAttributes(
                        value -> value.attributeName("Protocol-TLSv1.2").attributeValue("true"),
                        value -> value.attributeName(FIRST).attributeValue("true"),
                        value -> value.attributeName(SECOND).attributeValue("true")));
        elb.setLoadBalancerPoliciesOfListener(request ->
                request.loadBalancerName(name).loadBalancerPort(443).policyNames("client-order"));
        await().atMost(Duration.ofSeconds(30)).until(() -> healthy(name, backend));
        String changed = dns.resolveIpv4(hostname).orElseThrow().addresses().getFirst();
        response(fetch(callerHelper, changed, "1.2", PREFERENCE), 4, SECOND);
        assertEquals("4", exec(backendGuest, "cat", "/tmp/request-count").stdout().strip());
    }

    private ContainerExec.Result fetch(String helper, String address, String version, String ciphers) {
        return exec(helper, "curl", "--silent", "--show-error", "--verbose", "--fail", "--max-time", "3",
                "--tlsv" + version, "--tls-max", version, "--ciphers", ciphers,
                "--cacert", "/tmp/policy-ca.pem", "--resolve", "policy.example.test:443:" + address,
                "https://policy.example.test/cgi-bin/request");
    }

    private static void response(ContainerExec.Result result, int count, String cipher) {
        assertEquals(0, result.exitCode(), result.summary());
        assertEquals("accepted " + count, result.stdout().strip());
        assertTrue(result.stderr().contains("SSL connection using TLSv1.2 / " + cipher), result.summary());
        assertTrue(result.stderr().contains("SSL certificate verify ok"), result.summary());
    }
}
