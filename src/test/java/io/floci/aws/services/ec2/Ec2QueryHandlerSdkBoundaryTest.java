package io.floci.aws.services.ec2;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateSnapshotResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.InstanceIpv6Address;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.SnapshotState;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.Vpc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static io.floci.aws.testing.Ec2QueryHandlerFixtures.REGION;
import static io.floci.aws.testing.Ec2QueryHandlerFixtures.queryHandler;
import static io.floci.aws.testing.Ec2ServiceFixtures.mockModeService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the real SDK codecs and composed handler; HTTP routing and authentication need separate integration tests. */
class Ec2QueryHandlerSdkBoundaryTest {

    private final HandlerHttpClient transport = new HandlerHttpClient(queryHandler(mockModeService()));
    private final Ec2Client client = Ec2Client.builder()
            .region(Region.of(REGION))
            .endpointOverride(URI.create("http://localhost:4566"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .httpClient(transport)
            .build();

    @AfterEach
    void closeClient() {
        client.close();
        transport.close();
    }

    @Test
    void ipv6AndSecondaryIpv4CountsRemainIndependentThroughSdkCodecs() {
        String subnet = dualStackSubnet();
        NetworkInterface created = client.createNetworkInterface(request -> request.subnetId(subnet)
                .ipv6AddressCount(1).secondaryPrivateIpAddressCount(2)).networkInterface();

        assertEquals(1, created.ipv6Addresses().size());
        assertEquals(3, created.privateIpAddresses().size());
        assertEquals(1, created.privateIpAddresses().stream().filter(address -> address.primary()).count());
        NetworkInterface described = client.describeNetworkInterfaces(request -> request
                .networkInterfaceIds(created.networkInterfaceId())).networkInterfaces().getFirst();
        assertEquals(created.ipv6Addresses(), described.ipv6Addresses());
        assertEquals(created.privateIpAddresses(), described.privateIpAddresses());
    }

    @Test
    void explicitIpv6AddressCoexistsWithSecondaryIpv4Count() {
        String subnet = dualStackSubnet();
        String block = client.describeSubnets(request -> request.subnetIds(subnet)).subnets().getFirst()
                .ipv6CidrBlockAssociationSet().getFirst().ipv6CidrBlock();
        String address = block.substring(0, block.indexOf('/')) + "abcd";
        NetworkInterface created = client.createNetworkInterface(request -> request.subnetId(subnet)
                .ipv6Addresses(InstanceIpv6Address.builder().ipv6Address(address).build())
                .secondaryPrivateIpAddressCount(2)).networkInterface();

        assertEquals(address, created.ipv6Addresses().getFirst().ipv6Address());
        assertEquals(3, created.privateIpAddresses().size());
        Ec2Exception conflict = assertThrows(Ec2Exception.class, () -> client.createNetworkInterface(request ->
                request.subnetId(subnet).ipv6AddressCount(1)
                        .ipv6Addresses(InstanceIpv6Address.builder().ipv6Address(address).build())));
        assertEquals("InvalidParameterCombination", conflict.awsErrorDetails().errorCode());
    }

    @Test
    void snapshotCreationAndDescribeUseTheirRespectiveSdkStatesAndPreserveTags() {
        String volume = client.createVolume(request -> request.availabilityZone(REGION + "a").size(2)).volumeId();
        Tag tag = Tag.builder().key("Name").value("snapshot & recovery").build();
        CreateSnapshotResponse created = client.createSnapshot(request -> request.volumeId(volume)
                .description("SDK <boundary> & snapshot")
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.SNAPSHOT).tags(tag).build()));

        assertEquals(SnapshotState.PENDING, created.state());
        assertTrue(created.progress() == null || created.progress().isEmpty());
        assertEquals(volume, created.volumeId());
        assertEquals("SDK <boundary> & snapshot", created.description());
        assertEquals(tag, created.tags().getFirst());
        Snapshot described = client.describeSnapshots(request -> request.snapshotIds(created.snapshotId()))
                .snapshots().getFirst();
        assertEquals(SnapshotState.COMPLETED, described.state());
        assertEquals("100%", described.progress());
        assertEquals(created.tags(), described.tags());
        assertEquals(created.ownerId(), described.ownerId());
    }

    @Test
    void destructiveDryRunKeepsTheResourceAndSdkReportsMissingTargets() {
        String vpc = client.createVpc(request -> request.cidrBlock("10.72.0.0/16")).vpc().vpcId();

        Ec2Exception dryRun = assertThrows(Ec2Exception.class,
                () -> client.deleteVpc(request -> request.vpcId(vpc).dryRun(true)));
        assertEquals("DryRunOperation", dryRun.awsErrorDetails().errorCode());
        assertEquals(412, dryRun.statusCode());
        assertEquals(vpc, client.describeVpcs(request -> request.vpcIds(vpc)).vpcs().getFirst().vpcId());
        client.deleteVpc(request -> request.vpcId(vpc));
        Ec2Exception missing = assertThrows(Ec2Exception.class,
                () -> client.deleteVpc(request -> request.vpcId(vpc).dryRun(true)));
        assertEquals("InvalidVpcID.NotFound", missing.awsErrorDetails().errorCode());
    }

    private String dualStackSubnet() {
        Vpc vpc = client.createVpc(request -> request.cidrBlock("10.73.0.0/16")
                .amazonProvidedIpv6CidrBlock(true)).vpc();
        String block = vpc.ipv6CidrBlockAssociationSet().getFirst().ipv6CidrBlock();
        return client.createSubnet(request -> request.vpcId(vpc.vpcId()).cidrBlock("10.73.0.0/24")
                .ipv6CidrBlock(block.substring(0, block.indexOf('/')) + "/64")).subnet().subnetId();
    }

    private static final class HandlerHttpClient implements SdkHttpClient {
        private final Ec2QueryHandler handler;
        private boolean closed;

        private HandlerHttpClient(Ec2QueryHandler handler) {
            this.handler = handler;
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                private boolean aborted;

                @Override
                public HttpExecuteResponse call() throws IOException {
                    if (closed || aborted) {
                        throw new IOException("SDK handler transport is closed or request was aborted");
                    }
                    MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
                    try (InputStream body = request.contentStreamProvider().orElseThrow().newStream()) {
                        String encoded = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                        for (String parameter : encoded.split("&")) {
                            String[] pair = parameter.split("=", 2);
                            params.add(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
                        }
                    }
                    try (Response response = handler.handle(params.getFirst("Action"), params, REGION)) {
                        byte[] bytes = response.getEntity().toString().getBytes(StandardCharsets.UTF_8);
                        return HttpExecuteResponse.builder()
                                .response(SdkHttpResponse.builder().statusCode(response.getStatus())
                                        .putHeader("Content-Type", "text/xml; charset=UTF-8").build())
                                .responseBody(AbortableInputStream.create(new ByteArrayInputStream(bytes))).build();
                    }
                }

                @Override
                public void abort() {
                    aborted = true;
                }
            };
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
