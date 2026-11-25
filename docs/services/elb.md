# Elastic Load Balancing v2

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

Floci supports Application Load Balancers (ALB) and Network Load Balancers (NLB) through the ELBv2 management API. The control plane is AWS SDK / CLI / Terraform compatible. HTTP, HTTPS, and TCP listeners can forward to registered instance and IP targets using the target's reachable local address.

Shutdown retires this emulator's ALB backend network helpers before shared VPC
routing stops. Stored load balancers and their network interfaces remain available
for runtime restoration on restart. Reset also retires those helpers; a failed
removal stays visible and does not prevent cleanup of the other owned helpers.
Startup restores the persisted VPC and subnet address plans before reconnecting
the load balancers and starting dependent ECS tasks.

### DNS names

By default a load balancer's `DNSName` is `<name>-<id>.elb.<FLOCI_HOSTNAME>`, which resolves to Floci
under the emulator's own DNS suffix. With `FLOCI_SERVICES_ELBV2_DNS_NAME_STRATEGY=aws` a new load
balancer gets the form AWS documents instead, for clients that check it:

| Type | `DNSName` |
|---|---|
| Application, internet-facing | `<name>-<digits>.<region>.elb.<dns-suffix>` |
| Application, internal | `internal-<name>-<digits>.<region>.elb.<dns-suffix>` |
| Network or Gateway | `<name>-<id>.elb.<region>.<dns-suffix>`, where `<id>` is the id in the ARN |

`<dns-suffix>` is the region's partition suffix, for example `amazonaws.com` or `amazonaws.com.cn`.
Floci's DNS answers such a name, and the same name with `dualstack.` in front, with the address the
default form resolves to. That answer comes ahead of `FLOCI_DNS_SPOOF_AWS_ENDPOINTS`, which would
otherwise give it the AWS API address. A name in those zones (`<region>.elb.<dns-suffix>` and
`elb.<region>.<dns-suffix>`) that no load balancer has gets NXDOMAIN, as on AWS, rather than being spoofed or
forwarded upstream; names outside them resolve as before. A client that uses another resolver needs those zones
forwarded to Floci's DNS. Listener selection by DNS
name and Route 53 aliases work the same with either form. A load balancer keeps the name it was
created with when the setting changes.

### Route 53 aliases

A public Route 53 `A` alias to an existing Application Load Balancer resolves to
Floci's listener address. The target DNS name and canonical hosted-zone ID must
both match the stored load balancer. DNS lookup finds the owner across accounts
without changing account-scoped management reads. Target names accept DNS casing,
a trailing dot, and the `dualstack.` prefix.

The IPv4 listener currently supplies no IPv6 address. Network and Gateway Load
Balancer aliases, health-evaluated aliases, and advanced routing policies remain
unsupported. Unknown, deleted, ambiguous, or mismatched targets cannot resolve
through a suffix match or upstream DNS fallback.

> Classic (v1) Elastic Load Balancing is a **different API** served from the same endpoint host —
> see [ELB Classic (v1)](elb-classic.md). Requests are routed by their `Version` parameter:
> `2015-12-01` here, `2012-06-01` there.

## DNS alias targets

The embedded DNS alias resolver identifies ALBs by their stored DNS name across
accounts and requires the target's canonical hosted-zone ID. Names are case
insensitive and may include a trailing dot or the `dualstack.` prefix. Ambiguous
owners, mismatched hosted zones and unsupported target types are refused.

A valid A target uses the bound emulator address with a 60-second TTL. An
internet-facing ALB uses the caller VPC's public API transport when native public
HTTPS routing is enabled on port 443. An unavailable address produces no data;
the resolver does not synthesize an IPv6 address.

## Supported Actions

### Load Balancers

| Action | Description |
|--------|-------------|
| CreateLoadBalancer | Creates an ALB or NLB in active state with persisted attributes and tags. |
| DescribeLoadBalancers | Lists or returns stored load balancers. |
| DeleteLoadBalancer | Deletes a load balancer and stops its listener sockets. |
| ModifyLoadBalancerAttributes | Updates persisted load balancer attributes. |
| DescribeLoadBalancerAttributes | Returns attributes stored for a load balancer. |
| DescribeCapacityReservation | Returns the stored capacity reservation fields for a load balancer. |
| DescribeProvisionedCapacity | Legacy capacity view for an application load balancer without a reservation. |
| SetSecurityGroups | Replaces the security groups associated with a load balancer. |
| SetSubnets | Replaces the subnets associated with a load balancer. |
| SetIpAddressType | Updates the IP address type stored for a load balancer. |

### Target Groups

| Action | Description |
|--------|-------------|
| CreateTargetGroup | Creates a target group with protocol, port, health check, and target-type settings. |
| DescribeTargetGroups | Lists or returns stored target groups. |
| ModifyTargetGroup | Updates mutable target group settings. |
| DeleteTargetGroup | Deletes an unused target group. |
| ModifyTargetGroupAttributes | Updates persisted target group attributes. |
| DescribeTargetGroupAttributes | Returns attributes stored for a target group. |

### Targets

| Action | Description |
|--------|-------------|
| RegisterTargets | Registers targets with a target group. |
| DeregisterTargets | Removes targets from a target group. |
| DescribeTargetHealth | Returns target health records maintained by Floci. |

### Listeners

