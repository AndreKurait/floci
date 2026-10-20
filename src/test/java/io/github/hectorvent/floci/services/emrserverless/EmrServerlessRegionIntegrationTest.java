package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class EmrServerlessRegionIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification emr(String region) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=TEST/20261020/" + region
                        + "/emr-serverless/aws4_request, SignedHeaders=host;x-amz-date, Signature=test")
                .contentType("application/json")
                .urlEncodingEnabled(false);
    }

    private static String tagsPath(String arn) {
        return "/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    @Test
    void applicationsAndClientTokensStayInTheirCreatingRegion() {
        String token = UUID.randomUUID().toString();
        String body = """
                {"name":"regional","releaseLabel":"emr-7.5.0","type":"SPARK",
                 "clientToken":"%s","tags":{"team":"data"}}
                """.formatted(token);

        Response east = emr("us-east-1").body(body).when().post("/applications");
        east.then().statusCode(200).body("arn", startsWith("arn:aws:emr-serverless:us-east-1:"));
        String eastId = east.jsonPath().getString("applicationId");
        String eastArn = east.jsonPath().getString("arn");

        Response west = emr("eu-west-1").body(body).when().post("/applications");
        west.then().statusCode(200).body("arn", startsWith("arn:aws:emr-serverless:eu-west-1:"))
                .body("applicationId", not(equalTo(eastId)));
        String westId = west.jsonPath().getString("applicationId");
        String westArn = west.jsonPath().getString("arn");

        emr("us-east-1").body(body).when().post("/applications")
                .then().statusCode(200).body("applicationId", equalTo(eastId));
        emr("eu-west-1").body(body).when().post("/applications")
                .then().statusCode(200).body("applicationId", equalTo(westId));

        emr("us-east-1").when().get("/applications/" + eastId)
                .then().statusCode(200).body("application.arn", equalTo(eastArn));
        emr("eu-west-1").when().get("/applications/" + westId)
                .then().statusCode(200).body("application.arn", equalTo(westArn));
        emr("eu-west-1").when().get("/applications/" + eastId)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        emr("us-east-1").when().get("/applications/" + westId)
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        emr("us-east-1").when().get("/applications")
                .then().statusCode(200)
                .body("applications.id", hasItem(eastId))
                .body("applications.id", not(hasItem(westId)));
        emr("eu-west-1").when().get("/applications")
                .then().statusCode(200)
                .body("applications.id", hasItem(westId))
                .body("applications.id", not(hasItem(eastId)));

        emr("us-east-1").when().get(tagsPath(eastArn))
                .then().statusCode(200).body("tags.team", equalTo("data"));
        emr("eu-west-1").when().get(tagsPath(eastArn))
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        emr("eu-west-1").body("{\"tags\":{\"team\":\"wrong-region\"}}")
                .when().post(tagsPath(eastArn))
                .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        emr("us-east-1").when().get(tagsPath(eastArn))
                .then().statusCode(200).body("tags.team", equalTo("data"));
    }
}
