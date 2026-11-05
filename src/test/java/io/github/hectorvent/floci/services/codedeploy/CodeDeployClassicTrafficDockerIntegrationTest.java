package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.elb.ElbClassicDnsRecordSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codedeploy.model.DeploymentStatus;
import software.amazon.awssdk.services.codedeploy.model.InstanceTarget;
import software.amazon.awssdk.services.codedeploy.model.LifecycleEvent;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unmodified AWS agent hooks, real Classic frontend connections, drain and HTTP health checks.
 * The fixture AMI and application are intentionally small; native service rollouts are qualified separately.
 */
@QuarkusTest
@TestProfile(CodeDeployClassicTrafficDockerIntegrationTest.TrafficProfile.class)
class CodeDeployClassicTrafficDockerIntegrationTest extends CodeDeployAgentDockerFixture {
    public static class TrafficProfile extends AgentProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.putAll(Map.of("floci.services.elb.mock", "false",
                    "floci.services.elb.vpc-frontends", "true",
                    "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.services.ec2.aws-faithful-private-ip", "true",
                    "floci.network.security-group-enforcement.enabled", "true"));
            return overrides;
        }
    }

    @Inject ElbClassicDnsRecordSource dns;

    @Test
    void stockAgentWaitsForDrainingAndHealthAndDoesNotContinueAfterStop() throws Exception {
        Fleet fleet = fleet();
        String first = fleet.targets().getFirst();
        String second = fleet.targets().getLast();
        Traffic frontend = frontend(fleet);
        register(frontend, first);
        register(frontend, second);
        String baseline = deploy(fleet.deployments(), fleet.name(),
                upload(fleet.s3(), fleet.name(), "baseline.zip", trafficBundle("baseline", true)));
        assertDeployment(fleet.deployments(), baseline, DeploymentStatus.SUCCEEDED);
        for (String target : fleet.targets()) {
            assertEquals("InService", health(frontend, target));
            assertNoHook(target, baseline, "BeforeBlockTraffic");
            assertNoHook(target, baseline, "ApplicationStop");
        }
        frontend.elb().deregisterInstancesFromLoadBalancer(request -> request.loadBalancerName(fleet.name())
                .instances(instance -> instance.instanceId(second)));
        try (HeldRequest held = new HeldRequest(frontend)) {
            register(frontend, second);
            awaitHealthy(frontend, second);
            String update = deploy(fleet.deployments(), fleet.name(),
                    upload(fleet.s3(), fleet.name(), "updated.zip", trafficBundle("updated", false)));
            stage(fleet, update, first, "BlockTraffic");
            assertFalse(held.response.isDone());
            assertNoHook(first, update, "ApplicationStop");
            assertPacket(frontend, "baseline");
            held.release();
            assertEquals("held", held.result().stdout().trim());

            stage(fleet, update, first, "AllowTraffic");
            assertEquals("OutOfService", health(frontend, first));
            assertNoHook(first, update, "AfterAllowTraffic");
            assertNoHook(second, update, "BeforeBlockTraffic");
            assertPacket(frontend, "baseline");
            output(first, "touch", "/opt/floci-traffic-proof/health");
            stage(fleet, update, second, "AllowTraffic");
            assertEquals("InService", health(frontend, first));
            assertEquals("OutOfService", health(frontend, second));
            assertPacket(frontend, "updated");
            output(second, "touch", "/opt/floci-traffic-proof/health");
            assertDeployment(fleet.deployments(), update, DeploymentStatus.SUCCEEDED);
            for (String target : fleet.targets()) {
                assertTrafficHooks(target, update, "baseline", "updated");
                assertEquals("InService", health(frontend, target));
            }
        }

        // Select the first backend for a held request before returning the healthy peer to service.
        frontend.elb().deregisterInstancesFromLoadBalancer(request -> request.loadBalancerName(fleet.name())
                .instances(instance -> instance.instanceId(second)));
        try (HeldRequest held = new HeldRequest(frontend)) {
            register(frontend, second);
            awaitHealthy(frontend, second);
            String stopped = deploy(fleet.deployments(), fleet.name(),
                    upload(fleet.s3(), fleet.name(), "stopped.zip", trafficBundle("stopped", true)));
            stage(fleet, stopped, first, "BlockTraffic");
            fleet.deployments().stopDeployment(request -> request.deploymentId(stopped));
            assertDeployment(fleet.deployments(), stopped, DeploymentStatus.STOPPED);
            assertNoHook(first, stopped, "ApplicationStop");
            assertNoHook(second, stopped, "BeforeBlockTraffic");
            held.release();
            assertEquals("held", held.result().stdout().trim());
            await().atMost(Duration.ofSeconds(10)).until(() -> "OutOfService".equals(health(frontend, first)));
            assertFalse(frontend.elb().describeLoadBalancers(request -> request.loadBalancerNames(fleet.name()))
                    .loadBalancerDescriptions().getFirst().instances().stream()
                    .anyMatch(instance -> first.equals(instance.instanceId())));
            assertPacket(frontend, "updated");
        }
        String recovered = deploy(fleet.deployments(), fleet.name(),
                upload(fleet.s3(), fleet.name(), "recovered.zip", trafficBundle("recovered", true)));
        assertDeployment(fleet.deployments(), recovered, DeploymentStatus.SUCCEEDED);
        for (String target : fleet.targets()) {
            assertTrafficHooks(target, recovered, "updated", "recovered");
            assertEquals("InService", health(frontend, target));
        }
        assertPacket(frontend, "recovered");
    }

    private Traffic frontend(Fleet fleet) {
        ElasticLoadBalancingClient elb = ElasticLoadBalancingClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(ADMIN)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.addFirst(elb::close);
        Instance instance = fleet.ec2().describeInstances(request -> request.instanceIds(fleet.targets().getFirst()))
                .reservations().getFirst().instances().getFirst();
        String group = fleet.ec2().createSecurityGroup(request -> request.vpcId(instance.vpcId())
                .groupName(fleet.name() + "-frontend").description("Classic traffic fixture")).groupId();
        cleanup.addFirst(() -> fleet.ec2().deleteSecurityGroup(request -> request.groupId(group)));
        fleet.ec2().authorizeSecurityGroupIngress(request -> request.groupId(group)
                .ipPermissions(permission -> permission.ipProtocol("tcp").fromPort(80).toPort(80)
                        .ipRanges(range -> range.cidrIp("10.94.0.0/16"))));
        String backendGroup = instance.securityGroups().getFirst().groupId();
        IpPermission backendIngress = IpPermission.builder().ipProtocol("tcp").fromPort(8080).toPort(8080)
                .userIdGroupPairs(peer -> peer.groupId(group)).build();
        fleet.ec2().authorizeSecurityGroupIngress(request ->
                request.groupId(backendGroup).ipPermissions(backendIngress));
        cleanup.addFirst(() -> fleet.ec2().revokeSecurityGroupIngress(request ->
                request.groupId(backendGroup).ipPermissions(backendIngress)));
        String hostname = elb.createLoadBalancer(request -> request.loadBalancerName(fleet.name())
                .scheme("internal").subnets(fleet.subnet()).securityGroups(group)
                .listeners(listener -> listener.protocol("HTTP").loadBalancerPort(80).instancePort(8080))).dnsName();
        cleanup.addFirst(() -> elb.deleteLoadBalancer(request -> request.loadBalancerName(fleet.name())));
        elb.configureHealthCheck(request -> request.loadBalancerName(fleet.name())
                .healthCheck(check -> check.target("HTTP:8080/health")
                        .interval(5).timeout(2).healthyThreshold(2).unhealthyThreshold(2)));
        elb.modifyLoadBalancerAttributes(request -> request.loadBalancerName(fleet.name())
                .loadBalancerAttributes(attributes -> attributes.connectionDraining(drain -> drain.enabled(true)
                        .timeout(90))));
        String role = fleet.iam().getRole(request -> request.roleName(fleet.name() + "-deployment")).role().arn();
        String arn = AwsArnUtils.Arn.of("elasticloadbalancing", REGION,
                AwsArnUtils.parse(role).accountId(), "loadbalancer/" + fleet.name()).toString();
        fleet.iam().putRolePolicy(request -> request.roleName(fleet.name() + "-deployment").policyName("traffic")
                .policyDocument("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":["elasticloadbalancing:RegisterInstancesWithLoadBalancer",
                            "elasticloadbalancing:DeregisterInstancesFromLoadBalancer"],"Resource":"%s"},
                          {"Effect":"Allow","Action":"elasticloadbalancing:DescribeInstanceHealth","Resource":"*"}
                        ]}
                        """.formatted(arn)));
        cleanup.addFirst(() -> fleet.iam().deleteRolePolicy(request ->
                request.roleName(fleet.name() + "-deployment").policyName("traffic")));
        fleet.deployments().updateDeploymentGroup(request -> request.applicationName(fleet.name())
                .currentDeploymentGroupName(fleet.name())
                .deploymentStyle(style -> style.deploymentType("IN_PLACE").deploymentOption("WITH_TRAFFIC_CONTROL"))
                .loadBalancerInfo(info -> info.elbInfoList(lb -> lb.name(fleet.name()))));
        String address = dns.resolveIpv4(hostname).orElseThrow().addresses().getFirst();
        return new Traffic(fleet, elb, address);
    }

    private static void register(Traffic frontend, String instance) {
        frontend.elb().registerInstancesWithLoadBalancer(request -> request.loadBalancerName(frontend.fleet().name())
                .instances(target -> target.instanceId(instance)));
    }

    private static String health(Traffic frontend, String instance) {
        return frontend.elb().describeInstanceHealth(request -> request.loadBalancerName(frontend.fleet().name())
                .instances(target -> target.instanceId(instance))).instanceStates().getFirst().state();
    }

    private static void awaitHealthy(Traffic frontend, String instance) {
        await().atMost(Duration.ofSeconds(30)).until(() -> "InService".equals(health(frontend, instance)));
    }

    private static void stage(Fleet fleet, String deployment, String instance, String phase) {
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            InstanceTarget target = fleet.deployments().getDeploymentTarget(request ->
                    request.deploymentId(deployment).targetId(instance)).deploymentTarget().instanceTarget();
            LifecycleEvent event = target.lifecycleEvents().stream()
                    .filter(candidate -> phase.equals(candidate.lifecycleEventName())).findFirst().orElse(null);
            assertNotNull(event, "Awaiting " + phase + ": " + target);
            assertEquals("InProgress", event.statusAsString(), target.toString());
        });
    }

    private void assertPacket(Traffic frontend, String expected) {
        assertEquals(expected, output(frontend.fleet().targets().getLast(), "busybox", "wget", "-qO-", "-T", "5",
                "http://" + frontend.address() + "/payload.txt").trim());
    }

    private void assertNoHook(String instance, String deployment, String phase) {
        assertFalse(output(instance, "cat", "/var/lib/floci-traffic-events").contains(deployment + " " + phase + " "));
    }

    private void assertTrafficHooks(String instance, String deployment, String previous, String current) {
        List<String> events = output(instance, "cat", "/var/lib/floci-traffic-events").lines()
                .filter(line -> line.startsWith(deployment + " "))
                .map(line -> line.substring(deployment.length() + 1)).toList();
        assertEquals(List.of("BeforeBlockTraffic " + previous, "AfterBlockTraffic " + previous,
                "ApplicationStop " + previous, "BeforeInstall " + current, "AfterInstall " + current,
                "ApplicationStart " + current, "ValidateService " + current,
                "BeforeAllowTraffic " + current, "AfterAllowTraffic " + current), events);
    }

    private static byte[] trafficBundle(String marker, boolean healthy) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            write(zip, "www/payload.txt", marker + "\n");
            write(zip, "www/health", "healthy\n");
            write(zip, "www/cgi-bin/hold.sh", """
                    #!/bin/sh
                    set -eu
                    touch /var/lib/floci-traffic-request-started
                    cat /var/lib/floci-traffic-request-gate > /dev/null
                    printf 'Content-Type: text/plain\\r\\n\\r\\nheld\\n'
                    """);
            StringBuilder appspec = new StringBuilder("""
                    version: 0.0
                    os: linux
                    files:
                      - source: www
                        destination: /opt/floci-traffic-proof
                    hooks:
                    """);
            for (String phase : List.of("BeforeBlockTraffic", "AfterBlockTraffic", "ApplicationStop",
                    "BeforeInstall", "AfterInstall", "ApplicationStart", "ValidateService",
                    "BeforeAllowTraffic", "AfterAllowTraffic")) {
                appspec.append("  ").append(phase).append(":\n    - location: scripts/").append(phase)
                        .append(".sh\n      timeout: 10\n      runas: root\n");
                String hook = "#!/bin/sh\nset -eu\nprintf '%s %s %s\\n' \"$DEPLOYMENT_ID\" \"$LIFECYCLE_EVENT\" '"
                        + marker + "' >> /var/lib/floci-traffic-events\n";
                if ("ApplicationStop".equals(phase)) {
                    hook += "kill \"$(cat /var/lib/floci-traffic-server.pid)\"\n";
                } else if ("AfterInstall".equals(phase) && !healthy) {
                    hook += "rm /opt/floci-traffic-proof/health\n";
                } else if ("ApplicationStart".equals(phase)) {
                    hook += "nohup busybox httpd -f -p 8080 -h /opt/floci-traffic-proof"
                            + " > /var/log/floci-traffic-server.log 2>&1 < /dev/null &\n"
                            + "echo $! > /var/lib/floci-traffic-server.pid\n";
                } else if ("ValidateService".equals(phase)) {
                    hook += "test \"$(cat /opt/floci-traffic-proof/payload.txt)\" = '" + marker + "'\n";
                }
                write(zip, "scripts/" + phase + ".sh", hook);
            }
            write(zip, "appspec.yml", appspec.toString());
        }
        return bytes.toByteArray();
    }

    private record Traffic(Fleet fleet, ElasticLoadBalancingClient elb, String address) {}

    private final class HeldRequest implements AutoCloseable {
        private final String instance;
        private final CompletableFuture<ContainerExec.Result> response;
        private boolean released;

        private HeldRequest(Traffic frontend) {
            instance = frontend.fleet().targets().getFirst();
            output(instance, "sh", "-c", "rm -f /var/lib/floci-traffic-request-started"
                    + " /var/lib/floci-traffic-request-gate && mkfifo /var/lib/floci-traffic-request-gate");
            String caller = localInstance(frontend.fleet().targets().getLast()).getDockerContainerId();
            response = CompletableFuture.supplyAsync(() -> ContainerExec.run(docker, caller,
                    new String[]{"busybox", "wget", "-qO-", "-T", "90",
                            "http://" + frontend.address() + "/cgi-bin/hold.sh"}, 100));
            await().atMost(Duration.ofSeconds(10)).until(() -> guest(instance, "test", "-f",
                    "/var/lib/floci-traffic-request-started").exitCode() == 0);
        }

        private void release() {
            if (!released) {
                output(instance, "busybox", "timeout", "5", "sh", "-c",
                        "printf resume > /var/lib/floci-traffic-request-gate");
                released = true;
            }
        }

        private ContainerExec.Result result() throws Exception {
            ContainerExec.Result result = response.get(10, TimeUnit.SECONDS);
            assertEquals(0, result.exitCode(), result.summary());
            return result;
        }

        @Override
        public void close() throws Exception {
            if (!released && !response.isDone()) {
                release();
            }
            result();
        }
    }
}
