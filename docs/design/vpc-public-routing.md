# VPC public routing milestone

Status: AWS SDK lifecycle and protected EC2 and Lambda packet regressions qualified on
Linux amd64. Full native application qualification remains incomplete.

Public routing connects protected IPv4 workloads in separate VPCs to managed
internet-facing Classic load balancers through a public NAT gateway. AWS EC2
route tables, NAT gateways, Elastic IPs and internet-gateway attachments supply
the topology. Security groups continue to supply admission independently.

The runtime owns a Docker bridge without IP masquerading for public transport and one network
namespace per NAT gateway. A namespace attaches to its native VPC at the private
address reported by `DescribeNatGateways`. Linux forwarding and connection
tracking translate outbound traffic to the allocated Elastic IP. A public
frontend attaches to the transport bridge, maps its public address to its private
listener, and forwards to its backend from the frontend's private address.
Docker transport addresses are implementation details. They do not replace the
source Elastic IP observed by a listener.

Docker's internal bridge filter rejects the translated public addresses because
they are outside its transport subnet. The transport therefore uses an ordinary
bridge. Each participating namespace enforces the native routes and a default-deny
filter. NAT namespaces have no default route or local application traffic, and
frontends discard the transport interface's default route.

Protected namespaces admit local routes, explicit platform control connections
and routes to managed public destinations. A private workload needs an active
route to an available public NAT gateway in its own VPC. The NAT's subnet needs
an active route to an internet gateway attached to that VPC. A public frontend
needs its own internet-gateway route. Route selection uses the subnet's explicit
association or the VPC's main table, then longest prefix match. Unsupported
targets and absent routes drop traffic instead of falling back to Docker's
default internet route.

An external client on the public transport can reach a protected frontend only
on ports its owning service explicitly publishes. Publication checks the
account, region, ENI and live namespace owner. Traffic still requires the
frontend's security group and an active internet-gateway route to the caller;
a more-specific NAT, blackhole or unsupported route denies it. Unpublished
ports remain closed even if a process is listening and the security group
allows them. Withdrawing all ports closes external admission. This routing
contract does not itself start listeners or attach external client containers.
`VpcExternalFrontendPacketIntegrationTest` verifies these checks with real
packets, including denial and recovery without delivering denied requests.

With `FLOCI_NETWORK_VPC_ROUTING_PUBLIC_AWS_API=true`, the transport also has an
owned TCP bridge for the public AWS APIs. Spoofed AWS API DNS names resolve to
`192.0.2.1` for known VPC clients; queries outside a VPC retain the usual emulator
address. Stored public records, including Classic frontend addresses, retain
precedence. The API address is distinct from the Classic and NAT address pools.
The bridge forwards HTTP and TLS bytes to Floci's container-reachable API
listener. The client keeps its AWS hostname, protocol and certificate verification.
Use the ordinary TLS configuration and install Floci's CA for HTTPS.

### Owned HTTPS listeners through the public bridge

`FLOCI_NETWORK_VPC_ROUTING_PUBLIC_AWS_API_NATIVE_HTTPS=true` sends the bridge's
port 443 connections to Floci's extra AWS HTTPS listener. TLS must be enabled,
and `FLOCI_TLS_AWS_HTTPS_PORT` must select a different port from the API listener.
The HTTP upstream retains its configured address and port. This option defaults
to false.

The bridge forwards the original TLS bytes and supplies the routed caller's
public source address through a PROXY preface. Floci accepts that preface only
from its owned bridge and only for callers admitted by the current route
configuration. Revocation invalidates partially received prefaces. Unknown
callers, malformed prefaces and incomplete headers cannot reach a backend.
Direct connections retain their socket identity. Existing NAT, internet-gateway
and security-group checks still apply; the bridge exposes only ports 80 and 443.

Owned HTTPS load-balancer names select their registered SNI listener. Other
names retain the API backend unless `FLOCI_TLS_AWS_HTTPS_FALLBACK_PORT` selects a
separate TLS destination. Its host defaults to loopback and can be set with
`FLOCI_TLS_AWS_HTTPS_FALLBACK_HOST`, which requires an explicit local IPv4
address. Proxy loops and local private API destinations are rejected.
`FLOCI_TLS_AWS_HTTPS_FALLBACK_HOSTS` optionally assigns exact DNS names to this
destination before owned SNI lookup. These names do not imply wildcard matches.

The external TLS destination receives the original TLS bytes.
`FLOCI_TLS_AWS_HTTPS_FALLBACK_PROXY_PROTOCOL=true` additionally supplies the
caller identity to a destination configured to trust only Floci's socket.
This option defaults to false. Clients keep certificate and hostname
verification; their trust store must include the selected listener's issuer.
These options select transport destinations and do not implement a peer's
protocol or authorization.

