package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.StackResourceSummary;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class CloudFormationDeletedResourceHistoryIntegrationTest {

    private static final String ACCOUNT = "611111111111";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void deletedResourcesRemainReadableByIdAcrossAllResourceDescribeApis(String regionName) {
        Region region = Region.of(regionName);
        String name = "deleted-resources-" + UUID.randomUUID();
        try (CloudFormationClient cfn = client(ACCOUNT, region);
             SsmClient ssm = AwsTestClients.create(SsmClient.builder(), ACCOUNT, region)) {
            String id = create(cfn, name, "Original");
            StackResourceSummary original = cfn.listStackResources(b -> b.stackName(id))
                    .stackResourceSummaries().getFirst();
            delete(cfn, id, name);

            assertThrows(ParameterNotFoundException.class,
                    () -> ssm.getParameter(b -> b.name(original.physicalResourceId())));
            assertHistory(cfn, id, "Original", original.physicalResourceId());
            assertMissing(cfn, name);
        }
    }

    @Test
    void retainedResourceHistoryReportsSkippedDeletionWhileTheResourceStillExists() {
        String name = "retained-resource-history-" + UUID.randomUUID();
        try (CloudFormationClient cfn = client(ACCOUNT, Region.US_EAST_1);
             SsmClient ssm = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String id = createWithTemplate(cfn, name, """
                    {"Resources":{"Retained":{"Type":"AWS::SSM::Parameter","DeletionPolicy":"Retain",
                    "Properties":{"Type":"String","Value":"native"}}}}
                    """);
            String physical = cfn.listStackResources(b -> b.stackName(id))
                    .stackResourceSummaries().getFirst().physicalResourceId();
            delete(cfn, id, name);
            try {
                assertEquals("native", ssm.getParameter(b -> b.name(physical)).parameter().value());
                assertHistory(cfn, id, "Retained", physical, "DELETE_SKIPPED");
                assertMissing(cfn, name);
            } finally {
                ssm.deleteParameter(b -> b.name(physical));
            }
        }
    }

    @Test
    void deletedResourceHistoryRemainsScopedToItsAccountAndRegion() {
        String name = "scoped-deleted-resources-" + UUID.randomUUID();
        try (CloudFormationClient owner = client(ACCOUNT, Region.US_EAST_1);
             CloudFormationClient otherAccount = client("622222222222", Region.US_EAST_1);
             CloudFormationClient otherRegion = client(ACCOUNT, Region.EU_WEST_1)) {
            String id = create(owner, name, "Original");
            String physical = owner.listStackResources(b -> b.stackName(id))
                    .stackResourceSummaries().getFirst().physicalResourceId();
            delete(owner, id, name);

            assertHistory(owner, id, "Original", physical);
            assertMissing(otherAccount, id);
            assertMissing(otherRegion, id);
        }
    }

    @Test
    void reusedNameKeepsOldResourceHistorySeparateFromItsReplacement() {
        String name = "reused-resource-history-" + UUID.randomUUID();
        try (CloudFormationClient cfn = client(ACCOUNT, Region.US_EAST_1)) {
            String oldId = create(cfn, name, "Original");
            String physical = cfn.listStackResources(b -> b.stackName(oldId))
                    .stackResourceSummaries().getFirst().physicalResourceId();
            delete(cfn, oldId, name);
            String replacementId = create(cfn, name, "Replacement");
            try {
                assertNotEquals(oldId, replacementId);
                assertHistory(cfn, oldId, "Original", physical);
                List<StackResourceSummary> current = cfn.listStackResources(b -> b.stackName(name))
                        .stackResourceSummaries();
                assertEquals(1, current.size());
                assertEquals("Replacement", current.getFirst().logicalResourceId());
                assertEquals("CREATE_COMPLETE", current.getFirst().resourceStatusAsString());
                assertNotEquals(physical, current.getFirst().physicalResourceId());
                assertThrows(CloudFormationException.class, () -> cfn.updateStack(b -> b.stackName(oldId)
                        .templateBody(template("Changed"))));
                assertEquals("Replacement", cfn.listStackResources(b -> b.stackName(replacementId))
                        .stackResourceSummaries().getFirst().logicalResourceId());
            } finally {
                delete(cfn, replacementId, name);
            }
        }
    }

    private static void assertHistory(CloudFormationClient cfn, String id, String logical, String physical) {
        assertHistory(cfn, id, logical, physical, "DELETE_COMPLETE");
    }

    private static void assertHistory(CloudFormationClient cfn, String id, String logical, String physical,
                                      String status) {
        List<StackResourceSummary> listed = cfn.listStackResources(b -> b.stackName(id)).stackResourceSummaries();
        assertEquals(1, listed.size());
        assertEquals(logical, listed.getFirst().logicalResourceId());
        assertEquals(physical, listed.getFirst().physicalResourceId());
        assertEquals(status, listed.getFirst().resourceStatusAsString());
        assertEquals(physical, cfn.describeStackResources(b -> b.stackName(id).logicalResourceId(logical))
                .stackResources().getFirst().physicalResourceId());
        assertEquals(status, cfn.describeStackResource(b -> b.stackName(id).logicalResourceId(logical))
                .stackResourceDetail().resourceStatusAsString());
    }

    private static void assertMissing(CloudFormationClient cfn, String id) {
        assertEquals("ValidationError", assertThrows(CloudFormationException.class,
                () -> cfn.listStackResources(b -> b.stackName(id))).awsErrorDetails().errorCode());
        assertEquals("ValidationError", assertThrows(CloudFormationException.class,
                () -> cfn.describeStackResources(b -> b.stackName(id))).awsErrorDetails().errorCode());
        assertEquals("ValidationError", assertThrows(CloudFormationException.class,
                () -> cfn.describeStackResource(b -> b.stackName(id).logicalResourceId("Original")))
                .awsErrorDetails().errorCode());
    }

    private static String create(CloudFormationClient cfn, String name, String logical) {
        return createWithTemplate(cfn, name, template(logical));
    }

    private static String createWithTemplate(CloudFormationClient cfn, String name, String body) {
        String id = cfn.createStack(b -> b.stackName(name).templateBody(body)).stackId();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("CREATE_COMPLETE",
                cfn.describeStacks(b -> b.stackName(id)).stacks().getFirst().stackStatusAsString()));
        return id;
    }

    private static void delete(CloudFormationClient cfn, String id, String name) {
        cfn.deleteStack(b -> b.stackName(id));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertEquals("DELETE_COMPLETE",
                    cfn.describeStacks(b -> b.stackName(id)).stacks().getFirst().stackStatusAsString());
            assertThrows(CloudFormationException.class, () -> cfn.describeStacks(b -> b.stackName(name)));
        });
    }

    private static String template(String logical) {
        return "{\"Resources\":{\"" + logical + "\":{\"Type\":\"AWS::SSM::Parameter\","
                + "\"Properties\":{\"Type\":\"String\",\"Value\":\"native\"}}}}";
    }

    private static CloudFormationClient client(String account, Region region) {
        return AwsTestClients.create(CloudFormationClient.builder(), account, region);
    }
}
