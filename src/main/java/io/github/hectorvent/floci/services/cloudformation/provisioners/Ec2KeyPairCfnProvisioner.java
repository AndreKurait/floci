package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ssm.SsmService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Real EC2 keys, including the SSM private-key record owned by generated CFN keys. */
@ApplicationScoped
public class Ec2KeyPairCfnProvisioner implements CfnResourceProvisioner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRIOR_NAME = "__FlociKeyPairPriorName";
    private static final String PRIOR_INPUTS = "__FlociKeyPairPriorInputs";
    private static final String PRIOR_GENERATED = "__FlociKeyPairPriorGenerated";
    private static final String GENERATED = "__FlociKeyPairGenerated";
    private static final String INPUTS = "__FlociKeyPairInputs";
    private final Ec2Service ec2;
    private final SsmService ssm;

    @Inject
    public Ec2KeyPairCfnProvisioner(Ec2Service ec2, SsmService ssm) {
        this.ec2 = ec2;
        this.ssm = ssm;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::KeyPair");
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "KeyName");
        String material = ctx.resolveOptional(props, "PublicKeyMaterial");
        String type = ctx.resolveOptional(props, "KeyType");
        String format = ctx.resolveOptional(props, "KeyFormat");
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationError", "AWS::EC2::KeyPair requires KeyName.", 400);
        }
        if (material == null && ((type != null && !"rsa".equals(type))
                || (format != null && !"pem".equals(format)))) {
            throw new AwsException("ValidationError", "Floci currently generates only RSA PEM key pairs.", 400);
        }
        if (material != null && material.isBlank()) {
            throw new AwsException("ValidationError", "PublicKeyMaterial must not be empty.", 400);
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        String inputs = MAPPER.valueToTree(List.of(name, material == null ? "" : material,
                material == null && type != null ? type : "rsa",
                material == null && format != null ? format : "pem",
                new TreeMap<>(tags))).toString();
        Map<String, String> before = new HashMap<>(resource.getAttributes());
        KeyPair key = ctx.isUpdate() && name.equals(ctx.priorPhysicalId())
                && inputs.equals(before.get(INPUTS)) ? find(name, ctx.region()) : null;
        if (key != null && !key.getKeyPairId().equals(before.get("KeyPairId"))) {
            throw new AwsException("ValidationError", "The managed EC2 key pair was replaced outside this stack.", 400);
        }
        if (key == null) {
            key = material == null ? ec2.createKeyPair(ctx.region(), name)
                    : ec2.importKeyPair(ctx.region(), name, material);
            boolean parameterCreated = false;
            try {
                if (material == null) {
                    ssm.putParameter(parameter(key), key.getKeyMaterial(), "SecureString",
                            "CloudFormation EC2 key pair", false, ctx.region());
                    parameterCreated = true;
                }
                Ec2Tags.reconcile(ec2, ctx.region(), key.getKeyPairId(), tags);
            } catch (RuntimeException failure) {
                try {
                    if (parameterCreated) {
                        ssm.deleteParameter(parameter(key), ctx.region());
                    }
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                try {
                    ec2.deleteKeyPair(ctx.region(), null, key.getKeyPairId());
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        resource.setPhysicalId(key.getKeyName());
        resource.getAttributes().put("KeyPairId", key.getKeyPairId());
        resource.getAttributes().put("KeyFingerprint", key.getKeyFingerprint());
        resource.getAttributes().put(INPUTS, inputs);
        resource.getAttributes().put(GENERATED, Boolean.toString(material == null));
        if (ctx.isUpdate() && !ctx.priorPhysicalId().equals(resource.getPhysicalId())) {
            resource.getAttributes().put(PRIOR_NAME, ctx.priorPhysicalId());
            restore(resource, PRIOR_INPUTS, before.get(INPUTS));
            restore(resource, PRIOR_GENERATED, before.get(GENERATED));
        }
        ReplacementCleanup.record(resource, ctx, before);
    }

    private KeyPair find(String name, String region) {
        try {
            return ec2.describeKeyPairs(region, List.of(name), List.of()).getFirst();
        } catch (AwsException expected) {
            if (!"InvalidKeyPair.NotFound".equals(expected.getErrorCode())) {
                throw expected;
            }
            return null;
        }
    }

    private static String parameter(KeyPair key) {
        return "/ec2/keypair/" + key.getKeyPairId();
    }

    @Override
    public void delete(StackResource resource, String region) {
        String id = resource.getAttributes().get("KeyPairId");
        if (id == null) {
            delete(resource.getResourceType(), resource.getPhysicalId(), region);
            return;
        }
        if (Boolean.parseBoolean(resource.getAttributes().get(GENERATED))) {
            CfnDeletes.safeDelete("EC2 key pair parameter", id,
                    () -> ssm.deleteParameter("/ec2/keypair/" + id, region), "ParameterNotFound");
        }
        ec2.deleteKeyPair(region, null, id);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        KeyPair key = find(physicalId, region);
        if (key == null) {
            return;
        }
        if (key.getKeyMaterial() != null) {
            CfnDeletes.safeDelete("EC2 key pair parameter", parameter(key),
                    () -> ssm.deleteParameter(parameter(key), region), "ParameterNotFound");
        }
        ec2.deleteKeyPair(region, null, key.getKeyPairId());
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
        clearPrior(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String prior = resource.getAttributes().get(PRIOR_NAME);
        String inputs = resource.getAttributes().get(PRIOR_INPUTS);
        String generated = resource.getAttributes().get(PRIOR_GENERATED);
        try {
            return ReplacementCleanup.rollback(resource, this::delete);
        } finally {
            if (prior != null && prior.equals(resource.getPhysicalId())) {
                restore(resource, INPUTS, inputs);
                restore(resource, GENERATED, generated);
                clearPrior(resource);
            }
        }
    }

    private static void restore(StackResource resource, String key, String value) {
        if (value == null) {
            resource.getAttributes().remove(key);
        } else {
            resource.getAttributes().put(key, value);
        }
    }

    private static void clearPrior(StackResource resource) {
        resource.getAttributes().remove(PRIOR_NAME);
        resource.getAttributes().remove(PRIOR_INPUTS);
        resource.getAttributes().remove(PRIOR_GENERATED);
    }
}
