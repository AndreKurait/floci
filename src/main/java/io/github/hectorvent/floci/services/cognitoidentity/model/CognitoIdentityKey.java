package io.github.hectorvent.floci.services.cognitoidentity.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Persisted signing material. The pool and owner come from trusted storage, never JWT claims. */
@RegisterForReflection
public record CognitoIdentityKey(String keyId, String identityPoolId, String accountId, String region,
                                String publicKey, String privateKey) {
}
