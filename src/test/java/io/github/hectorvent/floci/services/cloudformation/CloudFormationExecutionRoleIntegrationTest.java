package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native SDK operations must resolve a cross-account output as the selected execution role. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudFormationExecutionRoleIntegrationTest {
    private static final String CONSUMER = "141414141414";
    private static final String OWNER = "252525252525";
    private static final String ASSUME_ROLE = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"sts:AssumeRole","Resource":"*"}]}""";
    private static final String SERVICE_TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Principal":{"Service":"cloudformation.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    @TestHTTPResource
    URI endpoint;

    private final Deque<Runnable> cleanup = new ArrayDeque<>();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void retireAllOwnedResources() {
        AssertionError failures = new AssertionError("CloudFormation execution-role fixture cleanup failed");
        while (!cleanup.isEmpty()) {
            try {
                cleanup.pop().run();
            } catch (RuntimeException | AssertionError failure) {
                failures.addSuppressed(failure);
            }
        }
        if (failures.getSuppressed().length > 0) {
            throw failures;
        }
    }

    @Test
    void accountTrustWithAnExactPrincipalArnConditionUsesTheSelectedExecutionRole() {
        Fixture fixture = fixture(ASSUME_ROLE);
        String consuming = create(fixture.consumer(), "consumer", template(fixture, "initial"), fixture.execution());

        Stack result = terminal(fixture.consumer(), consuming);
        assertEquals("CREATE_COMPLETE", result.stackStatusAsString(), result.stackStatusReason());
        assertEquals(fixture.execution(), result.roleARN());
        assertParameter(fixture, "vpc-execution-role");
    }

    @Test
    void anotherRoleInTheSameAccountCannotUseTheReader() {
        Fixture fixture = fixture(ASSUME_ROLE);
        String other = role(fixture.consumerIam(), "other", SERVICE_TRUST, ASSUME_ROLE);
        String consuming = create(fixture.consumer(), "wrong-role", template(fixture, "initial"), other);

        assertReadDenied(fixture.consumer(), consuming, "sts:AssumeRole");
    }

    @Test
    void executionRoleRequiresPermissionToAssumeTheReader() {
        Fixture fixture = fixture("""
                {"Statement":[{"Effect":"Allow","Action":"ssm:*","Resource":"*"}]}""");
        String consuming = create(fixture.consumer(), "no-assume", template(fixture, "initial"), fixture.execution());

        assertReadDenied(fixture.consumer(), consuming, "sts:AssumeRole");
    }

    @Test
    void executionRoleMustTrustCloudFormationBeforeAStackIsCreated() {
        Fixture fixture = fixture(ASSUME_ROLE);
        String other = role(fixture.consumerIam(), "wrong-service", SERVICE_TRUST.replace("cloudformation", "lambda"),
                ASSUME_ROLE);
        String name = "execution-role-refused-" + suffix;
        CloudFormationException failure = assertThrows(CloudFormationException.class,
                () -> fixture.consumer().createStack(request -> request.stackName(name)
                        .templateBody(template(fixture, "initial")).roleARN(other)));

        assertEquals("ValidationError", failure.awsErrorDetails().errorCode());
        assertTrue(failure.getMessage().contains("cannot be assumed"), failure.getMessage());
        assertThrows(CloudFormationException.class,
                () -> fixture.consumer().describeStacks(request -> request.stackName(name)));
    }

    @Test
    void updatesReuseTheExecutionRoleAndRefreshTheWeakOutputReference() {
        Fixture fixture = fixture(ASSUME_ROLE);
        String consuming = create(fixture.consumer(), "consumer", template(fixture, "initial"), fixture.execution());
        assertEquals("CREATE_COMPLETE", terminal(fixture.consumer(), consuming).stackStatusAsString());
        fixture.owner().updateStack(request -> request.stackName(fixture.producer()).templateBody("""
                {"Outputs":{"VpcId":{"Value":"vpc-refreshed"}}}"""));
        assertEquals("UPDATE_COMPLETE", terminal(fixture.owner(), fixture.producer()).stackStatusAsString());

        fixture.consumer().updateStack(request -> request.stackName(consuming).usePreviousTemplate(true)
                .parameters(parameter -> parameter.parameterKey("Refresh").parameterValue("second")));

        Stack result = terminal(fixture.consumer(), consuming);
        assertEquals("UPDATE_COMPLETE", result.stackStatusAsString(), result.stackStatusReason());
        assertEquals(fixture.execution(), result.roleARN());
        assertParameter(fixture, "vpc-refreshed");
    }

    @Test
    void anUnexecutedChangeSetDoesNotReplaceTheDeployedExecutionRole() {
        Fixture fixture = fixture(ASSUME_ROLE);
        String consuming = create(fixture.consumer(), "consumer", template(fixture, "initial"), fixture.execution());
        assertEquals("CREATE_COMPLETE", terminal(fixture.consumer(), consuming).stackStatusAsString());
        String other = role(fixture.consumerIam(), "reviewed", SERVICE_TRUST, ASSUME_ROLE);

        fixture.consumer().createChangeSet(request -> request.stackName(consuming).changeSetName("review-only")
                .changeSetType("UPDATE").usePreviousTemplate(true).roleARN(other)
                .parameters(parameter -> parameter.parameterKey("Refresh").parameterValue("review")));

        assertEquals(fixture.execution(), fixture.consumer().describeStacks(request -> request.stackName(consuming))
                .stacks().getFirst().roleARN());
        fixture.consumer().deleteChangeSet(request -> request.stackName(consuming).changeSetName("review-only"));
        fixture.consumer().updateStack(request -> request.stackName(consuming).usePreviousTemplate(true)
                .parameters(parameter -> parameter.parameterKey("Refresh").parameterValue("actual")));
        assertEquals("UPDATE_COMPLETE", terminal(fixture.consumer(), consuming).stackStatusAsString());
        assertParameter(fixture, "vpc-execution-role");
    }

    @Test
    void executingAChangeSetAssociatesAndUsesItsNewExecutionRole() {
        Fixture fixture = fixture(ASSUME_ROLE);
        String replacement = role(fixture.consumerIam(), "replacement", SERVICE_TRUST, ASSUME_ROLE);
        String consuming = create(fixture.consumer(), "consumer", template(fixture, "initial"), fixture.execution());
        assertEquals("CREATE_COMPLETE", terminal(fixture.consumer(), consuming).stackStatusAsString());
        fixture.owner().updateStack(request -> request.stackName(fixture.producer()).templateBody("""
                {"Outputs":{"VpcId":{"Value":"vpc-after-role-change"}}}"""));
        assertEquals("UPDATE_COMPLETE", terminal(fixture.owner(), fixture.producer()).stackStatusAsString());
        fixture.consumer().createChangeSet(request -> request.stackName(consuming).changeSetName("replace-role")
                .changeSetType("UPDATE").usePreviousTemplate(true).roleARN(replacement)
                .parameters(parameter -> parameter.parameterKey("Refresh").parameterValue("replace")));
        assertEquals(fixture.execution(), fixture.consumer().describeStacks(request -> request.stackName(consuming))
                .stacks().getFirst().roleARN());
        fixture.ownerIam().updateAssumeRolePolicy(request -> request.roleName("cfn-role-reader-" + suffix)
                .policyDocument(readerTrust(replacement)));

        fixture.consumer().executeChangeSet(request -> request.stackName(consuming).changeSetName("replace-role"));

        Stack result = terminal(fixture.consumer(), consuming);
        assertEquals("UPDATE_COMPLETE", result.stackStatusAsString(), result.stackStatusReason());
        assertEquals(replacement, result.roleARN());
        assertParameter(fixture, "vpc-after-role-change");
    }

    @Test
    void readerPermissionMustNameTheReferencedStack() {
        Fixture fixture = fixture(ASSUME_ROLE);
        fixture.ownerIam().putRolePolicy(request -> request.roleName("cfn-role-reader-" + suffix)
                .policyName("access").policyDocument("""
                        {"Statement":[{"Effect":"Allow","Action":"cloudformation:DescribeStacks",
                          "Resource":"arn:aws:cloudformation:us-east-1:252525252525:stack/different/*"}]}"""));
        String consuming = create(fixture.consumer(), "wrong-source", template(fixture, "initial"), fixture.execution());

        assertReadDenied(fixture.consumer(), consuming, "cloudformation:DescribeStacks");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void withoutAnExecutionRoleTheActualCallerAndItsSessionPolicyAreRetained(boolean restrictSession) {
        Fixture fixture = fixture(ASSUME_ROLE);
        String caller = role(fixture.consumerIam(), "api-caller", """
                {"Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"AWS":"arn:aws:iam::141414141414:root"}}]}""", """
                {"Statement":[{"Effect":"Allow","Action":"*","Resource":"*"}]}""");
        fixture.ownerIam().updateAssumeRolePolicy(request -> request.roleName("cfn-role-reader-" + suffix)
                .policyDocument(readerTrust(caller)));
        StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials(CONSUMER)).build();
        cleanup.push(sts::close);
        Credentials session = sts.assumeRole(request -> request.roleArn(caller).roleSessionName("stack-operation")
                .policy(restrictSession ? """
                        {"Statement":[{"Effect":"Allow","Action":"cloudformation:*","Resource":"*"}]}""" : null))
                .credentials();
        CloudFormationClient actualCaller = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        session.accessKeyId(), session.secretAccessKey(), session.sessionToken()))).build();
        cleanup.push(actualCaller::close);
        String consuming = create(actualCaller, "caller", template(fixture, "initial"), null);

        if (restrictSession) {
            assertReadDenied(fixture.consumer(), consuming, "sts:AssumeRole");
        } else {
            Stack result = terminal(fixture.consumer(), consuming);
            assertEquals("CREATE_COMPLETE", result.stackStatusAsString(), result.stackStatusReason());
            assertNull(result.roleARN());
            assertParameter(fixture, "vpc-execution-role");
        }
    }

    private Fixture fixture(String executionPolicy) {
        CloudFormationClient consumer = cloudFormation(CONSUMER);
        CloudFormationClient owner = cloudFormation(OWNER);
        IamClient consumerIam = iam(CONSUMER);
        IamClient ownerIam = iam(OWNER);
        String execution = role(consumerIam, "execution", SERVICE_TRUST, executionPolicy);
        String producer = create(owner, "producer", """
                {"Outputs":{"VpcId":{"Value":"vpc-execution-role"}}}""", null);
        assertEquals("CREATE_COMPLETE", terminal(owner, producer).stackStatusAsString());
        String producerId = owner.describeStacks(request -> request.stackName(producer)).stacks().getFirst().stackId();
        String reader = role(ownerIam, "reader", readerTrust(execution), """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Action":"cloudformation:DescribeStacks","Resource":"%s"}]}""".formatted(producerId));
        return new Fixture(consumer, owner, consumerIam, ownerIam, execution, producer, reader,
                "/execution-role/" + suffix);
    }

    private static String readerTrust(String execution) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole",
                  "Condition":{"ArnEquals":{"aws:PrincipalArn":"%s"}}}]}""".formatted(CONSUMER, execution);
    }

    private static String template(Fixture fixture, String refresh) {
        return """
                {"Parameters":{"Refresh":{"Type":"String","Default":"%s"}},
                 "Resources":{"Copy":{"Type":"AWS::SSM::Parameter","Properties":{
                  "Name":"%s","Type":"String","Description":{"Ref":"Refresh"},
                  "Value":{"Fn::GetStackOutput":{
                    "StackName":"%s","OutputName":"VpcId","RoleArn":"%s"}}}}}}
                """.formatted(refresh, fixture.parameter(), fixture.producer(), fixture.reader());
    }

    private void assertParameter(Fixture fixture, String expected) {
        SsmClient ssm = SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials(CONSUMER)).build();
        cleanup.push(ssm::close);
        assertEquals(expected, ssm.getParameter(request -> request.name(fixture.parameter())).parameter().value());
    }

    private static void assertReadDenied(CloudFormationClient client, String name, String action) {
        assertEquals("ROLLBACK_COMPLETE", terminal(client, name).stackStatusAsString());
        assertTrue(client.describeStackEvents(request -> request.stackName(name)).stackEvents().stream()
                .anyMatch(event -> event.resourceStatusReason() != null
                        && event.resourceStatusReason().contains("not authorized to perform: " + action)));
    }

    private String role(IamClient client, String label, String trust, String policy) {
        String name = "cfn-role-" + label + "-" + suffix;
        String arn = client.createRole(request -> request.roleName(name).path("/deployment/")
                .assumeRolePolicyDocument(trust)).role().arn();
        cleanup.push(() -> client.deleteRole(request -> request.roleName(name)));
        client.putRolePolicy(request -> request.roleName(name).policyName("access").policyDocument(policy));
        cleanup.push(() -> client.deleteRolePolicy(request -> request.roleName(name).policyName("access")));
        return arn;
    }

    private String create(CloudFormationClient client, String label, String template, String roleArn) {
        String name = "execution-role-" + label + "-" + suffix;
        client.createStack(request -> request.stackName(name).templateBody(template).roleARN(roleArn));
        cleanup.push(() -> {
            client.deleteStack(request -> request.stackName(name));
            await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                    .until(() -> {
                try {
                    return "DELETE_COMPLETE".equals(client.describeStacks(request -> request.stackName(name))
                            .stacks().getFirst().stackStatusAsString());
                } catch (CloudFormationException failure) {
                    if ("ValidationError".equals(failure.awsErrorDetails().errorCode())
                            && failure.getMessage().contains("does not exist")) {
                        return true;
                    }
                    throw failure;
                }
            });
        });
        return name;
    }

    private static Stack terminal(CloudFormationClient client, String name) {
        return await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .until(
                () -> client.describeStacks(request -> request.stackName(name)).stacks().getFirst(),
                stack -> !stack.stackStatusAsString().endsWith("_IN_PROGRESS"));
    }

    private CloudFormationClient cloudFormation(String account) {
        CloudFormationClient client = CloudFormationClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(credentials(account)).build();
        cleanup.push(client::close);
        return client;
    }

    private IamClient iam(String account) {
        IamClient client = IamClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(credentials(account)).build();
        cleanup.push(client::close);
        return client;
    }

    private static StaticCredentialsProvider credentials(String account) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"));
    }

    private record Fixture(CloudFormationClient consumer, CloudFormationClient owner, IamClient consumerIam,
                           IamClient ownerIam, String execution, String producer, String reader, String parameter) {}
}
