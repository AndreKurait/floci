# Elastic Load Balancing (Classic, v1)

**Protocol:** Query (XML), form `POST` or SigV4-signed `GET` at `http://localhost:4566/`
with `Action=` and `Version=2012-06-01`.

Classic Elastic Load Balancing is the 2012-06-01 API behind `aws elb` and Terraform's `aws_elb`
resource. It is a **different API** from [ELB v2](elb.md) (ALB/NLB, 2015-12-01), even though both
are served from the same endpoint host and signed with the same `elasticloadbalancing` credential
scope.

## How Floci tells the two apart

Every Query-protocol request carries the API version it is speaking as the `Version` request
parameter, and that is what Floci routes on:

| `Version` | Answered by |
|---|---|
| `2012-06-01` | Classic (this page) |
| `2015-12-01` | [ELB v2](elb.md) |

Responses are emitted in the matching namespace —
`http://elasticloadbalancing.amazonaws.com/doc/2012-06-01/` for Classic. A Classic
`CreateLoadBalancer` returns `DNSName` and nothing else; Classic load balancers have no ARN and
are addressed by `LoadBalancerName` for their whole life.

If a hand-rolled client omits `Version`, Floci falls back to the request shape: an action unique
to one API decides, and for the action names both APIs define (`CreateLoadBalancer`,
`DescribeLoadBalancers`, `AddTags`, …) the presence of `LoadBalancerName` or `LoadBalancerNames`
selects Classic. With neither present the request goes to ELB v2.

## Supported Actions

| Action | Description |
|--------|-------------|
| CreateLoadBalancer | Creates a Classic load balancer with listeners, subnets or AZs, security groups, scheme, and tags. Returns `DNSName`. |
| DescribeLoadBalancers | Returns `LoadBalancerDescription` records, optionally filtered by `LoadBalancerNames`. |
| DeleteLoadBalancer | Deletes a load balancer; deleting one that does not exist succeeds. |
| CreateLoadBalancerListeners | Adds listeners. A conflicting listener on a port already in use is rejected with `DuplicateListener`. |
| DeleteLoadBalancerListeners | Removes listeners by `LoadBalancerPorts`. |
| ConfigureHealthCheck | Replaces the health check and re-arms Floci's checker. |
| RegisterInstancesWithLoadBalancer | Registers EC2 instances and returns the full registered set. |
| DeregisterInstancesFromLoadBalancer | Removes instances and returns the remaining set. |
| DescribeInstanceHealth | Returns `InstanceStates` for the named instances, or for every registered instance. |
| ModifyLoadBalancerAttributes | Updates cross-zone, access log, connection draining, connection settings, and additional attributes. Unsent members keep their current value. |
| DescribeLoadBalancerAttributes | Returns the full attribute structure. |
| ApplySecurityGroupsToLoadBalancer | Replaces the security groups. |
| AttachLoadBalancerToSubnets / DetachLoadBalancerFromSubnets | Adds or removes subnets and recomputes the availability zones and VPC. |
| EnableAvailabilityZonesForLoadBalancer / DisableAvailabilityZonesForLoadBalancer | Adds or removes availability zones. |
| AddTags / RemoveTags / DescribeTags | Tagging, keyed by `LoadBalancerNames`. |
| DescribeAccountLimits | Returns standard default Classic limits. |

## Not implemented

These Classic operations return `UnsupportedOperation` in the 2012-06-01 namespace rather than a
fabricated result:

`CreateAppCookieStickinessPolicy`, `CreateLBCookieStickinessPolicy`, `CreateLoadBalancerPolicy`,
`DeleteLoadBalancerPolicy`, `DescribeLoadBalancerPolicies`, `DescribeLoadBalancerPolicyTypes`,
`SetLoadBalancerPoliciesForBackendServer`, `SetLoadBalancerPoliciesOfListener`,
`SetLoadBalancerListenerSSLCertificate`.

## HTTP data plane

