package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.KmsCfnProvisioner;
import io.floci.aws.services.cloudformation.provisioners.ProvisionContext;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class KmsCloudFormationKeyTest {
    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final KmsService kms = new KmsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new RegionResolver(REGION, "111111111111"));
    private final KmsCfnProvisioner provisioner = new KmsCfnProvisioner(kms);
    private final CloudFormationTemplateEngine engine = CloudFormationTemplateEngine.standalone(
            "111111111111", REGION, "keys", mapper, value -> value);

    private StackResource create(String properties) throws Exception {
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::KMS::Key");
        resource.setLogicalId("Key");
        provisioner.provision(resource, mapper.readTree(properties), context(null, null));
        return resource;
    }

    private ProvisionContext context(String priorId, JsonNode priorProperties) {
        return new ProvisionContext(engine, REGION, "111111111111", "keys", priorId, event -> {}, priorProperties);
    }

    @Test
    void declaredRsaKeySignsAndVerifiesInsteadOfCreatingASymmetricKey() throws Exception {
        StackResource resource = create("{\"KeySpec\":\"RSA_2048\",\"KeyUsage\":\"SIGN_VERIFY\"}");
        KmsKey key = kms.describeKey(resource.getPhysicalId(), REGION);
        assertEquals("RSA_2048", key.getKeySpec().name());
        assertEquals("SIGN_VERIFY", key.getKeyUsage().name());
        byte[] message = "native aggregate".getBytes(StandardCharsets.UTF_8);
        byte[] signature = kms.sign(key.getKeyId(), message, "RSASSA_PKCS1_V1_5_SHA_256", REGION);
        assertDoesNotThrow(() -> kms.verify(key.getKeyId(), message, signature, "RSASSA_PKCS1_V1_5_SHA_256", REGION));
        assertEquals(key.getArn(), resource.getAttributes().get("Arn"));
    }

    @Test
    void omittedSpecAndUsageKeepTheSymmetricDefault() throws Exception {
        StackResource resource = create("{}");
        byte[] message = "artifact".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(message, kms.decrypt(kms.encrypt(resource.getPhysicalId(), message, REGION), REGION));
    }

    @Test
    void unchangedUpdateKeepsTheKeyAndItsSigningMaterial() throws Exception {
        String properties = "{\"KeySpec\":\"RSA_2048\",\"KeyUsage\":\"SIGN_VERIFY\"}";
        StackResource resource = create(properties);
        String id = resource.getPhysicalId();
        String publicKey = kms.getPublicKey(id, REGION).getPublicKeyEncoded();
        int keyCount = kms.listKeys(REGION).size();
        provisioner.provision(resource, mapper.readTree(properties), context(id, mapper.readTree(properties)));
        assertEquals(id, resource.getPhysicalId());
        assertEquals(publicKey, kms.getPublicKey(id, REGION).getPublicKeyEncoded());
        assertEquals(keyCount, kms.listKeys(REGION).size());
    }

    @Test
    void immutableChangeFailsBeforeMutablePropertiesChange() throws Exception {
        StackResource resource = create("{\"Description\":\"before\"}");
        String id = resource.getPhysicalId();
        int keyCount = kms.listKeys(REGION).size();
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource,
                mapper.readTree("{\"Description\":\"after\",\"KeySpec\":\"RSA_2048\",\"KeyUsage\":\"SIGN_VERIFY\"}"),
                context(id, null)));
        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals("before", kms.describeKey(id, REGION).getDescription());
        assertEquals(id, resource.getPhysicalId());
        assertEquals(keyCount, kms.listKeys(REGION).size());
    }

    @Test
    void resolvesPolicyIntrinsicsAndAppliesDisabledState() throws Exception {
        StackResource resource = create("""
                {"Enabled":false,"KeyPolicy":{"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"kms:*","Resource":"*","Principal":
                {"AWS":{"Fn::Sub":"arn:${AWS::Partition}:iam::${AWS::AccountId}:root"}}}]}}
                """);
        KmsKey key = kms.describeKey(resource.getPhysicalId(), REGION);
        assertFalse(key.isEnabled());
        assertFalse(key.getPolicy().contains("Fn::Sub"));
        assertTrue(key.getPolicy().contains(":root"));
    }

    @Test
    void resolvedStringPolicyIsStoredAsADocumentOnCreateAndUpdate() throws Exception {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[],\"Id\":\"${AWS::AccountId}\"}";
        JsonNode properties = mapper.createObjectNode().set("KeyPolicy",
                mapper.createObjectNode().put("Fn::Sub", policy));
        StackResource resource = create(properties.toString());
        String expected = policy.replace("${AWS::AccountId}", "111111111111");
        assertEquals(expected, kms.describeKey(resource.getPhysicalId(), REGION).getPolicy());
        assertTrue(mapper.readTree(kms.describeKey(resource.getPhysicalId(), REGION).getPolicy()).isObject());

        String updated = "{\"Version\":\"2012-10-17\",\"Statement\":[],\"Id\":\"updated\"}";
        JsonNode next = mapper.createObjectNode().put("KeyPolicy", updated);
        provisioner.provision(resource, next, context(resource.getPhysicalId(), properties));
        assertEquals(updated, kms.describeKey(resource.getPhysicalId(), REGION).getPolicy());
    }

    @Test
    void removingDeclaredPolicyRestoresAccountDefaultWithoutReplacingKey() throws Exception {
        JsonNode prior = mapper.readTree("{\"KeyPolicy\":{\"Statement\":[]}}");
        StackResource resource = create(prior.toString());
        String id = resource.getPhysicalId();
        provisioner.provision(resource, mapper.createObjectNode(), context(id, prior));
        JsonNode policy = mapper.readTree(kms.describeKey(id, REGION).getPolicy());
        assertEquals("2012-10-17", policy.path("Version").asText());
        JsonNode statement = policy.path("Statement").get(0);
        assertEquals("Allow", statement.path("Effect").asText());
        assertEquals("arn:aws:iam::111111111111:root", statement.path("Principal").path("AWS").asText());
        assertEquals("kms:*", statement.path("Action").asText());
        assertEquals("*", statement.path("Resource").asText());
        assertEquals(id, resource.getPhysicalId());
    }

    @Test
    void omittedPolicyDoesNotResetUndeclaredOutOfBandPolicy() throws Exception {
        StackResource resource = create("{}");
        String policy = "{\"Statement\":[],\"Id\":\"manual\"}";
        kms.putKeyPolicy(resource.getPhysicalId(), policy, REGION);
        provisioner.provision(resource, mapper.createObjectNode(), context(resource.getPhysicalId(), mapper.createObjectNode()));
        assertEquals(policy, kms.describeKey(resource.getPhysicalId(), REGION).getPolicy());
    }

    @Test
    void retainedOverrideReconcilesMutableTagsWithoutChangingIdentity() throws Exception {
        JsonNode prior = mapper.readTree("""
                {"Description":"before","Tags":[{"Key":"floci:override-id","Value":"fixed-key"},
                {"Key":"old","Value":"v"}]}
                """);
        StackResource resource = create(prior.toString());
        JsonNode next = mapper.readTree("""
                {"Description":"after","Tags":[{"Key":"floci:override-id","Value":" fixed-key "},
                {"Key":"new","Value":"v2"}]}
                """);
        provisioner.provision(resource, next, context(resource.getPhysicalId(), prior));
        assertEquals("fixed-key", resource.getPhysicalId());
        assertEquals("after", kms.describeKey("fixed-key", REGION).getDescription());
        assertEquals(Map.of("new", "v2"), kms.describeKey("fixed-key", REGION).getTags());
        provisioner.provision(resource, mapper.readTree("{}"), context(resource.getPhysicalId(), next));
        assertTrue(kms.describeKey("fixed-key", REGION).getTags().isEmpty());
    }

    @Test
    void changedOverrideFailsBeforeAnyMutableUpdate() throws Exception {
        JsonNode prior = mapper.readTree("""
                {"Description":"before","Tags":[{"Key":"floci:override-id","Value":"fixed-key"},
                {"Key":"old","Value":"v"}]}
                """);
        StackResource resource = create(prior.toString());
        JsonNode next = mapper.readTree("""
                {"Description":"after","Enabled":false,"KeyPolicy":{"Statement":[]},
                "Tags":[{"Key":"floci:override-id","Value":"different-key"}]}
                """);
        String policy = kms.describeKey("fixed-key", REGION).getPolicy();
        int keyCount = kms.listKeys(REGION).size();
        assertThrows(AwsException.class, () -> provisioner.provision(resource, next,
                context(resource.getPhysicalId(), prior)));
        KmsKey key = kms.describeKey("fixed-key", REGION);
        assertEquals("before", key.getDescription());
        assertEquals(policy, key.getPolicy());
        assertTrue(key.isEnabled());
        assertEquals(Map.of("old", "v"), key.getTags());
        assertEquals("fixed-key", resource.getPhysicalId());
        assertEquals(keyCount, kms.listKeys(REGION).size());
    }

    static Stream<Arguments> invalidUpdateTags() {
        return Stream.of(
                Arguments.of("floci:override-id", "", "TagException"),
                Arguments.of("floci:override-id", "   ", "TagException"),
                Arguments.of("floci:override-id", "\u0001", "TagException"),
                Arguments.of("floci:override-id", "other-key", "InvalidRequest"),
                Arguments.of("floci:override-cognito-client-id", "client", "ValidationException"),
                Arguments.of("floci:unknown", "value", "ValidationException"));
    }

    @ParameterizedTest
    @MethodSource("invalidUpdateTags")
    void invalidReservedTagFailsBeforeAnyMutableUpdate(String tagKey, String tagValue, String errorCode)
            throws Exception {
        JsonNode prior = mapper.readTree("""
                {"Description":"before","Tags":[{"Key":"floci:override-id","Value":"fixed-key"},
                {"Key":"owned","Value":"before"}]}
                """);
        StackResource resource = create(prior.toString());
        String policy = kms.describeKey("fixed-key", REGION).getPolicy();
        int keyCount = kms.listKeys(REGION).size();
        JsonNode next = mapper.createObjectNode()
                .put("Description", "after")
                .put("Enabled", false)
                .set("Tags", mapper.createArrayNode().add(mapper.createObjectNode()
                        .put("Key", tagKey).put("Value", tagValue)));

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource, next,
                context(resource.getPhysicalId(), prior)));

        KmsKey key = kms.describeKey("fixed-key", REGION);
        assertEquals(errorCode, failure.getErrorCode());
        assertEquals("before", key.getDescription());
        assertEquals(policy, key.getPolicy());
        assertTrue(key.isEnabled());
        assertEquals(Map.of("owned", "before"), key.getTags());
        assertEquals("fixed-key", resource.getPhysicalId());
        assertEquals(keyCount, kms.listKeys(REGION).size());
    }

    @Test
    void rollbackRestoresDroppedPolicyAndOwnedTagsWithoutChangingExternalTagsOrKeyMaterial() throws Exception {
        JsonNode prior = mapper.readTree("""
                {"Description":"before","KeyPolicy":{"Statement":[],"Id":"original"},
                "Tags":[{"Key":"owned","Value":"before"}]}
                """);
        StackResource resource = create(prior.toString());
        String id = resource.getPhysicalId();
        String policy = kms.describeKey(id, REGION).getPolicy();
        kms.tagResource(id, Map.of("external", "untouched"), REGION);
        byte[] plaintext = "rollback key material".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = kms.encrypt(id, plaintext, REGION);

        provisioner.provision(resource, mapper.readTree("{\"Description\":\"after\"}"), context(id, prior));

        assertTrue(kms.describeKey(id, REGION).getPolicy().contains(":root"));
        assertEquals(Map.of("external", "untouched"), kms.describeKey(id, REGION).getTags());
        assertTrue(provisioner.rollbackUpdate(resource));
        KmsKey restored = kms.describeKey(id, REGION);
        assertEquals(policy, restored.getPolicy());
        assertEquals("before", restored.getDescription());
        assertEquals(Map.of("owned", "before", "external", "untouched"), restored.getTags());
        assertEquals(id, resource.getPhysicalId());
        assertArrayEquals(plaintext, kms.decrypt(ciphertext, REGION));
        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void removingDisabledPropertyRestoresEnabledWithoutReplacingKey() throws Exception {
        JsonNode prior = mapper.readTree("{\"Enabled\":false}");
        StackResource resource = create(prior.toString());
        String id = resource.getPhysicalId();
        assertFalse(kms.describeKey(id, REGION).isEnabled());
        provisioner.provision(resource, mapper.readTree("{}"), context(id, prior));
        assertTrue(kms.describeKey(id, REGION).isEnabled());
        assertEquals(id, resource.getPhysicalId());
        byte[] message = "restored".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(message, kms.decrypt(kms.encrypt(id, message, REGION), REGION));
    }

    @Test
    void omittedEnabledDoesNotResetUndeclaredOutOfBandState() throws Exception {
        StackResource resource = create("{}");
        String id = resource.getPhysicalId();
        kms.disableKey(id, REGION);
        provisioner.provision(resource, mapper.readTree("{\"Description\":\"updated\"}"),
                context(id, mapper.readTree("{}")));
        assertFalse(kms.describeKey(id, REGION).isEnabled());
        assertEquals("updated", kms.describeKey(id, REGION).getDescription());
    }

    @Test
    void mutableUpdatePreservesIdentityAndReconcilesDescriptionPolicyTagsAndEnabled() throws Exception {
        JsonNode prior = mapper.readTree("""
                {"Description":"before","Tags":[{"Key":"old","Value":"v"}]}
                """);
        StackResource resource = create(prior.toString());
        String id = resource.getPhysicalId();
        JsonNode next = mapper.readTree("""
                {"Description":"after","Enabled":false,"KeyPolicy":{"Statement":[]},"Tags":[{"Key":"new","Value":"v2"}]}
                """);
        provisioner.provision(resource, next, context(id, prior));
        KmsKey key = kms.describeKey(id, REGION);
        assertEquals(id, resource.getPhysicalId());
        assertEquals("after", key.getDescription());
        assertEquals(Map.of("new", "v2"), key.getTags());
        assertEquals(next.get("KeyPolicy").toString(), key.getPolicy());
        assertFalse(key.isEnabled());
    }
}
