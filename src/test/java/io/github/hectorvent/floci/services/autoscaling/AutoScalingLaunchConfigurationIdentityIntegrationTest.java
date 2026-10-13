package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class AutoScalingLaunchConfigurationIdentityIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=222222222222/20261012/us-east-1/autoscaling/aws4_request";
    @Inject
    AutoScalingReconciler reconciler;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void launchResolvesProfileAndPreservesRequiredMetadata(boolean useArn) {
        String name = "launch-identity-" + UUID.randomUUID();
        String arn = given().header("Authorization", AUTH.replace("/autoscaling/", "/iam/"))
                .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                .post("/").then().statusCode(200).extract().path(
                        "CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.Arn");
        given().header("Authorization", AUTH).formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("IamInstanceProfile", useArn ? arn : name)
                .formParam("MetadataOptions.HttpTokens", "required")
                .formParam("MetadataOptions.HttpEndpoint", "enabled")
                .formParam("MetadataOptions.HttpPutResponseHopLimit", "2")
                .post("/").then().statusCode(200);
        given().header("Authorization", AUTH).formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", name).formParam("LaunchConfigurationName", name)
                .formParam("MinSize", "1").formParam("MaxSize", "1").formParam("DesiredCapacity", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a").post("/").then().statusCode(200);
        reconciler.reconcileAll();
        String instance = given().header("Authorization", AUTH)
                .formParam("Action", "DescribeAutoScalingGroups").formParam("AutoScalingGroupNames.member.1", name)
                .post("/").then().statusCode(200)
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                        + ".AutoScalingGroups.member.Instances.member.size()", equalTo(1))
                .extract().path("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                        + ".AutoScalingGroups.member.Instances.member.InstanceId");
        given().header("Authorization", AUTH.replace("/autoscaling/", "/ec2/")).formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instance).post("/").then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.arn", equalTo(arn))
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.metadataOptions.httpTokens", equalTo("required"))
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.metadataOptions.httpEndpoint", equalTo("enabled"))
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.metadataOptions.httpPutResponseHopLimit", equalTo("2"));
    }
    @ParameterizedTest
    @CsvSource({"MetadataOptions.HttpTokens=invalid,ValidationError",
            "MetadataOptions.HttpEndpoint=invalid,ValidationError",
            "MetadataOptions.HttpPutResponseHopLimit=0,ValidationError",
            "MetadataOptions.HttpPutResponseHopLimit=65,ValidationError",
            "MetadataOptions.HttpPutResponseHopLimit=not-an-integer,MalformedInput"})
    void invalidMetadataRefusesWithoutCreatingLaunchConfiguration(String parameter, String code) {
        String name = "invalid-metadata-" + UUID.randomUUID();
        String[] pair = parameter.split("=", 2);
        given().header("Authorization", AUTH).formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam(pair[0], pair[1])
                .post("/").then().statusCode(400).body("ErrorResponse.Error.Code", equalTo(code));
        given().header("Authorization", AUTH).formParam("Action", "DescribeLaunchConfigurations")
                .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                .body("DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                        + ".LaunchConfigurations.member.size()", equalTo(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queryAndCloudFormationPreserveMetadataDefaultsAndExplicitValues(boolean cloudFormation) {
        String name = "metadata-roundtrip-" + UUID.randomUUID();
        if (cloudFormation) {
            String template = """
                    {"Resources":{"Launch":{"Type":"AWS::AutoScaling::LaunchConfiguration",
                    "Properties":{"LaunchConfigurationName":"%s","ImageId":"ami-amazonlinux2023",
                    "InstanceType":"t3.micro","MetadataOptions":{"HttpTokens":"required",
                    "HttpEndpoint":"disabled","HttpPutResponseHopLimit":3}}}}}
                    """.formatted(name);
            given().header("Authorization", AUTH.replace("/autoscaling/", "/cloudformation/"))
                    .formParam("Action", "CreateStack").formParam("StackName", name)
                    .formParam("TemplateBody", template).post("/").then().statusCode(200);
        } else {
            given().header("Authorization", AUTH).formParam("Action", "CreateLaunchConfiguration")
                    .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                    .formParam("InstanceType", "t3.micro").post("/").then().statusCode(200);
        }
        String prefix = "DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                + ".LaunchConfigurations.member.MetadataOptions";
        if (cloudFormation) {
            given().header("Authorization", AUTH).formParam("Action", "DescribeLaunchConfigurations")
                    .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                    .body(prefix + ".HttpTokens", equalTo("required"))
                    .body(prefix + ".HttpEndpoint", equalTo("disabled"))
                    .body(prefix + ".HttpPutResponseHopLimit", equalTo("3"));
        } else {
            given().header("Authorization", AUTH).formParam("Action", "DescribeLaunchConfigurations")
                    .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                    .body(prefix + ".size()", equalTo(0));
        }
    }

    @Test
    void partialMetadataFillsOnlyTheOtherMetadataDefaults() {
        String name = "partial-metadata-" + UUID.randomUUID();
        given().header("Authorization", AUTH).formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MetadataOptions.HttpTokens", "required")
                .post("/").then().statusCode(200);
        String prefix = "DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                + ".LaunchConfigurations.member.MetadataOptions";
        given().header("Authorization", AUTH).formParam("Action", "DescribeLaunchConfigurations")
                .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                .body(prefix + ".HttpTokens", equalTo("required"))
                .body(prefix + ".HttpEndpoint", equalTo("enabled"))
                .body(prefix + ".HttpPutResponseHopLimit", equalTo("1"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrForeignProfileRefusesLaunchConfiguration(boolean foreignArn) {
        String name = "refused-profile-" + UUID.randomUUID();
        if (foreignArn) {
            given().header("Authorization", AUTH.replace("/autoscaling/", "/iam/"))
                    .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                    .post("/").then().statusCode(200);
        }
        given().header("Authorization", AUTH).formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("IamInstanceProfile",
                        foreignArn ? "arn:aws:iam::333333333333:instance-profile/" + name : name)
                .post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
        given().header("Authorization", AUTH).formParam("Action", "DescribeLaunchConfigurations")
                .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                .body("DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                        + ".LaunchConfigurations.member.size()", equalTo(0));
    }

}
