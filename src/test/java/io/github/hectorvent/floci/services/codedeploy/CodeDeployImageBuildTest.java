package io.github.hectorvent.floci.services.codedeploy;

import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.BuildResponseItem;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeDeployImageBuildTest {
    @Test
    void failedBuildRetainsPackageOutputAndOriginalError() throws IOException {
        try (CodeDeployImageBuild build = new CodeDeployImageBuild()) {
            build.onNext(stream("E: Failed to fetch package archive\n"));
            BuildResponseItem error = mock(BuildResponseItem.class);
            when(error.isErrorIndicated()).thenReturn(true);
            when(error.getError()).thenReturn("command returned code 100");
            build.onNext(error);
            build.onComplete();

            IllegalStateException failure = failure(build);
            assertTrue(failure.getMessage().contains("E: Failed to fetch package archive\n"));
            assertTrue(failure.getMessage().endsWith("command returned code 100"));
            assertInstanceOf(DockerClientException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("command returned code 100"));
        }
    }

    @Test
    void successfulBuildStillReturnsImageId() throws IOException {
        try (CodeDeployImageBuild build = new CodeDeployImageBuild()) {
            build.onNext(stream("ordinary package installation\n"));
            BuildResponseItem success = mock(BuildResponseItem.class);
            when(success.isBuildSuccessIndicated()).thenReturn(true);
            when(success.getImageId()).thenReturn("sha256:fixture-image");
            build.onNext(success);
            build.onComplete();

            assertEquals("sha256:fixture-image", build.awaitImageId(0, TimeUnit.SECONDS));
        }
    }

    @Test
    void retainsOnlyBoundedTailAcrossChunks() throws IOException {
        try (CodeDeployImageBuild build = new CodeDeployImageBuild()) {
            build.onNext(stream("a".repeat(CodeDeployImageBuild.OUTPUT_LIMIT)));
            build.onNext(stream("final package error\n"));
            build.onNext(mock(BuildResponseItem.class));
            build.onComplete();

            String tail = tail(failure(build));
            assertEquals(CodeDeployImageBuild.OUTPUT_LIMIT, tail.length());
            assertTrue(tail.endsWith("final package error\n"));
        }
    }

    @Test
    void oversizedSingleChunkAlsoRetainsBoundedTail() throws IOException {
        try (CodeDeployImageBuild build = new CodeDeployImageBuild()) {
            build.onNext(stream("discarded previous output\n"));
            build.onNext(stream("b".repeat(CodeDeployImageBuild.OUTPUT_LIMIT * 2) + "last"));
            build.onComplete();

            assertEquals("b".repeat(CodeDeployImageBuild.OUTPUT_LIMIT - 4) + "last", tail(failure(build)));
        }
    }

    @Test
    void transportFailurePreservesCauseAndOutput() throws IOException {
        try (CodeDeployImageBuild build = new CodeDeployImageBuild()) {
            RuntimeException transport = new IllegalStateException("transport failed");
            build.onNext(stream("last build step\n"));
            build.onError(transport);

            IllegalStateException failure = failure(build);
            assertSame(transport, failure.getCause());
            assertTrue(failure.getMessage().endsWith("last build step\n"));
        }
    }

    @Test
    void incompleteBuildDoesNotBecomeSuccess() throws IOException {
        try (CodeDeployImageBuild build = new CodeDeployImageBuild()) {
            build.onNext(stream("waiting for package\n"));
            IllegalStateException failure = failure(build);
            assertInstanceOf(DockerClientException.class, failure.getCause());
            assertTrue(failure.getMessage().endsWith("waiting for package\n"));
        }
    }

    private static BuildResponseItem stream(String text) {
        BuildResponseItem item = mock(BuildResponseItem.class);
        when(item.getStream()).thenReturn(text);
        return item;
    }

    private static IllegalStateException failure(CodeDeployImageBuild build) {
        return assertThrows(IllegalStateException.class, () -> build.awaitImageId(0, TimeUnit.SECONDS));
    }

    private static String tail(IllegalStateException failure) {
        return failure.getMessage().substring(failure.getMessage().indexOf('\n') + 1);
    }
}