Standalone ALB HTTP and HTTPS listeners use the API listener's configured
`quarkus.http.proxy` forwarding policy. With proxy address forwarding enabled,
the same Quarkus parser and trusted-proxy checks determine the client address
appended to `X-Forwarded-For`. Configure `trusted-proxies` explicitly and have the
upstream proxy replace caller-supplied forwarding headers with its actual peer.
The original Host, request target and listener scheme still determine routing
and backend requests; forwarded authority and prefix values do not replace them.
Resource listeners do not authenticate client certificates. When the API proxy
policy requires a trusted client certificate, these listeners retain the socket
peer identity and ignore forwarding claims. This does not change TLS certificates
or the ALB's append/preserve/remove attribute. Shared-port TLS routing retains its existing listener selection rules.

HTTPS listeners require a default certificate. Supply one when creating a listener or changing an HTTP listener to HTTPS; an omitted certificate list on an existing HTTPS listener keeps its default.

Listener responses carry the listener's default certificate in `Certificates`, and omit the
member when the listener has none. The certificate list `AddListenerCertificates` builds is
read through `DescribeListenerCertificates` rather than through a listener response, as the
reference describes.

With Floci TLS enabled, an ALB HTTPS listener on the AWS HTTPS proxy port
(normally `443`) shares that socket with AWS API callbacks. TLS SNI must uniquely
identify a listener by its load-balancer DNS name, host rules or certificate
hostnames. The selected ALB serves its own certificate and configured actions;
unmatched names and clients without SNI continue to the API backend. The public
Floci API port and non-HTTPS proxy-port collisions remain reserved. See
[TLS shared-port routing](../configuration/tls.md) for limits.


Creating an HTTPS or TLS listener without `SslPolicy` selects `ELBSecurityPolicy-2016-08`,
matching AWS's default for API, CLI and CloudFormation creation. An explicit policy
is preserved, and omitting the policy on subsequent modifications retains its current
value. These policies remain present in create, describe and modify responses;
listeners with no policy, such as HTTP listeners, omit `SslPolicy`.

Changing HTTPS to HTTP or TLS to TCP removes the SSL policy and certificate configuration.
Changing HTTP to HTTPS or TCP to TLS requires an explicit SSL policy and default certificate;
missing either rejects the modification without changing the listener. Changing other
properties without changing protocol retains the existing security settings.

Gateway Load Balancer listeners require omitted `Port` and `Protocol` fields on both create and modify requests. Their portless control-plane configuration does not provide GENEVE packet forwarding.

| Action | Description |
|--------|-------------|
| CreateListener | Creates a listener and its non-deletable default rule. |
| DescribeListeners | Lists or returns stored listeners. |
| ModifyListener | Updates a listener's configuration and default actions. |
| ModifyListenerAttributes | Updates persisted listener attributes. |
| DescribeListenerAttributes | Returns attributes stored for a listener. |
| DeleteListener | Deletes a listener and stops its socket. |
| AddListenerCertificates | Adds certificates to a listener. |
| RemoveListenerCertificates | Removes certificates from a listener. |
| DescribeListenerCertificates | Lists certificates associated with a listener. |

### Rules

| Action | Description |
|--------|-------------|
| CreateRule | Creates a non-default listener rule with conditions, actions, and priority. |
| DescribeRules | Lists or returns listener rules. |
| ModifyRule | Updates a listener rule's conditions and actions. |
| DeleteRule | Deletes a non-default listener rule. |
| SetRulePriorities | Atomically updates rule priorities after validating uniqueness. |

### Tags

| Action | Description |
|--------|-------------|
| AddTags | Adds tags to supported ELBv2 resources. |
| RemoveTags | Removes tags from supported ELBv2 resources. |
| DescribeTags | Returns tags for supported ELBv2 resources. |

### Metadata

| Action | Description |
|--------|-------------|
| DescribeSSLPolicies | Returns Floci's pre-seeded standard SSL policy list. |
| DescribeAccountLimits | Returns standard default ELBv2 account limits. |

## Behavior Notes

- `CreateLoadBalancer` retries with the same name, type, scheme, IP address type, subnets,
  security groups and tags return the existing load balancer without resetting its listeners
  or attributes. Omitted defaults and reordered subnet, security-group and tag collections
  describe the same configuration. A name used with different settings returns
  `DuplicateLoadBalancerName`.
- `DescribeLoadBalancers`, `DescribeTargetGroups`, `DescribeListeners`, `DescribeRules`,
  `DescribeAccountLimits` and `DescribeListenerCertificates` return their complete matching
  results without a `NextMarker`. These handlers do not yet split results using `PageSize`
  or resume from `Marker`.
- HTTP and HTTPS target groups use their configured protocol for both application requests
  and WebSocket upgrades. An HTTPS target requires a TLS handshake; a failed handshake
  never falls back to plaintext. Backend certificates may be self-signed or expired, as on
  AWS ALB. This trust setting applies only to the target clients and health checks.
- Backend connection and TLS handshake errors return 502 for HTTP requests and WebSocket
  upgrades. Connection and handshake timeouts use ten-second deadlines and return 504.
- HTTP and HTTPS health checks use their own configured protocol and port, independently
  of the target's application protocol. HTTPS health checks require a TLS handshake,
  preserve the configured success-code matcher, and do not follow redirects.
- One health-check deadline covers connection, TLS handshake and response headers.
  Partial bytes do not renew it. Expiry closes the probe and counts toward `Target.Timeout`.
- Health probes ignore JVM proxy settings and use the ALB's managed backend transport
  when native VPC networking is enabled. A timed-out lookup keeps
  its target and shared worker slot until the underlying work actually exits. This bounds
  background work even when name resolution ignores interruption.
- Backend TLS uses the runtime's supported protocols and ciphers. Floci does not reproduce
  AWS ALB's backend security-policy selection from the frontend listener policy.
