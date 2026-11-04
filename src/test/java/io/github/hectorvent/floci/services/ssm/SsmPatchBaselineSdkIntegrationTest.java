package io.github.hectorvent.floci.services.ssm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.CreatePatchBaselineRequest;
import software.amazon.awssdk.services.ssm.model.DescribePatchBaselinesResponse;
import software.amazon.awssdk.services.ssm.model.DescribePatchGroupsResponse;
import software.amazon.awssdk.services.ssm.model.GetPatchBaselineResponse;
import software.amazon.awssdk.services.ssm.model.PatchFilter;
import software.amazon.awssdk.services.ssm.model.PatchFilterGroup;
import software.amazon.awssdk.services.ssm.model.PatchRule;
import software.amazon.awssdk.services.ssm.model.PatchRuleGroup;
import software.amazon.awssdk.services.ssm.model.PatchSource;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.Tag;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SsmPatchBaselineSdkIntegrationTest {

    private static final String ACCOUNT = "830011110005";
    private static final PatchRuleGroup RULES = PatchRuleGroup.builder()
            .patchRules(PatchRule.builder().approveAfterDays(0).enableNonSecurity(false)
                    .patchFilterGroup(PatchFilterGroup.builder().patchFilters(
                            PatchFilter.builder().key("CLASSIFICATION").values("Security").build()).build())
                    .build()).build();
    private static final PatchSource SOURCE = PatchSource.builder().name("example-repository")
            .products("*").configuration("[example]\nname=Example repository\nbaseurl=file:///repo\nenabled=1\n")
            .build();

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void lifecyclePreservesRulesSourcesAndTimestampTypes(String region) {
        try (SsmClient client = client(region, ACCOUNT)) {
            String name = name();
            Instant before = Instant.now().minusSeconds(1);
            String id = client.createPatchBaseline(request -> request.name(name)
                    .operatingSystem("AMAZON_LINUX_2").description("initial")
                    .approvalRules(RULES).approvedPatches("kernel*", "bootloader*")
                    .approvedPatchesEnableNonSecurity(true).sources(SOURCE)
                    .tags(Tag.builder().key("owner").value("initial").build())).baselineId();
            try {
                assertTrue(id.matches("pb-[0-9a-f]{17}"), id);
                GetPatchBaselineResponse initial = client.getPatchBaseline(request -> request.baselineId(id));
                assertEquals(name, initial.name());
                assertEquals("AMAZON_LINUX_2", initial.operatingSystemAsString());
                assertEquals(List.of("kernel*", "bootloader*"), initial.approvedPatches());
                assertEquals(List.of(SOURCE), initial.sources());
                assertEquals("CLASSIFICATION",
                        initial.approvalRules().patchRules().getFirst().patchFilterGroup()
                                .patchFilters().getFirst().keyAsString());
                assertEquals(0, initial.approvalRules().patchRules().getFirst().approveAfterDays());
                assertEquals("UNSPECIFIED", initial.approvedPatchesComplianceLevelAsString());
                assertEquals("ALLOW_AS_DEPENDENCY", initial.rejectedPatchesActionAsString());
                assertNotNull(initial.createdDate());
                assertFalse(initial.createdDate().isBefore(before));
                assertFalse(initial.modifiedDate().isBefore(initial.createdDate()));
                client.updatePatchBaseline(request -> request.baselineId(id).description("updated"));
                GetPatchBaselineResponse updated = client.getPatchBaseline(request -> request.baselineId(id));
                assertEquals("updated", updated.description());
                assertEquals(initial.createdDate(), updated.createdDate());
                assertEquals(initial.approvalRules(), updated.approvalRules());
                assertEquals(initial.sources(), updated.sources());
                assertEquals(initial.approvedPatches(), updated.approvedPatches());
                client.addTagsToResource(request -> request.resourceType("PatchBaseline").resourceId(id)
                        .tags(Tag.builder().key("owner").value("updated").build(),
                                Tag.builder().key("stage").value("test").build()));
                assertEquals(Set.of(Tag.builder().key("owner").value("updated").build(),
                                Tag.builder().key("stage").value("test").build()),
                        new HashSet<>(client.listTagsForResource(request -> request
                                .resourceType("PatchBaseline").resourceId(id)).tagList()));
                client.removeTagsFromResource(request -> request.resourceType("PatchBaseline")
                        .resourceId(id).tagKeys("stage"));
                assertEquals(List.of(Tag.builder().key("owner").value("updated").build()),
                        client.listTagsForResource(request -> request.resourceType("PatchBaseline")
                                .resourceId(id)).tagList());
            } finally {
                client.deletePatchBaseline(request -> request.baselineId(id));
            }
            assertError("DoesNotExistException", () -> client.getPatchBaseline(request -> request.baselineId(id)));
            assertEquals(id, client.deletePatchBaseline(request -> request.baselineId(id)).baselineId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void replaceClearsOptionalConfigurationAndKeepsOperatingSystem(String region) {
        try (SsmClient client = client(region, ACCOUNT)) {
            String name = name();
            String id = client.createPatchBaseline(request -> request.name(name).operatingSystem("AMAZON_LINUX_2")
                    .description("discard").approvalRules(RULES).approvedPatches("kernel*")
                    .approvedPatchesEnableNonSecurity(true).sources(SOURCE)).baselineId();
            try {
                assertError("ValidationException", () -> client.updatePatchBaseline(
                        request -> request.baselineId(id).replace(true)));
                assertEquals("discard", client.getPatchBaseline(request -> request.baselineId(id)).description());
                client.updatePatchBaseline(request -> request.baselineId(id).name(name).replace(true));
                GetPatchBaselineResponse replaced = client.getPatchBaseline(request -> request.baselineId(id));
                assertEquals("AMAZON_LINUX_2", replaced.operatingSystemAsString());
                assertTrue(replaced.approvedPatches().isEmpty());
                assertTrue(replaced.sources().isEmpty());
                assertFalse(replaced.approvedPatchesEnableNonSecurity());
                assertTrue(replaced.description() == null || replaced.description().isEmpty());
            } finally {
                client.deletePatchBaseline(request -> request.baselineId(id));
            }
        }
    }

    @Test
    void concurrentIdempotentCreatesHaveOneIdentity() throws Exception {
        try (SsmClient client = client("us-east-1", ACCOUNT);
             ExecutorService executor = Executors.newFixedThreadPool(6)) {
            CreatePatchBaselineRequest request = CreatePatchBaselineRequest.builder().name(name())
                    .clientToken(UUID.randomUUID().toString()).description("same").build();
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(() -> client.createPatchBaseline(request).baselineId());
            }
            Set<String> ids = new HashSet<>();
            for (Future<String> future : executor.invokeAll(calls)) {
                ids.add(future.get());
            }
            try {
                assertEquals(1, ids.size());
                assertError("IdempotentParameterMismatch", () -> client.createPatchBaseline(
                        request.toBuilder().description("different").build()));
            } finally {
                for (String id : ids) {
                    client.deletePatchBaseline(delete -> delete.baselineId(id));
                }
            }
        }
    }

    @Test
    void patchGroupRegistrationControlsSelectionAndPreventsDeletion() {
        try (SsmClient client = client("us-east-1", ACCOUNT)) {
            String first = client.createPatchBaseline(request -> request.name(name())
                    .operatingSystem("AMAZON_LINUX_2")).baselineId();
            String second = client.createPatchBaseline(request -> request.name(name())
                    .operatingSystem("AMAZON_LINUX_2")).baselineId();
            String group = "group-" + UUID.randomUUID();
            try {
                client.registerPatchBaselineForPatchGroup(request -> request.baselineId(first).patchGroup(group));
                assertEquals(first, client.getPatchBaselineForPatchGroup(request -> request
                        .patchGroup(group).operatingSystem("AMAZON_LINUX_2")).baselineId());
                assertEquals(List.of(group),
                        client.getPatchBaseline(request -> request.baselineId(first)).patchGroups());
                assertError("ResourceInUseException", () -> client.deletePatchBaseline(
                        request -> request.baselineId(first)));
                assertError("AlreadyExistsException", () -> client.registerPatchBaselineForPatchGroup(
                        request -> request.baselineId(second).patchGroup(group)));
                client.deregisterPatchBaselineForPatchGroup(request -> request.baselineId(first).patchGroup(group));
                assertTrue(client.getPatchBaseline(request -> request.baselineId(first)).patchGroups().isEmpty());
                assertEquals(client.getDefaultPatchBaseline(request -> request.operatingSystem("AMAZON_LINUX_2"))
                                .baselineId(),
                        client.getPatchBaselineForPatchGroup(request -> request.patchGroup(group)
                                .operatingSystem("AMAZON_LINUX_2")).baselineId());
                client.registerPatchBaselineForPatchGroup(request -> request.baselineId(second).patchGroup(group));
            } finally {
                client.deregisterPatchBaselineForPatchGroup(request -> request.baselineId(first).patchGroup(group));
                client.deregisterPatchBaselineForPatchGroup(request -> request.baselineId(second).patchGroup(group));
                client.deletePatchBaseline(request -> request.baselineId(first));
                client.deletePatchBaseline(request -> request.baselineId(second));
            }
        }
    }

    @Test
    void defaultsAndCustomerBaselinesAreScopedByAccountAndRegion() {
        try (SsmClient owner = client("us-east-1", ACCOUNT);
             SsmClient otherAccount = client("us-east-1", "830011110006");
             SsmClient otherRegion = client("us-gov-west-1", ACCOUNT)) {
            String id = owner.createPatchBaseline(request -> request.name(name())
                    .operatingSystem("AMAZON_LINUX_2")).baselineId();
            try {
                assertError("DoesNotExistException", () -> otherAccount.getPatchBaseline(
                        request -> request.baselineId(id)));
                assertError("DoesNotExistException", () -> otherRegion.getPatchBaseline(
                        request -> request.baselineId(id)));
                owner.registerDefaultPatchBaseline(request -> request.baselineId(id));
                assertEquals(id, owner.getDefaultPatchBaseline(request -> request
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
                assertNotEquals(id, otherAccount.getDefaultPatchBaseline(request -> request
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
                assertNotEquals(id, otherRegion.getDefaultPatchBaseline(request -> request
                        .operatingSystem("AMAZON_LINUX_2")).baselineId());
            } finally {
                owner.deletePatchBaseline(request -> request.baselineId(id));
            }
            assertNotEquals(id, owner.getDefaultPatchBaseline(request -> request
                    .operatingSystem("AMAZON_LINUX_2")).baselineId());
        }
    }

    @Test
    void describePaginatesCustomerBaselinesWithOwnerAndNameFilters() {
        try (SsmClient client = client("us-east-1", ACCOUNT)) {
            String prefix = name();
            Set<String> ids = new HashSet<>();
            try {
                for (int i = 0; i < 3; i++) {
                    String name = prefix + "-" + i;
                    ids.add(client.createPatchBaseline(request -> request.name(name)).baselineId());
                }
                Set<String> seen = new HashSet<>();
                String token = null;
                do {
                    String next = token;
                    DescribePatchBaselinesResponse page = client.describePatchBaselines(request -> request
                            .maxResults(1).nextToken(next).filters(
                                    filter -> filter.key("OWNER").values("Self"),
                                    filter -> filter.key("NAME_PREFIX").values(prefix)));
                    assertEquals(1, page.baselineIdentities().size());
                    assertTrue(seen.add(page.baselineIdentities().getFirst().baselineId()));
                    token = page.nextToken();
                } while (token != null);
                assertEquals(ids, seen);
                assertTrue(client.describePatchBaselines(request -> request.filters(
                        filter -> filter.key("OWNER").values("AWS"),
                        filter -> filter.key("NAME_PREFIX").values(prefix))).baselineIdentities().isEmpty());
            } finally {
                for (String id : ids) {
                    client.deletePatchBaseline(request -> request.baselineId(id));
                }
            }
        }
    }

    @Test
    void invalidRulesAndSourcesDoNotChangeTheExistingBaseline() {
        try (SsmClient client = client("us-east-1", ACCOUNT)) {
            String id = client.createPatchBaseline(request -> request.name(name())
                    .operatingSystem("AMAZON_LINUX_2").approvalRules(RULES).sources(SOURCE)).baselineId();
            try {
                GetPatchBaselineResponse before = client.getPatchBaseline(request -> request.baselineId(id));
                PatchRule original = RULES.patchRules().getFirst();
                for (PatchRule invalid : List.of(original.toBuilder().approveAfterDays(-1).build(),
                        original.toBuilder().approveUntilDate("2026-11-04").build(),
                        original.toBuilder().approveAfterDays((Integer) null).build(),
                        original.toBuilder().approveAfterDays((Integer) null)
                                .approveUntilDate("2026-11-31").build())) {
                    assertError("ValidationException", () -> client.updatePatchBaseline(request -> request
                            .baselineId(id).approvalRules(group -> group.patchRules(invalid))));
                    assertTrue(before.equalsBySdkFields(client.getPatchBaseline(request -> request.baselineId(id))));
                }
                assertError("ValidationException", () -> client.updatePatchBaseline(request -> request
                        .baselineId(id).sources(SOURCE.toBuilder().configuration("").build())));
                assertTrue(before.equalsBySdkFields(client.getPatchBaseline(request -> request.baselineId(id))));
                assertError("ValidationException", () -> client.describePatchBaselines(request -> request.maxResults(0)));
            } finally {
                client.deletePatchBaseline(request -> request.baselineId(id));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBIAN", "UBUNTU"})
    void aptBasedSystemsAcceptRulesWithoutDateBasedApproval(String os) {
        PatchRule rule = PatchRule.builder().patchFilterGroup(group -> group.patchFilters(
                filter -> filter.key("PRIORITY").values("Required"))).build();
        try (SsmClient client = client("us-east-1", ACCOUNT)) {
            String id = client.createPatchBaseline(request -> request.name(name()).operatingSystem(os)
                    .approvalRules(group -> group.patchRules(rule))).baselineId();
            try {
                GetPatchBaselineResponse before = client.getPatchBaseline(request -> request.baselineId(id));
                assertEquals(rule, before.approvalRules().patchRules().getFirst());
                for (PatchRule invalid : List.of(rule.toBuilder().approveAfterDays(0).build(),
                        rule.toBuilder().approveUntilDate("2026-11-04").build())) {
                    assertError("ValidationException", () -> client.updatePatchBaseline(request -> request
                            .baselineId(id).approvalRules(group -> group.patchRules(invalid))));
                    assertTrue(before.equalsBySdkFields(client.getPatchBaseline(request -> request.baselineId(id))));
                }
                client.updatePatchBaseline(request -> request.baselineId(id).replace(true).name(before.name())
                        .approvalRules(group -> group.patchRules(rule.toBuilder().enableNonSecurity(true).build())));
                assertTrue(client.getPatchBaseline(request -> request.baselineId(id))
                        .approvalRules().patchRules().getFirst().enableNonSecurity());
            } finally {
                client.deletePatchBaseline(request -> request.baselineId(id));
            }
        }
    }

    @Test
    void groupPaginationUsesNamePrefixesAndScopedCursors() {
        try (SsmClient client = client("us-east-1", ACCOUNT);
             SsmClient other = client("us-east-1", "830011110006")) {
            String prefix = "group-" + UUID.randomUUID();
            String id = client.createPatchBaseline(request -> request.name(name())
                    .operatingSystem("AMAZON_LINUX_2")).baselineId();
            List<String> groups = List.of(prefix + "-one", prefix + "-two", prefix + "-three");
            try {
                for (String group : groups) {
                    client.registerPatchBaselineForPatchGroup(request -> request.baselineId(id).patchGroup(group));
                }
                DescribePatchGroupsResponse first = client.describePatchGroups(request -> request.maxResults(1)
                        .filters(filter -> filter.key("NAME_PREFIX").values(prefix + "*")));
                assertEquals(1, first.mappings().size());
                assertNotNull(first.nextToken());
                assertEquals(id, first.mappings().getFirst().baselineIdentity().baselineId());
                assertError("InvalidNextToken", () -> other.describePatchGroups(request -> request
                        .maxResults(1).nextToken(first.nextToken())
                        .filters(filter -> filter.key("NAME_PREFIX").values(prefix + "*"))));
                Set<String> seen = new HashSet<>();
                String token = null;
                do {
                    String next = token;
                    DescribePatchGroupsResponse page = client.describePatchGroups(request -> request.maxResults(1)
                            .nextToken(next).filters(filter -> filter.key("NAME_PREFIX").values(prefix + "*")));
                    assertEquals(1, page.mappings().size());
                    assertTrue(seen.add(page.mappings().getFirst().patchGroup()));
                    token = page.nextToken();
                } while (token != null);
                assertEquals(new HashSet<>(groups), seen);
            } finally {
                for (String group : groups) {
                    client.deregisterPatchBaselineForPatchGroup(request -> request.baselineId(id).patchGroup(group));
                }
                client.deletePatchBaseline(request -> request.baselineId(id));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void customerArnsResolveOnlyWithinTheirAccountRegionAndPartition(String region) {
        try (SsmClient client = client(region, ACCOUNT);
             SsmClient other = client(region, "830011110006")) {
            String id = client.createPatchBaseline(request -> request.name(name())).baselineId();
            String partition = region.equals("us-east-1") ? "aws" : "aws-us-gov";
            String arn = "arn:" + partition + ":ssm:" + region + ":" + ACCOUNT + ":patchbaseline/" + id;
            try {
                assertEquals(id, client.getPatchBaseline(request -> request.baselineId(arn)).baselineId());
                assertError("DoesNotExistException",
                        () -> other.getPatchBaseline(request -> request.baselineId(arn)));
                assertError("DoesNotExistException", () -> client.getPatchBaseline(request -> request
                        .baselineId(arn.replace(":ssm:", ":ec2:"))));
                String wrongPartition = partition.equals("aws") ? "aws-us-gov" : "aws";
                assertError("DoesNotExistException", () -> client.getPatchBaseline(request -> request
                        .baselineId(arn.replace("arn:" + partition + ":", "arn:" + wrongPartition + ":"))));
            } finally {
                client.deletePatchBaseline(request -> request.baselineId(id));
            }
        }
    }

    private static void assertError(String code, Runnable operation) {
        SsmException error = assertThrows(SsmException.class, operation::run);
        assertEquals(code, error.awsErrorDetails().errorCode(), error.getMessage());
    }

    private static String name() {
        return "baseline-" + UUID.randomUUID();
    }

    private static SsmClient client(String region, String account) {
        return SsmClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region)).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
