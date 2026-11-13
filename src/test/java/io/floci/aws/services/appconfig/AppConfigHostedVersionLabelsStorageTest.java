package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.appconfig.model.HostedConfigurationVersion;
import io.floci.aws.services.appconfig.model.HostedConfigurationVersionSummary;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class AppConfigHostedVersionLabelsStorageTest {
    private static final String ACCOUNT = "000000000000";
    private static final byte[] CONTENT = new byte[]{0, 1, -1, 42};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void labelsAndAllocationSurviveRestartAndAnEmptyVersionCollection(String mode) {
        Selection selection;
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(first);
            selection = selection(service);
            create(service, selection, "reusable", 0);
            create(service, selection, "retained", 1);
            service.deleteHostedConfigurationVersion(selection.app(), selection.profile(), 1);
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(second);
            HostedConfigurationVersion retained = service.getHostedConfigurationVersion(
                    selection.app(), selection.profile(), 2);
            assertEquals("retained", retained.getVersionLabel());
            assertArrayEquals(CONTENT, retained.getContent());
            assertEquals(3, create(service, selection, "reusable", 2).getVersionNumber());
            service.deleteHostedConfigurationVersion(selection.app(), selection.profile(), 2);
            service.deleteHostedConfigurationVersion(selection.app(), selection.profile(), 3);
            assertEquals(List.of(), service.listHostedConfigurationVersions(selection.app(), selection.profile()));
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(third);
            assertEquals(4, create(service, selection, "retained", 0).getVersionNumber());
            remove(service, selection);
            assertEquals(List.of(), service.listApplications());
        } finally {
            third.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "label", "ordinary"})
    void concurrentRequestsCannotOverwriteVersionsOrBypassAdmission(String mode) throws Exception {
        StorageFactory factory = StorageFactories.open(directory, "memory", ACCOUNT);
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            AppConfigService service = service(factory);
            Selection selection = selection(service);
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int index = i;
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        String label = "label".equals(mode) ? "shared" : "request-" + index;
                        Integer token = "token".equals(mode) ? 0 : null;
                        return "created:" + create(service, selection, label, token).getVersionNumber();
                    } catch (AwsException error) {
                        return error.getErrorCode();
                    }
                }));
            }
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
            int accepted = "ordinary".equals(mode) ? 8 : 1;
            assertEquals(accepted, outcomes.stream().filter(value -> value.startsWith("created:")).count());
            if (accepted == 1) {
                String code = "token".equals(mode) ? "ConflictException" : "BadRequestException";
                assertEquals(7, outcomes.stream().filter(code::equals).count());
                assertTrue(outcomes.contains("created:1"));
            }
            List<HostedConfigurationVersionSummary> stored =
                    service.listHostedConfigurationVersions(selection.app(), selection.profile());
            assertEquals(accepted, stored.size());
            assertEquals(accepted, stored.stream().map(HostedConfigurationVersionSummary::getVersionNumber).distinct().count());
            assertEquals(accepted + 1, create(service, selection, "after", null).getVersionNumber());
            remove(service, selection);
        } finally {
            factory.shutdownAll();
        }
    }

    @Test
    void paginationTokensRemainBoundToTheirFilterAndProfile() {
        StorageFactory factory = StorageFactories.open(directory, "memory", ACCOUNT);
        try {
            AppConfigService service = service(factory);
            Selection primary = populated(service);
            Selection other = populated(service);
            String token = service.listHostedConfigurationVersionsPage(
                    primary.app(), primary.profile(), 1, null, "release-*").nextToken();
            assertTrue(token != null && !token.isEmpty());
            for (String filter : new String[]{null, "*", "release-a", "Release-*"}) {
                assertBadRequest(() -> service.listHostedConfigurationVersionsPage(
                        primary.app(), primary.profile(), 1, token, filter));
            }
            assertBadRequest(() -> service.listHostedConfigurationVersionsPage(
                    other.app(), other.profile(), 1, token, "release-*"));
            PaginatedResult<HostedConfigurationVersionSummary> next = service.listHostedConfigurationVersionsPage(
                    primary.app(), primary.profile(), 1, token, "release-*");
            assertEquals(List.of(2), next.items().stream().map(HostedConfigurationVersionSummary::getVersionNumber).toList());
            assertNull(next.nextToken());
            remove(service, primary);
            remove(service, other);
        } finally {
            factory.shutdownAll();
        }
    }

    @Test
    void refusedShapesAndTokensLeaveAllVersionsAndTheNextAllocationUnchanged() throws Exception {
        StorageFactory factory = StorageFactories.open(directory, "memory", ACCOUNT);
        try {
            AppConfigService service = service(factory);
            Selection selection = populated(service);
            String before = fullState(service, selection);
            for (String label : List.of("", "123", "x".repeat(65))) {
                assertBadRequest(() -> create(service, selection, label, null));
            }
            for (String filter : List.of("", "x".repeat(65))) {
                assertBadRequest(() -> service.listHostedConfigurationVersionsPage(
                        selection.app(), selection.profile(), 1, null, filter));
            }
            for (int size : List.of(0, -1, 51)) {
                assertBadRequest(() -> service.listHostedConfigurationVersionsPage(
                        selection.app(), selection.profile(), size, null, null));
            }
            for (String token : List.of("", "not-a-token", "x".repeat(2049))) {
                assertBadRequest(() -> service.listHostedConfigurationVersionsPage(
                        selection.app(), selection.profile(), 1, token, null));
            }
            assertEquals(List.of(), service.listHostedConfigurationVersionsPage(
                    selection.app(), selection.profile(), 1, null, "123").items());
            assertEquals(before, fullState(service, selection));
            assertEquals(3, create(service, selection, "next", 2).getVersionNumber());
            remove(service, selection);
        } finally {
            factory.shutdownAll();
        }
    }

    @Test
    void legacyVersionsWithoutLabelsOrCountersKeepBytesAndAllocateAboveTheirMaximum() throws Exception {
        Files.writeString(directory.resolve("appconfig-applications.json"), """
                {"000000000000/app": {"Id": "app", "Name": "legacy"}}
                """);
        Files.writeString(directory.resolve("appconfig-profiles.json"), """
                {"000000000000/profile": {"Id": "profile", "ApplicationId": "app",
                  "Name": "legacy", "LocationUri": "hosted", "Type": "AWS.Freeform"}}
                """);
        Files.writeString(directory.resolve("appconfig-versions.json"), """
                {"000000000000/app::profile::7": {"ApplicationId": "app", "ConfigurationProfileId": "profile",
                  "VersionNumber": 7, "Content": "bGVnYWN5", "ContentType": "text/plain"}}
                """);
        StorageFactory factory = StorageFactories.open(directory, "persistent", ACCOUNT);
        try {
            AppConfigService service = service(factory);
            Selection selection = new Selection("app", "profile");
            HostedConfigurationVersion legacy = service.getHostedConfigurationVersion("app", "profile", 7);
            assertArrayEquals("legacy".getBytes(StandardCharsets.UTF_8), legacy.getContent());
            assertNull(legacy.getVersionLabel());
            assertFalse(MAPPER.valueToTree(legacy).has("VersionLabel"));
            assertFalse(MAPPER.valueToTree(service.listHostedConfigurationVersions("app", "profile").getFirst())
                    .has("VersionLabel"));
            assertEquals(8, create(service, selection, "new", 7).getVersionNumber());
            remove(service, selection);
        } finally {
            factory.shutdownAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void deletingLegacyVersionsBeforeAnyCreationRetainsTheirNumbersAcrossRestart(String mode) throws Exception {
        String suffix = "wal".equals(mode) ? "-snapshot.json" : ".json";
        Files.writeString(directory.resolve("appconfig-applications" + suffix), """
                {"000000000000/app": {"Id": "app", "Name": "legacy"}}
                """);
        Files.writeString(directory.resolve("appconfig-profiles" + suffix), """
                {"000000000000/profile": {"Id": "profile", "ApplicationId": "app",
                  "Name": "legacy", "LocationUri": "hosted", "Type": "AWS.Freeform"}}
                """);
        Files.writeString(directory.resolve("appconfig-versions" + suffix), """
                {"000000000000/app::profile::7": {"ApplicationId": "app", "ConfigurationProfileId": "profile",
                  "VersionNumber": 7, "Content": "bGVnYWN5", "ContentType": "text/plain"}}
                """);
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(first);
            assertEquals(7, service.getHostedConfigurationVersion("app", "profile", 7).getVersionNumber());
            service.deleteHostedConfigurationVersion("app", "profile", 7);
            service.deleteHostedConfigurationVersion("app", "profile", 100);
            assertEquals(List.of(), service.listHostedConfigurationVersions("app", "profile"));
        } finally {
            first.shutdownAll();
        }
        StorageFactory restarted = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            AppConfigService service = service(restarted);
            Selection selection = new Selection("app", "profile");
            assertEquals(8, create(service, selection, "after-delete", 0).getVersionNumber());
            remove(service, selection);
        } finally {
            restarted.shutdownAll();
        }
    }

    private static AppConfigService service(StorageFactory factory) {
        return new AppConfigService(factory, new RegionResolver("us-east-1", ACCOUNT));
    }

    private static Selection selection(AppConfigService service) {
        String app = service.createApplication(Map.of("Name", "labels")).getId();
        String profile = service.createConfigurationProfile(app,
                Map.of("Name", "labels", "LocationUri", "hosted")).getId();
        return new Selection(app, profile);
    }

    private static Selection populated(AppConfigService service) {
        Selection selection = selection(service);
        create(service, selection, "release-a", 0);
        create(service, selection, "release-b", 1);
        return selection;
    }

    private static HostedConfigurationVersion create(AppConfigService service, Selection selection,
                                                       String label, Integer latest) {
        return service.createHostedConfigurationVersion(selection.app(), selection.profile(),
                CONTENT, "application/octet-stream", "content", label, latest);
    }

    private static String fullState(AppConfigService service, Selection selection) throws Exception {
        List<HostedConfigurationVersion> versions = service.listHostedConfigurationVersions(
                selection.app(), selection.profile()).stream().map(version -> service.getHostedConfigurationVersion(
                        selection.app(), selection.profile(), version.getVersionNumber())).toList();
        return MAPPER.writeValueAsString(versions);
    }

    private static void assertBadRequest(Runnable action) {
        AwsException error = assertThrows(AwsException.class, action::run);
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    private static void remove(AppConfigService service, Selection selection) {
        service.deleteConfigurationProfile(selection.app(), selection.profile());
        service.deleteApplication(selection.app());
    }

    private record Selection(String app, String profile) {}
}
