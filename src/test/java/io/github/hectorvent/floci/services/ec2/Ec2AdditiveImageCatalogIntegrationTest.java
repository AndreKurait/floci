package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(Ec2AdditiveImageCatalogIntegrationTest.Profile.class)
class Ec2AdditiveImageCatalogIntegrationTest {
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            URL resource = Objects.requireNonNull(
                    getClass().getResource("/ec2/additive-image-catalog.yaml"), "Additive catalog test resource");
            return Map.of("floci.services.ec2.image-catalog-path", Path.of(URI.create(resource.toString())).toString(),
                    "floci.storage.mode", "memory", "floci.services.ec2.mock", "true",
                    "floci.services.ec2.vpc-networks.enabled", "false");
        }
    }

    @ParameterizedTest
    @CsvSource({"x86_64,t3.micro", "arm64,t4g.micro"})
    void publicSsmAmiStillLaunchesAnOwnedHostAlongsideLocalImages(String architecture, String type) {
        try (Ec2Client ec2 = ec2(); SsmClient ssm = ssm()) {
            String parameter = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-" + architecture;
            String ami = ssm.getParameter(request -> request.name(parameter)).parameter().value();
            Image image = ec2.describeImages(request -> request.imageIds(ami)).images().getFirst();
            assertEquals(architecture, image.architectureAsString());
            assertEquals("arm64", ec2.describeImages(request -> request.imageIds("ami-local-native"))
                    .images().getFirst().architectureAsString());
            String token = UUID.randomUUID().toString();
            String name = "catalog-host-" + architecture;
            String instanceId = ec2.runInstances(request -> request.imageId(ami).instanceType(type)
                    .minCount(1).maxCount(1).clientToken(token).tagSpecifications(
                            TagSpecification.builder().resourceType("instance")
                                    .tags(Tag.builder().key("Name").value(name).build()).build()))
                    .instances().getFirst().instanceId();
            try {
                Instance instance = ec2.describeInstances(request -> request.instanceIds(instanceId))
                        .reservations().getFirst().instances().getFirst();
                assertTrue(instanceId.matches("i-[0-9a-f]{17}"));
                assertEquals(ami, instance.imageId());
                assertEquals(token, instance.clientToken());
                assertEquals(name, instance.tags().stream().filter(tag -> "Name".equals(tag.key()))
                        .findFirst().orElseThrow().value());
            } finally {
                ec2.terminateInstances(request -> request.instanceIds(instanceId));
                assertEquals("terminated", ec2.describeInstances(request -> request.instanceIds(instanceId))
                        .reservations().getFirst().instances().getFirst().state().nameAsString());
            }
        }
    }

    @Test
    void registeredLocalImageKeepsItsArchitectureAcrossCopyAndLaunch() {
        try (Ec2Client ec2 = ec2()) {
            String source = ec2.registerImage(request -> request.name("native-worker-release").architecture("arm64"))
                    .imageId();
            String copy = null;
            String instanceId = null;
            try {
                copy = ec2.copyImage(request -> request.sourceRegion(Region.US_EAST_1.id()).sourceImageId(source)
                        .name("copied-native-" + UUID.randomUUID())).imageId();
                String selected = copy;
                assertEquals("arm64", ec2.describeImages(request -> request.imageIds(selected))
                        .images().getFirst().architectureAsString());
                instanceId = ec2.runInstances(request -> request.imageId(selected).instanceType("t4g.micro")
                        .minCount(1).maxCount(1)).instances().getFirst().instanceId();
                String ownedInstance = instanceId;
                assertEquals(copy, ec2.describeInstances(request -> request.instanceIds(ownedInstance))
                        .reservations().getFirst().instances().getFirst().imageId());
            } finally {
                if (instanceId != null) {
                    String ownedInstance = instanceId;
                    ec2.terminateInstances(request -> request.instanceIds(ownedInstance));
                }
                if (copy != null) {
                    String ownedCopy = copy;
                    ec2.deregisterImage(request -> request.imageId(ownedCopy));
                }
                ec2.deregisterImage(request -> request.imageId(source));
            }
        }
    }

    private Ec2Client ec2() {
        return Ec2Client.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1).httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("111111111111", "test"))).build();
    }

    private SsmClient ssm() {
        return SsmClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1).httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("111111111111", "test"))).build();
    }
}