- With native VPC networking and security-group enforcement enabled, each ALB subnet has a
  service-held ENI and protected namespace. HTTP/HTTPS requests, WebSocket handshakes, and
  health checks originate from those private addresses using the ALB's security groups.
  Target ingress can reference the ALB security group; ALB egress and VPC routes still apply.
  A missing backend namespace fails the connection instead of using the emulator's network.
  `SetSecurityGroups` updates these ENIs, `SetSubnets` reconciles them, and deletion removes
  their helpers before releasing their addresses. Saved ENI identities support restoration.
- The managed backend transport uses the stock Dante SOCKS5 server in the network helper.
  Only the emulator's control address may connect to that listener. Its return-route exception
  matches replies to that connection; target traffic follows the normal VPC rules.
  Custom network-helper images must include `sockd`, supplied by Alpine's `dante-server` package.
- ALB frontend listeners still share the emulator's network address. This backend transport
  does not enforce frontend security-group ingress or provide a native public ALB address.
  NLB TCP listeners retain their existing transport. Native ALB backend networking currently
  follows the native VPC implementation's IPv4 boundary.
- TCP listeners relay bytes bidirectionally without parsing HTTP or terminating TLS. TLS passthrough requires a TCP listener, with certificates configured at the target, not at the listener. Targets are selected per connection using round-robin among healthy targets, falling back to registered targets when none are healthy. The default TCP idle timeout is 350 seconds, configurable via the `tcp.idle_timeout.seconds` listener attribute (60 to 6000 seconds).
- TCP listeners require a dedicated local port. Floci still shares its address across load balancers, so a second TCP listener on an in-use TCP port registers successfully and logs a warning, but serves no traffic until the listener holding the port is deleted or moved, and ports reserved by Floci remain unavailable to TCP listeners. Address isolation and TLS-terminating NLB listeners are not provided by the TCP proxy.
- A TCP listener never takes a port away from another load balancer's HTTP or HTTPS listener. Those are selected by their load balancer's name and the port, as on AWS, where each load balancer has its own addresses: an HTTP listener by `Host` through the emulator's endpoint (a request to Floci's port whose `Host` names the load balancer and the listener port), and an HTTPS listener on the TLS proxy's port (`floci.tls.aws-https-port`) by SNI. On any other port the TCP listener keeps the local socket, so a client that connects to that port directly reaches the TCP listener.
- An asynchronous socket-bind failure retains the admitted listeners and retries while they
  remain configured. Retry delays grow from 100 milliseconds to five seconds; a successful
  bind resets the delay. Deleting the last listener or stopping Floci cancels its retry.
  An external process holding the port must still release it before Floci can serve traffic.
- HTTPS listeners terminate TLS using the configured ACM or IAM server certificates, with SNI certificate selection. When several load balancers share one local HTTPS port, clients must send SNI to identify the certificate they need; a connection without SNI cannot identify a particular load balancer before the TLS handshake.
- ACM certificates must be issued before attaching them. Reimporting an attached ACM certificate refreshes subsequent full TLS handshakes; existing connections keep their negotiated certificate. Reload does not invalidate TLS session caches: a resumed session can retain its previously authenticated certificate after replacement or removal until the session expires or is invalidated. ACM deletion and revocation are refused while a listener still references the certificate.
- Certificate replacement keeps the previous key material available to new handshakes while the replacement loads. A failed reload leaves the previous certificate active.
- Unusable saved listeners are retained for correction and logged individually so other saved listeners can start.
- HTTP or HTTPS listeners sharing a local port must use the same protocol. Requests select a load balancer by its DNS name, a public Route 53 alias, a hostname declared by its rules, or a certificate hostname; ambiguous hostnames return 502. TCP never serves a port alongside either protocol; see the TCP note above.
- Load balancer, target group, listener, rule, and tag state is persisted through Floci storage and rebuilt on service startup.
- Load balancers return `active` after their required backend namespaces are ready.
  Native ALBs remain `provisioning` during preparation. Failed preparation attempts clean up
  their owned resources; a cleanup failure retains the failed balancer and ENI identities for deletion.
- `CreateLoadBalancer` takes `Subnets` or `SubnetMappings`; specifying both is rejected with `ValidationError`. Application Load Balancers use the regional zone policy described below: by default, two distinct ordinary AZs are required in every region. Empty subnet requests remain invalid. Requests below the minimum return `ValidationError`: `At least two subnets in two different Availability Zones must be specified` (or `At least one subnet must be specified` when single-zone operation is enabled). Subnet-count checks precede lookup, and rejected requests create no load balancer. CloudFormation uses the same service validation; an update that keeps the balancer and omits subnets preserves its current subnets.
- `SetSubnets` uses the same regional policy for `Subnets` and `SubnetMappings`. Specifying both, an empty request, or a request below the effective AZ minimum is rejected before mutation. Repeated zones return `InvalidConfigurationRequest`: `A load balancer cannot be attached to multiple subnets in the same Availability Zone`. With no subnets, NLB and GWLB return `ValidationError`: `At least one subnet must be specified`. AWS does not document the one-subnet ALB update error; Floci uses the create error and order. Each selected subnet must belong to the load balancer's VPC. Network Load Balancers may use one subnet. The AWS requirement that a Gateway Load Balancer retain every existing subnet is not currently enforced.
- `DescribeProvisionedCapacity` is a Floci Query extension, using version `2015-12-01`
  and one `LoadBalancerArn`. Its `ProvisionedCapacity` has `MinimumLBCapacityUnits=0`,
  `Status=disabled` and `DecreasesRemaining=4`; `LastModifiedTime` is absent.
  Capacity is never reserved, so no daily decreases are consumed. Load balancer
  startup or health does not imply a pending capacity reservation. Per-account
  capacity quota overrides and reservation mutations are not implemented.
  The action rejects invalid scope and other types, and hides deleted or inaccessible
  load balancers. With IAM enforcement enabled, its policy action is
  `elasticloadbalancing:DescribeProvisionedCapacity`, scoped to the named load balancer.
  The public AWS ELBv2 endpoint rejected this action with `InvalidAction` in a live
  comparison using an ordinary account; it is not in the public SDK model. The public
  `DescribeCapacityReservation` action remains separate.
