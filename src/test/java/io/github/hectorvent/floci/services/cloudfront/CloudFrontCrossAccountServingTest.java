package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.KeyGroup;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.cloudfront.model.PublicKey;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(CloudFrontCrossAccountServingTest.GlobalBucketProfile.class)
class CloudFrontCrossAccountServingTest {
    private static final String OWNER = "111122223333";
    private static final String VIEWER = "444455556666";
    private static final String PATH = "/2/core/repository/repodata/repomd.xml";

    @Inject
    CloudFrontService cloudFront;
    @Inject
    S3Service s3;
    @Inject
    RegionResolver regionResolver;
    @Inject
    IamService iam;

    public static class GlobalBucketProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.enforce-auth", "true",
                    "floci.services.s3.global-bucket-namespace", "true");
        }
    }

    @Test
    void anonymousViewerReadsAnotherAccountsOriginWithAndWithoutQuery() {
        Distribution distribution = distribution(true);
        for (String query : List.of("", "?instance_id=i-local&region=eu-west-1")) {
            given().header("Host", distribution.getDomainName()).get(PATH + query)
                    .then().statusCode(200).body(equalTo("repository-metadata"));
        }
        given().header("Host", distribution.getId() + ".cloudfront.localhost")
                .get(PATH).then().statusCode(200).body(equalTo("repository-metadata"));
        assertThrows(AwsException.class, () -> cloudFront.getDistribution(distribution.getId()));
        RequestScopes.runAs(VIEWER, () ->
                assertThrows(AwsException.class, () -> cloudFront.getDistribution(distribution.getId())));
        RequestScopes.runAs(OWNER, () ->
                assertEquals(distribution.getId(), cloudFront.getDistribution(distribution.getId()).getId()));
    }

    @Test
    void unsignedViewerCannotReadPrivateOriginOrBypassHostRouting() {
        Distribution distribution = distribution(false);
        given().header("Host", distribution.getDomainName()).get(PATH).then().statusCode(403);
        given().get("/_cloudfront/" + distribution.getId() + PATH).then().statusCode(404);
    }

    @Test
    void distributionArnNamesItsActualOwner() {
        Distribution distribution = distribution(true);
        assertEquals("arn:aws:cloudfront::" + OWNER + ":distribution/" + distribution.getId(),
                distribution.getArn());
    }

    @Test
    void configurationReadsRestoreTheViewerAndUseStoredOwnership() {
        Distribution distribution = distribution(true);
        distribution.setArn("arn:aws:cloudfront::000000000000:distribution/" + distribution.getId());
        RequestScopes.runAs(VIEWER, () -> {
            assertEquals(OWNER, cloudFront.readViewerConfiguration(distribution, regionResolver::getAccountId));
            assertEquals(VIEWER, regionResolver.getAccountId());
            assertThrows(IllegalStateException.class, () -> cloudFront.readViewerConfiguration(distribution, () -> {
                throw new IllegalStateException("configuration read failed");
            }));
            assertEquals(VIEWER, regionResolver.getAccountId());
        });
    }

    @Test
    void resolvingTheOwnersOacDoesNotTurnViewerCredentialsIntoOwnerCredentials() {
        Distribution distribution = RequestScopes.callAs(OWNER, () -> {
            OriginAccessControl oac = new OriginAccessControl();
            oac.setName("owner-" + UUID.randomUUID());
            oac.setSigningBehavior("no-override");
            oac.setSigningProtocol("sigv4");
            oac.setOriginAccessControlOriginType("s3");
            String id = cloudFront.createOriginAccessControl(oac).getId();
            Distribution created = distribution(false);
            created.getConfig().getOrigins().getFirst().setOriginAccessControlId(id);
            return cloudFront.updateDistribution(created.getId(), created.getEtag(), created);
        });
        for (String viewer : List.of(issuedAccessKey(VIEWER), "test", "unknown")) {
            given().header("Host", distribution.getDomainName())
                    .header("Authorization", authorization(viewer))
                    .get(PATH).then().statusCode(403);
        }
        given().header("Host", distribution.getDomainName())
                .header("Authorization", authorization(issuedAccessKey(OWNER)))
                .get(PATH).then().statusCode(200).body(equalTo("repository-metadata"));
    }

    @Test
    void ambiguousLegacyAliasesCannotSelectAnArbitraryAccount() {
        Distribution first = distribution(true);
        Distribution second = distribution(true, VIEWER);
        String host = UUID.randomUUID() + ".example.test";
        // These conflicting stored claims model legacy data from before cross-account validation.
        first.getConfig().setAliases(List.of(host));
        second.getConfig().setAliases(List.of(host));
        assertNull(cloudFront.findByHost(host));
        given().header("Host", host).get(PATH).then().statusCode(404);
        String bucket = "cf-fallthrough-" + UUID.randomUUID();
        s3.createBucket(bucket, "us-east-1");
        s3.putObject(bucket, "object.txt", "unrelated-public-object".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());
        s3.deletePublicAccessBlock(bucket);
        s3.putBucketPolicy(bucket, """
                {"Version":"2012-10-17","Statement":{"Effect":"Allow","Principal":"*",
                "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}}
                """.formatted(bucket));
        given().header("Host", host).get("/" + bucket + "/object.txt").then().statusCode(404);
        second.getConfig().setAliases(List.of());
        assertEquals(first.getId(), cloudFront.findByHost(host).getId());
        first.getConfig().setEnabled(false);
        given().header("Host", host).get(PATH).then().statusCode(404);
    }

    @Test
    void appliesTheOwnersResponseHeadersPolicy() {
        Distribution distribution = RequestScopes.callAs(OWNER, () -> {
            ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
            policy.setName("owner-" + UUID.randomUUID());
            policy.setConfig(Map.of("CustomHeadersConfig", List.of(Map.of(
                    "Header", "X-Distribution-Owner", "Value", OWNER, "Override", "true"))));
            String policyId = cloudFront.createResponseHeadersPolicy(policy).getId();
            Distribution created = distribution(true);
            created.getConfig().getDefaultCacheBehavior().setResponseHeadersPolicyId(policyId);
            return cloudFront.updateDistribution(created.getId(), created.getEtag(), created);
        });
        given().header("Host", distribution.getDomainName()).get(PATH).then()
                .statusCode(200).header("X-Distribution-Owner", OWNER);
    }

    @Test
    void verifiesSignedUrlsUsingTheOwnersKeyGroup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        PublicKey key = RequestScopes.callAs(OWNER, () -> {
            PublicKey value = new PublicKey();
            value.setName("owner-" + UUID.randomUUID());
            value.setCallerReference(UUID.randomUUID().toString());
            value.setEncodedKey("-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----");
            return cloudFront.createPublicKey(value);
        });
        Distribution distribution = RequestScopes.callAs(OWNER, () -> {
            KeyGroup group = new KeyGroup();
            group.setName("owner-" + UUID.randomUUID());
            group.setItems(List.of(key.getId()));
            String groupId = cloudFront.createKeyGroup(group).getId();
            Distribution created = distribution(true);
            created.getConfig().getDefaultCacheBehavior().setTrustedKeyGroups(List.of(groupId));
            return cloudFront.updateDistribution(created.getId(), created.getEtag(), created);
        });
        given().header("Host", distribution.getDomainName()).get(PATH).then().statusCode(403);
        String policy = """
                {"Statement":[{"Resource":"http://%s%s","Condition":{
                "DateLessThan":{"AWS:EpochTime":%d}}}]}
                """.formatted(distribution.getDomainName(), PATH, Instant.now().getEpochSecond() + 300);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(policy.getBytes(StandardCharsets.UTF_8));
        given().header("Host", distribution.getDomainName())
                .queryParam("Policy", encoded(policy.getBytes(StandardCharsets.UTF_8)))
                .queryParam("Signature", encoded(signer.sign()))
                .queryParam("Key-Pair-Id", key.getId())
                .queryParam("Hash-Algorithm", "SHA256")
                .get(PATH).then().statusCode(200).body(equalTo("repository-metadata"));
    }

    private Distribution distribution(boolean publicRead) {
        return distribution(publicRead, OWNER);
    }

    private Distribution distribution(boolean publicRead, String account) {
        return RequestScopes.callAs(account, "eu-west-1", () -> {
            String bucket = "cf-owner-" + UUID.randomUUID();
            s3.createBucket(bucket, "eu-west-1");
            s3.putObject(bucket, PATH.substring(1), "repository-metadata".getBytes(StandardCharsets.UTF_8),
                    "application/xml", Map.of());
            if (publicRead) {
                s3.deletePublicAccessBlock(bucket);
                s3.putBucketPolicy(bucket, """
                        {"Version":"2012-10-17","Statement":{"Effect":"Allow","Principal":"*",
                        "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}}
                        """.formatted(bucket));
            }
            Origin origin = new Origin();
            origin.setId("repository");
            origin.setDomainName(bucket + ".s3.eu-west-1.amazonaws.com");
            origin.setS3OriginConfig(Map.of("OriginAccessIdentity", ""));
            DefaultCacheBehavior behavior = new DefaultCacheBehavior();
            behavior.setTargetOriginId(origin.getId());
            behavior.setViewerProtocolPolicy("allow-all");
            DistributionConfig config = new DistributionConfig();
            config.setEnabled(true);
            config.setOrigins(List.of(origin));
            config.setDefaultCacheBehavior(behavior);
            Distribution distribution = new Distribution();
            distribution.setConfig(config);
            return cloudFront.createDistribution(distribution, Map.of());
        });
    }

    private static String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value)
                .replace('+', '-').replace('=', '_').replace('/', '~');
    }

    private static String authorization(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20261106/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc123";
    }

    private String issuedAccessKey(String account) {
        return RequestScopes.callAs(account, () -> {
            String user = "viewer-" + UUID.randomUUID();
            iam.createUser(user, "/");
            iam.putUserPolicy(user, "ReadObjects", """
                    {"Version":"2012-10-17","Statement":{"Effect":"Allow",
                    "Action":"s3:GetObject","Resource":"*"}}
                    """);
            return iam.createAccessKey(user).getAccessKeyId();
        });
    }
}
