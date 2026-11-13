package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.core.common.Digests;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a Floci-recipe helper image for the workload daemon's platform when needed,
 * instead of trying to pull an image that only exists as a Dockerfile checked into Floci itself.
 * Security-group enforcement is the caller today; this exists as a shared utility because a second
 * one is coming (an ECS task-role credentials proxy needs the same image), and the alternative was
 * a verbatim copy of the build alongside the original. The Dockerfile-to-image build handling, and
 * the guard that only builds when the caller is still pointed at Floci's own default, live here.
 */
public final class LocallyBuiltHelperImage {

    static final String RECIPE_LABEL = "io.floci.helper.recipe-sha256";

    private LocallyBuiltHelperImage() {
    }

    /**
     * @param configuredImage the image the caller is actually configured to use
     * @param defaultImage the caller's own built-in default; a caller pointed anywhere else is
     *        responsible for that image being pullable, so nothing is built for it here
     * @param dockerfileResourcePath classpath location of the Dockerfile to build, e.g.
     *        {@code "/docker/network-helper.Dockerfile"}. An optional sibling {@code .files}
     *        manifest lists additional files from the same directory.
     */
    public static synchronized void ensureBuilt(DockerClient dockerClient, ContainerBuilder containerBuilder,
                                                String configuredImage, String defaultImage,
                                                String dockerfileResourcePath) {
        if (!defaultImage.equals(configuredImage)) {
            return;
        }
        String image = containerBuilder.resolveImage(configuredImage);
        String platform = DockerPlatform.daemon(dockerClient);
        try (InputStream dockerfile = LocallyBuiltHelperImage.class.getResourceAsStream(dockerfileResourcePath)) {
            if (dockerfile == null) {
                throw new IllegalStateException("Floci helper Dockerfile is missing: " + dockerfileResourcePath);
            }
            byte[] content = dockerfile.readAllBytes();
            Map<String, byte[]> context = buildContext(dockerfileResourcePath, content);
            String recipe = recipe(context);
            try {
                InspectImageResponse cached = dockerClient.inspectImageCmd(image).exec();
                if (DockerPlatform.matches(cached, platform) && cached.getConfig() != null
                        && cached.getConfig().getLabels() != null
                        && recipe.equals(cached.getConfig().getLabels().get(RECIPE_LABEL))) {
                    return;
                }
            } catch (NotFoundException missing) {
                // Build the current Floci recipe in the daemon used for workloads.
            }
            ByteArrayOutputStream archive = new ByteArrayOutputStream(content.length + 1024);
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
                for (Map.Entry<String, byte[]> file : context.entrySet()) {
                    TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                    entry.setSize(file.getValue().length);
                    tar.putArchiveEntry(entry);
                    tar.write(file.getValue());
                    tar.closeArchiveEntry();
                }
            }
            try (BuildImageResultCallback callback = new BuildImageResultCallback()) {
                String built = dockerClient.buildImageCmd(new ByteArrayInputStream(archive.toByteArray()))
                        .withTags(Set.of(image)).withPlatform(platform).withPull(true)
                        .withLabels(Map.of(RECIPE_LABEL, recipe)).exec(callback).awaitImageId();
                if (built == null || built.isBlank()) {
                    throw new IllegalStateException("Docker did not build the Floci helper image: " + image);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build the Floci helper image: " + image, e);
        }
    }

    static Map<String, byte[]> buildContext(String dockerfile, byte[] content) throws IOException {
        Map<String, byte[]> context = new TreeMap<>();
        context.put("Dockerfile", content);
        try (InputStream manifest = LocallyBuiltHelperImage.class.getResourceAsStream(dockerfile + ".files")) {
            if (manifest == null) {
                return context;
            }
            String directory = dockerfile.substring(0, dockerfile.lastIndexOf('/') + 1);
            for (String line : new String(manifest.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String name = line.strip();
                if (name.isEmpty()) {
                    continue;
                }
                if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || context.containsKey(name)) {
                    throw new IOException("Invalid helper build-context filename: " + name);
                }
                try (InputStream file = LocallyBuiltHelperImage.class.getResourceAsStream(directory + name)) {
                    if (file == null) {
                        throw new IOException("Missing helper build-context file: " + directory + name);
                    }
                    context.put(name, file.readAllBytes());
                }
            }
        }
        return context;
    }

    static String recipe(Map<String, byte[]> context) {
        if (context.size() == 1) {
            return Digests.sha256Hex(context.get("Dockerfile"));
        }
        StringBuilder manifest = new StringBuilder();
        new TreeMap<>(context).forEach((name, content) ->
                manifest.append(name).append('\0').append(Digests.sha256Hex(content)).append('\n'));
        return Digests.sha256Hex(manifest.toString().getBytes(StandardCharsets.UTF_8));
    }
}
