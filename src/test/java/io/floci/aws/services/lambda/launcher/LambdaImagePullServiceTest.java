package io.floci.aws.services.lambda.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.AuthConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.ecr.EcrService;
import io.floci.aws.services.ecr.model.Repository;
import io.floci.aws.services.ecr.registry.EcrRegistryAuthorization;
import io.floci.aws.services.ecr.registry.EcrWorkloadImagePull;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ScpProvider;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.testing.InMemoryStorageFactory;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LambdaImagePullServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::111111111111:role/pull";
    private static final String SOURCE = "arn:aws:lambda:us-east-1:111111111111:function:image";
    private static final String IMAGE = "111111111111.dkr.ecr.us-east-1.amazonaws.com/image:latest";
    private static final String RESOURCE = "arn:aws:ecr:us-east-1:111111111111:repository/image";
    private static final String READ = """
            {"Statement":{"Effect":"Allow","Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer"],"Resource":"*"}}
            """;
    private static final String SERVICE = """
            {"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":["ecr:BatchGetImage",
            "ecr:GetDownloadUrlForLayer"],"Resource":"*","Condition":{"ArnLike":{"aws:sourceARN":
            "arn:aws:lambda:us-east-1:111111111111:function:image*"}}}
            """;
    private EmulatorConfig config;
    private IamService iam;
    private EcrService ecr;
    private RequestContext request;
    private Repository repository;
    private IamRole role;
    private LambdaFunction function;
    private EcrRegistryAuthorization auth;
    private LambdaImagePullService pulls;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iam = mock(IamService.class);
        ecr = mock(EcrService.class);
        request = mock(RequestContext.class);
        ObjectMapper mapper = new ObjectMapper();
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);
        role = new IamRole("AROAPULL", "pull", "/", ROLE, """
                {"Statement":{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}}
                """);
        when(iam.findRole(ACCOUNT, "pull")).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(READ)));
        Instance<ScpProvider> scps = mock(Instance.class);
        ServiceRoleAuthorizer roles = new ServiceRoleAuthorizer(config, iam, evaluator,
                new AssumeRolePolicyEvaluator(mapper, evaluator), scps);
        auth = new EcrRegistryAuthorization(new InMemoryStorageFactory(ACCOUNT), iam, evaluator, request, config);
        pulls = new LambdaImagePullService(config, roles, new EcrWorkloadImagePull(ecr, auth));
        repository = new Repository();
        repository.setRepositoryName("image");
        when(ecr.registryRepository(eq("image"), anyString(), eq(REGION))).thenReturn(Optional.of(repository));
        function = new LambdaFunction();
        function.setAccountId(ACCOUNT);
        function.setFunctionArn(SOURCE);
        function.setRole(ROLE);
        function.setPackageType("Image");
        function.setImageUri(IMAGE);
    }

    @Test
    void sameAccountExecutionRoleReadGrantNeedsNeitherRepositoryGrantNorAuthorizationTokenPermission() {
        assertPulled(IMAGE);
        verifyNoInteractions(request);
    }

    @Test
    void sameAccountServiceGrantWorksWithoutExecutionRoleEcrPermissions() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        repository.setRepositoryPolicyText(policy(SERVICE));
        assertPulled(IMAGE);
    }

    @Test
    void serviceGrantIsEvaluatedAsAServiceRatherThanAsTheExecutionRole() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of("""
                {"Statement":{"Effect":"Deny","Action":"ecr:*","Resource":"*"}}
                """)));
        repository.setRepositoryPolicyText(policy(SERVICE));
        assertPulled(IMAGE);
    }

    @Test
    void serviceGrantCannotSatisfyConditionsThatNameAnIamPrincipalArn() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        repository.setRepositoryPolicyText(policy(SERVICE.replace("\"ArnLike\":",
                "\"ArnEquals\":{\"aws:PrincipalArn\":\"" + ROLE + "\"},\"ArnLike\":")));
        assertDenied(IMAGE);
    }

    @Test
    void serviceSourceConditionsUseTheOwnedFunctionIncludingItsQualifier() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        repository.setRepositoryPolicyText(policy(SERVICE));
        function.setFunctionArn(SOURCE + ":7");
        assertPulled(IMAGE);
        function.setFunctionArn(SOURCE.replace("function:image", "function:other"));
        assertDenied(IMAGE);
    }

    @Test
    void rolePolicyDoesNotReceiveInventedServiceSourceConditions() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(READ.replace(
                "\"Resource\":\"*\"", "\"Resource\":\"*\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\""
                        + SOURCE + "\"}}"))));
        assertDenied(IMAGE);
    }

    @Test
    void crossAccountNeedsRoleOwnerGrantAndLambdaServiceGrant() {
        String foreign = IMAGE.replace(ACCOUNT, "222222222222");
        repository.setRepositoryPolicyText(policy(SERVICE));
        assertDenied(foreign);
        String account = """
                {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::111111111111:root"},"Action":["ecr:BatchGetImage",
                "ecr:GetDownloadUrlForLayer"],"Resource":"*"}
                """;
        repository.setRepositoryPolicyText(policy(account));
        assertDenied(foreign);
        repository.setRepositoryPolicyText(policy(account + "," + SERVICE));
        assertPulled(foreign);
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        assertDenied(foreign);
    }

    @Test
    void universalRepositoryDenyBlocksBothGrantPaths() {
        repository.setRepositoryPolicyText(policy(SERVICE + """
                ,{"Effect":"Deny","Principal":"*","Action":"ecr:*","Resource":"*"}
                """));
        assertDenied(IMAGE);
    }

    @Test
    void wireRequestsReevaluateCurrentRepositoryPolicyWithoutCachedGrant() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        repository.setRepositoryPolicyText(policy(SERVICE));
        pulls.pull(function, IMAGE, credentials -> {
            EcrRegistryAuthorization.Identity identity = auth.authenticate(header(credentials), REGION);
            assertDoesNotThrow(() -> auth.requirePermission(identity, "ecr:BatchGetImage",
                    RESOURCE, repository.getRepositoryPolicyText()));
            repository.setRepositoryPolicyText(null);
            assertThrows(AwsException.class, () -> auth.requirePermission(identity, "ecr:BatchGetImage", RESOURCE, null));
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ecr:PutImage", "ecr:ListImages", "ecr:InitiateLayerUpload"})
    void lambdaLeaseNeverAllowsWritesOrListing(String action) {
        pulls.pull(function, IMAGE, credentials -> {
            EcrRegistryAuthorization.Identity identity = auth.authenticate(header(credentials), REGION);
            assertThrows(AwsException.class, () -> auth.requirePermission(identity, action, RESOURCE, null));
            return null;
        });
    }

    @Test
    void sourceRegionAndStoredOwnerMustMatchTheImageAndFunction() {
        function.setFunctionArn(SOURCE.replace(REGION, "us-west-2"));
        assertDenied(IMAGE);
        function.setFunctionArn(SOURCE);
        function.setAccountId("222222222222");
        assertDenied(IMAGE);
    }

    @Test
    void roleMustStillExistAndTrustLambdaEvenWithServiceRepositoryGrant() {
        repository.setRepositoryPolicyText(policy(SERVICE));
        role.setAssumeRolePolicyDocument(role.getAssumeRolePolicyDocument().replace("lambda.amazonaws.com", "ecs-tasks.amazonaws.com"));
        assertDenied(IMAGE);
        when(iam.findRole(ACCOUNT, "pull")).thenReturn(Optional.empty());
        assertDenied(IMAGE);
    }

    @Test
    void iamOffAndNonEcrPreserveTheOriginalCredentialPath() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        assertEquals("original", pulls.pull(function, IMAGE, credentials -> {
            assertNull(credentials);
            return "original";
        }));
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        assertNull(pulls.pull(function, "example.invalid/image:1", credentials -> credentials));
        verifyNoInteractions(iam, ecr, request);
    }

    private void assertPulled(String image) {
        AtomicReference<String> issued = new AtomicReference<>();
        assertEquals("pulled", pulls.pull(function, image, credentials -> {
            assertNotNull(credentials);
            issued.set(header(credentials));
            EcrRegistryAuthorization.Identity identity = auth.authenticate(issued.get(), REGION);
            String resource = RESOURCE.replace(ACCOUNT, image.substring(0, 12));
            assertDoesNotThrow(() -> auth.requirePermission(identity,
                    "ecr:BatchGetImage", resource, repository.getRepositoryPolicyText()));
            assertDoesNotThrow(() -> auth.requirePermission(identity,
                    "ecr:BatchCheckLayerAvailability", resource, repository.getRepositoryPolicyText()));
            return "pulled";
        }));
        assertThrows(AwsException.class, () -> auth.authenticate(issued.get(), REGION));
    }

    private void assertDenied(String image) {
        assertThrows(AwsException.class, () -> pulls.pull(function, image, credentials -> fail("Denied before cache access")));
    }

    private static String policy(String statements) {
        return "{\"Statement\":[" + statements + "]}";
    }

    private static String header(AuthConfig credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(
                ("AWS:" + credentials.getPassword()).getBytes(StandardCharsets.UTF_8));
    }
}
