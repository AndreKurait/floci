package io.floci.aws.services.lambdaweb;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.AuthConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecr.registry.EcrWorkloadImagePull;
import io.floci.aws.services.iam.model.SessionCreds;
import io.floci.aws.services.lambda.launcher.ImageResolver;
import io.floci.aws.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.floci.aws.services.lambda.zip.ZipExtractor;
import io.floci.aws.services.lambdaweb.LambdaWebEnvironmentManager.Environment;
import io.floci.aws.services.lambdaweb.LambdaWebEnvironmentManager.StartFailure;
import io.floci.aws.services.lambdaweb.model.WebFunction;
import io.floci.aws.services.lambdaweb.model.WebFunctionRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LambdaWebEnvironmentManagerTest {

    private static final List<String> BASE = List.of("--expose-gc", "--max-http-header-size", "135168",
            "--no-experimental-require-module");
    private static final List<String> DEFAULT_HEAP = List.of("--max-semi-space-size=102", "--max-old-space-size=1024");

    private final LambdaWebExecutionRole executionRole = mock(LambdaWebExecutionRole.class);
    private final LambdaExecutionRoleCredentials credentials = mock(LambdaExecutionRoleCredentials.class);
    private final LambdaWebMetadataServer metadata = mock(LambdaWebMetadataServer.class);
    private final ContainerBuilder containerBuilder = mock(ContainerBuilder.class);

    @BeforeEach
    void trustTheRole() {
        when(executionRole.assumableFor(any(), any(), any())).thenReturn(true);
    }

    @Test
    void heapFlagsInNodeOptionsReplaceBothDefaultHeapFlags() {
        assertEquals(flags(DEFAULT_HEAP), LambdaWebEnvironmentManager.nodeFlags(null));
        assertEquals(flags(List.of("--max-old-space-size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=1536"));
        assertEquals(flags(List.of("--max-semi-space-size=64")),
                LambdaWebEnvironmentManager.nodeFlags("--max-semi-space-size=64"));
        assertEquals(flags(List.of("--max-old-space-size=1536", "--max-semi-space-size=64")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=1536 --max-semi-space-size=64"));
        assertEquals(flags(List.of("--max-old-space-size=1536", "--max-old-space-size=1200")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=1536 --max-old-space-size=1200"));
        assertEquals(flags(List.of("--max_old_space_size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--max_old_space_size=1536"));
        assertEquals(flags(List.of("--max-old_space-size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old_space-size=1536"));
        assertEquals(flags(List.of("--max-old-space-size=1536")),
                LambdaWebEnvironmentManager.nodeFlags("--expose-gc --max-old-space-size=1536 --title=y"));
        assertEquals(flags(List.of("--max-semi-space-size=32")),
                LambdaWebEnvironmentManager.nodeFlags("--expose-gc   --max-semi-space-size=32"));
        assertEquals(flags(List.of("--max-old-space-size")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size 1536"));
        assertEquals(flags(List.of("--max-old-space-size=\"1536\"")),
                LambdaWebEnvironmentManager.nodeFlags("--max-old-space-size=\"1536\""));
    }

    @Test
    void otherFlagsInNodeOptionsKeepTheDefaults() {
        List<String> defaults = flags(DEFAULT_HEAP);
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("--expose-gc"));
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("--max-http-header-size=1000"));
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("--require ./pre.js"));
        assertEquals(defaults, LambdaWebEnvironmentManager.nodeFlags("\"--max-old-space-size=1536\""));
    }

    @Test
    void aRoleDeletedAfterTheTrustCheckStartsNoContainer() {
        when(credentials.forRole(any(), any(), any())).thenReturn(Optional.empty());

        StartFailure failure = assertThrows(StartFailure.class, this::acquire);
        assertEquals(LambdaWebErrorPages.CANNOT_ASSUME_ROLE, failure.page());
        verifyNoInteractions(containerBuilder);
    }

    @Test
    void aMetadataServerThatCannotStartLeavesNoSessionOrToken() {
        when(metadata.address()).thenThrow(new IllegalStateException("no port"));

        assertThrows(IllegalStateException.class, this::acquire);
        verifyNoInteractions(credentials, containerBuilder);
        verify(metadata, never()).register(any());
    }

    @Test
    void privateRuntimeUsesWebFunctionsRoleAndCleansUpSessionsOnPullFailure() {
        String image = "localhost:4566/000000000000/us-east-1/runtime:latest";
        String source = "arn:aws:lambda:us-east-1:000000000000:web-function/fn";
        String role = "arn:aws:iam::000000000000:role/web";
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        ImageResolver images = mock(ImageResolver.class);
        EcrRegistryManager registry = mock(EcrRegistryManager.class);
        EcrWorkloadImagePull pulls = mock(EcrWorkloadImagePull.class);
        AuthConfig credential = new AuthConfig().withRegistryAddress("localhost:4566")
                .withUsername("AWS").withPassword("scoped");
        SessionCreds session = new SessionCreds("ASIAWEB", "owned-test-secret", "owned-test-session");
        when(credentials.forRole(role, "000000000000", "fn")).thenReturn(Optional.of(session));
        when(metadata.register("us-east-1")).thenReturn("owned-token");
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        when(images.resolve("nodejs24.x")).thenReturn(image);
        when(registry.rewriteImageUri(image)).thenReturn(image);
        when(containerBuilder.newContainer(image)).thenReturn(mock(ContainerBuilder.Builder.class, RETURNS_SELF));
        when(pulls.withRolePull(eq(image), eq(role), eq(source), any())).thenAnswer(invocation -> {
            Function<AuthConfig, String> operation = invocation.getArgument(3);
            return operation.apply(credential);
        });
        when(lifecycle.create(null, null, credential)).thenThrow(new IllegalStateException("Owned pull failed"));
        LambdaWebEnvironmentManager manager = new LambdaWebEnvironmentManager(config, containerBuilder, lifecycle,
                mock(DockerClient.class), images, registry, mock(LambdaWebCode.class), mock(ZipExtractor.class),
                mock(LaunchedContainerAwsEnv.class), credentials, executionRole, metadata,
                mock(LambdaWebLogs.class), mock(LambdaWebMetrics.class), pulls);
        try {
            assertEquals("Owned pull failed", assertThrows(IllegalStateException.class, () -> acquire(manager)).getMessage());
            verify(pulls).withRolePull(eq(image), eq(role), eq(source), any());
            verify(lifecycle).create(null, null, credential);
            verify(lifecycle, never()).create(any());
            verify(credentials).unregister("000000000000", "ASIAWEB");
            verify(metadata).forget("owned-token");
            clearInvocations(lifecycle, credentials, metadata);
            when(pulls.withRolePull(eq(image), eq(role), eq(source), any()))
                    .thenThrow(new AwsException("DENIED", "No image grant", 403));
            assertEquals("No image grant", assertThrows(AwsException.class, () -> acquire(manager)).getMessage());
            verify(lifecycle, never()).create(any());
            verify(lifecycle, never()).create(any(), any(), any());
            verify(credentials).unregister("000000000000", "ASIAWEB");
            verify(metadata).forget("owned-token");
        } finally {
            manager.shutdown();
        }
    }

    private void acquire() {
        LambdaWebEnvironmentManager manager = new LambdaWebEnvironmentManager(
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), containerBuilder,
                mock(ContainerLifecycleManager.class), mock(DockerClient.class), mock(ImageResolver.class),
                mock(EcrRegistryManager.class), mock(LambdaWebCode.class), mock(ZipExtractor.class),
                mock(LaunchedContainerAwsEnv.class), credentials, executionRole, metadata,
                mock(LambdaWebLogs.class), mock(LambdaWebMetrics.class));
        try {
            acquire(manager);
        } finally {
            manager.shutdown();
        }
    }

    private void acquire(LambdaWebEnvironmentManager manager) {
        WebFunction fn = new WebFunction();
        fn.functionName = "fn";
        fn.functionArn = "arn:aws:lambda:us-east-1:000000000000:web-function/fn";
        WebFunctionRevision revision = new WebFunctionRevision();
        revision.revisionId = "1";
        revision.buildConfig.runtime = "nodejs24.x";
        revision.serviceConfig.executionRoleArn = "arn:aws:iam::000000000000:role/web";
        manager.acquire("000000000000", "us-east-1", fn, "endpoint", null, revision);
    }

    private static List<String> flags(List<String> heap) {
        List<String> flags = new ArrayList<>(BASE);
        flags.addAll(heap);
        return flags;
    }

    @Test
    void onlyAnExitSeenBeforeTheRequestStartedAllowsARetry() {
        Environment environment = new Environment("pool", "rev-1", "000000000000");
        long started = System.nanoTime();
        assertFalse(environment.exitedBefore(started));

        environment.sawExit(started);

        assertFalse(environment.exitedBefore(started));
        assertFalse(environment.exitedBefore(started - 1));
        assertTrue(environment.exitedBefore(started + 1));
    }

    @Test
    void awaitExitEndsWhenTheExitIsSeen() throws Exception {
        Environment environment = new Environment("pool", "rev-1", "000000000000");
        CompletableFuture<Void> waited = environment.awaitExit(Duration.ofMinutes(1));
        assertFalse(waited.isDone());

        environment.sawExit(System.nanoTime());

        waited.get(5, TimeUnit.SECONDS);
    }

    @Test
    void awaitExitGivesUpOnAProcessThatKeepsRunning() throws Exception {
        Environment environment = new Environment("pool", "rev-1", "000000000000");

        environment.awaitExit(Duration.ofMillis(10)).get(5, TimeUnit.SECONDS);

        assertFalse(environment.exitedBefore(System.nanoTime()));
        environment.sawExit(System.nanoTime());
        assertTrue(environment.exitedBefore(System.nanoTime()));
    }
}