With `FLOCI_SERVICES_ELB_MOCK=false`, Floci's shared HTTP edge forwards requests addressed to an
existing Classic load balancer's DNS name or an unambiguous public Route53 alias/CNAME chain.
The alias target DNS name and hosted-zone ID must match the load balancer. Public endpoints
select the resource's account, independently of the client's API credentials.

Requests stream to healthy registered EC2 instances using the same address as their health
checks: the modeled container address, or the registered instance's DNS name for external compute.
DNS resolution happens outside the HTTP event loop and each connection uses the validated IP.
Requests use the listener's `InstancePort`. Host, method, raw path/query, authorization headers, request body
and response status/body are preserved. No external DNS target is contacted. Deregistration,
failed health checks, record changes and load-balancer deletion take effect without restarting.

This serves HTTP and HTTPS backends for HTTP listeners and HTTPS listeners behind Floci's existing
TLS edge. `InstanceProtocol=HTTPS` opens a TLS connection to `InstancePort`; it never falls back to
plaintext. Backend certificates are not authenticated, matching Classic ELB without its optional
backend authentication policy. The policy APIs remain unsupported. Backend TLS uses the runtime's
enabled protocols and ciphers rather than reproducing Classic ELB's legacy cipher list.

It does not install the listener's ACM certificate on that edge. TCP/SSL forwarding,
cookie stickiness and connection draining remain unsupported. An owned endpoint
with no healthy registered instance returns 503. Classic never forwards unresolved, deleted or
ambiguous endpoints. Hosts without a current Classic resource binding continue through the shared
edge's existing routing, including API Gateway and S3 custom domains. Multiple competing weighted
records, latency/failover records and independent Route53 health checks are not selected.

## Protected frontends

`FLOCI_SERVICES_ELB_VPC_FRONTENDS=true` enables an experimental packet path for an
internal IPv4 Classic load balancer across its selected subnets. It requires real ELB operation,
EC2 VPC networks, native private addresses and security-group enforcement. A
digest-pinned stock HAProxy process runs inside each node's firewall namespace
and binds its EC2 network interface's private address. Select at most one subnet
per Availability Zone, all in the same VPC. The embedded DNS source returns all
node addresses for the load balancer's native DNS name.

Frontend ingress is checked before TCP or TLS reaches a listener. HTTPS and SSL
listeners use their declared IAM or ACM certificate. Backend connections and the
configured health probes originate from the load balancer's private address, so
an instance's rule referencing the frontend security group is exercised too.
The shared HTTP edge refuses protected load balancers; it cannot bypass their
firewall.

Each node has independent health thresholds and admits only instances its own
namespace can reach. Cross-zone routing selects registered instances in all
enabled zones; with cross-zone routing disabled, each node selects only its own
zone. An instance is reported healthy after an eligible node applies its
healthy state to the listener. Retired nodes cannot update their replacements.

Adding or removing backend instances preserves the frontend processes and addresses.
Connection draining stops new work for a deregistered instance while existing
requests finish, up to the configured 1–3600 second timeout. With draining disabled,
deregistration closes those connections immediately. An unhealthy instance stops
receiving new work but keeps existing connections without the deregistration
deadline when draining is enabled. Re-registration cancels a pending retirement
and requires fresh health admission; other healthy instances retain their state.
See [connection draining](../design/classic-connection-draining.md) for the API
states, tests and Auto Scaling limitation.

This mode reconstructs the runtime on listener, health-check, subnet,
group-attachment, cross-zone or idle-timeout changes; the private address can change, and clients
must resolve it again after those updates. Security-group rule edits reconcile
the existing firewall directly. IPv6, access logging and
automatic DNS withdrawal of zones without healthy targets are not supported in
this mode. Route53 aliases need separate
qualification. Deletion retains the
interface reservation if retiring its namespace fails, allowing cleanup to be
retried without reallocating a live address.

With `FLOCI_NETWORK_VPC_ROUTING_ENABLED=true`, this mode also accepts
internet-facing Classic frontends in their selected IPv4 subnets. Protected callers use
native VPC routes and a public NAT gateway; the frontend sees that gateway's
allocated Elastic IP, and its ingress rules must permit that source.
Both VPCs need their required internet-gateway routes, including each public
frontend subnet. See [public routing and its limits](../design/vpc-public-routing.md)
and [node ownership across zones](../design/classic-multiaz.md).

