package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.cloudwatch.logs.model.LogDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class CloudWatchLogsDestinationStorageTest {
    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-east-1";
    private static final String TARGET = "arn:aws:kinesis:us-west-2:" + ACCOUNT + ":stream/owned";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/owned";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, LogDestination>> TYPE = new TypeReference<>() {};

    @Test
    void failedControlAdmissionDoesNotChangeStoredOrReloadedDestination(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("destinations.json");
        PersistentStorage<String, LogDestination> store = new PersistentStorage<>(file, TYPE);
        CloudWatchLogsDestinationTargetService targets = mock(CloudWatchLogsDestinationTargetService.class);
        CloudWatchLogsCrossAccountService service = service(store, targets);
        put(service, "existing");
        service.putDestinationPolicy("existing", "{\"Statement\":[]}", REGION);
        JsonNode before = MAPPER.valueToTree(store.get(REGION + "::existing").orElseThrow());
        String disk = Files.readString(file);
        doThrow(new AwsException("InvalidParameterException", "controlled failed target write", 400))
                .when(targets).admit(any(LogDestination.class));
        assertThrows(AwsException.class, () -> put(service, "new"));
        assertThrows(AwsException.class, () -> service.putDestination("existing", TARGET + "-other", ROLE,
                Map.of("team", "changed"), REGION));
        assertEquals(1, store.keys().size());
        assertEquals(before, MAPPER.valueToTree(store.get(REGION + "::existing").orElseThrow()));
        assertEquals(disk, Files.readString(file));
        PersistentStorage<String, LogDestination> loaded = new PersistentStorage<>(file, TYPE);
        loaded.load();
        assertEquals(store.keys(), loaded.keys());
        assertEquals(before, MAPPER.valueToTree(loaded.get(REGION + "::existing").orElseThrow()));
    }

    @Test
    void returnedCopiesCannotMutateThePersistedDefinition(@TempDir Path directory) throws Exception {
        PersistentStorage<String, LogDestination> store = new PersistentStorage<>(directory.resolve("copy.json"), TYPE);
        CloudWatchLogsCrossAccountService service = service(store, mock(CloudWatchLogsDestinationTargetService.class));
        LogDestination created = put(service, "existing");
        JsonNode before = MAPPER.valueToTree(store.get(REGION + "::existing").orElseThrow());
        created.setTargetArn(TARGET + "-caller");
        created.setAccessPolicy("caller");
        mutateTags(created);
        LogDestination resolved = service.resolveDestination("arn:aws:logs:us-east-1:" + ACCOUNT + ":destination:existing", REGION);
        resolved.setRoleArn(ROLE + "-caller");
        mutateTags(resolved);
        LogDestination described = service.describeDestinations(null, 1, null, REGION).items().getFirst();
        described.setDestinationName("caller");
        mutateTags(described);
        assertEquals(before, MAPPER.valueToTree(store.get(REGION + "::existing").orElseThrow()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingLegacyDestinationCannotResurrectAfterReload(boolean scopedCopy, @TempDir Path directory) {
        Path file = directory.resolve("legacy.json");
        PersistentStorage<String, LogDestination> raw = new PersistentStorage<>(file, TYPE);
        String key = REGION + "::existing";
        LogDestination destination = put(service(raw, mock(CloudWatchLogsDestinationTargetService.class)), "existing");
        if (scopedCopy) {
            raw.put(ACCOUNT + "/" + key, destination);
        }
        LogDestination foreign = new LogDestination();
        foreign.setDestinationName("existing");
        foreign.setArn(destination.getArn().replace(ACCOUNT, "333333333333"));
        raw.put("333333333333/" + key, foreign);
        CloudWatchLogsCrossAccountService service = accountService(raw);
        service.deleteDestination("existing", REGION);
        assertThrows(AwsException.class, () -> service.resolveDestination(destination.getArn(), REGION));
        assertTrue(raw.get(key).isEmpty());
        assertTrue(raw.get(ACCOUNT + "/" + key).isEmpty());
        assertEquals(foreign.getArn(), raw.get("333333333333/" + key).orElseThrow().getArn());
        PersistentStorage<String, LogDestination> reloaded = new PersistentStorage<>(file, TYPE);
        reloaded.load();
        assertThrows(AwsException.class, () -> accountService(reloaded).resolveDestination(destination.getArn(), REGION));
        assertEquals(raw.keys(), reloaded.keys());
    }

    @Test
    void deletingScopedDestinationPreservesForeignLegacyValue(@TempDir Path directory) {
        PersistentStorage<String, LogDestination> raw = new PersistentStorage<>(directory.resolve("foreign.json"), TYPE);
        String key = REGION + "::existing";
        LogDestination destination = put(service(raw, mock(CloudWatchLogsDestinationTargetService.class)), "existing");
        raw.put(ACCOUNT + "/" + key, destination);
        LogDestination foreign = new LogDestination();
        foreign.setDestinationName("existing");
        foreign.setArn(destination.getArn().replace(ACCOUNT, "333333333333"));
        raw.put(key, foreign);
        accountService(raw).deleteDestination("existing", REGION);
        assertEquals(foreign.getArn(), raw.get(key).orElseThrow().getArn());
        assertTrue(raw.get(ACCOUNT + "/" + key).isEmpty());
    }

    @Test
    void foreignLegacyDestinationCannotBeDeletedByDefaultAccount(@TempDir Path directory) {
        PersistentStorage<String, LogDestination> raw = new PersistentStorage<>(directory.resolve("denied.json"), TYPE);
        LogDestination destination = put(service(raw, mock(CloudWatchLogsDestinationTargetService.class)), "existing");
        destination.setArn(destination.getArn().replace(ACCOUNT, "333333333333"));
        raw.put(REGION + "::existing", destination);
        assertThrows(AwsException.class, () -> accountService(raw).deleteDestination("existing", REGION));
        assertEquals(destination.getArn(), raw.get(REGION + "::existing").orElseThrow().getArn());
    }

    private static CloudWatchLogsCrossAccountService accountService(PersistentStorage<String, LogDestination> raw) {
        return new CloudWatchLogsCrossAccountService(new AccountAwareStorageBackend<>(raw, null, ACCOUNT),
                new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT), MAPPER,
                mock(CloudWatchLogsDestinationTargetService.class));
    }

    private static CloudWatchLogsCrossAccountService service(PersistentStorage<String, LogDestination> store,
                                                              CloudWatchLogsDestinationTargetService targets) {
        return new CloudWatchLogsCrossAccountService(store, new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT), MAPPER, targets);
    }

    private static LogDestination put(CloudWatchLogsCrossAccountService service, String name) {
        return service.putDestination(name, TARGET, ROLE, Map.of("team", "original"), REGION);
    }

    private static void mutateTags(LogDestination destination) {
        destination.getTags().put("team", "caller");
    }
}
