package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.config;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudWatchQueryGetIamEnforcementIntegrationTest {

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"Action", "Operation"})
    void unsignedWritesReturnQueryAuthenticationErrors(String actionParameter) {
        given()
                .queryParam(actionParameter, "PutMetricData")
                .queryParam("Version", "2010-08-01")
                .queryParam("Namespace", "query-auth-" + UUID.randomUUID())
                .queryParam("MetricData.member.1.MetricName", "Denied")
                .queryParam("MetricData.member.1.Value", "1")
        .when().get("/")
        .then()
                .statusCode(403)
                .contentType(containsString("xml"))
                .body("ErrorResponse.Error.Code", equalTo("MissingAuthenticationToken"));
    }

    @ParameterizedTest
    @CsvSource({"Action,false,ListMetrics,2010-08-01", "Operation,false,ListMetrics,2010-08-01",
            "Action,true,ListMetrics,2010-08-01", "Operation,true,ListMetrics,2010-08-01",
            "Action,false,DescribeAlarms,2013-01-16", "Action,true,DescribeAlarms,2013-01-16"})
    void deniedSignedReadsReturnQueryAuthorizationErrors(
            String actionParameter, boolean presigned, String action, String version) {
        String user = "query-denied-" + UUID.randomUUID();
        String accessKey = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), auth("000000000000", "iam"), user).accessKeyId();

        signedRequest(accessKey, presigned)
                .queryParam(actionParameter, action)
                .queryParam("Version", version)
        .when().get("/")
        .then()
                .statusCode(403)
                .contentType(containsString("xml"))
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    @ParameterizedTest
    @CsvSource({"GET,Action,ListMetrics,2010-08-01", "GET,Operation,ListMetrics,2010-08-01",
            "PRESIGNED,Action,ListMetrics,2010-08-01", "PRESIGNED,Operation,ListMetrics,2010-08-01",
            "POST,Action,ListMetrics,2010-08-01", "JSON,Action,ListMetrics,2010-08-01",
            "GET,Action,DescribeAlarms,2013-01-16", "PRESIGNED,Action,DescribeAlarms,2013-01-16",
            "POST,Action,DescribeAlarms,2013-01-16"})
    void anAllowedSignedReadStillReachesCloudWatch(
            String protocol, String actionParameter, String action, String version) {
        String user = "query-allowed-" + UUID.randomUUID();
        String admin = auth("000000000000", "iam");
        String accessKey = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), admin, user).accessKeyId();
        given()
                .header("Authorization", admin)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PutUserPolicy")
                .formParam("Version", "2010-05-08")
                .formParam("UserName", user)
                .formParam("PolicyName", "list-metrics")
                .formParam("PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{
                          "Effect":"Allow","Action":"cloudwatch:%s","Resource":"*"
                        }]}
                        """.formatted(action))
        .when().post("/")
        .then().statusCode(200);

        RequestSpecification request = signedRequest(accessKey, "PRESIGNED".equals(protocol));
        Response response = switch (protocol) {
            case "JSON" -> request.contentType("application/x-amz-json-1.0")
                    .config(config().encoderConfig(EncoderConfig.encoderConfig()
                            .encodeContentTypeAs("application/x-amz-json-1.0", ContentType.JSON)))
                    .header("X-Amz-Target", "GraniteServiceVersion20100801.ListMetrics").body("{}").post("/");
            case "POST" -> request.contentType("application/x-www-form-urlencoded")
                    .formParam(actionParameter, action).formParam("Version", version).post("/");
            default -> request.queryParam(actionParameter, action)
                    .queryParam("Version", version).get("/");
        };
        response.then().statusCode(200);
        if ("JSON".equals(protocol)) {
            response.then().contentType(containsString("json"));
        } else {
            response.then().contentType(containsString("xml")).body(containsString(action + "Response"));
        }
    }

    private static RequestSpecification signedRequest(String accessKey, boolean presigned) {
        return presigned
                ? given().queryParam("X-Amz-Credential", accessKey + "/20261013/us-east-1/monitoring/aws4_request")
                : given().header("Authorization", auth(accessKey, "monitoring"));
    }
}
