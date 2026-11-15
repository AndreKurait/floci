package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AppConfigResourceTagsTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"application", "environment", "configurationprofile", "deploymentstrategy"})
    void createMergeOverwriteRemoveAndMetadataUpdatesPreserveIndependentSnapshots(String type) {
        AppConfigService service = service(new InMemoryStorageFactory(ACCOUNT));
        Map<String, String> original = new HashMap<>(Map.of("team", "payments", "remove", "yes"));
        Resource resource = create(service, type, original);
        AppConfigTagHandler handler = new AppConfigTagHandler(service);
        original.clear();
        Map<String, String> snapshot = storedTags(service, resource);
        assertEquals(Map.of("team", "payments", "remove", "yes"), handler.listTags(REGION, resource.arn()));
        handler.tagResource(REGION, resource.arn(), Map.of("team", "billing", "empty", ""));
        assertEquals(Map.of("team", "payments", "remove", "yes"), snapshot);
        Map<String, String> beforeUntag = storedTags(service, resource);
        handler.untagResource(REGION, resource.arn(), List.of("remove", "absent"));
        assertTrue(beforeUntag.containsKey("remove"));
        Map<String, String> expected = Map.of("team", "billing", "empty", "");
        update(service, resource);
        assertEquals(expected, handler.listTags(REGION, resource.arn()));
        handler.untagResource(REGION, resource.arn(), List.of());
        assertEquals(expected, handler.listTags(REGION, resource.arn()));
        handler.untagResource(REGION, resource.arn(), List.of("team", "empty"));
        assertEquals(Map.of(), handler.listTags(REGION, resource.arn()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application", "environment", "configurationprofile", "deploymentstrategy"})
    void foreignScopesAndDeletedResourcesNeverReadOrChangeLocalTags(String type) {
        AppConfigService service = service(new InMemoryStorageFactory(ACCOUNT));
        Resource resource = create(service, type, Map.of("owner", "local"));
        AppConfigTagHandler handler = new AppConfigTagHandler(service);
        for (String foreign : List.of(resource.arn().replace(ACCOUNT, "111111111111"),
                resource.arn().replace(REGION, "us-west-2"), resource.arn().replace("arn:aws:", "arn:aws-cn:"))) {
            missing(() -> handler.listTags(REGION, foreign));
            missing(() -> handler.tagResource(REGION, foreign, Map.of("owner", "foreign")));
            missing(() -> handler.untagResource(REGION, foreign, List.of("owner")));
        }
        assertEquals(Map.of("owner", "local"), handler.listTags(REGION, resource.arn()));
        delete(service, resource);
        missing(() -> handler.listTags(REGION, resource.arn()));
        missing(() -> handler.tagResource(REGION, resource.arn(), Map.of("owner", "again")));
        missing(() -> handler.untagResource(REGION, resource.arn(), List.of("owner")));
        if (type.equals("application")) {
            String otherId = service.createApplication(Map.of("Name", resource.id())).getId();
            missing(() -> handler.tagResource(REGION, resource.arn(), Map.of("owner", "wrong")));
            assertTrue(service.getApplicationTags(otherId).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"environment", "configurationprofile"})
    void wrongParentAndMalformedNestedArnCannotReachChildTags(String type) {
        AppConfigService service = service(new InMemoryStorageFactory(ACCOUNT));
        Resource resource = create(service, type, Map.of("owner", "right"));
        AppConfigTagHandler handler = new AppConfigTagHandler(service);
        String other = service.createApplication(Map.of("Name", "other")).getId();
        String wrong = resource.arn().replace(resource.parent(), other);
        missing(() -> handler.listTags(REGION, wrong));
        missing(() -> handler.tagResource(REGION, wrong, Map.of("owner", "wrong")));
        missing(() -> handler.untagResource(REGION, wrong, List.of("owner")));
        String malformed = resource.arn().replace("/" + type + "/", "/environment/fake/" + type + "/");
        assertEquals(400, assertThrows(AwsException.class,
                () -> handler.tagResource(REGION, malformed, Map.of("owner", "wrong"))).getHttpStatus());
        assertEquals(Map.of("owner", "right"), handler.listTags(REGION, resource.arn()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application", "environment", "configurationprofile", "deploymentstrategy"})
    void invalidTagsAndMergedLimitFailAtomically(String type) {
        AppConfigService service = service(new InMemoryStorageFactory(ACCOUNT));
        Resource resource = create(service, type, Map.of("keep", "yes"));
        AppConfigTagHandler handler = new AppConfigTagHandler(service);
        for (Map<String, String> invalid : List.of(Map.of("", "x"), Map.of("aws:reserved", "x"), Map.of("k".repeat(129), "x"),
                Map.of("k", "v".repeat(257)))) {
            assertEquals(400, assertThrows(AwsException.class,
                    () -> handler.tagResource(REGION, resource.arn(), invalid)).getHttpStatus());
            assertEquals(Map.of("keep", "yes"), handler.listTags(REGION, resource.arn()));
        }
        Map<String, String> fifty = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            fifty.put("key" + i, "value");
        }
        assertEquals(400, assertThrows(AwsException.class,
                () -> handler.tagResource(REGION, resource.arn(), fifty)).getHttpStatus());
        assertEquals(Map.of("keep", "yes"), handler.listTags(REGION, resource.arn()));
        fifty.remove("key0");
        handler.tagResource(REGION, resource.arn(), fifty);
        assertEquals(50, handler.listTags(REGION, resource.arn()).size());
        handler.tagResource(REGION, resource.arn(), Map.of("keep", "updated"));
        assertEquals("updated", handler.listTags(REGION, resource.arn()).get("keep"));
    }

    @ParameterizedTest
    @CsvSource({"persistent,application", "persistent,environment", "persistent,configurationprofile",
            "persistent,deploymentstrategy", "hybrid,application", "hybrid,environment",
            "hybrid,configurationprofile", "hybrid,deploymentstrategy", "wal,application",
            "wal,environment", "wal,configurationprofile", "wal,deploymentstrategy"})
    void tagsSurviveStorageReloadAndDeletionStaysDeleted(String mode, String type) {
        StorageFactory first = factory(mode);
        Resource resource;
        try {
            AppConfigService service = service(first);
            resource = create(service, type, Map.of("keep", "original", "remove", "yes"));
            AppConfigTagHandler handler = new AppConfigTagHandler(service);
            handler.tagResource(REGION, resource.arn(), Map.of("keep", "updated", "empty", ""));
            handler.untagResource(REGION, resource.arn(), List.of("remove"));
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = factory(mode);
        try {
            AppConfigService service = service(second);
            assertEquals(Map.of("keep", "updated", "empty", ""),
                    new AppConfigTagHandler(service).listTags(REGION, resource.arn()));
            delete(service, resource);
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = factory(mode);
        try {
            AppConfigTagHandler handler = new AppConfigTagHandler(service(third));
            missing(() -> handler.listTags(REGION, resource.arn()));
        } finally {
            third.shutdownAll();
        }
    }

    private StorageFactory factory(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(60000L);
        return new StorageFactory(config, access);
    }

    private static AppConfigService service(StorageFactory factory) {
        return new AppConfigService(factory, new RegionResolver(REGION, ACCOUNT),
                mock(AppConfigAlarmMonitor.class), mock(AppConfigS3Source.class));
    }

    private static Resource create(AppConfigService service, String type, Map<String, String> tags) {
        String parent = service.createApplication(Map.of("Name", "parent")).getId();
        Map<String, Object> request = new HashMap<>(Map.of("Name", "resource", "Tags", tags));
        String id = switch (type) {
            case "application" -> service.createApplication(request).getId();
            case "environment" -> service.createEnvironment(parent, request).getId();
            case "configurationprofile" -> {
                request.put("LocationUri", "hosted");
                yield service.createConfigurationProfile(parent, request).getId();
            }
            case "deploymentstrategy" -> {
                request.put("DeploymentDurationInMinutes", 0);
                request.put("GrowthFactor", 100f);
                yield service.createDeploymentStrategy(request).getId();
            }
            default -> throw new IllegalArgumentException(type);
        };
        String path = (type.equals("environment") || type.equals("configurationprofile"))
                ? "application/" + parent + "/" + type + "/" + id : type + "/" + id;
        return new Resource(type, parent, id, "arn:aws:appconfig:" + REGION + ":" + ACCOUNT + ":" + path);
    }

    private static Map<String, String> storedTags(AppConfigService service, Resource resource) {
        return switch (resource.type()) {
            case "application" -> service.getApplication(resource.id()).getTags();
            case "environment" -> service.getEnvironment(resource.parent(), resource.id()).getTags();
            case "configurationprofile" -> service.getConfigurationProfile(resource.parent(), resource.id()).getTags();
            case "deploymentstrategy" -> service.getDeploymentStrategy(resource.id()).getTags();
            default -> throw new IllegalArgumentException(resource.type());
        };
    }

    private static void update(AppConfigService service, Resource resource) {
        switch (resource.type()) {
            case "application" -> service.updateApplication(resource.id(), "renamed", "changed");
            case "environment" -> service.updateEnvironment(resource.parent(), resource.id(), "renamed", "changed", null);
            case "configurationprofile" -> service.updateConfigurationProfile(resource.parent(), resource.id(),
                    Map.of("Name", "renamed", "Description", "changed"));
            case "deploymentstrategy" -> service.updateDeploymentStrategy(resource.id(), "changed", 0, 0, 100f, "LINEAR");
            default -> throw new IllegalArgumentException(resource.type());
        }
    }

    private static void delete(AppConfigService service, Resource resource) {
        switch (resource.type()) {
            case "application" -> service.deleteApplicationById(resource.id());
            case "environment" -> service.deleteEnvironmentById(resource.parent(), resource.id());
            case "configurationprofile" -> service.deleteConfigurationProfileById(resource.parent(), resource.id());
            case "deploymentstrategy" -> service.deleteDeploymentStrategy(resource.id());
            default -> throw new IllegalArgumentException(resource.type());
        }
    }

    private static void missing(Runnable operation) {
        AwsException error = assertThrows(AwsException.class, operation::run);
        assertEquals(404, error.getHttpStatus());
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    private record Resource(String type, String parent, String id, String arn) {}
}
