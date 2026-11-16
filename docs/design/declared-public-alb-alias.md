# Declared public ALB aliases

An exact configured public peer hostname keeps its routed API transport after Route 53 validates one existing application load balancer, its canonical hosted zone, and a ready frontend owned by that load balancer. Ordinary aliases retain their caller VPC scope. An unavailable owned frontend or shared listener cannot create a declared route.

Only an IPv4 query for an exact configured peer with a usable caller identity selects this path. Subdomains, undeclared names, invalid origins, unsupported record types, duplicate owners and mismatched hosted zones keep their ordinary refusal or no-data behavior. Queries do not create frontend containers or listeners.

This ports the change merged into `dev` by PR #1067, with composed DNS regressions from PR #1068. It is stacked on the TLS ownership port in PR #1062. Main still requires the native routing and resource alias foundations tracked by PRs #724, #810, #811, #1026 and #1038; this dependent port must remain a draft until those foundations build and its tests run on main.
