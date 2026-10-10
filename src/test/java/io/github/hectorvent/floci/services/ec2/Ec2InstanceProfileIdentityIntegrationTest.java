package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class Ec2InstanceProfileIdentityIntegrationTest {
    private static final String ACCOUNT = "246813579012";
    private static final String OTHER = "135792468013";
    @Inject Ec2Service ec2;
    @Inject ObjectMapper mapper;

    @ParameterizedTest
    @CsvSource({"Name,false", "Arn,false", "Name,true", "Arn,true"})
    void launchUsesTheSameIamIdentityForEveryInstance(String field, boolean template) {
        String name = "profile-identity-" + UUID.randomUUID().toString().substring(0, 8);
        Profile profile = createProfile(ACCOUNT, name);
        List<String> instances = new ArrayList<>();
        String templateId = null;
        try {
            RequestSpecification run = request(ACCOUNT, "ec2", "RunInstances")
                    .formParam("MinCount", "2").formParam("MaxCount", "2")
                    .formParam("ClientToken", name);
            String value = field.equals("Name") ? name : profile.arn();
            if (template) {
                templateId = request(ACCOUNT, "ec2", "CreateLaunchTemplate")
                        .formParam("LaunchTemplateName", name)
                        .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                        .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                        .formParam("LaunchTemplateData.IamInstanceProfile." + field, value)
                        .post("/").then().statusCode(200)
                        .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
                run.formParam("LaunchTemplate.LaunchTemplateId", templateId);
            } else {
                run.formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                        .formParam("IamInstanceProfile." + field, value);
            }
            XmlPath response = run.post("/").then().statusCode(200).extract().xmlPath();
            instances.addAll(response.getList("RunInstancesResponse.instancesSet.item.instanceId"));
            assertEquals(List.of(profile.id(), profile.id()),
                    response.getList("RunInstancesResponse.instancesSet.item.iamInstanceProfile.id"));
            List<String> filtered = request(ACCOUNT, "ec2", "DescribeInstances")
                    .formParam("Filter.1.Name", "client-token").formParam("Filter.1.Value.1", name)
                    .post("/").then().statusCode(200).extract().xmlPath()
                    .getList("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId");
            assertEquals(instances.stream().sorted().toList(), filtered.stream().sorted().toList());
            for (String id : instances) {
                assertProfile(id, profile);
            }
        } finally {
            for (String id : instances) {
                terminate(id);
            }
            if (templateId != null) {
                request(ACCOUNT, "ec2", "DeleteLaunchTemplate").formParam("LaunchTemplateId", templateId)
                        .post("/").then().statusCode(200);
            }
            deleteProfile(ACCOUNT, name);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidArnsCannotCreateInstances(boolean template) {
        String name = "profile-invalid-" + UUID.randomUUID().toString().substring(0, 8);
        Profile own = createProfile(ACCOUNT, name);
        Profile other = createProfile(OTHER, name);
        try {
            for (String arn : List.of(own.arn() + "-absent", other.arn(),
                    own.arn().replace("/workers/", "/other/"),
                    own.arn().replace(":instance-profile/", ":role/"),
                    own.arn().replace("arn:aws:", "arn:aws-cn:"))) {
                String token = UUID.randomUUID().toString();
                String templateId = null;
                try {
                    RequestSpecification run = request(ACCOUNT, "ec2", "RunInstances")
                            .formParam("MinCount", "1").formParam("MaxCount", "1")
                            .formParam("ClientToken", token);
                    if (template) {
                        templateId = request(ACCOUNT, "ec2", "CreateLaunchTemplate")
                                .formParam("LaunchTemplateName", "invalid-" + token)
                                .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                                .formParam("LaunchTemplateData.IamInstanceProfile.Arn", arn)
                                .post("/").then().statusCode(200)
                                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
                        run.formParam("LaunchTemplate.LaunchTemplateId", templateId);
                    } else {
                        run.formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                                .formParam("IamInstanceProfile.Arn", arn);
                    }
                    run.post("/").then().statusCode(400)
                            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
                    request(ACCOUNT, "ec2", "DescribeInstances").formParam("Filter.1.Name", "client-token")
                            .formParam("Filter.1.Value.1", token).post("/").then().statusCode(200)
                            .body("DescribeInstancesResponse.reservationSet.item.size()", equalTo(0));
                } finally {
                    if (templateId != null) {
                        request(ACCOUNT, "ec2", "DeleteLaunchTemplate").formParam("LaunchTemplateId", templateId)
                                .post("/").then().statusCode(200);
                    }
                }
            }
        } finally {
            deleteProfile(ACCOUNT, name);
            deleteProfile(OTHER, name);
        }
    }

    @Test
    void attachmentIdentityIsPersistedAndUnknownLegacyIdsAreOmitted() throws Exception {
        String name = "profile-stored-" + UUID.randomUUID().toString().substring(0, 8);
        Profile profile = createProfile(ACCOUNT, name);
        String id = request(ACCOUNT, "ec2", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("IamInstanceProfile.Name", name).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            Instance stored = ec2.findInstanceForAccount(ACCOUNT, "us-east-1", id).orElseThrow();
            Instance roundTrip = mapper.readValue(mapper.writeValueAsBytes(stored), Instance.class);
            assertEquals(profile.id(), roundTrip.getIamInstanceProfileId());
            roundTrip.setIamInstanceProfileId(null);
            assertNull(mapper.readValue(mapper.writeValueAsBytes(roundTrip), Instance.class)
                    .getIamInstanceProfileId());
            stored.setIamInstanceProfileId(null);
            request(ACCOUNT, "ec2", "DescribeInstances").formParam("InstanceId.1", id)
                    .post("/").then().statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.id.size()",
                            equalTo(0));
        } finally {
            terminate(id);
            deleteProfile(ACCOUNT, name);
        }
    }

    @Test
    void conflictingSelectorsDoNotReplaceAnExistingAssociation() {
        String name = "profile-replace-" + UUID.randomUUID().toString().substring(0, 8);
        Profile first = createProfile(ACCOUNT, name);
        Profile second = createProfile(ACCOUNT, name + "-second");
        String id = request(ACCOUNT, "ec2", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("IamInstanceProfile.Name", name).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        try {
            String association = Ec2Service.iamInstanceProfileAssociationId(id);
            request(ACCOUNT, "ec2", "ReplaceIamInstanceProfileAssociation")
                    .formParam("AssociationId", association).formParam("IamInstanceProfile.Name", name)
                    .formParam("IamInstanceProfile.Arn", second.arn()).post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
            assertProfile(id, first);
            request(ACCOUNT, "ec2", "ReplaceIamInstanceProfileAssociation")
                    .formParam("AssociationId", association).formParam("IamInstanceProfile.Arn", second.arn())
                    .post("/").then().statusCode(200)
                    .body("ReplaceIamInstanceProfileAssociationResponse.iamInstanceProfileAssociation"
                            + ".iamInstanceProfile.id", equalTo(second.id()));
            assertProfile(id, second);
            request(ACCOUNT, "ec2", "DisassociateIamInstanceProfile").formParam("AssociationId", association)
                    .post("/").then().statusCode(200)
                    .body("DisassociateIamInstanceProfileResponse.iamInstanceProfileAssociation"
                            + ".iamInstanceProfile.id", equalTo(second.id()));
        } finally {
            terminate(id);
            deleteProfile(ACCOUNT, name);
            deleteProfile(ACCOUNT, name + "-second");
        }
    }

    private static void assertProfile(String instanceId, Profile profile) {
        request(ACCOUNT, "ec2", "DescribeInstances").formParam("InstanceId.1", instanceId)
                .post("/").then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.arn",
                        equalTo(profile.arn()))
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.id",
                        equalTo(profile.id()));
        request(ACCOUNT, "ec2", "DescribeIamInstanceProfileAssociations")
                .formParam("Filter.1.Name", "instance-id").formParam("Filter.1.Value.1", instanceId)
                .post("/").then().statusCode(200)
                .body("DescribeIamInstanceProfileAssociationsResponse.iamInstanceProfileAssociationSet"
                        + ".item.iamInstanceProfile.id", equalTo(profile.id()));
    }

    private static Profile createProfile(String account, String name) {
        String prefix = "CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.";
        XmlPath value = request(account, "iam", "CreateInstanceProfile")
                .formParam("InstanceProfileName", name).formParam("Path", "/workers/").post("/")
                .then().statusCode(200).extract().xmlPath();
        return new Profile(value.getString(prefix + "Arn"), value.getString(prefix + "InstanceProfileId"));
    }

    private static void deleteProfile(String account, String name) {
        request(account, "iam", "DeleteInstanceProfile").formParam("InstanceProfileName", name)
                .post("/").then().statusCode(200);
    }

    private static void terminate(String instanceId) {
        request(ACCOUNT, "ec2", "TerminateInstances").formParam("InstanceId.1", instanceId)
                .post("/").then().statusCode(200);
    }

    private static RequestSpecification request(String account, String service, String action) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                + "/20261010/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc")
                .formParam("Action", action);
    }

    private record Profile(String arn, String id) {}
}
