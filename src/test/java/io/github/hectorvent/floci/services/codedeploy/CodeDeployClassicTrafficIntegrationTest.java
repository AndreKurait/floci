package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.DeploymentInfo;
import software.amazon.awssdk.services.codedeploy.model.LifecycleEvent;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.iam.IamClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public SDK traffic and IAM decisions with simulated EC2 instances and explicit mailbox replies.
 * The separate Docker test runs the stock agent and actual load-balancer connections.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CodeDeployClassicTrafficIntegrationTest {
    private static final String ACCOUNT = "333344445555";
    private static final String REGION = "eu-west-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Deque<Executable> cleanup = new ArrayDeque<>();

    @Inject CodeDeployAgentCommands commands;
    @TestHTTPResource URI endpoint;

    @AfterEach
    void deleteOwnedResources() {
        assertAll("CodeDeploy traffic resources", cleanup);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workersKeepTheSelectedOwnerAndRecheckTagsBeforeReregistration(boolean revokeTag) throws Exception {
        String name = "deploy-" + UUID.randomUUID().toString().substring(0, 8);
        Fixture selected = fixture(ACCOUNT, REGION, name);
        Fixture otherAccount = fixture("666677778888", REGION, name);
        Fixture otherRegion = fixture(ACCOUNT, "us-west-2", name);
        String deployment = deploy(selected, "*");

        complete(selected, "BeforeBlockTraffic");
        await().atMost(Duration.ofSeconds(10)).until(() -> members(selected).isEmpty());
        for (String phase : List.of("AfterBlockTraffic", "ApplicationStop", "DownloadBundle", "BeforeInstall",
                "Install", "AfterInstall", "ApplicationStart", "ValidateService")) {
            assertEquals(List.of(), members(selected));
            assertEquals(List.of(otherAccount.instance()), members(otherAccount));
            assertEquals(List.of(otherRegion.instance()), members(otherRegion));
            complete(selected, phase);
        }
        if (revokeTag) {
            selected.elb().addTags(request -> request.loadBalancerNames(name)
                    .tags(tag -> tag.key("Stage").value("unapproved")));
        }
        complete(selected, "BeforeAllowTraffic");
        if (!revokeTag) {
            complete(selected, "AfterAllowTraffic");
        }
        DeploymentInfo result = terminal(selected, deployment);
        assertEquals(revokeTag ? "Failed" : "Succeeded", result.statusAsString(), result.toString());
        if (revokeTag) {
            assertEquals("IAM_ROLE_PERMISSIONS", result.errorInformation().codeAsString());
            assertTrue(result.errorInformation().message().contains("RegisterInstancesWithLoadBalancer"));
        }
        assertEquals(revokeTag ? List.of() : List.of(selected.instance()), members(selected));
        assertEquals(List.of(otherAccount.instance()), members(otherAccount));
        assertEquals(List.of(otherRegion.instance()), members(otherRegion));
    }

    @Test
    void healthReadNeedsAnUnscopedGrantEvenWhenTheWriteHasTheExactLoadBalancerArn() throws Exception {
        Fixture selected = fixture(ACCOUNT, REGION, "deploy-" + UUID.randomUUID().toString().substring(0, 8));
        String deployment = deploy(selected, loadBalancerArn(selected));
        complete(selected, "BeforeBlockTraffic");
        DeploymentInfo result = terminal(selected, deployment);
        assertEquals("Failed", result.statusAsString());
        assertEquals("IAM_ROLE_PERMISSIONS", result.errorInformation().codeAsString());
        assertTrue(result.errorInformation().message().contains("DescribeInstanceHealth"));
        assertEquals(List.of(), members(selected));
        List<LifecycleEvent> events = selected.deployments().getDeploymentTarget(request ->
                request.deploymentId(deployment).targetId(selected.instance()))
                .deploymentTarget().instanceTarget().lifecycleEvents();
        assertTrue(events.stream().anyMatch(event ->
                "BlockTraffic".equals(event.lifecycleEventName()) && "Failed".equals(event.statusAsString())));
        assertTrue(events.stream().filter(event -> "AfterBlockTraffic".equals(event.lifecycleEventName()))
                .allMatch(event -> "Skipped".equals(event.statusAsString())));
    }

    private Fixture fixture(String account, String region, String name) {
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"));
        Region selectedRegion = Region.of(region);
        Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(selectedRegion)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        ElasticLoadBalancingClient elb = ElasticLoadBalancingClient.builder()
                .endpointOverride(endpoint).region(selectedRegion)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(selectedRegion)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        CodeDeployClient deployments = CodeDeployClient.builder().endpointOverride(endpoint).region(selectedRegion)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        cleanup.addFirst(ec2::close);
        cleanup.addFirst(elb::close);
        cleanup.addFirst(iam::close);
        cleanup.addFirst(deployments::close);
        String vpc = ec2.createVpc(request -> request.cidrBlock("10.96.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.96.1.0/24"))
                .subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(request -> request.subnetId(subnet)));
        String instance = ec2.runInstances(request -> request.imageId("ami-traffic-test").instanceType("t3.micro")
                .subnetId(subnet).minCount(1).maxCount(1).tagSpecifications(tags -> tags.resourceType("instance")
                        .tags(tag -> tag.key("Deployment").value(name)))).instances().getFirst().instanceId();
        cleanup.addFirst(() -> ec2.terminateInstances(request -> request.instanceIds(instance)));
        elb.createLoadBalancer(request -> request.loadBalancerName(name).subnets(subnet)
                .listeners(listener -> listener.protocol("HTTP").loadBalancerPort(80).instancePort(8080))
                .tags(tag -> tag.key("Stage").value("approved")));
        cleanup.addFirst(() -> elb.deleteLoadBalancer(request -> request.loadBalancerName(name)));
        elb.registerInstancesWithLoadBalancer(request -> request.loadBalancerName(name)
                .instances(target -> target.instanceId(instance)));
        return new Fixture(account, region, name, instance, elb, iam, deployments);
    }

    private String deploy(Fixture selected, String healthResource) throws Exception {
        String name = selected.name();
        String roleName = name + "-" + selected.region();
        String role = selected.iam().createRole(request -> request.roleName(roleName).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"Service":"codedeploy.amazonaws.com"}}]}""")).role().arn();
        cleanup.addFirst(() -> selected.iam().deleteRole(request -> request.roleName(roleName)));
        String policy = MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(
                Map.of("Effect", "Allow", "Action", List.of("elasticloadbalancing:RegisterInstancesWithLoadBalancer",
                                "elasticloadbalancing:DeregisterInstancesFromLoadBalancer"),
                        "Resource", loadBalancerArn(selected),
                        "Condition", Map.of("StringEquals", Map.of("aws:ResourceTag/Stage", "approved"))),
                Map.of("Effect", "Allow", "Action", "elasticloadbalancing:DescribeInstanceHealth",
                        "Resource", healthResource))));
        selected.iam().putRolePolicy(request -> request.roleName(roleName).policyName("traffic").policyDocument(policy));
        cleanup.addFirst(() -> selected.iam().deleteRolePolicy(request ->
                request.roleName(roleName).policyName("traffic")));
        selected.deployments().createApplication(request -> request.applicationName(name).computePlatform("Server"));
        cleanup.addFirst(() -> selected.deployments().deleteApplication(request -> request.applicationName(name)));
        selected.deployments().createDeploymentGroup(request -> request.applicationName(name).deploymentGroupName(name)
                .serviceRoleArn(role).deploymentConfigName("CodeDeployDefault.OneAtATime")
                .ec2TagFilters(tag -> tag.key("Deployment").value(name).type("KEY_AND_VALUE"))
                .deploymentStyle(style -> style.deploymentType("IN_PLACE").deploymentOption("WITH_TRAFFIC_CONTROL"))
                .loadBalancerInfo(info -> info.elbInfoList(lb -> lb.name(name))));
        cleanup.addFirst(() -> selected.deployments().deleteDeploymentGroup(request ->
                request.applicationName(name).deploymentGroupName(name)));
        String id = selected.deployments().createDeployment(request -> request.applicationName(name)
                .deploymentGroupName(name).revision(revision -> revision.revisionType("S3")
                        .s3Location(s3 -> s3.bucket(name).key("fixture.zip").bundleType("zip")))).deploymentId();
        cleanup.addFirst(() -> {
            String status = selected.deployments().getDeployment(request -> request.deploymentId(id))
                    .deploymentInfo().statusAsString();
            if (!List.of("Succeeded", "Failed", "Stopped").contains(status)) {
                selected.deployments().stopDeployment(request -> request.deploymentId(id));
                terminal(selected, id);
            }
        });
        return id;
    }

    private void complete(Fixture selected, String phase) {
        String host = AwsArnUtils.Arn.of("ec2", selected.region(), selected.account(),
                "instance/" + selected.instance()).toString();
        Map<String, Object> response = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> commands.poll(selected.account(), selected.region(), host));
        Object command = response.get("HostCommand");
        assertNotNull(command, phase);
        Map<?, ?> details = (Map<?, ?>) command;
        assertEquals(phase, details.get("CommandName"));
        String id = (String) details.get("HostCommandIdentifier");
        commands.acknowledge(selected.account(), selected.region(), id);
        commands.complete(selected.account(), selected.region(), id, "Succeeded", "{\"error_code\":0}");
    }

    private static DeploymentInfo terminal(Fixture selected, String id) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> selected.deployments()
                        .getDeployment(request -> request.deploymentId(id)).deploymentInfo(),
                info -> List.of("Succeeded", "Failed", "Stopped").contains(info.statusAsString()));
    }

    private static List<String> members(Fixture selected) {
        return selected.elb().describeLoadBalancers(request -> request.loadBalancerNames(selected.name()))
                .loadBalancerDescriptions().getFirst().instances().stream().map(instance -> instance.instanceId()).toList();
    }

    private static String loadBalancerArn(Fixture selected) {
        return AwsArnUtils.Arn.of("elasticloadbalancing", selected.region(), selected.account(),
                "loadbalancer/" + selected.name()).toString();
    }

    private record Fixture(String account, String region, String name, String instance,
                           ElasticLoadBalancingClient elb, IamClient iam, CodeDeployClient deployments) {}
}
