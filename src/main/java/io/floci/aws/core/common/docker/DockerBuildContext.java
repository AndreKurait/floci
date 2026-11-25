package io.floci.aws.core.common.docker;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** The tar build context Docker's build API takes. */
public final class DockerBuildContext {

    private DockerBuildContext() {
    }

    public static byte[] ofDockerfile(byte[] dockerfile) {
        return ofFiles(Map.of("Dockerfile", dockerfile));
    }

    public static byte[] ofFiles(Map<String, byte[]> files) {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(file.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the Docker build context", e);
        }
        return archive.toByteArray();
    }
}
