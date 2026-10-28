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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.VirtualMFADevice;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.GetSessionTokenRequest;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SessionMfaContextIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;

    @ParameterizedTest
    @CsvSource({
            "Allow,Bool,false,true,true,MultiFactorAuthPresent",
            "Allow,Bool,false,false,false,MultiFactorAuthPresent",
            "Deny,Bool,false,true,false,MultiFactorAuthPresent",
            "Deny,Bool,false,false,true,MultiFactorAuthPresent",
            "Allow,Null,true,true,false,MultiFactorAuthPresent",
            "Allow,Null,true,false,true,MultiFactorAuthPresent",
            "Deny,BoolIfExists,false,true,false,MultiFactorAuthPresent",
            "Deny,BoolIfExists,false,false,false,MultiFactorAuthPresent",
            "Allow,Bool,true,true,false,MultiFactorAuthPresent",
            "Allow,Null,true,true,true,MultiFactorAuthAge",
            "Allow,Null,true,false,true,MultiFactorAuthAge",
            "Allow,NumericLessThan,3600,true,false,MultiFactorAuthAge"
    })
    void issuedNonMfaCredentialsHaveFalseRatherThanAbsentContext(String effect, String operator, String value,
                                                                boolean temporary, boolean allowed, String key) throws Exception {
        String statement = "{\"Effect\":\"" + effect + "\",\"Action\":\"ssm:DescribeParameters\","
                + "\"Resource\":\"*\",\"Condition\":{\"" + operator
                + "\":{\"aws:" + key + "\":\"" + value + "\"}}}";
        String policy = "{\"Statement\":[" + ("Deny".equals(effect)
                ? "{\"Effect\":\"Allow\",\"Action\":\"ssm:DescribeParameters\",\"Resource\":\"*\"}," : "")
                + statement + "]}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "mfa-context-", "read", policy)) {
            AwsBasicCredentials longTerm = AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey());
            try (StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(longTerm)).build()) {
                Credentials issued = temporary ? sts.getSessionToken().credentials() : null;
                try {
                    AwsCredentials credentials = issued == null ? longTerm : AwsSessionCredentials.create(
                            issued.accessKeyId(), issued.secretAccessKey(), issued.sessionToken());
                    try (SsmClient ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                            .credentialsProvider(StaticCredentialsProvider.create(credentials)).build()) {
                        if (allowed) {
                            assertNotNull(assertDoesNotThrow(() -> ssm.describeParameters()));
                        } else {
                            assertEquals("AccessDeniedException", assertThrows(SsmException.class,
                                    () -> ssm.describeParameters()).awsErrorDetails().errorCode());
                        }
                    }
                } finally {
                    if (issued != null) {
                        iam.unregisterSession("000000000000", issued.accessKeyId());
                        assertTrue(iam.resolveCallerArns(issued.accessKeyId()).isEmpty());
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "Allow,Bool,MultiFactorAuthPresent,true,true",
            "Allow,Bool,MultiFactorAuthPresent,false,false",
            "Deny,Bool,MultiFactorAuthPresent,true,false",
            "Deny,Bool,MultiFactorAuthPresent,false,true",
            "Allow,NumericLessThan,MultiFactorAuthAge,3600,true",
            "Allow,NumericGreaterThan,MultiFactorAuthAge,3600,false",
            "Deny,NumericLessThan,MultiFactorAuthAge,3600,false",
            "Deny,NumericGreaterThan,MultiFactorAuthAge,3600,true",
            "Allow,Null,MultiFactorAuthAge,false,true",
            "Allow,Null,MultiFactorAuthAge,true,false"
    })
    void verifiedVirtualMfaControlsSignedResourceAccess(String effect, String operator, String key,
                                                       String value, boolean allowed)
            throws Exception {
        String conditional = "{\"Effect\":\"" + effect + "\",\"Action\":\"ssm:DescribeParameters\","
                + "\"Resource\":\"*\",\"Condition\":{\"" + operator + "\":{\"aws:" + key + "\":\""
                + value + "\"}}}";
        String policy = "{\"Statement\":[" + ("Deny".equals(effect)
                ? "{\"Effect\":\"Allow\",\"Action\":\"ssm:DescribeParameters\",\"Resource\":\"*\"}," : "")
                + conditional + "]}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "verified-mfa-", "read", policy);
             IamClient admin = admin(); StsClient sts = sts(user)) {
            VirtualMFADevice device = admin.createVirtualMFADevice(b -> b
                    .virtualMFADeviceName("session-" + UUID.randomUUID())).virtualMFADevice();
            boolean enabled = false;
            try {
                String seed = device.base32StringSeed().asUtf8String();
                long step = Totp.stepAt(Instant.now());
                admin.enableMFADevice(b -> b.userName(user.name()).serialNumber(device.serialNumber())
                        .authenticationCode1(Totp.codeAt(seed, step - 1))
                        .authenticationCode2(Totp.codeAt(seed, step)));
                enabled = true;
                Credentials issued = sts.getSessionToken(b -> b.serialNumber(device.serialNumber())
                        .tokenCode(Totp.codeAt(seed, Totp.stepAt(Instant.now())))).credentials();
                try (SsmClient ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                                issued.accessKeyId(), issued.secretAccessKey(), issued.sessionToken()))).build()) {
                    if (allowed) {
                        assertNotNull(assertDoesNotThrow(() -> ssm.describeParameters()));
                    } else {
                        assertEquals("AccessDeniedException", assertThrows(SsmException.class,
                                () -> ssm.describeParameters()).awsErrorDetails().errorCode());
                    }
                } finally {
                    iam.unregisterSession("000000000000", issued.accessKeyId());
                    assertTrue(iam.resolveCallerArns(issued.accessKeyId()).isEmpty());
                }
            } finally {
                if (enabled) {
                    admin.deactivateMFADevice(b -> b.userName(user.name()).serialNumber(device.serialNumber()));
                }
                admin.deleteVirtualMFADevice(b -> b.serialNumber(device.serialNumber()));
                assertTrue(admin.listVirtualMFADevices().virtualMFADevices().stream()
                        .noneMatch(remaining -> remaining.serialNumber().equals(device.serialNumber())));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "wrong-code,AccessDenied", "wrong-owner,AccessDenied", "unassigned,AccessDenied",
            "missing-code,AccessDenied", "missing-serial,AccessDenied", "missing-device,AccessDenied",
            "malformed-code,ValidationError", "malformed-serial,ValidationError"
    })
    void unverifiedMfaCannotMintCredentials(String scenario, String expectedCode) throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "invalid-mfa-", "read",
                     "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"ssm:DescribeParameters\",\"Resource\":\"*\"}]}");
             SignedIamUsers.User other = SignedIamUsers.createUser(endpoint, "other-mfa-", "read",
                     "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"ssm:DescribeParameters\",\"Resource\":\"*\"}]}");
             IamClient admin = admin(); StsClient sts = sts(user)) {
            VirtualMFADevice device = admin.createVirtualMFADevice(b -> b
                    .virtualMFADeviceName("session-" + UUID.randomUUID())).virtualMFADevice();
            String owner = "wrong-owner".equals(scenario) ? other.name() : user.name();
            boolean enabled = false;
            try {
                String seed = device.base32StringSeed().asUtf8String();
                long step = Totp.stepAt(Instant.now());
                if (!"unassigned".equals(scenario)) {
                    admin.enableMFADevice(b -> b.userName(owner).serialNumber(device.serialNumber())
                            .authenticationCode1(Totp.codeAt(seed, step - 1))
                            .authenticationCode2(Totp.codeAt(seed, step)));
                    enabled = true;
                }
                GetSessionTokenRequest.Builder request = GetSessionTokenRequest.builder();
                if (!"missing-serial".equals(scenario)) {
                    request.serialNumber(switch (scenario) {
                        case "malformed-serial" -> "!";
                        case "missing-device" -> device.serialNumber() + "-missing";
                        default -> device.serialNumber();
                    });
                }
                if (!"missing-code".equals(scenario)) {
                    request.tokenCode(switch (scenario) {
                        case "malformed-code" -> "not-a-code";
                        case "wrong-code" -> invalidCode(seed);
                        default -> Totp.codeAt(seed, Totp.stepAt(Instant.now()));
                    });
                }
                AtomicReference<Credentials> unexpected = new AtomicReference<>();
                try {
                    assertEquals(expectedCode, assertThrows(StsException.class,
                            () -> unexpected.set(sts.getSessionToken(request.build()).credentials()))
                            .awsErrorDetails().errorCode());
                } finally {
                    if (unexpected.get() != null) {
                        iam.unregisterSession("000000000000", unexpected.get().accessKeyId());
                        assertTrue(iam.resolveCallerArns(unexpected.get().accessKeyId()).isEmpty());
                    }
                }
            } finally {
                if (enabled) {
                    admin.deactivateMFADevice(b -> b.userName(owner).serialNumber(device.serialNumber()));
                }
                admin.deleteVirtualMFADevice(b -> b.serialNumber(device.serialNumber()));
                assertTrue(admin.listVirtualMFADevices().virtualMFADevices().stream()
                        .noneMatch(remaining -> remaining.serialNumber().equals(device.serialNumber())));
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

    private IamClient admin() {
        return IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private StsClient sts(SignedIamUsers.User user) {
        return StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build();
    }
}
