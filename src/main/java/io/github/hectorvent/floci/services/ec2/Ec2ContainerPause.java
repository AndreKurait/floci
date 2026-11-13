package io.github.hectorvent.floci.services.ec2;

/** The blocking pauses of the EC2 container manager, so a test can run them on a controlled timeline. */
@FunctionalInterface
interface Ec2ContainerPause {
    Ec2ContainerPause THREAD_SLEEP = Thread::sleep;

    void pause(long millis) throws InterruptedException;
}
