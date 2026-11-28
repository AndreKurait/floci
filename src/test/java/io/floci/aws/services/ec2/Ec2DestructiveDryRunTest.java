package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.floci.aws.testing.Ec2QueryHandlerFixtures.queryHandler;
import static io.floci.aws.testing.Ec2ServiceFixtures.mockModeService;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** A dry run of an operation that stops, detaches or deletes something answers DryRunOperation and does nothing. */
class Ec2DestructiveDryRunTest {

    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @ValueSource(strings = {"TerminateInstances", "StartInstances", "StopInstances", "RebootInstances",
            "DeleteTags", "DeleteRoute",
            "ReleaseAddress", "DeleteNetworkInterface", "DetachVolume", "DeregisterImage",
            "DeleteLaunchTemplate", "CancelSpotInstanceRequests"})
    void aDryRunAnswersDryRunOperationThroughTheCorrectValidationBoundary(String action) {
        boolean validatesInService = Set.of("TerminateInstances", "StartInstances", "StopInstances", "RebootInstances",
                "DeleteTags", "DeleteRoute", "DetachVolume", "DeleteLaunchTemplate",
                "CancelSpotInstanceRequests").contains(action);
        AtomicInteger validationCalls = new AtomicInteger();
        Ec2Service service = mock(Ec2Service.class, invocation -> {
            assertEquals(true, validatesInService, "This operation must stop before reaching its service");
            assertEquals(Character.toLowerCase(action.charAt(0)) + action.substring(1),
                    invocation.getMethod().getName());
            assertEquals(REGION, invocation.getArgument(0));
            assertEquals(Boolean.TRUE, invocation.getArgument(invocation.getArguments().length - 1),
                    "Validation must receive DryRun=true, so it cannot perform the real operation");
            validationCalls.incrementAndGet();
            throw Ec2Service.dryRunOperation();
        });
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
        assertEquals(validatesInService ? 1 : 0, validationCalls.get());
    }

    @ParameterizedTest
    @CsvSource({
            "DeleteVpc, VpcId, InvalidVpcID.NotFound",
            "DeleteSubnet, SubnetId, InvalidSubnetID.NotFound",
            "DeleteInternetGateway, InternetGatewayId, InvalidInternetGatewayID.NotFound",
            "DeleteRouteTable, RouteTableId, InvalidRouteTableID.NotFound",
            "DeleteVolume, VolumeId, InvalidVolume.NotFound"
    })
    void validatedDeletionDryRunKeepsTheResourceAndStillReportsMissingTargets(
            String action, String parameter, String missingCode) {
        Ec2Service service = mockModeService();
        Ec2QueryHandler handler = queryHandler(service);
        String id = switch (action) {
            case "DeleteVpc" -> service.createVpc(REGION, "10.90.0.0/16", false).getVpcId();
            case "DeleteSubnet" -> service.createSubnet(REGION,
                    service.createVpc(REGION, "10.90.0.0/16", false).getVpcId(),
                    "10.90.1.0/24", REGION + "a").getSubnetId();
            case "DeleteInternetGateway" -> service.createInternetGateway(REGION).getInternetGatewayId();
            case "DeleteRouteTable" -> service.createRouteTable(REGION,
                    service.createVpc(REGION, "10.90.0.0/16", false).getVpcId()).getRouteTableId();
            case "DeleteVolume" -> service.createVolume(REGION, REGION + "a", "gp2", 10,
                    false, null, 0, null, null, List.of()).getVolumeId();
            default -> throw new IllegalArgumentException(action);
        };
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle(parameter, id);
        params.putSingle("DryRun", "true");

        Response dryRun = handler.handle(action, params, REGION);
        assertEquals(412, dryRun.getStatus());
        assertThat(dryRun.getEntity().toString(), containsString("<Code>DryRunOperation</Code>"));

        params.putSingle("DryRun", "false");
        Response deletion = handler.handle(action, params, REGION);
        assertEquals(200, deletion.getStatus(), "The dry run must leave a resource that can still be deleted");

        params.putSingle("DryRun", "true");
        Response missing = handler.handle(action, params, REGION);
        assertEquals(400, missing.getStatus());
        assertThat(missing.getEntity().toString(), containsString("<Code>" + missingCode + "</Code>"));
    }
}
