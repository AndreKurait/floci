package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.SfnException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class StepFunctionsQualifierIamIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> ACTIONS = List.of("states:DescribeStateMachine", "states:StartExecution");
    private static final String DEFINITION = """
            {"StartAt":"Done","States":{"Done":{"Type":"Succeed"}}}""";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @Test
    void qualifierGrantAdmitsOnlySelectedVersion() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(qualified("Allow", fixture.machine(), "1")));
             SfnClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            allowed(actor, fixture.first());
            denied(actor, fixture.second());
            denied(actor, fixture.alias());
            denied(actor, fixture.machine());
        }
    }

    @Test
    void qualifierDenyBlocksOnlySelectedVersion() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(
                Map.of("Effect", "Allow", "Action", ACTIONS, "Resource", fixture.machine()),
                qualified("Deny", fixture.machine(), "1")));
             SfnClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            allowed(actor, fixture.second());
            denied(actor, fixture.first());
            allowed(actor, fixture.alias());
            allowed(actor, fixture.machine());
        }
    }

    @Test
    void aliasQualifierIsTheRequestedAliasRatherThanItsSelectedVersion() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(qualified("Allow", fixture.machine(), "PROD")));
             SfnClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            allowed(actor, fixture.alias());
            denied(actor, fixture.first());
            denied(actor, fixture.machine());
        }
    }

    @Test
    void aVersionArnInResourceDoesNotReplaceTheBaseArnAndQualifier() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(
                Map.of("Effect", "Allow", "Action", ACTIONS, "Resource", fixture.first())));
             SfnClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            denied(actor, fixture.first());
            denied(actor, fixture.machine());
        }
    }

    private static Map<String, Object> qualified(String effect, String machine, String qualifier) {
        return Map.of("Effect", effect, "Action", ACTIONS, "Resource", machine,
                "Condition", Map.of("ForAnyValue:StringEquals",
                        Map.of("states:StateMachineQualifier", List.of(qualifier))));
    }

    private static void allowed(SfnClient actor, String machine) {
        assertDoesNotThrow(() -> actor.describeStateMachine(request -> request.stateMachineArn(machine)));
        assertDoesNotThrow(() -> actor.startExecution(request -> request.stateMachineArn(machine)));
    }

    private static void denied(SfnClient actor, String machine) {
        SfnException describe = assertThrows(SfnException.class,
                () -> actor.describeStateMachine(request -> request.stateMachineArn(machine)));
        assertEquals("AccessDeniedException", describe.awsErrorDetails().errorCode());
        SfnException start = assertThrows(SfnException.class,
                () -> actor.startExecution(request -> request.stateMachineArn(machine)));
        assertEquals("AccessDeniedException", start.awsErrorDetails().errorCode());
    }

    private SignedIamUsers.User user(List<Map<String, Object>> statements) throws Exception {
        return SignedIamUsers.createUser(endpoint, "qualifier-", "qualifier",
                MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements)));
    }

    private Fixture fixture() {
        String name = "qualifier-" + UUID.randomUUID();
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        cleanup.register(iam::close);
        String role = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"Service":"states.amazonaws.com"}}]}""")).role().arn();
        cleanup.register(() -> iam.deleteRole(request -> request.roleName(name)));
        SfnClient admin = client("test", "test");
        cleanup.register(admin::close);
        String machine = admin.createStateMachine(request -> request.name(name).roleArn(role)
                .definition(DEFINITION).publish(true)).stateMachineArn();
        cleanup.register(() -> admin.deleteStateMachine(request -> request.stateMachineArn(machine)));
        String first = machine + ":1";
        String second = admin.updateStateMachine(request -> request.stateMachineArn(machine)
                .definition(DEFINITION.replace("\"StartAt\"", "\"Comment\":\"second\",\"StartAt\""))
                .publish(true)).stateMachineVersionArn();
        assertEquals(machine + ":2", second);
        String alias = admin.createStateMachineAlias(request -> request.name("PROD")
                .routingConfiguration(route -> route.stateMachineVersionArn(first).weight(100)))
                .stateMachineAliasArn();
        cleanup.register(() -> admin.deleteStateMachineAlias(request -> request.stateMachineAliasArn(alias)));
        return new Fixture(machine, first, second, alias);
    }

    private SfnClient client(String access, String secret) {
        return SfnClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .build();
    }

    private record Fixture(String machine, String first, String second, String alias) {
    }
}
