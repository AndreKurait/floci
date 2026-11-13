package io.github.hectorvent.floci.services.account;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.TestAccounts;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.account.AccountClient;
import software.amazon.awssdk.services.account.model.AccountException;
import software.amazon.awssdk.services.account.model.ListRegionsResponse;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Organization;
import software.amazon.awssdk.services.organizations.model.OrganizationsException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real Organizations membership and Account IAM evaluation in fresh local numeric-account scopes. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@Timeout(90)
class AccountRegionOrganizationIamIntegrationTest {
    private static final Logger LOG = Logger.getLogger(AccountRegionOrganizationIamIntegrationTest.class);
    private static final String TARGET = "ap-southeast-7";
    private static final String PRINCIPAL = "account.amazonaws.com";
    @TestHTTPResource("/")
    URI endpoint;
    private final List<IamUserFixture> users = new ArrayList<>();
    private final Map<String, AccountClient> accounts = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> baselines = new LinkedHashMap<>();
    private String management;
    private String delegate;
    private String target;
    private OrganizationsClient organizations;
    private Organization ownedOrganization;
    private boolean organizationAttempted;

    @BeforeEach
    void createAnOwnedOrganizationAndInviteOnlyFreshFixtureAccounts() {
        management = TestAccounts.randomId();
        delegate = TestAccounts.randomId();
        target = TestAccounts.randomId();
        assertEquals(3, Set.of(management, delegate, target).size());
        organizations = AwsTestClients.create(OrganizationsClient.builder(), management, Region.US_EAST_1, endpoint);
        OrganizationsException absent = assertThrows(OrganizationsException.class, organizations::describeOrganization);
        assertEquals("AWSOrganizationsNotInUseException", absent.awsErrorDetails().errorCode());
        organizationAttempted = true;
        ownedOrganization = organizations.createOrganization(r -> r.featureSet("ALL")).organization();
        assertEquals(management, ownedOrganization.masterAccountId());
        assertNotNull(ownedOrganization.id());
        for (String member : List.of(delegate, target)) {
            String handshake = organizations.inviteAccountToOrganization(r -> r
                    .target(t -> t.id(member).type("ACCOUNT"))).handshake().id();
            try (OrganizationsClient invitee = AwsTestClients.create(OrganizationsClient.builder(),
                    member, Region.US_EAST_1, endpoint)) {
                assertEquals("ACCEPTED", invitee.acceptHandshake(r -> r.handshakeId(handshake)).handshake().stateAsString());
            }
        }
        organizations.enableAWSServiceAccess(r -> r.servicePrincipal(PRINCIPAL));
        for (String owner : List.of(management, delegate, target)) {
            AccountClient client = AwsTestClients.create(AccountClient.builder(), owner, Region.US_EAST_1, endpoint);
            accounts.put(owner, client);
            Map<String, String> baseline = projection(client);
            assertEquals(34, baseline.size());
            assertEquals("DISABLED", baseline.get(TARGET));
            baselines.put(owner, baseline);
        }
        LOG.infov("ACCOUNT_REGION_ORG_RESERVED org={0} management={1} members={2} baselines={3}",
                ownedOrganization.id(), management, List.of(delegate, target), baselines);
    }

