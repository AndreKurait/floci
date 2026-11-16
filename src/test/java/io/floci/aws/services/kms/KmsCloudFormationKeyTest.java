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

import java.nio.charset.StandardCharsets;
import java.util.Map;

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
        assertEquals("InvalidRequest", failure.getErrorCode());
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
