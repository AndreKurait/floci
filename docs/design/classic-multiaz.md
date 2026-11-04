# Protected Classic load balancer nodes across Availability Zones

Scope: protected IPv4 frontend nodes with independent routing and health state.

The protected Classic frontend originally owned one interface and one listener
process. Native templates can select several subnets, so the emulator owns
one node per enabled Availability Zone while keeping each node's security-group
boundary and health checks independent.

This change extends the existing `vpc-frontends` mode. It adds no configuration
switch, custom endpoint or application-specific routing rule.

## Resource and request behavior

- `CreateLoadBalancer` and subnet updates require at least one subnet and at
  most one subnet in each Availability Zone. Every subnet belongs to the same
  account, Region and VPC. Invalid updates leave the existing selection intact.
- Each selected subnet gets an owned ENI, protected network namespace and
  listener process. Public nodes use their own routed public address. DNS
  returns the addresses of the selected nodes.
- With cross-zone routing enabled, nodes route to registered instances in all
  enabled zones. With it disabled, each node routes only within its own zone.
  Instances in zones without a selected frontend are not eligible.
- Health thresholds and listener admission are independent for each node and
  instance. Probes execute independently and runtime health updates coalesce
  per node and instance. A successful check from one namespace cannot enable a server in
  another. The instance API reports whether an eligible node has actually
  admitted the instance; a queued result from a retired node cannot change the
  replacement's state.
- Reconciliation creates the complete replacement before retiring prior nodes.
  Deletion and shutdown retire every owned node. Failed retirement retains
  ownership for retry.

The implementation touches `ElbClassicFrontendManager`,
`ElbClassicHealthChecker`, `ElbClassicDnsRecordSource` and
`ElbClassicService`. Tests cover subnet admission, independent health state,
retirement races, and real packet routing through three protected nodes.
Existing private, public NAT, Lambda and overlapping-VPC cases remain regression
coverage.

## Qualification and remaining work

Packet qualification must exercise each advertised address, cross-zone changes,
subnet removal, blocked frontend ingress, recovery and normal resource cleanup.
Test clients must preserve the actual network failure rather than inject an
application delay.
Cross-zone qualification withdraws backend ingress one instance at a time and
waits for the configured health thresholds, then verifies every frontend can
reach each remaining eligible instance. Short sequences of HTTPS requests do
not establish a target-distribution guarantee.

This step does not implement connection draining, ALB, network ACLs or automatic
DNS withdrawal for an Availability Zone without healthy targets. Those are
separate capabilities; accepting a subnet list does not establish their parity.
The existing proxy uses round robin; AWS Classic HTTP and HTTPS use least
outstanding requests. Exact scheduling parity remains separate work.
Native application startup and a complete regional deployment remain separate
qualifications.

The AWS contracts are described in
[Classic load balancer subnets](https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/elb-manage-subnets.html),
[cross-zone routing](https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/enable-disable-crosszone-lb.html)
and [instance health checks](https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/elb-healthchecks.html).
