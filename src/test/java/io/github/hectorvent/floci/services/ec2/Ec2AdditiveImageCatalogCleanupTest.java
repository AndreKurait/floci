package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CopyImageRequest;
import software.amazon.awssdk.services.ec2.model.CopyImageResponse;
import software.amazon.awssdk.services.ec2.model.DeregisterImageRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.RegisterImageResponse;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Ec2AdditiveImageCatalogCleanupTest {
    @Test
    void terminationFailureStillDeregistersBothImagesAndClosesClient() {
        exerciseCleanupFailure("instance");
    }

    @Test
    void copyDeletionFailureStillDeregistersSourceAndClosesClient() {
        exerciseCleanupFailure("copy");
    }

    @Test
    void sourceDeletionFailureStillClosesClient() {
        exerciseCleanupFailure("source");
    }

    @Test
    void successfulLifecycleCleansEveryResourceInReverseOrder() {
        List<String> deleted = new ArrayList<>();
        Ec2Client client = client(deleted);
        fixture(client).registeredLocalImageKeepsItsArchitectureAcrossCopyAndLaunch();
        assertEquals(List.of("instance", "copy", "source"), deleted);
        verify(client).close();
    }

    @Test
    void cleanupFailuresRetainTheOriginalValidationFailure() {
        List<String> deleted = new ArrayList<>();
        Ec2Client client = client(deleted, "instance", "copy", "source");
        IllegalStateException validation = new IllegalStateException("Instance lookup failed");
        when(client.describeInstances(Ec2AdditiveImageCatalogCleanupTest.<DescribeInstancesRequest.Builder>anyConsumer()))
                .thenThrow(validation);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture(client).registeredLocalImageKeepsItsArchitectureAcrossCopyAndLaunch());

        assertSame(validation, failure);
        assertEquals(List.of("instance", "copy", "source"), deleted);
        assertEquals(List.of("Injected cleanup failure for instance", "Injected cleanup failure for copy",
                        "Injected cleanup failure for source"),
                Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage).toList());
        verify(client).close();
    }

    @Test
    void multipleCleanupFailuresRetainTheFirstAndSuppressTheRest() {
        List<String> deleted = new ArrayList<>();
        Ec2Client client = client(deleted, "instance", "copy", "source");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture(client).registeredLocalImageKeepsItsArchitectureAcrossCopyAndLaunch());

        assertEquals("Injected cleanup failure for instance", failure.getMessage());
        assertEquals(List.of("instance", "copy", "source"), deleted);
        assertEquals(List.of("Injected cleanup failure for copy", "Injected cleanup failure for source"),
                Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage).toList());
        verify(client).close();
    }

    @Test
    void failedCopyCreationReleasesOnlyTheSource() {
        List<String> deleted = new ArrayList<>();
        Ec2Client client = client(deleted);
        IllegalStateException creation = new IllegalStateException("Copy failed");
        when(client.copyImage(Ec2AdditiveImageCatalogCleanupTest.<CopyImageRequest.Builder>anyConsumer()))
                .thenThrow(creation);

        assertSame(creation, assertThrows(IllegalStateException.class,
                () -> fixture(client).registeredLocalImageKeepsItsArchitectureAcrossCopyAndLaunch()));
        assertEquals(List.of("source"), deleted);
        verify(client).close();
    }

    @Test
    void failedInstanceCreationReleasesBothImages() {
        List<String> deleted = new ArrayList<>();
        Ec2Client client = client(deleted);
        IllegalStateException creation = new IllegalStateException("Launch failed");
        when(client.runInstances(Ec2AdditiveImageCatalogCleanupTest.<RunInstancesRequest.Builder>anyConsumer()))
                .thenThrow(creation);

        assertSame(creation, assertThrows(IllegalStateException.class,
                () -> fixture(client).registeredLocalImageKeepsItsArchitectureAcrossCopyAndLaunch()));
        assertEquals(List.of("copy", "source"), deleted);
        verify(client).close();
    }

    private void exerciseCleanupFailure(String failingResource) {
        List<String> deleted = new ArrayList<>();
        Ec2Client client = client(deleted, failingResource);
        assertThrows(IllegalStateException.class,
                () -> fixture(client).registeredLocalImageKeepsItsArchitectureAcrossCopyAndLaunch());
        assertEquals(List.of("instance", "copy", "source"), deleted);
        verify(client).close();
    }

    private Ec2AdditiveImageCatalogIntegrationTest fixture(Ec2Client client) {
        return new Ec2AdditiveImageCatalogIntegrationTest() {
            @Override
            Ec2Client ec2() {
                return client;
            }
        };
    }

    private Ec2Client client(List<String> deleted, String... failingResources) {
        Ec2Client client = mock(Ec2Client.class);
        when(client.registerImage(anyConsumer())).thenReturn(RegisterImageResponse.builder().imageId("source").build());
        when(client.copyImage(Ec2AdditiveImageCatalogCleanupTest.<CopyImageRequest.Builder>anyConsumer()))
                .thenReturn(CopyImageResponse.builder().imageId("copy").build());
        when(client.describeImages(Ec2AdditiveImageCatalogCleanupTest.<DescribeImagesRequest.Builder>anyConsumer()))
                .thenReturn(DescribeImagesResponse.builder().images(Image.builder().architecture("arm64").build()).build());
        when(client.runInstances(Ec2AdditiveImageCatalogCleanupTest.<RunInstancesRequest.Builder>anyConsumer()))
                .thenReturn(RunInstancesResponse.builder().instances(Instance.builder().instanceId("instance").build()).build());
        when(client.describeInstances(Ec2AdditiveImageCatalogCleanupTest.<DescribeInstancesRequest.Builder>anyConsumer()))
                .thenReturn(DescribeInstancesResponse.builder().reservations(Reservation.builder()
                        .instances(Instance.builder().imageId("copy").build()).build()).build());
        doAnswer(call -> {
            TerminateInstancesRequest.Builder request = TerminateInstancesRequest.builder();
            Consumer<TerminateInstancesRequest.Builder> consumer = call.getArgument(0);
            consumer.accept(request);
            recordDeletion(deleted, request.build().instanceIds().getFirst(), failingResources);
            return null;
        }).when(client).terminateInstances(Ec2AdditiveImageCatalogCleanupTest.<TerminateInstancesRequest.Builder>anyConsumer());
        doAnswer(call -> {
            DeregisterImageRequest.Builder request = DeregisterImageRequest.builder();
            Consumer<DeregisterImageRequest.Builder> consumer = call.getArgument(0);
            consumer.accept(request);
            recordDeletion(deleted, request.build().imageId(), failingResources);
            return null;
        }).when(client).deregisterImage(Ec2AdditiveImageCatalogCleanupTest.<DeregisterImageRequest.Builder>anyConsumer());
        return client;
    }

    private static <T> Consumer<T> anyConsumer() {
        return any();
    }

    private static void recordDeletion(List<String> deleted, String resource, String... failingResources) {
        deleted.add(resource);
        if (List.of(failingResources).contains(resource)) {
            throw new IllegalStateException("Injected cleanup failure for " + resource);
        }
    }
}
