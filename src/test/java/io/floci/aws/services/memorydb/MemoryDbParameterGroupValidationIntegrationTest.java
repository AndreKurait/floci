package io.floci.aws.services.memorydb;

import io.floci.aws.testing.ValidateSignaturesProfile;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class MemoryDbParameterGroupValidationIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {
            "\"ParameterGroupName\":5", "\"ParameterGroupName\":true", "\"ParameterGroupName\":[]",
            "\"ParameterGroupName\":{}", "\"Family\":7", "\"Family\":false", "\"Description\":9",
            "\"Tags\":{}", "\"Tags\":[\"tag\"]", "\"Tags\":[{\"Key\":4,\"Value\":\"value\"}]",
            "\"Tags\":[{\"Key\":\"key\",\"Value\":7}]", "\"Tags\":[{\"Key\":\"key\",\"Value\":[]}]"
    })
    void createRejectsWrongMemberTypesWithoutPublishing(String malformed) {
        String name = fresh();
        String member = malformed.substring(0, malformed.indexOf(':'));
        String body = "{\"ParameterGroupName\":\"" + name + "\",\"Family\":\"memorydb_redis6\"}";
        if (member.equals("\"ParameterGroupName\"")) {
            body = "{\"Family\":\"memorydb_redis6\"," + malformed + "}";
        } else if (member.equals("\"Family\"")) {
            body = "{\"ParameterGroupName\":\"" + name + "\"," + malformed + "}";
        } else {
            body = body.substring(0, body.length() - 1) + "," + malformed + "}";
        }
        post("CreateParameterGroup", body).then().statusCode(400);
        post("DescribeParameterGroups", Map.of("ParameterGroupName", name)).then().statusCode(400)
                .body("__type", equalTo("ParameterGroupNotFoundFault"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "null", "[]", "{} {}", "{\"ParameterGroupName\":\"not-admitted\"} true"})
    void sharedJsonRouteRejectsMalformedOrNonObjectCreateBody(String body) {
        post("CreateParameterGroup", body).then().statusCode(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"20\"", "20.5", "true", "[]", "2147483648"})
    void paginationRequiresAnActualBoundedIntegralNumber(String value) {
        post("DescribeParameters", "{\"ParameterGroupName\":\"default.memorydb-redis6\",\"MaxResults\":"
                + value + "}").then().statusCode(400);
    }

    @Test
    void updateAndResetTypedFailuresLeaveParametersAndTagsUnchanged() {
        String name = fresh();
        String arn = post("CreateParameterGroup", Map.of("ParameterGroupName", name, "Family", "memorydb_redis6",
                "Tags", List.of(Map.of("Key", "team", "Value", "retained"))))
                .then().statusCode(200).extract().path("ParameterGroup.ARN");
        try {
            String baseline = post("DescribeParameters", Map.of("ParameterGroupName", name))
                    .then().statusCode(200).extract().body().asString();
            post("UpdateParameterGroup", "{\"ParameterGroupName\":\"" + name + "\","
                    + "\"ParameterNameValues\":[{\"ParameterName\":\"timeout\",\"ParameterValue\":\"20\"},"
                    + "{\"ParameterName\":\"slowlog-max-len\",\"ParameterValue\":9}]}")
                    .then().statusCode(400);
            post("ResetParameterGroup", Map.of("ParameterGroupName", name, "AllParameters", "true"))
                    .then().statusCode(400);
            post("ResetParameterGroup", Map.of("ParameterGroupName", name, "ParameterNames", List.of(1)))
                    .then().statusCode(400);
            assertEquals(baseline, post("DescribeParameters", Map.of("ParameterGroupName", name))
                    .then().statusCode(200).extract().body().asString());
            post("ListTags", Map.of("ResourceArn", arn)).then().statusCode(200)
                    .body("TagList[0].Value", equalTo("retained"));
            post("UntagResource", Map.of("ResourceArn", arn, "TagKeys", List.of("team", "missing")))
                    .then().statusCode(200).body("TagList.size()", equalTo(1))
                    .body("TagList[0].Key", equalTo("team")).body("TagList[0].Value", equalTo("retained"));
            post("ListTags", Map.of("ResourceArn", arn)).then().statusCode(200).body("TagList.size()", equalTo(0));
        } finally {
            post("DeleteParameterGroup", Map.of("ParameterGroupName", name)).then().statusCode(200);
        }
    }

    private Response post(String operation, Object body) {
        return given().filter(AwsRequestSigner.signedAs("test", "test", "memorydb"))
                .contentType("application/x-amz-json-1.1").header("X-Amz-Target", "AmazonMemoryDB." + operation)
                .body(body).post(endpoint);
    }

    private static String fresh() {
        return "validation-" + UUID.randomUUID().toString().replace("-", "");
    }
}
