package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2ImageCatalogTest {

    @Inject
    Ec2ImageCatalog imageCatalog;

    @Inject
    AmiImageResolver amiImageResolver;

    @Test
    void catalogContainsCurrentEc2ImagesAndFlociAliases() {
        Set<String> imageIds = imageCatalog.images().stream()
                .map(image -> image.imageId)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "ami-0abcdef1234567890",
                "ami-0abcdef1234567891",
                "ami-0abcdef1234567894",
                "ami-0abcdef1234567892",
                "ami-ubuntu2204",
                "ami-ubuntu2404-arm64",
                "ami-ubuntu2404-amd64",
                "ami-ubuntu2404-cloud-arm64",
                "ami-debian12",
                "ami-alpine",
                "ami-0abcdef1234567893",
                "ami-opensuse-leap160",
                "ami-sles15sp4-byos",
                "ami-sles15sp5-byos",
                "ami-sles15sp6-byos",
                "ami-sles15sp7-byos",
                "ami-sles12sp5-byos",
                "ami-sles-sap15sp5",
                "ami-smlm-server51-x86-64",
                "ami-smlm-server51-arm64",
                "ami-smlm-proxy51-x86-64",
                "ami-smlm-proxy51-arm64",
                "ami-sles12sp5",
                "ami-sles15sp5",
                "ami-sles15sp6",
                "ami-sles15sp7",
                "ami-sles160",
                "ami-rocky9",
                "ami-rocky10",
                "ami-rhel8",
                "ami-rhel9"), imageIds);

        assertTrue(imageCatalog.findByIdOrAlias("ami-amazonlinux2").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-amazonlinux2023").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-amazonlinux2023-arm64").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-ubuntu2004").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-ubuntu2404").isPresent());
        assertTrue(imageCatalog.findByIdOrAlias("ami-ubuntu2404-cloud").isPresent());
    }

    @Test
    void amazonLinux2023EntriesCoverArm64AndX86_64Architectures() {
        Ec2ImageCatalog.CatalogImage x86 = imageCatalog.findByIdOrAlias("ami-0abcdef1234567891").orElseThrow();
        assertEquals("x86_64", x86.architecture);
        assertEquals("al2023-ami-2023.0.20230315.0-kernel-6.1-x86_64", x86.name);
        assertEquals(List.of(
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64",
                "/aws/service/ami-amazon-linux-latest/al2023-ami-minimal-kernel-default-x86_64",
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64",
                "/aws/service/eks/optimized-ami/1.28/amazon-linux-2023/x86_64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.29/amazon-linux-2023/x86_64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.30/amazon-linux-2023/x86_64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.31/amazon-linux-2023/x86_64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.32/amazon-linux-2023/x86_64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.33/amazon-linux-2023/x86_64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.34/amazon-linux-2023/x86_64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.35/amazon-linux-2023/x86_64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.36/amazon-linux-2023/x86_64/standard/recommended/image_id",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/schema_version",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_name",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/image_id",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/os",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/ecs_agent_version",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/recommended/ecs_runtime_version"),
                x86.publicParameterNames());

        Ec2ImageCatalog.CatalogImage arm64 = imageCatalog.findByIdOrAlias("ami-amazonlinux2023-arm64").orElseThrow();
        assertEquals("arm64", arm64.architecture);
        assertEquals("al2023-ami-2023.0.20230315.0-kernel-6.1-arm64", arm64.name);
        assertEquals(List.of(
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64",
                "/aws/service/ami-amazon-linux-latest/al2023-ami-minimal-kernel-default-arm64",
                "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-arm64",
                "/aws/service/eks/optimized-ami/1.28/amazon-linux-2023/arm64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.29/amazon-linux-2023/arm64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.30/amazon-linux-2023/arm64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.31/amazon-linux-2023/arm64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.32/amazon-linux-2023/arm64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.33/amazon-linux-2023/arm64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.34/amazon-linux-2023/arm64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.35/amazon-linux-2023/arm64/standard/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.36/amazon-linux-2023/arm64/standard/recommended/image_id",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/arm64/recommended",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/arm64/recommended/schema_version",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/arm64/recommended/image_name",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/arm64/recommended/image_id",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/arm64/recommended/os",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/arm64/recommended/ecs_agent_version",
                "/aws/service/ecs/optimized-ami/amazon-linux-2023/arm64/recommended/ecs_runtime_version"),
                arm64.publicParameterNames());
    }

    @Test
    void amazonLinux2EntriesCapEksOptimizedAmiAt1_32() {
        Ec2ImageCatalog.CatalogImage amzn2 = imageCatalog.findByIdOrAlias("ami-0abcdef1234567890").orElseThrow();
        assertEquals("x86_64", amzn2.architecture);
        assertEquals(List.of(
                "/aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-gp2",
                "/aws/service/ami-amazon-linux-latest/amzn2-ami-hvm-x86_64-ebs",
                "/aws/service/ami-amazon-linux-latest/amzn2-ami-kernel-5.10-hvm-x86_64-gp2",
                "/aws/service/eks/optimized-ami/1.28/amazon-linux-2/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.29/amazon-linux-2/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.30/amazon-linux-2/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.31/amazon-linux-2/recommended/image_id",
                "/aws/service/eks/optimized-ami/1.32/amazon-linux-2/recommended/image_id"),
                amzn2.publicParameterNames());
    }

    @Test
    void resolverUsesCatalogDockerImagesForIdsAndAliases() {
        imageCatalog.images()
                .stream()
                .filter(image -> !"windows".equalsIgnoreCase(image.platform))
                .forEach(image -> assertEquals(image.dockerImage, amiImageResolver.resolve(image.imageId)));

        List.of("ami-amazonlinux2", "ami-amazonlinux2023", "ami-ubuntu2004", "ami-ubuntu2404")
                .forEach(alias -> assertEquals(
                        imageCatalog.findByIdOrAlias(alias).orElseThrow().dockerImage,
                        amiImageResolver.resolve(alias)));
    }

    @Test
    void resolverExposesCloudImageGuestRuntimeMetadata() {
        ResolvedAmiImage image = amiImageResolver.resolveImage("ami-ubuntu2404-cloud");

        assertEquals("floci/ami-ubuntu:24.04-arm64", image.dockerImage());
        assertEquals(ResolvedAmiImage.SYSTEMD_RUNTIME, image.guestRuntime());
        assertTrue(image.cloudInit());
        assertTrue(image.systemd());
    }

    @Test
    void resolverMapsImageArchitectureToDockerPlatform() {
        assertEquals("linux/arm64", amiImageResolver.resolveImage("ami-ubuntu2404-cloud-arm64").dockerPlatform());
        assertEquals("linux/arm64", amiImageResolver.resolveImage("ami-amazonlinux2023-arm64").dockerPlatform());
        assertEquals("linux/amd64", amiImageResolver.resolveImage("ami-0abcdef1234567891").dockerPlatform());
    }

    @Test
    void resolverRejectsWindowsImagesForContainerExecution() {
        AwsException error = assertThrows(AwsException.class,
                () -> amiImageResolver.resolveImage("ami-0abcdef1234567893"));

        assertEquals("UnsupportedOperation", error.getErrorCode());
    }

    @Test
    void unknownImageUsesCatalogDefault() {
        assertFalse(imageCatalog.findByIdOrAlias("ami-unknown").isPresent());
        assertEquals(imageCatalog.defaultDockerImage(), amiImageResolver.resolve("ami-unknown"));
    }

    @Test
    void imageMountsNormalizeDevicesAndSurviveResolution(@TempDir Path directory) throws Exception {
        for (String architecture : List.of("x86_64", "arm64")) {
            Ec2ImageCatalog catalog = mountCatalog(directory, architecture, """
                    - {device: sdf, target: /data/./engine, filesystem: ext4}
                    """);
            ResolvedAmiImage resolved = new AmiImageResolver(catalog).resolveImage("ami-mounted");
            assertEquals(List.of(new Ec2ImageCatalog.VolumeMount("/dev/sdf", "/data/engine", "ext4")),
                    resolved.volumeMounts());
            assertTrue(resolved.imageRuntime());
            assertEquals("arm64".equals(architecture) ? "linux/arm64" : "linux/amd64",
                    resolved.dockerPlatform());
            assertThrows(UnsupportedOperationException.class, () -> resolved.volumeMounts().clear());
        }
        List<Ec2ImageCatalog.VolumeMount> mutable = new ArrayList<>(
                List.of(new Ec2ImageCatalog.VolumeMount("sdf", "/data", "ext4")));
        ResolvedAmiImage resolved = new ResolvedAmiImage("image", "image", false, null, mutable);
        mutable.clear();
        assertEquals(1, resolved.volumeMounts().size());
        assertTrue(new ResolvedAmiImage("image", "image", false, null).volumeMounts().isEmpty());
    }

    @Test
    void mountCatalogRejectsUnknownFieldsAndConflictingBindings(@TempDir Path directory) {
        for (String entries : List.of(
                "- {device: sdf, target: /data, filesystem: ext4, format: true}",
                "- {device: sdf, target: /data, filesystem: xfs}",
                "- {device: sdf, target: relative, filesystem: ext4}",
                "- {device: sdf, target: /dev/data, filesystem: ext4}",
                "- {device: sdf, target: /, filesystem: ext4}",
                "- {device: sdf, target: /data, filesystem: ext4}\n"
                        + "- {device: /dev/sdf, target: /other, filesystem: ext4}",
                "- {device: sdf, target: /data, filesystem: ext4}\n"
                        + "- {device: sdg, target: /data/child, filesystem: ext4}")) {
            assertThrows(IllegalStateException.class,
                    () -> mountCatalog(directory, "x86_64", entries).images(), entries);
        }
    }

    @Test
    void managedMountsRequireTheImagesOwnStartupContract(@TempDir Path directory) throws Exception {
        Ec2ImageCatalog catalog = mountCatalog(directory, "x86_64",
                "- {device: sdf, target: /data, filesystem: ext4}");
        Path file = directory.resolve("catalog.yaml");
        Files.writeString(file, Files.readString(file).replace("guestRuntime: image", "guestRuntime: minimal"));
        assertThrows(IllegalStateException.class, catalog::images);
    }

    private static Ec2ImageCatalog mountCatalog(Path directory, String architecture, String mounts) throws Exception {
        Path file = directory.resolve("catalog.yaml");
        Files.writeString(file, """
                defaultDockerImage: debian:bookworm-slim
                images:
                  - imageId: ami-mounted
                    dockerImage: example/native:1
                    name: native
                    description: Native mounted data volume
                    creationDate: '2026-11-01T00:00:00.000Z'
                    architecture: %s
                    guestRuntime: image
                    volumeMounts:
                %s
                """.formatted(architecture, mounts.indent(6)));
        return new Ec2ImageCatalog(file);
    }
}
