package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.cloudformation.provisioners.CfnDynamicReferences;
import io.floci.aws.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.ssm.SsmService;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFormationDriftLifetimeTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";

    @Test
    void absenceAndReplacedStackReceiptsCannotPublishOrAdoptLaterNames() {
        try (Harness h = new Harness()) {
            CloudFormationDriftSnapshot absent = h.service.captureDriftStack("owned", REGION, ACCOUNT);
            h.seed("first");
            AtomicBoolean wrote = new AtomicBoolean();
            assertThrows(AwsException.class, () -> h.service.withCurrentDriftStack(absent, () -> wrote.set(true)));
            CloudFormationDriftSnapshot first = h.service.captureDriftStack("owned", REGION, ACCOUNT);
            h.seed("second");
            assertThrows(AwsException.class, () -> h.service.withCurrentDriftStack(first, () -> wrote.set(true)));
            assertFalse(wrote.get());
            assertEquals(null, h.service.captureDriftStack(first.stackId(), REGION, ACCOUNT).stackId());
        }
    }

    @Test
    void changedTemplateOrTagsIsAStaleReceiptAndDeniedValidationDoesNotWrite() {
        try (Harness h = new Harness()) {
            Stack current = h.seed("first");
            CloudFormationDriftSnapshot before = h.service.captureDriftStack("owned", REGION, ACCOUNT);
            current.getTags().put("Owner", "new");
            AtomicBoolean wrote = new AtomicBoolean();
            assertThrows(AwsException.class, () -> h.service.withCurrentDriftStack(before, () -> wrote.set(true)));
            CloudFormationDriftSnapshot tagged = h.service.captureDriftStack("owned", REGION, ACCOUNT);
            current.setTemplateBody("{\"Resources\":{\"Changed\":{}}}");
            assertThrows(AwsException.class, () -> h.service.withCurrentDriftStack(tagged, () -> wrote.set(true)));
            assertFalse(wrote.get());
        }
    }

    @Test
    void actualDeleteAdmissionWaitsForTheDriftCommitBoundary() throws Exception {
        try (Harness h = new Harness(); ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            h.seed("first");
            CloudFormationDriftSnapshot before = h.service.captureDriftStack("owned", REGION, ACCOUNT);
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean deleted = new AtomicBoolean();
            Future<?> publish = executor.submit(() -> h.service.withCurrentDriftStack(before, () -> {
                inside.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interruption);
                }
            }));
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            CountDownLatch deleting = new CountDownLatch(1);
            Future<?> delete = executor.submit(() -> {
                deleting.countDown();
                h.service.deleteStack("owned", REGION, ACCOUNT);
                deleted.set(true);
            });
            try {
                assertTrue(deleting.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> delete.get(100, TimeUnit.MILLISECONDS));
                assertFalse(deleted.get());
                assertEquals("CREATE_COMPLETE", before.status());
            } finally {
                release.countDown();
            }
            publish.get(5, TimeUnit.SECONDS);
            delete.get(5, TimeUnit.SECONDS);
            assertTrue(deleted.get());
            assertThrows(AwsException.class, () -> h.service.withCurrentDriftStack(before,
                    () -> { throw new AssertionError("stale delete receipt wrote"); }));
        }
    }

    @Test
    void corruptPresentIdentityIsAnErrorInsteadOfAnAbsentStack() {
        try (Harness h = new Harness()) {
            Stack current = h.seed("first");
            current.setAccountId("999999999999");
            assertThrows(AwsException.class, () -> h.service.captureDriftStack("owned", REGION, ACCOUNT));
        }
    }

    private static final class Harness implements AutoCloseable {
        private final InMemoryStorageFactory factory = new InMemoryStorageFactory(ACCOUNT);
        private final AccountAwareStorageBackend<Stack> backend = factory.create("cloudformation",
                "cloudformation-stacks.json", new TypeReference<Map<String, Stack>>() {});
        private final CloudFormationService service;

        private Harness() {
            EmulatorConfig config = mock(EmulatorConfig.class);
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.defaultRegion()).thenReturn(REGION);
            service = new CloudFormationService(mock(CfnResourceDispatcher.class), mock(S3Service.class),
                    mock(SsmService.class), mock(GetStackOutputRoleAuthorizer.class), mock(CfnDynamicReferences.class),
                    new ObjectMapper(), config, new RegionResolver(REGION, ACCOUNT), Clock.systemUTC(), factory);
        }

        private Stack seed(String lifetime) {
            Stack stack = new Stack();
            stack.setStackName("owned");
            stack.setStackId("arn:aws:cloudformation:us-east-1:123456789012:stack/owned/" + lifetime);
            stack.setAccountId(ACCOUNT);
            stack.setRegion(REGION);
            stack.setCreationTime(Instant.EPOCH);
            stack.setStatus("CREATE_COMPLETE");
            stack.setTemplateBody("{\"Resources\":{}}");
            backend.putForAccount(ACCOUNT, REGION + ":owned", stack);
            service.loadPersistedState();
            return stack;
        }

        @Override
        public void close() {
            service.stop();
        }
    }
}
