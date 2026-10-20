package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@QuarkusTest
@TestProfile(S3GlobalLegacyListingIntegrationTest.GlobalNamespaceProfile.class)
class S3GlobalLegacyListingIntegrationTest {

    public static class GlobalNamespaceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.global-bucket-namespace", "true");
        }
    }

    @Inject
    RequestContext requestContext;

    @Test
    void bothListingVersionsReadTheOwnerAndRecordTheLegacyCaller() {
        String owner = "AWS 000000000001:local-signature";
        String caller = "AWS 000000000002:local-signature";
        String bucket = "global-legacy-listing-" + UUID.randomUUID();
        AtomicReference<String> eventAccount = new AtomicReference<>();
        AtomicReference<String> eventKey = new AtomicReference<>();
        CloudTrailService trail = mock(CloudTrailService.class);
        doAnswer(invocation -> {
            CloudTrailService.S3EventInput input = invocation.getArgument(0);
            eventAccount.set(requestContext.getAccountId());
            eventKey.set(input.accessKeyId());
            return null;
        }).when(trail).emitS3DataEvent(any());
        QuarkusMock.installMockForType(trail, CloudTrailService.class);
        given().header("Authorization", owner).put("/" + bucket).then().statusCode(200);
        try {
            given().header("Authorization", owner).body("owner-data").put("/" + bucket + "/object.txt")
                    .then().statusCode(200);
            given().header("Authorization", caller).put("/" + bucket)
                    .then().statusCode(409).body(containsString("BucketAlreadyExists"));
            for (String query : new String[]{"", "?list-type=2"}) {
                given().header("Authorization", caller).get("/" + bucket + query)
                        .then().statusCode(200).body(containsString("<Key>object.txt</Key>"));
                assertEquals("000000000002", eventAccount.get());
                assertEquals("000000000002", eventKey.get());
            }
        } finally {
            given().header("Authorization", owner).delete("/" + bucket + "/object.txt");
            given().header("Authorization", owner).delete("/" + bucket);
        }
    }
}
