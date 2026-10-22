package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class Ec2CapacityReservationMoveIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=111111111111/20261022/us-east-1/ec2/aws4_request,"
            + " SignedHeaders=host;x-amz-date, Signature=test";

    @Test
    void moveReturnsBothReservationsAndRetryDoesNotTransferAgain() {
        String source = create(3);
        String destination = create(1);
        String token = UUID.randomUUID().toString();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                given().header("Authorization", AUTH)
                        .formParam("Action", "MoveCapacityReservationInstances")
                        .formParam("SourceCapacityReservationId", source)
                        .formParam("DestinationCapacityReservationId", destination)
                        .formParam("InstanceCount", 2).formParam("ClientToken", token)
                        .post("/").then().statusCode(200)
                        .body("MoveCapacityReservationInstancesResponse.requestId", notNullValue())
                        .body("MoveCapacityReservationInstancesResponse.instanceCount", equalTo("2"))
                        .body("MoveCapacityReservationInstancesResponse.sourceCapacityReservation.state",
                                equalTo("active"))
                        .body("MoveCapacityReservationInstancesResponse.sourceCapacityReservation.totalInstanceCount",
                                equalTo("1"))
                        .body("MoveCapacityReservationInstancesResponse.destinationCapacityReservation.totalInstanceCount",
                                equalTo("3"));
            }
        } finally {
            cancel(source);
            cancel(destination);
        }
    }

    @Test
    void dryRunDoesNotChangeReservationCounts() {
        String source = create(2);
        String destination = create(1);
        try {
            given().header("Authorization", AUTH)
                    .formParam("Action", "MoveCapacityReservationInstances")
                    .formParam("SourceCapacityReservationId", source)
                    .formParam("DestinationCapacityReservationId", destination)
                    .formParam("InstanceCount", 1).formParam("DryRun", true)
                    .post("/").then().statusCode(412)
                    .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
            given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeCapacityReservations")
                    .formParam("CapacityReservationId.1", source)
                    .post("/").then().statusCode(200)
                    .body("DescribeCapacityReservationsResponse.capacityReservationSet.item.totalInstanceCount",
                            equalTo("2"));
        } finally {
            cancel(source);
            cancel(destination);
        }
    }

    @Test
    void missingCountReturnsQueryError() {
        given().header("Authorization", AUTH)
                .formParam("Action", "MoveCapacityReservationInstances")
                .formParam("SourceCapacityReservationId", "cr-source")
                .formParam("DestinationCapacityReservationId", "cr-destination")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    private String create(int count) {
        return given().header("Authorization", AUTH)
                .formParam("Action", "CreateCapacityReservation")
                .formParam("InstanceType", "t3.micro")
                .formParam("InstancePlatform", "Linux/UNIX")
                .formParam("AvailabilityZone", "us-east-1a")
                .formParam("InstanceCount", count)
                .post("/").then().statusCode(200).extract()
                .path("CreateCapacityReservationResponse.capacityReservation.capacityReservationId");
    }

    private void cancel(String reservationId) {
        given().header("Authorization", AUTH)
                .formParam("Action", "CancelCapacityReservation")
                .formParam("CapacityReservationId", reservationId)
                .post("/").then().statusCode(200);
    }
}
