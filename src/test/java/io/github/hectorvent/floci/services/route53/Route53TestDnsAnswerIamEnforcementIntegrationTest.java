package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Route53Exception;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Route53TestDnsAnswerIamEnforcementIntegrationTest {
    private static final String ACCOUNT = "741686000053";

    @TestHTTPResource("/")
    URI baseUri;

    private final PartitionCleanup CLEANUP = new PartitionCleanup();
    @RegisterExtension
    final AfterAllCallback classCleanup = CLEANUP::afterEach;
    private String zoneId;
    private String zoneName;
    private String allowKey;
    private String denyKey;
    private String implicitDenyKey;

    @BeforeAll
    void createFixtures() {
        Route53Client root = client(ACCOUNT);
        CLEANUP.register(root::close);
        zoneName = "test-answer-" + UUID.randomUUID() + ".example.";
        zoneId = root.createHostedZone(r -> r.name(zoneName).callerReference(UUID.randomUUID().toString()))
                .hostedZone().id();
        CLEANUP.register(() -> root.deleteHostedZone(r -> r.id(zoneId)));
        allowKey = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"route53:TestDNSAnswer","Resource":"*"}]}
                """);
        denyKey = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"route53:*","Resource":"*"},
                {"Effect":"Deny","Action":"route53:TestDNSAnswer","Resource":"*"}]}
                """);
        implicitDenyKey = userWithPolicy(null);
    }

    @Test
    void actionAllowGrantsAccessAndBothKindsOfDenyApply() {
        try (Route53Client allowed = client(allowKey)) {
            assertEquals("NOERROR", allowed.testDNSAnswer(r -> r.hostedZoneId(zoneId)
                    .recordName(zoneName).recordType("NS")).responseCode());
        }
        for (String key : new String[]{denyKey, implicitDenyKey}) {
            try (Route53Client denied = client(key)) {
                Route53Exception error = assertThrows(Route53Exception.class, () ->
                        denied.testDNSAnswer(r -> r.hostedZoneId(zoneId).recordName(zoneName).recordType("NS")));
                assertEquals(403, error.statusCode());
                assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/2013-04-01/testdnsanswer", "/2013-04-01/%74estdnsanswer",
            "/2013-04-01/testdnsanswer;ignored=1"})
    void anOperationHeaderOrEncodedRouteCannotBypassTheDeny(String path) {
        given().urlEncodingEnabled(false).header("Authorization", auth(denyKey, "route53"))
                .header("X-Amz-Target", "Route53.ListHostedZones").queryParam("Action", "ListHostedZones")
                .queryParam("hostedzoneid", zoneId).queryParam("recordname", zoneName).queryParam("recordtype", "NS")
                .get(path).then().statusCode(403).body(containsString("<Code>AccessDenied</Code>"));
    }

    private String userWithPolicy(String policy) {
        String user = "r53-answer-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(CLEANUP, baseUri.getPort(), auth(ACCOUNT, "iam"), user).accessKeyId();
        if (policy != null) {
            EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), user, "answer", policy);
        }
        return key;
    }

    private Route53Client client(String key) {
        return AwsTestClients.create(Route53Client.builder(), key, Region.US_EAST_1, baseUri);
    }
}
