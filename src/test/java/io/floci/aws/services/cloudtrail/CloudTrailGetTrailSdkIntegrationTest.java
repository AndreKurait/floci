package io.floci.aws.services.cloudtrail;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.CloudTrailException;
import software.amazon.awssdk.services.cloudtrail.model.Trail;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudTrailGetTrailSdkIntegrationTest {

    private static final String OWNER = "661100000018";
    private static final String OTHER = "661100000019";

    @ParameterizedTest
    @CsvSource({"us-east-1,us-west-2,aws", "us-gov-west-1,us-gov-east-1,aws-us-gov"})
    void sdkReadsSettingsByNameAndArnWithinTheOwningAccount(String home, String away, String partition) {
        String name = "sdk-read-" + UUID.randomUUID();
        try (CloudTrailClient owner = client(OWNER, home);
                CloudTrailClient regional = client(OWNER, away);
                CloudTrailClient other = client(OTHER, home)) {
            owner.createTrail(request -> request.name(name).s3BucketName("gettrail-sdk-logs")
                    .s3KeyPrefix("first").isMultiRegionTrail(true).includeGlobalServiceEvents(true)
                    .enableLogFileValidation(true));
            try {
                Trail trail = owner.getTrail(request -> request.name(name)).trail();
                assertNotNull(trail);
                assertEquals(name, trail.name());
                assertEquals(home, trail.homeRegion());
                assertEquals("gettrail-sdk-logs", trail.s3BucketName());
                assertEquals("first", trail.s3KeyPrefix());
                assertTrue(trail.isMultiRegionTrail());
                assertTrue(trail.includeGlobalServiceEvents());
                assertTrue(trail.logFileValidationEnabled());
                String arn = "arn:" + partition + ":cloudtrail:" + home + ":" + OWNER + ":trail/" + name;
                assertEquals(arn, trail.trailARN());
                assertEquals(trail, owner.getTrail(request -> request.name(arn)).trail());
                assertEquals(trail, regional.getTrail(request -> request.name(arn)).trail());

                assertNotFound(() -> other.getTrail(request -> request.name(arn)));
                assertNotFound(() -> regional.getTrail(request -> request.name(name)));
                owner.updateTrail(request -> request.name(name).s3KeyPrefix("updated"));
                assertEquals("updated", owner.getTrail(request -> request.name(name)).trail().s3KeyPrefix());
            } finally {
                owner.deleteTrail(request -> request.name(name));
            }
            assertNotFound(() -> owner.getTrail(request -> request.name(name)));
        }
    }

    @ParameterizedTest
    @CsvSource({"ab,InvalidTrailNameException", "arn:malformed,CloudTrailARNInvalidException",
        "missing-sdk-trail,TrailNotFoundException"})
    void sdkReceivesModeledLookupErrors(String name, String code) {
        try (CloudTrailClient cloudTrail = client(OWNER, "us-east-1")) {
            CloudTrailException failure = assertThrows(CloudTrailException.class,
                    () -> cloudTrail.getTrail(request -> request.name(name)));
            assertEquals(400, failure.statusCode());
            assertEquals(code, failure.awsErrorDetails().errorCode());
        }
    }

    private static void assertNotFound(Executable request) {
        CloudTrailException failure = assertThrows(CloudTrailException.class, request);
        assertEquals(400, failure.statusCode());
        assertEquals("TrailNotFoundException", failure.awsErrorDetails().errorCode());
    }

    private static CloudTrailClient client(String account, String region) {
        return AwsTestClients.create(CloudTrailClient.builder(), account, Region.of(region));
    }
}
