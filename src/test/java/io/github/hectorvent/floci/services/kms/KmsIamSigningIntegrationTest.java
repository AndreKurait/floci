package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KmsException;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class KmsIamSigningIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void scopedSignerUsesAliasAndDeniesAnotherKeyWithForbiddenStatus() throws Exception {
        String account = "000000000000";
        String alias = "alias/iam-signing-" + UUID.randomUUID();
        try (KmsClient root = AwsTestClients.create(KmsClient.builder(), account, Region.US_EAST_1)) {
            String allowed = root.createKey(request -> request.keySpec("RSA_2048")
                    .keyUsage("SIGN_VERIFY")).keyMetadata().arn();
            String denied = root.createKey(request -> request.keySpec("RSA_2048")
                    .keyUsage("SIGN_VERIFY")).keyMetadata().arn();
            root.createAlias(request -> request.aliasName(alias).targetKeyId(allowed));
            try {
                String name = "kms-signer-" + UUID.randomUUID();
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(account, "iam"), name);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(account, "iam"), name, "sign-one", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Action":["kms:Sign","kms:GetPublicKey"],"Resource":"%s"}]}""".formatted(allowed));
                try (KmsClient user = AwsTestClients.create(KmsClient.builder(), credentials.accessKeyId(),
                        credentials.secretAccessKey(), Region.US_EAST_1)) {
                    byte[] message = "native BARS manifest signing".getBytes(StandardCharsets.UTF_8);
                    SdkBytes digest = SdkBytes.fromByteArray(MessageDigest.getInstance("SHA-256").digest(message));
                    byte[] signature = user.sign(request -> request.keyId(alias).message(digest)
                            .messageType("DIGEST").signingAlgorithm("RSASSA_PKCS1_V1_5_SHA_256")).signature().asByteArray();
                    byte[] publicKey = user.getPublicKey(request -> request.keyId(alias)).publicKey().asByteArray();
                    Signature verifier = Signature.getInstance("SHA256withRSA");
                    verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey)));
                    verifier.update(message);
                    assertTrue(verifier.verify(signature));

                    KmsException signError = assertThrows(KmsException.class, () -> user.sign(request -> request
                            .keyId(denied).message(digest).messageType("DIGEST")
                            .signingAlgorithm("RSASSA_PKCS1_V1_5_SHA_256")));
                    assertEquals("AccessDeniedException", signError.awsErrorDetails().errorCode());
                    assertEquals(403, signError.statusCode());
                    KmsException publicKeyError = assertThrows(KmsException.class,
                            () -> user.getPublicKey(request -> request.keyId(denied)));
                    assertEquals("AccessDeniedException", publicKeyError.awsErrorDetails().errorCode());
                    assertEquals(403, publicKeyError.statusCode());
                }
            } finally {
                root.deleteAlias(request -> request.aliasName(alias));
                root.scheduleKeyDeletion(request -> request.keyId(allowed).pendingWindowInDays(7));
                root.scheduleKeyDeletion(request -> request.keyId(denied).pendingWindowInDays(7));
            }
        }
    }
}
