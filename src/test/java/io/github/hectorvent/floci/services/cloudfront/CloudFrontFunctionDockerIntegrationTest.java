package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@QuarkusTest
class CloudFrontFunctionDockerIntegrationTest {

    private static final String EVENT = "{\"context\":{\"eventType\":\"viewer-request\"},"
            + "\"request\":{\"uri\":\"/real\",\"headers\":{}}}";

    @Inject
    ContainerBuilder builder;
    @Inject
    ContainerLifecycleManager lifecycle;
    @Inject
    EmulatorConfig config;
    @Inject
    ObjectMapper mapper;

    private DockerCloudFrontFunctionRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new DockerCloudFrontFunctionRuntime(builder, lifecycle, config, mapper);
    }

    @AfterEach
    void removeOwnedContainers() {
        runtime.stopManagedContainers();
        assertTrue(runtime.activeContainers().isEmpty());
    }

    @Test
    void actualChildHasTheDeclaredIsolationAndResourceBounds() throws Exception {
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CloudFrontFunctionRuntime.Result> pending = workers.submit(() -> run(
                    "function handler(event) { const end=Date.now()+1200; while(Date.now()<end){}; return event.request; }",
                    EVENT));
            String id = awaitContainer();
            InspectContainerResponse inspected = lifecycle.getDockerClient().inspectContainerCmd(id).exec();
            HostConfig host = inspected.getHostConfig();
            assertEquals("none", host.getNetworkMode());
            assertEquals("1000:1000", inspected.getConfig().getUser());
            assertTrue(host.getReadonlyRootfs());
            assertFalse(host.getPrivileged());
            assertEquals(128L * 1024 * 1024, host.getMemory());
            assertEquals(128L * 1024 * 1024, host.getMemorySwap());
            assertEquals(1_000_000_000L, host.getNanoCPUs());
            assertEquals(64L, host.getPidsLimit());
            assertTrue(host.getSecurityOpts().contains("no-new-privileges:true"));
            assertTrue(Arrays.asList(host.getCapDrop()).contains(Capability.ALL));
            assertTrue(host.getCapAdd() == null || host.getCapAdd().length == 0);
            assertTrue(host.getBinds() == null || host.getBinds().length == 0);
            assertTrue(inspected.getMounts() == null || inspected.getMounts().isEmpty());
            assertTrue(inspected.getConfig().getVolumes() == null || inspected.getConfig().getVolumes().isEmpty());
            assertTrue(host.getDevices() == null || host.getDevices().length == 0);
            assertTrue(host.getPidMode() == null || host.getPidMode().isEmpty());
            assertTrue(host.getPortBindings() == null || host.getPortBindings().getBindings().isEmpty());
            for (String entry : inspected.getConfig().getEnv()) {
                String name = entry.substring(0, entry.indexOf('='));
                assertFalse(name.startsWith("AWS_") || name.contains("SECRET") || name.contains("TOKEN")
                        || name.contains("PRIVATE_KEY") || name.equals("DOCKER_HOST"), name);
            }
            assertTrue(Arrays.asList(inspected.getArgs()).contains("--max-old-space-size=32"));
            assertTrue(Arrays.asList(inspected.getArgs()).contains("--stack-size=512"));
            assertTrue(Arrays.asList(inspected.getArgs()).contains("--no-addons"));
            assertTrue(Arrays.asList(inspected.getArgs()).contains("--disable-proto=throw"));
            assertEquals("1m", host.getLogConfig().getConfig().get("max-size"));
            assertEquals("1", host.getLogConfig().getConfig().get("max-file"));
            assertNull(pending.get(15, TimeUnit.SECONDS).evaluation().error());
            assertThrows(NotFoundException.class, () -> lifecycle.getDockerClient().inspectContainerCmd(id).exec());
        }
    }

    @Test
    void maximumEscapedEventAndSourceLaunchWithoutASingleOversizedArgument() throws Exception {
        String prefix = "{\"data\":\"";
        String suffix = "\"}";
        int count = (40960 - prefix.length() - suffix.length()) / 2;
        String event = prefix + "\\\"".repeat(count) + suffix;
        event += " ".repeat(40960 - event.length());
        String code = "function handler(event){return {statusCode:200,body:String(event.data.length)};}\n//";
        code += "\\".repeat(10240 - code.length());
        assertEquals(40960, event.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(10240, code.getBytes(StandardCharsets.UTF_8).length);
        CloudFrontFunctionRuntime.Evaluation result = run(code, event).evaluation();
        assertNull(result.error());
        assertEquals(Integer.toString(count), mapper.readTree(result.output()).path("response").path("body").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "function handler(event){while(true){}}",
            "function handler(event){return {get loop(){while(true){}}};}",
            "function handler(event){return {toJSON(){while(true){}}};}",
            "function handler(event){throw {get name(){while(true){}}};}",
            "function handler(event){throw {name:'Error',get message(){while(true){}}};}",
            "function handler(event){console.log({toString(){while(true){}}});return event.request;}",
            "function handler(event){Promise.resolve().then(()=>{while(true){}});return event.request;}"
    })
    void userCallbacksAndMicrotasksRemainInsideTheEvaluationDeadline(String code) {
        long start = System.nanoTime();
        CloudFrontFunctionRuntime.Evaluation result = run(code, EVENT).evaluation();
        assertNull(result.output());
        assertNotNull(result.error());
        assertTrue(result.error().contains("execution limit"), result.error());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(15));
        assertTrue(runtime.activeContainers().isEmpty());
    }

    @Test
    void theContextDoesNotExposeNodeHostGlobals() throws Exception {
        CloudFrontFunctionRuntime.Evaluation result = run("""
                function handler(event) {
                  return {statusCode:200,body:JSON.stringify([
                    typeof process,typeof require,typeof Buffer,typeof setTimeout,typeof setImmediate
                  ])};
                }
                """, EVENT).evaluation();
        assertNull(result.error());
        assertEquals(List.of("undefined", "undefined", "undefined", "undefined", "undefined"),
                mapper.readValue(mapper.readTree(result.output()).path("response").path("body").asText(), List.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "function handler(event){return Function('return process')();}",
            "function handler(event){return eval('process');}",
            "function handler(event){return Promise.resolve(event.request);}",
            "async function handler(event){return event.request;}"
    })
    void dynamicCodeAndAsyncHandlersProduceAnExecutionError(String code) {
        CloudFrontFunctionRuntime.Evaluation result = run(code, EVENT).evaluation();
        assertNull(result.output());
        assertNotNull(result.error());
    }

    @Test
    void consoleOutputIsBoundedWithAnExplicitTruncationMarker() {
        CloudFrontFunctionRuntime.Evaluation result = run("""
                function handler(event) {
                  for(let i=0;i<1000;i++) console.log('actual-log-'+i);
                  return event.request;
                }
                """, EVENT).evaluation();
        assertNull(result.error());
        assertEquals(100, result.logs().size());
        assertEquals("actual-log-0", result.logs().getFirst());
        assertEquals("[logs truncated]", result.logs().getLast());
        assertTrue(result.logs().stream().mapToInt(line -> line.getBytes(StandardCharsets.UTF_8).length).sum() <= 8192);
    }

    @Test
    void oversizedOutputIsAnExecutionError() {
        CloudFrontFunctionRuntime.Evaluation result = run(
                "function handler(event){return {statusCode:200,body:'x'.repeat(50000)};}", EVENT).evaluation();
        assertNull(result.output());
        assertTrue(result.error().contains("output limit"));
    }

    @Test
    void separateContainersDoNotShareGlobalState() throws Exception {
        String code = """
                var invocationCount=0;
                function handler(event) { invocationCount++; return {statusCode:200,body:String(invocationCount)}; }
                """;
        for (int i = 0; i < 2; i++) {
            JsonNode output = mapper.readTree(run(code, EVENT).evaluation().output());
            assertEquals("1", output.path("response").path("body").asText());
        }
    }

    @Test
    void actualNodeStartupFailureIsAnInfrastructureErrorAndItsContainerIsRemoved() {
        AtomicReference<String> created = new AtomicReference<>();
        ContainerLifecycleManager failing = mock(ContainerLifecycleManager.class, delegatesTo(lifecycle));
        doAnswer(call -> {
            ContainerSpec requested = call.getArgument(0);
            ContainerSpec malformed = builder.newContainer(requested.image()).withName(requested.name())
                    .withLabel(DockerCloudFrontFunctionRuntime.OWNER_LABEL,
                            requested.labels().get(DockerCloudFrontFunctionRuntime.OWNER_LABEL))
                    .withLabel(DockerCloudFrontFunctionRuntime.INVOCATION_LABEL,
                            requested.labels().get(DockerCloudFrontFunctionRuntime.INVOCATION_LABEL))
                    .withEntrypoint(List.of("node")).withCmd(List.of("--floci-test-invalid-node-startup-option"))
                    .withNetworkMode("none").withUser("1000:1000").withReadonlyRootfs()
                    .withCapDrop("ALL").withNoNewPrivileges().withPidsLimit(64).withMemoryMb(128)
                    .withMemorySwapBytes(128L * 1024 * 1024).withCpuUnits(1024).build();
            String id = lifecycle.create(malformed);
            created.set(id);
            return id;
        }).when(failing).create(any());
        runtime = new DockerCloudFrontFunctionRuntime(builder, failing, config, mapper);
        AwsException error = assertThrows(AwsException.class, () -> run("function handler(e){return e.request;}", EVENT));
        assertEquals("TestFunctionFailed", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
        assertNotNull(created.get());
        assertThrows(NotFoundException.class, () -> lifecycle.getDockerClient().inspectContainerCmd(created.get()).exec());
    }

    @Test
    void excessiveAllocationCannotEscapeTheDisposableProcessBoundary() {
        AwsException error = assertThrows(AwsException.class, () -> run("""
                function handler(event) {
                  const values=[];
                  while(true) values.push(new Array(100000).fill('owned-allocation'));
                }
                """, EVENT));
        assertEquals(500, error.getHttpStatus());
        assertTrue(runtime.activeContainers().isEmpty());
    }

    private CloudFrontFunctionRuntime.Result run(String code, String event) {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        CloudFrontFunctionSnapshot snapshot = new CloudFrontFunctionSnapshot("000000000000", "aws",
                AwsArnUtils.Arn.global("aws", "cloudfront", "000000000000", "function/native-runtime").toString(),
                "native-runtime", "DEVELOPMENT", "captured-version", created, created, "UNPUBLISHED",
                "cloudfront-js-2.0", "native runtime fixture", code, List.of());
        return runtime.evaluate(() -> snapshot, event);
    }

    private String awaitContainer() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Map<String, String> active = runtime.activeContainers();
            if (!active.isEmpty()) {
                return active.values().iterator().next();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No owned invocation container was observed");
    }
}
