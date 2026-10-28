package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Totp;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.iam.model.VirtualMFADevice;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AssumeRoleMfaIntegrationTest {
    private static final String ACCOUNT = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;
    Supplier<String> adminNames = () -> "assume-mfa-admin-" + UUID.randomUUID();
    @Inject
    MutableClock clock;

    @BeforeEach
    void alignAuthenticationClockWithDeviceCodes() {
        clock.reset();
        clock.advance(Duration.between(clock.instant(), Instant.now()));
    }

    @AfterEach
    void restoreClock() {
        clock.reset();
    }

    @ParameterizedTest
    @CsvSource({
            "trust,true,AccessDenied", "identity,true,AccessDenied", "age,true,AccessDenied",
            "foreign,true,AccessDenied", "omitted-open,true,AccessDenied",
            "omitted-required,false,AccessDenied", "wrong-code,false,AccessDenied",
            "wrong-owner,false,AccessDenied", "unassigned,false,AccessDenied",
            "missing-code,false,AccessDenied", "missing-serial,false,AccessDenied",
            "missing-device,false,AccessDenied", "malformed-code,false,ValidationError",
            "malformed-serial,false,ValidationError", "identity-deny,false,AccessDenied",
            "trust-deny,false,AccessDenied", "revoked,false,AccessDenied"
    })
    void suppliedMfaAuthenticatesTheAssumeRequestOnly(String scenario, boolean allowed, String error)
            throws Exception {
        String identityCondition = switch (scenario) {
            case "identity" -> ",\"Condition\":{\"Bool\":{\"aws:MultiFactorAuthPresent\":\"true\"}}";
            default -> "";
        };
        String identityDeny = "identity-deny".equals(scenario)
                ? ",{\"Effect\":\"Deny\",\"Action\":\"sts:AssumeRole\",\"Resource\":\"*\","
                + "\"Condition\":{\"Bool\":{\"aws:MultiFactorAuthPresent\":\"true\"}}}" : "";
        String identity = "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"sts:AssumeRole\","
                + "\"Resource\":\"*\"" + identityCondition + "}" + identityDeny + "]}";
        String roleAccount = "foreign".equals(scenario) ? "111111111111" : ACCOUNT;
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "assume-mfa-", "assume", identity);
             SignedIamUsers.User other = SignedIamUsers.createUser(endpoint, "assume-mfa-other-", "assume", identity);
             IamClient admin = admin(ACCOUNT); IamClient roleAdmin = admin(roleAccount);
             StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                             user.accessKeyId(), user.secretAccessKey()))).build()) {
            String condition = switch (scenario) {
                case "trust", "foreign", "omitted-required" ->
                        ",\"Condition\":{\"Bool\":{\"aws:MultiFactorAuthPresent\":\"true\"}}";
                case "age" -> ",\"Condition\":{\"NumericEquals\":{\"aws:MultiFactorAuthAge\":\"0\"}}";
                default -> "";
            };
            String trustDeny = "trust-deny".equals(scenario)
                    ? ",{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"" + ACCOUNT + "\"},"
                    + "\"Action\":\"sts:AssumeRole\",\"Condition\":{\"Bool\":{"
                    + "\"aws:MultiFactorAuthPresent\":\"true\"}}}" : "";
            String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"AWS\":\"" + ACCOUNT + "\"},\"Action\":\"sts:AssumeRole\""
                    + condition + "}" + trustDeny + "]}";
            Role role = roleAdmin.createRole(b -> b.roleName("mfa-" + UUID.randomUUID()).path("/teams/mfa/")
                    .assumeRolePolicyDocument(trust)).role();
            VirtualMFADevice device = null;
            boolean enabled = false;
            String owner = "wrong-owner".equals(scenario) ? other.name() : user.name();
            AtomicReference<Credentials> issued = new AtomicReference<>();
            try {
                roleAdmin.putRolePolicy(b -> b.roleName(role.roleName()).policyName("read")
                        .policyDocument("{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"ssm:DescribeParameters\","
                                + "\"Resource\":\"*\",\"Condition\":{\"Null\":{"
                                + "\"aws:MultiFactorAuthPresent\":\"true\",\"aws:MultiFactorAuthAge\":\"true\"}}}]}"));
                device = admin.createVirtualMFADevice(b -> b.virtualMFADeviceName("assume-" + UUID.randomUUID()))
                        .virtualMFADevice();
                String serial = device.serialNumber();
                String seed = device.base32StringSeed().asUtf8String();
                long step = Totp.stepAt(Instant.now());
                if (!"unassigned".equals(scenario)) {
                    admin.enableMFADevice(b -> b.userName(owner).serialNumber(serial)
                            .authenticationCode1(Totp.codeAt(seed, step - 1))
                            .authenticationCode2(Totp.codeAt(seed, step)));
                    enabled = true;
                }
                if ("revoked".equals(scenario)) {
                    admin.deactivateMFADevice(b -> b.userName(owner).serialNumber(serial));
                    enabled = false;
                }
                AssumeRoleRequest.Builder request = AssumeRoleRequest.builder().roleArn(role.arn())
                        .roleSessionName("verified-request");
                if (!scenario.startsWith("omitted")) {
                    if (!"missing-serial".equals(scenario)) {
                        request.serialNumber(switch (scenario) {
                            case "malformed-serial" -> "!";
                            case "missing-device" -> serial + "-missing";
                            default -> serial;
                        });
                    }
                    if (!"missing-code".equals(scenario)) {
                        request.tokenCode(switch (scenario) {
                            case "malformed-code" -> "not-a-code";
                            case "wrong-code" -> invalidCode(seed);
                            default -> Totp.codeAt(seed, Totp.stepAt(Instant.now()));
                        });
                    }
                }
                if (allowed) {
                    if (!scenario.startsWith("omitted")) {
                        assertTrue(RequestScopes.callAs(ACCOUNT, () -> Totp.matchesCode(
                                iam.getVirtualMfaDevice(serial).getBase32Seed(), request.build().tokenCode(),
                                1, Instant.now())), "Fixture code must match the stored device");
                    }
                    issued.set(assertDoesNotThrow(() -> sts.assumeRole(request.build())).credentials());
                    assertNotNull(issued.get());
                    try (SsmClient ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                            .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                                    issued.get().accessKeyId(), issued.get().secretAccessKey(),
                                    issued.get().sessionToken()))).build()) {
                        assertNotNull(ssm.describeParameters());
                    }
                } else {
                    assertEquals(error, assertThrows(StsException.class,
                            () -> issued.set(sts.assumeRole(request.build()).credentials()))
                            .awsErrorDetails().errorCode());
                }
            } finally {
                if (issued.get() != null) {
                    iam.unregisterSession(ACCOUNT, issued.get().accessKeyId());
                    iam.unregisterSession(roleAccount, issued.get().accessKeyId());
                    assertTrue(iam.resolveCallerArns(issued.get().accessKeyId()).isEmpty());
                }
                if (device != null) {
                    String serial = device.serialNumber();
                    if (enabled) {
                        admin.deactivateMFADevice(b -> b.userName(owner).serialNumber(serial));
                    }
                    admin.deleteVirtualMFADevice(b -> b.serialNumber(serial));
                    assertTrue(admin.listVirtualMFADevices().virtualMFADevices().stream()
                            .noneMatch(remaining -> remaining.serialNumber().equals(serial)));
                }
                roleAdmin.deleteRolePolicy(b -> b.roleName(role.roleName()).policyName("read"));
                roleAdmin.deleteRole(b -> b.roleName(role.roleName()));
            }
        }
    }

    private static String invalidCode(String seed) {
        for (int candidate = 0; candidate < 100; candidate++) {
            String code = String.format("%06d", candidate);
            if (!Totp.matchesCode(seed, code, 10, Instant.now())) {
                return code;
            }
        }
        throw new AssertionError("No invalid TOTP candidate");
    }

    @Test
    void rejectedAdminCreationPreservesPreexistingForeignUser() {
        String account = "111122223333";
        String existing = "mfa-existing-admin-" + UUID.randomUUID();
        RequestScopes.runAs(account, () -> iam.createUser(existing, "/"));
        cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUser(existing)));
        AssumeRoleMfaIntegrationTest fixture = nestedFixture();
        fixture.adminNames = () -> existing;

        assertThrows(AwsException.class, () -> fixture.admin(account));
        fixture.cleanup.afterEach(mock(ExtensionContext.class));

        assertDoesNotThrow(() -> RequestScopes.callAs(account, () -> iam.getUser(existing)),
                "Rejected creation must not transfer cleanup ownership of an existing user");
    }

    @Test
    void successfulAdminCreationRemovesOwnedUserAndDependents() {
        String account = "111122223333";
        String name = "mfa-owned-admin-" + UUID.randomUUID();
        AssumeRoleMfaIntegrationTest fixture = nestedFixture();
        fixture.adminNames = () -> name;
        try (IamClient client = fixture.admin(account)) {
            assertEquals(name, client.getUser(b -> b.userName(name)).user().userName());
        } finally {
            fixture.cleanup.afterEach(mock(ExtensionContext.class));
        }
        assertThrows(AwsException.class, () -> RequestScopes.callAs(account, () -> iam.getUser(name)));
    }

    private AssumeRoleMfaIntegrationTest nestedFixture() {
        AssumeRoleMfaIntegrationTest fixture = new AssumeRoleMfaIntegrationTest();
        fixture.iam = iam;
        fixture.endpoint = endpoint;
        return fixture;
    }

    private IamClient admin(String account) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create("test", "test");
        if (!ACCOUNT.equals(account)) {
            String name = adminNames.get();
            RequestScopes.runAs(account, () -> iam.createUser(name, "/"));
            cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUser(name)));
            AccessKey key = RequestScopes.callAs(account, () -> iam.createAccessKey(name));
            cleanup.register(() -> RequestScopes.runAs(account,
                    () -> iam.deleteAccessKey(name, key.getAccessKeyId())));
            RequestScopes.runAs(account, () -> iam.putUserPolicy(name, "admin",
                    "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"iam:*\",\"Resource\":\"*\"}]}"));
            cleanup.register(() -> RequestScopes.runAs(account, () -> iam.deleteUserPolicy(name, "admin")));
            credentials = AwsBasicCredentials.create(key.getAccessKeyId(), key.getSecretAccessKey());
        }
        return IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
    }
}
