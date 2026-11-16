package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.model.IamPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamManagedPolicyCaseTest {

    private static final String TRUST = "{\"Statement\":{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}}";

    private final IamService iam = new IamService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"), false);

    @ParameterizedTest
    @CsvSource({
        "aws, AWSXRayDaemonWriteAccess, AWSXrayDaemonWriteAccess",
        "aws, service-role/AWSLambdaBasicExecutionRole, SERVICE-ROLE/awslambdabasicexecutionrole",
        "aws-cn, AWSXRayDaemonWriteAccess, awsxraydaemonwriteaccess",
        "aws-us-gov, AWSXRayDaemonWriteAccess, AWSXRAYDAEMONWRITEACCESS"
    })
    void lookupPreservesCanonicalIdentityAndDefaultVersion(String partition, String name, String alias) {
        String prefix = AwsManagedPolicies.arnPrefix(partition) + "/";
        IamPolicy canonical = iam.getPolicy(prefix + name);
        IamPolicy observed = iam.getPolicy(prefix + alias);
        assertEquals(canonical.getArn(), observed.getArn());
        assertEquals(canonical.getPolicyId(), observed.getPolicyId());
        assertEquals(canonical.getPolicyName(), observed.getPolicyName());
        assertEquals(canonical.getDefaultDocument(),
                iam.getPolicyVersion(prefix + alias, observed.getDefaultVersionId()).getDocument());
        AwsException missing = assertThrows(AwsException.class, () -> iam.getPolicy(prefix + alias + "Missing"));
        assertEquals("NoSuchEntity", missing.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "user", "group"})
    void alternateSpellingsShareOneAttachmentAndDetach(String kind) {
        String canonical = AwsManagedPolicies.arnPrefix("aws") + "/AWSXRayDaemonWriteAccess";
        String alias = AwsManagedPolicies.arnPrefix("aws") + "/AWSXrayDaemonWriteAccess";
        iam.createRole("reader", "/", TRUST, null, 3600, null);
        iam.createUser("reader", "/");
        iam.createGroup("reader", "/");
        BiConsumer<String, String> attach = switch (kind) {
            case "role" -> iam::attachRolePolicy;
            case "user" -> iam::attachUserPolicy;
            default -> iam::attachGroupPolicy;
        };
        BiConsumer<String, String> detach = switch (kind) {
            case "role" -> iam::detachRolePolicy;
            case "user" -> iam::detachUserPolicy;
            default -> iam::detachGroupPolicy;
        };
        Supplier<List<IamPolicy>> attached = switch (kind) {
            case "role" -> () -> iam.listAttachedRolePolicies("reader", null);
            case "user" -> () -> iam.listAttachedUserPolicies("reader", null);
            default -> () -> iam.listAttachedGroupPolicies("reader", null);
        };
        attach.accept("reader", alias);
        attach.accept("reader", canonical);
        assertEquals(List.of(canonical), attached.get().stream().map(IamPolicy::getArn).toList());
        assertEquals(1, iam.getPolicy(alias).getAttachmentCount());
        assertEquals(1, iam.getPolicy(canonical).getAttachmentCount());
        IamService.PolicyEntities entities = iam.listEntitiesForPolicy(alias);
        assertEquals(1, entities.roles().size() + entities.users().size() + entities.groups().size());
        detach.accept("reader", canonical);
        assertTrue(attached.get().isEmpty());
        assertEquals(0, iam.getPolicy(alias).getAttachmentCount());
        attach.accept("reader", canonical);
        detach.accept("reader", alias);
        assertTrue(attached.get().isEmpty());
    }

    @Test
    void permissionBoundariesRetainTheCanonicalPolicyArn() {
        String canonical = AwsManagedPolicies.arnPrefix("aws") + "/AmazonS3ReadOnlyAccess";
        String alias = AwsManagedPolicies.arnPrefix("aws") + "/amazons3readonlyaccess";
        iam.createUser("bounded", "/", alias);
        iam.createRole("bounded", "/", TRUST, null, 3600, null, alias);
        assertEquals(canonical, iam.getUser("bounded").getPermissionsBoundaryArn());
        assertEquals(canonical, iam.getRole("bounded").getPermissionsBoundaryArn());
        iam.putUserPermissionsBoundary("bounded", alias);
        iam.putRolePermissionsBoundary("bounded", alias);
        assertEquals(canonical, iam.getUser("bounded").getPermissionsBoundaryArn());
        assertEquals(canonical, iam.getRole("bounded").getPermissionsBoundaryArn());
    }
}
