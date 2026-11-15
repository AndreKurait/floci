package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/** Registers an EC2 agent's public key using that instance's authenticated IMDS identity. */
@ApplicationScoped
public class SsmEc2RegistrationService {
    private final StorageBackend<String, Registration> registrations;
    private final IamService iam;
    private final Ec2Service ec2;
    private final RequestContext context;

    @RegisterForReflection
    public record Registration(String instanceId, String publicKey, String keyType, Instant registeredAt) {}

    @Inject
    public SsmEc2RegistrationService(StorageFactory storage, IamService iam, Ec2Service ec2,
                                     RequestContext context) {
        this.registrations = storage.create("ssm", "ssm-ec2-registrations.json", new TypeReference<>() {});
        this.iam = iam;
        this.ec2 = ec2;
        this.context = context;
    }

    public Map<String, String> register(JsonNode request, String region) {
        SessionCredential verified = context.getVerifiedSession();
        if (verified == null || iam.ec2InstanceIdentitySession(verified.getAccessKeyId()).orElse(null) != verified) {
            throw denied();
        }
        AwsArnUtils.Arn identity = AwsArnUtils.parse(verified.getEc2InstanceArn());
        String instanceId = request.path("InstanceId").asText("");
        if (!identity.region().equals(region) || !identity.accountId().equals(context.getAccountId())
                || !verified.getEc2InstanceId().equals(instanceId)) {
            throw denied();
        }
        Instance instance = ec2.getInstance(region, instanceId).orElseThrow(SsmEc2RegistrationService::denied);
        if (!identity.accountId().equals(instance.getOwnerId()) || instance.getState() == null
                || !"running".equals(instance.getState().getName())) {
            throw denied();
        }
        if (request.hasNonNull("ActivationId") || request.hasNonNull("ActivationCode")) {
            throw new AwsException("ValidationException", "EC2 identity registration does not use an activation.", 400);
        }
        String keyType = request.path("KeyType").asText("");
        String publicKey = request.path("PublicKey").asText("");
        if (!"rsa".equals(keyType) || !validPublicKey(publicKey)) {
            throw new AwsException("ValidationException", "PublicKey must be an RSA public key and KeyType must be rsa.", 400);
        }
        if (iam.ec2InstanceIdentitySession(verified.getAccessKeyId()).orElse(null) != verified
                || context.getVerifiedSession() != verified) {
            throw denied();
        }
        String key = region + "::" + instanceId;
        Registration current = registrations.get(key).orElse(null);
        if (current == null || !Objects.equals(current.publicKey(), publicKey)) {
            registrations.put(key, new Registration(instanceId, publicKey, keyType, Instant.now()));
        }
        // Online status comes from the agent's subsequent UpdateInstanceInformation heartbeat.
        return Map.of("InstanceId", instanceId);
    }

    private static boolean validPublicKey(String value) {
        if (value.isBlank() || value.length() > 8192) {
            return false;
        }
        String encoded = value;
        if (value.startsWith("-----BEGIN PUBLIC KEY-----")) {
            if (!value.stripTrailing().endsWith("-----END PUBLIC KEY-----")) {
                return false;
            }
            encoded = value.substring("-----BEGIN PUBLIC KEY-----".length(),
                    value.lastIndexOf("-----END PUBLIC KEY-----"));
        }
        try {
            byte[] der = Base64.getDecoder().decode(encoded.replaceAll("\\s", ""));
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            return key.getModulus().bitLength() >= 2048;
        } catch (GeneralSecurityException | IllegalArgumentException ignored) {
            // Invalid encodings and non-RSA keys are rejected by the caller.
            return false;
        }
    }

    private static AwsException denied() {
        return new AwsException("AccessDeniedException", "The request must identify its running EC2 instance.", 400);
    }
}
