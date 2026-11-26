package io.floci.aws.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerRejectedException;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The image's first process reads real IMDS and mounted inputs before and after a guest restart. */
@QuarkusTest
class Ec2ConfiguredGuestStartupDockerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STARTUP = """
            import errno, json, os, resource, signal, sys, time
            from pathlib import Path
            from urllib.request import ProxyHandler, Request, build_opener
            from urllib.parse import urlencode
            import xml.etree.ElementTree as ET
            signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
            opener = build_opener(ProxyHandler({}))
            endpoint = os.environ['AWS_EC2_METADATA_SERVICE_ENDPOINT']
            token = opener.open(Request(endpoint + '/latest/api/token', method='PUT',
                headers={'X-aws-ec2-metadata-token-ttl-seconds': '60'}), timeout=5).read().decode()
            instance = opener.open(Request(endpoint + '/latest/meta-data/instance-id',
                headers={'X-aws-ec2-metadata-token': token}), timeout=5).read().decode()
            account = os.environ['CUSTOM_ACCOUNT']
            auth = 'AWS4-HMAC-SHA256 Credential=' + account + '/20260629/us-east-1/sts/aws4_request, SignedHeaders=host, Signature=abc'
            identity = opener.open(Request(os.environ['AWS_ENDPOINT_URL'],
                data=urlencode({'Action': 'GetCallerIdentity', 'Version': '2011-06-15'}).encode(),
                headers={'Authorization': auth, 'Content-Type': 'application/x-www-form-urlencoded'}), timeout=5).read()
            observed_account = ET.fromstring(identity).find('.//{*}Account').text
            assert observed_account == account, identity
            source = Path('/startup/input')
            content = source.read_text()
            try:
                source.write_text('must not succeed')
                raise AssertionError('startup input was writable')
            except OSError as error:
                assert error.errno == errno.EROFS, repr(error)
            result = Path('/startup-result.json')
            boot = json.loads(result.read_text())['boot'] + 1 if result.exists() else 1
            result.write_text(json.dumps({'boot': boot, 'input': content, 'instance': instance,
                'arguments': sys.argv[1:], 'environment': os.environ['CUSTOM_MODE'],
                'limits': resource.getrlimit(resource.RLIMIT_NOFILE),
                'endpoint': os.environ['AWS_ENDPOINT_URL'], 'account': observed_account, 'token': bool(token)}))
            while True:
                time.sleep(60)
            """;

    @Inject DockerClient docker;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject DockerHostResolver hostResolver;
    @Inject PortAllocator ports;
    @Inject EmulatorConfig config;
    @Inject RegionResolver regions;
    @TestHTTPResource URI api;
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void imageEntrypointReadsMetadataAndReadOnlyConfigurationOnEveryStart(boolean customResolver) throws Exception {
        assumeTrue(DockerAvailability.answers(docker), "Docker must be available");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Path input = directory.resolve("input with spaces,commas");
        Files.writeString(input, "first");
        Path resolver = directory.resolve("resolv.conf");
        Files.writeString(resolver, "nameserver 192.0.2.1\noptions attempts:1 timeout:1\n");
        EmulatorConfig guestConfig = mock(EmulatorConfig.class, delegatesTo(config));
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class,
                delegatesTo(config.services()));
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class,
                delegatesTo(config.services().ec2()));
        when(guestConfig.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.extraEnv()).thenReturn(Optional.of(List.of("CUSTOM_MODE=ready",
                "CUSTOM_ACCOUNT=" + config.defaultAccountId())));
        when(ec2.ulimits()).thenReturn(Optional.of(List.of("nofile=1024:2048")));
        List<EmulatorConfig.Ec2ServiceConfig.GuestReadOnlyBind> binds = new ArrayList<>();
        binds.add(bind(input, "/startup/input"));
        if (customResolver) {
            binds.add(bind(resolver, "/etc/resolv.conf"));
        }
        when(ec2.readOnlyBinds()).thenReturn(binds);
        EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        ContainerBuilder builder = new ContainerBuilder(guestConfig, hostResolver, dns);
        ContainerReachableEndpoint endpoint = new ContainerReachableEndpoint(guestConfig, hostResolver, dns,
                null, api.getPort());
        Files.writeString(directory.resolve("startup.py"), STARTUP);
        Files.writeString(directory.resolve("Dockerfile"), "FROM " + TestImages.PYTHON_ALPINE
                + "\nCOPY startup.py /startup.py\nENTRYPOINT [\"python3\",\"/startup.py\"]"
                + "\nCMD [\"image-owned-argument\"]\n");
        String image;
        try (BuildImageResultCallback build = new BuildImageResultCallback()) {
            image = docker.buildImageCmd(directory.toFile()).withTags(Set.of("floci-guest-startup-test:" + suffix))
                    .exec(build).awaitImageId();
        }
        Instance instance = new Instance();
        instance.setInstanceId("i-startup-" + suffix);
        instance.setOwnerId(config.defaultAccountId());
        instance.setRegion("us-east-1");
        String helper = null;
        try (Ec2MetadataTestFixture metadata = new Ec2MetadataTestFixture()) {
            metadata.startUnregistered(guestConfig);
            Ec2ContainerManager manager = new Ec2ContainerManager(builder, lifecycle,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), hostResolver, docker,
                    ports, guestConfig, metadata.server(), mock(Ec2PortForwardManager.class), regions,
                    mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class), endpoint);
            try {
                manager.launch(instance, new ResolvedAmiImage(image, ResolvedAmiImage.IMAGE_RUNTIME, false, null),
                        null, "us-east-1");
                await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertStartup(instance, "first", 1,
                        customResolver ? endpoint.baseUrl(false) : endpoint.baseUrl()));
                String mode = docker.inspectContainerCmd(instance.getDockerContainerId()).exec()
                        .getHostConfig().getNetworkMode();
                assertTrue(mode.startsWith("container:"));
                helper = mode.substring("container:".length());
                manager.stop(instance);
                await().atMost(Duration.ofSeconds(45)).until(() -> "stopped".equals(instance.getState().getName()));
                Files.writeString(input, "second");
                manager.start(instance);
                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertStartup(instance, "second", 2,
                        customResolver ? endpoint.baseUrl(false) : endpoint.baseUrl()));
                assertEquals("second", Files.readString(input));
            } finally {
                try {
                    CountDownLatch removed = new CountDownLatch(1);
                    manager.terminate(instance, removed::countDown);
                    assertTrue(removed.await(60, TimeUnit.SECONDS), "guest and metadata helper cleanup timed out");
                } finally {
                    manager.stop();
                }
            }
        } finally {
            if (instance.getDockerContainerId() != null) {
                lifecycle.removeIfExistsStrict(instance.getDockerContainerId());
            }
            if (helper != null) {
                lifecycle.removeIfExistsStrict(helper);
            }
            docker.removeImageCmd(image).withForce(true).exec();
        }
    }

    @Test
    void missingReadOnlySourceRefusesLaunchWithoutCreatingTheHostPath() throws Exception {
        assumeTrue(DockerAvailability.answers(docker), "Docker must be available");
        Path missing = directory.resolve("missing-parent").resolve("missing-input");
        EmulatorConfig guestConfig = mock(EmulatorConfig.class, delegatesTo(config));
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class,
                delegatesTo(config.services()));
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class,
                delegatesTo(config.services().ec2()));
        when(guestConfig.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        List<EmulatorConfig.Ec2ServiceConfig.GuestReadOnlyBind> binds = List.of(bind(missing, "/startup/input"));
        when(ec2.readOnlyBinds()).thenReturn(binds);
        EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        ContainerBuilder builder = new ContainerBuilder(guestConfig, hostResolver, dns);
        ContainerReachableEndpoint endpoint = new ContainerReachableEndpoint(guestConfig, hostResolver, dns,
                null, api.getPort());
        AtomicReference<ContainerRejectedException> rejection = new AtomicReference<>();
        ContainerLifecycleManager guestLifecycle = mock(ContainerLifecycleManager.class, delegatesTo(lifecycle));
        doAnswer(invocation -> {
            try {
                return lifecycle.create(invocation.getArgument(0, ContainerSpec.class));
            } catch (ContainerRejectedException failure) {
                rejection.set(failure);
                throw failure;
            }
        }).when(guestLifecycle).create(any(ContainerSpec.class));
        Instance instance = new Instance();
        instance.setInstanceId("i-missing-" + UUID.randomUUID().toString().replace("-", ""));
        instance.setOwnerId(config.defaultAccountId());
        instance.setRegion("us-east-1");
        try (Ec2MetadataTestFixture metadata = new Ec2MetadataTestFixture()) {
            metadata.startUnregistered(guestConfig);
            Ec2ContainerManager manager = new Ec2ContainerManager(builder, guestLifecycle,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), hostResolver, docker,
                    ports, guestConfig, metadata.server(), mock(Ec2PortForwardManager.class), regions,
                    mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class), endpoint);
            try {
                manager.launch(instance, new ResolvedAmiImage(TestImages.PYTHON_ALPINE,
                        ResolvedAmiImage.IMAGE_RUNTIME, false, null), null, "us-east-1");
                await().atMost(Duration.ofSeconds(90))
                        .until(() -> "terminated".equals(instance.getState().getName()));
                assertNotNull(rejection.get(), "launch must reach Docker's mount validation after preparing IMDS");
                assertTrue(rejection.get().getMessage().contains("bind source path does not exist"));
                assertTrue(rejection.get().getMessage().contains(missing.toString()));
                verify(guestLifecycle, never()).startCreated(anyString(), any(ContainerSpec.class));
                assertFalse(Files.exists(missing));
                assertFalse(Files.exists(missing.getParent()));
                await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertTrue(
                        docker.listContainersCmd().withShowAll(true)
                                .withLabelFilter(Map.of(Ec2ContainerManager.LABEL_RESOURCE_ID,
                                        instance.getInstanceId())).exec().isEmpty(),
                        "the refused launch must remove its metadata namespace"));
            } finally {
                manager.stop();
            }
        }
    }

    private void assertStartup(Instance instance, String input, int boot, String endpoint) throws Exception {
        assertEquals("running", instance.getState().getName());
        ContainerExec.Result read = ContainerExec.runMerged(docker, instance.getDockerContainerId(),
                new String[]{"cat", "/startup-result.json"}, 10);
        assertEquals(0, read.exitCode(), read.summary());
        JsonNode result = JSON.readTree(read.stdout());
        assertEquals(boot, result.path("boot").asInt());
        assertEquals(input, result.path("input").asText());
        assertEquals(instance.getInstanceId(), result.path("instance").asText());
        assertTrue(result.path("token").asBoolean());
        assertEquals(List.of("image-owned-argument"), JSON.convertValue(result.path("arguments"), List.class));
        assertEquals("ready", result.path("environment").asText());
        assertEquals(1024, result.path("limits").get(0).asInt());
        assertEquals(2048, result.path("limits").get(1).asInt());
        assertEquals(endpoint, result.path("endpoint").asText());
        assertEquals(config.defaultAccountId(), result.path("account").asText());
        assertFalse(result.path("input").asText().contains("must not succeed"));
    }

    private static EmulatorConfig.Ec2ServiceConfig.GuestReadOnlyBind bind(Path source, String target) {
        EmulatorConfig.Ec2ServiceConfig.GuestReadOnlyBind bind = mock(EmulatorConfig.Ec2ServiceConfig.GuestReadOnlyBind.class);
        when(bind.source()).thenReturn(source.toString());
        when(bind.target()).thenReturn(target);
        return bind;
    }
}
