package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.stepfunctions.model.Activity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepFunctionsActivityMutationRaceTest {
    private static final String ARN = "arn:aws:states:us-west-2:867530900003:activity:worker";

    @ParameterizedTest
    @ValueSource(strings = {"replace", "tag", "untag"})
    void deletingDuringTagMutationDoesNotResurrectTheActivity(String operation) throws Exception {
        Activity initial = new Activity();
        initial.setActivityArn(ARN);
        initial.setName("worker");
        initial.setTags(new HashMap<>(Map.of("old", "value")));
        AtomicReference<Activity> stored = new AtomicReference<>(initial);
        AtomicBoolean pauseRead = new AtomicBoolean(true);
        CountDownLatch readCaptured = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AccountAwareStorageBackend<Activity> activities = mock(AccountAwareStorageBackend.class);
        when(activities.get(ARN)).thenAnswer(call -> {
            Activity captured = stored.get();
            if (pauseRead.getAndSet(false)) {
                readCaptured.countDown();
                assertTrue(releaseRead.await(10, TimeUnit.SECONDS));
            }
            return Optional.ofNullable(captured);
        });
        doAnswer(call -> {
            stored.set(call.getArgument(1));
            return null;
        }).when(activities).put(eq(ARN), any());
        doAnswer(call -> {
            stored.set(null);
            return null;
        }).when(activities).delete(ARN);
        StorageFactory factory = mock(StorageFactory.class);
        AccountAwareStorageBackend<Object> empty = mock(AccountAwareStorageBackend.class);
        when(empty.get(anyString())).thenReturn(Optional.empty());
        doReturn(empty).when(factory).create(anyString(), anyString(), any());
        doReturn(activities).when(factory).create(eq("stepfunctions"), eq("sfn-activities.json"), any());
        StepFunctionsService service = new StepFunctionsService(factory, mock(RegionResolver.class),
                mock(AslExecutor.class), new ObjectMapper(), mock(SfnMockLoader.class),
                mock(StepFunctionsEventPublisher.class));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                switch (operation) {
                    case "replace" -> service.replaceActivityTags(ARN, Map.of("new", "value"));
                    case "tag" -> service.tagResource(ARN, Map.of("new", "value"));
                    case "untag" -> service.untagResource(ARN, List.of("old"));
                    default -> throw new AssertionError(operation);
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, "activity-tag-writer");
        Thread deleter = new Thread(() -> {
            try {
                service.deleteActivity(ARN);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, "activity-deleter");
        try {
            writer.start();
            assertTrue(readCaptured.await(10, TimeUnit.SECONDS));
            deleter.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (deleter.isAlive() && !blockedAtDelete(deleter) && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(!deleter.isAlive() || blockedAtDelete(deleter),
                    "Delete must either complete or wait for the tag mutation");
        } finally {
            releaseRead.countDown();
            writer.join(10_000);
            deleter.join(10_000);
        }
        assertTrue(!writer.isAlive() && !deleter.isAlive());
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        AwsException missing = assertThrows(AwsException.class, () -> service.describeActivity(ARN));
        assertEquals("ActivityDoesNotExist", missing.getErrorCode());
        assertNull(stored.get());
    }

    private static boolean blockedAtDelete(Thread thread) {
        if (thread.getState() != Thread.State.BLOCKED) {
            return false;
        }
        StackTraceElement[] stack = thread.getStackTrace();
        return stack.length > 0
                && StepFunctionsService.class.getName().equals(stack[0].getClassName())
                && "deleteActivity".equals(stack[0].getMethodName());
    }
}
