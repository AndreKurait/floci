package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SsmConnectionStatusIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    RegionResolver regions;

    @ParameterizedTest
    @ValueSource(strings = {"i-0123456789abcdef0", "mi-0123456789abcdef0"})
    void checksTheSelectedNodeInsteadOfWildcard(String target) throws Exception {
        boolean managed = target.startsWith("mi-");
        String resource = regions.buildArn(managed ? "ssm" : "ec2", "us-east-1",
                (managed ? "managed-instance/" : "instance/") + target);
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-status-", "status", """
                {"Statement":{"Effect":"Allow","Action":"ssm:GetConnectionStatus","Resource":"%s"}}
                """.formatted(resource));
             SsmClient client = client(user, Region.US_EAST_1)) {
            assertEquals("notconnected", client.getConnectionStatus(request -> request.target(target)).statusAsString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"i-0123456789abcdef0", "mi-0123456789abcdef0"})
    void permissionForAnotherNodeDoesNotGrantAccess(String target) throws Exception {
        boolean managed = target.startsWith("mi-");
        String resource = regions.buildArn(managed ? "ssm" : "ec2", "us-east-1",
                (managed ? "managed-instance/mi-" : "instance/i-") + "00000000000000000");
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ssm-status-", "status", """
                {"Statement":{"Effect":"Allow","Action":"ssm:GetConnectionStatus","Resource":"%s"}}
                """.formatted(resource));
             SsmClient client = client(user, Region.US_EAST_1)) {
            SsmException denied = assertThrows(SsmException.class,
                    () -> client.getConnectionStatus(request -> request.target(target)));
            assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
        }
    }

    private SsmClient client(SignedIamUsers.User user, Region region) {
        return SsmClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey())))
                .build();
    }
}
