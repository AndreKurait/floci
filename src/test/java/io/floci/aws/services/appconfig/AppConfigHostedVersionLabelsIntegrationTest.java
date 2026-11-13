package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.CreateHostedConfigurationVersionRequest;
import software.amazon.awssdk.services.appconfig.model.CreateHostedConfigurationVersionResponse;
import software.amazon.awssdk.services.appconfig.model.GetApplicationResponse;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationProfileResponse;
import software.amazon.awssdk.services.appconfig.model.GetHostedConfigurationVersionResponse;
import software.amazon.awssdk.services.appconfig.model.HostedConfigurationVersionSummary;
import software.amazon.awssdk.services.appconfig.model.ListApplicationsRequest;
import software.amazon.awssdk.services.appconfig.model.ListHostedConfigurationVersionsResponse;
import software.amazon.awssdk.services.appconfig.model.ResourceNotFoundException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(60)
class AppConfigHostedVersionLabelsIntegrationTest {
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-appconfig-hosted-labels-wire-id");
    private final String marker = UUID.randomUUID().toString();
    private final WireReceipt wire = new WireReceipt();
    private final List<String> profiles = new ArrayList<>();
    private AppConfigClient client;
    private String application;
    private String profile;
    private String independent;

    @BeforeEach
    void createIsolatedHostedProfiles() {
        String account = Long.toString(ThreadLocalRandom.current().nextLong(760000000000L, 769999999999L));
        client = AwsTestClients.create(AppConfigClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        assertEquals(List.of(), client.listApplications(ListApplicationsRequest.builder().build()).items());
        application = client.createApplication(request -> request.name("hosted-labels-" + marker)
                .description(marker)).id();
        profile = newProfile("primary");
        independent = newProfile("independent");
    }

    private String newProfile(String suffix) {
        String id = client.createConfigurationProfile(request -> request.applicationId(application)
                .name(marker + "-" + suffix).description(marker).locationUri("hosted").type("AWS.Freeform")).id();
        profiles.add(id);
        return id;
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "release-a", "123x"})
    void labelsRoundTripWithExactContentAndIndependentProfileScope(String label) {
        ResponseBytes<CreateHostedConfigurationVersionResponse> first = create(profile, label, 0, "first");
        ResponseBytes<CreateHostedConfigurationVersionResponse> other = create(independent, label, 0, "other");
        ResponseBytes<GetHostedConfigurationVersionResponse> fetched = get(profile, first.response().versionNumber());
        List<HostedConfigurationVersionSummary> summaries = versions(profile, null, 50);
        assertAll(
                () -> assertEquals(label, first.response().versionLabel()),
                () -> assertEquals(label, other.response().versionLabel()),
                () -> assertEquals(label, fetched.response().versionLabel()),
                () -> assertEquals(label, summaries.getFirst().versionLabel()),
                () -> assertEquals(Base64.getEncoder().encodeToString(contentBytes("first")),
                        Base64.getEncoder().encodeToString(first.asByteArray())),
                () -> assertEquals(Base64.getEncoder().encodeToString(contentBytes("first")),
                        Base64.getEncoder().encodeToString(fetched.asByteArray())),
                () -> assertEquals(application, fetched.response().applicationId()),
                () -> assertEquals(profile, fetched.response().configurationProfileId()),
                () -> assertEquals(1, first.response().versionNumber()),
                () -> assertEquals(1, other.response().versionNumber()));
    }

