package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;

@QuarkusTest
class CloudTrailGetTrailIntegrationTest {
    private static final String OWNER = "661100000016";
    private static final String OTHER = "661100000017";
    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";

    @Inject
    ObjectMapper mapper;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void configurationReadsPreserveAccountAndRegionIdentityAndReflectDeletion() throws Exception {
        String name = "read-" + UUID.randomUUID();
        List<Scope> scopes = List.of(new Scope(OWNER, EAST), new Scope(OWNER, WEST), new Scope(OTHER, EAST));
        try {
            for (Scope scope : scopes) {
                invoke(scope, "CreateTrail", Map.of(
                        "Name", name, "S3BucketName", "logs-" + scope.account() + "-" + scope.region(),
                        "S3KeyPrefix", "foundation", "IsMultiRegionTrail", true,
                        "IncludeGlobalServiceEvents", true, "EnableLogFileValidation", true))
                        .then().statusCode(200);
            }
            String firstArn = null;
            for (Scope scope : scopes) {
                String arn = invoke(scope, "GetTrail", Map.of("Name", name)).then().statusCode(200)
                        .body("Trail.Name", equalTo(name))
                        .body("Trail.HomeRegion", equalTo(scope.region()))
                        .body("Trail.S3BucketName", equalTo("logs-" + scope.account() + "-" + scope.region()))
                        .body("Trail.S3KeyPrefix", equalTo("foundation"))
                        .body("Trail.IsMultiRegionTrail", equalTo(true))
                        .body("Trail.IncludeGlobalServiceEvents", equalTo(true))
                        .body("Trail.LogFileValidationEnabled", equalTo(true))
                        .extract().path("Trail.TrailARN");
                invoke(scope, "GetTrail", Map.of("Name", arn)).then().statusCode(200)
                        .body("Trail.TrailARN", equalTo(arn));
                if (firstArn == null) {
                    firstArn = arn;
                }
            }
            invoke(scopes.get(1), "GetTrail", Map.of("Name", firstArn)).then().statusCode(200)
                    .body("Trail.HomeRegion", equalTo(EAST))
                    .body("Trail.TrailARN", equalTo(firstArn));
            invoke(scopes.get(2), "GetTrail", Map.of("Name", firstArn)).then().statusCode(400)
                    .body(containsString("TrailNotFoundException"));
            invoke(new Scope(OWNER, "eu-west-1"), "GetTrail", Map.of("Name", name)).then().statusCode(400)
                    .body(containsString("TrailNotFoundException"));
            invoke(scopes.getFirst(), "DeleteTrail", Map.of("Name", name)).then().statusCode(200);
            invoke(scopes.getFirst(), "GetTrail", Map.of("Name", firstArn)).then().statusCode(400)
                    .body(containsString("TrailNotFoundException"));
            invoke(scopes.get(1), "GetTrail", Map.of("Name", name)).then().statusCode(200)
                    .body("Trail.HomeRegion", equalTo(WEST));
            invoke(scopes.get(2), "GetTrail", Map.of("Name", name)).then().statusCode(200)
                    .body("Trail.HomeRegion", equalTo(EAST));
        } finally {
            assertAll(scopes.stream().map(scope -> () -> {
                Response response = invoke(scope, "DeleteTrail", Map.of("Name", name));
                if (response.statusCode() != 200) {
                    response.then().statusCode(400).body(containsString("TrailNotFoundException"));
                }
            }));
        }
    }

    @ParameterizedTest
    @CsvSource({
        ", InvalidTrailNameException",
        "'', InvalidTrailNameException",
        "ab, InvalidTrailNameException",
        "bad trail, InvalidTrailNameException",
        "arn:malformed, CloudTrailARNInvalidException",
        "arn:aws:s3:::bucket, CloudTrailARNInvalidException",
        "arn:aws:cloudtrail:us-east-1:661100000016:dashboard/name, CloudTrailARNInvalidException",
        "missing-trail, TrailNotFoundException"
    })
    void invalidOrUnknownLookupReturnsTheCloudTrailError(String name, String error) throws Exception {
        invoke(new Scope(OWNER, EAST), "GetTrail", name == null ? Map.of() : Map.of("Name", name))
                .then().statusCode(400).body(containsString(error));
    }

    private Response invoke(Scope scope, String action, Map<String, Object> body) throws Exception {
        return given().header("X-Amz-Target", "CloudTrail_20131101." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + scope.account()
                        + "/20260101/" + scope.region()
                        + "/cloudtrail/aws4_request, SignedHeaders=host, Signature=test")
                .contentType("application/x-amz-json-1.1").body(mapper.writeValueAsString(body))
                .when().post("/");
    }

    private record Scope(String account, String region) {
    }
}
