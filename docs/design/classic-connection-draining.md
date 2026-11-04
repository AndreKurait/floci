# Protected Classic connection draining

Scope: backend registration, deregistration and health changes on the existing
IPv4 `vpc-frontends` packet path. The shared HTTP edge has separate limitations.

Changing instance membership updates the pinned HAProxy process through its
administrative socket. It preserves the node's ENI, TLS listener and existing
connections. New servers begin in maintenance until that node's health checks
admit them. An unaffected healthy server keeps its health state.

HTTP and HTTPS listeners terminate HTTP/1.x. TLS listeners do not advertise
ALPN, matching Classic's
[published protocol support](https://aws.amazon.com/elasticloadbalancing/features/).
HAProxy's default HTTP/2 negotiation must not turn a closed Classic connection
into a retryable HTTP/2 stream. Packet tests offer HTTP/2 and verify HTTP/1.1
before exercising the drain.

With `ConnectionDraining.Enabled=true`, deregistration puts each removed server
in maintenance. Active and queued sessions finish until the configured timeout;
the backend is removed immediately when there is no work left. The timeout is
1–3600 seconds and defaults to 300. At the deadline, remaining sessions close.
With draining disabled, deregistration closes them immediately.

During draining, `DescribeInstanceHealth` reports `InService` with
`Instance deregistration currently in progress`. Once finished, an explicitly
queried existing instance is `OutOfService` with
`Instance is not currently registered with the LoadBalancer.` Unknown EC2
instances remain invalid.

Unhealthy servers stop accepting new work. With draining enabled, their existing
connections do not use a deregistration deadline. Disabling draining also closes
those connections. Registering an instance again cancels its retirement and
starts new health admission. A late health result from the old registration
cannot re-enable it.

Each node reads its actual proxy's session counts. Missing or malformed
statistics and rejected administrative commands cannot claim successful
completion. Transient server-deletion failures retain ownership for retry.
Deletion and shutdown still retire the owned proxy and namespace.

## Validation and remaining work

Unit tests cover deadlines, queued sessions, early completion, health changes,
re-registration, old health results and partial cleanup. Packet tests hold a
real backend request through NAT, a TLS frontend and backend security groups;
they verify new work reaches a remaining healthy instance while the held
request completes or the frontend closes it. The application's FIFO supplies
an explicit release signal, not a simulated network timeout.

Auto Scaling scale-in and instance refresh still need to wait for this drain
before terminating a guest. Native application rollout and scale are therefore
not qualified by the direct deregistration tests. Listener, health-check,
subnet, group-attachment, cross-zone and idle-timeout changes still reconstruct
the frontend and can interrupt connections. Process restart recovery, ALB,
stickiness and exact Classic HTTP scheduling remain separate work.

The AWS contract is documented in
[connection draining for Classic Load Balancers](https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/config-conn-drain.html).
