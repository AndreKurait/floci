package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class IamPrincipalTagsIntegrationTest {
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"iam:ListRoles\","
            + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/department\":\"engineering\"}}}}";
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;

    @Test
    void signedManagementUsesCurrentUserTags() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "principal-tag-", "list", ALLOW);
             IamClient root = client(AwsBasicCredentials.create("test", "test"));
             IamClient caller = client(AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))) {
            assertEquals(403, assertThrows(IamException.class, () -> caller.listRoles()).statusCode());
            root.tagUser(request -> request.userName(user.name()).tags(tag -> tag.key("department").value("engineering")));
            assertNotNull(caller.listRoles());
            root.untagUser(request -> request.userName(user.name()).tagKeys("department"));
            assertEquals(403, assertThrows(IamException.class, () -> caller.listRoles()).statusCode());
        }
    }

    @Test
    void issuedUserCredentialsHonorTagConditionedExplicitDenial() throws Exception {
        String policy = "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"ssm:DescribeParameters\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"ssm:DescribeParameters\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/blocked\":\"yes\"}}}]}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "principal-tag-session-", "list", policy);
             StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build();
             IamClient root = client(AwsBasicCredentials.create("test", "test"))) {
            Credentials session = sts.getSessionToken().credentials();
            try (SsmClient caller = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                            session.accessKeyId(), session.secretAccessKey(), session.sessionToken()))).build()) {
                assertNotNull(caller.describeParameters());
                root.tagUser(request -> request.userName(user.name()).tags(tag -> tag.key("blocked").value("yes")));
                assertEquals("AccessDeniedException", assertThrows(SsmException.class,
                        () -> caller.describeParameters()).awsErrorDetails().errorCode());
                root.untagUser(request -> request.userName(user.name()).tagKeys("blocked"));
                assertNotNull(caller.describeParameters());
            } finally {
                iam.unregisterSession("000000000000", session.accessKeyId());
            }
        }
    }

    private IamClient client(AwsCredentials credentials) {
        return IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
    }
}
