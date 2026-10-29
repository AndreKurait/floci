package io.github.hectorvent.floci.services.autoscaling;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.CreateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.CreateLaunchConfigurationRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteLaunchConfigurationRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingStandbyCleanupTest {
    @Test
    void failedGroupCreateStillDeletesBothOwnedNames() {
        AutoScalingClient client = mock(AutoScalingClient.class);
        RuntimeException primary = new IllegalStateException("create group");
        when(client.createAutoScalingGroup(any(CreateAutoScalingGroupRequest.class))).thenThrow(primary);
        assertSame(primary, assertThrows(RuntimeException.class,
                () -> AutoScalingStandbyIntegrationTest.withStandbyGroup(client, "owned", () -> fail("body ran"))));
        verify(client).deleteAutoScalingGroup(any(DeleteAutoScalingGroupRequest.class));
        verify(client).deleteLaunchConfiguration(any(DeleteLaunchConfigurationRequest.class));
    }

    @Test
    void failedLaunchResponseStillDeletesLaunchConfiguration() {
        AutoScalingClient client = mock(AutoScalingClient.class);
        RuntimeException primary = new IllegalStateException("create launch response");
        when(client.createLaunchConfiguration(any(CreateLaunchConfigurationRequest.class))).thenThrow(primary);
        assertSame(primary, assertThrows(RuntimeException.class,
                () -> AutoScalingStandbyIntegrationTest.withStandbyGroup(client, "owned", () -> fail("body ran"))));
        verify(client).deleteLaunchConfiguration(any(DeleteLaunchConfigurationRequest.class));
        verify(client, never()).createAutoScalingGroup(any(CreateAutoScalingGroupRequest.class));
    }

    @Test
    void bodyFailurePreservedWhenBothDeletesFail() {
        AutoScalingClient client = mock(AutoScalingClient.class);
        AssertionError primary = new AssertionError("body");
        RuntimeException group = new IllegalStateException("group delete");
        RuntimeException launch = new IllegalStateException("launch delete");
        when(client.deleteAutoScalingGroup(any(DeleteAutoScalingGroupRequest.class))).thenThrow(group);
        when(client.deleteLaunchConfiguration(any(DeleteLaunchConfigurationRequest.class))).thenThrow(launch);
        assertSame(primary, assertThrows(AssertionError.class,
                () -> AutoScalingStandbyIntegrationTest.withStandbyGroup(client, "owned", () -> { throw primary; })));
        assertArrayEquals(new Throwable[]{group, launch}, primary.getSuppressed());
        verify(client).deleteLaunchConfiguration(any(DeleteLaunchConfigurationRequest.class));
    }

    @Test
    void successfulBodyStillAttemptsLaunchDeleteAfterGroupDeleteFailure() {
        AutoScalingClient client = mock(AutoScalingClient.class);
        RuntimeException primary = new IllegalStateException("delete group");
        when(client.deleteAutoScalingGroup(any(DeleteAutoScalingGroupRequest.class))).thenThrow(primary);
        assertSame(primary, assertThrows(RuntimeException.class,
                () -> AutoScalingStandbyIntegrationTest.withStandbyGroup(client, "owned", () -> { })));
        verify(client).deleteLaunchConfiguration(any(DeleteLaunchConfigurationRequest.class));
    }

    @Test
    void successfulBodyDeletesBothResources() {
        AutoScalingClient client = mock(AutoScalingClient.class);
        AutoScalingStandbyIntegrationTest.withStandbyGroup(client, "owned", () -> { });
        verify(client).deleteAutoScalingGroup(any(DeleteAutoScalingGroupRequest.class));
        verify(client).deleteLaunchConfiguration(any(DeleteLaunchConfigurationRequest.class));
    }

    @Test
    void exactMissingOwnedNameDoesNotMaskCreateFailure() {
        AutoScalingClient client = mock(AutoScalingClient.class);
        RuntimeException primary = new IllegalStateException("create launch");
        when(client.createLaunchConfiguration(any(CreateLaunchConfigurationRequest.class))).thenThrow(primary);
        when(client.deleteLaunchConfiguration(any(DeleteLaunchConfigurationRequest.class))).thenThrow(
                AutoScalingException.builder().awsErrorDetails(AwsErrorDetails.builder().errorCode("ValidationError")
                        .errorMessage("Launch configuration 'owned' not found.").build()).build());
        assertSame(primary, assertThrows(RuntimeException.class,
                () -> AutoScalingStandbyIntegrationTest.withStandbyGroup(client, "owned", () -> fail("body ran"))));
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void otherValidationFailureIsNotSwallowed() {
        AutoScalingClient client = mock(AutoScalingClient.class);
        AutoScalingException cleanup = (AutoScalingException) AutoScalingException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ValidationError").errorMessage("other").build()).build();
        when(client.deleteLaunchConfiguration(any(DeleteLaunchConfigurationRequest.class))).thenThrow(cleanup);
        assertSame(cleanup, assertThrows(AutoScalingException.class,
                () -> AutoScalingStandbyIntegrationTest.withStandbyGroup(client, "owned", () -> { })));
    }
}
