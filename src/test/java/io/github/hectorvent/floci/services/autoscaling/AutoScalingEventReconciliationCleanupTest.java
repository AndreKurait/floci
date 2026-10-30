package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.services.autoscaling.AutoScalingEventReconciliationIntegrationTest.OwnedGroup;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.CreateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.CreateLaunchConfigurationRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteLaunchConfigurationRequest;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoScalingEventReconciliationCleanupTest {
    @Test
    void failedConfigurationCreationDoesNotDeleteUnownedResources() {
        AutoScalingClient client = mock(AutoScalingClient.class);
        RuntimeException primary = new IllegalStateException("configuration creation failed");
        when(client.createLaunchConfiguration(anyConfiguration())).thenThrow(primary);
        Exception actual = assertThrows(Exception.class, () -> {
            try (OwnedGroup group = new OwnedGroup(client, "owned")) {
                group.create("us-east-1");
            }
        });
        assertSame(primary, actual);
        verify(client, never()).deleteAutoScalingGroup(anyGroupDeletion());
        verify(client, never()).deleteLaunchConfiguration(anyConfigurationDeletion());
    }

    @Test
    void failedGroupCreationStillDeletesOwnedConfiguration() {
        AutoScalingClient client = mock(AutoScalingClient.class);
        RuntimeException primary = new IllegalStateException("group creation failed");
        when(client.createAutoScalingGroup(anyGroup())).thenThrow(primary);
        Exception actual = assertThrows(Exception.class, () -> {
            try (OwnedGroup group = new OwnedGroup(client, "owned")) {
                group.create("us-east-1");
            }
        });
        assertSame(primary, actual);
        verify(client, never()).deleteAutoScalingGroup(anyGroupDeletion());
        verify(client).deleteLaunchConfiguration(anyConfigurationDeletion());
    }

    @Test
    void failedGroupDeletionStillAttemptsConfigurationAndOtherOwner() {
        AutoScalingClient first = mock(AutoScalingClient.class);
        AutoScalingClient second = mock(AutoScalingClient.class);
        RuntimeException deletion = new IllegalStateException("group deletion failed");
        when(second.deleteAutoScalingGroup(anyGroupDeletion())).thenThrow(deletion);
        Exception actual = assertThrows(Exception.class, () -> {
            try (OwnedGroup firstGroup = new OwnedGroup(first, "owned");
                 OwnedGroup secondGroup = new OwnedGroup(second, "owned")) {
                firstGroup.create("us-east-1");
                secondGroup.create("us-gov-west-1");
            }
        });
        assertSame(deletion, actual);
        InOrder cleanup = inOrder(second, first);
        cleanup.verify(second).deleteAutoScalingGroup(anyGroupDeletion());
        cleanup.verify(second).deleteLaunchConfiguration(anyConfigurationDeletion());
        cleanup.verify(first).deleteAutoScalingGroup(anyGroupDeletion());
        cleanup.verify(first).deleteLaunchConfiguration(anyConfigurationDeletion());
    }

    @Test
    void assertionRemainsPrimaryAndIndependentCleanupFailuresAreSuppressed() {
        AutoScalingClient client = mock(AutoScalingClient.class);
        AssertionError primary = new AssertionError("lifecycle invariant");
        RuntimeException groupFailure = new IllegalStateException("group deletion failed");
        RuntimeException configurationFailure = new IllegalStateException("configuration deletion failed");
        when(client.deleteAutoScalingGroup(anyGroupDeletion())).thenThrow(groupFailure);
        when(client.deleteLaunchConfiguration(anyConfigurationDeletion())).thenThrow(configurationFailure);
        AssertionError actual = assertThrows(AssertionError.class, () -> {
            try (OwnedGroup group = new OwnedGroup(client, "owned")) {
                group.create("us-east-1");
                throw primary;
            }
        });
        assertSame(primary, actual);
        assertArrayEquals(new Throwable[]{groupFailure}, actual.getSuppressed());
        assertArrayEquals(new Throwable[]{configurationFailure}, groupFailure.getSuppressed());
    }

    @Test
    void successfulExplicitDeletionIsNotRepeatedDuringCleanup() throws Exception {
        AutoScalingClient client = mock(AutoScalingClient.class);
        try (OwnedGroup group = new OwnedGroup(client, "owned")) {
            group.create("us-east-1");
            group.deleteGroup();
        }
        verify(client).deleteAutoScalingGroup(anyGroupDeletion());
        verify(client).deleteLaunchConfiguration(anyConfigurationDeletion());
    }

    private static Consumer<CreateLaunchConfigurationRequest.Builder> anyConfiguration() {
        return any();
    }

    private static Consumer<CreateAutoScalingGroupRequest.Builder> anyGroup() {
        return any();
    }

    private static Consumer<DeleteAutoScalingGroupRequest.Builder> anyGroupDeletion() {
        return any();
    }

    private static Consumer<DeleteLaunchConfigurationRequest.Builder> anyConfigurationDeletion() {
        return any();
    }
}
