# Explicit DNS listeners on a host

Private hosted zones and service DNS must use the same resolver when Floci runs
as a host process or in a container. Automatic DNS startup remains scoped to a
container's own address. A host process opts in with `FLOCI_DNS_LISTEN_ADDRESS`,
set to a local IPv4 address that its workload containers can reach.

The configured address is both the bind address and the advertised resolver.
UDP and TCP use port 53. Wildcard, multicast, hostname and IPv6 values are
rejected because the current resolver advertises a concrete IPv4 address.
Startup waits for both listeners; an explicit listener that cannot bind fails
startup. Shutdown attempts both closes and waits up to ten seconds. A close
failure retains the listener handles for retry and reports its cause.

Qualification must exercise host selection, real UDP and TCP queries, failure
when either socket is occupied, and release of a partially started listener.
The isolated listener tests use an ephemeral port so they do not require
privileged host ports or interfere with the machine's DNS service. Workload DNS
still uses port 53.
