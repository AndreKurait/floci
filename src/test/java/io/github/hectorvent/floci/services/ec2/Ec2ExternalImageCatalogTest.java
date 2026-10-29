package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Ec2ExternalImageCatalogTest {
    @TempDir
    Path directory;

    @Test
    void externalCatalogSelectsLocalSystemdImageWithoutChangingBundledCatalog() throws Exception {
        Path catalog = directory.resolve("images.yaml");
        Files.writeString(catalog, """
                defaultDockerImage: example/base:1
                images:
                  - imageId: ami-local-worker
                    aliases: [ami-worker-alias]
                    dockerImage: example/worker:1
                    name: local-worker
                    description: local worker
                    architecture: arm64
                    creationDate: '2025-11-05T00:00:00.000Z'
                    guestRuntime: systemd
                """);
        Ec2ImageCatalog external = new Ec2ImageCatalog(catalog);
        assertEquals("example/worker:1", external.findByIdOrAlias("ami-worker-alias").orElseThrow().dockerImage);
        assertEquals("systemd", external.findByIdOrAlias("ami-local-worker").orElseThrow().guestRuntime);
        assertEquals("example/base:1", external.defaultDockerImage());
        assertTrue(external.findByIdOrAlias("ami-amazonlinux2023").isEmpty());
        assertTrue(new Ec2ImageCatalog().findByIdOrAlias("ami-amazonlinux2023").isPresent());
        assertTrue(new Ec2ImageCatalog().findByIdOrAlias("ami-local-worker").isEmpty());
    }

    @Test
    void additiveCatalogRetainsPublicAmiSelectionAlongsideLocalRuntimeBindings() throws Exception {
        Ec2ImageCatalog catalog = extension("", "");
        Ec2ImageCatalog bundled = new Ec2ImageCatalog();
        AmiImageResolver resolver = new AmiImageResolver(catalog);

        assertEquals(bundled.defaultDockerImage(), catalog.defaultDockerImage());
        assertEquals(bundled.images().size() + 1, catalog.images().size());
        assertEquals(bundled.publicParameterNames(), catalog.publicParameterNames());
        for (String architecture : List.of("x86_64", "arm64")) {
            String parameter = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-" + architecture;
            Ec2ImageCatalog.CatalogImage image = catalog.findByPublicParameterName(parameter).orElseThrow();
            assertEquals(bundled.findByPublicParameterName(parameter).orElseThrow().imageId, image.imageId);
            assertEquals(architecture, image.architecture);
            assertEquals("arm64".equals(architecture) ? "linux/arm64" : "linux/amd64",
                    resolver.resolveImage(image.imageId).dockerPlatform());
            assertFalse(resolver.resolveImage(image.imageId).imageRuntime());
        }
        Ec2ImageCatalog.CatalogImage worker = catalog.findByRegistrationName("worker-release").orElseThrow();
        assertEquals("ami-local-worker", worker.imageId);
        assertEquals(new ResolvedAmiImage("example/worker:1", ResolvedAmiImage.IMAGE_RUNTIME, false, "linux/arm64"),
                resolver.resolveImage("ami-worker-alias"));
        assertTrue(bundled.findByIdOrAlias(worker.imageId).isEmpty());
    }

    @Test
    void additiveCatalogCanSetFallbackWithoutReplacingBundledImages() throws Exception {
        Ec2ImageCatalog catalog = extension("defaultDockerImage: example/fallback:1\n", "");
        AmiImageResolver resolver = new AmiImageResolver(catalog);

        assertEquals(ResolvedAmiImage.minimal("example/fallback:1"), resolver.resolveImage("ami-unknown"));
        assertEquals(new AmiImageResolver(new Ec2ImageCatalog()).resolveImage("ami-amazonlinux2023"),
                resolver.resolveImage("ami-amazonlinux2023"));
    }

    @Test
    void additiveCatalogWithNoEntriesRetainsTheBundledCatalog() throws Exception {
        Path file = directory.resolve("no-additions.yaml");
        Files.writeString(file, "includeDefaultImages: true\nimages: []\n");
        Ec2ImageCatalog catalog = new Ec2ImageCatalog(file);
        Ec2ImageCatalog bundled = new Ec2ImageCatalog();

        assertEquals(bundled.defaultDockerImage(), catalog.defaultDockerImage());
        assertEquals(bundled.images().size(), catalog.images().size());
        assertEquals(bundled.publicParameterNames(), catalog.publicParameterNames());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "alias", "public-parameter"})
    void additiveCatalogRejectsAmbiguousBindingsToBundledImages(String collision) throws Exception {
        Ec2ImageCatalog.CatalogImage builtin =
                new Ec2ImageCatalog().findByIdOrAlias("ami-amazonlinux2023").orElseThrow();
        String field = switch (collision) {
            case "id" -> "imageId: " + builtin.imageId;
            case "alias" -> "aliases: [ami-amazonlinux2023]";
            default -> "publicParameterNames: [" + builtin.publicParameterNames().getFirst() + "]";
        };
        Ec2ImageCatalog catalog = extension("defaultDockerImage: example/fallback:1\n", field);

        IllegalStateException error = assertThrows(IllegalStateException.class, catalog::images);
        assertTrue(error.getMessage().contains("Duplicate EC2 image catalog"), error.getMessage());
    }

    @Test
    void additiveCatalogDoesNotSilentlyReplaceAnExplicitBlankFallback() throws Exception {
        Ec2ImageCatalog catalog = extension("defaultDockerImage: ' '\n", "");

        IllegalStateException error = assertThrows(IllegalStateException.class, catalog::images);
        assertTrue(error.getMessage().contains("defaultDockerImage"));
    }

    private Ec2ImageCatalog extension(String settings, String field) throws Exception {
        Path file = directory.resolve("extension.yaml");
        String id = field.startsWith("imageId: ") ? field.substring("imageId: ".length()) : "ami-local-worker";
        String aliases = field.startsWith("aliases: ") ? field : "aliases: [ami-worker-alias]";
        String parameter = field.startsWith("publicParameterNames: ") ? "    " + field + "\n" : "";
        Files.writeString(file, settings + """
                includeDefaultImages: true
                images:
                  - imageId: %s
                    %s
                    dockerImage: example/worker:1
                    name: local-worker
                    description: local worker
                    architecture: arm64
                    creationDate: '2026-10-08T00:00:00.000Z'
                    guestRuntime: image
                    registrationNames: [worker-release]
                """.formatted(id, aliases) + parameter);
        return new Ec2ImageCatalog(file);
    }

    @Test
    void i386CatalogImageLaunchesOnTheLinux386Platform() throws Exception {
        Path catalog = directory.resolve("i386.yaml");
        Files.writeString(catalog, """
                defaultDockerImage: example/base:1
                images:
                  - imageId: ami-local-i386
                    dockerImage: i386/alpine:3
                    name: local-i386
                    description: local i386
                    architecture: i386
                    creationDate: 2025-11-05T00:00:00.000Z
                """);
        AmiImageResolver resolver = new AmiImageResolver(new Ec2ImageCatalog(catalog));
        assertEquals("linux/386", resolver.resolveImage("ami-local-i386").dockerPlatform());
    }

    @Test
    void missingOrInvalidFileFailsInsteadOfSilentlyUsingBundledImages() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> new Ec2ImageCatalog(directory.resolve("missing.yaml")).images());
        Path invalid = directory.resolve("invalid.yaml");
        Files.writeString(invalid, "images: [not-valid");
        assertThrows(IllegalStateException.class, () -> new Ec2ImageCatalog(invalid).images());
    }

    @Test
    void externalImageRuntimeSurvivesResolutionWithoutChangingDefaultRuntime() throws Exception {
        Path catalog = directory.resolve("image-runtime.yaml");
        Files.writeString(catalog, """
                defaultDockerImage: example/base:1
                images:
                  - imageId: ami-local-worker
                    aliases: [ami-worker-alias]
                    dockerImage: example/worker:1
                    name: local-worker
                    description: local worker
                    architecture: arm64
                    creationDate: '2025-11-05T00:00:00.000Z'
                    guestRuntime: image
                    registrationNames: [worker-release, worker-next]
                """);
        Ec2ImageCatalog external = new Ec2ImageCatalog(catalog);
        AmiImageResolver resolver = new AmiImageResolver(external);

        ResolvedAmiImage image = resolver.resolveImage("ami-worker-alias");
        assertEquals("example/worker:1", image.dockerImage());
        assertEquals(ResolvedAmiImage.IMAGE_RUNTIME, image.guestRuntime());
        assertEquals("linux/arm64", image.dockerPlatform());
        assertTrue(image.imageRuntime());
        assertFalse(image.systemd());
        assertFalse(image.cloudInit());
        assertEquals("ami-local-worker", external.findByRegistrationName("worker-release").orElseThrow().imageId);
        assertEquals("ami-local-worker", external.findByRegistrationName("worker-next").orElseThrow().imageId);
        assertTrue(external.findByRegistrationName("Worker-release").isEmpty());
        assertTrue(external.findByRegistrationName("worker-release-extra").isEmpty());
        assertTrue(external.findByRegistrationName("local-worker").isEmpty());

        ResolvedAmiImage fallback = resolver.resolveImage("ami-unknown");
        assertEquals("example/base:1", fallback.dockerImage());
        assertEquals(ResolvedAmiImage.DEFAULT_RUNTIME, fallback.guestRuntime());
        assertFalse(fallback.imageRuntime());
    }

    @Test
    void ambiguousRegistrationNamesFailCatalogAdmission() {
        Ec2ImageCatalog.Catalog catalog = new Ec2ImageCatalog.Catalog();
        catalog.defaultDockerImage = "example/base:1";
        catalog.images = List.of(registrationImage("ami-one", List.of("same-name")),
                registrationImage("ami-two", List.of("same-name")));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new Ec2ImageCatalog(catalog));
        assertTrue(error.getMessage().contains("Duplicate EC2 image catalog registration name"));
    }

    @Test
    void blankRegistrationNamesFailCatalogAdmission() {
        for (String name : Arrays.asList("", "  ", null)) {
            Ec2ImageCatalog.Catalog catalog = new Ec2ImageCatalog.Catalog();
            catalog.defaultDockerImage = "example/base:1";
            catalog.images = List.of(registrationImage("ami-one", Collections.singletonList(name)));

            IllegalStateException error = assertThrows(IllegalStateException.class, () -> new Ec2ImageCatalog(catalog));
            assertTrue(error.getMessage().contains("registrationNames"));
        }
    }

    @Test
    void absentRegistrationNamesDoNotInferABindingFromTheImageName() {
        Ec2ImageCatalog.Catalog catalog = new Ec2ImageCatalog.Catalog();
        catalog.defaultDockerImage = "example/base:1";
        catalog.images = List.of(registrationImage("ami-one", null));

        assertTrue(new Ec2ImageCatalog(catalog).findByRegistrationName("local-worker").isEmpty());
    }

    private static Ec2ImageCatalog.CatalogImage registrationImage(String id, List<String> names) {
        Ec2ImageCatalog.CatalogImage image = new Ec2ImageCatalog.CatalogImage();
        image.imageId = id;
        image.dockerImage = "example/worker:1";
        image.name = "local-worker";
        image.description = "local worker";
        image.architecture = "arm64";
        image.creationDate = "2025-11-05T00:00:00.000Z";
        image.registrationNames = names;
        return image;
    }
}