    @Test
    void maximumLengthLabelIsRetained() {
        String label = "v".repeat(64);
        ResponseBytes<CreateHostedConfigurationVersionResponse> created = create(profile, label, null, "maximum");
        assertEquals(label, created.response().versionLabel());
        assertEquals(label, get(profile, created.response().versionNumber()).response().versionLabel());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456", "too-long", "duplicate"})
    void invalidAndDuplicateLabelsRefuseWithoutChangingAnyStoredConfiguration(String kind) {
        create(profile, "release-a", null, "original");
        create(independent, "unrelated", null, "unrelated");
        String label = switch (kind) {
            case "too-long" -> "v".repeat(65);
            case "duplicate" -> "release-a";
            default -> kind;
        };
        Snapshot before = snapshot();
        Outcome outcome = attempt(profile, label, null, "refused");
        Snapshot after = snapshot();
        assertAll(() -> assertEquals(new Outcome(400, "BadRequestException"), outcome),
                () -> assertEquals(before, after));
    }

    @Test
    void currentVersionTokenAcceptsAndStaleOrFutureTokensRefuseWithoutConsumingNumbers() {
        ResponseBytes<CreateHostedConfigurationVersionResponse> first = create(profile, null, 0, "first");
        ResponseBytes<CreateHostedConfigurationVersionResponse> second = create(profile, null, 1, "second");
        List<Executable> assertions = new ArrayList<>();
        assertions.add(() -> assertEquals(1, first.response().versionNumber()));
        assertions.add(() -> assertEquals(2, second.response().versionNumber()));
        for (int token : List.of(1, 102)) {
            Snapshot before = snapshot();
            Outcome outcome = attempt(profile, "refused-" + token, token, "candidate");
            Snapshot after = snapshot();
            assertions.add(() -> assertEquals(new Outcome(409, "ConflictException"), outcome));
            assertions.add(() -> assertEquals(before, after));
        }
        ResponseBytes<CreateHostedConfigurationVersionResponse> next = create(profile, null, null, "third");
        assertions.add(() -> assertEquals(3, next.response().versionNumber()));
        assertAll(assertions);
    }

    @Test
    void versionTokenUsesTheHighestRemainingVersionWhileAllocationRetainsItsHighWaterMark() {
        create(profile, null, null, "first");
        create(profile, null, null, "second");
        delete(profile, 2);
        Snapshot before = snapshot();
        Outcome deletedToken = attempt(profile, null, 2, "deleted-token");
        Snapshot after = snapshot();
        Outcome remainingToken;
        Integer allocated = null;
        try {
            ResponseBytes<CreateHostedConfigurationVersionResponse> response = create(profile, null, 1, "remaining-token");
            remainingToken = new Outcome(response.response().sdkHttpResponse().statusCode(), null);
            allocated = response.response().versionNumber();
        } catch (AppConfigException error) {
            remainingToken = new Outcome(error.statusCode(), error.awsErrorDetails().errorCode());
        }
        Outcome actual = remainingToken;
        Integer number = allocated;
        assertAll(() -> assertEquals(new Outcome(409, "ConflictException"), deletedToken),
                () -> assertEquals(before, after),
                () -> assertEquals(new Outcome(201, null), actual),
                () -> assertEquals(3, number));
    }

    @Test
    void ordinaryCreationNeverReusesADeletedVersionNumber() {
        create(profile, null, null, "first");
        create(profile, null, null, "second");
        delete(profile, 2);
        ResponseBytes<CreateHostedConfigurationVersionResponse> next = create(profile, null, null, "third");
        assertEquals(3, next.response().versionNumber());
        assertEquals(List.of(1, 3), versions(profile, null, 50).stream()
                .map(HostedConfigurationVersionSummary::versionNumber).toList());
    }

    @Test
    void labelsAreCaseSensitiveAndMayBeReusedAfterTheirVersionIsDeleted() {
        create(profile, "release-a", null, "first");
        ResponseBytes<CreateHostedConfigurationVersionResponse> distinct = create(profile, "Release-A", null, "distinct");
        delete(profile, 1);
        ResponseBytes<CreateHostedConfigurationVersionResponse> reused = create(profile, "release-a", null, "reused");
        List<HostedConfigurationVersionSummary> lower = versions(profile, "release-a", 1);
        List<HostedConfigurationVersionSummary> upper = versions(profile, "Release-A", 1);
        assertAll(() -> assertEquals("Release-A", distinct.response().versionLabel()),
                () -> assertEquals("release-a", reused.response().versionLabel()),
                () -> assertEquals(List.of(reused.response().versionNumber()),
                        lower.stream().map(HostedConfigurationVersionSummary::versionNumber).toList()),
                () -> assertEquals(List.of(distinct.response().versionNumber()),
                        upper.stream().map(HostedConfigurationVersionSummary::versionNumber).toList()));
    }

    @Test
    void exactAndPrefixFiltersPaginateWithoutUnlabeledOrCaseMismatchedEntries() {
        create(profile, "release-a", null, "lower-one");
        create(profile, "release-b", null, "lower-two");
        create(profile, "Release-A", null, "upper");
        create(profile, null, null, "unlabeled");
        Snapshot before = snapshot();
        Map<String, List<Integer>> actual = new LinkedHashMap<>();
        for (String filter : List.of("release-a", "release-*", "Release-*", "release", "missing", "*")) {
            actual.put(filter, versions(profile, filter, 1).stream()
                    .map(HostedConfigurationVersionSummary::versionNumber).sorted().toList());
        }
        Snapshot after = snapshot();
        assertAll(() -> assertEquals(List.of(1), actual.get("release-a")),
                () -> assertEquals(List.of(1, 2), actual.get("release-*")),
                () -> assertEquals(List.of(3), actual.get("Release-*")),
                () -> assertEquals(List.of(), actual.get("release")),
                () -> assertEquals(List.of(), actual.get("missing")),
                () -> assertEquals(List.of(1, 2, 3), actual.get("*")),
                () -> assertEquals(before, after));
    }

    private ResponseBytes<CreateHostedConfigurationVersionResponse> create(
            String selectedProfile, String label, Integer latest, String content) {
        CreateHostedConfigurationVersionRequest.Builder request = CreateHostedConfigurationVersionRequest.builder()
                .applicationId(application).configurationProfileId(selectedProfile)
                .description(marker + ":" + content).contentType("application/octet-stream");
        if (label != null) {
            request.versionLabel(label);
        }
        if (latest != null) {
            request.latestVersionNumber(latest);
        }
        CreateHostedConfigurationVersionResponse response = client.createHostedConfigurationVersion(
                request.content(SdkBytes.fromByteArray(contentBytes(content))).build());
        return ResponseBytes.fromByteArray(response, response.content().asByteArray());
    }

    private static byte[] contentBytes(String content) {
        return ("\u0000" + content + "\u00ff").getBytes(StandardCharsets.UTF_8);
    }

    private Outcome attempt(String selectedProfile, String label, Integer latest, String content) {
        try {
            return new Outcome(create(selectedProfile, label, latest, content).response().sdkHttpResponse().statusCode(), null);
        } catch (AppConfigException error) {
            return new Outcome(error.statusCode(), error.awsErrorDetails().errorCode());
        }
    }

    private ResponseBytes<GetHostedConfigurationVersionResponse> get(String selectedProfile, int number) {
        GetHostedConfigurationVersionResponse response = client.getHostedConfigurationVersion(request ->
                request.applicationId(application).configurationProfileId(selectedProfile).versionNumber(number));
        return ResponseBytes.fromByteArray(response, response.content().asByteArray());
    }

    private List<HostedConfigurationVersionSummary> versions(String selectedProfile, String filter, int size) {
        List<HostedConfigurationVersionSummary> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String token = null;
        for (int page = 0; page < 20; page++) {
            String cursor = token;
            ListHostedConfigurationVersionsResponse response = client.listHostedConfigurationVersions(request ->
                    request.applicationId(application).configurationProfileId(selectedProfile)
                            .versionLabel(filter).maxResults(size).nextToken(cursor));
            assertTrue(response.items().size() <= size, "Page must respect MaxResults");
            values.addAll(response.items());
            token = response.nextToken();
            if (token == null) {
                return values.stream().sorted(Comparator.comparingInt(HostedConfigurationVersionSummary::versionNumber)).toList();
            }
            assertTrue(seen.add(token), "Page token must advance");
        }
        throw new AssertionError("Hosted version page budget exceeded");
    }

    private Snapshot snapshot() {
        GetApplicationResponse app = client.getApplication(request -> request.applicationId(application));
        // Stored-state comparisons exclude per-request transport metadata, retaining every modeled field.
        GetApplicationResponse.Builder applicationState = app.toBuilder();
        applicationState.sdkHttpResponse(null);
        applicationState.responseMetadata(null);
        GetApplicationResponse modeledApplication = applicationState.build();
        assertTrue(app.equalsBySdkFields(modeledApplication));
        Map<String, ProfileState> state = new LinkedHashMap<>();
        for (String selected : profiles) {
            GetConfigurationProfileResponse metadata = client.getConfigurationProfile(request ->
                    request.applicationId(application).configurationProfileId(selected));
            List<HostedConfigurationVersionSummary> summaries = versions(selected, null, 50);
            List<StoredVersion> full = new ArrayList<>();
            for (HostedConfigurationVersionSummary summary : summaries) {
                ResponseBytes<GetHostedConfigurationVersionResponse> bytes = get(selected, summary.versionNumber());
                GetHostedConfigurationVersionResponse version = bytes.response();
                full.add(new StoredVersion(version.applicationId(), version.configurationProfileId(),
                        version.versionNumber(), version.description(), version.contentType(), version.versionLabel(),
                        version.kmsKeyArn(), Base64.getEncoder().encodeToString(bytes.asByteArray())));
            }
            GetConfigurationProfileResponse.Builder profileState = metadata.toBuilder();
            profileState.sdkHttpResponse(null);
            profileState.responseMetadata(null);
            GetConfigurationProfileResponse modeledProfile = profileState.build();
            assertTrue(metadata.equalsBySdkFields(modeledProfile));
            state.put(selected, new ProfileState(modeledProfile, summaries, full));
        }
        return new Snapshot(modeledApplication, state);
    }

    private void delete(String selectedProfile, int number) {
        assertEquals(204, client.deleteHostedConfigurationVersion(request -> request.applicationId(application)
                .configurationProfileId(selectedProfile).versionNumber(number)).sdkHttpResponse().statusCode());
        assertThrows(ResourceNotFoundException.class, () -> get(selectedProfile, number));
    }

    @AfterEach
    void removeCapturedOwnedHierarchy() {
        List<Executable> failures = new ArrayList<>();
        try {
            if (client != null && application != null) {
                for (String selected : profiles) {
                    cleanupAttempt(failures, () -> {
                        for (HostedConfigurationVersionSummary version : versions(selected, null, 50)) {
                            cleanupAttempt(failures, () -> {
                                assertTrue(version.description().startsWith(marker + ":"));
                                delete(selected, version.versionNumber());
                            });
                        }
                        assertEquals(List.of(), versions(selected, null, 50));
                        client.deleteConfigurationProfile(request -> request.applicationId(application)
                                .configurationProfileId(selected));
                        assertThrows(ResourceNotFoundException.class, () -> client.getConfigurationProfile(request ->
                                request.applicationId(application).configurationProfileId(selected)));
                    });
                }
                cleanupAttempt(failures, () -> {
                    assertEquals(List.of(), client.listConfigurationProfiles(request -> request.applicationId(application)).items());
                    client.deleteApplication(request -> request.applicationId(application));
                    assertThrows(ResourceNotFoundException.class,
                            () -> client.getApplication(request -> request.applicationId(application)));
                    assertEquals(List.of(), client.listApplications(ListApplicationsRequest.builder().build()).items());
                });
            }
        } finally {
            try {
                cleanupAttempt(failures, wire::save);
            } finally {
                if (client != null) {
                    cleanupAttempt(failures, client::close);
                }
            }
        }
        assertAll("Owned cleanup and capture", failures);
    }

    private static void cleanupAttempt(List<Executable> failures, Executable action) {
        try {
            action.execute();
        } catch (Throwable failure) {
            failures.add(() -> {
                throw failure;
            });
        }
    }

    private record Outcome(int status, String code) {}
    private record StoredVersion(String application, String profile, int version, String description,
                                 String contentType, String label, String kmsKeyArn, String base64) {}
    private record ProfileState(GetConfigurationProfileResponse metadata,
                                List<HostedConfigurationVersionSummary> summaries, List<StoredVersion> full) {}
    private record Snapshot(GetApplicationResponse application, Map<String, ProfileState> profiles) {}

    private final class WireReceipt implements ExecutionInterceptor {
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            int id = calls.size();
            attributes.putAttribute(WIRE_ID, id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", id);
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            Map<String, String> headers = new LinkedHashMap<>();
            for (String header : List.of("content-type", "description", "latest-version-number", "versionlabel")) {
                context.httpRequest().firstMatchingHeader(header).ifPresent(value -> headers.put(header, value));
            }
            row.put("requestHeaders", headers);
            try (InputStream stream = context.requestBody().isPresent()
                    ? context.requestBody().orElseThrow().contentStreamProvider().newStream() : InputStream.nullInputStream()) {
                row.put("requestBase64", encode(stream));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            calls.add(row);
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(
                Context.ModifyHttpResponse context, ExecutionAttributes attributes) {
            Map<String, Object> row = calls.get(attributes.getAttribute(WIRE_ID));
            row.put("status", context.httpResponse().statusCode());
            row.put("responseHeaders", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElseGet(InputStream::nullInputStream)) {
                String encoded = encode(stream);
                row.put("responseBase64", encoded);
                return Optional.of(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        private String encode(InputStream stream) throws IOException {
            byte[] bytes = stream.readNBytes(262145);
            assertTrue(bytes.length <= 262144, "Wire capture byte limit");
            return Base64.getEncoder().encodeToString(bytes);
        }

        private void save() throws IOException {
            Path directory = Path.of(System.getProperty("audit.appconfig.hosted-labels.directory", "target/hosted-labels-wire"));
            Files.createDirectories(directory);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                    directory.resolve(marker + ".json").toFile(), Map.of("marker", marker, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")));
        }
    }
}