- EC2 refuses deletion of subnets and VPCs still referenced by load balancers with `DependencyViolation`. These account- and region-scoped dependencies follow persisted load balancer state; changing subnets or deleting the load balancer releases its old references. A CloudFormation update that names other subnets in `Subnets` or `SubnetMappings` moves the load balancer the way `SetSubnets` does, and a rolled-back update moves it back. An independent target group does not prevent subnet or VPC deletion.
- HTTP listener sockets are preserved when listener actions change and are restarted only when socket-level settings such as port change.
- Gateway listeners require `Protocol` and `Port` to be omitted on create and modify. Their control-plane response reports `GENEVE` without a port. CloudFormation preserves omitted gateway listener settings on create and update; other load balancer types retain their existing `HTTP` and port 80 defaults.
- Instance targets are resolved through EC2 instance private addresses so local load balancer traffic can reach containers. An instance port that an ECS task on that container instance publishes resolves to the task's container instead, which is how an ECS service in `bridge` or `host` network mode serves an `instance` target group (see [ECS](ecs.md)).
- Target health starts in `initial` state with reason `Elb.RegistrationInProgress` and is updated by Floci's health checker when monitoring is active.
- A newly registered non-Lambda Application Load Balancer target (HTTP/HTTPS target group) becomes healthy after its first successful check. Network and Gateway target groups retain `HealthyThresholdCount` for initial admission. The threshold applies to every group when recovering from `unhealthy`.
- Lambda target groups default to disabled health checks. A registered Lambda target with checks disabled reports `unavailable` and `Target.HealthCheckDisabled`. Checks may be enabled or disabled for Lambda groups; enabled checks currently report `healthy` without invoking the function. Disabling checks for any non-Lambda target group fails with `InvalidConfigurationRequest` before changing its settings. The message names the target type, for example `Health check enabled must be true for target groups with target type 'ip'`.
- Health checks probe the target group's `HealthCheckPort` when it is a port number, and each target's own port when it is `traffic-port` (the default).
- `HealthCheckProtocol` defaults to `HTTP` for `HTTP` and `HTTPS` target groups and for target type `alb`, and to `TCP` for every other protocol, as for Network and Gateway Load Balancers. A health check uses `HTTP`, `HTTPS` or `TCP`. `TCP` is refused for `HTTP` and `HTTPS` target groups and for target type `alb`. `GENEVE`, `TLS`, `UDP`, `TCP_UDP`, `QUIC` and `TCP_QUIC` are refused for every target group. Each refusal is a `ValidationError` naming the protocols the target group takes, such as `Health check protocol 'TCP' must be one of 'HTTP, HTTPS'`, from `CreateTargetGroup` and `ModifyTargetGroup` alike, and changes nothing.
- A `TCP` check has no `HealthCheckPath` or `Matcher`. Naming either for one, the default `TCP` check included, is refused with `InvalidConfigurationRequest` (`Health check paths are not supported for TCP health checks`, and the same for matchers). Moving a `TCP` check to `HTTP` or `HTTPS` takes both, or `ModifyTargetGroup` answers `ValidationError` `Path and return code are required for layer 7 health checks.`. Moving a check to `TCP` drops its path and matcher.

- For faster local readiness tests, set
  `floci.services.elbv2.max-health-check-interval-millis`, for example `100`.
  Zero, the default, preserves each target group's requested interval. A positive
  value caps the interval between probes without changing the settings returned
  by the API. Real HTTP probes, their timeouts, healthy and unhealthy thresholds,
  and the bounded probe queue still apply; one target never has overlapping probes.
  This deliberately accelerates local health transitions and does not reproduce
  AWS wall-clock timing. Negative values are rejected during startup. Registration
  probes start a target's cadence; an earlier group timer tick does not advance its
  health threshold. Probe ownership survives deregistration until the worker exits.
- `CreateTargetGroup` and `ModifyTargetGroup` validate `HealthCheckIntervalSeconds`
  against the AWS range of 5 to 300 before changing stored settings or active timers.
