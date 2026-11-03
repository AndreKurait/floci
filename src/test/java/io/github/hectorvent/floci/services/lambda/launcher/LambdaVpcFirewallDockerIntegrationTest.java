package io.github.hectorvent.floci.services.lambda.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.SecurityGroupNftCompiler;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Lambda runtimes reach a protected listener only while both security groups permit it. */
@QuarkusTest
@TestProfile(LambdaVpcFirewallDockerIntegrationTest.Enforced.class)
class LambdaVpcFirewallDockerIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String NODE_IMAGE = "public.ecr.aws/lambda/nodejs:20";
    private static final String VPC_POLICY =
            "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole";

    public static class Enforced implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.network.security-group-enforcement.enabled", "true",
                    "floci.services.ec2.mock", "false", "floci.storage.mode", "memory",
                    "floci.services.ec2.vpc-networks.enabled", "true");
        }
    }

    @Inject SecurityGroupFirewallManager firewall;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject ContainerBuilder builder;
    @Inject DockerClient docker;
    @Inject Ec2Service service;
    @Inject VpcNetworkManager vpcNetworks;
    @Inject EmulatorConfig config;
    @TestHTTPResource("/") URI endpoint;

    private final Deque<Executable> cleanup = new ArrayDeque<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void retireOwnedResources() {
        assertAll("Lambda, network, role and listener cleanup", cleanup);
    }

    @Test
    void runtimeSurvivesRealIngressAndEgressDropsAndReleasesItsEni() throws Exception {
        assertTrue(firewall.enabled());
        try {
            Assumptions.assumeTrue("linux".equals(docker.infoCmd().exec().getOsType()));
        } catch (Exception unavailable) {
            Assumptions.abort("Linux Docker is unavailable");
        }
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCOUNT, "test"));
        Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.addFirst(ec2::close);
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.addFirst(iam::close);
        LambdaClient lambda = LambdaClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60))).build();
        cleanup.addFirst(lambda::close);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleName = "lambda-network-" + suffix;
        String role = iam.createRole(request -> request.roleName(roleName).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """)).role().arn();
        cleanup.addFirst(() -> iam.deleteRole(request -> request.roleName(roleName)));
        iam.attachRolePolicy(request -> request.roleName(roleName).policyArn(VPC_POLICY));
        cleanup.addFirst(() -> iam.detachRolePolicy(request -> request.roleName(roleName).policyArn(VPC_POLICY)));

        String vpc = ec2.createVpc(request -> request.cidrBlock("10.74.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.74.1.0/24"))
                .subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(request -> request.subnetId(subnet)));
        String callerGroup = group(ec2, vpc, "caller-" + suffix);
        String listenerGroup = group(ec2, vpc, "listener-" + suffix);
        cleanup.addFirst(() -> clearGroup(ec2, listenerGroup));
        cleanup.addFirst(() -> clearGroup(ec2, callerGroup));
        IpPermission ingress = reference(callerGroup);
        ec2.authorizeSecurityGroupIngress(request -> request.groupId(listenerGroup).ipPermissions(ingress));
        Listener listener = listener(ec2, subnet, listenerGroup, suffix);

        String function = "vpc-packets-" + suffix;
        lambda.createFunction(request -> request.functionName(function).role(role).runtime(Runtime.NODEJS20_X)
                .handler("index.handler").timeout(5).memorySize(256)
                .code(code -> code.zipFile(SdkBytes.fromByteArray(functionCode())))
                .vpcConfig(vpcConfig -> vpcConfig.subnetIds(subnet).securityGroupIds(callerGroup)));
        Executable deleteFunction = () -> lambda.deleteFunction(request -> request.functionName(function));
        cleanup.addFirst(deleteFunction);

        JsonNode connected = invoke(lambda, function, listener.address());
        assertEquals("connected", connected.path("status").asText(),
                "A Lambda VPC execution must reach the listener's private ENI address");
        Map<String, Object> emulatorProbe = Map.of("runtimeHost", true, "connectOnly", true,
                "port", endpoint.getPort());
        assertEquals("connected", invoke(lambda, function, emulatorProbe).path("status").asText());
        long accepted = acceptedConnections(listener.containerId());
        List<NetworkInterface> callerInterfaces = ec2.describeNetworkInterfaces(request -> request.filters(
                filter -> filter.name("group-id").values(callerGroup))).networkInterfaces();
        assertFalse(callerInterfaces.isEmpty(), "the execution environments must own real ENIs");
        assertTrue(callerInterfaces.stream().anyMatch(eni ->
                        eni.privateIpAddress().equals(connected.path("sourceAddress").asText())),
                "The Lambda TCP source must be an advertised VPC ENI private address");
        CommandResult observedSource = execute(listener.containerId(), "/var/lang/bin/node", "-e",
                "process.stdout.write(require('fs').readFileSync('/tmp/source', 'utf8'))");
        assertEquals(0, observedSource.code(), observedSource.output());
        assertEquals(connected.path("sourceAddress").asText(), observedSource.output().trim(),
                "The listener must observe the same private source address as the Lambda socket");
        for (NetworkInterface eni : callerInterfaces) {
            assertAll(() -> assertEquals(ACCOUNT, eni.ownerId()),
                    () -> assertEquals(subnet, eni.subnetId()),
                    () -> assertEquals("in-use", eni.statusAsString()));
        }

        ec2.revokeSecurityGroupIngress(request -> request.groupId(listenerGroup).ipPermissions(ingress));
        JsonNode blockedIngress = invoke(lambda, function, listener.address());
        assertEquals("timeout", blockedIngress.path("status").asText());
        assertTrue(blockedIngress.path("elapsedMillis").asLong() >= 850);
        assertEquals(accepted, acceptedConnections(listener.containerId()),
                "a dropped connection must not reach the listener application");

        ec2.authorizeSecurityGroupIngress(request -> request.groupId(listenerGroup).ipPermissions(ingress));
        assertEquals("connected", invoke(lambda, function, listener.address()).path("status").asText());
        accepted = acceptedConnections(listener.containerId());
        List<IpPermission> originalEgress = ec2.describeSecurityGroups(request -> request.groupIds(callerGroup))
                .securityGroups().getFirst().ipPermissionsEgress();
        ec2.revokeSecurityGroupEgress(request -> request.groupId(callerGroup).ipPermissions(originalEgress));
        assertEquals("timeout", invoke(lambda, function, listener.address()).path("status").asText(),
                "the Runtime API still delivers the invocation response while application egress is blocked");
        assertEquals("connected", invoke(lambda, function, Map.of("runtime", true, "connectOnly", true))
                .path("status").asText(), "a fresh Runtime API connection must remain possible");
        assertEquals("timeout", invoke(lambda, function, emulatorProbe).path("status").asText(),
                "the Runtime API exception must not open other emulator ports on the same host");
        assertEquals(accepted, acceptedConnections(listener.containerId()));

        ec2.authorizeSecurityGroupEgress(request ->
                request.groupId(callerGroup).ipPermissions(reference(listenerGroup)));
        assertEquals("connected", invoke(lambda, function, listener.address()).path("status").asText());

        String version = lambda.publishVersion(request -> request.functionName(function)).version();
        String publishedFunction = function + ":" + version;
        assertEquals("connected", invoke(lambda, publishedFunction, listener.address()).path("status").asText());
        String deniedGroup = group(ec2, vpc, "denied-" + suffix);
        cleanup.remove(deleteFunction);
        cleanup.addFirst(deleteFunction);
        clearGroup(ec2, deniedGroup);
        lambda.updateFunctionConfiguration(request -> request.functionName(function)
                .vpcConfig(vpcConfig -> vpcConfig.subnetIds(subnet).securityGroupIds(deniedGroup)));
        assertEquals("timeout", invoke(lambda, function, emulatorProbe).path("status").asText(),
                "the updated latest environment must use its replacement security group");
        assertEquals("connected", invoke(lambda, publishedFunction, listener.address()).path("status").asText(),
                "a published version retains its original VPC configuration");

        lambda.updateFunctionConfiguration(request -> request.functionName(function)
                .vpcConfig(vpcConfig -> vpcConfig.subnetIds(List.of()).securityGroupIds(List.of())));
        assertTrue(ec2.describeNetworkInterfaces(request -> request.filters(
                filter -> filter.name("group-id").values(deniedGroup))).networkInterfaces().isEmpty(),
                "detaching the latest version must retire its old execution environment's ENI");
        assertEquals("connected", invoke(lambda, function, emulatorProbe).path("status").asText());
        assertEquals("connected", invoke(lambda, publishedFunction, listener.address()).path("status").asText());
        lambda.deleteFunction(request -> request.functionName(function).qualifier(version));
        assertTrue(ec2.describeNetworkInterfaces(request -> request.filters(
                filter -> filter.name("group-id").values(callerGroup))).networkInterfaces().isEmpty(),
                "deleting the published version must retire its independently owned ENI");

        lambda.deleteFunction(request -> request.functionName(function));
        cleanup.remove(deleteFunction);
        assertTrue(ec2.describeNetworkInterfaces(request -> request.filters(
                filter -> filter.name("group-id").values(callerGroup))).networkInterfaces().isEmpty(),
                "ordinary Lambda deletion must release the execution environment's ENI");
    }

    private String group(Ec2Client ec2, String vpc, String name) {
        String group = ec2.createSecurityGroup(request -> request.vpcId(vpc).groupName(name)
                .description("Lambda packet test")).groupId();
        cleanup.addFirst(() -> ec2.deleteSecurityGroup(request -> request.groupId(group)));
        return group;
    }

    private void clearGroup(Ec2Client ec2, String group) {
        // The firewall uses Floci's SecurityGroup model; this is the SDK response type.
        software.amazon.awssdk.services.ec2.model.SecurityGroup current =
                ec2.describeSecurityGroups(request -> request.groupIds(group)).securityGroups().getFirst();
        if (!current.ipPermissions().isEmpty()) {
            ec2.revokeSecurityGroupIngress(request -> request.groupId(group).ipPermissions(current.ipPermissions()));
        }
        if (!current.ipPermissionsEgress().isEmpty()) {
            ec2.revokeSecurityGroupEgress(request -> request.groupId(group)
                    .ipPermissions(current.ipPermissionsEgress()));
        }
    }

    private Listener listener(Ec2Client ec2, String subnet, String group, String suffix) throws Exception {
        NetworkInterface eni = ec2.createNetworkInterface(request -> request.subnetId(subnet).groups(group))
                .networkInterface();
        String eniId = eni.networkInterfaceId();
        cleanup.addFirst(() -> ec2.deleteNetworkInterface(request -> request.networkInterfaceId(eniId)));
        RequestScopes.runAs(ACCOUNT, REGION, () -> service.holdNetworkInterfaceForService(REGION, eniId));
        cleanup.addFirst(() -> RequestScopes.runAs(ACCOUNT, REGION,
                () -> service.releaseNetworkInterfaceFromService(REGION, eniId)));
        SecurityGroupFirewallManager.Namespace namespace = firewall.createNamespace("fixture", suffix,
                ACCOUNT, REGION, config.services().lambda().dockerNetwork(), Map.of());
        cleanup.addFirst(() -> lifecycle.removeIfExistsStrict(namespace.helperId()));
        cleanup.addFirst(() -> firewall.unregister(eniId));
        assertTrue(RequestScopes.callAs(ACCOUNT, REGION, () -> vpcNetworks.attach(
                REGION, eni.vpcId(), subnet, namespace.helperId(), eni.privateIpAddress())).isPresent(),
                "The listener fixture must own its private ENI address");
        List<SecurityGroup> groups = RequestScopes.callAs(ACCOUNT, REGION, () ->
                service.describeSecurityGroups(REGION, List.of(group), List.of(), Map.of()));
        firewall.register(new SecurityGroupNftCompiler.Endpoint(ACCOUNT, REGION, eni.vpcId(), eniId,
                eni.privateIpAddress(), namespace.transportAddress(), Set.of(group), groups,
                Set.of(eni.privateIpAddress())),
                namespace.helperId(), Map.of());
        String container = lifecycle.createAndStart(builder.newContainer(NODE_IMAGE)
                .withName("floci-lambda-listener-" + suffix)
                .withNetworkMode("container:" + namespace.helperId())
                .withEntrypoint(List.of("/var/lang/bin/node"))
                .withCmd(List.of("-e", """
                        const fs = require('fs');
                        fs.writeFileSync('/tmp/accepted', '');
                        require('net').createServer(socket => {
                          fs.appendFileSync('/tmp/accepted', 'x');
                          fs.writeFileSync('/tmp/source', socket.remoteAddress);
                          socket.end('ok');
                        }).listen(8080, '0.0.0.0');
                        """)).build()).containerId();
        cleanup.addFirst(() -> lifecycle.stopAndRemoveStrict(container, null, 1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (execute(namespace.helperId(), "nc", "-z", "-w", "1", "127.0.0.1", "8080").code() != 0) {
            assertTrue(System.nanoTime() < deadline, "listener fixture did not start");
            Thread.sleep(50);
        }
        return new Listener(container, eni.privateIpAddress());
    }

    private JsonNode invoke(LambdaClient lambda, String function, String address) throws Exception {
        return invoke(lambda, function, Map.of("host", address, "port", 8080));
    }

    private JsonNode invoke(LambdaClient lambda, String function, Map<String, Object> event) throws Exception {
        SdkBytes payload = SdkBytes.fromUtf8String(mapper.writeValueAsString(event));
        InvokeResponse result = lambda.invoke(request -> request.functionName(function).payload(payload));
        assertNull(result.functionError(), result.payload().asUtf8String());
        return mapper.readTree(result.payload().asUtf8String());
    }

    private long acceptedConnections(String container) throws Exception {
        CommandResult result = execute(container, "/var/lang/bin/node", "-e",
                "process.stdout.write(String(require('fs').readFileSync('/tmp/accepted').length))");
        assertEquals(0, result.code(), result.output());
        return Long.parseLong(result.output().trim());
    }

    private CommandResult execute(String container, String... command) throws Exception {
        String id = docker.execCreateCmd(container).withAttachStdout(true).withAttachStderr(true)
                .withCmd(command).exec().getId();
        StringBuilder output = new StringBuilder();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override public void onNext(Frame frame) {
                output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        }) {
            assertTrue(docker.execStartCmd(id).exec(callback).awaitCompletion(5, TimeUnit.SECONDS));
        }
        return new CommandResult(docker.inspectExecCmd(id).exec().getExitCodeLong().intValue(), output.toString());
    }

    private static IpPermission reference(String group) {
        return IpPermission.builder().ipProtocol("tcp").fromPort(8080).toPort(8080)
                .userIdGroupPairs(pair -> pair.groupId(group)).build();
    }

    private static byte[] functionCode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("index.js"));
                zip.write("""
                        const net = require('net');
                        exports.handler = async (event) => new Promise(resolve => {
                          const started = performance.now();
                          const runtime = new URL('http://' + process.env.AWS_LAMBDA_RUNTIME_API);
                          const host = event.runtime || event.runtimeHost ? runtime.hostname : event.host;
                          const port = event.runtime ? Number(runtime.port) : event.port;
                          const socket = net.createConnection({host, port});
                          const finish = status => {
                            const sourceAddress = socket.localAddress ?? null;
                            socket.destroy();
                            resolve({status, sourceAddress, elapsedMillis:Math.round(performance.now() - started)});
                          };
                          socket.setTimeout(900, () => finish('timeout'));
                          socket.once('connect', () => { if (event.connectOnly) finish('connected'); });
                          socket.once('data', () => finish('connected'));
                          socket.once('error', error => finish(error.code));
                        });
                        """.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return bytes.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot build Lambda fixture", failure);
        }
    }

    private record Listener(String containerId, String address) {}
    private record CommandResult(int code, String output) {}
}
