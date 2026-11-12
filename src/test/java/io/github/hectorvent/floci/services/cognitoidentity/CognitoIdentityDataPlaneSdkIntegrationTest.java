package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentity.CognitoIdentityClient;
import software.amazon.awssdk.services.cognitoidentity.model.CognitoIdentityException;
import software.amazon.awssdk.services.cognitoidentity.model.GetOpenIdTokenForDeveloperIdentityResponse;
import software.amazon.awssdk.services.cognitoidentity.model.ListIdentitiesResponse;
import software.amazon.awssdk.services.cognitoidentity.model.LookupDeveloperIdentityResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CognitoIdentityDataPlaneSdkIntegrationTest {
    private static final String PROVIDER = "login.example.test";
    @TestHTTPResource URI endpoint;

    @Test
    void developerProviderCannotBeChangedOrRemovedThroughPoolUpdate() {
        try (CognitoIdentityClient admin = client(Region.US_EAST_1, false)) {
            String pool = pool(admin);
            try {
                String identity = admin.getOpenIdTokenForDeveloperIdentity(r -> r.identityPoolId(pool)
                        .logins(Map.of(PROVIDER, "alice"))).identityId();
                for (String replacement : new String[]{"login.other.test", null}) {
                    CognitoIdentityException error = assertThrows(CognitoIdentityException.class,
                            () -> admin.updateIdentityPool(r -> r.identityPoolId(pool)
                                    .identityPoolName("rejected update").allowUnauthenticatedIdentities(true)
                                    .developerProviderName(replacement)));
                    assertEquals("InvalidParameterException", error.awsErrorDetails().errorCode());
                    assertEquals(400, error.statusCode());
                    assertEquals(PROVIDER, admin.describeIdentityPool(r -> r.identityPoolId(pool))
                            .developerProviderName());
                    assertEquals(List.of(PROVIDER), admin.describeIdentity(r -> r.identityId(identity)).logins());
                    assertEquals(identity, admin.lookupDeveloperIdentity(r -> r.identityPoolId(pool)
                            .developerUserIdentifier("alice").maxResults(1)).identityId());
                }
                admin.updateIdentityPool(r -> r.identityPoolId(pool).identityPoolName("accepted update")
                        .allowUnauthenticatedIdentities(true).developerProviderName(PROVIDER));
                assertEquals("accepted update", admin.describeIdentityPool(r -> r.identityPoolId(pool))
                        .identityPoolName());
            } finally {
                admin.deleteIdentityPool(r -> r.identityPoolId(pool));
            }
        }
    }

    @Test
    void nativeGuestAndDeveloperLifecyclesUseRealRecordsPaginationAndLastLinkDeactivation() throws Exception {
        try (CognitoIdentityClient admin = client(Region.US_EAST_1, false);
             CognitoIdentityClient publicClient = client(Region.US_EAST_1, true)) {
            String pool = pool(admin);
            try {
                String guest = publicClient.getId(r -> r.identityPoolId(pool)).identityId();
                String other = publicClient.getId(r -> r.identityPoolId(pool)).identityId();
                assertNotEquals(guest, other);
                assertNotNull(admin.describeIdentity(r -> r.identityId(guest)).creationDate());
                assertTrue(admin.describeIdentity(r -> r.identityId(guest)).logins().isEmpty());
                String guestToken = publicClient.getOpenIdToken(r -> r.identityId(guest)).token();
                assertEquals("RS512", decode(guestToken, 0).path("alg").asText());
                assertEquals("unauthenticated", decode(guestToken, 1).path("amr").get(0).asText());
                ListIdentitiesResponse page = admin.listIdentities(r -> r.identityPoolId(pool).maxResults(1));
                assertEquals(1, page.identities().size());
                assertNotNull(page.nextToken());
                ListIdentitiesResponse next = admin.listIdentities(r -> r.identityPoolId(pool).maxResults(1)
                        .nextToken(page.nextToken()));
                assertEquals(Set.of(guest, other), new HashSet<>(List.of(
                        page.identities().getFirst().identityId(), next.identities().getFirst().identityId())));
                GetOpenIdTokenForDeveloperIdentityResponse developer = admin.getOpenIdTokenForDeveloperIdentity(r ->
                        r.identityPoolId(pool).identityId(guest).logins(Map.of(PROVIDER, "alice")).tokenDuration(1200L));
                assertEquals(guest, developer.identityId());
                assertEquals(1200, decode(developer.token(), 1).path("exp").asLong()
                        - decode(developer.token(), 1).path("iat").asLong());
                admin.getOpenIdTokenForDeveloperIdentity(r -> r.identityPoolId(pool).identityId(guest)
                        .logins(Map.of(PROVIDER, "bob")));
                LookupDeveloperIdentityResponse lookup = admin.lookupDeveloperIdentity(r -> r.identityPoolId(pool)
                        .developerUserIdentifier("alice").maxResults(1));
                assertEquals(guest, lookup.identityId());
                assertEquals(List.of("alice"), lookup.developerUserIdentifierList());
                assertEquals(List.of("bob"), admin.lookupDeveloperIdentity(r -> r.identityPoolId(pool)
                        .identityId(guest).maxResults(1).nextToken(lookup.nextToken())).developerUserIdentifierList());
                assertThrows(CognitoIdentityException.class, () -> publicClient.getOpenIdToken(r -> r.identityId(guest)));
                admin.unlinkDeveloperIdentity(r -> r.identityPoolId(pool).identityId(guest)
                        .developerProviderName(PROVIDER).developerUserIdentifier("alice"));
                assertEquals(List.of(PROVIDER), admin.describeIdentity(r -> r.identityId(guest)).logins());
                admin.unlinkDeveloperIdentity(r -> r.identityPoolId(pool).identityId(guest)
                        .developerProviderName(PROVIDER).developerUserIdentifier("bob"));
                assertThrows(CognitoIdentityException.class, () -> publicClient.getOpenIdToken(r -> r.identityId(guest)));
                String issuer = decode(developer.token(), 1).path("iss").asText();
                assertThrows(CognitoIdentityException.class, () -> publicClient.getCredentialsForIdentity(r ->
                        r.identityId(guest).logins(Map.of(issuer, developer.token()))));
                assertFalse(admin.listIdentities(r -> r.identityPoolId(pool).maxResults(60).hideDisabled(true))
                        .identities().stream().anyMatch(value -> value.identityId().equals(guest)));
                assertTrue(admin.deleteIdentities(r -> r.identityIdsToDelete(guest, other)).unprocessedIdentityIds().isEmpty());
                assertThrows(CognitoIdentityException.class, () -> admin.describeIdentity(r -> r.identityId(guest)));
                assertTrue(admin.listIdentities(r -> r.identityPoolId(pool).maxResults(60)).identities().isEmpty());
            } finally {
                admin.deleteIdentityPool(r -> r.identityPoolId(pool));
            }
        }
    }

    @Test
    void signedRegionAndAccountAreCheckedWhileAnonymousRegionalIdsResolveTheirOwner() {
        try (CognitoIdentityClient east = client(Region.US_EAST_1, false);
             CognitoIdentityClient west = client(Region.US_WEST_2, false);
             CognitoIdentityClient publicClient = client(Region.US_EAST_1, true)) {
            String pool = pool(west);
            try {
                String id = publicClient.getId(r -> r.identityPoolId(pool)).identityId();
                assertTrue(id.startsWith("us-west-2:"));
                assertThrows(CognitoIdentityException.class, () -> east.describeIdentity(r -> r.identityId(id)));
                assertEquals(id, west.describeIdentity(r -> r.identityId(id)).identityId());
                assertThrows(CognitoIdentityException.class, () -> publicClient.getId(r -> r.identityPoolId(pool)
                        .accountId("999999999999")));
                assertTrue(east.getId(r -> r.identityPoolId(pool)).identityId().startsWith("us-west-2:"),
                        "SDK GetId is unsigned even with a static credentials provider");
            } finally {
                west.deleteIdentityPool(r -> r.identityPoolId(pool));
            }
        }
    }

    @Test
    void recognizableUnsignedIdentityJwtIsRefusedByStsEvenWithoutIamEnforcement() throws Exception {
        try (CognitoIdentityClient admin = client(Region.US_EAST_1, false);
             StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(AnonymousCredentialsProvider.create()).build()) {
            String pool = pool(admin);
            try {
                String id = admin.getId(r -> r.identityPoolId(pool)).identityId();
                String token = admin.getOpenIdToken(r -> r.identityId(id)).token();
                String[] parts = token.split("\\.");
                String unsigned = parts[0] + "." + parts[1] + ".";
                StsException error = assertThrows(StsException.class, () -> sts.assumeRoleWithWebIdentity(r ->
                        r.roleArn("arn:aws:iam::000000000000:role/absent").roleSessionName("strict-token")
                                .webIdentityToken(unsigned)));
                assertEquals("InvalidIdentityToken", error.awsErrorDetails().errorCode());
            } finally {
                admin.deleteIdentityPool(r -> r.identityPoolId(pool));
            }
        }
    }

    private String pool(CognitoIdentityClient client) {
        return client.createIdentityPool(r -> r.identityPoolName("sdk-" + UUID.randomUUID())
                .allowUnauthenticatedIdentities(true).allowClassicFlow(true).developerProviderName(PROVIDER)).identityPoolId();
    }

    private CognitoIdentityClient client(Region region, boolean anonymous) {
        return CognitoIdentityClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(anonymous ? AnonymousCredentialsProvider.create()
                        : StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }

    private JsonNode decode(String token, int part) throws Exception {
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[part]));
    }
}
