package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CopyImageRequest;
import software.amazon.awssdk.services.ec2.model.CopyImageResponse;
import software.amazon.awssdk.services.ec2.model.DeregisterImageRequest;
import software.amazon.awssdk.services.ec2.model.DeregisterImageResponse;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.RegisterImageRequest;
import software.amazon.awssdk.services.ec2.model.RegisterImageResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("EC2 copy-image fixture cleanup")
class Ec2CopyImageCleanupTest {

    @Test
    void successfulScenarioDeletesCopiesBeforeSourcesWithoutRepeatingDeletedCopy() throws Exception {
        RecordingEc2 ec2 = new RecordingEc2();
        runScenario(ec2);
        assertThat(ec2.deletions).containsExactly("copy-1", "copy-3", "copy-2", "source-2", "source-1");
        assertThat(ec2.images).isEmpty();
    }

    @Test
    void assertionFailurePreservesBothCleanupCausesAndAttemptsEveryImage() {
        RecordingEc2 ec2 = new RecordingEc2();
        ec2.invalidCopyArchitecture = true;
        RuntimeException copyFailure = new IllegalStateException("copy deletion");
        RuntimeException sourceFailure = new IllegalStateException("source deletion");
        ec2.deletionFailures.put("copy-3", copyFailure);
        ec2.deletionFailures.put("source-2", sourceFailure);
        AssertionError failure = assertThrows(AssertionError.class, () -> runScenario(ec2));
        assertThat(failure).hasMessageContaining("i386");
        assertThat(failure.getSuppressed()).containsExactly(copyFailure);
        assertThat(copyFailure.getSuppressed()).containsExactly(sourceFailure);
        assertThat(ec2.deletions).containsExactly("copy-3", "copy-2", "copy-1", "source-2", "source-1");
        assertThat(ec2.images.keySet()).containsExactlyInAnyOrder("copy-3", "source-2");
    }

    @Test
    void failedSecondCopyStillDeletesFirstCopyAndBothSources() {
        RecordingEc2 ec2 = new RecordingEc2();
        ec2.failCopyAt = 2;
        RuntimeException cleanupFailure = new IllegalStateException("copy deletion");
        ec2.deletionFailures.put("copy-1", cleanupFailure);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> runScenario(ec2));
        assertThat(failure).isSameAs(ec2.creationFailure);
        assertThat(failure.getSuppressed()).containsExactly(cleanupFailure);
        assertThat(ec2.deletions).containsExactly("copy-1", "source-2", "source-1");
        assertThat(ec2.images.keySet()).containsExactly("copy-1");
    }

    @Test
    void failedSecondRegistrationStillDeletesFirstSource() {
        RecordingEc2 ec2 = new RecordingEc2();
        ec2.failRegistrationAt = 2;
        RuntimeException failure = assertThrows(RuntimeException.class, () -> runScenario(ec2));
        assertThat(failure).isSameAs(ec2.creationFailure);
        assertThat(ec2.deletions).containsExactly("source-1");
        assertThat(ec2.images).isEmpty();
    }

    @Test
    void cleanupFailureAfterSuccessfulAssertionsStillAttemptsRemainingImages() {
        RecordingEc2 ec2 = new RecordingEc2();
        RuntimeException copyFailure = new IllegalStateException("copy deletion");
        RuntimeException sourceFailure = new IllegalStateException("source deletion");
        ec2.deletionFailures.put("copy-3", copyFailure);
        ec2.deletionFailures.put("source-2", sourceFailure);
        RuntimeException failure = assertThrows(RuntimeException.class, () -> runScenario(ec2));
        assertThat(failure).isSameAs(copyFailure);
        assertThat(failure.getSuppressed()).containsExactly(sourceFailure);
        assertThat(ec2.deletions).containsExactly("copy-1", "copy-3", "copy-2", "source-2", "source-1");
    }

    @Test
    void cleanupFailureDoesNotSkipRemainingImages() {
        RecordingEc2 ec2 = new RecordingEc2();
        ec2.deletionFailures.put("copy-3", new IllegalStateException("copy deletion"));
        assertThrows(RuntimeException.class, () -> runScenario(ec2));
        assertThat(ec2.deletions).containsExactly("copy-1", "copy-3", "copy-2", "source-2", "source-1");
        assertThat(ec2.images.keySet()).containsExactly("copy-3");
    }

    @Test
    void unexpectedSuccessfulRegistrationIsRecordedBeforeTheAssertionFails() {
        RecordingEc2 ec2 = new RecordingEc2();
        ec2.allowDuplicateRegistration = true;
        assertThrows(AssertionError.class, () -> runScenario(ec2));
        assertThat(ec2.deletions).containsExactly("copy-3", "copy-2", "copy-1", "source-3", "source-2", "source-1");
        assertThat(ec2.images).isEmpty();
    }

    private static void runScenario(RecordingEc2 ec2) throws Exception {
        Ec2CopyImageNameTest.copiesCanShareANameWhileRegistrationsCannot(ec2, "us-east-1", "shared-name");
    }

    private static final class RecordingEc2 implements Ec2Client {
        private final Map<String, Image> images = new LinkedHashMap<>();
        private final List<String> deletions = new ArrayList<>();
        private final Map<String, RuntimeException> deletionFailures = new LinkedHashMap<>();
        private final RuntimeException creationFailure = new IllegalStateException("creation failed");
        private int registrations;
        private int copies;
        private int failRegistrationAt;
        private int failCopyAt;
        private boolean invalidCopyArchitecture;
        private boolean allowDuplicateRegistration;

        @Override
        public RegisterImageResponse registerImage(RegisterImageRequest request) {
            if ("shared-name".equals(request.name()) && !allowDuplicateRegistration) {
                throw Ec2Exception.builder().awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("InvalidAMIName.Duplicate").build()).build();
            }
            registrations++;
            if (registrations == failRegistrationAt) {
                throw creationFailure;
            }
            String id = "source-" + registrations;
            images.put(id, Image.builder().imageId(id).name(request.name())
                    .architecture(request.architecture()).state("available").build());
            return RegisterImageResponse.builder().imageId(id).build();
        }

        @Override
        public CopyImageResponse copyImage(CopyImageRequest request) {
            copies++;
            if (copies == failCopyAt) {
                throw creationFailure;
            }
            String id = "copy-" + copies;
            Image source = images.get(request.sourceImageId());
            images.put(id, source.toBuilder().imageId(id).name(request.name())
                    .architecture(invalidCopyArchitecture ? "i386" : source.architectureAsString()).build());
            return CopyImageResponse.builder().imageId(id).build();
        }

        @Override
        public DescribeImagesResponse describeImages(DescribeImagesRequest request) {
            return DescribeImagesResponse.builder().images(request.imageIds().stream().map(images::get).toList()).build();
        }

        @Override
        public DeregisterImageResponse deregisterImage(DeregisterImageRequest request) {
            assertThat(request.deleteAssociatedSnapshots()).isTrue();
            deletions.add(request.imageId());
            RuntimeException failure = deletionFailures.get(request.imageId());
            if (failure != null) {
                throw failure;
            }
            images.remove(request.imageId());
            return DeregisterImageResponse.builder().returnValue(true).build();
        }

        @Override
        public String serviceName() {
            return "ec2";
        }

        @Override
        public void close() {
            // The recording client owns no HTTP transport or external resources.
        }
    }
}
