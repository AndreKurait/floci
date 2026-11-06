# Native guests requiring cgroup v1 service management

An AL2 image with systemd 219 cannot boot through a private Docker cgroup v2
namespace alone. Its init tries to mount the named `systemd` hierarchy, whose
creation the Linux kernel permits only in the initial cgroup namespace.

The explicit catalog runtime `guestRuntime: systemd-v1` delegates a private
subtree of that named hierarchy before starting the image's `/sbin/init`.
The image supplies native systemd, cloud-init, and its service
packages. Floci supplies no replacement service manager or application hooks.
Other catalog runtime modes retain their command, entrypoint, and PID 1 behavior.
Images declaring native cloud-init own their bootstrap in every native init mode;
Floci no longer also executes their user data through its compatibility bootstrap.

The guest has its own read-only `/sys/hypervisor/uuid`, using the EC2 prefix
documented in [AWS instance identification](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/identify_ec2_instances.html).
It is derived from the account, region and instance ID, so restarting an
instance preserves its identity and separate instances do not share it.
Stock cloud-init can recognize EC2 and negotiate IMDSv2 without reading host
DMI data. This is the Xen-style identification surface, not emulation of
Nitro hardware or a separate kernel. The UUID is not an authentication
credential: metadata tokens and instance-role sessions retain their own checks.

Each instance has a labelled helper with no network, `SYS_ADMIN`, `SYS_TIME`, and the
Docker host's PID and cgroup namespaces. This helper verifies the guest's
Docker identity before moving its init PID into its private service subtree.
The guest then creates a cgroup namespace rooted at that subtree and executes
native init. The guest has a private PID namespace and no bind mount of the
host's cgroup filesystem. Its existing security-group network namespace and
mandatory network capability restrictions remain in force.

Only the named hierarchy is created. CPU and memory controllers remain under
Docker's resource limits; legacy per-service resource-controller limits are
not emulated. A rootful Linux Docker daemon that permits this named hierarchy
is required, together with kernel time namespaces. Dropping `SYS_ADMIN` or
`SYS_BOOT` explicitly refuses this runtime.

The helper image includes a small statically linked `floci-ec2-init` binary.
It runs on the workload daemon's architecture and is copied into the guest's
temporary boot filesystem. Docker archive extraction cannot populate the running
guest's `/run` tmpfs, so the runtime stages the binary in a private rootfs directory,
moves it into `/run` through a guest exec and removes the staging directory before
releasing init. This runtime mounts `/run` with execution enabled, retaining
`nosuid,nodev`; its bootstrap disappears when the container stops.
It creates a private child time namespace and waits
for the helper to initialize monotonic and boot-time offsets. The helper checks
the target PID's Docker cgroup and PID-namespace identity before writing offsets.
The kernel refuses this operation for initial or already-entered namespaces.
The binary then joins that clock namespace, enters the delegated service cgroup
and executes the image's native init without replacing PID 1.

The guest receives no `SYS_TIME` capability. Wall-clock time and instance-role
credential expiry retain their normal meaning; monotonic time and `/proc/uptime`
start near zero on each boot. This lets native code verify reboots using the
operating system's clock. The helper uses no `clock_settime` operation.

`docker exec` enters Docker's original OCI time namespace and observes host
uptime. Native services inherit PID 1's private time namespace. Measure guest
uptime from a native service when validating reboot or stop/start; an external
Docker exec is not an observer of that clock.

The helper Dockerfile's sibling `.files` manifest declares its additional build
inputs. Both local helper builds and image publication include those inputs in
the recipe hash, so changing the bootstrap invalidates the cached image.
Native executables register the manifest and its inputs as classpath resources,
so automatic helper builds receive the same context as JVM builds.

StartInstances and RebootInstances release the same boot gate for the new
container PID. Termination removes only this instance's empty service groups
before retiring its helper. Kernel cgroup deletion is asynchronous: bounded
read-only remounts allow an empty named root to retire after its children are
released. Retirement stops when another guest still has groups. Other cgroup
namespace references can retain the shared hierarchy; its presence alone does
not mean this instance's groups remain. Another instance's groups are never removed.
Helpers carry the same namespace,
owner, region and instance identity used for ordinary EC2 lifecycle ownership.

The guest also receives `SYS_BOOT` in its private PID namespace. A native OS
reboot ends that namespace with Linux's reboot exit status; Floci restarts the
same owned container and releases its init gate again. This preserves the EC2
instance ID, attached role and filesystem. Stop and terminate take precedence
over queued reboot recovery. Poweroff and unrelated process failures are not
automatically restarted by this mechanism. Failed recovery is not reported as
a healthy instance to Auto Scaling.

Qualification must include native
cloud-init through IMDSv2, actual cron and SSM processes, concurrent guest
isolation, stop/start/reboot, failed-launch cleanup and emulator recovery.
Native patch Scan or reboot tests alone do not establish that a complete
Patch Manager installation succeeds.
