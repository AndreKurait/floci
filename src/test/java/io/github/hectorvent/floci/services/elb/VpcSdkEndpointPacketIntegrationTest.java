package io.github.hectorvent.floci.services.elb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.model.BuildResponseItem;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.ec2.ResolvedAmiImage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** The fixture deadline bounds a real SDK connection attempt; it does not simulate a service delay. */
@QuarkusTest
@TestProfile(VpcSdkEndpointPacketIntegrationTest.SdkWorkloads.class)
class VpcSdkEndpointPacketIntegrationTest extends VpcPublicAwsApiPacketIntegrationTest {
    private static final String NODE_IMAGE = "public.ecr.aws/lambda/nodejs@sha256:"
            + "a4440274d6f0fb4e6cb92cd5f2a97254efe6612f631d9974b197ecbd4a61fcab";
    private static final String VPC_POLICY = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole";
    private static final String SDK = """
            const {EC2Client, DescribeVpcsCommand} = require('@aws-sdk/client-ec2');
            exports.handler = async event => {
              const client = new EC2Client({maxAttempts: 1});
              const started = Date.now();
              const result = {endpointOverride: process.env.AWS_ENDPOINT_URL ?? null};
              try {
                const response = await client.send(new DescribeVpcsCommand({VpcIds: [event.vpc]}),
                  {abortSignal: AbortSignal.timeout(2500)});
                result.vpcs = response.Vpcs.map(vpc => vpc.VpcId);
                result.ok = true;
              } catch (error) {
                result.ok = false;
                result.error = error.name;
                result.message = error.message;
              } finally {
                result.elapsedMs = Date.now() - started;
                client.destroy();
              }
              return result;
            };
            """;
    private final ObjectMapper mapper = new ObjectMapper();

