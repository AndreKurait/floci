package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.floci.aws.services.kms.model.KmsAlias;
import io.floci.aws.services.kms.model.KmsGrant;
import io.floci.aws.services.kms.model.KmsKey;
import io.floci.aws.services.kms.model.KmsKeySpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KmsResourcePolicyProviderTest {
    private static final String OWNER = "111122223333";
    private static final String CALLER = "444455556666";
    private static final String REGION = "us-east-1";
    private static final String KEY_ID = "12345678-1234-1234-1234-123456789abc";
    private static final String ARN = "arn:aws:kms:" + REGION + ":" + OWNER + ":key/" + KEY_ID;
    private final InMemoryStorage<String, KmsKey> keys = new InMemoryStorage<>();
    private final InMemoryStorage<String, KmsGrant> grants = new InMemoryStorage<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final RequestContext request = mock(RequestContext.class);
    private final KmsService service = new KmsService(
            new AccountAwareStorageBackend<>(keys, null, OWNER),
            new AccountAwareStorageBackend<KmsAlias>(new InMemoryStorage<>(), null, OWNER),
            new AccountAwareStorageBackend<>(grants, null, OWNER), new RegionResolver(REGION, CALLER));
    private final KmsResourcePolicyProvider provider = new KmsResourcePolicyProvider(service, request, mapper);

    private KmsKey storeKey() {
        KmsKey key = new KmsKey();
        key.setKeyId(KEY_ID);
        key.setArn(ARN);
        key.setKeySpec(KmsKeySpec.SYMMETRIC_DEFAULT);
        key.setPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        keys.put(REGION + "::" + KEY_ID, key);
        return key;
    }

    private KmsGrant grant(String id, Map<String, Object> constraints) {
        KmsGrant grant = new KmsGrant();
        grant.setGrantId(id);
        grant.setKeyId(KEY_ID);
        grant.setKeyArn(ARN);
        grant.setGranteePrincipal("arn:aws:iam::" + CALLER + ":role/reader");
        grant.setOperations(List.of("Decrypt", "GenerateDataKey"));
        grant.setConstraints(constraints);
        return grant;
    }

    @Test
    void readsOwnerGrantsWithoutMigrationOrCallerMutationAcrossCredentialScopes() throws Exception {
        storeKey();
        grants.put(REGION + "::legacy", grant("legacy", Map.of("EncryptionContextEquals", Map.of("Team", "blue"))));
        grants.put(CALLER + "/" + REGION + "::foreign", grant("foreign", Map.of()));
        Set<String> keyNames = keys.keys();
        Set<String> grantNames = grants.keys();

        List<ResourcePolicy> policies = provider.getResourcePolicies("redshift-serverless", "kms:Decrypt", ARN);

        assertEquals(2, policies.size());
        assertTrue(policies.stream().allMatch(ResourcePolicy::grantRequired));
        assertTrue(policies.stream().allMatch(policy -> OWNER.equals(policy.ownerAccountId())));
        JsonNode statement = mapper.readTree(policies.get(1).policyDocument()).path("Statement").get(0);
        assertEquals("arn:aws:iam::" + CALLER + ":role/reader", statement.path("Principal").path("AWS").asText());
        assertEquals(List.of("kms:Decrypt", "kms:GenerateDataKey"),
                mapper.convertValue(statement.path("Action"), List.class));
        assertEquals("blue", statement.path("Condition").path("StringEquals")
                .path(KmsGrantConditions.encryptionContextKey("Team")).asText());
        assertEquals("Team", statement.path("Condition").path("ForAllValues:StringEqualsIgnoreCase")
                .path("kms:EncryptionContextKeys").get(0).asText());
        assertEquals(keyNames, keys.keys());
        assertEquals(grantNames, grants.keys());
        verifyNoInteractions(request);
        assertTrue(provider.getResourcePolicies("kms", "s3:GetObject", ARN).isEmpty());
    }

    @Test
    void keepsEmptyContextAndSourceConstraintsAndIgnoresContextForAsymmetricKeys() throws Exception {
        KmsKey key = storeKey();
        String source = "arn:aws:s3:::source";
        grants.put(REGION + "::grant", grant("grant", Map.of("EncryptionContextEquals", Map.of(), "SourceArn", source)));
        JsonNode symmetric = mapper.readTree(provider.getResourcePolicies("kms", ARN).get(1).policyDocument())
                .path("Statement").get(0).path("Condition");
        assertEquals("true", symmetric.path("Null").path("kms:EncryptionContextKeys").asText());
        assertEquals(source, symmetric.path("StringEquals").path("aws:SourceArn").asText());

        key.setKeySpec(KmsKeySpec.RSA_2048);
        JsonNode asymmetric = mapper.readTree(provider.getResourcePolicies("kms", ARN).get(1).policyDocument())
                .path("Statement").get(0).path("Condition");
        assertFalse(asymmetric.has("Null"));
        assertEquals(source, asymmetric.path("StringEquals").path("aws:SourceArn").asText());
    }

    @Test
    void readsAllGrantsAndPrefersAnAccountRowOverItsLegacyDuplicate() {
        storeKey();
        for (int i = 0; i < 105; i++) {
            String id = "grant-" + i;
            grants.put(REGION + "::" + id, grant(id, Map.of()));
        }
        grants.put(OWNER + "/" + REGION + "::grant-0", grant("grant-0", Map.of()));
        assertEquals(106, provider.getResourcePolicies("kms", ARN).size());
        assertEquals(106, grants.keys().size());
    }

    @Test
    void aBlankKeyPolicyStillRequiresAResourceGrant() {
        KmsKey key = storeKey();
        for (String policy : new String[]{null, "", "  "}) {
            key.setPolicy(policy);
            assertEquals(List.of(new ResourcePolicy(null, OWNER, true)), provider.getResourcePolicies("kms", ARN));
        }
    }

    @Test
    void missingOrWrongPartitionKeysRetainOwnerWithoutExposingPoliciesOrGrants() {
        storeKey();
        grants.put(REGION + "::grant", grant("grant", Map.of()));
        String foreign = ARN.replace(OWNER, CALLER);
        assertEquals(List.of(new ResourcePolicy(null, CALLER, false)), provider.getResourcePolicies("kms", foreign));
        assertEquals(List.of(new ResourcePolicy(null, OWNER, false)),
                provider.getResourcePolicies("kms", ARN.replace("arn:aws:", "arn:aws-cn:")));
        assertTrue(provider.getResourcePolicies("kms", ARN.replace("key/" + KEY_ID, "alias/test")).isEmpty());
        verifyNoInteractions(request);
    }
}
