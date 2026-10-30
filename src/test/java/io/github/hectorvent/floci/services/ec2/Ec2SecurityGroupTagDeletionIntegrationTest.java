package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2SecurityGroupTagDeletionIntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionRemovesGroupAndRuleTagsButPreservesOtherResources(boolean checkRuleFirst) {
        Ec2Client client = client();
        String vpc = vpc(client);
        String group = group(client, vpc);
        String retainedGroup = group(client, vpc);
        String rule = rule(client, group);
        String retainedRule = rule(client, retainedGroup);
        tag(client, List.of(group, rule, retainedGroup, retainedRule, vpc));
        assertEquals(2, tagCount(client, List.of(group, rule)));

        client.deleteSecurityGroup(request -> request.groupId(group));

        String first = checkRuleFirst ? rule : group;
        String second = checkRuleFirst ? group : rule;
        assertEquals(0, tagCount(client, List.of(first)), "Deleted resource tags must be removed: " + first);
        assertEquals(0, tagCount(client, List.of(second)), "Deleted resource tags must be removed: " + second);
        assertEquals(3, tagCount(client, List.of(retainedGroup, retainedRule, vpc)));
        assertTrue(client.describeSecurityGroupRules(request -> request.filters(
                Filter.builder().name("group-id").values(group).build())).securityGroupRules().isEmpty());
    }

    @Test
    void dryRunPreservesGroupAndRuleTags() {
        Ec2Client client = client();
        String group = group(client, vpc(client));
        String rule = rule(client, group);
        tag(client, List.of(group, rule));

        Ec2Exception error = assertThrows(Ec2Exception.class,
                () -> client.deleteSecurityGroup(request -> request.groupId(group).dryRun(true)));

        assertEquals("DryRunOperation", error.awsErrorDetails().errorCode());
        assertEquals(2, tagCount(client, List.of(group, rule)));
        assertEquals(1, client.describeSecurityGroups(request -> request.groupIds(group)).securityGroups().size());
    }

    @Test
    void dependencyRejectionPreservesGroupAndRuleTags() {
        Ec2Client client = client();
        String vpc = vpc(client);
        String group = group(client, vpc);
        String rule = rule(client, group);
        tag(client, List.of(group, rule));
        String subnet = client.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.231.0.0/24"))
                .subnet().subnetId();
        cleanup.register(() -> client.deleteSubnet(request -> request.subnetId(subnet)));
        String networkInterface = client.createNetworkInterface(request -> request.subnetId(subnet).groups(group))
                .networkInterface().networkInterfaceId();
        cleanup.register(() -> client.deleteNetworkInterface(request -> request.networkInterfaceId(networkInterface)));

        Ec2Exception error = assertThrows(Ec2Exception.class,
                () -> client.deleteSecurityGroup(request -> request.groupId(group)));

        assertEquals("DependencyViolation", error.awsErrorDetails().errorCode());
        assertEquals(2, tagCount(client, List.of(group, rule)));
        assertEquals(1, client.describeSecurityGroups(request -> request.groupIds(group)).securityGroups().size());
    }

    private Ec2Client client() {
        Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_WEST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("555566667789", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        cleanup.register(client::close);
        return client;
    }

    private String vpc(Ec2Client client) {
        String vpc = client.createVpc(request -> request.cidrBlock("10.231.0.0/16")).vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        return vpc;
    }

    private String group(Ec2Client client, String vpc) {
        String name = "tag-deletion-" + UUID.randomUUID();
        cleanup.register(() -> client.describeSecurityGroups(request -> request.filters(
                Filter.builder().name("vpc-id").values(vpc).build(),
                Filter.builder().name("group-name").values(name).build())).securityGroups()
                .forEach(group -> client.deleteSecurityGroup(request -> request.groupId(group.groupId()))));
        return client.createSecurityGroup(request -> request.vpcId(vpc).groupName(name)
                .description("owned tag cleanup fixture")).groupId();
    }

    private static String rule(Ec2Client client, String group) {
        return client.describeSecurityGroupRules(request -> request.filters(
                Filter.builder().name("group-id").values(group).build()))
                .securityGroupRules().getFirst().securityGroupRuleId();
    }

    private static void tag(Ec2Client client, List<String> resources) {
        client.createTags(request -> request.resources(resources)
                .tags(Tag.builder().key("Owner").value("tag-deletion-fixture").build()));
    }

    private static int tagCount(Ec2Client client, List<String> resources) {
        return client.describeTags(request -> request.filters(
                Filter.builder().name("resource-id").values(resources).build())).tags().size();
    }
}
