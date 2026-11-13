package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.AssociationDescription;
import software.amazon.awssdk.services.ssm.model.AssociationFilter;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.Target;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmAssociationUpdateSdkIntegrationTest {

    private static final String ACCOUNT = "840011110001";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void replacementDocumentIsReturnedByUpdateDescribeAndList(String region) {
        try (Scenario scenario = new Scenario(region, 1, 1)) {
            AssociationDescription before = scenario.describe();
            AssociationDescription updated = scenario.client.updateAssociation(request -> request
                    .associationId(scenario.id).name(scenario.replacement).documentVersion("1"))
                    .associationDescription();

            assertEquals(scenario.replacement, updated.name());
            assertEquals("2", updated.associationVersion());
            assertEquals(before.associationId(), updated.associationId());
            assertEquals(before.date(), updated.date());
            assertEquals("Pending", updated.status().nameAsString());
            assertNull(updated.lastExecutionDate());
            assertEquals(updated, scenario.describe());
            assertTrue(scenario.client.listAssociations(request -> request.associationFilterList(
                    AssociationFilter.builder().key("Name").value(scenario.original).build()))
                    .associations().stream().noneMatch(item -> scenario.id.equals(item.associationId())));
            assertTrue(scenario.client.listAssociations(request -> request.associationFilterList(
                    AssociationFilter.builder().key("Name").value(scenario.replacement).build()))
                    .associations().stream().anyMatch(item -> scenario.id.equals(item.associationId())));
        }
    }

    @Test
    void requestedVersionIsValidatedAgainstTheReplacementDocument() {
        try (Scenario scenario = new Scenario("us-east-1", 1, 2)) {
            AssociationDescription updated = assertDoesNotThrow(() -> scenario.client.updateAssociation(request -> request
                    .associationId(scenario.id).name(scenario.replacement).documentVersion("2"))
                    .associationDescription());
            assertEquals(scenario.replacement, updated.name());
            assertEquals("2", updated.documentVersion());
            assertEquals(updated, scenario.describe());
        }
    }

    @Test
    void missingReplacementDocumentLeavesTheAssociationUnchanged() {
        try (Scenario scenario = new Scenario("us-east-1", 1, 1)) {
            AssociationDescription before = scenario.describe();
            SsmException failure = assertThrows(SsmException.class, () -> scenario.client.updateAssociation(request -> request
                    .associationId(scenario.id).name("missing-" + UUID.randomUUID())
                    .associationName("must-not-be-written").documentVersion("$DEFAULT")
                    .scheduleExpression("rate(6 hours)")));
            assertEquals("InvalidDocument", failure.awsErrorDetails().errorCode());
            assertEquals(before, scenario.describe());
        }
    }

    @Test
    void versionFromThePreviousDocumentCannotValidateAReplacement() {
        try (Scenario scenario = new Scenario("us-east-1", 2, 1)) {
            AssociationDescription before = scenario.describe();
            SsmException failure = assertThrows(SsmException.class, () -> scenario.client.updateAssociation(request -> request
                    .associationId(scenario.id).name(scenario.replacement).documentVersion("2")
                    .associationName("must-not-be-written")));
            assertEquals("InvalidDocumentVersion", failure.awsErrorDetails().errorCode());
            assertEquals(before, scenario.describe());
        }
    }

    @Test
    void replacementWithoutAVersionUsesItsOwnDefault() {
        try (Scenario scenario = new Scenario("us-east-1", 2, 1)) {
            AssociationDescription updated = scenario.client.updateAssociation(request -> request
                    .associationId(scenario.id).name(scenario.replacement)).associationDescription();
            assertEquals(scenario.replacement, updated.name());
            assertEquals("$DEFAULT", updated.documentVersion());
            assertEquals(updated, scenario.describe());
        }
    }

    @Test
    void staleAssociationVersionCannotOverwriteTheLastUpdate() {
        try (Scenario scenario = new Scenario("us-east-1", 1, 1)) {
            AssociationDescription winner = scenario.client.updateAssociation(request -> request
                    .associationId(scenario.id).associationVersion("1")
                    .scheduleExpression("rate(6 hours)")).associationDescription();
            SsmException failure = assertThrows(SsmException.class, () -> scenario.client.updateAssociation(request -> request
                    .associationId(scenario.id).associationVersion("1")
                    .name(scenario.replacement).scheduleExpression("rate(1 hour)")));
            assertEquals("InvalidAssociationVersion", failure.awsErrorDetails().errorCode());
            assertEquals(winner, scenario.describe());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "$LATEST"})
    void currentAssociationVersionAndLatestAllowAnUpdate(String version) {
        try (Scenario scenario = new Scenario("us-east-1", 1, 1)) {
            AssociationDescription updated = scenario.client.updateAssociation(request -> request
                    .associationId(scenario.id).associationVersion(version)
                    .scheduleExpression("rate(6 hours)")).associationDescription();
            assertEquals("2", updated.associationVersion());
            assertEquals("rate(6 hours)", updated.scheduleExpression());
        }
    }

    @Test
    void concurrentUpdatesWithTheSameVersionHaveOneWinner() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1", 1, 1);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> updates = List.of("rate(1 hour)", "rate(2 hours)").stream()
                    .map(schedule -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        try {
                            return scenario.client.updateAssociation(request -> request.associationId(scenario.id)
                                    .associationVersion("1").scheduleExpression(schedule))
                                    .associationDescription().scheduleExpression();
                        } catch (SsmException failure) {
                            return failure.awsErrorDetails().errorCode();
                        }
                    })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<String> results = List.of(updates.get(0).get(20, TimeUnit.SECONDS),
                    updates.get(1).get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("InvalidAssociationVersion"::equals).count());
            String winner = results.stream().filter(value -> value.startsWith("rate(")).findFirst().orElseThrow();
            assertEquals(winner, scenario.describe().scheduleExpression());
            assertEquals("2", scenario.describe().associationVersion());
        }
    }

    @Test
    void updatesStayWithinTheOwningAccountAndRegion() {
        try (Scenario scenario = new Scenario("us-east-1", 1, 1);
             SsmClient otherAccount = AwsTestClients.create(SsmClient.builder(), "840011110002", Region.US_EAST_1);
             SsmClient otherRegion = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.EU_WEST_2)) {
            AssociationDescription before = scenario.describe();
            for (SsmClient foreign : List.of(otherAccount, otherRegion)) {
                SsmException failure = assertThrows(SsmException.class, () -> foreign.updateAssociation(request -> request
                        .associationId(scenario.id).name(scenario.replacement)));
                assertEquals("AssociationDoesNotExist", failure.awsErrorDetails().errorCode());
            }
            assertEquals(before, scenario.describe());
        }
    }

    @Test
    void anOmittedNameStillUsesTheCurrentDocument() {
        try (Scenario scenario = new Scenario("us-east-1", 2, 1)) {
            AssociationDescription updated = scenario.client.updateAssociation(request -> request
                    .associationId(scenario.id).documentVersion("1")).associationDescription();
            assertEquals(scenario.original, updated.name());
            assertEquals("1", updated.documentVersion());
            assertEquals(updated, scenario.describe());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updatesReplaceOptionalConfiguration(boolean includeOptionalConfiguration) {
        try (Scenario scenario = new Scenario("us-east-1", 1, 1)) {
            AssociationDescription before = scenario.describe();
            AssociationDescription updated = scenario.client.updateAssociation(request -> {
                request.associationId(scenario.id).name(before.name()).documentVersion(before.documentVersion())
                        .targets(before.targets());
                if (includeOptionalConfiguration) {
                    request.associationName(before.associationName()).parameters(before.parameters())
                            .scheduleExpression(before.scheduleExpression()).maxErrors(before.maxErrors())
                            .maxConcurrency(before.maxConcurrency()).complianceSeverity(before.complianceSeverity());
                }
            }).associationDescription();
            assertEquals(before.name(), updated.name());
            assertEquals(before.targets(), updated.targets());
            if (includeOptionalConfiguration) {
                assertAll(
                        () -> assertEquals(before.associationName(), updated.associationName()),
                        () -> assertEquals(before.parameters(), updated.parameters()),
                        () -> assertEquals(before.scheduleExpression(), updated.scheduleExpression()),
                        () -> assertEquals(before.maxErrors(), updated.maxErrors()),
                        () -> assertEquals(before.maxConcurrency(), updated.maxConcurrency()),
                        () -> assertEquals(before.complianceSeverity(), updated.complianceSeverity()));
            } else {
                assertAll(
                        () -> assertNull(updated.associationName()),
                        () -> assertTrue(updated.parameters().isEmpty()),
                        () -> assertNull(updated.scheduleExpression()),
                        () -> assertNull(updated.maxErrors()),
                        () -> assertNull(updated.maxConcurrency()),
                        () -> assertNull(updated.complianceSeverity()));
            }
            assertEquals(updated, scenario.describe());
        }
    }

    private static final class Scenario implements AutoCloseable {
        private final SsmClient client;
        private final String original = "association-original-" + UUID.randomUUID();
        private final String replacement = "association-replacement-" + UUID.randomUUID();
        private final String id;

        private Scenario(String region, int originalVersions, int replacementVersions) {
            client = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.of(region));
            createDocument(original, originalVersions);
            createDocument(replacement, replacementVersions);
            id = client.createAssociation(request -> request.name(original).documentVersion(Integer.toString(originalVersions))
                    .associationName(original).targets(Target.builder().key("tag:Configuration").values(original).build())
                    .parameters(Map.of("commands", List.of("printf configuration")))
                    .maxErrors("5%").maxConcurrency("10%").complianceSeverity("HIGH")
                    .scheduleExpression("rate(12 hours)")).associationDescription().associationId();
        }

        private void createDocument(String name, int versions) {
            for (int version = 1; version <= versions; version++) {
                String content = """
                        {"schemaVersion":"2.2","description":"version %s",
                         "parameters":{"commands":{"type":"StringList"}},
                         "mainSteps":[{"action":"aws:runShellScript","name":"configure",
                                      "inputs":{"runCommand":"{{ commands }}"}}]}
                        """.formatted(version);
                if (version == 1) {
                    client.createDocument(request -> request.name(name).documentType("Command").content(content));
                } else {
                    client.updateDocument(request -> request.name(name).documentVersion("$LATEST").content(content));
                }
            }
        }

        private AssociationDescription describe() {
            return client.describeAssociation(request -> request.associationId(id)).associationDescription();
        }

        @Override
        public void close() {
            try {
                assertAll("association and document cleanup",
                        () -> client.deleteAssociation(request -> request.associationId(id)),
                        () -> client.deleteDocument(request -> request.name(original)),
                        () -> client.deleteDocument(request -> request.name(replacement)));
            } finally {
                client.close();
            }
        }
    }
}