- Each `CreateListener` automatically creates an immutable default rule (`priority=default`, `isDefault=true`). This rule cannot be deleted; use `ModifyListener` to change its action.
- `PathPatternConfig.RegexValues` supports regex conditions in `CreateRule`, `ModifyRule`, `DescribeRules`, and CloudFormation `ListenerRule`. Each expression is 1 to 128 characters; a condition accepts either glob `Values` or `RegexValues`. Rules containing path regexes allow at most three match evaluations per condition and five across the rule. Expressions match the whole request path, excluding its query string, and multiple expressions are ORed. Matching uses RE2/J with bounded, linear-time execution. Lookarounds, backreferences, atomic groups, possessive quantifiers, recursion, subroutines, and Unicode character classes are rejected. Escaped or quoted literal text remains valid. Existing `Values` glob matching is unchanged.
- Mixed glob and regex values, invalid regex syntax, and excess per-rule match values return AWS's `ValidationError` messages. The syntax error does not echo the submitted pattern.
- Rule priorities are unique per listener: `CreateRule` and `SetRulePriorities` only reject a priority held by another rule on the same listener. `SetRulePriorities` is atomic: all priority assignments are validated before any change is committed.
- `DeleteTargetGroup` is rejected with `ResourceInUse` while the target group is referenced by any listener or rule.
- HTTPS and TLS certificate ARNs must refer to existing IAM server certificates or ACM certificates. `CreateListener`, `ModifyListener` and `AddListenerCertificates` reject a blank or unknown ARN with `CertificateNotFound`. When `ModifyListener` changes the protocol to HTTPS or TLS, it checks any inherited certificate ARNs too. An HTTPS listener also needs a default certificate: `CreateListener` and `ModifyListener` reject an absent or empty effective certificate list with `InvalidConfigurationRequest`. A referenced IAM server certificate cannot be deleted: `DeleteServerCertificate` returns `DeleteConflict` until the certificate is removed from the listener. Writes recording a new IAM certificate reference hold IAM's certificate lock, so the certificate cannot be deleted between the check and the write. A refused change preserves the listener's previous port, protocol, policy and certificates.
- `RemoveListenerCertificates` refuses removal of an HTTPS or TLS listener's default certificate with `OperationNotPermitted` (400) and preserves the certificate list. Use `ModifyListener` to replace the default certificate. Additional certificates can still be removed. A default certificate the certificate list holds as well is refused too: the published sentence, that you cannot remove the default certificate for a listener, names no exception for that case, so the refusal is Floci's reading.
- A listener's default certificate is one certificate. `CreateListener` and `ModifyListener` refuse a `Certificates` list naming more than one with `ValidationError` (400), the reference saying of that member on both operations that you must provide exactly one certificate. No published sentence gives the code a longer list is answered with, so the code is Floci's reading.
- The certificate list is kept apart from the default certificate. A listener created through the API starts with an empty certificate list, and `AddListenerCertificates` adds to it, taking more than one certificate in a call: the API member reads as one certificate per call, while the Application Load Balancers guide adds three in one call of its own, and the guide's example is what Floci takes. `ModifyListener` replaces the default certificate and leaves the certificate list standing, so a default that was added to the list keeps serving SNI after it is replaced. `AddListenerCertificates` answers with the certificate list the add leaves behind rather than with the certificates the call named.
- `DescribeListenerCertificates` names the default certificate with `IsDefault` true and each certificate in the certificate list with `IsDefault` false, so a certificate that is both appears twice. A listener's own `Certificates` member is its default certificate alone and carries no `IsDefault`, which is where the reference puts that member.
- A load balancer's listeners share a certificate quota of 25, counting the certificates in their certificate lists and not their default certificates, which is the published Application Load Balancer quota and the number `DescribeAccountLimits` reports. `AddListenerCertificates` refuses the certificate that would pass it with `TooManyCertificates` (400). A TLS listener on a Network Load Balancer is held to the same ceiling, which is Floci's reading rather than a published number of its own.
- `DeleteRule` is rejected with `OperationNotPermitted` for the default rule.
- `DescribeSSLPolicies` returns a pre-seeded list of standard AWS SSL policies (`ELBSecurityPolicy-*`).
- `DescribeAccountLimits` returns standard default limits (e.g., 50 load balancers per region, 100 target groups, etc.).
- Responses from HTTP targets preserve repeated header values, including multiple `Set-Cookie` headers. This also applies when a target rejects a WebSocket upgrade.
- Duration-based ALB target stickiness is applied for instance and IP targets when
  `stickiness.enabled=true` and `stickiness.type=lb_cookie` (the default type). Requests
  without a usable cookie select a target normally; subsequent requests carrying the
  cookie return to that target while it remains eligible. Deregistered or unhealthy
  targets are replaced using the existing health-aware selection, including fail-open
  behavior when every target is unhealthy.
- Sticky responses include opaque, authenticated `AWSALB` and `AWSALBCORS` cookies, without
  replacing application cookies. `AWSALBCORS` takes precedence and has
  `SameSite=None; Secure`. Both cookies use `Expires` seven days in the future; the
  configured `stickiness.lb_cookie.duration_seconds` (default 86400, range 1 to 604800)
  separately limits the age of a target binding. Each response renews the binding.
  Restarting Floci invalidates existing bindings. Application-cookie stickiness,
  weighted target-group affinity, and NLB stickiness are not implemented. Cookie
  stickiness is not applied with target-group cross-zone balancing disabled or `weighted_random` routing.
