package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.iam.IamClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class Ec2LaunchAuthorizationIamIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "arn:aws:ec2:us-east-1:000000000000:";
    private static final String IMAGE = "ami-0abcdef1234567890";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @InjectSpy
    Ec2Service service;

    @Test
    void scopedResourcesAndTemplateConditionAuthorizeTheSelectedLaunch() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(launchGrant(fixture), pass(fixture)));
             Ec2Client actor = client(user.accessKeyId(), user.secretAccessKey())) {
            assertDoesNotThrow(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1"))));
            assertDoesNotThrow(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateName(fixture.name()).version("1"))));
            denied(() -> launch(fixture, actor, request -> request.imageId(IMAGE).instanceType("t3.micro")
                    .subnetId(fixture.subnet()).securityGroupIds(fixture.group())));
        }
    }

    @Test
    void templateGrantCannotAuthorizeAnotherTemplateOrOverriddenResources() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        try (SignedIamUsers.User user = user(List.of(launchGrant(fixture), pass(fixture), pass(other)));
             Ec2Client actor = client(user.accessKeyId(), user.secretAccessKey())) {
            denied(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(other.template()).version("1"))));
            denied(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1")).imageId("ami-0fedcba9876543210")));
            denied(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1"))
                    .networkInterfaces(network -> network.deviceIndex(0).subnetId(other.subnet()).groups(other.group()))));
            assertDoesNotThrow(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1"))
                    .networkInterfaces(network -> network.deviceIndex(0).subnetId(fixture.subnet()).groups(fixture.group()))));
        }
    }

    @Test
    void explicitProfileOverrideRequiresOnlyTheProfileActuallyUsed() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        try (SignedIamUsers.User user = user(List.of(launchGrant(fixture), pass(fixture)));
             Ec2Client actor = client(user.accessKeyId(), user.secretAccessKey())) {
            denied(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1"))
                    .iamInstanceProfile(profile -> profile.arn(other.profileArn()))));
            SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of(
                    "UserName", user.name(), "PolicyName", "launch",
                    "PolicyDocument", policy(List.of(launchGrant(fixture), pass(other))))).then().statusCode(200);
            String instance = assertDoesNotThrow(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1"))
                    .iamInstanceProfile(profile -> profile.name(other.name()))));
            assertEquals(other.profileArn(), fixture.admin().describeInstances(request -> request.instanceIds(instance))
                    .reservations().getFirst().instances().getFirst().iamInstanceProfile().arn());
            denied(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1"))));
        }
    }

    @Test
    void selectedTemplateVersionDeterminesWhichRoleIsPassed() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        fixture.admin().createLaunchTemplateVersion(request -> request.launchTemplateId(fixture.template())
                .sourceVersion("1").launchTemplateData(data -> data.iamInstanceProfile(
                        profile -> profile.arn(other.profileArn()))));
        fixture.admin().modifyLaunchTemplate(request -> request.launchTemplateId(fixture.template()).defaultVersion("2"));
        try (SignedIamUsers.User user = user(List.of(launchGrant(fixture), pass(fixture)));
             Ec2Client actor = client(user.accessKeyId(), user.secretAccessKey())) {
            for (String version : List.of("2", "$Default", "$Latest")) {
                denied(() -> launch(fixture, actor, request -> request.launchTemplate(
                        template -> template.launchTemplateId(fixture.template()).version(version))));
            }
            denied(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateName(fixture.name()))));
            assertDoesNotThrow(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1"))));
        }
    }

    @Test
    void concurrentDefaultChangeCannotReplaceTheTemplateThatPassedAuthorization() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        fixture.admin().createLaunchTemplateVersion(request -> request.launchTemplateId(fixture.template())
                .sourceVersion("1").launchTemplateData(data -> data.iamInstanceProfile(
                        profile -> profile.arn(other.profileArn()))));
        doAnswer(invocation -> {
            LaunchTemplate selected = (LaunchTemplate) invocation.callRealMethod();
            fixture.admin().modifyLaunchTemplate(request ->
                    request.launchTemplateId(fixture.template()).defaultVersion("2"));
            return selected;
        }).when(service).launchTemplateForAuthorization("us-east-1", fixture.template(), null, "$Default");
        try (SignedIamUsers.User user = user(List.of(launchGrant(fixture), pass(fixture)));
             Ec2Client actor = client(user.accessKeyId(), user.secretAccessKey())) {
            String instance = launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("$Default")));
            assertEquals(fixture.profileArn(), fixture.admin().describeInstances(
                            request -> request.instanceIds(instance))
                    .reservations().getFirst().instances().getFirst().iamInstanceProfile().arn());
        } finally {
            reset(service);
        }
    }

    @Test
    void repeatedAuthorizedClientTokenReturnsTheOriginalInstance() throws Exception {
        Fixture fixture = fixture();
        String token = UUID.randomUUID().toString();
        try (SignedIamUsers.User user = user(List.of(launchGrant(fixture), pass(fixture)));
             Ec2Client actor = client(user.accessKeyId(), user.secretAccessKey())) {
            Consumer<RunInstancesRequest.Builder> request = value -> value.clientToken(token).launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1"));
            String first = launch(fixture, actor, request);
            assertEquals(first, launch(fixture, actor, request));
        }
    }

    @Test
    void templateLaunchRequiresTagPermissionForItsRequestedInstanceTags() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(launchGrant(fixture), pass(fixture)));
             Ec2Client actor = client(user.accessKeyId(), user.secretAccessKey())) {
            Consumer<RunInstancesRequest.Builder> selector = request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1"))
                    .tagSpecifications(spec -> spec.resourceType("instance")
                            .tags(tag -> tag.key("workflow").value(fixture.name())));
            denied(() -> launch(fixture, actor, selector));
            Map<String, Object> tagGrant = Map.of(
                    "Effect", "Allow", "Action", "ec2:CreateTags", "Resource", PREFIX + "instance/*",
                    "Condition", Map.of("StringEquals", Map.of("ec2:CreateAction", "RunInstances")));
            SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of(
                    "UserName", user.name(), "PolicyName", "launch",
                    "PolicyDocument", policy(List.of(launchGrant(fixture), pass(fixture), tagGrant))))
                    .then().statusCode(200);
            String instance = assertDoesNotThrow(() -> launch(fixture, actor, selector));
            assertEquals(fixture.name(), fixture.admin().describeInstances(request -> request.instanceIds(instance))
                    .reservations().getFirst().instances().getFirst().tags().stream()
                    .filter(tag -> "workflow".equals(tag.key())).findFirst().orElseThrow().value());
        }
    }

    @Test
    void inheritedInstanceProfileRequiresPassRole() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> run = Map.of("Effect", "Allow", "Action", "ec2:RunInstances", "Resource", "*");
        try (SignedIamUsers.User user = user(List.of(run));
             Ec2Client actor = client(user.accessKeyId(), user.secretAccessKey())) {
            denied(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateId(fixture.template()).version("1"))));
            SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of(
                    "UserName", user.name(), "PolicyName", "launch",
                    "PolicyDocument", policy(List.of(run, pass(fixture))))).then().statusCode(200);
            String instance = assertDoesNotThrow(() -> launch(fixture, actor, request -> request.launchTemplate(
                    template -> template.launchTemplateName(fixture.name()).version("1"))));
            assertEquals(fixture.profileArn(), fixture.admin().describeInstances(request -> request.instanceIds(instance))
                    .reservations().getFirst().instances().getFirst().iamInstanceProfile().arn());
        }
    }

    @Test
    void scopedTerminationRejectsAMixedOwnershipBatchBeforeChangingInstances() throws Exception {
        Fixture fixture = fixture();
        String owned = launch(fixture, fixture.admin(), request -> request.launchTemplate(
                template -> template.launchTemplateId(fixture.template()).version("1")));
        String other = launch(fixture, fixture.admin(), request -> request.launchTemplate(
                template -> template.launchTemplateId(fixture.template()).version("1")));
        fixture.admin().createTags(request -> request.resources(owned)
                .tags(tag -> tag.key("workflow").value(fixture.name())));
        Map<String, Object> grant = Map.of(
                "Effect", "Allow", "Action", "ec2:TerminateInstances", "Resource", PREFIX + "instance/*",
                "Condition", Map.of("StringEquals", Map.of("aws:ResourceTag/workflow", fixture.name())));
        try (SignedIamUsers.User user = user(List.of(grant));
             Ec2Client actor = client(user.accessKeyId(), user.secretAccessKey())) {
            denied(() -> actor.terminateInstances(request -> request.instanceIds(owned, other)));
            assertEquals("running", state(fixture, owned));
            assertEquals("running", state(fixture, other));
            assertDoesNotThrow(() -> actor.terminateInstances(request -> request.instanceIds(owned)));
            assertEquals("terminated", state(fixture, owned));
            denied(() -> actor.terminateInstances(request -> request.instanceIds(other)));
            assertEquals("running", state(fixture, other));
        }
    }

    @Test
    void terminationPairsEachResourceWithItsOwnTagGrant() throws Exception {
        Fixture fixture = fixture();
        String first = launch(fixture, fixture.admin(), request -> request.launchTemplate(
                template -> template.launchTemplateId(fixture.template()).version("1")));
        String second = launch(fixture, fixture.admin(), request -> request.launchTemplate(
                template -> template.launchTemplateId(fixture.template()).version("1")));
        fixture.admin().createTags(request -> request.resources(first).tags(tag -> tag.key("owner").value("first")));
        fixture.admin().createTags(request -> request.resources(second).tags(tag -> tag.key("owner").value("second")));
        Map<String, Object> firstGrant = Map.of(
                "Effect", "Allow", "Action", "ec2:TerminateInstances", "Resource", PREFIX + "instance/" + first,
                "Condition", Map.of("StringEquals", Map.of("aws:ResourceTag/owner", "first")));
        Map<String, Object> secondGrant = Map.of(
                "Effect", "Allow", "Action", "ec2:TerminateInstances", "Resource", PREFIX + "instance/" + second,
                "Condition", Map.of("StringEquals", Map.of("aws:ResourceTag/owner", "second")));
        try (SignedIamUsers.User user = user(List.of(firstGrant, secondGrant));
             Ec2Client actor = client(user.accessKeyId(), user.secretAccessKey())) {
            assertDoesNotThrow(() -> actor.terminateInstances(request -> request.instanceIds(first, second)));
            assertEquals("terminated", state(fixture, first));
            assertEquals("terminated", state(fixture, second));
        }
    }

    private static Map<String, Object> launchGrant(Fixture fixture) {
        return Map.of(
                "Effect", "Allow", "Action", "ec2:RunInstances",
                "Resource", List.of("arn:aws:ec2:us-east-1::image/" + IMAGE,
                        PREFIX + "instance/*", PREFIX + "volume/*", PREFIX + "network-interface/*",
                        PREFIX + "subnet/" + fixture.subnet(), PREFIX + "security-group/" + fixture.group()),
                "Condition", Map.of("ArnEquals",
                        Map.of("ec2:LaunchTemplate", PREFIX + "launch-template/" + fixture.template())));
    }

    private static String state(Fixture fixture, String instance) {
        return fixture.admin().describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().state().nameAsString();
    }

    private static Map<String, Object> pass(Fixture fixture) {
        return Map.of("Effect", "Allow", "Action", "iam:PassRole", "Resource", fixture.roleArn(),
                "Condition", Map.of("StringEquals", Map.of("iam:PassedToService", "ec2.amazonaws.com")));
    }

    private static void denied(Executable operation) {
        Ec2Exception failure = assertThrows(Ec2Exception.class, operation);
        assertEquals(403, failure.statusCode());
    }

    private String launch(Fixture fixture, Ec2Client actor, Consumer<RunInstancesRequest.Builder> selector) {
        RunInstancesRequest.Builder request = RunInstancesRequest.builder().minCount(1).maxCount(1);
        selector.accept(request);
        String instance = actor.runInstances(request.build()).instances().getFirst().instanceId();
        cleanup.register(() -> fixture.admin().terminateInstances(value -> value.instanceIds(instance)));
        return instance;
    }

    private SignedIamUsers.User user(List<Map<String, Object>> statements) throws Exception {
        return SignedIamUsers.createUser(endpoint, "launch-", "launch", policy(statements));
    }

    private static String policy(List<Map<String, Object>> statements) throws Exception {
        return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements));
    }

    private Fixture fixture() {
        String name = "launch-" + UUID.randomUUID();
        Ec2Client admin = client("test", "test");
        cleanup.register(admin::close);
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        cleanup.register(iam::close);
        String role = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"Service":"ec2.amazonaws.com"}}]}""")).role().arn();
        cleanup.register(() -> iam.deleteRole(request -> request.roleName(name)));
        String profile = iam.createInstanceProfile(request -> request.instanceProfileName(name)).instanceProfile().arn();
        cleanup.register(() -> iam.deleteInstanceProfile(request -> request.instanceProfileName(name)));
        iam.addRoleToInstanceProfile(request -> request.instanceProfileName(name).roleName(name));
        cleanup.register(() -> iam.removeRoleFromInstanceProfile(request -> request.instanceProfileName(name).roleName(name)));
        String vpc = admin.createVpc(request -> request.cidrBlock("10.88.0.0/16")).vpc().vpcId();
        cleanup.register(() -> admin.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = admin.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.88.1.0/24")
                .availabilityZone("us-east-1a")).subnet().subnetId();
        cleanup.register(() -> admin.deleteSubnet(request -> request.subnetId(subnet)));
        String group = admin.createSecurityGroup(request -> request.vpcId(vpc).groupName(name).description(name)).groupId();
        cleanup.register(() -> admin.deleteSecurityGroup(request -> request.groupId(group)));
        String template = admin.createLaunchTemplate(request -> request.launchTemplateName(name).launchTemplateData(data ->
                data.imageId(IMAGE).instanceType("t3.micro").iamInstanceProfile(value -> value.name(name))
                        .networkInterfaces(value -> value.deviceIndex(0).subnetId(subnet).groups(group)
                                .deleteOnTermination(true)))).launchTemplate().launchTemplateId();
        cleanup.register(() -> admin.deleteLaunchTemplate(request -> request.launchTemplateId(template)));
        return new Fixture(admin, name, template, subnet, group, role, profile);
    }

    private Ec2Client client(String access, String secret) {
        return Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .build();
    }

    private record Fixture(Ec2Client admin, String name, String template, String subnet,
                           String group, String roleArn, String profileArn) {
    }
}
