package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * AttachVolume decides and writes under the volume's lock, as DeleteVolume does, so a delete that
 * arrives after the attach found the volume available cannot remove it underneath the attach.
 */
class Ec2VolumeAttachmentLockTest {

    private static final String REGION = "us-east-1";
    /** Only bounds a broken run; the delete is over or parked on the lock long before this. */
    private static final long GIVE_UP_NANOS = TimeUnit.SECONDS.toNanos(60);

    @Test
    void aDeleteArrivingWhileTheVolumeIsBeingAttachedIsRefusedAndTheAttachmentStands() throws InterruptedException {
        InterruptibleVolumeStore volumes = new InterruptibleVolumeStore();
        Ec2Service service = Ec2ServiceFixtures.mockModeService(
                new InMemoryStorageFactory(Ec2ServiceFixtures.ACCOUNT).withStore("ec2-volumes.json", volumes));
        Instance instance = service.runInstances(REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null, null,
                List.of(), null, null).getInstances().getFirst();
        instance.setState(InstanceState.running());
        String volumeId = service.createVolume(REGION, instance.getPlacement().getAvailabilityZone(), "gp3", 8,
                false, 0, null, null, List.of()).getVolumeId();
        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
        Thread delete = new Thread(() -> {
            try {
                service.deleteVolume(REGION, volumeId);
            } catch (RuntimeException failure) {
                deleteFailure.set(failure);
            }
        }, "delete-volume-during-attach");
        // AttachVolume lists the stored volumes once, after it has found this one available, to
        // check the device name. The delete is issued right there and given all the room it needs.
        volumes.beforeNextListing(() -> {
            delete.start();
            awaitFinishedOrParked(delete);
        });

        VolumeAttachment attachment = service.attachVolume(REGION, volumeId, instance.getInstanceId(), "/dev/sdf");
        delete.join(TimeUnit.NANOSECONDS.toMillis(GIVE_UP_NANOS));

        assertFalse(delete.isAlive(), "the delete returned once the attach had released the volume");
        assertEquals("attached", attachment.getState());
        assertThat("the delete found the volume attached", deleteFailure.get(), instanceOf(AwsException.class));
        assertEquals("VolumeInUse", ((AwsException) deleteFailure.get()).getErrorCode());
        Volume stored = service.describeVolumes(REGION, List.of(volumeId), Map.<String, List<String>>of()).getFirst();
        assertEquals("in-use", stored.getState());
        assertThat(stored.getAttachments(), hasSize(1));
    }

    private static void awaitFinishedOrParked(Thread thread) {
        long giveUpAt = System.nanoTime() + GIVE_UP_NANOS;
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() - giveUpAt > 0) {
                throw new AssertionError("the delete neither finished nor waited for the volume's lock");
            }
            Thread.onSpinWait();
        }
    }

    /** Runs one armed action on the calling thread the next time the stored keys are listed. */
    private static final class InterruptibleVolumeStore extends InMemoryStorage<String, Volume> {

        private final AtomicReference<Runnable> beforeNextListing = new AtomicReference<>();

        void beforeNextListing(Runnable action) {
            beforeNextListing.set(action);
        }

        @Override
        public Set<String> keys() {
            Runnable action = beforeNextListing.getAndSet(null);
            if (action != null) {
                action.run();
            }
            return super.keys();
        }
    }
}