With the native public HTTPS bridge enabled on port 443, public Route 53 A
aliases to owned internet-facing Application Load Balancers use `192.0.2.1`
for known VPC callers. This includes weighted aliases and does not require
listing generated alias names in configuration. The stored load balancer and
canonical hosted-zone ID must validate first. Internal load balancers, callers
outside a known VPC and disabled native HTTPS routing retain their existing
address selection. Literal DNS records are unchanged. This serves the shared
HTTPS listener; it does not open arbitrary load-balancer ports on the bridge.

### Declared public endpoint handoff

The transport handoff is implemented. Application qualification remains separate.

A caller may select one explicit upstream IPv4 address and separate HTTP and
HTTPS ports for the public API bridge. The default remains Floci's listener.
This lets a local test's existing front door dispatch declared peer hostnames
and forward AWS requests to Floci without changing the application's hostnames,
credentials, or TLS verification. The configured upstream supplies its TLS
certificate; the caller must trust that certificate's issuer.

For known VPC clients, recursive DNS answers pointing at this configured upstream
use the bridge's public address. This lets an existing DNS resolver remain the
authority for peer readiness and suffix matching. Negative answers remain
negative; unrelated addresses, native records, private-zone answers and explicit
Resolver rules retain their behavior. Only matching A records in the answer
section change. Names, TTLs and other records remain intact; truncated, malformed
or signed responses are not partially rewritten. Non-VPC clients receive the
original upstream response.

The bridge still admits only ports 80 and 443 through the caller's native NAT,
internet-gateway route and security-group policy. Its outbound connections are
limited to the selected address and ports. It does not give guests a route to
the upstream's Docker address or a general internet route.

Additional explicitly named public hosts may use this same destination for
known VPC clients. Exact names may contain underscores or a single label;
literal IP addresses are not names. Exact names do not imply suffix or wildcard matches.
Native DNS records and forwarding rules retain precedence. Outside a VPC, exact
declared peers use owned DNS records or the configured upstream resolvers, including
names beneath AWS domain suffixes. Broad AWS spoofing must not replace a declared
peer's address with the emulator's API listener. Other AWS names retain the
usual emulator address. This configuration supplies a transport
destination, not an implementation of any peer protocol or authorization.

For an explicitly declared public hostname, a public Route 53 alias to an owned
listener also uses the configured public transport for known VPC clients. The
alias target and canonical zone must validate before its emulator listener
address is translated. This preserves the caller's NAT and security-group
checks. Undeclared aliases, literal address records, private-zone answers and
failed alias lookups retain their existing behavior. This configured front-door
handoff does not implement general native Application Load Balancer frontends.

Outside a VPC, the same validated public alias uses the configured upstream DNS
resolver to reach its declared front door. Alias ownership and canonical-zone
validation still run first. A refused, missing, ambiguous or unsupported alias
does not fall through to upstream DNS. The internal routing hint survives public
zone validation without changing DNS record contents. Literal records pointing
at the emulator retain their addresses. TLS remains verified against the original
hostname and the front door's certificate.

Qualification must prove a real peer response over verified TLS and a signed
AWS request through the selected front door, then repeat route withdrawal and
security-group denial with successful recovery. Source compilation or DNS
answers alone cannot qualify this handoff.

Private workloads reach that address only through an active native NAT route and
the NAT subnet's attached internet-gateway route. Workload egress rules still
apply. The bridge admits ports 80 and 443 from the active NAT EIPs and installs
explicit return routes to those NATs. Its only new outbound connection is to the
selected upstream listener. It neither routes general internet traffic nor adds a
workload control-network exemption.

An EC2 instance with a native public IPv4 address can instead use its subnet's
attached internet-gateway route. Its protected namespace joins the owned public
transport bridge for managed public EC2 peers. Public API traffic uses the
native address's original Docker network when that network is not internal.
Keeping that public source on its owning bridge prevents Docker's host
masquerading rule from replacing it with a transport gateway address. Internal
control networks retain the managed public transport for the routed API address.
Both paths preserve the native public source and remain subject to the instance's
internet route and security-group egress. A private neighbor cannot borrow them. Removing
an Elastic IP or stopping the instance withdraws its admission; reassociation or
start restores it when the declared public address and internet route exist.
Removing the last NAT and public caller retires the bridge.
The TCP bridge opens a new upstream connection after accepting a caller socket,
so this path does not qualify IAM `aws:SourceIp` behavior.

A private workload can reach an EC2 peer's automatically assigned public IPv4
address through its NAT gateway. The caller's NAT route, the NAT subnet's
internet-gateway route and the server's internet-gateway return route must all
exist. The server observes the allocated NAT Elastic IP; allowing only the
caller's private address does not admit the request. Direct private traffic
between these VPCs remains blocked. Egress CIDRs apply to the published public
destination only after both peers enforce their security-group policies.
Publishing a public address creates no security-group grant, and withdrawing
it restores the private transport restriction. `Ec2PublicPeerPacketIntegrationTest` covers
the observed source, route withdrawal and security-group denial and recovery.

