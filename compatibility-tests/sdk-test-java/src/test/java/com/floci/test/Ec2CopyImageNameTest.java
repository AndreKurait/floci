package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Image;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EC2 copied image names")
class Ec2CopyImageNameTest {

    @Test
    @DisplayName("Copies can share a name across architectures and repeated copies of one source")
    void copiesCanShareANameWhileRegistrationsCannot() {
        String region = TestFixtures.region().id();
        String name = TestFixtures.uniqueName("shared-copy-name");
        List<String> sourceIds = new ArrayList<>();
        List<String> copyIds = new ArrayList<>();
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            try {
                for (String architecture : List.of("x86_64", "arm64")) {
                    sourceIds.add(ec2.registerImage(r -> r
                            .name(TestFixtures.uniqueName("copy-source"))
                            .architecture(architecture)).imageId());
                }
                for (String sourceId : List.of(sourceIds.get(0), sourceIds.get(1), sourceIds.get(0))) {
                    copyIds.add(ec2.copyImage(r -> r.sourceImageId(sourceId).sourceRegion(region)
                            .name(name)).imageId());
                }
                assertThat(copyIds).hasSize(3).doesNotHaveDuplicates();
                List<Image> copies = ec2.describeImages(r -> r.imageIds(copyIds)).images();
                assertThat(copies).extracting(Image::name).containsOnly(name);
                assertThat(copies).extracting(Image::architectureAsString)
                        .containsExactlyInAnyOrder("x86_64", "arm64", "x86_64");
                assertThat(copies).extracting(Image::stateAsString).containsOnly("available");

                for (String architecture : List.of("x86_64", "arm64")) {
                    assertThatThrownBy(() -> ec2.registerImage(r -> r.name(name).architecture(architecture)))
                            .isInstanceOfSatisfying(Ec2Exception.class, e -> assertThat(e.awsErrorDetails().errorCode())
                                    .isEqualTo("InvalidAMIName.Duplicate"));
                }
                ec2.deregisterImage(r -> r.imageId(copyIds.get(0)).deleteAssociatedSnapshots(true));
                copyIds.remove(0);
                assertThat(ec2.describeImages(r -> r.imageIds(copyIds)).images())
                        .extracting(Image::imageId).containsExactlyInAnyOrderElementsOf(copyIds);
            } finally {
                for (String copyId : copyIds) {
                    ec2.deregisterImage(r -> r.imageId(copyId).deleteAssociatedSnapshots(true));
                }
                for (String sourceId : sourceIds) {
                    ec2.deregisterImage(r -> r.imageId(sourceId).deleteAssociatedSnapshots(true));
                }
            }
        }
    }
}
