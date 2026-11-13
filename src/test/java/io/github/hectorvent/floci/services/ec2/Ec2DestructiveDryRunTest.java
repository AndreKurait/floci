package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** A dry run of an operation that stops, detaches or deletes something answers DryRunOperation and does nothing. */
class Ec2DestructiveDryRunTest {

    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @ValueSource(strings = {"TerminateInstances", "StartInstances", "StopInstances", "RebootInstances",
            "DeleteVpc", "DeleteSubnet", "DeleteTags", "DeleteInternetGateway", "DeleteRouteTable", "DeleteRoute",
            "ReleaseAddress", "DeleteNetworkInterface", "DeleteVolume", "DetachVolume", "DeregisterImage",
            "DeleteLaunchTemplate", "CancelSpotInstanceRequests"})
    void aDryRunAnswersDryRunOperationWithoutReachingTheService(String action) {
        Ec2Service service = mock(Ec2Service.class);
        Ec2QueryHandler handler = new Ec2QueryHandler(service, mock(EmulatorConfig.class),
                mock(FlowLogService.class), mock(Ec2EbsEncryptionService.class),
                mock(Ec2BlockPublicAccessService.class), mock(Ec2IpamService.class),
                new RegionResolver(REGION, "000000000000"));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("DryRun", "true");
        params.putSingle("InstanceId.1", "i-0123456789abcdef0");
        params.putSingle("VpcId", "vpc-0123456789abcdef0");
        params.putSingle("SubnetId", "subnet-0123456789abcdef0");
        params.putSingle("ResourceId.1", "vpc-0123456789abcdef0");
        params.putSingle("Tag.1.Key", "Name");
        params.putSingle("InternetGatewayId", "igw-0123456789abcdef0");
        params.putSingle("RouteTableId", "rtb-0123456789abcdef0");
        params.putSingle("DestinationCidrBlock", "10.0.0.0/16");
        params.putSingle("AllocationId", "eipalloc-0123456789abcdef0");
        params.putSingle("NetworkInterfaceId", "eni-0123456789abcdef0");
        params.putSingle("VolumeId", "vol-0123456789abcdef0");
        params.putSingle("ImageId", "ami-0123456789abcdef0");
        params.putSingle("LaunchTemplateId", "lt-0123456789abcdef0");
        params.putSingle("SpotInstanceRequestId.1", "sir-01234567");

        Response response = handler.handle(action, params, REGION);

        assertEquals(412, response.getStatus());
        assertThat(response.getEntity().toString(), containsString("<Code>DryRunOperation</Code>"));
        verifyNoInteractions(service);
    }
}
