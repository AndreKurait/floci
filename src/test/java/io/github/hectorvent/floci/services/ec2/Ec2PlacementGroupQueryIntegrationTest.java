package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2PlacementGroupQueryIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=000000000000/20261112/us-east-1/ec2/aws4_request";

    @Test
    void queryGetAndXmlReturnElementsKeepTheNativeEnvelope() {
        String name = "pg-query-" + UUID.randomUUID();
        Response created = given().header("Authorization", AUTH).formParam("Action", "CreatePlacementGroup")
                .formParam("Version", "2016-11-15").formParam("GroupName", name).formParam("Strategy", "cluster")
                .post("/");
        try {
            created.then().statusCode(200).body("CreatePlacementGroupResponse.return", equalTo("true"))
                    .body("CreatePlacementGroupResponse.placementGroup.groupName", equalTo(name));
            given().header("Authorization", AUTH).queryParam("Action", "DescribePlacementGroups")
                    .queryParam("Version", "2016-11-15").queryParam("GroupName.1", name).get("/")
                    .then().statusCode(200)
                    .body("DescribePlacementGroupsResponse.placementGroupSet.item.groupName", equalTo(name));
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeletePlacementGroup")
                    .formParam("Version", "2016-11-15").formParam("GroupName", name).post("/")
                    .then().statusCode(200).body("DeletePlacementGroupResponse.return", equalTo("true"));
        }
    }

    @Test
    void malformedDryRunAndWrongTagTypeDoNotCreateTheGroup() {
        String name = "pg-query-" + UUID.randomUUID();
        try {
            given().header("Authorization", AUTH).formParam("Action", "CreatePlacementGroup")
                    .formParam("GroupName", name).formParam("Strategy", "cluster").formParam("DryRun", "not-a-boolean")
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
            given().header("Authorization", AUTH).formParam("Action", "CreatePlacementGroup")
                    .formParam("GroupName", name).formParam("Strategy", "cluster")
                    .formParam("TagSpecification.1.ResourceType", "instance")
                    .formParam("TagSpecification.1.Tag.1.Key", "owner")
                    .formParam("TagSpecification.1.Tag.1.Value", "test")
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
            given().header("Authorization", AUTH).formParam("Action", "DescribePlacementGroups")
                    .formParam("GroupName.1", name).post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidPlacementGroup.Unknown"));
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeletePlacementGroup")
                    .formParam("GroupName", name).post("/");
        }
    }
}
