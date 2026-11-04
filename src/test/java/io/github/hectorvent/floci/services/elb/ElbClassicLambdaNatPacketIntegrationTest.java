package io.github.hectorvent.floci.services.elb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A real Lambda execution environment uses native public NAT while retaining its Runtime API connection. */
@QuarkusTest
@TestProfile(ElbClassicLambdaNatPacketIntegrationTest.LambdaRouting.class)
class ElbClassicLambdaNatPacketIntegrationTest extends ElbClassicPublicNatPacketIntegrationTest {
    public static class LambdaRouting extends PublicRouting {
        private static final String NAMESPACE = "classic-lambda-nat-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.docker.resource-namespace", NAMESPACE);
            overrides.put("floci.services.lambda.runtime-images.\"nodejs20.x\"",
                    "public.ecr.aws/lambda/nodejs@sha256:"
                            + "a4440274d6f0fb4e6cb92cd5f2a97254efe6612f631d9974b197ecbd4a61fcab");
            return overrides;
        }
    }

    @Test
    void protectedLambdaKeepsItsRuntimeWhileNativeNatAndFrontendRulesChange() throws Exception {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("111111111111", "test"));
        Ec2Client callerAccount = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        IamClient callerIam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        LambdaClient lambda = LambdaClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(Duration.ofSeconds(60))).build();
        cleanup.addFirst(callerAccount::close);
        cleanup.addFirst(callerIam::close);
        cleanup.addFirst(lambda::close);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Network callerVpc = network(callerAccount, "10.89.0.0/16", "10.89.2.0/24");
        String callerSubnet = subnet(callerAccount, callerVpc.vpc(), "10.89.1.0/24");
        String callerRoutes = routes(callerAccount, callerVpc.vpc(), callerSubnet);
        NatGateway nat = nat(callerAccount, callerVpc.publicSubnet());
        String publicIp = nat.natGatewayAddresses().getFirst().publicIp();
        callerAccount.createRoute(request -> request.routeTableId(callerRoutes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.natGatewayId()));
        String callerGroup = group(callerAccount, callerVpc.vpc(), "lambda-" + suffix);

        Network serverVpc = network(ec2, "10.90.0.0/16", "10.90.1.0/24");
        String frontendGroup = group(serverVpc.vpc(), "frontend-" + suffix);
        String backendGroup = group(serverVpc.vpc(), "backend-" + suffix);
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(backendGroup)
                .ipPermissions(fromGroup(8080, frontendGroup)));
        IpPermission publicCaller = fromAddress(443, publicIp);
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(frontendGroup).ipPermissions(publicCaller));
        String backend = launch(serverVpc.publicSubnet(), backendGroup, "10.90.1.10",
                Base64.getEncoder().encodeToString("""
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
                        printf 'Content-Type: text/plain\\r\\n\\r\\n%s %s %s\\n' \
                        "$REMOTE_ADDR" "$count" "${HTTP_X_FORWARDED_FOR:-missing}"
                        APP
                        chmod +x /www/cgi-bin/request
                        httpd -p 8080 -h /www
                        """.getBytes(StandardCharsets.UTF_8)));
        String backendGuest = localInstance(backend).getDockerContainerId();
        CertificateGenerator.GeneratedCertificate certificate = new CertificateGenerator().generateSelfSignedCertificate(
                "nat-app.example.test", List.of(), KeyAlgorithm.EC_prime256v1);
        String name = "lambda-nat-" + suffix;
        String arn = iam.uploadServerCertificate(request -> request.serverCertificateName(name)
                .certificateBody(certificate.certificatePem()).privateKey(certificate.privateKeyPem()))
                .serverCertificateMetadata().arn();
        cleanup.addFirst(() -> iam.deleteServerCertificate(request -> request.serverCertificateName(name)));
        String hostname = elb.createLoadBalancer(request -> request.loadBalancerName(name).scheme("internet-facing")
                .subnets(serverVpc.publicSubnet()).securityGroups(frontendGroup).listeners(Listener.builder()
                        .protocol("HTTPS").loadBalancerPort(443).instanceProtocol("HTTP").instancePort(8080)
                        .sslCertificateId(arn).build())).dnsName();
        cleanup.addFirst(() -> elb.deleteLoadBalancer(request -> request.loadBalancerName(name)));
        elb.configureHealthCheck(request -> request.loadBalancerName(name).healthCheck(check -> check
                .target("HTTP:8080/health").interval(5).timeout(2).healthyThreshold(2).unhealthyThreshold(2)));
        elb.registerInstancesWithLoadBalancer(request -> request.loadBalancerName(name)
                .instances(instance -> instance.instanceId(backend)));
        await().atMost(Duration.ofSeconds(30)).until(() -> healthy(name, backend));
        String address = dns.resolveIpv4(hostname).orElseThrow().addresses().getFirst();

        String role = callerIam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """)).role().arn();
        cleanup.addFirst(() -> callerIam.deleteRole(request -> request.roleName(name)));
        String policy = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole";
        callerIam.attachRolePolicy(request -> request.roleName(name).policyArn(policy));
        cleanup.addFirst(() -> callerIam.detachRolePolicy(request -> request.roleName(name).policyArn(policy)));
        byte[] code = functionCode();
        lambda.createFunction(request -> request.functionName(name).role(role).runtime(Runtime.NODEJS20_X)
                .handler("index.handler").timeout(8).memorySize(256)
                .code(builder -> builder.zipFile(SdkBytes.fromByteArray(code)))
                .vpcConfig(vpc -> vpc.subnetIds(callerSubnet).securityGroupIds(callerGroup)));
        Executable deleteFunction = () -> lambda.deleteFunction(request -> request.functionName(name));
        cleanup.addFirst(deleteFunction);
        Map<String, String> request = Map.of("address", address, "certificate", certificate.certificatePem());

        JsonNode connected = invoke(lambda, name, request);
        assertEquals("connected", connected.path("status").asText(), connected.toString());
        assertTrue(connected.path("body").asText().trim().endsWith(" 1 " + publicIp), connected.toString());
        assertTrue(callerAccount.describeNetworkInterfaces(query -> query.filters(filter ->
                filter.name("group-id").values(callerGroup))).networkInterfaces().stream()
                .anyMatch(eni -> eni.privateIpAddress().equals(connected.path("sourceAddress").asText())));

        ec2.revokeSecurityGroupIngress(query -> query.groupId(frontendGroup).ipPermissions(publicCaller));
        ec2.authorizeSecurityGroupIngress(query -> query.groupId(backendGroup).ipPermissions(publicCaller));
        JsonNode blocked = invoke(lambda, name, request);
        assertEquals("timeout", blocked.path("status").asText(), blocked.toString());
        assertTrue(blocked.path("elapsedMillis").asLong() >= 1700, blocked.toString());
        assertEquals("1", exec(backendGuest, "cat", "/tmp/request-count").stdout().trim());
        ec2.authorizeSecurityGroupIngress(query -> query.groupId(frontendGroup).ipPermissions(publicCaller));
        assertTrue(invoke(lambda, name, request).path("body").asText().trim().endsWith(" 2 " + publicIp));

        callerAccount.deleteRoute(query -> query.routeTableId(callerRoutes).destinationCidrBlock("0.0.0.0/0"));
        assertEquals("timeout", invoke(lambda, name, request).path("status").asText());
        assertEquals("2", exec(backendGuest, "cat", "/tmp/request-count").stdout().trim());
        callerAccount.createRoute(query -> query.routeTableId(callerRoutes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.natGatewayId()));
        assertTrue(invoke(lambda, name, request).path("body").asText().trim().endsWith(" 3 " + publicIp));
        lambda.deleteFunction(query -> query.functionName(name));
        cleanup.remove(deleteFunction);
        await().atMost(Duration.ofSeconds(30)).until(() -> callerAccount.describeNetworkInterfaces(query ->
                query.filters(filter -> filter.name("group-id").values(callerGroup))).networkInterfaces().isEmpty());
    }

    private JsonNode invoke(LambdaClient lambda, String name, Map<String, String> payload) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SdkBytes requestPayload = SdkBytes.fromByteArray(mapper.writeValueAsBytes(payload));
        InvokeResponse response = lambda.invoke(request -> request.functionName(name)
                .payload(requestPayload));
        assertEquals(200, response.statusCode());
        assertNull(response.functionError(), response.payload().asUtf8String());
        return mapper.readTree(response.payload().asByteArray());
    }

    private byte[] functionCode() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("""
                    const https = require('https');
                    exports.handler = async (event) => new Promise((resolve) => {
                      const started = Date.now();
                      const request = https.get({
                        hostname: event.address, port: 443, path: '/cgi-bin/request',
                        servername: 'nat-app.example.test', ca: event.certificate,
                        agent: false, signal: AbortSignal.timeout(2000)
                      }, response => {
                        let body = '';
                        const sourceAddress = request.socket.localAddress;
                        response.on('data', part => body += part);
                        response.on('end', () => resolve({status: 'connected', body, sourceAddress}));
                      });
                      request.on('error', error => resolve({
                        status: error.name === 'AbortError' ? 'timeout' : error.code,
                        elapsedMillis: Date.now() - started
                      }));
                    });
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
