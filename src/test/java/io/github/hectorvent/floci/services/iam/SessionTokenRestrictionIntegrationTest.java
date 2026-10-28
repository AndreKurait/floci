package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.Totp;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.VirtualMFADevice;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SessionTokenRestrictionIntegrationTest {
    private static final String ALLOW_ALL = "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";

    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;

    @ParameterizedTest
    @CsvSource({"false,read", "false,write", "false,sts", "true,read", "true,write", "true,sts"})
    void sessionTokenRestrictsIamAndStsButAllowsOtherServices(boolean mfa, String operation) throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "session-restriction-", "all", ALLOW_ALL);
             IamClient admin = iam(AwsBasicCredentials.create("test", "test"));
             StsClient issuer = sts(AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            VirtualMFADevice device = null;
            boolean enabled = false;
            Credentials credentials = null;
            String childName = "session-child-" + UUID.randomUUID();
            try {
                if (mfa) {
                    device = admin.createVirtualMFADevice(b -> b.virtualMFADeviceName("restriction-" + UUID.randomUUID()))
                            .virtualMFADevice();
                    String serial = device.serialNumber();
                    String seed = device.base32StringSeed().asUtf8String();
                    long step = Totp.stepAt(Instant.now());
                    admin.enableMFADevice(b -> b.userName(user.name()).serialNumber(serial)
                            .authenticationCode1(Totp.codeAt(seed, step - 1)).authenticationCode2(Totp.codeAt(seed, step)));
                    enabled = true;
                    credentials = issuer.getSessionToken(b -> b.serialNumber(serial)
                            .tokenCode(Totp.codeAt(seed, Totp.stepAt(Instant.now())))).credentials();
                } else {
                    credentials = issuer.getSessionToken().credentials();
                }
                AwsSessionCredentials session = session(credentials);
                try (IamClient client = iam(session); StsClient sts = sts(session);
                     SsmClient ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                             .credentialsProvider(StaticCredentialsProvider.create(session)).build()) {
                    if ("read".equals(operation)) {
                        if (mfa) {
                            assertEquals(user.name(), client.getUser(b -> b.userName(user.name())).user().userName());
                            admin.putUserPolicy(b -> b.userName(user.name()).policyName("all")
                                    .policyDocument("{\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"iam:GetUser\",\"Resource\":\"*\"}]}"));
                            assertEquals("AccessDenied", assertThrows(IamException.class,
                                    () -> client.getUser(b -> b.userName(user.name()))).awsErrorDetails().errorCode());
                            admin.putUserPolicy(b -> b.userName(user.name()).policyName("all").policyDocument(ALLOW_ALL));
                        } else {
                            assertEquals("AccessDenied", assertThrows(IamException.class,
                                    () -> client.getUser(b -> b.userName(user.name()))).awsErrorDetails().errorCode());
                        }
                    } else if ("write".equals(operation)) {
                        if (mfa) {
                            assertEquals(childName, client.createUser(b -> b.userName(childName)).user().userName());
                        } else {
                            assertEquals("AccessDenied", assertThrows(IamException.class,
                                    () -> client.createUser(b -> b.userName(childName))).awsErrorDetails().errorCode());
                            assertEquals("NoSuchEntity", assertThrows(IamException.class,
                                    () -> admin.getUser(b -> b.userName(childName))).awsErrorDetails().errorCode());
                        }
                    } else {
                        assertEquals("AccessDenied", assertThrows(StsException.class,
                                () -> sts.getAccessKeyInfo(b -> b.accessKeyId(user.accessKeyId())))
                                .awsErrorDetails().errorCode());
                    }
                    assertEquals("000000000000", sts.getCallerIdentity().account());
                    assertNotNull(ssm.describeParameters());
                    assertEquals("AccessDenied", assertThrows(StsException.class,
                            () -> sts.getSessionToken()).awsErrorDetails().errorCode());
                }
            } finally {
                if (admin.listUsers().users().stream().anyMatch(u -> childName.equals(u.userName()))) {
                    admin.deleteUser(b -> b.userName(childName));
                }
                if (credentials != null) {
                    iam.unregisterSession("000000000000", credentials.accessKeyId());
                    assertTrue(iam.resolveCallerArns(credentials.accessKeyId()).isEmpty());
                }
                if (device != null) {
                    String serial = device.serialNumber();
                    if (enabled) {
                        admin.deactivateMFADevice(b -> b.userName(user.name()).serialNumber(serial));
                    }
                    admin.deleteVirtualMFADevice(b -> b.serialNumber(serial));
                    assertTrue(admin.listVirtualMFADevices().virtualMFADevices().stream()
                            .noneMatch(d -> serial.equals(d.serialNumber())));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sessionTokenMayAssumeRoleAndRoleCredentialsDoNotInheritItsApiRestrictions(boolean temporary) throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "session-assume-", "all", ALLOW_ALL);
             IamClient admin = iam(AwsBasicCredentials.create("test", "test"));
             StsClient issuer = sts(AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            String roleName = "session-target-" + UUID.randomUUID();
            String principal = admin.getUser(b -> b.userName(user.name())).user().arn();
            String trust = "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + principal
                    + "\"},\"Action\":\"sts:AssumeRole\"}]}";
            String roleArn = admin.createRole(b -> b.roleName(roleName).path("/bounded/").assumeRolePolicyDocument(trust))
                    .role().arn();
            Credentials initial = null;
            Credentials assumed = null;
            try {
                admin.putRolePolicy(b -> b.roleName(roleName).policyName("all").policyDocument(ALLOW_ALL));
                initial = temporary ? issuer.getSessionToken().credentials() : null;
                AwsCredentials caller = initial == null ? AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey())
                        : session(initial);
                try (StsClient client = sts(caller)) {
                    assumed = client.assumeRole(b -> b.roleArn(roleArn).roleSessionName("session-restriction")).credentials();
                }
                try (IamClient client = iam(session(assumed)); StsClient sts = sts(session(assumed))) {
                    assertEquals(user.name(), client.getUser(b -> b.userName(user.name())).user().userName());
                    assertEquals("000000000000", sts.getAccessKeyInfo(b -> b.accessKeyId(user.accessKeyId())).account());
                    assertTrue(sts.getCallerIdentity().arn().endsWith("/" + roleName + "/session-restriction"));
                }
            } finally {
                if (assumed != null) {
                    iam.unregisterSession("000000000000", assumed.accessKeyId());
                    assertTrue(iam.resolveCallerArns(assumed.accessKeyId()).isEmpty());
                }
                if (initial != null) {
                    iam.unregisterSession("000000000000", initial.accessKeyId());
                    assertTrue(iam.resolveCallerArns(initial.accessKeyId()).isEmpty());
                }
                admin.deleteRolePolicy(b -> b.roleName(roleName).policyName("all"));
                admin.deleteRole(b -> b.roleName(roleName));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"missing,InvalidClientTokenId", "wrong,InvalidClientTokenId"})
    void invalidTokensPrecedeSessionTypeRestriction(String mode, String code) throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "session-invalid-", "all", ALLOW_ALL);
             StsClient issuer = sts(AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            Credentials issued = issuer.getSessionToken().credentials();
            try {
                AwsCredentials caller = "missing".equals(mode)
                        ? AwsBasicCredentials.create(issued.accessKeyId(), issued.secretAccessKey())
                        : AwsSessionCredentials.create(issued.accessKeyId(), issued.secretAccessKey(),
                                "wrong".equals(mode) ? "invalid-token" : issued.sessionToken());
                try (IamClient client = iam(caller)) {
                    IamException denied = assertThrows(IamException.class,
                            () -> client.getUser(b -> b.userName(user.name())));
                    assertEquals(code, denied.awsErrorDetails().errorCode(), denied.getMessage());
                }
            } finally {
                iam.unregisterSession("000000000000", issued.accessKeyId());
                assertTrue(iam.resolveCallerArns(issued.accessKeyId()).isEmpty());
            }
        }
    }

    private static AwsSessionCredentials session(Credentials credentials) {
        return AwsSessionCredentials.create(credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken());
    }

    private IamClient iam(AwsCredentials credentials) {
        return IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
    }

    private StsClient sts(AwsCredentials credentials) {
        return StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
    }
}
