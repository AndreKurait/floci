package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.LoadBalancerDescription;
import software.amazon.awssdk.services.elasticloadbalancing.model.LoadBalancerNotFoundException;
import software.amazon.awssdk.services.elasticloadbalancing.model.Tag;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationClassicLoadBalancerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String name = "cfn-classic-" + UUID.randomUUID().toString().substring(0, 8);
    private CloudFormationClient cfn;
    private ElasticLoadBalancingClient elb;
    private Ec2Client ec2;
    private String vpc;
    private String subnet;
    private String group;
    private String stackId;

    @BeforeEach
    void foundation() {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        elb = AwsTestClients.create(ElasticLoadBalancingClient.builder(), account, Region.US_EAST_1);
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
        vpc = ec2.createVpc(request -> request.cidrBlock("10.93.0.0/16")).vpc().vpcId();
        subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.93.1.0/24")
                .availabilityZone("us-east-1a")).subnet().subnetId();
        group = ec2.createSecurityGroup(request -> request.vpcId(vpc).groupName(name)
                .description("Classic frontend")).groupId();
    }

    @AfterEach
    void cleanup() {
        try {
            if (stackId != null) {
                cfn.deleteStack(request -> request.stackName(stackId));
                awaitStatus("DELETE_COMPLETE");
            }
        } finally {
            try {
                for (LoadBalancerDescription loadBalancer : elb.describeLoadBalancers().loadBalancerDescriptions()) {
                    elb.deleteLoadBalancer(request -> request.loadBalancerName(loadBalancer.loadBalancerName()));
                }
                ec2.deleteSecurityGroup(request -> request.groupId(group));
                ec2.deleteSubnet(request -> request.subnetId(subnet));
                ec2.deleteVpc(request -> request.vpcId(vpc));
            } finally {
                cfn.close();
                elb.close();
                ec2.close();
            }
        }
    }

    @Test
    void nativePropertiesAndAllGetAttValuesMatchTheClassicApi() {
        ObjectNode template = template(name);
        create(template);

        LoadBalancerDescription actual = describe(name);
        Map<String, String> outputs = outputs();
        assertEquals(name, outputs.get("Name"));
        assertEquals(actual.dnsName(), outputs.get("Dns"));
        assertEquals(actual.canonicalHostedZoneName(), outputs.get("ZoneName"));
        assertEquals(actual.canonicalHostedZoneNameID(), outputs.get("ZoneId"));
        assertEquals(actual.sourceSecurityGroup().groupName(), outputs.get("GroupName"));
        assertEquals(actual.sourceSecurityGroup().ownerAlias(), outputs.get("GroupOwner"));
        assertEquals(List.of(subnet), actual.subnets());
        assertEquals(List.of(group), actual.securityGroups());
        assertEquals("HTTP:8080/ping", actual.healthCheck().target());
        assertEquals(8080, actual.listenerDescriptions().getFirst().listener().instancePort());
        assertTrue(elb.describeLoadBalancerAttributes(request -> request.loadBalancerName(name))
                .loadBalancerAttributes().crossZoneLoadBalancing().enabled());
        assertEquals(Map.of("phase", "first"), tags(name));
    }

    @Test
    void unnamedUpdateKeepsIdentityAndAppliesMutableConfiguration() {
        ObjectNode template = template(null);
        create(template);
        String physical = outputs().get("Name");
        ObjectNode properties = properties(template);
        ((ObjectNode) properties.path("Listeners").get(0)).put("InstancePort", "8081");
        ((ObjectNode) properties.path("HealthCheck")).put("Target", "HTTP:8081/ready");
        properties.put("CrossZone", false);
        properties.putObject("ConnectionDrainingPolicy").put("Enabled", true).put("Timeout", 17);
        properties.putObject("ConnectionSettings").put("IdleTimeout", 41);
        properties.putArray("Tags").addObject().put("Key", "phase").put("Value", "second");

        update(template, "UPDATE_COMPLETE");

        assertEquals(physical, outputs().get("Name"));
        LoadBalancerDescription actual = describe(physical);
        assertEquals("HTTP:8081/ready", actual.healthCheck().target());
        assertEquals(8081, actual.listenerDescriptions().getFirst().listener().instancePort());
        assertEquals(1, elb.describeLoadBalancers().loadBalancerDescriptions().size());
        assertEquals(Map.of("phase", "second"), tags(physical));
        assertFalse(elb.describeLoadBalancerAttributes(request -> request.loadBalancerName(physical))
                .loadBalancerAttributes().crossZoneLoadBalancing().enabled());
        assertEquals(17, elb.describeLoadBalancerAttributes(request -> request.loadBalancerName(physical))
                .loadBalancerAttributes().connectionDraining().timeout());
        assertEquals(41, elb.describeLoadBalancerAttributes(request -> request.loadBalancerName(physical))
                .loadBalancerAttributes().connectionSettings().idleTimeout());
    }

    @Test
    void laterFailureRestoresThePreviousListenerHealthAttributesAndTags() {
        ObjectNode template = template(name);
        create(template);
        LoadBalancerDescription before = describe(name);
        ObjectNode properties = properties(template);
        ((ObjectNode) properties.path("Listeners").get(0)).put("InstancePort", 8082);
        ((ObjectNode) properties.path("HealthCheck")).put("Target", "HTTP:8082/ready");
        properties.put("CrossZone", false);
        properties.putArray("Tags").addObject().put("Key", "changed").put("Value", "true");
        ObjectNode bad = properties(template(name + "-bad")).deepCopy();
        ((ObjectNode) bad.path("HealthCheck")).put("Timeout", 1);
        ObjectNode badResource = ((ObjectNode) template.path("Resources")).putObject("Bad")
                .put("Type", "AWS::ElasticLoadBalancing::LoadBalancer").put("DependsOn", "Web");
        badResource.set("Properties", bad);

        update(template, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(before, describe(name));
        assertEquals(Map.of("phase", "first"), tags(name));
        assertTrue(elb.describeLoadBalancerAttributes(request -> request.loadBalancerName(name))
                .loadBalancerAttributes().crossZoneLoadBalancing().enabled());
        assertEquals(List.of(name), names());
        assertFailureContains("HealthCheck.Timeout");
    }

    @Test
    void invalidHealthCheckDoesNotLeakTheCreatedLoadBalancer() {
        ObjectNode template = template(name);
        ((ObjectNode) properties(template).path("HealthCheck")).put("Timeout", 1);
        stackId = cfn.createStack(request -> request.stackName(name).templateBody(template.toString())).stackId();
        awaitStatus("ROLLBACK_COMPLETE");

        assertEquals(List.of(), names());
        assertFailureContains("HealthCheck.Timeout");
    }

    @Test
    void sameNameOutsideTheStackIsNotAdoptedOrDeleted() {
        elb.createLoadBalancer(request -> request.loadBalancerName(name).subnets(subnet).securityGroups(group)
                .listeners(listener -> listener.protocol("HTTP").loadBalancerPort(80).instancePort(9000)));
        LoadBalancerDescription before = describe(name);
        stackId = cfn.createStack(request -> request.stackName(name).templateBody(template(name).toString())).stackId();
        awaitStatus("ROLLBACK_COMPLETE");

        assertEquals(before, describe(name));
        assertFailureContains("already exists");
    }

    @Test
    void renamingReplacesAndDeletesOnlyTheDisplacedLoadBalancer() {
        ObjectNode template = template(name);
        create(template);
        properties(template).put("LoadBalancerName", name + "-new");
        update(template, "UPDATE_COMPLETE");

        assertEquals(name + "-new", outputs().get("Name"));
        assertEquals(List.of(name + "-new"), names());
        assertThrows(LoadBalancerNotFoundException.class, () -> describe(name));
    }

    @Test
    void schemeReplacementKeepsAnUnnamedResourceDistinct() {
        ObjectNode template = template(null);
        create(template);
        String first = outputs().get("Name");
        properties(template).put("Scheme", "internal");
        update(template, "UPDATE_COMPLETE");

        String second = outputs().get("Name");
        assertNotEquals(first, second);
        assertEquals("internal", describe(second).scheme());
        assertEquals(List.of(second), names());
    }

    @Test
    void customNameRequiresANewNameForSchemeReplacement() {
        ObjectNode template = template(name);
        create(template);
        LoadBalancerDescription before = describe(name);
        properties(template).put("Scheme", "internal");
        update(template, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(before, describe(name));
        assertFailureContains("custom-named");
    }

    @Test
    void droppingManagedAttributesAndTagsResetsTheirDefaults() {
        ObjectNode template = template(name);
        ObjectNode properties = properties(template);
        properties.putObject("ConnectionDrainingPolicy").put("Enabled", true).put("Timeout", 17);
        properties.putObject("ConnectionSettings").put("IdleTimeout", 41);
        create(template);
        properties.remove(List.of("CrossZone", "ConnectionDrainingPolicy", "ConnectionSettings", "Tags"));
        update(template, "UPDATE_COMPLETE");

        assertFalse(elb.describeLoadBalancerAttributes(request -> request.loadBalancerName(name))
                .loadBalancerAttributes().crossZoneLoadBalancing().enabled());
        assertFalse(elb.describeLoadBalancerAttributes(request -> request.loadBalancerName(name))
                .loadBalancerAttributes().connectionDraining().enabled());
        assertEquals(300, elb.describeLoadBalancerAttributes(request -> request.loadBalancerName(name))
                .loadBalancerAttributes().connectionDraining().timeout());
        assertEquals(60, elb.describeLoadBalancerAttributes(request -> request.loadBalancerName(name))
                .loadBalancerAttributes().connectionSettings().idleTimeout());
        assertEquals(Map.of(), tags(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AppCookieStickinessPolicy", "LBCookieStickinessPolicy"})
    void unsupportedPoliciesCannotProduceAGreenStack(String property) {
        ObjectNode template = template(name);
        properties(template).putArray(property).addObject().put("PolicyName", "policy");
        stackId = cfn.createStack(request -> request.stackName(name).templateBody(template.toString())).stackId();
        awaitStatus("ROLLBACK_COMPLETE");

        assertEquals(List.of(), names());
        assertFailureContains(property);
    }

    @Test
    void deletionRetiresTheActualClassicResource() {
        create(template(name));
        cfn.deleteStack(request -> request.stackName(stackId));
        awaitStatus("DELETE_COMPLETE");
        stackId = null;

        assertThrows(LoadBalancerNotFoundException.class, () -> describe(name));
        assertEquals(List.of(), names());
    }

    private void create(ObjectNode template) {
        stackId = cfn.createStack(request -> request.stackName(name).templateBody(template.toString())).stackId();
        awaitStatus("CREATE_COMPLETE");
    }

    private void update(ObjectNode template, String status) {
        cfn.updateStack(request -> request.stackName(stackId).templateBody(template.toString()));
        awaitStatus(status);
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() ->
                assertEquals(expected, cfn.describeStacks(request -> request.stackName(stackId))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private LoadBalancerDescription describe(String physical) {
        return elb.describeLoadBalancers(request -> request.loadBalancerNames(physical))
                .loadBalancerDescriptions().getFirst();
    }

    private List<String> names() {
        return elb.describeLoadBalancers().loadBalancerDescriptions().stream()
                .map(LoadBalancerDescription::loadBalancerName).sorted().toList();
    }

    private Map<String, String> tags(String physical) {
        return elb.describeTags(request -> request.loadBalancerNames(physical)).tagDescriptions().getFirst().tags()
                .stream().collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private Map<String, String> outputs() {
        return cfn.describeStacks(request -> request.stackName(stackId)).stacks().getFirst().outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private void assertFailureContains(String message) {
        assertTrue(cfn.describeStackEvents(request -> request.stackName(stackId)).stackEvents().stream()
                .anyMatch(event -> event.resourceStatusReason() != null
                        && event.resourceStatusReason().contains(message)), message);
    }

    private static ObjectNode properties(ObjectNode template) {
        return (ObjectNode) template.path("Resources").path("Web").path("Properties");
    }

    private ObjectNode template(String physical) {
        ObjectNode template = MAPPER.createObjectNode();
        ObjectNode properties = template.putObject("Resources").putObject("Web")
                .put("Type", "AWS::ElasticLoadBalancing::LoadBalancer").putObject("Properties");
        if (physical != null) {
            properties.put("LoadBalancerName", physical);
        }
        properties.putArray("Subnets").add(subnet);
        properties.putArray("SecurityGroups").add(group);
        properties.put("CrossZone", "true");
        properties.putArray("Listeners").addObject().put("Protocol", "HTTP")
                .put("LoadBalancerPort", "80").put("InstancePort", "8080");
        properties.putObject("HealthCheck").put("Target", "HTTP:8080/ping").put("Timeout", "3")
                .put("Interval", "10").put("HealthyThreshold", "2").put("UnhealthyThreshold", "2");
        properties.putArray("Tags").addObject().put("Key", "phase").put("Value", "first");
        ObjectNode outputs = template.putObject("Outputs");
        outputs.putObject("Name").putObject("Value").put("Ref", "Web");
        Map<String, String> attributes = Map.of("Dns", "DNSName", "ZoneName", "CanonicalHostedZoneName",
                "ZoneId", "CanonicalHostedZoneNameID", "GroupName", "SourceSecurityGroup.GroupName",
                "GroupOwner", "SourceSecurityGroup.OwnerAlias");
        attributes.forEach((key, attribute) -> outputs.putObject(key).putObject("Value")
                .putArray("Fn::GetAtt").add("Web").add(attribute));
        return template;
    }
}
