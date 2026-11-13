package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.Role;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IamServiceLinkedRoleSdkIntegrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1"})
    void theSdkReadsTheOwnersPolicyInItsPartition(String regionName) {
        Region region = Region.of(regionName);
        try (IamClient owner = AwsTestClients.create(IamClient.builder(), "000000000352", region);
             IamClient other = AwsTestClients.create(IamClient.builder(), "000000000353", region)) {
            Role role = owner.createServiceLinkedRole(request -> request.awsServiceName(
                    "opensearchservice.amazonaws.com")).role();
            try {
                List<AttachedPolicy> policies = owner.listAttachedRolePolicies(
                        request -> request.roleName(role.roleName())).attachedPolicies();
                assertEquals(1, policies.size());
                assertEquals("AmazonOpenSearchServiceRolePolicy", policies.getFirst().policyName());
                String partition = regionName.startsWith("cn-") ? "aws-cn" : "aws";
                assertEquals("arn:" + partition
                        + ":iam::aws:policy/aws-service-role/AmazonOpenSearchServiceRolePolicy",
                        policies.getFirst().policyArn());
                IamException missing = assertThrows(IamException.class,
                        () -> other.getRole(request -> request.roleName(role.roleName())));
                assertEquals("NoSuchEntity", missing.awsErrorDetails().errorCode());
                assertEquals(role.roleName(), owner.getRole(request -> request.roleName(role.roleName()))
                        .role().roleName());
            } finally {
                owner.deleteServiceLinkedRole(request -> request.roleName(role.roleName()));
            }
            IamException deleted = assertThrows(IamException.class,
                    () -> owner.getRole(request -> request.roleName(role.roleName())));
            assertEquals("NoSuchEntity", deleted.awsErrorDetails().errorCode());
        }
    }

    @Test
    void theSdkDoesNotInventAPolicyForAnUnmappedService() {
        try (IamClient owner = AwsTestClients.create(IamClient.builder(), "000000000354", Region.US_EAST_1)) {
            Role role = owner.createServiceLinkedRole(request -> request.awsServiceName(
                    "autoscaling.amazonaws.com")).role();
            try {
                assertTrue(owner.listAttachedRolePolicies(request -> request.roleName(role.roleName()))
                        .attachedPolicies().isEmpty());
            } finally {
                owner.deleteServiceLinkedRole(request -> request.roleName(role.roleName()));
            }
        }
    }
}
