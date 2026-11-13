package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;
import software.amazon.awssdk.services.elasticloadbalancing.model.DuplicatePolicyNameException;
import software.amazon.awssdk.services.elasticloadbalancing.model.ElasticLoadBalancingException;
import software.amazon.awssdk.services.elasticloadbalancing.model.InvalidConfigurationRequestException;
import software.amazon.awssdk.services.elasticloadbalancing.model.Listener;
import software.amazon.awssdk.services.elasticloadbalancing.model.ListenerNotFoundException;
import software.amazon.awssdk.services.elasticloadbalancing.model.PolicyAttributeDescription;
import software.amazon.awssdk.services.elasticloadbalancing.model.PolicyDescription;
import software.amazon.awssdk.services.elasticloadbalancing.model.PolicyNotFoundException;
import software.amazon.awssdk.services.iam.IamClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ElbClassicPolicyIntegrationTest.Records.class)
class ElbClassicPolicyIntegrationTest {
    public static class Records implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elb.mock", "true", "floci.services.ec2.mock", "true");
        }
    }

    private static final String TLS12 = "ELBSecurityPolicy-TLS-1-2-2017-01";
    private static final String DEFAULT = "ELBSecurityPolicy-2016-08";
    private final String name = "policy-" + UUID.randomUUID().toString().substring(0, 8);
    private ElasticLoadBalancingClient elb;
    private IamClient iam;
    private Listener listener;

    @BeforeEach
    void createTlsListener() {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        elb = AwsTestClients.create(ElasticLoadBalancingClient.builder(), account, Region.US_EAST_1);
        iam = AwsTestClients.create(IamClient.builder(), account, Region.US_EAST_1);
        CertificateGenerator.GeneratedCertificate pair = new CertificateGenerator()
                .generateSelfSignedCertificate("policy.example.test", List.of(), KeyAlgorithm.EC_prime256v1);
        String arn = iam.uploadServerCertificate(request -> request.serverCertificateName(name)
                .certificateBody(pair.certificatePem()).privateKey(pair.privateKeyPem()))
                .serverCertificateMetadata().arn();
        listener = Listener.builder().protocol("HTTPS").loadBalancerPort(443).instanceProtocol("HTTP")
                .instancePort(8080).sslCertificateId(arn).build();
        elb.createLoadBalancer(request -> request.loadBalancerName(name)
                .availabilityZones("us-east-1a").listeners(listener));
    }

    @AfterEach
    void cleanup() {
        try {
            elb.deleteLoadBalancer(request -> request.loadBalancerName(name));
            iam.deleteServerCertificate(request -> request.serverCertificateName(name));
        } finally {
            elb.close();
            iam.close();
        }
    }

    @Test
    void tlsListenerStartsWithTheAwsDefaultPolicy() {
        assertEquals(List.of(DEFAULT), bindings());
        assertEquals("true", attributes(policy(DEFAULT)).get("Server-Defined-Cipher-Order"));
        assertEquals("true", attributes(policy(DEFAULT)).get("Protocol-TLSv1"));
    }

    @Test
    void referenceCreationExposesTheFullDefinitionWithoutChangingTheListener() {
        reference("chosen", TLS12);
        PolicyDescription predefined = elb.describeLoadBalancerPolicies(request -> request.policyNames(TLS12))
                .policyDescriptions().getFirst();

        assertEquals("SSLNegotiationPolicyType", policy("chosen").policyTypeName());
        assertEquals(attributes(predefined), attributes(policy("chosen")));
        assertEquals(93, attributes(predefined).size());
        assertEquals("false", attributes(predefined).get("Protocol-TLSv1.1"));
        assertEquals("true", attributes(predefined).get("ECDHE-RSA-AES128-GCM-SHA256"));
        assertEquals("false", attributes(predefined).get("ECDHE-RSA-AES128-SHA"));
        assertEquals(List.of(DEFAULT), bindings());
    }

    @Test
    void bindingReplacesItsPreviousListAndAttachedPoliciesCannotBeDeleted() {
        reference("chosen", TLS12);
        bind("chosen");
        assertEquals(List.of("chosen"), bindings());
        assertTrue(elb.describeLoadBalancers(request -> request.loadBalancerNames(name))
                .loadBalancerDescriptions().getFirst().policies().otherPolicies().contains("chosen"));
        InvalidConfigurationRequestException failure = assertThrows(InvalidConfigurationRequestException.class,
                () -> elb.deleteLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName("chosen")));
        assertEquals(409, failure.statusCode());

        bind(DEFAULT);
        elb.deleteLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName("chosen"));
        assertThrows(PolicyNotFoundException.class, () -> policy("chosen"));
        elb.deleteLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName("chosen"));
    }

    @Test
    void emptyListDisassociatesAllPoliciesInApiRecordMode() {
        bind();
        assertEquals(List.of(), bindings());
        elb.deleteLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName(DEFAULT));
        assertEquals(List.of(), elb.describeLoadBalancerPolicies(request -> request.loadBalancerName(name))
                .policyDescriptions());
    }

    @Test
    void duplicatePolicyCreationPreservesTheOriginalDefinition() {
        reference("chosen", TLS12);
        PolicyDescription before = policy("chosen");
        assertThrows(DuplicatePolicyNameException.class, () -> reference("chosen", DEFAULT));
        assertEquals(before, policy("chosen"));
    }

    @Test
    void missingPolicyOrListenerCannotChangeAnExistingBinding() {
        reference("chosen", TLS12);
        bind("chosen");
        assertThrows(PolicyNotFoundException.class, () -> bind(DEFAULT, "missing"));
        assertThrows(ListenerNotFoundException.class, () -> elb.setLoadBalancerPoliciesOfListener(request ->
                request.loadBalancerName(name).loadBalancerPort(444).policyNames("chosen")));
        assertEquals(List.of("chosen"), bindings());
    }

    @Test
    void incompatibleOrMultipleNegotiationPoliciesCannotReplaceTheBinding() {
        reference("chosen", TLS12);
        reference("second", DEFAULT);
        bind("chosen");
        assertThrows(InvalidConfigurationRequestException.class, () -> bind("chosen", "second"));
        elb.createLoadBalancerListeners(request -> request.loadBalancerName(name).listeners(value ->
                value.protocol("HTTP").loadBalancerPort(80).instancePort(8080)));
        assertThrows(InvalidConfigurationRequestException.class, () -> elb.setLoadBalancerPoliciesOfListener(request ->
                request.loadBalancerName(name).loadBalancerPort(80).policyNames("chosen")));
        assertEquals(List.of("chosen"), bindings());
    }

    @Test
    void repeatListenerCreationPreservesItsPolicyAndDeletionDropsItsReference() {
        reference("chosen", TLS12);
        bind("chosen");
        elb.createLoadBalancerListeners(request -> request.loadBalancerName(name).listeners(listener));
        assertEquals(List.of("chosen"), bindings());
        elb.deleteLoadBalancerListeners(request -> request.loadBalancerName(name).loadBalancerPorts(443));
        elb.deleteLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName("chosen"));
        assertThrows(PolicyNotFoundException.class, () -> policy("chosen"));
    }

    @Test
    void customBooleanSelectionDoesNotInheritUnrequestedProtocolsOrCiphers() {
        elb.createLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName("custom")
                .policyTypeName("SSLNegotiationPolicyType").policyAttributes(
                        value -> value.attributeName("Protocol-TLSv1.2").attributeValue("true"),
                        value -> value.attributeName("ECDHE-RSA-AES128-GCM-SHA256").attributeValue("true")));
        Map<String, String> actual = attributes(policy("custom"));
        assertEquals(2, actual.values().stream().filter("true"::equals).count());
        assertEquals("false", actual.get("Server-Defined-Cipher-Order"));
        assertEquals("false", actual.get("Protocol-TLSv1"));
        bind("custom");
        assertEquals(List.of("custom"), bindings());
    }

    @Test
    void aDifferentAccountCannotReadOrCollideWithThePolicyName() {
        reference("chosen", TLS12);
        try (ElasticLoadBalancingClient other = AwsTestClients.create(
                ElasticLoadBalancingClient.builder(), "998877665544", Region.US_EAST_1)) {
            other.createLoadBalancer(request -> request.loadBalancerName(name).availabilityZones("us-east-1a")
                    .listeners(value -> value.protocol("HTTP").loadBalancerPort(80).instancePort(8080)));
            try {
                assertThrows(PolicyNotFoundException.class, () -> other.describeLoadBalancerPolicies(request ->
                        request.loadBalancerName(name).policyNames("chosen")));
                other.createLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName("chosen")
                        .policyTypeName("SSLNegotiationPolicyType").policyAttributes(value ->
                                value.attributeName("Reference-Security-Policy").attributeValue(DEFAULT)));
                assertEquals("false", attributes(policy("chosen")).get("Protocol-TLSv1"));
            } finally {
                other.deleteLoadBalancer(request -> request.loadBalancerName(name));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ELBSecurityPolicy-forbidden", "ELBSample-forbidden"})
    void reservedPolicyNamesAreRejected(String reserved) {
        ElasticLoadBalancingException failure = assertThrows(ElasticLoadBalancingException.class,
                () -> reference(reserved, TLS12));
        assertEquals("InvalidConfigurationRequest", failure.awsErrorDetails().errorCode());
        assertFalse(elb.describeLoadBalancerPolicies(request -> request.loadBalancerName(name))
                .policyDescriptions().stream().anyMatch(value -> value.policyName().equals(reserved)));
    }

    @Test
    void repeatedAttributesFailWithAStructuredError() {
        InvalidConfigurationRequestException failure = assertThrows(InvalidConfigurationRequestException.class,
                () -> elb.createLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName("duplicate")
                        .policyTypeName("SSLNegotiationPolicyType").policyAttributes(
                                value -> value.attributeName("Protocol-TLSv1.2").attributeValue("true"),
                                value -> value.attributeName("Protocol-TLSv1.2").attributeValue("false"))));
        assertEquals(409, failure.statusCode());
        assertThrows(PolicyNotFoundException.class, () -> policy("duplicate"));
    }

    private void reference(String policyName, String reference) {
        elb.createLoadBalancerPolicy(request -> request.loadBalancerName(name).policyName(policyName)
                .policyTypeName("SSLNegotiationPolicyType").policyAttributes(value ->
                        value.attributeName("Reference-Security-Policy").attributeValue(reference)));
    }

    private void bind(String... names) {
        elb.setLoadBalancerPoliciesOfListener(request ->
                request.loadBalancerName(name).loadBalancerPort(443).policyNames(names));
    }

    private List<String> bindings() {
        return elb.describeLoadBalancers(request -> request.loadBalancerNames(name)).loadBalancerDescriptions()
                .getFirst().listenerDescriptions().stream().filter(value -> value.listener().loadBalancerPort() == 443)
                .findFirst().orElseThrow().policyNames();
    }

    private PolicyDescription policy(String policyName) {
        return elb.describeLoadBalancerPolicies(request -> request.loadBalancerName(name).policyNames(policyName))
                .policyDescriptions().getFirst();
    }

    private static Map<String, String> attributes(PolicyDescription policy) {
        return policy.policyAttributeDescriptions().stream().collect(Collectors.toMap(
                PolicyAttributeDescription::attributeName, PolicyAttributeDescription::attributeValue));
    }
}