    @AfterEach
    void restoreOwnedRegionViewsThenRemoveOnlyTheExactOrganizationAndIamFixtures() throws Exception {
        List<Throwable> failures = new ArrayList<>();
        try {
            for (Map.Entry<String, Map<String, String>> entry : baselines.entrySet()) {
                try {
                    AccountClient client = accounts.get(entry.getKey());
                    String state = settle(client);
                    if ("ENABLED".equals(state)) {
                        Map<String, String> enabled = new LinkedHashMap<>(entry.getValue());
                        enabled.put(TARGET, "ENABLED");
                        assertEquals(enabled, projection(client));
                        LOG.infov("ACCOUNT_REGION_ORG_RESTORE_INTENT account={0} target={1}", entry.getKey(), TARGET);
                        assertEquals(200, client.disableRegion(r -> r.regionName(TARGET)).sdkHttpResponse().statusCode());
                        assertEquals("DISABLED", settle(client));
                    } else {
                        assertEquals("DISABLED", state);
                    }
                    assertEquals(entry.getValue(), projection(client));
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
            for (IamUserFixture user : users.reversed()) {
                try {
                    user.close();
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
            if (organizationAttempted) {
                try {
                    Organization current = organizations.describeOrganization().organization();
                    assertNotNull(ownedOrganization, "Unknown create acceptance remains visible for manual review");
                    assertTrue(ownedOrganization.equalsBySdkFields(current));
                    Set<String> actual = organizations.listAccounts().accounts().stream()
                            .map(account -> account.id()).collect(Collectors.toSet());
                    assertTrue(Set.of(management, delegate, target).containsAll(actual));
                    for (String member : List.of(delegate, target)) {
                        if (actual.contains(member)) {
                            organizations.removeAccountFromOrganization(r -> r.accountId(member));
                        }
                    }
                    organizations.deleteOrganization();
                    assertEquals("AWSOrganizationsNotInUseException",
                            assertThrows(OrganizationsException.class, organizations::describeOrganization)
                                    .awsErrorDetails().errorCode());
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
        } finally {
            accounts.values().forEach(AccountClient::close);
            if (organizations != null) {
                organizations.close();
            }
        }
        assertAll("Every owned Account and Organizations fixture is accounted for",
                failures.stream().map(failure -> () -> { throw failure; }));
    }

    @Test
    void anExactManagementOrganizationMemberArnAuthorizesTheTargetsWholeRegionView() {
        IamUserFixture user = user(management, memberArn(target));
        try (AccountClient caller = user.accountClient()) {
            assertEquals(34, caller.listRegions(r -> r.accountId(target)).regions().size());
            assertEquals("DISABLED", caller.getRegionOptStatus(r -> r.accountId(target).regionName(TARGET))
                    .regionOptStatusAsString());
            assertEquals(200, caller.enableRegion(r -> r.accountId(target).regionName(TARGET))
                    .sdkHttpResponse().statusCode());
            assertEquals("ENABLED", settle(accounts.get(target)));
            assertEquals(baselines.get(management), projection(accounts.get(management)));
            assertEquals(baselines.get(delegate), projection(accounts.get(delegate)));
            denied(() -> caller.getRegionOptStatus(r -> r.accountId(delegate).regionName(TARGET)));
            denied(() -> caller.disableRegion(r -> r.accountId(delegate).regionName(TARGET)));
            assertEquals(200, caller.disableRegion(r -> r.accountId(target).regionName(TARGET))
                    .sdkHttpResponse().statusCode());
        }
    }

    @Test
    void aGrantWithAnotherOrganizationManagementAccountOrPartitionCannotAdmitTheTarget() {
        for (String arn : List.of(
                memberArn(target).replace(ownedOrganization.id(), "o-abcdefghij"),
                memberArn(target).replace(management, "999988887777"),
                memberArn(target).replace("arn:aws:", "arn:aws-cn:"))) {
            IamUserFixture user = user(management, arn);
            try (AccountClient caller = user.accountClient()) {
                denied(() -> caller.listRegions(r -> r.accountId(target)));
                denied(() -> caller.getRegionOptStatus(r -> r.accountId(target).regionName(TARGET)));
                denied(() -> caller.enableRegion(r -> r.accountId(target).regionName(TARGET)));
                denied(() -> caller.disableRegion(r -> r.accountId(target).regionName(TARGET)));
            }
            assertEquals(baselines.get(target), projection(accounts.get(target)));
        }
    }

    @Test
    void trustedAccessAndDelegatedAdministratorChecksStillApplyAfterTheIamResourceGrant() {
        IamUserFixture user = user(delegate, memberArn(target));
        IamUserFixture manager = user(management, memberArn(target));
        try (AccountClient caller = user.accountClient();
             AccountClient managementCaller = manager.accountClient()) {
            denied(() -> caller.getRegionOptStatus(r -> r.accountId(target).regionName(TARGET)));
            denied(() -> caller.enableRegion(r -> r.accountId(target).regionName(TARGET)));
            organizations.registerDelegatedAdministrator(r -> r.accountId(delegate).servicePrincipal(PRINCIPAL));
            assertEquals("DISABLED", caller.getRegionOptStatus(r -> r.accountId(target).regionName(TARGET))
                    .regionOptStatusAsString());
            assertEquals(200, caller.enableRegion(r -> r.accountId(target).regionName(TARGET))
                    .sdkHttpResponse().statusCode());
            assertEquals("ENABLED", settle(accounts.get(target)));
            organizations.deregisterDelegatedAdministrator(r -> r.accountId(delegate).servicePrincipal(PRINCIPAL));
            organizations.disableAWSServiceAccess(r -> r.servicePrincipal(PRINCIPAL));
            denied(() -> caller.disableRegion(r -> r.accountId(target).regionName(TARGET)));
            denied(() -> managementCaller.disableRegion(r -> r.accountId(target).regionName(TARGET)));
            assertEquals("ENABLED", settle(accounts.get(target)));
            organizations.enableAWSServiceAccess(r -> r.servicePrincipal(PRINCIPAL));
            organizations.registerDelegatedAdministrator(r -> r.accountId(delegate).servicePrincipal(PRINCIPAL));
            assertEquals("ENABLED", caller.getRegionOptStatus(r -> r.accountId(target).regionName(TARGET))
                    .regionOptStatusAsString());
            organizations.deregisterDelegatedAdministrator(r -> r.accountId(delegate).servicePrincipal(PRINCIPAL));
            denied(() -> caller.disableRegion(r -> r.accountId(target).regionName(TARGET)));
        }
    }

    @Test
    void managementSelfAccountIdAndTargetsOutsideTheOrganizationRemainRejected() {
        IamUserFixture user = user(management, "*");
        try (AccountClient caller = user.accountClient()) {
            AccountException own = assertThrows(AccountException.class,
                    () -> caller.listRegions(r -> r.accountId(management)));
            assertEquals(400, own.statusCode());
            assertEquals("ValidationException", own.awsErrorDetails().errorCode());
            denied(() -> caller.getRegionOptStatus(r -> r.accountId("999988887777").regionName(TARGET)));
            denied(() -> caller.enableRegion(r -> r.accountId("999988887777").regionName(TARGET)));
            assertEquals(baselines.get(target), projection(accounts.get(target)));
        }
    }

    private String memberArn(String member) {
        return "arn:aws:account::" + management + ":account/" + ownedOrganization.id() + "/" + member;
    }

    private IamUserFixture user(String owner, String resource) {
        IamClient iam = AwsTestClients.create(IamClient.builder(), owner, Region.US_EAST_1, endpoint);
        String name = "account-regions-" + UUID.randomUUID();
        iam.createUser(r -> r.userName(name));
        IamUserFixture fixture = new IamUserFixture(iam, name);
        users.add(fixture);
        fixture.key = iam.createAccessKey(r -> r.userName(name)).accessKey();
        iam.putUserPolicy(r -> r.userName(name).policyName("regions").policyDocument(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"account:*\","
                        + "\"Resource\":\"" + resource + "\"}]}"));
        fixture.policy = true;
        return fixture;
    }

    private Map<String, String> projection(AccountClient client) {
        ListRegionsResponse response = client.listRegions(r -> r.maxResults(50));
        assertNull(response.nextToken());
        Map<String, String> values = new LinkedHashMap<>();
        response.regions().forEach(region -> assertNull(values.put(region.regionName(), region.regionOptStatusAsString())));
        return Map.copyOf(values);
    }

    private String settle(AccountClient client) {
        for (int i = 0; i < 100; i++) {
            String state = client.getRegionOptStatus(r -> r.regionName(TARGET)).regionOptStatusAsString();
            if (!Set.of("ENABLING", "DISABLING").contains(state)) {
                return state;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError("Owned Region did not settle");
    }

    private static void denied(Runnable call) {
        AccountException failure = assertThrows(AccountException.class, call::run);
        assertEquals(403, failure.statusCode());
        assertTrue(failure.awsErrorDetails().errorCode().contains("AccessDenied"));
    }

    private final class IamUserFixture implements AutoCloseable {
        private final IamClient iam;
        private final String name;
        private AccessKey key;
        private boolean policy;

        private IamUserFixture(IamClient iam, String name) {
            this.iam = iam;
            this.name = name;
        }

        private AccountClient accountClient() {
            return AwsTestClients.create(AccountClient.builder(), key.accessKeyId(), key.secretAccessKey(),
                    Region.US_EAST_1, endpoint);
        }

        @Override
        public void close() {
            try {
                assertEquals(name, iam.getUser(r -> r.userName(name)).user().userName());
                if (policy) {
                    iam.deleteUserPolicy(r -> r.userName(name).policyName("regions"));
                }
                if (key != null) {
                    assertTrue(iam.listAccessKeys(r -> r.userName(name)).accessKeyMetadata().stream()
                            .anyMatch(value -> key.accessKeyId().equals(value.accessKeyId())));
                    iam.deleteAccessKey(r -> r.userName(name).accessKeyId(key.accessKeyId()));
                }
                iam.deleteUser(r -> r.userName(name));
            } finally {
                iam.close();
            }
        }
    }
}
