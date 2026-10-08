package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertTrue(new Ec2ImageCatalog().findByIdOrAlias("ami-amazonlinux2023").isPresent());
        assertTrue(new Ec2ImageCatalog().findByIdOrAlias("ami-local-worker").isEmpty());
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