- `routing.http.preserve_host_header.enabled` (default `false`) controls Host normalization for HTTP,
  WebSocket and Lambda targets. By default the client host is retained: listener ports 80 and 443
  remove an incoming port; other listener ports are appended only when the client supplied none.
  An absolute HTTP(S) request target supplies the authority instead of the Host header. When enabled,
  the original Host headers are preserved, including repeated values. These are the
  [ALB Host header rules](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/edit-load-balancer-attributes.html#host-header-preservation);
  the backend address is never substituted for the client authority.
- `routing.http.xff_header_processing.mode` (default `append`) controls `X-Forwarded-For` for HTTP, WebSocket and Lambda targets. `append` adds the client address to any value the client sent, `preserve` forwards the client's header unchanged, and `remove` drops it. `routing.http.xff_client_port.enabled` (default `false`) adds the client port as `ip:port` or `[ipv6]:port`, in `append` mode only. `ModifyLoadBalancerAttributes` answers `InvalidConfigurationRequest` for any other value of either attribute.
- ALB HTTP and HTTPS listeners use the same `quarkus.http.proxy.*` forwarding policy as
  the shared API endpoint, including HTTPS listeners sharing the TLS proxy. With forwarding
  enabled and `trusted-proxies` configured, an admitted proxy's accepted `Forwarded` client
  address is the address appended to `X-Forwarded-For`; untrusted headers cannot replace
  the connection's client address.
- `X-Forwarded-Proto` uses the effective client connection after configured proxy handling.
  `X-Forwarded-Port` uses the original listener port. Both replace any value the client sent.
- `RegisterTargets` rejects link-local and cloud instance-metadata addresses (`169.254.0.0/16`, `fe80::/10`, `fd00:ec2::254`) with `InvalidTarget`, and the load balancer and its health checks refuse to connect to them. Loopback and private addresses stay reachable so a load balancer can front a neighbouring container.
- Requests forwarded to a target carry `X-Amzn-Trace-Id`. A request without the header gets a new `Root` field, a request with a `Root` field gets a new `Self` field, and an existing `Self` field is updated. Other fields are kept. Above 7 KB of request headers the header is rewritten to a new `Root` field. Floci does not send these traces to X-Ray.

## Forwarding and network-security boundaries

An accepted NLB, listener, or target-group declaration confirms control-plane storage.
It does not establish that the declared transport is implemented. ELBv2 supports local
HTTP, HTTPS, and TCP listener sockets; other declared listener protocols and target
types remain control-plane only.

| Declared listener protocol | Local forwarding behavior | Target types and transport |
|---|---|---|
| `HTTP` | Parses and forwards HTTP requests; supports WebSocket upgrades. | `instance` resolves an EC2 private address; `ip` uses the registered address. The target-group protocol selects HTTP or HTTPS to the backend. `lambda` invokes the registered function with an ALB event. |
| `HTTPS` | Terminates TLS with an ACM or IAM certificate, then handles HTTP as above. | Same targets and backend protocol selection as HTTP. |
| `TCP` | Relays bytes bidirectionally without HTTP parsing or TLS termination (enabling TLS pass-through to the target). Requires a dedicated local port. | `instance` resolves an EC2 private address; `ip` uses the registered address. Selected round-robin per connection. |
| `TLS` | Stored; certificate references are checked, but only `HTTPS` enables TLS on the local socket. TLS termination for NLB `TLS` listeners is unsupported (use a `TCP` listener for TLS pass-through). | No NLB TLS backend transport is implemented. |
| `UDP`, `TCP_UDP` | Stored declarations do not create UDP sockets or UDP forwarding. | Datagram targets are unsupported. |
| Gateway `GENEVE` | Portless control-plane metadata only. | No GENEVE packet forwarding. |

`alb` target-type declarations do not implement NLB-to-ALB chaining. Use the HTTP, HTTPS,
or TCP rows for local routing. Do not use a successful registration alone as evidence of
TLS, UDP, or NLB target-type compatibility. Unsupported transports are not consistently
rejected by the management API: clients can instead see failed TLS handshakes or timeouts
on the listener socket.

### Health checks

For non-Lambda targets, the checker probes `HealthCheckPort`, or the target's traffic
port when configured as `traffic-port`. With `HealthCheckProtocol=TCP` it opens a
connection and passes once the target accepts it; `HealthCheckPath` and the matcher are
not used. Otherwise it sends an HTTP or HTTPS GET to `HealthCheckPath`, and
`HealthCheckProtocol` selects TLS independently of the target's application protocol.
The response-code matcher (HTTP and HTTPS checks) and healthy/unhealthy thresholds
affect target health.
Lambda targets are not actively probed. In ELBv2 mock mode, sockets
and active monitoring are disabled. While a target group has a `healthy` target, the
proxy routes only to its `healthy` targets, so a newly registered target in `initial`
state gets no requests until its first health check passes. When no target is
`healthy`, the proxy fails open to every registered target, including `initial` ones
and those in a group with health checks disabled, so a successful request alone does
not prove that a health check passed.

### Security groups and PrivateLink

Load-balancer security-group IDs are stored and returned by `CreateLoadBalancer`,
`DescribeLoadBalancers`, and `SetSecurityGroups`. The ELBv2 proxy and health checker do
not evaluate security-group ingress or egress rules, target security-group references,
network ACLs, or VPC/subnet routing. An `internal` scheme does not create a private
network boundary on the host. These declarations do not isolate reachable targets.
The ALB backend path above is the exception for application load balancers.

Network load balancers keep their existing transport: Floci dials NLB targets and sends
their health checks from its own container address. With
`FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_ENABLED=true`, a target ingress rule that
references a security group of a network load balancer in the target's VPC also accepts
Floci's address on the Docker network it shares with the target (the target's VPC network
where Floci joined it, otherwise Floci's main network), on that rule's protocol and ports.
On AWS the NLB's nodes are in that group. A target that admits neither the group nor a CIDR
containing that address still refuses the traffic, and its health check reports
`Target.Timeout`. With isolated VPC address spaces, Floci dials a protected NLB target of the
target group's VPC at its address on Floci's main network, because its private address lives
only inside its namespace. NLB traffic never reaches a protected endpoint of another VPC or
account, any protected endpoint from a target group without a VPC, or a host name registered
as an ip target, and never a protected target whose ingress does not admit the traffic's port
from that load balancer's own groups (or from Floci's address by CIDR), so a second NLB in the
VPC does not reach a target that admits only the first: the health check fails and the
connection is closed. This is decided by the
listener or load balancer the traffic is for, so a target group shared with an ALB keeps
these checks for the NLB's traffic. Application load balancer traffic gets none of this. Creating or deleting a network load balancer,
`SetSecurityGroups` and `SetSubnets` update the targets' rules.

Limits of the NLB rule:

- Every network load balancer shares Floci's address on a network. Such a rule therefore
  also admits other traffic Floci sends to that port from that address, from any account,
  including Floci's own HTTP integrations (SNS, EventBridge API destinations, API Gateway)
  and an ALB that has no managed VPC interfaces (native VPC networking off).
