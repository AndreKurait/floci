package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Info;
import io.github.hectorvent.floci.core.common.Digests;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers custom image selection, reuse of the current recipe on the correct platform, stale-cache
 * rebuilding and failure when a build produces no image. Real Docker coverage lives separately.
 */
class LocallyBuiltHelperImageTest {

    private static final String DEFAULT_IMAGE = "floci/network-helper:local";
    private static final String DOCKERFILE = "/docker/network-helper.Dockerfile";
    private static final String CONTEXT_DOCKERFILE = "/docker/native-platform-helper.Dockerfile";

    private DockerClient dockerClient;
    private ContainerBuilder containerBuilder;
    private Info daemon;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        containerBuilder = mock(ContainerBuilder.class);
        daemon = new Info().withArchitecture("x86_64").withOsType("linux");
        InfoCmd info = mock(InfoCmd.class);
        when(info.exec()).thenReturn(daemon);
        when(dockerClient.infoCmd()).thenReturn(info);
    }

    @Test
    void buildsNothingWhenTheCallerIsPointedAtSomeOtherImage() {
        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                "ghcr.io/someone/their-helper:2", DEFAULT_IMAGE, DOCKERFILE);

        // Not even an inspect: that image is the caller's own to make pullable.
        verify(dockerClient, never()).inspectImageCmd(any());
        verify(dockerClient, never()).buildImageCmd(any(InputStream.class));
        verify(containerBuilder, never()).resolveImage(any());
    }

    @Test
    void buildsNothingWhenTheImageHasTheCurrentRecipeAndPlatform() throws Exception {
        stubResolvedImage();
        CachedImageStubs.image(dockerClient, DEFAULT_IMAGE, "linux", "amd64", recipe());

        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                DEFAULT_IMAGE, DEFAULT_IMAGE, DOCKERFILE);

        verify(dockerClient, never()).buildImageCmd(any(InputStream.class));
    }

    @Test
    void buildsFromTheDockerfileWhenTheImageIsMissing() throws Exception {
        stubMissingImage();
        BuildImageCmd build = stubBuildReturning("sha256:built");

        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                DEFAULT_IMAGE, DEFAULT_IMAGE, DOCKERFILE);

        verify(dockerClient).buildImageCmd(any(InputStream.class));
        verify(build).withPlatform("linux/amd64");
        verify(build).withLabels(Map.of(LocallyBuiltHelperImage.RECIPE_LABEL, recipe()));
        verify(build).withPull(true);
    }

    @Test
    void replacesDockerfileOnlyCacheAndSendsDeclaredFilesToDocker() throws Exception {
        stubResolvedImage();
        CachedImageStubs.image(dockerClient, DEFAULT_IMAGE, "linux", "amd64",
                CachedImageStubs.recipe(CONTEXT_DOCKERFILE));
        BuildImageCmd build = stubBuildReturning("sha256:context");

        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                DEFAULT_IMAGE, DEFAULT_IMAGE, CONTEXT_DOCKERFILE);

        ArgumentCaptor<InputStream> captured = ArgumentCaptor.forClass(InputStream.class);
        verify(dockerClient).buildImageCmd(captured.capture());
        Map<String, byte[]> files = new HashMap<>();
        try (TarArchiveInputStream archive = new TarArchiveInputStream(captured.getValue())) {
            TarArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                files.put(entry.getName(), archive.readNBytes(Math.toIntExact(entry.getSize())));
            }
        }
        assertEquals(Set.of("Dockerfile", "helper-context.txt"), files.keySet());
        try (InputStream resource = getClass().getResourceAsStream("/docker/helper-context.txt")) {
            assertArrayEquals(resource.readAllBytes(), files.get("helper-context.txt"));
        }
        verify(build).withLabels(Map.of(LocallyBuiltHelperImage.RECIPE_LABEL,
                LocallyBuiltHelperImage.recipe(files)));
    }

    @Test
    void reusesCacheWhenAllDeclaredFilesAndPlatformMatch() throws Exception {
        stubResolvedImage();
        Map<String, byte[]> context;
        try (InputStream dockerfile = getClass().getResourceAsStream(CONTEXT_DOCKERFILE)) {
            context = LocallyBuiltHelperImage.buildContext(CONTEXT_DOCKERFILE, dockerfile.readAllBytes());
        }
        CachedImageStubs.image(dockerClient, DEFAULT_IMAGE, "linux", "amd64",
                LocallyBuiltHelperImage.recipe(context));

        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                DEFAULT_IMAGE, DEFAULT_IMAGE, CONTEXT_DOCKERFILE);

        verify(dockerClient, never()).buildImageCmd(any(InputStream.class));
    }

    @Test
    void recipeChangesWhenADeclaredFileChangesEvenIfDockerfileDoesNot() {
        byte[] dockerfile = "FROM scratch\n".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new HashMap<>();
        files.put("Dockerfile", dockerfile);
        assertEquals(Digests.sha256Hex(dockerfile), LocallyBuiltHelperImage.recipe(files));
        files.put("message.txt", "hello\n".getBytes(StandardCharsets.UTF_8));
        String recipe = LocallyBuiltHelperImage.recipe(files);
        assertNotEquals(Digests.sha256Hex(dockerfile), recipe);
        files.put("message.txt", "updated\n".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(recipe, LocallyBuiltHelperImage.recipe(files));
    }

    @Test
    void recipeIsOrderIndependentAndMatchesThePublishedBuildVector() {
        Map<String, byte[]> forward = new LinkedHashMap<>();
        forward.put("Dockerfile", "FROM scratch\n".getBytes(StandardCharsets.UTF_8));
        forward.put("message.txt", "hello\n".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> reverse = new LinkedHashMap<>();
        reverse.put("message.txt", forward.get("message.txt"));
        reverse.put("Dockerfile", forward.get("Dockerfile"));
        assertEquals(LocallyBuiltHelperImage.recipe(forward), LocallyBuiltHelperImage.recipe(reverse));
        assertEquals("9ebbd75b37d6c7247eb4d72616765a848b1482e313ae89048ed1ccbf30b16b30", LocallyBuiltHelperImage.recipe(forward));
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "unsafe", "missing"})
    void rejectsInvalidDeclaredContextBeforeDockerBuild(String fixture) {
        assertThrows(IOException.class, () -> LocallyBuiltHelperImage.buildContext(
                "/docker/" + fixture + "-context.Dockerfile", "FROM scratch\n".getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @CsvSource({"missing", "old-recipe"})
    void replacesNativeHelperFromAnOlderRecipe(String previous) {
        stubResolvedImage();
        CachedImageStubs.image(dockerClient, DEFAULT_IMAGE, "linux", "amd64",
                "missing".equals(previous) ? null : previous);
        BuildImageCmd build = stubBuildReturning("sha256:rebuilt");

        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                DEFAULT_IMAGE, DEFAULT_IMAGE, DOCKERFILE);

        verify(build).withPlatform("linux/amd64");
    }

    @ParameterizedTest
    @CsvSource({"x86_64,arm64,linux/amd64", "aarch64,amd64,linux/arm64"})
    void replacesForeignCachedHelperWithTheDaemonPlatform(String host, String cached, String expected) throws Exception {
        daemon.withArchitecture(host);
        stubResolvedImage();
        CachedImageStubs.image(dockerClient, DEFAULT_IMAGE, "linux", cached, recipe());
        BuildImageCmd build = stubBuildReturning("sha256:rebuilt");

        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                DEFAULT_IMAGE, DEFAULT_IMAGE, DOCKERFILE);

        verify(build).withPlatform(expected);
    }

    @Test
    void failsWhenTheBuildProducesNoImageId() {
        stubMissingImage();
        stubBuildReturning(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                        DEFAULT_IMAGE, DEFAULT_IMAGE, DOCKERFILE));

        assertTrue(thrown.getMessage().contains(DEFAULT_IMAGE), thrown.getMessage());
    }

    @Test
    void failsLoudlyWhenTheDockerfileIsNotOnTheClasspath() {
        stubMissingImage();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                        DEFAULT_IMAGE, DEFAULT_IMAGE, "/docker/does-not-exist.Dockerfile"));

        assertTrue(thrown.getMessage().contains("/docker/does-not-exist.Dockerfile"), thrown.getMessage());
        verify(dockerClient, never()).buildImageCmd(any(InputStream.class));
    }

    private void stubResolvedImage() {
        when(containerBuilder.resolveImage(DEFAULT_IMAGE)).thenReturn(DEFAULT_IMAGE);
    }

    private static String recipe() {
        return CachedImageStubs.recipe(DOCKERFILE);
    }

    private void stubMissingImage() {
        stubResolvedImage();
        InspectImageCmd inspectCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(DEFAULT_IMAGE)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new NotFoundException("no such image"));
    }

    /**
     * Returns a stub callback rather than the one the helper passes in: awaiting the real
     * {@link BuildImageResultCallback} would block, since no daemon ever feeds it events.
     */
    private BuildImageCmd stubBuildReturning(String imageId) {
        BuildImageCmd buildCmd = mock(BuildImageCmd.class, RETURNS_SELF);
        when(dockerClient.buildImageCmd(any(InputStream.class))).thenReturn(buildCmd);
        BuildImageResultCallback result = mock(BuildImageResultCallback.class);
        when(result.awaitImageId()).thenReturn(imageId);
        when(buildCmd.exec(any())).thenReturn(result);
        return buildCmd;
    }
}
