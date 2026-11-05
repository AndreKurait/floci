# CodeDeploy Server deployment through the AWS agent

Server revisions need to be installed inside their selected instances. Reporting
`DownloadBundle` or `Install` success without downloading the revision or copying
its files does not establish a deployment.

The runtime uses the ordinary CodeDeploy agent command protocol. Floci owns
deployment scheduling, target selection and command state. The AWS agent owns
archive extraction, AppSpec validation, file installation, permissions and guest
lifecycle scripts. S3 serves the actual revision selected by `CreateDeployment`.
The guest uses its own credentials and the configured emulator endpoints.
The requested `DISALLOW`, `OVERWRITE` or `RETAIN` file behavior is included in
the signed specification and applied by the agent.

The runtime targets S3 zip, tar and tgz revisions and in-place deployments,
with optional Classic load-balancer traffic control. It selects existing
`InService` Auto Scaling members and EC2 instances matched by the group's tags,
deduplicating instances selected both ways. Stopped EC2 instances remain targets;
they must receive the command or encounter the normal agent-start deadline.

`OneAtATime` sends work to one instance at a time, stops after an early failure,
and permits the documented last-instance failure when preceding instances
succeeded. `AllAtOnce` starts all selected hosts and requires at least one
successful deployment. Half-at-a-time and custom health policies, target-group
traffic control, blue/green Server deployments, and automatic rollback are
rejected before queuing agent commands.
Prior revision-health scheduling and Auto Scaling launch hooks are not
implemented. Group membership cannot establish that newly launched instances
received the last successful revision.
Enabled deployment alarms, restart mode, outdated-instance filtering, ignored
stop failures, and per-request rollback, alarm, target or EC2-process overrides
are rejected rather than silently discarded.

## Classic load-balancer traffic

An `IN_PLACE` group with `WITH_TRAFFIC_CONTROL` names its Classic load balancers
in `loadBalancerInfo.elbInfoList`. Each instance runs `BeforeBlockTraffic`,
`BlockTraffic` and `AfterBlockTraffic` before the ordinary installation phases,
then `BeforeAllowTraffic`, `AllowTraffic` and `AfterAllowTraffic` after
`ValidateService`. The AWS agent executes the four surrounding hooks, including
its normal selection of the previous successful revision for the blocking
hooks. Floci performs the two reserved traffic operations.

`BlockTraffic` deregisters the instance from each selected balancer and waits
until its existing connections have drained. `AllowTraffic` registers it and
waits for `InService` health from every selected balancer. These checks read the
same membership, frontend connection and health state as the Classic APIs.
They do not substitute a timer for application health or connection completion.
The traffic deadline is one hour; a test may inject a shorter deadline.

The deployment captures the owning account, Region, service role and balancer
names before starting workers. Each operation uses that account's request
scope and evaluates the configured CodeDeploy service role's trust, policies,
permissions boundary and SCPs. Register/deregister permissions use the
balancer ARN and current resource tags. `DescribeInstanceHealth` uses `*`,
as required by its AWS authorization model. A missing permission fails the
traffic phase and the deployment response names the denied action. A concurrent
deployment failure or explicit stop releases other workers waiting for traffic
state. Cancellation does not re-register an instance or report a stopped
application healthy.

## Supported actions

The reference client is the published AWS CodeDeploy agent `v2.1.2`, source commit
`f2acaacb9f223feee055b5679384bbceae4154f1`. Its command service uses JSON 1.1 and
PascalCase fields, with these operations:

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `PollHostCommand` | Wait for an outstanding command for the polling host |
| `PutHostCommandAcknowledgement` | Move that command into execution |
| `GetDeploymentSpecification` | Return the signed revision and deployment metadata |
| `PostHostCommandUpdate` | Return command state without completing it |
| `PutHostCommandComplete` | Record the agent's terminal result and diagnostics |
<!-- floci:actions:end -->

Both `CodeDeployCommandService` and the legacy
`CodeDeployCommandService_v20141006` target prefixes identify the command
protocol. They must remain distinct from the public CodeDeploy control API.

Deployment specifications use attached `PKCS7/JSON` signatures from the
emulator's certificate authority. Agent developer mode and unsigned
specifications are unnecessary. This is a local signer, not an AWS production
identity.

Command state is bound to the deployment's account, Region and selected host.
Retries must not run a completed command again, acknowledge another tenant's
command, or change a terminal result. A deployment group has one active
deployment; independent groups may deploy to the same host. Cancellation
releases that group's reservation and invalidates its unfinished commands.
It stops issuing phases; it does not claim to kill a script already executing
inside the agent.

Polling waits up to 60 seconds for eligible work and wakes when a command is
queued or its state changes. A delivered command stays unavailable for five
seconds while its acknowledgement is in flight; if the response was lost,
the pending command becomes deliverable again. These are bounded Floci
delivery intervals, not a claim about AWS's internal timing. They prevent rapid
duplicate or empty responses from making the stock agent sleep between every
lifecycle event. Shutdown and interrupted requests release waiting polls.

The start deadline is five minutes per lifecycle event; the completion deadline
is one hour after acknowledgement. Tests may inject shorter coordinator
durations. Runtime requests use the normal deadlines, and agent completion
retains the script name, error, message and log. Download and installation have
the same acknowledgement/result requirements as script hooks.

The agent endpoints have independent IAM namespaces:
`codedeploy-commands-secure` and `codedeploy-commands`. A public
`codedeploy:*` policy does not grant agent commands. These are account-scoped
authorization and selected-host checks, not an assertion that an IAM principal
has been bound to a particular EC2 instance's attested identity. That binding
requires separate qualification.

## Qualification

The runtime is not qualified by this design document. Before publication it
needs an actual AWS agent to download an S3 bundle, install its files and run
AppSpec scripts in an owned guest. Evidence must include installed file hashes,
script output, first-install behavior, a failed script, stopped deployment,
command retries, account/Region isolation and complete cleanup.

`CodeDeployAgentDockerIntegrationTest` exercises the stock agent in two Auto
Scaling guests with IMDS role credentials. It verifies versioned S3 content
hashes, file-conflict policies, hook diagnostics, cancellation during a hook
blocked on a pipe, and subsequent recovery. The fixture launches the agent's
foreground master in the background so user data can finish; it checks that
bootstrap continued before deploying.

`CodeDeployClassicTrafficIntegrationTest` checks the public SDK path in
nondefault accounts and Regions, including current tag conditions and the
unscoped health-read permission. Its mailbox replies simulate an agent.
`CodeDeployClassicTrafficDockerIntegrationTest` instead uses the stock agent
and a Classic frontend with actual connections: a held request blocks draining,
missing HTTP health blocks registration, and an explicit stop prevents later
hooks. Traffic control also applies to the first deployment, when no previous
revision exists. A later deployment recovers both guests. Its private HTTP fixture
does not establish production TLS, caller NAT routing or a native service rollout.
The two Docker tests share the same agent image and ASG/S3 setup.

The SDK compatibility suite's `CodeDeployAgentTest` verifies signed
specifications and command completion against the standalone server, including
native-image CI. It simulates the agent protocol and verifies the CMS signature;
the Docker fixture supplies the separate guest-installation evidence.

Configuration-only API tests, a command-protocol simulator and a
`CreateDeployment` success response are insufficient. Native AMI initialization,
complete service rollouts and Auto Scaling launch hooks remain separate
qualifications until their real paths execute.
