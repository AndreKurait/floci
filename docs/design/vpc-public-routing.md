# VPC public routing milestone

Status: AWS SDK lifecycle and protected EC2 packet regressions qualified on
Linux amd64. Full native application qualification remains incomplete.

The first public routing slice connects protected IPv4 workloads to managed
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

Floci installs routing and translation rules inside owned container namespaces.
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

This milestone does not implement arbitrary internet forwarding, VPC endpoints,
peering, transit gateways, IPv6/NAT64, regional or multi-address NAT gateways,
network ACLs, public EC2 addresses, or Application Load Balancer frontends.
Platform DNS and service-owned Runtime API connections retain their existing
narrow exemptions. Protected Lambda callers, public AWS API routing and a
complete application workflow need separate qualification before this is a
general VPC runtime. Persisted restart recovery and Docker Desktop also need
separate qualification.

References:

- [NAT gateway address translation](https://docs.aws.amazon.com/vpc/latest/userguide/vpc-nat-gateway.html)
- [NAT prerequisites and troubleshooting](https://docs.aws.amazon.com/vpc/latest/userguide/nat-gateway-troubleshooting.html)
- [Route priority](https://docs.aws.amazon.com/vpc/latest/userguide/route-tables-priority.html)
- [Classic load balancer security groups](https://docs.aws.amazon.com/elasticloadbalancing/latest/classic/elb-vpc-security-groups.html)
