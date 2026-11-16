package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
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
