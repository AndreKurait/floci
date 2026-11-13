package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.PolicyVersion;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.PolicyDescriptorType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class StsManagedSessionPolicyIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;

    @ParameterizedTest
    @CsvSource({"role,true", "role,false", "federation,true", "federation,false"})
    void managedPolicyRestrictsSignedDownstreamRequests(String operation, boolean allowed) throws Exception {
        exerciseManagedSession(operation, allowed, "unchanged");
    }

    @ParameterizedTest
    @CsvSource({"role,revoke", "role,grant", "role,delete",
            "federation,revoke", "federation,grant", "federation,delete"})
    void existingSessionTracksManagedPolicyLifecycle(String operation, String change) throws Exception {
        exerciseManagedSession(operation, true, change);
    }

    private void exerciseManagedSession(String operation, boolean allowed, String change) throws Exception {
        String name = "managed-session-" + UUID.randomUUID();
        String policyArn = "arn:aws:iam::" + ACCOUNT + ":policy/" + name;
        List<Credentials> sessions = new ArrayList<>();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "managed-admin-", "admin",
                "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}")) {
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()));
            try (IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(credentials).build();
                 StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                         .credentialsProvider(credentials).build();
                 AutoCloseable policyCleanup = () -> {
                     try {
                         for (PolicyVersion version : admin.listPolicyVersions(b -> b.policyArn(policyArn)).versions()) {
                             if (!version.isDefaultVersion()) {
                                 admin.deletePolicyVersion(b -> b.policyArn(policyArn).versionId(version.versionId()));
                             }
                         }
                         admin.deletePolicy(b -> b.policyArn(policyArn));
                     } catch (NoSuchEntityException expected) {
                         // Known-name cleanup is registered before the create request.
                     }
                     assertThrows(NoSuchEntityException.class, () -> admin.getPolicy(b -> b.policyArn(policyArn)));
                 };
                 AutoCloseable roleCleanup = () -> {
                     try {
                         admin.deleteRole(b -> b.roleName(name));
                     } catch (NoSuchEntityException expected) {
                         // Federation-token cases do not create a role.
                     }
                 };
                 AutoCloseable inlineCleanup = () -> {
                     try {
                         admin.deleteRolePolicy(b -> b.roleName(name).policyName("base"));
                     } catch (NoSuchEntityException expected) {
                         // Role creation may fail before the inline policy exists.
                     }
                 };
                 AutoCloseable sessionCleanup = () -> {
                     for (Credentials session : sessions) {
                         iam.unregisterSession(ACCOUNT, session.accessKeyId());
                         assertTrue(iam.findSessionToken(session.accessKeyId()).isEmpty());
                     }
                 }) {
                String action = allowed ? "ssm:DescribeParameters" : "ssm:GetParameter";
                admin.createPolicy(b -> b.policyName(name).policyDocument(
                        "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"*\"}}"));
                PolicyDescriptorType policy = PolicyDescriptorType.builder().arn(policyArn).build();
                Credentials session;
                if ("role".equals(operation)) {
                    String arn = admin.createRole(b -> b.roleName(name).assumeRolePolicyDocument(
                            "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                                    + ACCOUNT + ":user/" + user.name() + "\"},\"Action\":\"sts:AssumeRole\"}}"))
                            .role().arn();
                    admin.putRolePolicy(b -> b.roleName(name).policyName("base").policyDocument(
                            "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"ssm:*\",\"Resource\":\"*\"}}"));
                    session = sts.assumeRole(b -> b.roleArn(arn).roleSessionName("managed").policyArns(policy)).credentials();
                } else {
                    session = sts.getFederationToken(b -> b.name("managed").policyArns(policy)).credentials();
                }
                sessions.add(session);
                try (SsmClient caller = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                                session.accessKeyId(), session.secretAccessKey(), session.sessionToken()))).build()) {
                    if (!"unchanged".equals(change)) {
                        assertDoesNotThrow(() -> caller.describeParameters());
                        assertEquals("AccessDeniedException", assertThrows(SsmException.class, caller::listDocuments)
                                .awsErrorDetails().errorCode());
                        if ("delete".equals(change)) {
                            admin.deletePolicy(b -> b.policyArn(policyArn));
                            assertThrows(NoSuchEntityException.class, () -> admin.getPolicy(b -> b.policyArn(policyArn)));
                        } else {
                            admin.createPolicyVersion(b -> b.policyArn(policyArn).setAsDefault(true).policyDocument(
                                    "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"ssm:ListDocuments\","
                                            + "\"Resource\":\"*\"}}"));
                            if ("grant".equals(change)) {
                                assertDoesNotThrow(() -> caller.listDocuments());
                            }
                        }
                        assertEquals("AccessDeniedException", assertThrows(SsmException.class, caller::describeParameters)
                                .awsErrorDetails().errorCode());
                        if (!"delete".equals(change)) {
                            assertDoesNotThrow(() -> caller.listDocuments());
                        }
                    } else if (allowed) {
                        assertDoesNotThrow(() -> caller.describeParameters());
                    } else {
                        assertEquals("AccessDeniedException", assertThrows(SsmException.class, caller::describeParameters)
                                .awsErrorDetails().errorCode());
                    }
                }
            }
        }
    }
}
