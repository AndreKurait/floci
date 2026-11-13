package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class Ec2SecurityGroupIamIntegrationTest {

    private static final String ADMIN = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"*","Resource":"*"}]}""";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @Test
    void scopedPrincipalChangesRulesAndTagsOnlyOnItsTargetGroup() throws Exception {
        Ec2Client admin = client(ADMIN);
        String vpc = vpc(admin);
        String target = group(admin, vpc);
        String peer = group(admin, vpc);
        String prefix = admin.createManagedPrefixList(request -> request.prefixListName("sdk-" + UUID.randomUUID())
                .addressFamily("IPv4").maxEntries(1)
                .entries(entry -> entry.cidr("192.0.2.0/24"))).prefixList().prefixListId();
        cleanup.register(() -> admin.deleteManagedPrefixList(request -> request.prefixListId(prefix)));
        Ec2Client actor = client("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Action":["ec2:AuthorizeSecurityGroupIngress","ec2:RevokeSecurityGroupIngress",
                            "ec2:AuthorizeSecurityGroupEgress","ec2:RevokeSecurityGroupEgress",
                            "ec2:CreateTags","ec2:DeleteTags"],
                  "Resource":"%s"}]}""".formatted(arn(admin, target)));
        IpPermission incoming = IpPermission.builder().ipProtocol("tcp").fromPort(22).toPort(22)
                .prefixListIds(item -> item.prefixListId(prefix)).build();
        actor.authorizeSecurityGroupIngress(request -> request.groupId(target).ipPermissions(incoming));
        assertEquals(prefix, describe(admin, target).ipPermissions().getFirst().prefixListIds().getFirst().prefixListId());
        denied(() -> actor.authorizeSecurityGroupIngress(request -> request.groupId(peer).ipPermissions(incoming)));
        assertTrue(describe(admin, peer).ipPermissions().isEmpty());
        actor.revokeSecurityGroupIngress(request -> request.groupId(target).ipPermissions(incoming));
        IpPermission referencedPeer = IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                .userIdGroupPairs(item -> item.groupId(peer)).build();
        actor.authorizeSecurityGroupIngress(request -> request.groupId(target).ipPermissions(referencedPeer));
        actor.revokeSecurityGroupIngress(request -> request.groupId(target).ipPermissions(referencedPeer));
        IpPermission outgoing = IpPermission.builder().ipProtocol("tcp").fromPort(443).toPort(443)
                .ipRanges(item -> item.cidrIp("192.0.2.0/24")).build();
        actor.authorizeSecurityGroupEgress(request -> request.groupId(target).ipPermissions(outgoing));
        actor.revokeSecurityGroupEgress(request -> request.groupId(target).ipPermissions(outgoing));
        actor.createTags(request -> request.resources(target).tags(tag -> tag.key("Managed").value("yes")));
        assertTrue(describe(admin, target).tags().stream().anyMatch(tag -> tag.key().equals("Managed")));
        actor.deleteTags(request -> request.resources(target).tags(tag -> tag.key("Managed")));
        assertTrue(describe(admin, target).tags().stream().noneMatch(tag -> tag.key().equals("Managed")));
        assertTrue(describe(admin, target).ipPermissions().isEmpty());
    }

    @Test
    void anExplicitGroupDenyOverridesAnUnscopedAllow() throws Exception {
        Ec2Client admin = client(ADMIN);
        String vpc = vpc(admin);
        String target = group(admin, vpc);
        Ec2Client actor = client("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:*","Resource":"*"},
                  {"Effect":"Deny","Action":"ec2:*","Resource":"%s"}]}""".formatted(arn(admin, target)));
        IpPermission rule = IpPermission.builder().ipProtocol("tcp").fromPort(22).toPort(22)
                .ipRanges(item -> item.cidrIp("192.0.2.0/24")).build();
        denied(() -> actor.authorizeSecurityGroupIngress(request -> request.groupId(target).ipPermissions(rule)));
        denied(() -> actor.createTags(request -> request.resources(target).tags(tag -> tag.key("Denied").value("yes"))));
        assertTrue(describe(admin, target).ipPermissions().isEmpty());
        assertTrue(describe(admin, target).tags().stream().noneMatch(tag -> tag.key().equals("Denied")));
    }

    @Test
    void aMixedTagRequestNeedsPermissionForEveryGroupBeforeAnyMutation() throws Exception {
        Ec2Client admin = client(ADMIN);
        String vpc = vpc(admin);
        String target = group(admin, vpc);
        String denied = group(admin, vpc);
        Ec2Client actor = client("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Action":"ec2:CreateTags","Resource":"%s"}]}""".formatted(arn(admin, target)));
        denied(() -> actor.createTags(request -> request.resources(target, denied)
                .tags(tag -> tag.key("Batch").value("yes"))));
        assertTrue(describe(admin, target).tags().stream().noneMatch(tag -> tag.key().equals("Batch")));
        assertTrue(describe(admin, denied).tags().stream().noneMatch(tag -> tag.key().equals("Batch")));
    }

    private Ec2Client client(String policy) throws Exception {
        SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "sg-iam-", "test", policy);
        cleanup.register(() -> {
            try {
                user.close();
            } catch (Exception error) {
                throw new IllegalStateException("Cannot retire the owned IAM test user", error);
            }
        });
        Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build();
        cleanup.register(client::close);
        return client;
    }

    private String vpc(Ec2Client client) {
        String id = client.createVpc(request -> request.cidrBlock("10.41.0.0/16")).vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(id)));
        return id;
    }

    private String group(Ec2Client client, String vpc) {
        String id = client.createSecurityGroup(request -> request.vpcId(vpc).groupName("sdk-" + UUID.randomUUID())
                .description("Scoped SDK authorization")).groupId();
        cleanup.register(() -> client.deleteSecurityGroup(request -> request.groupId(id)));
        return id;
    }

    private SecurityGroup describe(Ec2Client client, String group) {
        return client.describeSecurityGroups(request -> request.groupIds(group)).securityGroups().getFirst();
    }

    private String arn(Ec2Client client, String group) {
        return "arn:aws:ec2:us-east-1:" + describe(client, group).ownerId() + ":security-group/" + group;
    }

    private void denied(Runnable action) {
        assertEquals(403, assertThrows(Ec2Exception.class, action::run).statusCode());
    }
}
