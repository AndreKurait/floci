package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertThrows(AwsException.class, () -> service.putDestination("existing", TARGET + "-other", ROLE, REGION));
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

    private static CloudWatchLogsCrossAccountService service(PersistentStorage<String, LogDestination> store,
                                                              CloudWatchLogsDestinationTargetService targets) {
        return new CloudWatchLogsCrossAccountService(store, new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT), MAPPER, targets);
    }

    private static LogDestination put(CloudWatchLogsCrossAccountService service, String name) {
        return service.putDestination(name, TARGET, ROLE, REGION);
    }

    private static void mutateTags(LogDestination destination) {
        destination.setTargetArn(TARGET + "-caller-alias");
    }
}
