package io.github.hectorvent.floci.services.backup;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * The injected {@link BackupService} with its own scheduler thread and the system clock, in real
 * time: one job started over HTTP has to finish by itself. Every timing rule is checked on a
 * controlled timeline in {@code BackupServiceTimingTest} and {@code BackupIntegrationTest}.
 */
@QuarkusTest
class BackupJobSchedulerIntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/backup/aws4_request";

    @Test
    void aJobStartedOverHttpIsCompletedByTheServicesOwnScheduler() {
        String vault = "scheduler-wiring-vault";
        given().header("Authorization", AUTH).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);
        String jobId = given().header("Authorization", AUTH).contentType("application/json")
            .body("""
                {"BackupVaultName": "%s", "ResourceArn": "arn:aws:dynamodb:us-east-1:000000000000:table/wiring",
                 "IamRoleArn": "arn:aws:iam::000000000000:role/backup-role"}
                """.formatted(vault))
        .when().put("/backup-jobs").then().statusCode(200).extract().path("BackupJobId");

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(25))
            .until(() -> "COMPLETED".equals(given().header("Authorization", AUTH)
                .when().get("/backup-jobs/" + jobId).then().statusCode(200).extract().path("State")));

        String point = given().header("Authorization", AUTH)
        .when().get("/backup-jobs/" + jobId).then().statusCode(200)
            .body("PercentDone", equalTo("100.0"))
            .body("RecoveryPointArn", containsString("recovery-point:"))
            .extract().path("RecoveryPointArn");
        given().header("Authorization", AUTH)
        .when().get("/backup-vaults/" + vault + "/recovery-points/").then().statusCode(200)
            .body("RecoveryPoints", hasSize(1))
            .body("RecoveryPoints[0].RecoveryPointArn", equalTo(point));

        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + vault + "/recovery-points/" + point).then().statusCode(204);
        given().header("Authorization", AUTH)
        .when().delete("/backup-vaults/" + vault).then().statusCode(204);
    }
}
