package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Ec2MetadataServiceIdentityTest {

    @ParameterizedTest
    @CsvSource({
            "eu-west-1, aws, amazonaws.com",
            "us-gov-west-1, aws-us-gov, amazonaws.com",
            "cn-north-1, aws-cn, amazonaws.com.cn",
            "us-iso-east-1, aws-iso, c2s.ic.gov",
            "us-isob-east-1, aws-iso-b, sc2s.sgov.gov",
            "eu-isoe-west-1, aws-iso-e, cloud.adc-e.uk",
            "us-isof-south-1, aws-iso-f, csp.hci.ic.gov",
            "eusc-de-east-1, aws-eusc, amazonaws.eu"
    })
    void serviceIdentityUsesTheInstanceRegionWithEitherImdsVersion(
            String region, String partition, String domain) throws Exception {
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.instance().setRegion(region);
            fixture.start();
            HttpResponse<String> issued = fixture.putToken("60");
            assertEquals(200, issued.statusCode());
            for (String token : new String[]{null, issued.body()}) {
                assertMetadata(fixture, "services/partition", token, partition);
                assertMetadata(fixture, "services/domain", token, domain);
                assertMetadata(fixture, "services", token, "domain\npartition");
                assertMetadata(fixture, "services/", token, "domain\npartition");
            }
        }
    }

    @Test
    void aTokenCannotReadAnotherInstancesServiceIdentity() throws Exception {
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.instance().setRegion("eu-west-1");
            fixture.start();
            String firstToken = fixture.putToken("60").body();
            assertMetadata(fixture, "services/partition", firstToken, "aws");

            Instance other = Ec2MetadataTestFixture.instance("i-0fedcba9876543210");
            other.setRegion("cn-north-1");
            fixture.server().registerContainer(Ec2MetadataTestFixture.CALLER_IP, other.getInstanceId(), other);
            for (String path : new String[]{"services", "services/", "services/partition", "services/domain"}) {
                HttpResponse<String> refused = fixture.get("/latest/meta-data/" + path, firstToken);
                assertEquals(401, refused.statusCode());
                assertEquals("", refused.body());
            }
            String secondToken = fixture.putToken("60").body();
            assertMetadata(fixture, "services/partition", secondToken, "aws-cn");
            assertMetadata(fixture, "services/domain", secondToken, "amazonaws.com.cn");
        }
    }

    private static void assertMetadata(Ec2MetadataTestFixture fixture, String path,
                                       String token, String expected) throws Exception {
        HttpResponse<String> response = fixture.get("/latest/meta-data/" + path, token);
        assertEquals(200, response.statusCode(), path);
        assertEquals(expected, response.body(), path);
    }
}
