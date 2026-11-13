package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Uses only a cached Java 17 Lambda image and its own internal bridge; no AWS API calls. */
class ContainerInternalDnsDockerIntegrationTest {
    private static final String PEER = "native-peer.dns.invalid";
    private static final String BUCKET = "native-bucket.s3.eu-west-1.amazonaws.com";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeJvmResolvesBucketAndDockerPeerAtStartupAndRestart(boolean readOnlyRoot, @TempDir Path temporary)
            throws Exception {
        String image = System.getenv("FLOCI_INTERNAL_DNS_TEST_IMAGE");
        assumeTrue(image != null && !image.isBlank(), "Set FLOCI_INTERNAL_DNS_TEST_IMAGE to a cached Java 17 Lambda image");
        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        try (DockerClient docker = DockerClientImpl.getInstance(clientConfig,
                new FlociDockerHttpClient.Builder().dockerHost(clientConfig.getDockerHost())
                        .sslConfig(clientConfig.getSSLConfig()).build())) {
            docker.inspectImageCmd(image).exec();
            String namespace = "dns-test-" + UUID.randomUUID();
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.docker().resourceNamespace()).thenReturn(Optional.of(namespace));
            ImageCacheService images = mock(ImageCacheService.class);
            when(images.ensureImageExists(image)).thenReturn(image);
            ContainerLifecycleManager manager = new ContainerLifecycleManager(
                    docker, images, mock(ContainerDetector.class), config, 0);
            Map<String, String> labels = ContainerStorageHelper.defaultLabels(config);
            String networkName = namespace + "-network";
            String codeVolume = namespace + "-code";
            List<String> containers = new ArrayList<>();
            boolean networkCreated = false;
            boolean codeCreated = false;
            try {
                Path source = temporary.resolve("DnsProbe.java");
                try (InputStream input = getClass().getResourceAsStream("/docker/DnsProbe.java")) {
                    assertNotNull(input);
                    Files.copy(input, source);
                }
                assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                        "--release", "17", source.toString()));
                docker.createNetworkCmd().withName(networkName).withDriver("bridge").withInternal(true)
                        .withLabels(labels).exec();
                networkCreated = true;
                docker.createVolumeCmd().withName(codeVolume).withLabels(labels).exec();
                codeCreated = true;
                HostConfig serverHost = HostConfig.newHostConfig().withNetworkMode(networkName)
                        .withMemory(128L * 1024 * 1024).withNanoCPUs(200_000_000L)
                        .withMounts(List.of(codeMount(codeVolume, false)));
                String server = docker.createContainerCmd(image).withName(namespace + "-server")
                        .withHostConfig(serverHost).withUser("0").withLabels(labels)
                        .withEntrypoint("/var/lang/bin/java")
                        .withCmd("-Xmx32m", "-cp", "/probe", "DnsProbe", "serve").exec().getId();
                containers.add(server);
                RetryingTarCopier.copyBytes(docker, server, "/probe", "DnsProbe.class",
                        Files.readAllBytes(temporary.resolve("DnsProbe.class")), 0644);
                docker.disconnectFromNetworkCmd().withContainerId(server).withNetworkId(networkName).exec();
                docker.connectToNetworkCmd().withContainerId(server).withNetworkId(networkName)
                        .withContainerNetwork(new ContainerNetwork().withAliases(List.of(PEER))).exec();
                docker.startContainerCmd(server).exec();
                String address = docker.inspectContainerCmd(server).exec().getNetworkSettings()
                        .getNetworks().get(networkName).getIpAddress();
                ContainerSpec spec = spec(image, networkName, codeVolume, address, readOnlyRoot);
                HostConfig baselineHost = HostConfig.newHostConfig().withNetworkMode(networkName)
                        .withDns(address).withMounts(List.of(codeMount(codeVolume, true)))
                        .withReadonlyRootfs(readOnlyRoot).withMemory(128L * 1024 * 1024);
                String baseline = docker.createContainerCmd(image).withHostConfig(baselineHost).withUser("1001")
                        .withLabels(labels).withEntrypoint(spec.entrypoint()).withCmd(spec.cmd()).exec().getId();
                containers.add(baseline);
                int baselineExit = startAndWait(docker, baseline);
                String before = logs(docker, baseline);
                assertTrue(before.contains(PEER + "=" + address), before);
                boolean baselineResolvesBucket = before.contains(BUCKET + "=203.0.113.99");
                assertTrue(baselineResolvesBucket || before.contains(BUCKET + "=UNKNOWN"), before);
                assertEquals(baselineResolvesBucket ? 0 : 2, baselineExit, before);

