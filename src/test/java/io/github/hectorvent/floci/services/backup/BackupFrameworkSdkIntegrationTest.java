package io.github.hectorvent.floci.services.backup;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.backup.BackupClient;
import software.amazon.awssdk.services.backup.model.AlreadyExistsException;
import software.amazon.awssdk.services.backup.model.ControlInputParameter;
import software.amazon.awssdk.services.backup.model.ControlScope;
import software.amazon.awssdk.services.backup.model.CreateFrameworkRequest;
import software.amazon.awssdk.services.backup.model.DescribeFrameworkResponse;
import software.amazon.awssdk.services.backup.model.FrameworkControl;
import software.amazon.awssdk.services.backup.model.InvalidParameterValueException;
import software.amazon.awssdk.services.backup.model.ListFrameworksResponse;
import software.amazon.awssdk.services.backup.model.ResourceNotFoundException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BackupFrameworkSdkIntegrationTest {

    private static final String ACCOUNT = "741677901025";

    @Test
    void nativeDefinitionLifecycleRetainsControlsTagsAndIdentityWithoutClaimingRecordingCoverage() {
        String name = name();
        try (BackupClient client = client(ACCOUNT, Region.EU_WEST_2)) {
            CreateFrameworkRequest create = CreateFrameworkRequest.builder().frameworkName(name)
                    .frameworkDescription("initial").frameworkControls(controls("RDS", 35))
                    .idempotencyToken(name).frameworkTags(Map.of("owner", name)).build();
            String arn = client.createFramework(create).frameworkArn();
            try {
                DescribeFrameworkResponse original = client.describeFramework(r -> r.frameworkName(name));
                assertTrue(arn.startsWith("arn:aws:backup:eu-west-2:" + ACCOUNT + ":framework:" + name + "-"));
                assertEquals("UNAVAILABLE", original.frameworkStatus());
                assertEquals(create.frameworkControls(), original.frameworkControls());
                assertEquals(arn, client.createFramework(create).frameworkArn());
                assertEquals(original.creationTime(), client.describeFramework(r -> r.frameworkName(name)).creationTime());
                assertEquals(Map.of("owner", name), client.listTags(r -> r.resourceArn(arn)).tags());
                assertThrows(AlreadyExistsException.class, () -> client.createFramework(create.toBuilder()
                        .frameworkName(name + "_duplicate").idempotencyToken(name + "_duplicate").build()));
                client.updateFramework(r -> r.frameworkName(name).frameworkDescription("changed")
                        .frameworkControls(controls("DynamoDB", 60)).idempotencyToken(name + "_update"));
                client.updateFramework(r -> r.frameworkName(name).frameworkDescription("description only")
                        .idempotencyToken(name + "_description"));
                DescribeFrameworkResponse updated = client.describeFramework(r -> r.frameworkName(name));
                assertEquals("description only", updated.frameworkDescription());
                assertEquals(controls("DynamoDB", 60), updated.frameworkControls());
                assertEquals(original.creationTime(), updated.creationTime());
                assertEquals(arn, updated.frameworkArn());
                assertEquals("UNAVAILABLE", updated.frameworkStatus());
                assertEquals(2, client.listFrameworks(r -> r.maxResults(100)).frameworks().stream()
                        .filter(framework -> framework.frameworkName().equals(name)).findFirst().orElseThrow()
                        .numberOfControls());
                client.tagResource(r -> r.resourceArn(arn).tags(Map.of("owner", "updated", "remove", "yes")));
                client.untagResource(r -> r.resourceArn(arn).tagKeyList("remove"));
                assertEquals(Map.of("owner", "updated"), client.listTags(r -> r.resourceArn(arn)).tags());
            } finally {
                client.deleteFramework(r -> r.frameworkName(name));
            }
            assertThrows(ResourceNotFoundException.class, () -> client.describeFramework(r -> r.frameworkName(name)));
            assertThrows(ResourceNotFoundException.class, () -> client.listTags(r -> r.resourceArn(arn)));
        }
    }

    @Test
    void nativeSameNameResourcesAndArnTagLookupsRespectAccountAndRegion() {
        String name = name();
        try (BackupClient east = client(ACCOUNT, Region.US_EAST_1);
             BackupClient west = client(ACCOUNT, Region.US_WEST_2);
             BackupClient foreign = client("741677901026", Region.US_EAST_1)) {
            String eastArn = east.createFramework(r -> r.frameworkName(name).frameworkControls(controls("RDS", 35))).frameworkArn();
            try {
                assertThrows(ResourceNotFoundException.class, () -> west.describeFramework(r -> r.frameworkName(name)));
                assertThrows(ResourceNotFoundException.class, () -> foreign.describeFramework(r -> r.frameworkName(name)));
                String westArn = west.createFramework(r -> r.frameworkName(name).frameworkControls(controls("RDS", 35))).frameworkArn();
                try {
                    String foreignArn = foreign.createFramework(r -> r.frameworkName(name)
                            .frameworkControls(controls("RDS", 35))).frameworkArn();
                    try {
                        assertNotEquals(eastArn, westArn);
                        assertNotEquals(eastArn, foreignArn);
                        assertThrows(ResourceNotFoundException.class, () -> west.tagResource(r ->
                                r.resourceArn(eastArn).tags(Map.of("foreign", "refused"))));
                        assertThrows(ResourceNotFoundException.class, () -> foreign.listTags(r -> r.resourceArn(eastArn)));
                        assertTrue(east.listTags(r -> r.resourceArn(eastArn)).tags().isEmpty());
                    } finally {
                        foreign.deleteFramework(r -> r.frameworkName(name));
                    }
                } finally {
                    west.deleteFramework(r -> r.frameworkName(name));
                }
            } finally {
                east.deleteFramework(r -> r.frameworkName(name));
            }
        }
    }

    @Test
    void nativePaginationTokensAreRegionBoundAndSummariesArePopulated() {
        String first = name();
        String second = name();
        try (BackupClient client = client(ACCOUNT, Region.AP_SOUTHEAST_2);
             BackupClient foreignRegion = client(ACCOUNT, Region.US_WEST_2)) {
            client.createFramework(r -> r.frameworkName(first).frameworkControls(controls("RDS", 35)));
            try {
                client.createFramework(r -> r.frameworkName(second).frameworkControls(controls("RDS", 60)));
                try {
                    ListFrameworksResponse page = client.listFrameworks(r -> r.maxResults(1));
                    assertEquals(1, page.frameworks().size());
                    assertFalse(page.nextToken().isBlank());
                    assertThrows(InvalidParameterValueException.class, () -> foreignRegion.listFrameworks(r ->
                            r.maxResults(1).nextToken(page.nextToken())));
                    ListFrameworksResponse last = client.listFrameworks(r -> r.maxResults(1).nextToken(page.nextToken()));
                    Set<String> names = new HashSet<>();
                    names.add(page.frameworks().getFirst().frameworkName());
                    names.add(last.frameworks().getFirst().frameworkName());
                    assertEquals(Set.of(first, second), names);
                    assertEquals(2, last.frameworks().getFirst().numberOfControls());
                    assertThrows(InvalidParameterValueException.class, () -> client.listFrameworks(r -> r.maxResults(0)));
                } finally {
                    client.deleteFramework(r -> r.frameworkName(second));
                }
            } finally {
                client.deleteFramework(r -> r.frameworkName(first));
            }
        }
    }

    private static List<FrameworkControl> controls(String resourceType, int retention) {
        return List.of(FrameworkControl.builder().controlName("BACKUP_RESOURCES_PROTECTED_BY_BACKUP_PLAN")
                        .controlInputParameters(List.of())
                        .controlScope(ControlScope.builder().complianceResourceTypes(resourceType).build()).build(),
                FrameworkControl.builder().controlName("BACKUP_RECOVERY_POINT_MINIMUM_RETENTION_CHECK")
                        .controlInputParameters(ControlInputParameter.builder().parameterName("requiredRetentionDays")
                                .parameterValue(Integer.toString(retention)).build())
                        .controlScope(ControlScope.builder().build()).build());
    }

    private static BackupClient client(String account, Region region) {
        return AwsTestClients.create(BackupClient.builder(), account, region);
    }

    private static String name() {
        return "Framework_" + UUID.randomUUID().toString().replace("-", "");
    }
}
