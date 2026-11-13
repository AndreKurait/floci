package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ManualClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class CloudFormationExplorerResourcesTest {

    private static final String ACCOUNT = "000000000000";
    private static final Instant NOW = Instant.parse("2031-05-06T07:08:09Z");

    @Test
    void aStackStoredWithoutACreationTimeIsReportedAtTheInjectedClock() {
        InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT);
        AccountAwareStorageBackend<Stack> stored = storage.create(
                "cloudformation", "cloudformation-stacks.json", new TypeReference<Map<String, Stack>>() {});
        Stack legacy = new Stack();
        legacy.setStackName("legacy");
        legacy.setRegion("us-east-1");
        legacy.setStackId("arn:aws:cloudformation:us-east-1:" + ACCOUNT + ":stack/legacy/abc123");
        legacy.setCreationTime(null);
        stored.putForAccount(ACCOUNT, "us-east-1::legacy", legacy);

        CloudFormationService service = CloudFormationServiceFixture.create(
                mock(CfnResourceDispatcher.class), mock(S3Service.class), new ManualClock(NOW), storage);
        service.loadPersistedState();

        List<ExplorerResource> resources = service.getResources();
        assertEquals(1, resources.size());
        assertEquals(NOW, resources.get(0).lastReportedAt());
    }
}