                String fixed = manager.create(spec);
                containers.add(fixed);
                InspectContainerResponse inspected = docker.inspectContainerCmd(fixed).exec();
                assertArrayEquals(spec.entrypoint().toArray(String[]::new), inspected.getConfig().getEntrypoint());
                assertArrayEquals(spec.cmd().toArray(String[]::new), inspected.getConfig().getCmd());
                assertEquals("1001", inspected.getConfig().getUser());
                for (int attempt = 0; attempt < 2; attempt++) {
                    assertEquals(0, startAndWait(docker, fixed));
                    String after = logs(docker, fixed);
                    assertTrue(after.contains(PEER + "=" + address), after);
                    assertTrue(after.contains(BUCKET + "=203.0.113.99"), after);
                }
                String protectedHelper = manager.create(protectedSpec(image, networkName, codeVolume, address));
                containers.add(protectedHelper);
                assertEquals(0, startAndWait(docker, protectedHelper));
                String helperOutput = logs(docker, protectedHelper);
                assertTrue(helperOutput.contains(BUCKET + "=203.0.113.99"), helperOutput);
            } finally {
                for (String container : containers.reversed()) {
                    docker.removeContainerCmd(container).withForce(true).withRemoveVolumes(true).exec();
                }
                manager.stopManagedContainers();
                if (codeCreated) {
                    docker.removeVolumeCmd(codeVolume).exec();
                }
                if (networkCreated) {
                    docker.removeNetworkCmd(networkName).exec();
                }
                assertTrue(docker.listContainersCmd().withShowAll(true)
                        .withLabelFilter(Map.of("floci_namespace", namespace)).exec().isEmpty());
                assertTrue(docker.listVolumesCmd().withFilter("label", List.of("floci_namespace=" + namespace))
                        .exec().getVolumes().isEmpty());
            }
        }
    }

    private static Mount codeMount(String name, boolean readOnly) {
        return new Mount().withType(MountType.VOLUME).withSource(name).withTarget("/probe").withReadOnly(readOnly);
    }

    private static ContainerSpec spec(String image, String network, String code, String dns, boolean readOnlyRoot) {
        return new ContainerSpec(image, null, List.of(),
                List.of("-Xmx32m", "-cp", "/probe", "DnsProbe", "lookup", PEER, BUCKET),
                List.of("/var/lang/bin/java"), 128L * 1024 * 1024, Map.of(), List.of(), List.of(),
                network, List.of(codeMount(code, true)), List.of(), List.of(), List.of(), Map.of(),
                null, false, null, List.of(dns), null, "1001", List.of(), List.of(), 200_000_000L,
                null, readOnlyRoot, List.of(), Map.of(), false);
    }

    private static int startAndWait(DockerClient docker, String container) {
        docker.startContainerCmd(container).exec();
        return docker.waitContainerCmd(container).exec(new WaitContainerResultCallback())
                .awaitStatusCode(30, TimeUnit.SECONDS);
    }

    private static ContainerSpec protectedSpec(String image, String network, String code, String dns) {
        return new ContainerSpec(image, null, List.of(),
                List.of("-c", "printf 'nameserver %s\\noptions ndots:0\\n' \"$1\" > /etc/resolv.conf"
                        + " && exec /var/lang/bin/java -Xmx32m -cp /probe DnsProbe lookup \"$2\"",
                        "floci-vpc-dns", dns, BUCKET),
                List.of("/bin/sh"), 128L * 1024 * 1024, Map.of(), List.of(), List.of(),
                network, List.of(codeMount(code, true)), List.of(), List.of(), List.of(),
                Map.of(ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true"),
                null, false, null, List.of(dns), null, "0", List.of(), List.of(), 200_000_000L,
                null, false, List.of(), Map.of(), false);
    }

    private static String logs(DockerClient docker, String container) throws InterruptedException {
        StringBuilder output = new StringBuilder();
        LogContainerResultCallback callback = new LogContainerResultCallback() {
            @Override
            public void onNext(Frame frame) {
                output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
            }
        };
        assertTrue(docker.logContainerCmd(container).withStdOut(true).withStdErr(true).withTailAll()
                .exec(callback).awaitCompletion(10, TimeUnit.SECONDS));
        return output.toString();
    }
}
