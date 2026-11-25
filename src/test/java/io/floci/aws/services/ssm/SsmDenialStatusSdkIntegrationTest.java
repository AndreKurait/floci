package io.floci.aws.services.ssm;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SsmDenialStatusSdkIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void deniedReadHasSharedJsonStatusBeforeParameterLookup() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-denial-");
             SsmClient client = client(user.accessKeyId(), user.secretAccessKey())) {
            SsmException denied = assertThrows(SsmException.class,
                    () -> client.getParameter(request -> request.name("/denied-" + UUID.randomUUID())));

            assertEquals(400, denied.statusCode());
            assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
            assertTrue(denied.awsErrorDetails().errorMessage().contains("ssm:GetParameter"));
        }
    }

    @Test
    void deniedWriteHasSharedJsonStatusAndCreatesNoParameter() throws Exception {
        String name = "/denied-write-" + UUID.randomUUID();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-denial-");
             SsmClient client = client(user.accessKeyId(), user.secretAccessKey());
             SsmClient root = client("test", "test")) {
            try {
                SsmException denied = assertThrows(SsmException.class,
                        () -> client.putParameter(request -> request.name(name).value("blocked")
                                .type(ParameterType.STRING)));

                assertEquals(400, denied.statusCode());
                assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                assertThrows(ParameterNotFoundException.class,
                        () -> root.getParameter(request -> request.name(name)));
            } finally {
                deleteIfPresent(root, name);
            }
        }
    }

    @Test
    void allowedSignedCallerStillWritesAndReads() throws Exception {
        String name = "/allowed-" + UUID.randomUUID();
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-allowed-", "parameters", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["ssm:PutParameter","ssm:GetParameter"],"Resource":"*"}]}
                """);
             SsmClient client = client(user.accessKeyId(), user.secretAccessKey());
             SsmClient root = client("test", "test")) {
            try {
                client.putParameter(request -> request.name(name).value("allowed").type(ParameterType.STRING));
                assertEquals("allowed", client.getParameter(request -> request.name(name)).parameter().value());
            } finally {
                deleteIfPresent(root, name);
            }
        }
    }

    private SsmClient client(String accessKeyId, String secretAccessKey) {
        return SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey))).build();
    }

    private void deleteIfPresent(SsmClient client, String name) {
        try {
            client.deleteParameter(request -> request.name(name));
        } catch (ParameterNotFoundException expected) {
            // Denied writes leave no parameter to remove.
        }
    }
}