    public static class SdkWorkloads extends PublicAwsApis {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.services.lambda.runtime-images.\"nodejs20.x\"", NODE_IMAGE);
            return overrides;
        }
    }

    @Test
    void ec2SdkUsesTheNatWithoutAnEndpointOverride() throws Exception {
        RoutedWorkload workload = routedWorkload();
        when(images.resolveImage("ami-classic-packets")).thenReturn(ResolvedAmiImage.minimal(sdkImage()));
        String instance = launch(workload.subnet(), workload.group(), "10.89.1.10", null);
        String container = localInstance(instance).getDockerContainerId();

        assertConnected(ec2Call(container, workload.vpc()), workload.vpc());
        List<IpPermission> egress = ec2.describeSecurityGroups(request -> request.groupIds(workload.group()))
                .securityGroups().getFirst().ipPermissionsEgress();
        ec2.revokeSecurityGroupEgress(request -> request.groupId(workload.group()).ipPermissions(egress));
        assertBlocked(ec2Call(container, workload.vpc()));
        ec2.authorizeSecurityGroupEgress(request -> request.groupId(workload.group()).ipPermissions(egress));
        assertConnected(ec2Call(container, workload.vpc()), workload.vpc());
    }

    @Test
    void lambdaSdkUsesTheNatAndKeepsItsRuntimeApiReachableDuringRouteFailure() throws Exception {
        RoutedWorkload workload = routedWorkload();
        LambdaClient lambda = LambdaClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(Duration.ofSeconds(90))).build();
        cleanup.addFirst(lambda::close);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleName = "vpc-sdk-role-" + suffix;
        String role = iam.createRole(request -> request.roleName(roleName).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """)).role().arn();
        cleanup.addFirst(() -> iam.deleteRole(request -> request.roleName(roleName)));
        iam.attachRolePolicy(request -> request.roleName(roleName).policyArn(VPC_POLICY));
        cleanup.addFirst(() -> iam.detachRolePolicy(request -> request.roleName(roleName).policyArn(VPC_POLICY)));
        String function = "vpc-sdk-" + suffix;
        lambda.createFunction(request -> request.functionName(function).runtime(Runtime.NODEJS20_X)
                .role(role).handler("index.handler").timeout(10)
                .vpcConfig(vpc -> vpc.subnetIds(workload.subnet()).securityGroupIds(workload.group()))
                .code(code -> code.zipFile(SdkBytes.fromByteArray(functionCode()))));
        cleanup.addFirst(() -> lambda.deleteFunction(request -> request.functionName(function)));

        assertConnected(lambdaCall(lambda, function, workload.vpc()), workload.vpc());
        ec2.deleteRoute(request -> request.routeTableId(workload.routes()).destinationCidrBlock("0.0.0.0/0"));
        assertBlocked(lambdaCall(lambda, function, workload.vpc()));
        ec2.createRoute(request -> request.routeTableId(workload.routes()).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(workload.nat()));
        assertConnected(lambdaCall(lambda, function, workload.vpc()), workload.vpc());
    }

    private RoutedWorkload routedWorkload() {
        Network network = network(ec2, "10.89.0.0/16", "10.89.2.0/24");
        String subnet = subnet(ec2, network.vpc(), "10.89.1.0/24");
        String routes = routes(ec2, network.vpc(), subnet);
        NatGateway nat = nat(ec2, network.publicSubnet());
        ec2.createRoute(request -> request.routeTableId(routes).destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.natGatewayId()));
        return new RoutedWorkload(network.vpc(), subnet, routes, nat.natGatewayId(),
                group(network.vpc(), "sdk-" + UUID.randomUUID()));
    }

    private String sdkImage() throws Exception {
        Path context = Files.createDirectory(imageContext.resolve("sdk"));
        Files.writeString(context.resolve("Dockerfile"), "FROM " + NODE_IMAGE + """

                ENV NODE_PATH=/var/runtime/node_modules:/var/lang/lib/node_modules
                ENTRYPOINT []
                CMD ["tail", "-f", "/dev/null"]
                """);
        StringBuilder output = new StringBuilder();
        try (BuildImageResultCallback callback = new BuildImageResultCallback() {
            @Override
            public void onNext(BuildResponseItem item) {
                if (item.getStream() != null) {
                    output.append(item.getStream());
                }
                if (item.getErrorDetail() != null) {
                    output.append(item.getErrorDetail());
                }
                super.onNext(item);
            }
        }) {
            docker.buildImageCmd(context.toFile()).withPull(false).exec(callback);
            String image;
            try {
                image = callback.awaitImageId(180, TimeUnit.SECONDS);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Cannot prepare the stock SDK fixture:\n" + output, failure);
            }
            cleanup.addFirst(() -> docker.removeImageCmd(image).exec());
            return image;
        }
    }

    private JsonNode ec2Call(String container, String vpc) throws Exception {
        ContainerExec.Result result = exec(container, "/var/lang/bin/node", "-e", SDK
                + "exports.handler({vpc: process.argv[1]}).then(result => console.log(JSON.stringify(result)));", vpc);
        assertEquals(0, result.exitCode(), result.summary());
        return mapper.readTree(result.stdout());
    }

    private JsonNode lambdaCall(LambdaClient lambda, String function, String vpc) throws Exception {
        InvokeResponse response = lambda.invoke(request -> request.functionName(function)
                .payload(SdkBytes.fromUtf8String("{\"vpc\":\"" + vpc + "\"}")));
        assertNull(response.functionError(), response.payload().asUtf8String());
        return mapper.readTree(response.payload().asUtf8String());
    }

    private void assertConnected(JsonNode result, String vpc) {
        assertTrue(result.path("ok").asBoolean(), result.toString());
        assertTrue(result.path("endpointOverride").isNull(), result.toString());
        assertEquals(1, result.path("vpcs").size(), result.toString());
        assertEquals(vpc, result.path("vpcs").get(0).asText(), result.toString());
    }

    private void assertBlocked(JsonNode result) {
        assertFalse(result.path("ok").asBoolean(), result.toString());
        assertEquals("AbortError", result.path("error").asText(), result.toString());
        assertTrue(result.path("elapsedMs").asLong() >= 2000, result.toString());
    }

    private byte[] functionCode() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(SDK.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot package the SDK fixture", failure);
        }
    }

    private record RoutedWorkload(String vpc, String subnet, String routes, String nat, String group) {}
}
