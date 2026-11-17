package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class KmsKeyCfnIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    private static String template(String description) {
        return """
                {"Resources":{
                  "EncryptionKey":{"Type":"AWS::KMS::Key","Properties":{
                    "KeySpec":"SYMMETRIC_DEFAULT","KeyUsage":"ENCRYPT_DECRYPT"}},
                  "SigningKey":{"Type":"AWS::KMS::Key","Properties":{
                    "KeySpec":"RSA_2048","KeyUsage":"SIGN_VERIFY","Description":"%s",
                    "KeyPolicy":{"Version":"2012-10-17","Statement":[{
                      "Effect":"Allow","Action":"kms:*","Resource":"*",
                      "Principal":{"AWS":{"Fn::Sub":"arn:${AWS::Partition}:iam::${AWS::AccountId}:root"}}}]}
                  }}
                }}
                """.formatted(description);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkFailedLaterResourceRestoresKeySettings(boolean initiallyEnabled) throws Exception {
        String stack = "kms-update-rollback-" + Long.toString(System.nanoTime(), 36);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode template = mapper.createObjectNode();
        ObjectNode resources = template.putObject("Resources");
        ObjectNode key = resources.putObject("Key");
        key.put("Type", "AWS::KMS::Key");
        ObjectNode properties = key.putObject("Properties");
        properties.put("Enabled", initiallyEnabled);
        properties.put("Description", "before");
        properties.putObject("KeyPolicy").putArray("Statement");
        properties.putArray("Tags").addObject().put("Key", "original").put("Value", "before");
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
                KmsClient kms = KmsClient.builder().endpointOverride(endpoint)
                        .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                String id = cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId();
                String policy = kms.getKeyPolicy(request -> request.keyId(id).policyName("default")).policy();
                properties.put("Enabled", !initiallyEnabled);
                properties.put("Description", "after");
                properties.putObject("KeyPolicy").put("Id", "changed").putArray("Statement");
                properties.putArray("Tags").addObject().put("Key", "added").put("Value", "after");
                if (!initiallyEnabled) {
                    properties.put("EnableKeyRotation", true);
                }
                ObjectNode failure = resources.putObject("Failure");
                failure.put("Type", "AWS::KMS::Alias");
                failure.put("DependsOn", "Key");
                failure.putObject("Properties").put("AliasName", "alias/" + stack)
                        .put("TargetKeyId", "missing-" + stack);
                cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                assertEquals(id, cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId());
                assertEquals(initiallyEnabled, kms.describeKey(request -> request.keyId(id)).keyMetadata().enabled());
                assertEquals("before", kms.describeKey(request -> request.keyId(id)).keyMetadata().description());
                assertEquals(policy, kms.getKeyPolicy(request -> request.keyId(id).policyName("default")).policy());
                assertFalse(kms.getKeyRotationStatus(request -> request.keyId(id)).keyRotationEnabled());
                assertEquals(1, kms.listResourceTags(request -> request.keyId(id)).tags().size());
                assertEquals("original", kms.listResourceTags(request -> request.keyId(id)).tags().getFirst().tagKey());
                assertEquals("before", kms.listResourceTags(request -> request.keyId(id)).tags().getFirst().tagValue());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
        }
    }

    @Test
    void sdkFailedRotationRestoresEarlierKeyMutations() {
        String stack = "kms-partial-rollback-" + Long.toString(System.nanoTime(), 36);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode template = mapper.createObjectNode();
        ObjectNode key = template.putObject("Resources").putObject("Key");
        key.put("Type", "AWS::KMS::Key");
        ObjectNode properties = key.putObject("Properties");
        properties.put("Description", "before");
        properties.put("KeySpec", "HMAC_256");
        properties.put("KeyUsage", "GENERATE_VERIFY_MAC");
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
                KmsClient kms = KmsClient.builder().endpointOverride(endpoint)
                        .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                String id = cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId();
                properties.put("Description", "after");
                properties.put("EnableKeyRotation", true);
                cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                assertEquals("before", kms.describeKey(request -> request.keyId(id)).keyMetadata().description());
                assertTrue(kms.describeKey(request -> request.keyId(id)).keyMetadata().enabled());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkPendingDeletionUpdatePreservesPolicyAndTags(boolean includeTags) {
        String stack = "kms-pending-update-" + Long.toString(System.nanoTime(), 36);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode template = mapper.createObjectNode();
        ObjectNode key = template.putObject("Resources").putObject("Key");
        key.put("Type", "AWS::KMS::Key");
        ObjectNode properties = key.putObject("Properties");
        String originalPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[],\"Id\":\"original\"}";
        properties.put("KeyPolicy", originalPolicy);
        if (includeTags) {
            properties.putArray("Tags").addObject().put("Key", "state").put("Value", "original");
        }
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
                KmsClient kms = KmsClient.builder().endpointOverride(endpoint)
                        .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                String id = cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId();
                kms.scheduleKeyDeletion(request -> request.keyId(id).pendingWindowInDays(7));
                properties.put("KeyPolicy", originalPolicy.replace("original", "updated"));
                if (includeTags) {
                    properties.putArray("Tags").addObject().put("Key", "state").put("Value", "updated");
                }
                cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                assertEquals(originalPolicy, kms.getKeyPolicy(request -> request.keyId(id).policyName("default")).policy());
                assertEquals("PendingDeletion", kms.describeKey(request -> request.keyId(id))
                        .keyMetadata().keyStateAsString());
                assertEquals(id, cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId());
                if (includeTags) {
                    assertEquals("original", kms.listResourceTags(request -> request.keyId(id))
                            .tags().getFirst().tagValue());
                } else {
                    assertTrue(kms.listResourceTags(request -> request.keyId(id)).tags().isEmpty());
                }
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkReconcilesRotationOnCreateUpdateAndRemoval(boolean initiallyEnabled) {
        String stack = "kms-rotation-" + Long.toString(System.nanoTime(), 36);
        ObjectNode template = new ObjectMapper().createObjectNode();
        ObjectNode key = template.putObject("Resources").putObject("Key");
        key.put("Type", "AWS::KMS::Key");
        ObjectNode properties = key.putObject("Properties");
        properties.put("EnableKeyRotation", initiallyEnabled);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
                KmsClient kms = KmsClient.builder().endpointOverride(endpoint)
                        .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                String id = cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId();
                assertEquals(initiallyEnabled, kms.getKeyRotationStatus(request -> request.keyId(id)).keyRotationEnabled());
                for (boolean enabled : new boolean[]{!initiallyEnabled, initiallyEnabled, true}) {
                    properties.put("EnableKeyRotation", enabled);
                    properties.put("Description", "rotation-" + System.nanoTime());
                    cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                    assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                    assertEquals(enabled, kms.getKeyRotationStatus(request -> request.keyId(id)).keyRotationEnabled());
                    assertEquals(id, cfn.describeStackResource(request -> request.stackName(stack)
                            .logicalResourceId("Key")).stackResourceDetail().physicalResourceId());
                }
                properties.remove("EnableKeyRotation");
                cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                assertFalse(kms.getKeyRotationStatus(request -> request.keyId(id)).keyRotationEnabled());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
        }
    }

    @Test
    void sdkReadsResolvedStringPolicyAsJsonDocumentAfterCreateAndUpdate() throws Exception {
        String stack = "kms-text-policy-" + Long.toString(System.nanoTime(), 36);
        ObjectMapper mapper = new ObjectMapper();
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[],\"Id\":\"${AWS::StackName}\"}";
        ObjectNode template = mapper.createObjectNode();
        ObjectNode key = template.putObject("Resources").putObject("Key");
        key.put("Type", "AWS::KMS::Key");
        ObjectNode properties = key.putObject("Properties");
        properties.putObject("KeyPolicy").put("Fn::Sub", policy);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
                KmsClient kms = KmsClient.builder().endpointOverride(endpoint)
                        .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                String id = cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId();
                String expected = policy.replace("${AWS::StackName}", stack);
                assertEquals(expected, kms.getKeyPolicy(request -> request.keyId(id).policyName("default")).policy());
                String updated = "{\"Version\":\"2012-10-17\",\"Statement\":[],\"Id\":\"updated\"}";
                properties.put("KeyPolicy", updated);
                cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                assertEquals(id, cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId());
                assertEquals(updated, kms.getKeyPolicy(request -> request.keyId(id).policyName("default")).policy());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
        }
    }

    @Test
    void sdkUpdatesKeyWithRetainedOverrideAndMutableTags() throws Exception {
        String stack = "kms-override-tags-" + Long.toString(System.nanoTime(), 36);
        ObjectMapper mapper = new ObjectMapper();
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[],\"Id\":\"${AWS::StackName}\"}";
        ObjectNode template = mapper.createObjectNode();
        ObjectNode key = template.putObject("Resources").putObject("Key");
        key.put("Type", "AWS::KMS::Key");
        ObjectNode properties = key.putObject("Properties");
        properties.putObject("KeyPolicy").put("Fn::Sub", policy);
        properties.putArray("Tags").addObject().put("Key", "floci:override-id").put("Value", stack);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
                KmsClient kms = KmsClient.builder().endpointOverride(endpoint)
                        .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                String id = cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId();
                String expected = policy.replace("${AWS::StackName}", stack);
                assertEquals(expected, kms.getKeyPolicy(request -> request.keyId(id).policyName("default")).policy());
                String updated = "{\"Version\":\"2012-10-17\",\"Statement\":[],\"Id\":\"updated\"}";
                properties.put("KeyPolicy", updated);
                properties.withArray("Tags").addObject().put("Key", "mutable").put("Value", "updated");
                cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                assertEquals(id, cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId());
                assertEquals(updated, kms.getKeyPolicy(request -> request.keyId(id).policyName("default")).policy());
                assertEquals(stack, id);
                assertEquals(1, kms.listResourceTags(request -> request.keyId(id)).tags().size());
                assertEquals("mutable", kms.listResourceTags(request -> request.keyId(id)).tags().getFirst().tagKey());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
        }
    }

    @Test
    void sdkRemovingEnabledRestoresUsableKeyWithoutReplacement() {
        String stack = "kms-enabled-removal-" + Long.toString(System.nanoTime(), 36);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode template = mapper.createObjectNode();
        ObjectNode key = template.putObject("Resources").putObject("Key");
        key.put("Type", "AWS::KMS::Key");
        ObjectNode properties = key.putObject("Properties");
        properties.put("Enabled", false);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
                KmsClient kms = KmsClient.builder().endpointOverride(endpoint)
                        .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                String id = cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId();
                assertFalse(kms.describeKey(request -> request.keyId(id)).keyMetadata().enabled());
                properties.remove("Enabled");
                cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                assertEquals(id, cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId());
                assertTrue(kms.describeKey(request -> request.keyId(id)).keyMetadata().enabled());
                SdkBytes message = SdkBytes.fromUtf8String("restored key");
                SdkBytes encrypted = kms.encrypt(request -> request.keyId(id).plaintext(message)).ciphertextBlob();
                assertEquals(message, kms.decrypt(request -> request.ciphertextBlob(encrypted)).plaintext());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
        }
    }

    @Test
    void sdkRemovingPolicyRestoresAccountDefaultWithoutReplacement() throws Exception {
        String stack = "kms-policy-removal-" + Long.toString(System.nanoTime(), 36);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode template = mapper.createObjectNode();
        ObjectNode key = template.putObject("Resources").putObject("Key");
        key.put("Type", "AWS::KMS::Key");
        ObjectNode properties = key.putObject("Properties");
        properties.putObject("KeyPolicy").putArray("Statement");
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
                KmsClient kms = KmsClient.builder().endpointOverride(endpoint)
                        .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                String id = cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId();
                assertEquals("{\"Statement\":[]}", kms.getKeyPolicy(request -> request.keyId(id).policyName("default")).policy());
                properties.remove("KeyPolicy");
                cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
                assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                assertEquals(id, cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("Key")).stackResourceDetail().physicalResourceId());
                JsonNode restored = mapper.readTree(kms.getKeyPolicy(request -> request.keyId(id).policyName("default")).policy());
                assertEquals("2012-10-17", restored.path("Version").asText());
                assertEquals("Allow", restored.path("Statement").get(0).path("Effect").asText());
                assertEquals("kms:*", restored.path("Statement").get(0).path("Action").asText());
                assertTrue(restored.path("Statement").get(0).path("Principal").path("AWS").asText().endsWith(":root"));
                SdkBytes message = SdkBytes.fromUtf8String("restored key");
                SdkBytes encrypted = kms.encrypt(request -> request.keyId(id).plaintext(message)).ciphertextBlob();
                assertEquals(message, kms.decrypt(request -> request.ciphertextBlob(encrypted)).plaintext());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
        }
    }

    @Test
    void sdkCreatesUsablePurposeKeysAndUpdatesWithoutReplacingThem() {
        String stack = "kms-purpose-keys-" + Long.toString(System.nanoTime(), 36);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).credentialsProvider(credentials).build();
                KmsClient kms = KmsClient.builder().endpointOverride(endpoint)
                        .region(Region.US_EAST_1).credentialsProvider(credentials).build()) {
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody(template("before")));
                assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                String signing = cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("SigningKey")).stackResourceDetail().physicalResourceId();
                String encryption = cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("EncryptionKey")).stackResourceDetail().physicalResourceId();
                assertEquals(KeySpec.RSA_2048, kms.describeKey(request -> request.keyId(signing)).keyMetadata().keySpec());
                assertEquals(KeyUsageType.SIGN_VERIFY,
                        kms.describeKey(request -> request.keyId(signing)).keyMetadata().keyUsage());
                SdkBytes message = SdkBytes.fromUtf8String("aggregate");
                SdkBytes signature = kms.sign(request -> request.keyId(signing).message(message)
                        .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)).signature();
                SdkBytes ciphertext = kms.encrypt(request -> request.keyId(encryption).plaintext(message)).ciphertextBlob();
                cfn.updateStack(request -> request.stackName(stack).templateBody(template("after")));
                assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
                assertEquals(signing, cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("SigningKey")).stackResourceDetail().physicalResourceId());
                assertEquals(encryption, cfn.describeStackResource(request -> request.stackName(stack)
                        .logicalResourceId("EncryptionKey")).stackResourceDetail().physicalResourceId());
                assertEquals("after", kms.describeKey(request -> request.keyId(signing)).keyMetadata().description());
                assertTrue(kms.verify(request -> request.keyId(signing).message(message).signature(signature)
                        .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)).signatureValid());
                assertEquals(message, kms.decrypt(request -> request.ciphertextBlob(ciphertext)).plaintext());
                assertFalse(kms.getKeyPolicy(request -> request.keyId(signing).policyName("default"))
                        .policy().contains("Fn::Sub"));
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
            }
        }
    }
}
