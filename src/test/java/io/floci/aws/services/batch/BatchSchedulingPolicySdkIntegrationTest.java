package io.floci.aws.services.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.batch.BatchClient;
import software.amazon.awssdk.services.batch.model.BatchException;
import software.amazon.awssdk.services.batch.model.CreateSchedulingPolicyRequest;
import software.amazon.awssdk.services.batch.model.CreateSchedulingPolicyResponse;
import software.amazon.awssdk.services.batch.model.DescribeSchedulingPoliciesResponse;
import software.amazon.awssdk.services.batch.model.FairsharePolicy;
import software.amazon.awssdk.services.batch.model.ListSchedulingPoliciesResponse;
import software.amazon.awssdk.services.batch.model.QuotaSharePolicy;
import software.amazon.awssdk.services.batch.model.SchedulingPolicyDetail;
import software.amazon.awssdk.services.batch.model.SchedulingPolicyListingDetail;
import software.amazon.awssdk.services.batch.model.ShareAttributes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Timeout(120)
class BatchSchedulingPolicySdkIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<Scope> scopes = new ArrayList<>();
    private final List<Reservation> reservations = new ArrayList<>();
    private final List<Map<String, Object>> wire = new ArrayList<>();
    private Scope primary;
    private String marker;
    private Path archive;

    @BeforeEach
    void openClient() throws IOException {
        marker = "batch-sdk-" + UUID.randomUUID();
        archive = Path.of("target", "batch-sdk-receipts", marker + ".json");
        Files.createDirectories(archive.getParent());
        String account = Long.toString(100000000000L
                + Long.parseUnsignedLong(UUID.randomUUID().toString().replace("-", "").substring(0, 10), 16)
                % 800000000000L);
        primary = scope(account, "us-east-1");
        persist();
    }

    @AfterEach
    void removeOnlyOwnedPolicies() {
        List<Throwable> failures = new ArrayList<>();
        try {
            for (Reservation reservation : reservations) {
                if (reservation.deleted || reservation.refused) {
                    continue;
                }
                try {
                    List<SchedulingPolicyDetail> current = describe(reservation.scope, reservation.arn);
                    if (current.isEmpty()) {
                        reservation.deleted = true;
                        persist();
                    } else {
                        assertEquals(1, current.size());
                        assertOwned(reservation, current.getFirst());
                        delete(reservation);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
        } finally {
            scopes.forEach(scope -> scope.client.close());
            persist();
        }
        assertAll("Every attempted creation retains a bounded ownership or absence receipt",
                failures.stream().map(failure -> () -> {
                    throw failure;
                }));
    }

    @Test
    void fairshareLifecycleKeepsIndependentPoliciesAndRecreatesDeletedName() {
        Reservation a = create(primary, "a", fairshare(600, 25, 1, 2), null);
        Reservation b = create(primary, "b", fairshare(3600, 10, 2, 1), null);
        SchedulingPolicyDetail originalB = read(b);
        assertEquals(fairshare(600, 25, 1, 2), read(a).fairsharePolicy());
        assertEquals(fairshare(3600, 10, 2, 1), originalB.fairsharePolicy());
        assertEquals(a.tags, read(a).tags());
        assertEquals(Set.of(a.arn, b.arn), new HashSet<>(list(primary, 1)));

        assertEquals(200, primary.client.updateSchedulingPolicy(request -> request
                .arn(a.arn).fairsharePolicy(fairshare(7200, 2, 2, 1))).sdkHttpResponse().statusCode());
        assertEquals(fairshare(7200, 2, 2, 1), read(a).fairsharePolicy());
        assertEquals(a.tags, read(a).tags());
        assertEquals(originalB, read(b));
        delete(a);
        assertEquals(originalB, read(b));
        assertEquals(List.of(b.arn), list(primary, 1));

        Reservation replacement = create(primary, "a", fairshare(1800, 7, 1, 1), null);
        assertEquals(a.arn, replacement.arn);
        assertEquals(fairshare(1800, 7, 1, 1), read(replacement).fairsharePolicy());
        assertEquals(originalB, read(b));
        delete(replacement);
        delete(b);
        assertTrue(list(primary, 1).isEmpty());
    }

    @Test
    void omittedFairshareMembersRoundTripTheirDocumentedDefaults() {
        FairsharePolicy submitted = FairsharePolicy.builder()
                .shareDistribution(ShareAttributes.builder().shareIdentifier("TeamA").build()).build();
        Reservation policy = create(primary, "defaults", submitted, null);
        FairsharePolicy expected = FairsharePolicy.builder().shareDecaySeconds(0).computeReservation(0)
                .shareDistribution(ShareAttributes.builder().shareIdentifier("TeamA").weightFactor(1.0f).build())
                .build();
        assertEquals(expected, read(policy).fairsharePolicy());
        assertNull(read(policy).quotaSharePolicy());
        assertEquals(200, primary.client.updateSchedulingPolicy(request -> request.arn(policy.arn))
                .sdkHttpResponse().statusCode());
        assertEquals(expected, read(policy).fairsharePolicy());
    }

    @Test
    void quotaMetadataRoundTripsWithoutSwitchingThePolicyKind() {
        QuotaSharePolicy quota = QuotaSharePolicy.builder().idleResourceAssignmentStrategy("FIFO").build();
        Reservation policy = create(primary, "quota", null, quota);
        SchedulingPolicyDetail before = read(policy);
        assertEquals(quota, before.quotaSharePolicy());
        assertNull(before.fairsharePolicy());
        assertEquals(200, primary.client.updateSchedulingPolicy(request -> request.arn(policy.arn)
                .quotaSharePolicy(quota)).sdkHttpResponse().statusCode());
        assertEquals(before, read(policy));
        BatchException failure = assertThrows(BatchException.class, () -> primary.client
                .updateSchedulingPolicy(request -> request.arn(policy.arn)
                        .fairsharePolicy(fairshare(600, 25, 1, 2))));
        assertEquals(400, failure.statusCode());
        assertEquals("ClientException", failure.awsErrorDetails().errorCode());
        assertEquals(before, read(policy));
    }

    @Test
    void policyTagsShareTheDescribeViewAndDoNotChangeAnotherPolicy() {
        Reservation a = create(primary, "tags-a", fairshare(600, 25, 1, 2), null);
        Reservation b = create(primary, "tags-b", fairshare(3600, 10, 2, 1), null);
        SchedulingPolicyDetail originalB = read(b);
        assertEquals(a.tags, primary.client.listTagsForResource(request -> request.resourceArn(a.arn)).tags());
        assertEquals(200, primary.client.tagResource(request -> request.resourceArn(a.arn)
                .tags(Map.of("Purpose", "updated", "Empty", ""))).sdkHttpResponse().statusCode());
        Map<String, String> expected = new LinkedHashMap<>(a.tags);
        expected.put("Purpose", "updated");
        expected.put("Empty", "");
        assertEquals(expected, read(a).tags());
        assertEquals(expected, primary.client.listTagsForResource(request -> request.resourceArn(a.arn)).tags());
        assertEquals(200, primary.client.untagResource(request -> request.resourceArn(a.arn)
                .tagKeys("Purpose", "Empty")).sdkHttpResponse().statusCode());
        assertEquals(a.tags, read(a).tags());
        assertEquals(originalB, read(b));
        delete(a);
        assertEquals(originalB, read(b));
        assertEquals(b.tags, primary.client.listTagsForResource(request -> request.resourceArn(b.arn)).tags());
    }

    @Test
    void identicalNamesKeepIndependentAccountAndRegionPolicies() {
        Scope otherAccount = scope(Long.toString(Long.parseLong(primary.account) + 1), primary.region);
        Scope otherRegion = scope(primary.account, "eu-west-2");
        Reservation a = create(primary, "same", fairshare(600, 25, 1, 2), null);
        Reservation b = create(otherAccount, "same", fairshare(3600, 10, 2, 1), null);
        Reservation c = create(otherRegion, "same", fairshare(1800, 7, 1, 1), null);
        assertEquals(a.name, b.name);
        assertEquals(a.name, c.name);
        assertEquals(3, Set.of(a.arn, b.arn, c.arn).size());
        SchedulingPolicyDetail originalB = read(b);
        SchedulingPolicyDetail originalC = read(c);
        assertEquals(List.of(a.arn), list(primary, 1));
        assertEquals(List.of(b.arn), list(otherAccount, 1));
        assertEquals(List.of(c.arn), list(otherRegion, 1));
        delete(a);
        assertEquals(originalB, read(b));
        assertEquals(originalC, read(c));
        assertTrue(list(primary, 1).isEmpty());
    }

    @Test
    void paginatedPoliciesAppearExactlyOnceAndDescribeOmitsUnknownArns() {
        Reservation a = create(primary, "page-a", fairshare(600, 25, 1, 2), null);
        Reservation b = create(primary, "page-b", fairshare(3600, 10, 2, 1), null);
        Reservation c = create(primary, "page-c", fairshare(1800, 7, 1, 1), null);
        assertEquals(Set.of(a.arn, b.arn, c.arn), new HashSet<>(list(primary, 1)));
        assertEquals(Set.of(a.arn, b.arn, c.arn), new HashSet<>(list(primary, 2)));
        List<SchedulingPolicyDetail> described = describe(primary, c.arn, arn(primary, marker + "-missing"),
                a.arn, a.arn);
        assertEquals(2, described.size());
        assertEquals(Set.of(a.arn, c.arn), described.stream()
                .map(SchedulingPolicyDetail::arn).collect(Collectors.toSet()));
        assertTrue(describe(primary, arn(primary, marker + "-missing")).isEmpty());
    }

    @Test
    void emptyCollectionHasNoContinuationToken() {
        assertTrue(list(primary, 1).isEmpty());
        assertTrue(list(primary, 100).isEmpty());
    }

    @Test
    void validUnknownPolicyHasAnEmptyDescribeResult() {
        assertTrue(describe(primary, arn(primary, marker + "-unknown")).isEmpty());
    }

    private Reservation create(Scope scope, String suffix, FairsharePolicy fairshare, QuotaSharePolicy quota) {
        String name = marker + "-" + suffix;
        Reservation reservation = new Reservation(scope, name, arn(scope, name),
                Map.of("AuditOwner", marker, "AuditSlot", suffix));
        reservations.add(reservation);
        persist();
        CreateSchedulingPolicyRequest request = CreateSchedulingPolicyRequest.builder().name(name)
                .tags(reservation.tags).fairsharePolicy(fairshare).quotaSharePolicy(quota).build();
        try {
            reservation.response = scope.client.createSchedulingPolicy(request);
            persist();
        } catch (BatchException failure) {
            reservation.refused = failure.statusCode() >= 400 && failure.statusCode() < 500;
            reservation.refusalStatus = failure.statusCode();
            persist();
            throw failure;
        }
        assertEquals(200, reservation.response.sdkHttpResponse().statusCode());
        assertEquals(name, reservation.response.name());
        assertEquals(reservation.arn, reservation.response.arn());
        assertOwned(reservation, read(reservation));
        return reservation;
    }

    private SchedulingPolicyDetail read(Reservation reservation) {
        List<SchedulingPolicyDetail> rows = describe(reservation.scope, reservation.arn);
        assertEquals(1, rows.size());
        assertOwned(reservation, rows.getFirst());
        return rows.getFirst();
    }

    private List<SchedulingPolicyDetail> describe(Scope scope, String... arns) {
        DescribeSchedulingPoliciesResponse response = scope.client.describeSchedulingPolicies(request -> request
                .arns(arns));
        assertEquals(200, response.sdkHttpResponse().statusCode());
        return response.schedulingPolicies();
    }

    private void assertOwned(Reservation reservation, SchedulingPolicyDetail policy) {
        assertEquals(reservation.name, policy.name());
        assertEquals(reservation.arn, policy.arn());
        assertEquals(marker, policy.tags().get("AuditOwner"));
        assertEquals(reservation.tags.get("AuditSlot"), policy.tags().get("AuditSlot"));
    }

    private void delete(Reservation reservation) {
        read(reservation);
        assertEquals(200, reservation.scope.client.deleteSchedulingPolicy(request -> request.arn(reservation.arn))
                .sdkHttpResponse().statusCode());
        assertTrue(describe(reservation.scope, reservation.arn).isEmpty());
        reservation.deleted = true;
        persist();
    }

    private List<String> list(Scope scope, int size) {
        List<String> result = new ArrayList<>();
        Set<String> tokens = new HashSet<>();
        String token = null;
        for (int page = 0; page < 64; page++) {
            String suppliedToken = token;
            ListSchedulingPoliciesResponse response = scope.client.listSchedulingPolicies(request -> request
                    .maxResults(size).nextToken(suppliedToken));
            assertEquals(200, response.sdkHttpResponse().statusCode());
            assertTrue(response.schedulingPolicies().size() <= size);
            for (SchedulingPolicyListingDetail row : response.schedulingPolicies()) {
                assertNotNull(row.arn());
                assertFalse(result.contains(row.arn()), "Duplicate policy across complete pages");
                result.add(row.arn());
            }
            token = response.nextToken();
            if (token == null) {
                return result;
            }
            assertFalse(token.isBlank(), "EOF is an absent token");
            assertTrue(tokens.add(token), "Repeated continuation token");
        }
        throw new AssertionError("Fixture exhausted its 64-page budget before EOF");
    }

    private Scope scope(String account, String region) {
        BatchClient client = BatchClient.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15))
                        .apiCallAttemptTimeout(Duration.ofSeconds(12)).retryPolicy(RetryPolicy.none())
                        .addExecutionInterceptor(new WireReceipt()))
                .build();
        Scope scope = new Scope(client, account, region);
        scopes.add(scope);
        return scope;
    }

    private static String arn(Scope scope, String name) {
        return "arn:aws:batch:" + scope.region + ":" + scope.account + ":scheduling-policy/" + name;
    }

    private static FairsharePolicy fairshare(int decay, int reservation, float a, float b) {
        return FairsharePolicy.builder().shareDecaySeconds(decay).computeReservation(reservation)
                .shareDistribution(
                        ShareAttributes.builder().shareIdentifier("TeamA").weightFactor(a).build(),
                        ShareAttributes.builder().shareIdentifier("TeamB").weightFactor(b).build()).build();
    }

    private void persist() {
        List<Map<String, Object>> owners = new ArrayList<>();
        for (Reservation reservation : reservations) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", reservation.name);
            row.put("expectedArn", reservation.arn);
            row.put("tags", reservation.tags);
            row.put("account", reservation.scope.account);
            row.put("region", reservation.scope.region);
            row.put("deleted", reservation.deleted);
            row.put("refused", reservation.refused);
            row.put("uncertain", reservation.response == null && !reservation.refused && !reservation.deleted);
            row.put("refusalStatus", reservation.refusalStatus);
            row.put("returnedName", reservation.response == null ? null : reservation.response.name());
            row.put("returnedArn", reservation.response == null ? null : reservation.response.arn());
            owners.add(row);
        }
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(archive.toFile(),
                    Map.of("marker", marker, "reservations", owners, "wire", wire));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private record Scope(BatchClient client, String account, String region) {}

    private static final class Reservation {
        private final Scope scope;
        private final String name;
        private final String arn;
        private final Map<String, String> tags;
        private CreateSchedulingPolicyResponse response;
        private boolean refused;
        private int refusalStatus;
        private boolean deleted;

        private Reservation(Scope scope, String name, String arn, Map<String, String> tags) {
            this.scope = scope;
            this.name = name;
            this.arn = arn;
            this.tags = tags;
        }
    }

    private final class WireReceipt implements ExecutionInterceptor {
        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertTrue(wire.size() < 512, "Fixture exceeded its total wire-record budget");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "request");
            row.put("operation", context.request().getClass().getSimpleName());
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("headers", context.httpRequest().headers());
            context.requestBody().ifPresent(body -> {
                try (InputStream stream = body.contentStreamProvider().newStream()) {
                    byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
                    row.put("bodyBase64", Base64.getEncoder().encodeToString(bytes));
                    assertTrue(bytes.length <= 1024 * 1024, "Request exceeds capture bound");
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            wire.add(row);
            persist();
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "response-before-parser");
            row.put("operation", context.request().getClass().getSimpleName());
            row.put("status", context.httpResponse().statusCode());
            row.put("headers", context.httpResponse().headers());
            try (InputStream original = context.responseBody().orElse(InputStream.nullInputStream())) {
                byte[] bytes = original.readNBytes(1024 * 1024 + 1);
                row.put("bodyBase64", Base64.getEncoder().encodeToString(bytes));
                row.put("withinBound", bytes.length <= 1024 * 1024);
                wire.add(row);
                persist();
                assertTrue(bytes.length <= 1024 * 1024, "Response exceeds capture bound");
                return Optional.of(new ByteArrayInputStream(bytes));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
