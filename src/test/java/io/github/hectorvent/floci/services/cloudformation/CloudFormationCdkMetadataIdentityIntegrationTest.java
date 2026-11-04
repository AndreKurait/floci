package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationCdkMetadataIdentityIntegrationTest {

    @Test
    void analyticsChangesPreserveIdentityThroughUpdateAndChangeSet() throws Exception {
        String name = "cdk-metadata-identity-" + UUID.randomUUID();
        try (CloudFormationClient cfn = AwsTestClients.create(
                CloudFormationClient.builder(), "000000000000", Region.EU_WEST_1)) {
            try {
                cfn.createStack(request -> request.stackName(name).templateBody(template("initial")));
                awaitStatus(cfn, name, "CREATE_COMPLETE");
                String original = physicalId(cfn, name);
                assertTrue(original.startsWith("cdk-metadata-"));

                cfn.updateStack(request -> request.stackName(name).templateBody(template("updated")));
                awaitStatus(cfn, name, "UPDATE_COMPLETE");
                assertEquals(original, physicalId(cfn, name));
                assertAnalytics(cfn, name, "updated");

                cfn.createChangeSet(request -> request.stackName(name).changeSetName("analytics")
                        .changeSetType("UPDATE").templateBody(template("change-set")));
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        assertEquals("CREATE_COMPLETE", cfn.describeChangeSet(request -> request
                                .stackName(name).changeSetName("analytics")).statusAsString()));
                cfn.executeChangeSet(request -> request.stackName(name).changeSetName("analytics"));
                awaitStatus(cfn, name, "UPDATE_COMPLETE");
                assertEquals(original, physicalId(cfn, name));
                assertAnalytics(cfn, name, "change-set");
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    CloudFormationException absent = assertThrows(CloudFormationException.class,
                            () -> cfn.describeStacks(request -> request.stackName(name)));
                    assertEquals("ValidationError", absent.awsErrorDetails().errorCode());
                    assertTrue(absent.getMessage().contains("does not exist"));
                });
            }
        }
    }

    private static String physicalId(CloudFormationClient cfn, String name) {
        return cfn.describeStackResource(request -> request.stackName(name)
                .logicalResourceId("CDKMetadata")).stackResourceDetail().physicalResourceId();
    }

    private static void assertAnalytics(CloudFormationClient cfn, String name, String expected) throws Exception {
        String body = cfn.getTemplate(request -> request.stackName(name).templateStage("Original")).templateBody();
        assertEquals(expected, new ObjectMapper().readTree(body)
                .at("/Resources/CDKMetadata/Properties/Analytics").asText());
    }

    private static void awaitStatus(CloudFormationClient cfn, String name, String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(expected, cfn.describeStacks(request -> request.stackName(name))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private static String template(String analytics) {
        return """
                {"Resources":{"CDKMetadata":{"Type":"AWS::CDK::Metadata","Properties":{"Analytics":"%s"}}}}
                """.formatted(analytics);
    }
}
