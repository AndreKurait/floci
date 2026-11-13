package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.SfnException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class StepFunctionsExecutionConfigurationIamIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"execution,true", "wildcard,true", "wrong,false", "state-machine,false", "deny,false"})
    void signedCallerNeedsPermissionOnTheExecution(String mode, boolean allowed) throws Exception {
        String name = "execution-config-iam-" + UUID.randomUUID();
        String definition = "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}}";
        try (IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials("test", "test"))
                .build(); SfnClient admin = client("test", "test")) {
            String role = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                    {"Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                    "Principal":{"Service":"states.amazonaws.com"}}]}
                    """)).role().arn();
            try {
                String machine = admin.createStateMachine(request -> request.name(name).roleArn(role)
                        .definition(definition)).stateMachineArn();
                try {
                    String execution = admin.startExecution(request -> request.stateMachineArn(machine)).executionArn();
                    String resource = switch (mode) {
                        case "execution", "deny" -> execution;
                        case "wrong" -> execution + "-other";
                        case "state-machine" -> machine;
                        default -> "*";
                    };
                    List<Map<String, String>> statements = new ArrayList<>();
                    statements.add(statement("Allow", resource));
                    if ("deny".equals(mode)) {
                        statements.add(statement("Deny", execution));
                    }
                    String policy = new ObjectMapper().writeValueAsString(
                            Map.of("Version", "2012-10-17", "Statement", statements));
                    try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "config-reader-", "read", policy);
                         SfnClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
                        if (allowed) {
                            assertEquals(definition, actor.describeStateMachineForExecution(request ->
                                    request.executionArn(execution)).definition());
                        } else {
                            SfnException denied = assertThrows(SfnException.class, () ->
                                    actor.describeStateMachineForExecution(request -> request.executionArn(execution)));
                            assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                        }
                    }
                } finally {
                    admin.deleteStateMachine(request -> request.stateMachineArn(machine));
                }
            } finally {
                iam.deleteRole(request -> request.roleName(name));
            }
        }
    }

    private SfnClient client(String access, String secret) {
        return SfnClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials(access, secret))
                .build();
    }

    private static StaticCredentialsProvider credentials(String access, String secret) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret));
    }

    private static Map<String, String> statement(String effect, String resource) {
        return Map.of("Effect", effect, "Action", "states:DescribeStateMachineForExecution", "Resource", resource);
    }
}
