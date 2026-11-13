package io.floci.aws.services.ssm;

import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ListResourceDataSyncResponse;
import software.amazon.awssdk.services.ssm.model.ResourceDataSyncItem;
import software.amazon.awssdk.services.ssm.model.ResourceDataSyncSource;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmSourceSyncIamIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(873033340000L);
    static final String SOURCE = "SyncFromSource";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void namedGrantsAuthorizeExactlyOneSyncAndDeniedMutationsPreserveCompleteRows(String region) {
        verifyExactGrants(region, region.startsWith("us-gov-") ? "aws-us-gov" : "aws");
    }

    static void verifyExactGrants(String region, String partition) {
        String replacement = "aws-us-gov".equals(partition) ? "us-gov-east-1" : "us-west-2";
        try (Scenario scenario = new Scenario(region, partition)) {
            scenario.grant("""
                    {"Effect":"Allow","Action":["ssm:CreateResourceDataSync","ssm:UpdateResourceDataSync",
                      "ssm:DeleteResourceDataSync"],"Resource":"%s"},
                    {"Effect":"Allow","Action":"ssm:ListResourceDataSync","Resource":"*"}"""
                    .formatted(scenario.arn("allowed")));
            scenario.create(scenario.user, "allowed");
            scenario.create(scenario.root, "sibling");
            Map<String, ResourceDataSyncItem> before = scenario.snapshot();
            assertDenied(() -> scenario.user.createResourceDataSync(request -> request.syncName("denied")
                    .syncType(SOURCE).syncSource(source(region))));
            assertEquals(before, scenario.snapshot());
            assertDenied(() -> scenario.user.updateResourceDataSync(request -> request.syncName("sibling")
                    .syncType(SOURCE).syncSource(source(replacement))));
            assertEquals(before, scenario.snapshot());
            assertDenied(() -> scenario.user.deleteResourceDataSync(request -> request.syncName("sibling").syncType(SOURCE)));
            assertEquals(before, scenario.snapshot());
            assertEquals(before, scenario.snapshot(scenario.user));
            scenario.user.updateResourceDataSync(request -> request.syncName("allowed")
                    .syncType(SOURCE).syncSource(source(replacement)));
            Map<String, ResourceDataSyncItem> after = scenario.snapshot();
            assertEquals(List.of(replacement), after.get("allowed").syncSource().sourceRegions());
            assertEquals(before.get("allowed").syncCreatedTime(), after.get("allowed").syncCreatedTime());
            assertEquals(before.get("sibling"), after.get("sibling"));
            scenario.user.deleteResourceDataSync(request -> request.syncName("allowed").syncType(SOURCE));
            scenario.created.remove("allowed");
            assertEquals(Map.of("sibling", before.get("sibling")), scenario.snapshot());
        }
    }

    @Test
    void listingRequiresWildcardResourceAndSourceConditionsMatchTheReturnedCollection() {
        try (Scenario scenario = new Scenario("us-east-1", "aws")) {
            scenario.create(scenario.root, "source");
            Map<String, ResourceDataSyncItem> before = scenario.snapshot();
            scenario.grant("""
                    {"Effect":"Allow","Action":"ssm:ListResourceDataSync","Resource":"%s"}"""
                    .formatted(scenario.arn("source")));
            assertDenied(() -> scenario.snapshot(scenario.user));
            assertEquals(before, scenario.snapshot());
            scenario.grant("""
                    {"Effect":"Allow","Action":"ssm:ListResourceDataSync","Resource":"*",
                      "Condition":{"StringEquals":{"ssm:SyncType":"SyncFromSource"}}}""");
            assertEquals(before, scenario.snapshot(scenario.user));
            assertDenied(() -> scenario.user.listResourceDataSync());
            assertDenied(() -> scenario.user.listResourceDataSync(request -> request.syncType("SyncToDestination")));
            scenario.grant("""
                    {"Effect":"Allow","Action":"ssm:ListResourceDataSync","Resource":"*",
                      "Condition":{"StringEquals":{"ssm:SyncType":"SyncToDestination"}}}""");
            assertTrue(scenario.user.listResourceDataSync().resourceDataSyncItems().isEmpty());
            assertDenied(() -> scenario.snapshot(scenario.user));
            assertEquals(before, scenario.snapshot());
        }
    }

    @Test
    void syncTypeConditionsDenyEachSourceMutationWithoutChangingTheCollection() {
        try (Scenario scenario = new Scenario("us-east-1", "aws")) {
            scenario.create(scenario.root, "source");
            scenario.create(scenario.root, "sibling");
            Map<String, ResourceDataSyncItem> before = scenario.snapshot();
            scenario.grant("""
                    {"Effect":"Allow","Action":["ssm:CreateResourceDataSync","ssm:UpdateResourceDataSync",
                      "ssm:DeleteResourceDataSync"],"Resource":"*",
                      "Condition":{"StringEquals":{"ssm:SyncType":"SyncToDestination"}}}""");
            assertDenied(() -> scenario.user.createResourceDataSync(request -> request.syncName("denied")
                    .syncType(SOURCE).syncSource(source(scenario.region))));
            assertEquals(before, scenario.snapshot());
            assertDenied(() -> scenario.user.updateResourceDataSync(request -> request.syncName("source")
                    .syncType(SOURCE).syncSource(source("us-west-2"))));
            assertEquals(before, scenario.snapshot());
            assertDenied(() -> scenario.user.deleteResourceDataSync(request -> request.syncName("source").syncType(SOURCE)));
            assertEquals(before, scenario.snapshot());
            scenario.grant("""
                    {"Effect":"Allow","Action":"ssm:UpdateResourceDataSync","Resource":"%s",
                      "Condition":{"StringEquals":{"ssm:SyncType":"SyncFromSource"}}}""".formatted(scenario.arn("source")));
            scenario.user.updateResourceDataSync(request -> request.syncName("source")
                    .syncType(SOURCE).syncSource(source("us-west-2")));
            assertEquals(before.get("sibling"), scenario.snapshot().get("sibling"));
            assertEquals(List.of("us-west-2"), scenario.snapshot().get("source").syncSource().sourceRegions());
        }
    }

    @Test
    void aSignedStsSessionPolicyCapsTheSameNamedSync() {
        try (Scenario scenario = new Scenario("us-east-1", "aws");
             StsClient sts = StsClient.builder().endpointOverride(scenario.endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(scenario.account, "test")))
                     .httpClient(UrlConnectionHttpClient.create()).build()) {
            scenario.create(scenario.root, "source");
            Map<String, ResourceDataSyncItem> before = scenario.snapshot();
            String role = "source-sync-" + UUID.randomUUID();
            String trust = """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                      "Principal":{"AWS":"arn:aws:iam::%s:root"}}]}""".formatted(scenario.account);
            String arn = scenario.iam.createRole(request -> request.roleName(role)
                    .assumeRolePolicyDocument(trust)).role().arn();
            scenario.roles.add(role);
            scenario.iam.putRolePolicy(request -> request.roleName(role).policyName("source-sync")
                    .policyDocument(policy("""
                            {"Effect":"Allow","Action":["ssm:ListResourceDataSync","ssm:UpdateResourceDataSync"],
                              "Resource":"*"}""")));
            Credentials credentials = sts.assumeRole(request -> request.roleArn(arn).roleSessionName("source-sync")
                    .policy(policy("""
                            {"Effect":"Allow","Action":"ssm:ListResourceDataSync","Resource":"*"},
                            {"Effect":"Allow","Action":"ssm:UpdateResourceDataSync","Resource":"%s"}"""
                            .formatted(scenario.arn("other"))))).credentials();
            try (SsmClient session = scenario.client(AwsSessionCredentials.create(credentials.accessKeyId(),
                    credentials.secretAccessKey(), credentials.sessionToken()))) {
                assertEquals(before, scenario.snapshot(session));
                assertDenied(() -> session.updateResourceDataSync(request -> request.syncName("source")
                        .syncType(SOURCE).syncSource(source("us-west-2"))));
                assertEquals(before, scenario.snapshot());
            }
        }
    }

    @Test
    void anExplicitDenyOverridesTheExactNamedAllowWithoutMutatingAnySource() {
        try (Scenario scenario = new Scenario("us-east-1", "aws")) {
            scenario.create(scenario.root, "source");
            Map<String, ResourceDataSyncItem> before = scenario.snapshot();
            scenario.grant("""
                    {"Effect":"Allow","Action":"ssm:*","Resource":"*"},
                    {"Effect":"Deny","Action":["ssm:CreateResourceDataSync","ssm:UpdateResourceDataSync",
                      "ssm:DeleteResourceDataSync"],"Resource":"%s"}""".formatted(scenario.arn("source")));
            assertDenied(() -> scenario.user.createResourceDataSync(request -> request.syncName("source")
                    .syncType(SOURCE).syncSource(source(scenario.region))));
            assertEquals(before, scenario.snapshot());
            assertDenied(() -> scenario.user.updateResourceDataSync(request -> request.syncName("source")
                    .syncType(SOURCE).syncSource(source("us-west-2"))));
            assertEquals(before, scenario.snapshot());
            assertDenied(() -> scenario.user.deleteResourceDataSync(request -> request.syncName("source").syncType(SOURCE)));
            assertEquals(before, scenario.snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aServiceControlPolicyRestrictsTheNamedSourceForMemberRootAndUser(boolean rootCaller) {
        String management = Long.toString(ACCOUNTS.incrementAndGet());
        try (OwnedCleanup cleanup = new OwnedCleanup()) {
            OrganizationsClient organizations = OrganizationsClient.builder()
                    .endpointOverride(URI.create("http://localhost:" + RestAssured.port)).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(management, "test")))
                    .httpClient(UrlConnectionHttpClient.create()).build();
            cleanup.add(organizations::close);
            organizations.createOrganization(request -> request.featureSet("ALL"));
            cleanup.add(() -> organizations.deleteOrganization());
            String root = organizations.listRoots().roots().getFirst().id();
            organizations.enablePolicyType(request -> request.rootId(root).policyType("SERVICE_CONTROL_POLICY"));
            String member = organizations.createAccount(request -> request.accountName("source-sync-member")
                    .email(UUID.randomUUID() + "@example.test")).createAccountStatus().accountId();
            cleanup.add(() -> organizations.removeAccountFromOrganization(request -> request.accountId(member)));
            Scenario scenario = new Scenario("us-east-1", "aws", member);
            cleanup.add(scenario::close);
            scenario.grant("{\"Effect\":\"Allow\",\"Action\":\"ssm:*\",\"Resource\":\"*\"}");
            scenario.create(scenario.root, "source");
            scenario.create(scenario.root, "sibling");
            Map<String, ResourceDataSyncItem> before = scenario.snapshot();
            String policyId = organizations.createPolicy(request -> request.name("source-sync-ceiling")
                    .type("SERVICE_CONTROL_POLICY").description("Source sync fixture")
                    .content(policy("""
                            {"Effect":"Deny","Action":"ssm:UpdateResourceDataSync","Resource":"%s"}
                            """.formatted(scenario.arn("source"))))).policy().policySummary().id();
            cleanup.add(() -> organizations.deletePolicy(request -> request.policyId(policyId)));
            organizations.attachPolicy(request -> request.policyId(policyId).targetId(member));
            cleanup.add(() -> organizations.detachPolicy(request -> request.policyId(policyId).targetId(member)));
            SsmClient caller = rootCaller ? scenario.root : scenario.user;
            assertEquals(before, scenario.snapshot(caller));
            assertDenied(() -> caller.updateResourceDataSync(request -> request.syncName("source")
                    .syncType(SOURCE).syncSource(source("us-west-2"))));
            assertEquals(before, scenario.snapshot());
            caller.updateResourceDataSync(request -> request.syncName("sibling")
                    .syncType(SOURCE).syncSource(source("us-west-2")));
            assertEquals(before.get("source"), scenario.snapshot().get("source"));
            assertEquals(List.of("us-west-2"), scenario.snapshot().get("sibling").syncSource().sourceRegions());
        }
    }

    private static final class OwnedCleanup implements AutoCloseable {
        private final Deque<Runnable> callbacks = new ArrayDeque<>();

        private void add(Runnable callback) { callbacks.push(callback); }

        @Override
        public void close() {
            List<RuntimeException> failures = new ArrayList<>();
            while (!callbacks.isEmpty()) {
                try {
                    callbacks.pop().run();
                } catch (RuntimeException failure) {
                    failures.add(failure);
                }
            }
            if (!failures.isEmpty()) {
                IllegalStateException failure = new IllegalStateException("Owned organization fixture cleanup failed.");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }
    }

    static void assertDenied(Runnable operation) {
        SsmException failure = assertThrows(SsmException.class, operation::run);
        assertEquals(400, failure.statusCode());
        assertEquals("AccessDeniedException", failure.awsErrorDetails().errorCode());
    }

    static ResourceDataSyncSource source(String region) {
        return ResourceDataSyncSource.builder().sourceType("SingleAccountMultiRegions")
                .sourceRegions(region).includeFutureRegions(false).enableAllOpsDataSources(false).build();
    }

    static String policy(String statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}";
    }

    static final class Scenario implements AutoCloseable {
        final String account;
        final String userName = "source-sync-" + UUID.randomUUID();
        final String region;
        final String partition;
        final URI endpoint = URI.create("http://localhost:" + RestAssured.port);
        final SsmClient root;
        final IamClient iam;
        final Set<String> created = new LinkedHashSet<>();
        final List<String> roles = new ArrayList<>();
        SsmClient user;
        String accessKey;
        boolean userCreated;
        boolean policyCreated;

        Scenario(String region, String partition) {
            this(region, partition, Long.toString(ACCOUNTS.incrementAndGet()));
        }

        Scenario(String region, String partition, String account) {
            this.account = account;
            this.region = region;
            this.partition = partition;
            AwsCredentials rootCredentials = AwsBasicCredentials.create(account, "test");
            root = client(rootCredentials);
            iam = IamClient.builder().endpointOverride(endpoint)
                    .region(Region.of("aws-us-gov".equals(partition) ? "us-gov-west-1" : "us-east-1"))
                    .credentialsProvider(StaticCredentialsProvider.create(rootCredentials))
                    .httpClient(UrlConnectionHttpClient.create()).build();
            try {
                iam.createUser(request -> request.userName(userName));
                userCreated = true;
                AccessKey key =
                        iam.createAccessKey(request -> request.userName(userName)).accessKey();
                accessKey = key.accessKeyId();
                user = client(AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()));
            } catch (RuntimeException failure) {
                try {
                    close();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        SsmClient client(AwsCredentials credentials) {
            return SsmClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .httpClient(UrlConnectionHttpClient.create()).build();
        }

        String arn(String name) {
            return "arn:" + partition + ":ssm:" + region + ":" + account + ":resource-data-sync/" + name;
        }

        void grant(String statements) {
            iam.putUserPolicy(request -> request.userName(userName).policyName("source-sync").policyDocument(policy(statements)));
            policyCreated = true;
        }

        void create(SsmClient caller, String name) {
            caller.createResourceDataSync(request -> request.syncName(name).syncType(SOURCE).syncSource(source(region)));
            created.add(name);
        }

        Map<String, ResourceDataSyncItem> snapshot() {
            return snapshot(root);
        }

        Map<String, ResourceDataSyncItem> snapshot(SsmClient caller) {
            Map<String, ResourceDataSyncItem> rows = new LinkedHashMap<>();
            ListResourceDataSyncResponse response =
                    caller.listResourceDataSync(request -> request.syncType(SOURCE).maxResults(50));
            assertNull(response.nextToken());
            for (ResourceDataSyncItem row : response.resourceDataSyncItems()) {
                assertNull(rows.put(row.syncName(), row));
            }
            return rows;
        }

        @Override
        public void close() {
            List<RuntimeException> failures = new ArrayList<>();
            for (String name : created) {
                clean(failures, () -> root.deleteResourceDataSync(request -> request.syncName(name).syncType(SOURCE)));
            }
            for (String role : roles) {
                clean(failures, () -> iam.deleteRolePolicy(request -> request.roleName(role).policyName("source-sync")));
                clean(failures, () -> iam.deleteRole(request -> request.roleName(role)));
            }
            if (policyCreated) {
                clean(failures, () -> iam.deleteUserPolicy(request -> request.userName(userName).policyName("source-sync")));
            }
            if (accessKey != null) {
                clean(failures, () -> iam.deleteAccessKey(request -> request.userName(userName).accessKeyId(accessKey)));
            }
            if (userCreated) {
                clean(failures, () -> iam.deleteUser(request -> request.userName(userName)));
            }
            if (user != null) {
                user.close();
            }
            root.close();
            iam.close();
            if (!failures.isEmpty()) {
                IllegalStateException failure = new IllegalStateException("Owned SSM IAM fixture cleanup failed.");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }

        private static void clean(List<RuntimeException> failures, Runnable cleanup) {
            try {
                cleanup.run();
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
    }
}
