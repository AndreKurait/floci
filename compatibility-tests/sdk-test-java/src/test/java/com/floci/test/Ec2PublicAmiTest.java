package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.ImageState;
import software.amazon.awssdk.services.ssm.SsmClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Public Amazon Linux AMI identifiers")
class Ec2PublicAmiTest {

    @ParameterizedTest
    @ValueSource(strings = {"x86_64", "arm64"})
    void ssmReturnsAnAvailableEc2ImageWithTheRequestedArchitecture(String architecture) {
        try (SsmClient ssm = TestFixtures.ssmClient(); Ec2Client ec2 = TestFixtures.ec2Client()) {
            String imageId = ssm.getParameter(request -> request.name(
                    "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-" + architecture))
                    .parameter().value();
            assertThat(imageId).matches("ami-[0-9a-f]{17}");
            assertThat(ec2.describeImages(request -> request.imageIds(imageId)).images())
                    .singleElement().satisfies(image -> {
                        assertThat(image.imageId()).isEqualTo(imageId);
                        assertThat(image.architectureAsString()).isEqualTo(architecture);
                        assertThat(image.state()).isEqualTo(ImageState.AVAILABLE);
                        assertThat(image.publicLaunchPermissions()).isTrue();
                    });
        }
    }

    @Test
    void theLegacyArmAliasStillResolvesToThePublicImage() {
        try (SsmClient ssm = TestFixtures.ssmClient(); Ec2Client ec2 = TestFixtures.ec2Client()) {
            String imageId = ssm.getParameter(request -> request.name(
                    "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"))
                    .parameter().value();
            Image image = ec2.describeImages(request -> request.imageIds("ami-amazonlinux2023-arm64"))
                    .images().get(0);
            assertThat(image.imageId()).isEqualTo(imageId);
            assertThat(image.architectureAsString()).isEqualTo("arm64");
        }
    }
}
