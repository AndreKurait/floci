package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class CloudFormationEc2PrimaryInterfaceIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String AUTH = auth("test", REGION, "cloudformation");

    @Inject
    Ec2Service ec2;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void conditionalPrimaryInterfaceResolvesItsParameterAndReplacesAChangedSubnet() {
        List<Subnet> subnets = ec2.describeSubnets(REGION, List.of(), Map.of());
        String firstSubnet = subnets.get(1).getSubnetId();
        String secondSubnet = subnets.get(2).getSubnetId();
        String stack = create(template(firstSubnet, true));
        String previous = instanceId(stack);
        assertEquals(firstSubnet, instance(previous).getSubnetId());

        cfn("UpdateStack", stack).header("Authorization", AUTH)
                .formParam("TemplateBody", template(secondSubnet, true))
                .formParam("Parameters.member.1.ParameterKey", "Subnet")
                .formParam("Parameters.member.1.ParameterValue", secondSubnet)
                .when().post("/").then().statusCode(200);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, AUTH).status());
        String current = instanceId(stack);
        assertNotEquals(previous, current);
        assertEquals(secondSubnet, instance(current).getSubnetId());
        assertEquals("terminated", instance(previous).getState().getName());
    }

    @Test
    void noValueRemovesTheInterfaceAndKeepsAnInstanceLevelSubnetValid() {
        String subnet = ec2.describeSubnets(REGION, List.of(), Map.of()).get(1).getSubnetId();
        String stack = create(template(subnet, false));

        assertEquals(subnet, instance(instanceId(stack)).getSubnetId());
    }

    @Test
    void mixedSubnetDeclarationFailsWithoutCreatingAnInstance() {
        String subnet = ec2.describeSubnets(REGION, List.of(), Map.of()).getFirst().getSubnetId();
        List<String> before = instanceIds();
        String stack = create("""
                {"Resources":{"Server":{"Type":"AWS::EC2::Instance","Properties":{
                  "ImageId":"ami-12345678","SubnetId":"%1$s",
                  "NetworkInterfaces":[{"DeviceIndex":0,"SubnetId":"%1$s"}]
                }}}}
                """.formatted(subnet), "ROLLBACK_COMPLETE");

        assertEquals(before, instanceIds());
        assertEquals("Network interfaces and an instance-level subnet ID may not be specified on the same request",
                CfnStackWaits.failedResourceReason(stack, AUTH));
    }

    private String create(String template) {
        return create(template, "CREATE_COMPLETE");
    }

    private String create(String template, String expectedStatus) {
        String stack = "primary-interface-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", stack).header("Authorization", AUTH).formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> {
            cfn("DeleteStack", stack).header("Authorization", AUTH).when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(stack, AUTH);
        });
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack, AUTH);
        assertEquals(expectedStatus, state.status(), state.reason());
        return stack;
    }

    private String instanceId(String stack) {
        String xml = cfn("DescribeStacks", stack).header("Authorization", AUTH)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get("InstanceId");
    }

    private Instance instance(String id) {
        return ec2.describeInstances(REGION, List.of(id), Map.of()).getFirst().getInstances().getFirst();
    }

    private List<String> instanceIds() {
        return ec2.describeInstances(REGION, List.of(), Map.of()).stream()
                .flatMap(reservation -> reservation.getInstances().stream())
                .map(Instance::getInstanceId).sorted().toList();
    }

    private static String template(String subnet, boolean useInterface) {
        return """
                {"Parameters":{"Subnet":{"Type":"String","Default":"%s"},
                               "Pinned":{"Type":"String","Default":"%s"}},
                 "Conditions":{"UseInterface":{"Fn::Equals":[{"Ref":"Pinned"},"true"]},
                               "Unused":{"Fn::Equals":["yes","no"]}},
                 "Resources":{"Server":{"Type":"AWS::EC2::Instance","Properties":{
                   "ImageId":"ami-12345678","InstanceType":"t3.micro",
                   "SubnetId":{"Fn::If":["UseInterface",{"Ref":"AWS::NoValue"},{"Ref":"Subnet"}]},
                   "NetworkInterfaces":{"Fn::If":["UseInterface",[
                     {"DeviceIndex":0,"SubnetId":{"Ref":"Subnet"},"AssociatePublicIpAddress":false},
                     {"Fn::If":["Unused",{"DeviceIndex":1},{"Ref":"AWS::NoValue"}]}
                   ],{"Ref":"AWS::NoValue"}]}
                 }}},
                 "Outputs":{"InstanceId":{"Value":{"Ref":"Server"}}}}
                """.formatted(subnet, useInterface);
    }
}
