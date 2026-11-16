package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.s3.model.S3KmsEnvelope;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** S3's delegated KMS calls retain the original caller and use the native key policy and key material. */
@ApplicationScoped
public class S3KmsEncryption {

    private final KmsService kms;
    private final IamEnforcementFilter authorization;
    private final RequestContext request;
    private final RegionResolver regions;
    private final SecureRandom random = new SecureRandom();

    public S3KmsEncryption(KmsService kms, IamEnforcementFilter authorization,
                           RequestContext request, RegionResolver regions) {
        this.kms = kms;
        this.authorization = authorization;
        this.request = request;
        this.regions = regions;
    }

    public record Material(S3KmsEnvelope envelope, byte[] plaintextKey) implements AutoCloseable {
        @Override
        public void close() {
            Arrays.fill(plaintextKey, (byte) 0);
        }
    }

    Material generate(String bucket, String object, String keyId, String region) {
        String selected = keyId == null || keyId.isBlank() ? "alias/aws/s3" : keyId;
        String owner = keyOwner(selected, region);
        String arn = withOwner(owner, () -> kms.describeKey(selected, region).getArn());
        Map<String, String> context = Map.of("aws:s3:arn",
                AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "s3", "", bucket + "/" + object).toString());
        authorize(arn, "kms:GenerateDataKey", context, region);
        Map<String, Object> result = withOwner(owner,
                () -> kms.generateDataKey(arn, "AES_256", null, context, region));
        return new Material(envelope(arn, (byte[]) result.get("CiphertextBlob"), context),
                (byte[]) result.get("Plaintext"));
    }

    Material decrypt(S3KmsEnvelope envelope) {
        requireEnvelope(envelope);
        AwsArnUtils.Arn arn = AwsArnUtils.parse(envelope.keyArn());
        authorize(envelope.keyArn(), "kms:Decrypt", envelope.encryptionContext(), arn.region());
        byte[] key = withOwner(arn.accountId(),
                () -> kms.decryptAndResolveKey(envelope.wrappedKey(), envelope.encryptionContext(), arn.region(),
                        envelope.keyArn()).plaintext());
        return new Material(envelope, key);
    }

    Material nextBody(S3KmsEnvelope existing) {
        Material key = decrypt(existing);
        return new Material(envelope(existing.keyArn(), existing.wrappedKey(), existing.encryptionContext()),
                key.plaintextKey());
    }

    InputStream encrypt(InputStream body, Material material) {
        return S3KmsCipher.encrypt(body, material.plaintextKey(), material.envelope().noncePrefix());
    }

    InputStream decrypt(InputStream body, S3KmsEnvelope envelope, long size) {
        try (Material material = decrypt(envelope)) {
            return S3KmsCipher.decrypt(body, material.plaintextKey(), envelope.noncePrefix(), size);
        }
    }

    static long storedSize(long size, S3KmsEnvelope envelope) {
        return envelope == null ? size : S3KmsCipher.storedSize(size);
    }

    static void requireEnvelope(S3KmsEnvelope envelope) {
        if (envelope == null || envelope.version() != 1) {
            throw new AwsException("InvalidObjectState",
                    "This SSE-KMS object predates encrypted storage. Re-upload it with an available KMS key.", 403);
        }
    }

    private S3KmsEnvelope envelope(String arn, byte[] wrappedKey, Map<String, String> context) {
        byte[] prefix = new byte[S3KmsCipher.PREFIX_SIZE];
        random.nextBytes(prefix);
        return new S3KmsEnvelope(1, arn, wrappedKey, context, prefix);
    }

    private String keyOwner(String selected, String region) {
        if (AwsArnUtils.looksLikeArn(selected)) {
            AwsArnUtils.Arn arn = AwsArnUtils.parse(selected);
            if (!"kms".equals(arn.service()) || !region.equals(arn.region())
                    || !AwsRegions.partitionFor(region).equals(arn.partition())) {
                throw new AwsException("KMS.NotFoundException", "The KMS key is not in the bucket's region.", 400);
            }
            return arn.accountId();
        }
        return regions.getAccountId();
    }

    private <T> T withOwner(String account, Supplier<T> operation) {
        String previous = request.getAccountId();
        request.setAccountId(account);
        try {
            return operation.get();
        } catch (AwsException error) {
            throw new AwsException("KMS." + error.getErrorCode(), error.getMessage(), error.getHttpStatus());
        } finally {
            request.setAccountId(previous);
        }
    }

    private void authorize(String keyArn, String action, Map<String, String> encryptionContext, String region) {
        String account = regions.getAccountId();
        Map<String, List<String>> context = new HashMap<>();
        context.put("kms:ViaService", List.of("s3." + region + "." + AwsRegions.dnsSuffixFor(region)));
        context.put("kms:CallerAccount", List.of(account));
        context.put("aws:ViaAWSService", List.of("true"));
        context.put("kms:EncryptionContextKeys", List.copyOf(encryptionContext.keySet()));
        for (Map.Entry<String, String> entry : encryptionContext.entrySet()) {
            context.put("kms:EncryptionContext:" + entry.getKey(), List.of(entry.getValue()));
        }
        authorization.authorizeDelegatedResource(action, keyArn, context);
    }
}
