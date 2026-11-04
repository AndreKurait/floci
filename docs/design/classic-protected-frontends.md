# Protected Classic frontends

Status: the internal IPv4 packet path passed its local JVM integration regression.
The public NAT path and native-image runtime remain separate qualification steps.

The shared HTTP edge cannot enforce a load balancer's frontend security group
before TCP and TLS. Its backend connections also originate from the emulator
process, so they cannot prove an instance's load-balancer security-group rule.

The first implementation serves an internal, IPv4 Classic load balancer in one
subnet. Its native EC2 network interface owns the address returned by DNS. The
existing firewall helper installs security-group rules before a stock HAProxy
process starts in that namespace. HAProxy owns listener sockets, certificate
termination and forwarding. Floci retains the native listener and health-check
configuration, account/Region ownership and lifecycle.

The existing Classic health checker keeps its configured consecutive-success
and failure thresholds. Its probes execute inside the same namespace as backend
connections, with the configured whole-probe deadline. No probe in the emulator
process can admit a backend through a different network path.

Creation and replacement must finish before an API success. Replacements prepare
new owned resources before retiring the old endpoint. Failure keeps the old
declaration and reports an error; retiring a partially failed runtime can interrupt
its listener. Cleanup preserves the interface reservation until its namespace is
gone. Persisted load balancers reconstruct their runtime
before exposing DNS. Updates to security-group rules use the existing firewall
reconciler. Listener, group attachment, subnet and backend changes reconcile the
owned runtime.

The ordinary shared edge refuses requests for a protected load balancer. It is
not a fallback route around the network boundary.

## Qualification

`ElbClassicVpcPacketIntegrationTest` uses the AWS SDKs to create the VPC, subnet,
caller and backend EC2 instances, groups, IAM certificate and load balancer.
It checks verified frontend TLS and the load balancer's private address as the
backend's observed peer. Removing frontend ingress and putting the caller rule
only on the backend group leaves a healthy backend unreachable by that caller:
its two-second network deadline expires without an application request. Restoring
frontend ingress succeeds. The test also deletes the listener and confirms that
cleanup retains the interface while another container still owns the namespace,
including when the original helper has already been removed.

The selected run passed this packet regression and 62 existing/configuration
checks without skips. It uses a small HTTP test application and the in-process
DNS source; wire DNS, aliases, multi-account packet isolation, native-image runtime,
and a production application's unchanged timeout are not qualified by this run.

## Following milestones

- Multiple subnets and independent per-node health.
- Internet-facing frontend addresses and Route 53 alias resolution.
- Effective route-table forwarding through the selected public NAT, with its
  allocated Elastic IP as the observed source.
- Native workload deployment, missing frontend allowlisting, unchanged client
  timeout, recovery and repeated cleanup.
- Crash recovery between interface allocation and namespace creation.

Unsupported topologies must refuse protected-mode admission. These milestones
are not passed by creating metadata, shortening deadlines or forwarding through
the shared HTTP edge.
