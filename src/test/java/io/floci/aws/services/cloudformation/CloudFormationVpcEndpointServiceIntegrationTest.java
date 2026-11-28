package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AllowedPrincipal;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.ServiceConfiguration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::EC2::VPCEndpointService} and {@code AWS::EC2::VPCEndpointServicePermissions}: created,
 * updated in place and deleted with their stack, with the documented {@code Ref} and
 * {@code Fn::GetAtt} values.
 */
@QuarkusTest
class CloudFormationVpcEndpointServiceIntegrationTest {

    private static final String ACCOUNT = "000000000421";

    @Test
    void endpointServiceAndItsPermissionsFollowTheirStack() {
        String stack = "vpce-svc-" + UUID.randomUUID().toString().substring(0, 8);
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, Region.US_EAST_1);
             Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), ACCOUNT, Region.US_EAST_1)) {
            cfn.createStack(r -> r.stackName(stack).templateBody(template(stack)).parameters(
                    parameter("Acceptance", "true"), parameter("Principal", "arn:aws:iam::111122223333:root"),
                    parameter("Marker", "initial")));
            String serviceId = null;
            try {
                Map<String, String> outputs = outputs(CfnStackWaits.awaitStatus(cfn, stack, "CREATE_COMPLETE"));
                serviceId = outputs.get("ServiceRef");
                assertTrue(serviceId.matches("vpce-svc-[0-9a-f]{17}"), serviceId);
                assertEquals(serviceId, outputs.get("ServiceId"));
                assertEquals(serviceId, outputs.get("PermissionsRef"));
                assertEquals("pendingVerification", outputs.get("DnsState"));
                assertEquals("TXT", outputs.get("DnsType"));
                assertTrue(outputs.get("DnsValue").startsWith("vpce:"));
                assertTrue(outputs.get("DnsName").startsWith("_"));

                ServiceConfiguration service = describe(ec2, serviceId);
                assertTrue(service.acceptanceRequired());
                assertEquals(List.of(outputs.get("NlbArn")), service.networkLoadBalancerArns());
                assertEquals("svc.example.com", service.privateDnsName());
                assertEquals("initial", service.tags().getFirst().value());
                assertEquals(List.of("arn:aws:iam::111122223333:root"), principals(ec2, serviceId));

                cfn.updateStack(r -> r.stackName(stack).templateBody(template(stack)).parameters(
                        parameter("Acceptance", "false"), parameter("Principal", "*"), parameter("Marker", "updated")));
                Map<String, String> updated = outputs(CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_COMPLETE"));
                assertEquals(serviceId, updated.get("ServiceRef"));
                ServiceConfiguration changed = describe(ec2, serviceId);
                assertEquals(false, changed.acceptanceRequired());
                assertEquals("updated", changed.tags().getFirst().value());
                assertEquals(List.of("*"), principals(ec2, serviceId));
            } finally {
                cfn.deleteStack(r -> r.stackName(stack));
                cfn.waiter().waitUntilStackDeleteComplete(r -> r.stackName(stack));
            }
            String deletedId = serviceId;
            Ec2Exception gone = assertThrows(Ec2Exception.class,
                    () -> ec2.describeVpcEndpointServiceConfigurations(r -> r.serviceIds(deletedId)));
            assertEquals("InvalidVpcEndpointServiceId.NotFound", gone.awsErrorDetails().errorCode());
        }
    }

    private static ServiceConfiguration describe(Ec2Client ec2, String serviceId) {
        return ec2.describeVpcEndpointServiceConfigurations(r -> r.serviceIds(serviceId))
                .serviceConfigurations().getFirst();
    }

    private static List<String> principals(Ec2Client ec2, String serviceId) {
        return ec2.describeVpcEndpointServicePermissions(r -> r.serviceId(serviceId)).allowedPrincipals()
                .stream().map(AllowedPrincipal::principal).toList();
    }

    private static Map<String, String> outputs(Stack stack) {
        return stack.outputs().stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static Parameter parameter(String key, String value) {
        return Parameter.builder().parameterKey(key).parameterValue(value).build();
    }

    private static String template(String name) {
        return """
            {
              "Parameters": {
                "Acceptance": {"Type": "String"},
                "Principal": {"Type": "String"},
                "Marker": {"Type": "String"}
              },
              "Resources": {
                "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.91.0.0/16"}},
                "SubnetA": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": {"Ref": "Vpc"},
                            "CidrBlock": "10.91.1.0/24", "AvailabilityZone": "us-east-1a"}},
                "SubnetB": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": {"Ref": "Vpc"},
                            "CidrBlock": "10.91.2.0/24", "AvailabilityZone": "us-east-1b"}},
                "Nlb": {"Type": "AWS::ElasticLoadBalancingV2::LoadBalancer", "Properties": {
                  "Name": "%s", "Type": "network", "Scheme": "internal",
                  "Subnets": [{"Ref": "SubnetA"}, {"Ref": "SubnetB"}]}},
                "Service": {"Type": "AWS::EC2::VPCEndpointService", "Properties": {
                  "NetworkLoadBalancerArns": [{"Ref": "Nlb"}],
                  "AcceptanceRequired": {"Ref": "Acceptance"},
                  "PrivateDnsName": "svc.example.com",
                  "Tags": [{"Key": "marker", "Value": {"Ref": "Marker"}}]}},
                "Permissions": {"Type": "AWS::EC2::VPCEndpointServicePermissions", "Properties": {
                  "ServiceId": {"Ref": "Service"},
                  "AllowedPrincipals": [{"Ref": "Principal"}]}}
              },
              "Outputs": {
                "NlbArn": {"Value": {"Ref": "Nlb"}},
                "ServiceRef": {"Value": {"Ref": "Service"}},
                "ServiceId": {"Value": {"Fn::GetAtt": ["Service", "ServiceId"]}},
                "PermissionsRef": {"Value": {"Ref": "Permissions"}},
                "DnsState": {"Value": {"Fn::GetAtt": ["Service", "PrivateDnsNameConfiguration.State"]}},
                "DnsType": {"Value": {"Fn::GetAtt": ["Service", "PrivateDnsNameConfiguration.Type"]}},
                "DnsValue": {"Value": {"Fn::GetAtt": ["Service", "PrivateDnsNameConfiguration.Value"]}},
                "DnsName": {"Value": {"Fn::GetAtt": ["Service", "PrivateDnsNameConfiguration.Name"]}}
              }
            }
            """.formatted(name);
    }
}
