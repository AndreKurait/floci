package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

/** One durable pending exec per exact helper; never infer that a missing record is clean. */
class PolicyWriterJournal {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final int MAX_RECORD_BYTES = 8192;
    private static final Set<String> FIELDS = Set.of("version", "owner", "helperId", "state", "execId");
    private final EmulatorConfig config;
    private final String owner;

    PolicyWriterJournal(EmulatorConfig config) {
        this.config = config;
        this.owner = ContainerStorageHelper.ownerIdentity(config);
    }

    /** Only the caller that just created this exact Docker helper may establish its first clean state. */
    void initialize(String helperId) {
        try (Lease lease = lock(helperId)) {
            if (Files.exists(lease.record, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Policy writer record already exists for " + helperId);
            }
            lease.write(null);
        }
    }

    Lease acquire(String helperId) {
        Lease lease = lock(helperId);
        try {
            lease.read();
            return lease;
        } catch (RuntimeException failure) {
            try {
                lease.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** The caller has confirmed removal of the exact helper, not just a successful stop response. */
    void forget(String helperId) {
        Path directory = directory();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Lease lease = lock(helperId)) {
            Files.deleteIfExists(lease.record);
            Files.deleteIfExists(lease.temporary);
            forceDirectory(directory);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not remove policy writer record for " + helperId, failure);
        }
        try {
            Files.deleteIfExists(directory.resolve(helperId + ".lock"));
            forceDirectory(directory);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not remove policy writer lock for " + helperId, failure);
        }
    }

    List<String> helpers() {
        Path directory = directory();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json") || name.endsWith(".tmp") || name.endsWith(".lock"))
                    .map(name -> name.substring(0, name.lastIndexOf('.')))
                    .peek(PolicyWriterJournal::identity).distinct().sorted().toList();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not list policy writer records", failure);
        }
    }

    private Path directory() {
        String persistent = config.storage().persistentPath();
        if (persistent == null || persistent.isBlank()) {
            throw new IllegalStateException("Security-group writer fencing requires the persistent data path");
        }
        try {
            String ownerKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner.getBytes(UTF_8)));
            return Path.of(persistent).toAbsolutePath().resolve("security-group-writers").resolve(ownerKey);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private Lease lock(String helperId) {
        identity(helperId);
        Path directory = directory();
        FileChannel channel = null;
        try {
            Files.createDirectories(directory);
            // Persist the owner directory and its entry under the existing durable data root.
            forceDirectory(directory);
            forceDirectory(directory.getParent());
            forceDirectory(directory.getParent().getParent());
            channel = FileChannel.open(directory.resolve(helperId + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IllegalStateException("Another process owns the policy writer for " + helperId);
            }
            return new Lease(helperId, directory, channel, lock);
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw new IllegalStateException("Could not own policy writer for " + helperId, failure);
        }
    }

    private static void identity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalStateException("Invalid exact policy writer identity");
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    class Lease implements AutoCloseable {
        private final String helperId;
        private final Path directory;
        private final Path record;
        private final Path temporary;
        private final FileChannel channel;
        private final FileLock lock;
        private String pendingExecId;

        private Lease(String helperId, Path directory, FileChannel channel, FileLock lock) {
            this.helperId = helperId;
            this.directory = directory;
            this.record = directory.resolve(helperId + ".json");
            this.temporary = directory.resolve(helperId + ".tmp");
            this.channel = channel;
            this.lock = lock;
        }

        String pendingExecId() {
            return pendingExecId;
        }

        void started(String execId) {
            identity(execId);
            if (pendingExecId != null) {
                throw new IllegalStateException("A policy writer is already pending for " + helperId);
            }
            write(execId);
        }

        void completed(String execId) {
            if (execId == null || !execId.equals(pendingExecId)) {
                throw new IllegalStateException("Policy writer identity changed for " + helperId);
            }
            write(null);
        }

        private void read() {
            try (InputStream input = Files.newInputStream(record, LinkOption.NOFOLLOW_LINKS)) {
                byte[] content = input.readNBytes(MAX_RECORD_BYTES + 1);
                if (content.length > MAX_RECORD_BYTES) {
                    throw new IllegalStateException("Policy writer record is too large");
                }
                JsonNode value = JSON.readTree(content);
                if (value == null || !value.isObject() || value.size() != FIELDS.size()
                        || !value.has("version") || !value.get("version").isIntegralNumber()
                        || !value.get("version").canConvertToInt()
                        || value.get("version").intValue() != 1
                        || !owner.equals(value.path("owner").textValue())
                        || !helperId.equals(value.path("helperId").textValue())
                        || !value.has("execId")) {
                    throw new IllegalStateException("Invalid policy writer ownership record");
                }
                if ("ready".equals(value.path("state").textValue()) && value.get("execId").isNull()) {
                    pendingExecId = null;
                } else if ("pending".equals(value.path("state").textValue())) {
                    pendingExecId = value.get("execId").textValue();
                    identity(pendingExecId);
                } else {
                    throw new IllegalStateException("Invalid policy writer record state");
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read policy writer record for " + helperId, failure);
            }
        }

        private void write(String execId) {
            try {
                byte[] content = JSON.writeValueAsBytes(new Entry(1, owner, helperId,
                        execId == null ? "ready" : "pending", execId));
                if (content.length > MAX_RECORD_BYTES) {
                    throw new IllegalStateException("Policy writer record is too large");
                }
                try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer bytes = ByteBuffer.wrap(content);
                    while (bytes.hasRemaining()) {
                        output.write(bytes);
                    }
                    output.force(true);
                }
                Files.move(temporary, record, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                forceDirectory(directory);
                pendingExecId = execId;
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot persist policy writer record for " + helperId, failure);
            }
        }

        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException failure) {
                throw new IllegalStateException("Could not release policy writer lock for " + helperId, failure);
            } finally {
                try {
                    channel.close();
                } catch (IOException failure) {
                    throw new IllegalStateException("Could not close policy writer lock for " + helperId, failure);
                }
            }
        }
    }

    private record Entry(int version, String owner, String helperId, String state, String execId) {}
}
