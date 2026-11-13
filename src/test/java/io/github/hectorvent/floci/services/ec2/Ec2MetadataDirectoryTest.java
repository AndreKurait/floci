package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2MetadataDirectoryTest {
    private Ec2MetadataTestFixture fixture;

    @BeforeEach
    void startServer() throws Exception {
        fixture = new Ec2MetadataTestFixture();
        fixture.start();
        fixture.instance().setImageId("ami-0123456789abcdef0");
        fixture.instance().setRegion("eu-west-1");
        fixture.instance().setPlacement(new Placement("eu-west-1a"));
        fixture.instance().setPrivateIpAddress("10.0.1.7");
        fixture.instance().setPrivateDnsName("ip-10-0-1-7.internal");
    }

    @AfterEach
    void stopServer() throws Exception {
        fixture.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "2016-09-02"})
    void discoversInstanceAndIdentityThroughDirectoryReads(String build) throws Exception {
        String token = fixture.putToken("60").body();
        Set<String> root = listing("/" + build + "/meta-data/", token);
        assertTrue(root.containsAll(Set.of("instance-id", "ami-id", "ami-launch-index", "placement/")));
        assertEquals("0", fixture.get("/" + build + "/meta-data/ami-launch-index", token).body());
        Set<String> placement = "latest".equals(build)
                ? Set.of("availability-zone", "region") : Set.of("availability-zone");
        assertEquals(placement, listing("/" + build + "/meta-data/placement/", token));
        assertEquals(Set.of("instance-identity/"), listing("/" + build + "/dynamic/", token));
        assertEquals(Set.of("document"), listing("/" + build + "/dynamic/instance-identity/", token));
        assertEquals(200, fixture.get("/" + build + "/dynamic/instance-identity/document", token).statusCode());
    }

    @Test
    void omitsUnavailableOptionalCategoriesAndUnimplementedSignatures() throws Exception {
        String token = fixture.putToken("60").body();
        Set<String> root = listing("/latest/meta-data/", token);
        for (String absent : Set.of("public-ipv4", "public-hostname", "public-keys/", "iam/", "tags/")) {
            assertFalse(root.contains(absent), absent);
        }
        fixture.instance().setPublicIpAddress("203.0.113.9");
        assertTrue(listing("/latest/meta-data/", token).contains("public-ipv4"));
        assertEquals(Set.of("document"), listing("/latest/dynamic/instance-identity/", token));
        assertEquals(404, fixture.get("/latest/meta-data/public-keys/", token).statusCode());
        assertEquals(404, fixture.get("/latest/meta-data/not-a-category/", token).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "2016-09-02"})
    void directoryAliasesRetainNativeCloudInitDiscoveryAndTokenRequirements(String build) throws Exception {
        LaunchTemplateData.MetadataOptions options = LaunchTemplateData.MetadataOptions.launchDefaults();
        options.setHttpTokens("required");
        fixture.instance().setMetadataOptions(options);
        String token = fixture.putToken("60").body();
        for (String path : List.of("/dynamic/instance-identity", "/dynamic", "/meta-data", "/meta-data/placement")) {
            String url = "/" + build + path;
            assertEquals(401, fixture.get(url, null).statusCode());
            HttpResponse<String> response = fixture.get(url, token);
            assertEquals(200, response.statusCode(), url);
            assertEquals(fixture.get(url + "/", token).body(), response.body());
            assertTrue(response.headers().firstValue("location").isEmpty(), "native clients need no redirect");
        }
        assertEquals(404, fixture.get("/" + build + "/meta-data/no-such-directory", token).statusCode());
    }

    @Test
    void directoryListingHonorsTheRequestedBuild() throws Exception {
        String token = fixture.putToken("60").body();
        Set<String> old = listing("/2007-01-19/meta-data/", token);
        assertFalse(old.contains("placement/"));
        assertFalse(old.contains("identity-credentials/"));
        assertEquals(404, fixture.get("/2007-01-19/dynamic/", token).statusCode());
        assertTrue(listing("/2008-02-01/meta-data/", token).contains("placement/"));
        assertEquals(Set.of("instance-identity/"), listing("/2009-04-04/dynamic/", token));
        assertEquals(404, fixture.get("/2099-01-01/meta-data/", token).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "2021-03-23"})
    void discoversEnabledTagsAsACollection(String build) throws Exception {
        LaunchTemplateData.MetadataOptions options = LaunchTemplateData.MetadataOptions.launchDefaults();
        options.setInstanceMetadataTags("enabled");
        fixture.instance().setMetadataOptions(options);
        fixture.instance().setTags(List.of(new Tag("Deployment", "native")));
        String token = fixture.putToken("60").body();
        String root = "/" + build + "/meta-data/";

        assertTrue(listing(root, token).contains("tags/"));
        assertEquals(Set.of("instance/"), listing(root + "tags/", token));
        assertEquals("Deployment", fixture.get(root + "tags/instance/", token).body());
        assertEquals("native", fixture.get(root + "tags/instance/Deployment", token).body());
        assertFalse(listing("/2016-09-02/meta-data/", token).contains("tags/"));

        options.setInstanceMetadataTags("disabled");
        assertFalse(listing(root, token).contains("tags/"));
        assertEquals(404, fixture.get(root + "tags/", token).statusCode());
    }

    @Test
    void directoryReadsKeepTokenExpiryAndMetadataDisablement() throws Exception {
        LaunchTemplateData.MetadataOptions options = LaunchTemplateData.MetadataOptions.launchDefaults();
        options.setHttpTokens("required");
        fixture.instance().setMetadataOptions(options);
        String path = "/2016-09-02/meta-data/";
        assertEquals(401, fixture.get(path, null).statusCode());
        assertEquals(401, fixture.get(path, "invalid").statusCode());
        String token = fixture.putToken("60").body();
        assertTrue(listing(path, token).contains("instance-id"));
        fixture.clock().advance(Duration.ofSeconds(60));
        assertEquals(401, fixture.get(path, token).statusCode());
        options.setHttpEndpoint("disabled");
        assertEquals(404, fixture.get(path, null).statusCode());
    }

    private Set<String> listing(String path, String token) throws Exception {
        HttpResponse<String> response = fixture.get(path, token);
        assertEquals(200, response.statusCode(), path);
        assertEquals("text/plain", response.headers().firstValue("content-type").orElseThrow());
        assertTrue(response.body().endsWith("\n"));
        return response.body().lines().collect(Collectors.toSet());
    }
}