- With isolated address spaces, a rule that admits only the VPC CIDR still refuses the
  traffic, and so does an NLB without security groups.
- The NLB's own ingress and egress rules are not applied.
- Floci running outside a container admits nothing this way: it reaches containers from a
  Docker gateway that every host process shares.

`EnforceSecurityGroupInboundRulesOnPrivateLinkTraffic` is not handled by
`SetSecurityGroups`, is not returned as an enforcement setting, and does not filter
traffic. PrivateLink endpoint declarations do not add a separate ELBv2 ingress path
or apply the AWS client-IP-based filtering contract. On AWS, security groups filter
both client and target traffic, and the PrivateLink setting controls inbound-rule
application; see [NLB security groups](https://docs.aws.amazon.com/elasticloadbalancing/latest/network/load-balancer-security-groups.html).

No allowed/denied traffic checks are claimed for these unimplemented controls.
Use host firewalls, Docker network isolation, and published-port restrictions to
control access to the emulator. Test AWS security-group and PrivateLink enforcement
against AWS when that behavior is part of the application contract.

## ARN Format

```
arn:aws:elasticloadbalancing:{region}:{account-id}:loadbalancer/app/{name}/{hex16}
arn:aws:elasticloadbalancing:{region}:{account-id}:targetgroup/{name}/{hex16}
arn:aws:elasticloadbalancing:{region}:{account-id}:listener/app/{lb-name}/{lb-id}/{hex16}
arn:aws:elasticloadbalancing:{region}:{account-id}:listener-rule/app/{lb-name}/{lb-id}/{listener-id}/{hex16}
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a load balancer on two of the default VPC's subnets
aws elbv2 create-load-balancer \
  --name my-alb \
  --type application \
  --scheme internet-facing \
  --subnets subnet-8068005281bb3dc6a subnet-c8ba5d8ce63635cea

# Create a target group
aws elbv2 create-target-group \
  --name my-targets \
  --protocol HTTP \
  --port 80 \
  --target-type instance

# Register targets
aws elbv2 register-targets \
  --target-group-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-targets/abc123 \
  --targets Id=i-00000000001,Port=8080

# Create a listener with a default forward action
aws elbv2 create-listener \
  --load-balancer-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/my-alb/abc123 \
  --protocol HTTP \
  --port 80 \
  --default-actions Type=forward,TargetGroupArn=arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-targets/abc123

# Add a path-based routing rule
aws elbv2 create-rule \
  --listener-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/my-alb/abc123/def456 \
  --priority 10 \
  --conditions Field=path-pattern,Values='/api/*' \
  --actions Type=forward,TargetGroupArn=arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-targets/abc123

# Describe load balancers
aws elbv2 describe-load-balancers

# Describe target health
aws elbv2 describe-target-health \
  --target-group-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-targets/abc123

# Tag a resource
aws elbv2 add-tags \
  --resource-arns arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/my-alb/abc123 \
  --tags Key=env,Value=dev

# Clean up
aws elbv2 delete-listener \
  --listener-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/my-alb/abc123/def456
aws elbv2 delete-load-balancer \
  --load-balancer-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/my-alb/abc123
aws elbv2 delete-target-group \
  --target-group-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-targets/abc123
```

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ELBV2_ENABLED` | `true` | Enable or disable the ELBv2 service |
| `FLOCI_SERVICES_ELBV2_BACKEND_KEEP_ALIVE` | `true` | Reuse backend connections for ordinary HTTP forwarding. Set to `false` to open a fresh connection per request. |
| `FLOCI_SERVICES_ELBV2_BACKEND_KEEP_ALIVE_TIMEOUT_SECONDS` | `60` | Default timeout for unused backend HTTP/1.x connections in the pool, in seconds. Non-negative; `0` disables expiry. A target's `Keep-Alive: timeout=...` overrides this value. |

### Backend connection reuse

Ordinary HTTP forwarding reuses backend HTTP/1.x connections by default. For local
targets that cannot reliably serve multiple requests on one connection, set
`FLOCI_SERVICES_ELBV2_BACKEND_KEEP_ALIVE=false` before starting Floci. This applies
to ordinary requests to HTTP and HTTPS targets, independently of client-to-listener
keep-alive. WebSocket upgrades keep their dedicated connection, and TCP forwarding
is unaffected.

To expire unused pooled connections sooner, set
`FLOCI_SERVICES_ELBV2_BACKEND_KEEP_ALIVE_TIMEOUT_SECONDS` below the target's idle
deadline. The default remains 60 seconds, matching the existing Vert.x client
default. This is the [Vert.x HTTP/1.x keep-alive timeout](https://vertx.io/docs/4.5.35/apidocs/io/vertx/core/http/HttpClientOptions.html#setKeepAliveTimeout-int-),
not a request timeout or a maximum connection age: it does not interrupt an active
response. Vert.x honors a target's advertised `Keep-Alive` timeout in preference to
this default. Choose consistent target settings, or disable reuse if a fresh
connection is required for every request. The pool's periodic cleanup can delay
physical closure of an unused connection. This setting is unused when reuse is off
and is separate from the ALB `idle_timeout.timeout_seconds` attribute.

These are local transport controls, not AWS load-balancer attributes. Disabling
reuse is a mitigation, not evidence of an AWS compatibility defect. A target that
closes its connection while a request is outstanding can still produce a 502,
as described in [AWS's ALB troubleshooting guide](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-troubleshooting.html#http-502-issues).
Floci does not retry forwarded requests when the target's outcome is uncertain,
including a POST whose body the target consumed before closing.

## Listener Ports

Listener sockets bind on the Floci host. Expose any listener ports you need in Docker Compose when Floci itself runs in a container, similar to RDS and ElastiCache proxy ports.

The shared HTTP API endpoint also forwards requests whose Host names an existing load balancer
or a public Route 53 alias/CNAME chain to one. An explicit Host port selects that listener;
otherwise HTTP selects port 80 and HTTPS selects port 443. This supports an external TLS
front door forwarding to the HTTP listener. Only HTTP listeners participate in shared-endpoint
dispatch; HTTPS and TCP listeners retain their dedicated transports. An explicit port zero does
not select the default listener. The shared endpoint does not use the single-listener
fallback for unrelated hosts, so ordinary AWS API requests retain their existing handlers.
Configured API hostnames and emulator DNS suffixes also retain ordinary API handling when
a public alias uses the same name. Dedicated listener ports continue to resolve those aliases.
After selecting a listener, target groups and load-balancer attributes are read in that
listener's owning account. Unsigned data requests therefore work for load balancers created
outside the emulator's default account.

### Two load balancers on the same port

In AWS each load balancer has its own DNS name and addresses, so two of them can both listen on
port 80 and never interact: the network picks the load balancer, and a `host-header` rule only picks
a rule inside one listener. Floci serves every load balancer from a single socket per port, so the
Host header has to carry both decisions. A template that puts a public and an internal load balancer
on port 80 is correct for AWS and stays accepted here, but the request has to name the one it wants.

A listener is chosen in this order:

1. The Host header equals a load balancer's own DNS name (`{name}-{id}.elb.localhost.floci.io`, or the
   AWS form; see [DNS names](#dns-names)).
2. A public Route 53 A/AAAA alias or CNAME chain resolves the Host to a load balancer on the port.
   A and AAAA aliases can coexist when they identify the same target. Alias target hosted-zone
   IDs, with or without `/hostedzone/`, must match that load balancer's canonical zone.
   Fully qualified targets and the `dualstack.` DNS prefix are supported.
   Broken chains, conflicting answers, delegated names, and aliases to a removed or different
   load balancer cannot fall back to another listener.
3. Exactly one listener on the port has a rule whose `host-header` condition matches the Host
   header. This uses the same matching as the rule evaluation that follows, so it is
   case-insensitive, ignores the port and final dot, and applies AWS's wildcards: `*` matches 0 or more
   characters and `?` matches exactly 1, which means `*.example.com` claims `test.example.com`
   but not `example.com`. `HostHeaderConfig.RegexValues` is not supported, by rule evaluation or
   by selection, so a load balancer that names itself only through a regex cannot be selected
   this way.
4. Exactly one listener has a certificate hostname matching the Host header.
5. Only one listener is on the port, in which case it answers any Host header.

If none of those resolve, and in particular when two load balancers declare the same hostname or
neither declares any, the request answers `502 No listener for host`. Floci logs a warning naming
both load balancers the first time a second one takes a port, because the targets behind them stay
healthy and nothing else reports the collision.

Public Route 53 records select their actual load balancer before its rules run. Rule evaluation
still sees the original Host and request path. For names without such records, distinct
`host-header` rules or distinct listener ports can disambiguate the shared local socket.

### Target health scheduling

ELBv2 probes independent targets concurrently, with up to 32 probes running and one
outstanding probe per target. Repeated target registration preserves probe cadence.
Changing the health interval reschedules monitoring, and late results from old
registrations or settings cannot change current health. Configured healthy and
unhealthy thresholds still apply to real HTTP results.

## Application Load Balancer zone policy

One Floci instance serves multiple regions. The ALB policy uses each request's
region, including CloudFormation provisioning, rather than the server's default region.
By default, ordinary ALB subnets require two distinct Availability Zones in every
region, including Osaka (`ap-northeast-3`). Empty subnet lists remain invalid.
Each selected subnet must belong to a different AZ, even when a single AZ is allowed.
This applies to CreateLoadBalancer and SetSubnets; NLB and GWLB rules are unchanged.
Local Zone and Outpost-specific placement is not implemented by this setting.

To override this rule globally, set
`FLOCI_SERVICES_ELBV2_ALB_ZONE_RULE=allow-single-zone`.
The default is `aws`. Individual regions can override the global setting in YAML:

```yaml
floci:
  services:
    elbv2:
      alb-zone-rule: allow-single-zone
      alb-zone-rule-by-region:
        us-east-1: aws
        ap-northeast-3: aws
```

Precedence is per-region setting, global setting, then AWS behavior.
A per-region `aws` entry restores AWS behavior even when the global rule is overridden.
These explicit overrides intentionally change local compatibility; they do not
change the AWS API shape or require a separate Floci process for each region.

CloudFormation rollback can restore a saved legacy ALB subnet list with repeated Availability Zones. This internal snapshot path still checks subnet existence, the zone minimum, and VPC membership; new API and stack-update requests continue to reject repeated zones.

Classic VPC frontend ownership is stored in `elb-classic-frontends.json` through the
ELB storage backend. An allocation intent precedes interface creation; internal
interface metadata allows recovery if the interface identity was not yet recorded.
Cleanup retains ownership until dependent resources and the interface are removed.
Memory mode does not retain this journal across process exit, and hybrid mode keeps
its configured asynchronous flush semantics. Keep the ELB and EC2 persistent data
together when restoring a deployment. Process-crash and packet recovery require
separate runtime qualification.

Path-pattern `RegexValues` use a linear-time regex engine for admission and full-path matching.
Lookarounds, backreferences, atomic groups, possessive quantifiers, recursion, subroutines,
and Unicode character classes are rejected. Escaped or quoted literal text remains valid.
Existing `Values` glob matching is unchanged.
