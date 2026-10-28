package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end check that CloudFormation provisions AWS::EC2::LaunchTemplate for real
 * (issue #1971): the template exists in Ec2Service, Ref resolves to the launch template
 * id, Fn::GetAtt LatestVersionNumber resolves, and DeleteStack removes it. Metadata-only,
 * so the test is Docker-free.
 */
@QuarkusTest
class CloudFormationLaunchTemplateIntegrationTest {

    @Inject
    Ec2Service ec2Service;

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void ec2InstanceResolvesImageAndTypeFromLaunchTemplate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lt-inst-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {
                          "ImageId": "ami-87654321",
                          "InstanceType": "t3.small"
                        }
                      }
                    },
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "LaunchTemplate": {
                          "LaunchTemplateId": {"Ref": "Lt"},
                          "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "InstanceId": {"Value": {"Ref": "Inst"}}
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<OutputValue>i-"));

        // Delete the stack so the instance is terminated: a live instance would otherwise leak
        // into the shared emulator state and change what EC2 describe calls in other tests see.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void createStackProvisionsLaunchTemplateWithVersionAttributes() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String templateName = "cfn-lt-" + suffix;
        String stackName = "cfn-lt-stack-" + suffix;
        String encodedUserData = "IyEvYmluL2Jhc2gKZWNobyBoaQo=";

        String template = """
                {
                  "Resources": {
                    "AppLaunchTemplate": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateName": "%s",
                        "LaunchTemplateData": {
                          "ImageId": "ami-12345678",
                          "InstanceType": "t3.micro",
                          "UserData": "%s"
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "TemplateId": {"Value": {"Ref": "AppLaunchTemplate"}},
                    "LatestVersion": {"Value": {"Fn::GetAtt": ["AppLaunchTemplate", "LatestVersionNumber"]}}
                  }
                }
                """.formatted(templateName, encodedUserData);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Stack completes, Ref resolves to a launch template id, GetAtt resolves the version.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<OutputValue>lt-"))
            .body(containsString("<OutputValue>1</OutputValue>"));

        // The launch template is real: EC2 DescribeLaunchTemplates finds it by name.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeLaunchTemplates")
            .formParam("LaunchTemplateName.1", templateName)
            .formParam("Version", "2016-11-15")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(templateName));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", templateName)
            .formParam("Versions.1", "$Latest")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.userData",
                    equalTo(encodedUserData));

        LaunchTemplateData launchData = ec2Service.resolveLaunchTemplateData(
                "us-east-1", null, templateName, null);
        assertEquals("#!/bin/bash\necho hi\n", launchData.getUserData());
        assertEquals(encodedUserData, launchData.getEncodedUserData());

        String instanceId = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "RunInstances")
            .formParam("LaunchTemplate.LaunchTemplateName", templateName)
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        Instance instance = ec2Service.describeInstances("us-east-1", List.of(instanceId), Map.of())
                .getFirst().getInstances().getFirst();
        assertEquals(launchData.getUserData(), instance.getUserData());
        assertEquals(encodedUserData, instance.getEncodedUserData());

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "TerminateInstances")
            .formParam("InstanceId.1", instanceId)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.awaitStackDeleted(stackName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeLaunchTemplates")
            .formParam("Version", "2016-11-15")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(templateName)));
    }

    @Test
    void explicitUserDataOverridesOversizedLegacyTemplate() {
        String templateName = "legacy-user-data-" + Long.toString(System.nanoTime(), 36);
        String oversized = Base64.getEncoder().encodeToString(
                "A".repeat(16 * 1024 + 1).getBytes(StandardCharsets.UTF_8));
        LaunchTemplateData legacyData = new LaunchTemplateData();
        legacyData.setImageId("ami-12345678");
        legacyData.setInstanceType("t3.micro");
        legacyData.setEncodedUserData(oversized);
        ec2Service.createLaunchTemplate("us-east-1", templateName, legacyData, List.of());

        String userData = "#!/bin/sh\necho override\n";
        String replacement = Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));
        String instanceId = null;
        try {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "RunInstances")
                .formParam("LaunchTemplate.LaunchTemplateName", templateName)
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("UserData exceeds the 16 KiB limit"));

            instanceId = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "RunInstances")
                .formParam("LaunchTemplate.LaunchTemplateName", templateName)
                .formParam("UserData", replacement)
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

            Instance instance = ec2Service.describeInstances("us-east-1", List.of(instanceId), Map.of())
                    .getFirst().getInstances().getFirst();
            assertEquals(userData, instance.getUserData());
            assertEquals(replacement, instance.getEncodedUserData());
        } finally {
            if (instanceId != null) {
                ec2Service.terminateInstances("us-east-1", List.of(instanceId));
            }
            ec2Service.deleteLaunchTemplate("us-east-1", null, templateName);
        }
    }

    @Test
    void cloudFormationInstanceIgnoresUnusedLegacyTemplateUserData() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String templateName = "legacy-cfn-user-data-" + suffix;
        String stackName = "legacy-cfn-user-data-stack-" + suffix;
        String oversized = Base64.getEncoder().encodeToString(
                "A".repeat(16 * 1024 + 1).getBytes(StandardCharsets.UTF_8));
        LaunchTemplateData legacyData = new LaunchTemplateData();
        legacyData.setImageId("ami-12345678");
        legacyData.setInstanceType("t3.micro");
        legacyData.setEncodedUserData(oversized);
        ec2Service.createLaunchTemplate("us-east-1", templateName, legacyData, List.of());

        String template = """
                {
                  "Resources": {
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "LaunchTemplate": {"LaunchTemplateName": "%s", "Version": "1"},
                        "UserData": "IyEvYmluL3NoCmVjaG8gY2ZuCg=="
                      }
                    }
                  }
                }
                """.formatted(templateName);
        try (AutoCloseable cleanup = () -> {
            try {
                given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", CFN_AUTH)
                    .formParam("Action", "DeleteStack")
                    .formParam("StackName", stackName)
                .when()
                    .post("/")
                .then()
                    .statusCode(200);
                CfnStackWaits.awaitStackDeleted(stackName);
            } finally {
                ec2Service.deleteLaunchTemplate("us-east-1", null, templateName);
            }
        }) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
            .when()
                .post("/")
            .then()
                .statusCode(200);

            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("CREATE_COMPLETE", state.status(), state.reason());
        }
    }

    @Test
    void networkInterfaceOptionsSurviveCreateUpdateAndRollbackAndDriveLaunch() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lt-network-" + suffix;
        String templateName = "cfn-lt-network-template-" + suffix;
        try {
            applyNetworkTemplate(stackName, networkTemplate(templateName, false, true, false), false);
            assertNetworkTemplate(templateName, "1", Boolean.FALSE);
            assertTemplateLaunchPublicIp(templateName, false);

            applyNetworkTemplate(stackName, networkTemplate(templateName, true, false, false), true);
            assertNetworkTemplate(templateName, "2", Boolean.TRUE);
            given()
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeLaunchTemplates")
                .formParam("LaunchTemplateName.1", templateName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLaunchTemplatesResponse.launchTemplates.item.defaultVersionNumber", equalTo("1"))
                .body("DescribeLaunchTemplatesResponse.launchTemplates.item.latestVersionNumber", equalTo("2"));
            assertNetworkTemplate(templateName, "$Default", "1", Boolean.FALSE);
            assertNetworkTemplate(templateName, "1", "1", Boolean.FALSE);
            assertNetworkTemplate(templateName, "2", "2", Boolean.TRUE);
            given()
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeLaunchTemplateVersions")
                .formParam("LaunchTemplateName", templateName)
                .formParam("LaunchTemplateVersion.1", "1")
                .formParam("LaunchTemplateVersion.2", "$Latest")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionNumber",
                        contains("1", "2"));
            given()
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeLaunchTemplateVersions")
                .formParam("LaunchTemplateName", templateName)
                .formParam("LaunchTemplateVersion.1", "invalid")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidLaunchTemplateVersion.Malformed</Code>"));
            given()
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeLaunchTemplateVersions")
                .formParam("LaunchTemplateName", templateName)
                .formParam("LaunchTemplateVersion.1", "1")
                .formParam("LaunchTemplateVersion.2", "999")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidLaunchTemplateVersion.NotFound</Code>"));
            assertTemplateLaunchPublicIp(templateName, true);

            applyNetworkTemplate(stackName, networkTemplate(templateName, false, false, true), true);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertNetworkTemplate(templateName, "2", Boolean.TRUE);
            assertNetworkTemplate(templateName, "$Default", "1", Boolean.FALSE);

            applyNetworkTemplate(stackName, networkTemplate(templateName, null, true, false), true);
            assertNetworkTemplate(templateName, "4", null);
            given()
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeLaunchTemplateVersions")
                .formParam("LaunchTemplateName", templateName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionNumber",
                        contains("1", "2", "4"));
            assertTemplateLaunchPublicIp(templateName, true);
        } finally {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void conditionalNoValueOmitsInterfaceFieldAndListItemAndKeepsSubnetDefault() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lt-conditional-" + suffix;
        String templateName = "cfn-lt-conditional-template-" + suffix;
        String template = """
                {
                  "Conditions": {"ExplicitPublicIp": {"Fn::Equals": ["yes", "no"]}},
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.84.0.0/16"}},
                    "Subnet": {"Type": "AWS::EC2::Subnet", "Properties": {
                      "VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.84.1.0/24", "MapPublicIpOnLaunch": true}},
                    "Lt": {"Type": "AWS::EC2::LaunchTemplate", "Properties": {
                      "LaunchTemplateName": "%s", "LaunchTemplateData": {
                        "ImageId": "ami-12345678", "InstanceType": "t3.micro",
                        "NetworkInterfaces": [
                          {"DeviceIndex": 0, "SubnetId": {"Ref": "Subnet"},
                           "AssociatePublicIpAddress": {"Fn::If": ["ExplicitPublicIp", true, {"Ref": "AWS::NoValue"}]}},
                          {"Fn::If": ["ExplicitPublicIp", {"DeviceIndex": 1}, {"Ref": "AWS::NoValue"}]}
                        ]}}}
                  }
                }
                """.formatted(templateName);
        try {
            CfnStackWaits.StackState state = applyNetworkTemplate(stackName, template, false);
            assertEquals("CREATE_COMPLETE", state.status(), state.reason());
            assertNetworkTemplate(templateName, "1", null);
            assertEquals(1, ec2Service.resolveLaunchTemplateData("us-east-1", null, templateName, "$Latest")
                    .getNetworkInterfaces().size());
            assertTemplateLaunchPublicIp(templateName, true);
        } finally {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    private CfnStackWaits.StackState applyNetworkTemplate(String stackName, String template, boolean update) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", update ? "UpdateStack" : "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        return CfnStackWaits.awaitTerminal(stackName);
    }

    private void assertNetworkTemplate(String templateName, String version, Boolean associatePublicIp) {
        assertNetworkTemplate(templateName, "$Latest", version, associatePublicIp);
    }

    private void assertNetworkTemplate(String templateName, String selector, String version, Boolean associatePublicIp) {
        String response = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", templateName)
            .formParam("LaunchTemplateVersion.1", selector)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<versionNumber>" + version + "</versionNumber>"))
            .body(containsString("<deviceIndex>0</deviceIndex>"))
            .body(containsString("<subnetId>subnet-"))
            .extract().asString();
        LaunchTemplateData data = ec2Service.resolveLaunchTemplateData("us-east-1", null, templateName, selector);
        assertEquals(associatePublicIp, data.effectiveAssociatePublicIpAddress());
        if (associatePublicIp == null) {
            assertNull(data.primaryNetworkInterface().getAssociatePublicIpAddress());
            assertThat(response, not(containsString("<associatePublicIpAddress>")));
        } else {
            assertThat(response,
                    containsString("<associatePublicIpAddress>" + associatePublicIp + "</associatePublicIpAddress>"));
        }
    }

    private void assertTemplateLaunchPublicIp(String templateName, boolean expected) {
        String instanceId = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "RunInstances")
            .formParam("LaunchTemplate.LaunchTemplateName", templateName)
            .formParam("LaunchTemplate.Version", "$Latest")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            Instance instance = ec2Service.describeInstances("us-east-1", List.of(instanceId), Map.of())
                    .getFirst().getInstances().getFirst();
            assertEquals(expected, instance.isAssociatePublicIp());
        } finally {
            ec2Service.terminateInstances("us-east-1", List.of(instanceId));
        }
    }

    private String networkTemplate(String name, Boolean associatePublicIp, boolean subnetPublicIp, boolean failUpdate) {
        String publicIp = associatePublicIp == null ? "" : "\"AssociatePublicIpAddress\": " + associatePublicIp + ",";
        String failingQueue = failUpdate ? """
                , "BadQueue": {"Type": "AWS::SQS::Queue", "DependsOn": ["Lt"],
                  "Properties": {"QueueName": "invalid-fifo-name", "FifoQueue": true}}
                """ : "";
        return """
                {"Resources": {
                  "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.83.0.0/16"}},
                  "Subnet": {"Type": "AWS::EC2::Subnet", "Properties": {
                    "VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.83.1.0/24",
                    "MapPublicIpOnLaunch": %s}},
                  "Lt": {"Type": "AWS::EC2::LaunchTemplate", "Properties": {
                    "LaunchTemplateName": "%s", "LaunchTemplateData": {
                      "ImageId": "ami-12345678", "InstanceType": "t3.micro",
                      "NetworkInterfaces": [{%s "DeviceIndex": 0, "SubnetId": {"Ref": "Subnet"}}]}}}
                  %s
                }}
                """.formatted(subnetPublicIp, name, publicIp, failingQueue);
    }
}
