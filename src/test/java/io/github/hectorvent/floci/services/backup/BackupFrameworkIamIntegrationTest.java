package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.backup.BackupClient;
import software.amazon.awssdk.services.backup.model.BackupException;
import software.amazon.awssdk.services.backup.model.FrameworkControl;
import software.amazon.awssdk.services.backup.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class BackupFrameworkIamIntegrationTest {

    private static final AwsCredentialsProvider ROOT =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    @TestHTTPResource URI endpoint;

    @Test
    void nativeResourceTagAndTagOnCreateChecksRefuseBeforeMutationAndUseTheServedRoute() throws Exception {
        String name = "Framework_" + UUID.randomUUID().toString().replace("-", "");
        String resource = "arn:aws:backup:us-east-1:000000000000:framework:" + name + "-*";
        String keyId = null;
        boolean created = false;
        try (IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(ROOT).build(); BackupClient root = client(ROOT)) {
            iam.createUser(r -> r.userName(name));
            try {
                AccessKey key = iam.createAccessKey(r -> r.userName(name)).accessKey();
                keyId = key.accessKeyId();
                String initialPolicy = policy(resource, false, null);
                iam.putUserPolicy(r -> r.userName(name).policyName("framework").policyDocument(initialPolicy));
                AwsCredentialsProvider userCredentials = StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()));
                try (BackupClient user = client(userCredentials)) {
                    // The create action is granted, but its tagging dependency is absent.
                    assertDenied(() -> create(user, name));
                    assertThrows(ResourceNotFoundException.class, () -> root.describeFramework(r -> r.frameworkName(name)));
                    String taggedPolicy = policy(resource, true, null);
                    iam.putUserPolicy(r -> r.userName(name).policyName("framework").policyDocument(taggedPolicy));
                    String arn = create(user, name);
                    created = true;
                    assertEquals("initial", user.describeFramework(r -> r.frameworkName(name)).frameworkDescription());
                    assertEquals(Map.of("env", "approved"), user.listTags(r -> r.resourceArn(arn)).tags());
                    assertDenied(() -> create(user, name + "_wrong"));
                    assertThrows(ResourceNotFoundException.class, () -> root.describeFramework(r ->
                            r.frameworkName(name + "_wrong")));
                    // An X-Amz-Target header cannot turn the POST framework route into the granted list operation.
                    assertDenied(() -> user.createFramework(r -> r.frameworkName(name + "_spoof")
                            .frameworkControls(control()).overrideConfiguration(c ->
                                    c.putHeader("X-Amz-Target", "Backup.ListFrameworks"))));
                    assertThrows(ResourceNotFoundException.class, () -> root.describeFramework(r ->
                            r.frameworkName(name + "_spoof")));
                    String conditionalDeny = policy(resource, true, arn);
                    iam.putUserPolicy(r -> r.userName(name).policyName("framework").policyDocument(conditionalDeny));
                    assertDenied(() -> user.updateFramework(r -> r.frameworkName(name).frameworkDescription("refused")));
                    assertEquals("initial", root.describeFramework(r -> r.frameworkName(name)).frameworkDescription());
                    root.tagResource(r -> r.resourceArn(arn).tags(Map.of("env", "different")));
                    user.updateFramework(r -> r.frameworkName(name).frameworkDescription("allowed after tag change"));
                    assertEquals("allowed after tag change", root.describeFramework(r ->
                            r.frameworkName(name)).frameworkDescription());
                    root.tagResource(r -> r.resourceArn(arn).tags(Map.of("env", "approved")));
                    assertDenied(() -> user.updateFramework(r -> r.frameworkName(name).frameworkDescription("refused again")));
                    assertEquals("allowed after tag change", root.describeFramework(r ->
                            r.frameworkName(name)).frameworkDescription());
                    assertTrue(user.listFrameworks(request -> {}).frameworks().stream().anyMatch(value -> value.frameworkName().equals(name)));
                }
            } finally {
                if (created) {
                    root.deleteFramework(r -> r.frameworkName(name));
                }
                iam.deleteUserPolicy(r -> r.userName(name).policyName("framework"));
                if (keyId != null) {
                    String deleteKey = keyId;
                    iam.deleteAccessKey(r -> r.userName(name).accessKeyId(deleteKey));
                }
                iam.deleteUser(r -> r.userName(name));
            }
        }
    }

    private static String create(BackupClient client, String name) {
        return client.createFramework(r -> r.frameworkName(name).frameworkDescription("initial")
                .frameworkControls(control()).frameworkTags(Map.of("env", "approved"))).frameworkArn();
    }

    private static FrameworkControl control() {
        return FrameworkControl.builder().controlName("BACKUP_RECOVERY_POINT_ENCRYPTED").build();
    }

    private BackupClient client(AwsCredentialsProvider credentials) {
        return BackupClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
    }

    private static String policy(String resource, boolean tagPermission, String deniedArn) throws Exception {
        List<Map<String, Object>> statements = new ArrayList<>();
        statements.add(Map.of("Effect", "Allow", "Action", "backup:CreateFramework", "Resource", resource,
                "Condition", Map.of("StringEquals", Map.of("aws:RequestTag/env", "approved"))));
        statements.add(Map.of("Effect", "Allow", "Action", "backup:ListFrameworks", "Resource", "*"));
        statements.add(Map.of("Effect", "Allow", "Action",
                List.of("backup:DescribeFramework", "backup:UpdateFramework", "backup:DeleteFramework", "backup:ListTags"),
                "Resource", resource));
        if (tagPermission) {
            statements.add(Map.of("Effect", "Allow", "Action", "backup:TagResource", "Resource", resource,
                    "Condition", Map.of("StringEquals", Map.of("aws:RequestTag/env", "approved"))));
        }
        if (deniedArn != null) {
            statements.add(Map.of("Effect", "Deny", "Action", "backup:UpdateFramework", "Resource", deniedArn,
                    "Condition", Map.of("StringEquals", Map.of("aws:ResourceTag/env", "approved"))));
        }
        return new ObjectMapper().writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements));
    }

    private static void assertDenied(Runnable request) {
        assertEquals("AccessDeniedException", assertThrows(BackupException.class, request::run)
                .awsErrorDetails().errorCode());
    }
}