`VpcPublicAwsApiPacketIntegrationTest` covers normal AWS DNS, signed EC2 requests
over HTTP and verified HTTPS, observed NAT peers, workload egress denial, private
and NAT-subnet route withdrawal, recovery, NAT replacement and retirement. It
also covers direct public EC2 access, private-neighbor isolation, Elastic IP
changes and stop/start, and repeats the private frontend and public NAT cases with the feature
enabled. The bounded test client waits on dropped packets; no server delay is injected.

Floci installs routing and translation rules inside owned container namespaces.
During route replacement, the temporary default-deny guard preserves UDP and TCP
port 53 only to the endpoint's declared resolvers, with connection-tracked replies.
The guard and its resolver permits are installed in one nftables transaction.
Peer traffic remains withdrawn until the new routes and policy are installed;
initial admission and failure quarantine still deny all traffic.
Docker manages its host bridge configuration. Removing a NAT
gateway retires forwarding before releasing its addresses. A namespace still
used by a container retains its reservation and makes deletion retryable.
Stopped workers can be retired without executing commands in their namespaces.
The normal container teardown hook also retires NAT workers before the Docker
client closes. Shutdown and reset pause reconciliation and new runtime creation
so another service's cleanup callback cannot recreate a retired NAT worker.
Reset resumes routing afterwards, including after a failed storage wipe, and
refuses to discard records for endpoints whose owners have not retired them.
Routing failures reach the AWS API caller after quarantining
managed forwarding, so a deployment cannot mistake an unapplied route for success.

`ElbClassicPublicNatPacketIntegrationTest` uses AWS SDK management calls,
independently observed client addresses, real connection failures, ingress
recovery, route removal and replacement, and deletion. A frontend allowlist
containing only the caller's private address fails. Adding the allocated NAT EIP
to a backend group also fails. Granting it on the frontend succeeds without an
application delay. The test also checks namespace consumers and stopped-worker
retirement. Focused route, address, interface and teardown regressions verify
failure reporting and ownership.

`ElbClassicLambdaNatPacketIntegrationTest` repeats the EC2 cases and runs a
pinned stock Node.js 20 Lambda runtime. It verifies the function's private ENI
address, observed NAT EIP, blocked frontend ingress, recovery and route removal
while the Runtime API remains reachable. Function deletion releases its ENI.
The fixture's two-second HTTP client deadline bounds connection failures; the
backend does not inject delays. This case passes a resolved frontend address to
the function, so it does not qualify application DNS resolution.

`FLOCI_SERVICES_EC2_VPC_NETWORKS_ISOLATE_ADDRESS_SPACES=true` preserves overlapping
VPC CIDRs on independent account-scoped bridges. Docker allocates a unique IPv4
transport pool for each bridge, which installs its ordinary forwarding rules
even when the daemon's forwarding policy is DROP. The native VPC CIDR is never
registered in Docker IPAM. Before workload admission, an owned helper replaces
its temporary transport address with its declared subnet address. The control
network and its default gateway remain separate.

The mode requires protected namespaces, faithful private addresses and
`vpc-networks.internal=false`. Namespace filters own isolation: Docker internal
bridges reject addresses outside their transport pool. It refuses overlap with
the selected Docker transport, foreign helpers and incompatible retained
bridges. Docker's configured default address pools must have capacity for the
selected VPCs; the emulator does not modify daemon address pools or host
firewall rules. IPv6 is disabled and no temporary transport address remains on
the native interface before workload admission.

`ElbOverlappingVpcPacketIntegrationTest` repeats the protected frontend and NAT
cases with this mode enabled. Its Lambda caller and backend belong to different
accounts and both use `10.89.1.10`; the backend must still observe the caller's
NAT EIP. It also verifies independent private DNS zones over UDP and TCP and
instance metadata for namespaces with identical private addresses.

This milestone does not implement arbitrary internet forwarding, VPC endpoints,
peering, transit gateways, IPv6/NAT64, regional or multi-address NAT gateways,
network ACLs, arbitrary public EC2 address allocation, multiple
frontend subnets, or Application Load Balancer frontends.
Platform DNS and service-owned Runtime API connections retain their existing
narrow exemptions. Direct public-instance API routes, VPC endpoints, IAM source-IP
conditions and a complete application workflow need separate qualification before
this is a general VPC runtime. Persisted restart recovery and Docker Desktop also need
separate qualification.

References:

- [NAT gateway address translation](https://docs.aws.amazon.com/vpc/latest/userguide/vpc-nat-gateway.html)
- [NAT prerequisites and troubleshooting](https://docs.aws.amazon.com/vpc/latest/userguide/nat-gateway-troubleshooting.html)
- [Route priority](https://docs.aws.amazon.com/vpc/latest/userguide/route-tables-priority.html)
- [Classic load balancer security groups](https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/elb-vpc-security-groups.html)
