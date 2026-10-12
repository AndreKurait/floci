package io.github.hectorvent.floci.testing;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;

public final class RdsRequests {
    private RdsRequests() {}

    public static RequestSpecification rds(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20261011/us-east-1/rds/aws4_request")
                .contentType(URLENC).formParam("Action", action).formParam("Version", "2014-10-31");
    }
}
