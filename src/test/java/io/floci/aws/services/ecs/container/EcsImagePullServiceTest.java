package io.floci.aws.services.ecs.container;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.AuthConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ecr.EcrService;
import io.floci.aws.services.ecr.model.Repository;
import io.floci.aws.services.ecr.registry.EcrRegistryAuthorization;
import io.floci.aws.services.ecr.registry.EcrWorkloadImagePull;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.services.ecs.model.TaskOverride;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ScpProvider;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.InstanceProfile;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EcsImagePullServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String IMAGE = "localhost:4566/111111111111/us-east-1/image:latest";
    private static final String ROLE = "arn:aws:iam::111111111111:role/execution";
    private static final String TASK = "arn:aws:ecs:us-east-1:111111111111:task/cluster/task";
    private static final String RESOURCE = "arn:aws:ecr:us-east-1:111111111111:repository/image";
    private static final String PROFILE = "arn:aws:iam::111111111111:instance-profile/host";
    private static final String ALLOW = """
            {"Statement":{"Effect":"Allow","Action":["ecr:GetAuthorizationToken","ecr:BatchGetImage",
            "ecr:GetDownloadUrlForLayer","ecr:BatchCheckLayerAvailability"],"Resource":"*"}}
            """;

    private EmulatorConfig config;
    private Ec2Service ec2;
    private IamService iam;
    private RequestContext request;
    private EcrRegistryAuthorization authorization;
    private EcsImagePullService service;
    private EcsTask task;
    private TaskDefinition definition;
    private IamRole executionRole;
    private Function<Supplier<AuthConfig>, String> cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        ec2 = mock(Ec2Service.class);
        iam = mock(IamService.class);
        request = mock(RequestContext.class);
        ObjectMapper mapper = new ObjectMapper();
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        StorageFactory storage = mock(StorageFactory.class);
        doReturn(AccountAwareStorageBackend.<EcrRegistryAuthorization.TokenRecord>inMemory(ACCOUNT))
                .when(storage).create(eq("ecr"), eq("registry-tokens.json"), any());
        authorization = new EcrRegistryAuthorization(storage, iam, evaluator, request, config);
        EcrService ecr = mock(EcrService.class);
        when(ecr.registryRepository("image", ACCOUNT, REGION)).thenReturn(Optional.of(new Repository()));
        // CDI Instance shares its name with the EC2 instance model used below.
        jakarta.enterprise.inject.Instance<ScpProvider> scps = mock(jakarta.enterprise.inject.Instance.class);
        ServiceRoleAuthorizer roles = new ServiceRoleAuthorizer(config, iam, evaluator,
                new AssumeRolePolicyEvaluator(mapper, evaluator), scps);
        service = new EcsImagePullService(config, ec2, iam, roles, new EcrWorkloadImagePull(ecr, authorization));
        executionRole = role("execution", "ecs-tasks.amazonaws.com");
        task = new EcsTask();
        task.setTaskArn(TASK);
        task.setLaunchType(LaunchType.FARGATE);
        definition = new TaskDefinition();
        definition.setExecutionRoleArn(ROLE);
        definition.setTaskRoleArn("arn:aws:iam::111111111111:role/application");
        cache = mock(Function.class);
    }

    @Test
    void fargateUsesExecutionRoleAndRevokesCredentialAfterCachedLaunch() {
        AtomicReference<String> saved = new AtomicReference<>();
        assertEquals("image-id", service.pull(task, definition, null, IMAGE, credentials -> {
            saved.set(header(credentials));
            assertEquals(ROLE, authorization.authenticate(saved.get(), REGION).caller().principalArn());
            return "image-id";
        }));
        assertThrows(AwsException.class, () -> authorization.authenticate(saved.get(), REGION));
        verify(iam, never()).findRole(ACCOUNT, "application");
        verifyNoInteractions(request, ec2);
    }

    @ParameterizedTest
    @EnumSource(value = LaunchType.class, names = {"FARGATE", "EXTERNAL", "MANAGED_INSTANCES"})
    void executionRoleLaunchTypesUseTheirOwnRoleWithoutAnEc2Fallback(LaunchType launchType) {
        task.setLaunchType(launchType);
        assertEquals(ROLE, service.pull(task, definition, null, IMAGE, credentials ->
                authorization.authenticate(header(credentials), REGION).caller().principalArn()));
        verifyNoInteractions(ec2);
    }

    @Test
    void taskExecutionRoleOverrideWinsWithoutChangingDefinition() {
        IamRole override = role("override", "ecs-tasks.amazonaws.com");
        TaskOverride overrides = new TaskOverride();
        overrides.setExecutionRoleArn(override.getArn());
        overrides.setTaskRoleArn("arn:aws:iam::111111111111:role/other-application");
        task.setOverrides(overrides);
        assertEquals(override.getArn(), service.pull(task, definition, null, IMAGE, credentials ->
                authorization.authenticate(header(credentials), REGION).caller().principalArn()));
        assertEquals(ROLE, definition.getExecutionRoleArn());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingExecutionRoleCannotFallBackToApplicationRole(String role) {
        definition.setExecutionRoleArn(role);
        assertEquals("ResourceInitializationError", assertThrows(AwsException.class,
                () -> service.pull(task, definition, null, IMAGE, cache)).getErrorCode());
        verifyNoInteractions(cache);
        verify(iam, never()).findRole(ACCOUNT, "application");
    }

    @Test
    void emptyOverrideCannotFallBackToDefinitionRole() {
        TaskOverride override = new TaskOverride();
        override.setExecutionRoleArn("");
        task.setOverrides(override);
        assertThrows(AwsException.class, () -> service.pull(task, definition, null, IMAGE, cache));
        verifyNoInteractions(cache);
    }

    @Test
    void wrongTrustAndSourceArnConditionsRejectBeforeCache() {
        executionRole.setAssumeRolePolicyDocument(trust("lambda.amazonaws.com"));
        assertThrows(AwsException.class, () -> service.pull(task, definition, null, IMAGE, cache));
        executionRole.setAssumeRolePolicyDocument("""
                {"Statement":{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},
                "Action":"sts:AssumeRole","Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}}
                """.formatted(TASK + "-other"));
        assertThrows(AwsException.class, () -> service.pull(task, definition, null, IMAGE, cache));
        verifyNoInteractions(cache);
        executionRole.setAssumeRolePolicyDocument(executionRole.getAssumeRolePolicyDocument().replace(TASK + "-other", TASK));
        when(cache.apply(any())).thenReturn("cached-image");
        assertEquals("cached-image", service.pull(task, definition, null, IMAGE, cache));
    }

    @Test
    void getAuthorizationTokenPermissionAndRequestedRegionAreCheckedBeforeCache() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW.replace(
                "\"ecr:GetAuthorizationToken\",", ""))));
        assertThrows(AwsException.class, () -> service.pull(task, definition, null, IMAGE, cache));
        verifyNoInteractions(cache);
        String tokenPolicy = """
                {"Statement":{"Effect":"Allow","Action":"ecr:GetAuthorizationToken","Resource":"*",
                "Condition":{"StringEquals":{"aws:RequestedRegion":"%s"}}}}
                """;
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(
                ALLOW.replace("\"ecr:GetAuthorizationToken\",", ""), tokenPolicy.formatted("us-west-2"))));
        assertThrows(AwsException.class, () -> service.pull(task, definition, null, IMAGE, cache));
        verifyNoInteractions(cache);
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(
                ALLOW.replace("\"ecr:GetAuthorizationToken\",", ""), tokenPolicy.formatted(REGION))));
        when(cache.apply(any())).thenReturn("cached-image");
        assertEquals("cached-image", service.pull(task, definition, null, IMAGE, cache));
    }

    @Test
    void ecsLayerHeadKeepsItsOwnPermissionAndRechecksCurrentDenial() {
        assertEquals("done", service.pull(task, definition, null, IMAGE, credentials -> {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(credentials), REGION);
            assertTrue(identity.pullScope().authenticatedPull());
            when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW, """
                    {"Statement":{"Effect":"Deny","Action":"ecr:BatchCheckLayerAvailability","Resource":"*"}}
                    """)));
            assertThrows(AwsException.class,
                    () -> authorization.requirePermission(identity, "ecr:BatchCheckLayerAvailability", RESOURCE, null));
            return "done";
        }));
    }

    @Test
    void ec2UsesCurrentInstanceProfileRoleInsteadOfBothTaskRoles() {
        IamRole hostRole = instanceProfile();
        task.setLaunchType(LaunchType.EC2);
        clearInvocations(iam);
        assertEquals(hostRole.getArn(), service.pull(task, definition, "i-owned", IMAGE, credentials ->
                authorization.authenticate(header(credentials), REGION).caller().principalArn()));
        verify(ec2).findInstanceForAccount(ACCOUNT, REGION, "i-owned");
        verify(iam, never()).findRole(ACCOUNT, "execution");
        verify(iam, never()).findRole(ACCOUNT, "application");
    }

    @Test
    void foreignMissingAndRecreatedInstanceProfilesCannotEnterCache() {
        instanceProfile();
        task.setLaunchType(LaunchType.EC2);
        Instance instance = ec2.findInstanceForAccount(ACCOUNT, REGION, "i-owned").orElseThrow();
        instance.setIamInstanceProfileArn(PROFILE.replace(ACCOUNT, "222222222222"));
        assertThrows(AwsException.class, () -> service.pull(task, definition, "i-owned", IMAGE, cache));
        instance.setIamInstanceProfileArn(PROFILE);
        when(iam.findInstanceProfile(ACCOUNT, "host")).thenReturn(Optional.empty());
        assertThrows(AwsException.class, () -> service.pull(task, definition, "i-owned", IMAGE, cache));
        InstanceProfile replacement = new InstanceProfile("AIPAREPLACED", "host", "/", PROFILE);
        replacement.setRoleNames(List.of("host"));
        when(iam.findInstanceProfile(ACCOUNT, "host")).thenReturn(Optional.of(replacement));
        assertThrows(AwsException.class, () -> service.pull(task, definition, "i-owned", IMAGE, cache));
        verifyNoInteractions(cache);
    }

    @Test
    void unknownEc2InstanceDoesNotFallBackToExecutionRole() {
        task.setLaunchType(LaunchType.EC2);
        assertThrows(AwsException.class, () -> service.pull(task, definition, null, IMAGE, cache));
        assertThrows(AwsException.class, () -> service.pull(task, definition, "i-missing", IMAGE, cache));
        verifyNoInteractions(cache);
    }

    @Test
    void iamOffAndNonEcrImagesKeepConfiguredCredentialPath() {
        when(cache.apply(null)).thenReturn("external-image");
        clearInvocations(iam);
        assertEquals("external-image", service.pull(task, definition, null, "example.invalid/image:1", cache));
        verifyNoInteractions(iam, ec2);
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        definition.setExecutionRoleArn(null);
        assertEquals("external-image", service.pull(task, definition, null, IMAGE, cache));
        verifyNoInteractions(iam, ec2);
    }

    private IamRole instanceProfile() {
        IamRole hostRole = role("host", "ec2.amazonaws.com");
        InstanceProfile profile = new InstanceProfile("AIPAOWNED", "host", "/", PROFILE);
        profile.setRoleNames(List.of("host"));
        when(iam.findInstanceProfile(ACCOUNT, "host")).thenReturn(Optional.of(profile));
        Instance instance = new Instance();
        instance.setInstanceId("i-owned");
        instance.setIamInstanceProfileArn(PROFILE);
        instance.setIamInstanceProfileId("AIPAOWNED");
        when(ec2.findInstanceForAccount(ACCOUNT, REGION, "i-owned")).thenReturn(Optional.of(instance));
        return hostRole;
    }

    private IamRole role(String name, String principal) {
        String arn = ROLE.replace("role/execution", "role/" + name);
        IamRole role = new IamRole("AROA" + name, name, "/", arn, trust(principal));
        when(iam.findRole(ACCOUNT, name)).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(arn)).thenReturn(CallerContext.of(List.of(ALLOW)));
        return role;
    }

    private static String trust(String principal) {
        return """
                {"Statement":{"Effect":"Allow","Principal":{"Service":"%s"},"Action":"sts:AssumeRole"}}
                """.formatted(principal);
    }

    private static String header(Supplier<AuthConfig> credentials) {
        return header(credentials.get());
    }

    private static String header(AuthConfig credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(
                ("AWS:" + credentials.getPassword()).getBytes(StandardCharsets.UTF_8));
    }
}
