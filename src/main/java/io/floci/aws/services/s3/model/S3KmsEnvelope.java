package io.floci.aws.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** Only wrapped key material and authenticated-body framing metadata are persisted. */
@RegisterForReflection
public record S3KmsEnvelope(int version, String keyArn, byte[] wrappedKey,
                            Map<String, String> encryptionContext, byte[] noncePrefix) {
    public S3KmsEnvelope {
        wrappedKey = wrappedKey.clone();
        encryptionContext = Map.copyOf(encryptionContext);
        noncePrefix = noncePrefix.clone();
    }
}
