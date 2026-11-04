package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2SecurityGroupResourceTagIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-west-2"})
    void groupActionsRequireTheMatchingResourceTag(String regionName) {
        String name = "sg-tags-" + UUID.randomUUID();
        Region region = Region.of(regionName);
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), name);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), name, "group-tags", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Resource":"*",
                "Action":["ec2:DeleteSecurityGroup","ec2:AuthorizeSecurityGroupIngress",
                "ec2:AuthorizeSecurityGroupEgress","ec2:RevokeSecurityGroupIngress","ec2:RevokeSecurityGroupEgress"],
                "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"%s"}}}]}
                """.formatted(name));
        try (Ec2Client root = client(region, "test", "test");
             Ec2Client caller = client(region, credentials.accessKeyId(), credentials.secretAccessKey())) {
            String vpc = root.createVpc(request -> request.cidrBlock("10.244.0.0/24")).vpc().vpcId();
            List<String> owned = new ArrayList<>();
            try {
                String matching = group(root, vpc, name + "-matching", name);
                owned.add(matching);
                String other = group(root, vpc, name + "-other", "other");
                owned.add(other);
                String untagged = root.createSecurityGroup(request -> request.vpcId(vpc)
                        .groupName(name + "-untagged").description("IAM tag fixture")).groupId();
                owned.add(untagged);
                for (String groupId : owned) {
                    root.authorizeSecurityGroupIngress(request -> request.groupId(groupId).ipPermissions(permission(443)));
                    root.authorizeSecurityGroupEgress(request -> request.groupId(groupId).ipPermissions(permission(443)));
                }
                for (String groupId : List.of(other, untagged)) {
                    assertDenied(() -> caller.authorizeSecurityGroupIngress(
                            request -> request.groupId(groupId).ipPermissions(permission(8443))));
                    assertDenied(() -> caller.authorizeSecurityGroupEgress(
                            request -> request.groupId(groupId).ipPermissions(permission(8443))));
                    assertDenied(() -> caller.revokeSecurityGroupIngress(
                            request -> request.groupId(groupId).ipPermissions(permission(443))));
                    assertDenied(() -> caller.revokeSecurityGroupEgress(
                            request -> request.groupId(groupId).ipPermissions(permission(443))));
                    assertDenied(() -> caller.deleteSecurityGroup(request -> request.groupId(groupId)));
                    assertEquals(1, root.describeSecurityGroups(request -> request.groupIds(groupId)).securityGroups().size());
                }
                assertDoesNotThrow(() -> {
                    caller.authorizeSecurityGroupIngress(request -> request.groupId(matching).ipPermissions(permission(8443)));
                    caller.authorizeSecurityGroupEgress(request -> request.groupId(matching).ipPermissions(permission(8443)));
                    caller.revokeSecurityGroupIngress(request -> request.groupId(matching).ipPermissions(permission(443)));
                    caller.revokeSecurityGroupEgress(request -> request.groupId(matching).ipPermissions(permission(443)));
                    caller.deleteSecurityGroup(request -> request.groupId(matching));
                });
                owned.remove(matching);
            } finally {
                for (String groupId : owned) {
                    root.deleteSecurityGroup(request -> request.groupId(groupId));
                }
                root.deleteVpc(request -> request.vpcId(vpc));
            }
        }
    }

    private String group(Ec2Client root, String vpc, String name, String owner) {
        return root.createSecurityGroup(request -> request.vpcId(vpc).groupName(name).description("IAM tag fixture")
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.SECURITY_GROUP)
                        .tags(Tag.builder().key("Owner").value(owner).build()).build())).groupId();
    }

    private Ec2Client client(Region region, String key, String secret) {
        return Ec2Client.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret))).build();
    }

    private IpPermission permission(int port) {
        return IpPermission.builder().ipProtocol("tcp").fromPort(port).toPort(port)
                .ipRanges(IpRange.builder().cidrIp("192.0.2.0/32").build()).build();
    }

    private void assertDenied(Runnable request) {
        assertEquals(403, assertThrows(Ec2Exception.class, request::run).statusCode());
    }
}
