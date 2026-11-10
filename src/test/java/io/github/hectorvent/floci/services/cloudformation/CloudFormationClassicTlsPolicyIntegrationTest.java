package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.LoadBalancerDescription;
import software.amazon.awssdk.services.elasticloadbalancing.model.PolicyAttributeDescription;
import software.amazon.awssdk.services.elasticloadbalancing.model.PolicyDescription;
import software.amazon.awssdk.services.iam.IamClient;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationClassicTlsPolicyIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT = "ELBSecurityPolicy-2016-08";
    private static final String TLS12 = "ELBSecurityPolicy-TLS-1-2-2017-01";
    private static final String STRICT = "StrictTls";
    private final String name = "cfn-tls-" + UUID.randomUUID().toString().substring(0, 8);
    private CloudFormationClient cfn;
    private ElasticLoadBalancingClient elb;
    private IamClient iam;
    private String certificate;
    private String stackId;

    @BeforeEach
    void certificate() {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        elb = AwsTestClients.create(ElasticLoadBalancingClient.builder(), account, Region.US_EAST_1);
        iam = AwsTestClients.create(IamClient.builder(), account, Region.US_EAST_1);
        CertificateGenerator.GeneratedCertificate pair = new CertificateGenerator()
                .generateSelfSignedCertificate("cfn-policy.example.test", List.of(), KeyAlgorithm.EC_prime256v1);
        certificate = iam.uploadServerCertificate(request -> request.serverCertificateName(name)
                .certificateBody(pair.certificatePem()).privateKey(pair.privateKeyPem()))
                .serverCertificateMetadata().arn();
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
                for (LoadBalancerDescription value : elb.describeLoadBalancers().loadBalancerDescriptions()) {
                    elb.deleteLoadBalancer(request -> request.loadBalancerName(value.loadBalancerName()));
                }
                iam.deleteServerCertificate(request -> request.serverCertificateName(name));
            } finally {
                cfn.close();
                elb.close();
                iam.close();
            }
        }
    }

    @Test
    void nativeReferenceAndListenerBindingReachTheClassicApi() {
        create(template());

        PolicyDescription predefined = elb.describeLoadBalancerPolicies(request -> request.policyNames(TLS12))
                .policyDescriptions().getFirst();
        assertEquals(attributes(predefined), definitions().get(STRICT));
        assertEquals(93, definitions().get(STRICT).size());
        assertEquals(List.of(STRICT), bindings());
        assertEquals(List.of(DEFAULT, STRICT), policyNames());
        assertEquals(name, cfn.describeStacks(request -> request.stackName(stackId)).stacks().getFirst()
                .outputs().getFirst().outputValue());
    }

    @Test
    void policyLoadBalancerPortsCanDeclareTheBinding() {
        ObjectNode template = template();
        listener(template).remove("PolicyNames");
        policy(template).putArray("LoadBalancerPorts").add("443");
        create(template);

        assertEquals(List.of(STRICT), bindings());
        assertEquals("false", definitions().get(STRICT).get("Protocol-TLSv1.1"));
    }

    @Test
    void unusedPolicyDoesNotAttachToAnHttpListener() {
        ObjectNode template = template();
        listener(template).put("Protocol", "HTTP").put("LoadBalancerPort", 80);
        listener(template).remove(List.of("PolicyNames", "SSLCertificateId"));
        create(template);

        assertEquals(List.of(), bindings());
        assertEquals(List.of(STRICT), policyNames());
    }

    @Test
    void unrelatedUpdateKeepsPolicyIdentityBindingsAndUnmanagedPolicies() {
        ObjectNode template = template();
        create(template);
        reference("External", DEFAULT);
        Map<String, Map<String, String>> before = definitions();
        LoadBalancerDescription original = describe();
        properties(template).putArray("Tags").addObject().put("Key", "phase").put("Value", "updated");
        update(template, "UPDATE_COMPLETE");

        assertEquals(before, definitions());
        assertEquals(List.of(STRICT), bindings());
        assertEquals(original.dnsName(), describe().dnsName());
        assertEquals(List.of(name), loadBalancerNames());
    }

    @Test
    void namedPolicyDefinitionCanChangeWithoutLeavingTemporaryPolicies() {
        ObjectNode template = template();
        create(template);
        reference("External", TLS12);
        changeReference(template, DEFAULT);
        update(template, "UPDATE_COMPLETE");

        assertEquals(List.of(STRICT), bindings());
        assertEquals("true", definitions().get(STRICT).get("Protocol-TLSv1.1"));
        assertEquals("false", definitions().get("External").get("Protocol-TLSv1.1"));
        assertEquals(List.of(DEFAULT, "External", STRICT), policyNames());
    }

    @Test
    void removingTemplatePoliciesDeletesOnlyManagedPoliciesAndRestoresDefaultBinding() {
        ObjectNode template = template();
        create(template);
        reference("External", TLS12);
        removePolicies(template);
        update(template, "UPDATE_COMPLETE");

        assertEquals(List.of(DEFAULT), bindings());
        assertEquals(List.of(DEFAULT, "External"), policyNames());
    }

    @Test
    void missingListenerPolicyRollsBackTheNewResource() {
        ObjectNode template = template();
        listener(template).putArray("PolicyNames").add("missing-policy");
        create(template, "ROLLBACK_COMPLETE");

        assertEquals(List.of(), loadBalancerNames());
        assertFailureContains("missing-policy");
    }

    @Test
    void failedBindingAfterNamedReplacementRestoresDefinitionsAndBindings() {
        ObjectNode template = template();
        create(template);
        Map<String, Map<String, String>> before = definitions();
        changeReference(template, DEFAULT);
        listener(template).putArray("PolicyNames").add("missing-policy");
        update(template, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(before, definitions());
        assertEquals(List.of(STRICT), bindings());
        assertEquals(List.of(DEFAULT, STRICT), policyNames());
        assertFailureContains("missing-policy");
    }

    @Test
    void laterResourceFailureRestoresPoliciesBindingsAndManagedOwnership() {
        ObjectNode template = template();
        create(template);
        reference("External", TLS12);
        Map<String, Map<String, String>> before = definitions();
        changeReference(template, DEFAULT);
        ArrayNode policies = (ArrayNode) properties(template).path("Policies");
        policies.add(policyDefinition("Extra", TLS12));
        ObjectNode invalid = properties(template()).deepCopy().put("LoadBalancerName", name + "-bad");
        invalid.putObject("HealthCheck").put("Target", "HTTP:8080/ping").put("Timeout", 1)
                .put("Interval", 10).put("HealthyThreshold", 2).put("UnhealthyThreshold", 2);
        ObjectNode badResource = ((ObjectNode) template.path("Resources")).putObject("Bad")
                .put("Type", "AWS::ElasticLoadBalancing::LoadBalancer").put("DependsOn", "Web");
        badResource.set("Properties", invalid);
        update(template, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(before, definitions());
        assertEquals(List.of(STRICT), bindings());
        assertEquals(List.of(name), loadBalancerNames());
        assertFailureContains("HealthCheck.Timeout");

        reference("Extra", DEFAULT);
        ObjectNode recovered = template();
        removePolicies(recovered);
        update(recovered, "UPDATE_COMPLETE");
        assertEquals(List.of(DEFAULT), bindings());
        assertEquals(List.of(DEFAULT, "External", "Extra"), policyNames());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reference", "backend", "duplicate", "undeclared-port"})
    void invalidPolicyCannotLeaveAnAllocatedLoadBalancer(String invalid) {
        ObjectNode template = template();
        switch (invalid) {
            case "reference" -> changeReference(template, "ELBSecurityPolicy-does-not-exist");
            case "backend" -> policy(template).putArray("InstancePorts").add(8080);
            case "duplicate" -> ((ArrayNode) properties(template).path("Policies"))
                    .add(policy(template).deepCopy());
            case "undeclared-port" -> policy(template).putArray("LoadBalancerPorts").add(444);
            default -> throw new AssertionError(invalid);
        }
        create(template, "ROLLBACK_COMPLETE");

        assertEquals(List.of(), loadBalancerNames());
        assertFailureContains(Map.of("reference", "ELBSecurityPolicy-does-not-exist", "backend", "InstancePorts",
                "duplicate", "Repeated Classic PolicyName", "undeclared-port", "undeclared listener").get(invalid));
    }

    @Test
    void deletingTheStackRetiresItsPolicyBearingLoadBalancer() {
        create(template());
        cfn.deleteStack(request -> request.stackName(stackId));
        awaitStatus("DELETE_COMPLETE");
        stackId = null;

        assertEquals(List.of(), loadBalancerNames());
    }

    private void create(ObjectNode template) {
        create(template, "CREATE_COMPLETE");
    }

    private void create(ObjectNode template, String status) {
        stackId = cfn.createStack(request -> request.stackName(name).templateBody(template.toString())).stackId();
        awaitStatus(status);
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

    private void reference(String policy, String predefined) {
        elb.createLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName(policy)
                .policyTypeName("SSLNegotiationPolicyType").policyAttributes(attribute ->
                        attribute.attributeName("Reference-Security-Policy").attributeValue(predefined)));
    }

    private LoadBalancerDescription describe() {
        return elb.describeLoadBalancers(request -> request.loadBalancerNames(name))
                .loadBalancerDescriptions().getFirst();
    }

    private List<String> bindings() {
        return describe().listenerDescriptions().getFirst().policyNames();
    }

    private List<String> loadBalancerNames() {
        return elb.describeLoadBalancers().loadBalancerDescriptions().stream()
                .map(LoadBalancerDescription::loadBalancerName).sorted().toList();
    }

    private List<String> policyNames() {
        return definitions().keySet().stream().sorted().toList();
    }

    private Map<String, Map<String, String>> definitions() {
        return elb.describeLoadBalancerPolicies(request -> request.loadBalancerName(name)).policyDescriptions()
                .stream().collect(Collectors.toMap(PolicyDescription::policyName,
                        CloudFormationClassicTlsPolicyIntegrationTest::attributes));
    }

    private static Map<String, String> attributes(PolicyDescription policy) {
        return policy.policyAttributeDescriptions().stream().collect(Collectors.toMap(
                PolicyAttributeDescription::attributeName, PolicyAttributeDescription::attributeValue));
    }

    private void assertFailureContains(String message) {
        assertTrue(cfn.describeStackEvents(request -> request.stackName(stackId)).stackEvents().stream()
                .anyMatch(event -> event.resourceStatusReason() != null
                        && event.resourceStatusReason().contains(message)), message);
    }

    private static void changeReference(ObjectNode template, String reference) {
        ((ObjectNode) policy(template).path("Attributes").get(0)).put("Value", reference);
    }

    private static void removePolicies(ObjectNode template) {
        properties(template).remove("Policies");
        listener(template).remove("PolicyNames");
    }

    private static ObjectNode policy(ObjectNode template) {
        return (ObjectNode) properties(template).path("Policies").get(0);
    }

    private static ObjectNode listener(ObjectNode template) {
        return (ObjectNode) properties(template).path("Listeners").get(0);
    }

    private static ObjectNode properties(ObjectNode template) {
        return (ObjectNode) template.path("Resources").path("Web").path("Properties");
    }

    private ObjectNode template() {
        ObjectNode template = MAPPER.createObjectNode();
        ObjectNode properties = template.putObject("Resources").putObject("Web")
                .put("Type", "AWS::ElasticLoadBalancing::LoadBalancer").putObject("Properties");
        properties.put("LoadBalancerName", name);
        properties.putArray("AvailabilityZones").add("us-east-1a");
        properties.putArray("Listeners").addObject().put("Protocol", "HTTPS")
                .put("LoadBalancerPort", "443").put("InstancePort", "8080").put("InstanceProtocol", "HTTP")
                .put("SSLCertificateId", certificate).putArray("PolicyNames").add(STRICT);
        properties.putArray("Policies").add(policyDefinition(STRICT, TLS12));
        template.putObject("Outputs").putObject("Name").putObject("Value").put("Ref", "Web");
        return template;
    }

    private static ObjectNode policyDefinition(String name, String reference) {
        ObjectNode policy = MAPPER.createObjectNode().put("PolicyName", name)
                .put("PolicyType", "SSLNegotiationPolicyType");
        policy.putArray("Attributes").addObject().put("Name", "Reference-Security-Policy").put("Value", reference);
        return policy;
    }
}
