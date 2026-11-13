package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2MetadataVersionTest {

    private Ec2MetadataTestFixture fixture;

    @BeforeEach
    void startServer() throws Exception {
        fixture = new Ec2MetadataTestFixture();
        fixture.instance().setRegion("eu-west-1");
        fixture.instance().setInstanceType("t3.small");
        fixture.instance().setPlacement(new Placement("eu-west-1a"));
        fixture.instance().setUserData("original user data\n");
        fixture.instance().setTags(List.of(new Tag("owner/team", "analytics")));
        fixture.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        fixture.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "2007-01-19", "2016-09-02", "2026-04-15", "latest"})
    void supportedVersionsReturnTheSameInstanceWithEitherTokenMode(String version) throws Exception {
        String path = "/" + version + "/meta-data/instance-id";
        for (String token : new String[]{null, fixture.putToken("60").body()}) {
            HttpResponse<String> response = fixture.get(path, token);
            assertEquals(200, response.statusCode());
            assertEquals(fixture.instance().getInstanceId(), response.body());
            assertEquals("original user data\n", fixture.get("/" + version + "/user-data", token).body());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "2007-01-19, meta-data/instance-type, 404",
            "2007-08-29, meta-data/instance-type, 200",
            "2007-12-15, meta-data/placement/availability-zone, 404",
            "2008-02-01, meta-data/placement/availability-zone, 200",
            "2008-09-01, dynamic/instance-identity/document, 404",
            "2009-04-04, dynamic/instance-identity/document, 200",
            "2012-01-12, meta-data/services/domain, 404",
            "2014-02-25, meta-data/services/domain, 200",
            "2014-02-25, meta-data/services/partition, 404",
            "2015-10-20, meta-data/services/partition, 200",
            "2018-09-24, meta-data/placement/region, 404",
            "2019-10-01, meta-data/placement/region, 200",
            "2021-01-03, meta-data/tags/instance/owner%2Fteam, 404",
            "2021-03-23, meta-data/tags/instance/owner%2Fteam, 200",
            "2016-09-03, meta-data/instance-id, 404",
            "2099-01-01, meta-data/instance-id, 404",
            "unknown, meta-data/instance-id, 404"
    })
    void categoryAvailabilityDependsOnAnExistingVersion(String version, String path, int expected) throws Exception {
        assertEquals(expected, fixture.get("/" + version + "/" + path, fixture.putToken("60").body()).statusCode());
    }

    @Test
    void olderServiceDirectoryDoesNotAdvertisePartition() throws Exception {
        assertEquals("domain", fixture.get("/2014-02-25/meta-data/services/", null).body());
        assertEquals("domain\npartition", fixture.get("/2015-10-20/meta-data/services", null).body());
        assertEquals("analytics", fixture.get("/2021-03-23/meta-data/tags/instance/owner%2Fteam", null).body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "2016-09-02"})
    void identityDocumentHasSeparateFieldLinesAndEscapedValues(String version) throws Exception {
        fixture.instance().setImageId("ami-\"example\"");
        HttpResponse<String> response = fixture.get("/" + version + "/dynamic/instance-identity/document", null);
        assertEquals(200, response.statusCode());
        JsonObject body = new JsonObject(response.body());
        assertEquals("eu-west-1", body.getString("region"));
        assertEquals("ami-\"example\"", body.getString("imageId"));
        assertTrue(response.body().lines().anyMatch(line ->
                line.matches("\\s*\"region\"\\s*:\\s*\"eu-west-1\"\\s*,?\\s*")));
    }

    @Test
    void datedRequestsKeepTokenRequirementsExpiryAndInstanceOwnership() throws Exception {
        LaunchTemplateData.MetadataOptions options = LaunchTemplateData.MetadataOptions.launchDefaults();
        options.setHttpTokens("required");
        fixture.instance().setMetadataOptions(options);
        String path = "/2016-09-02/meta-data/instance-id";
        assertEquals(401, fixture.get(path, null).statusCode());
        assertEquals(401, fixture.get("/2016-09-03/meta-data/instance-id", null).statusCode());
        String token = fixture.putToken("60").body();
        assertEquals(200, fixture.get(path, token).statusCode());
        assertEquals(401, fixture.get(path, "unknown-token").statusCode());
        fixture.clock().advance(Duration.ofSeconds(60));
        assertEquals(401, fixture.get(path, token).statusCode());
        String fresh = fixture.putToken("60").body();
        Instance other = Ec2MetadataTestFixture.instance("i-0fedcba9876543210");
        fixture.server().registerContainer(Ec2MetadataTestFixture.CALLER_IP, other.getInstanceId(), other);
        assertEquals(401, fixture.get(path, fresh).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2016-09-02", "2016-09-03"})
    void tokenIssuanceRequiresLatestEvenWhenTheDatedVersionExists(String version) throws Exception {
        assertEquals(403, fixture.send("PUT", "/" + version + "/api/token", null).statusCode());
    }

    @Test
    void disabledEndpointRefusesDatedMetadataAndTokenRequests() throws Exception {
        String token = fixture.putToken("60").body();
        LaunchTemplateData.MetadataOptions options = LaunchTemplateData.MetadataOptions.launchDefaults();
        options.setHttpEndpoint("disabled");
        fixture.instance().setMetadataOptions(options);
        assertEquals(404, fixture.get("/2016-09-02/meta-data/instance-id", token).statusCode());
        assertEquals(404, fixture.send("PUT", "/2016-09-02/api/token", null).statusCode());
    }
}