## Behavior Notes

- An omitted `InstanceProtocol` defaults to `HTTP` for HTTP/HTTPS listeners and
  `TCP` for TCP/SSL listeners. Explicit backend protocols remain unchanged.
  Creation, listener additions, persisted readback and identical listener retries
  use the same effective value.
- A load balancer created in subnets without `SecurityGroups` gets the VPC's `default` security
  group, reported in `SecurityGroups` and `SourceSecurityGroup`. Floci uses the VPC's `default`
  group in the default VPC too, rather than a dedicated `default_elb_<id>` group. A load balancer
  created with only `AvailabilityZones` gets no security group.
- These security-group associations are metadata at the shared Classic edge. It does not enforce
  frontend security-group rules or route clients through modeled NAT gateways. A successful request
  through this edge does not validate those network prerequisites.
- Load balancer state is persisted through Floci storage, and on startup health checking is
  restarted for the load balancers of every account.
- A new load balancer gets AWS's default health check (`TCP:80`, interval 30, timeout 5,
  unhealthy 2, healthy 10) and AWS's default attributes (cross-zone off, access log off,
  connection draining off with a 300 second timeout, 60 second idle timeout).
- Health checks probe the instance's reachable local address. `HTTP:` and `HTTPS:` send a request
  to the target's path and require status 200; redirects and other success codes are unhealthy.
  `HTTPS:` uses TLS. `TCP:` requires a connection, while `SSL:` also requires a TLS handshake.
  The configured health timeout bounds the whole connection, handshake and response probe.
  Continuous response progress does not extend that deadline; expiry closes the probe socket.
  Health probes do not authenticate backend certificates.
- Between registration and the first successful check an instance reports `OutOfService` with
  reason code `ELB`, as it does on AWS.
- Health is tracked per account, so two accounts can each have a load balancer with the same name
  without either one changing the instance health the other reports.
- An Auto Scaling group that names Classic load balancers in `LoadBalancerNames` has its instances
  registered and deregistered automatically, so a Terraform `min_elb_capacity` wait can be
  satisfied.
- A listener's `SSLCertificateId` must name an existing certificate: an IAM server certificate ARN
  or an ACM certificate ARN. `CreateLoadBalancer` and `CreateLoadBalancerListeners` reject a
  missing one with `CertificateNotFound`, and a blank one on an HTTPS or SSL listener; an HTTP or
  TCP listener may carry a blank one, as Terraform sends. A certificate a listener references cannot be
  deleted until the listener is removed: `DeleteServerCertificate` returns `DeleteConflict`, and
  ACM's `DeleteCertificate` returns `ResourceInUseException` and lists the load balancer under
  `InUseBy`.

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ELB_ENABLED` | `true` | Enable or disable the Classic ELB service |
| `FLOCI_SERVICES_ELB_MOCK` | `false` | When `true`, never probe: a registered instance is `InService` immediately |
| `FLOCI_SERVICES_ELB_VPC_FRONTENDS` | `false` | Enable protected frontends; internet-facing frontends additionally require VPC routing |
| `FLOCI_SERVICES_ELB_PROXY_IMAGE` | Digest-pinned `haproxy:3.2-alpine` | Listener process used by protected frontends |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws elb create-load-balancer \
  --load-balancer-name my-classic-elb \
  --listeners "Protocol=HTTP,LoadBalancerPort=80,InstanceProtocol=HTTP,InstancePort=8080" \
  --subnets subnet-12345678

aws elb configure-health-check \
  --load-balancer-name my-classic-elb \
  --health-check Target=HTTP:8080/,Interval=10,Timeout=3,UnhealthyThreshold=2,HealthyThreshold=2

aws elb register-instances-with-load-balancer \
  --load-balancer-name my-classic-elb \
  --instances i-1234567890abcdef0

aws elb describe-instance-health --load-balancer-name my-classic-elb

aws elb delete-load-balancer --load-balancer-name my-classic-elb
```
