package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.Application;
import software.amazon.awssdk.services.appconfig.model.ConfigurationProfileSummary;
import software.amazon.awssdk.services.appconfig.model.DeploymentStrategy;
import software.amazon.awssdk.services.appconfig.model.DeploymentSummary;
import software.amazon.awssdk.services.appconfig.model.Environment;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationProfileResponse;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationResponse;
import software.amazon.awssdk.services.appconfig.model.GetDeploymentResponse;
import software.amazon.awssdk.services.appconfig.model.HostedConfigurationVersionSummary;
import software.amazon.awssdk.services.appconfig.model.ListConfigurationProfilesResponse;
import software.amazon.awssdk.services.appconfig.model.ListDeploymentsResponse;
import software.amazon.awssdk.services.appconfig.model.ListEnvironmentsResponse;
import software.amazon.awssdk.services.appconfig.model.ListHostedConfigurationVersionsResponse;
import software.amazon.awssdk.services.appconfig.model.ResourceNotFoundException;
import software.amazon.awssdk.services.appconfig.model.StartDeploymentResponse;
import software.amazon.awssdk.services.appconfig.model.Validator;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationResponse;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(120)
class AppConfigS3SourceContractIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] FIRST = "{\"revision\":1}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECOND = "{\"revision\":2}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID = "{\"revision\":\"invalid\"}".getBytes(StandardCharsets.UTF_8);
    private static final String SCHEMA = """
            {"type":"object","required":["revision"],"properties":{"revision":{"type":"integer","enum":[1,2]}},"additionalProperties":false}
            """;
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"appconfig.amazonaws.com"},"Action":"sts:AssumeRole"}]}
            """;
    private static final ExecutionAttribute<Integer> WIRE_ID = new ExecutionAttribute<>("appconfig-s3-contract-wire-id");
    private final String marker = "s3-contract-" + UUID.randomUUID();
    private final WireReceipt wire = new WireReceipt();
    private final List<String> sourceVersions = new ArrayList<>();
    private final List<String> roleNames = List.of(marker + "-a", marker + "-b");
    private final List<String> roleArns = new ArrayList<>();
    private final String key = "owned/config.json";
    private AppConfigClient client;
    private AppConfigDataClient data;
    private S3Client s3;
    private IamClient iam;
    private CloudFormationClient cfn;
    private String caseName;
    private String account;
    private String application;
    private String profile;
    private String environment;
    private String strategy;
    private String policy;
    private String stackName;
    private List<Application> initialApplications;
    private List<DeploymentStrategy> initialStrategies;
    private List<Bucket> initialBuckets;
    private List<Role> initialRoles;
    private List<Stack> initialStacks;

    @BeforeEach
    void createIndependentOwnedHierarchy(TestInfo info) throws IOException {
        caseName = info.getTestMethod().orElseThrow().getName();
        account = Long.toString(ThreadLocalRandom.current().nextLong(770000000000L, 779999999999L));
        client = AwsTestClients.create(AppConfigClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, Region.US_EAST_1,
                ApacheHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        data = AwsTestClients.create(AppConfigDataClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, Region.US_EAST_1);
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true).overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, Region.US_EAST_1);
        iam = AwsTestClients.create(IamClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, Region.US_EAST_1);
        cfn = AwsTestClients.create(CloudFormationClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, Region.US_EAST_1);
        initialApplications = applications();
        initialStrategies = strategies();
        initialBuckets = buckets();
        initialRoles = roles();
        initialStacks = stacks();
        assertTrue(initialApplications.isEmpty());
        assertTrue(initialBuckets.stream().noneMatch(value -> marker.equals(value.name())));
        assertTrue(initialRoles.stream().noneMatch(value -> roleNames.contains(value.roleName())));
        assertTrue(initialStacks.stream().noneMatch(value -> value.stackName().startsWith(marker)));
        assertEquals(200, s3.createBucket(request -> request.bucket(marker)).sdkHttpResponse().statusCode());
        s3.putBucketVersioning(request -> request.bucket(marker).expectedBucketOwner(account)
                .versioningConfiguration(config -> config.status("Enabled")));
        for (byte[] content : List.of(FIRST, SECOND, INVALID)) {
            String version = s3.putObject(request -> request.bucket(marker).key(key).expectedBucketOwner(account)
                            .contentType("application/json").serverSideEncryption("AES256")
                            .metadata(Map.of("floci-owner", marker)), RequestBody.fromBytes(content)).versionId();
            assertNotNull(version);
            assertFalse(version.isEmpty() || "null".equals(version) || sourceVersions.contains(version));
            sourceVersions.add(version);
        }
        policy = JSON.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(Map.of(
                "Effect", "Allow", "Action", List.of("s3:GetBucketVersioning", "s3:GetObjectVersion"),
                "Resource", List.of("arn:aws:s3:::" + marker, "arn:aws:s3:::" + marker + "/" + key)))));
        for (String name : roleNames) {
            roleArns.add(iam.createRole(request -> request.roleName(name).path("/floci-s3-contract/")
                    .description(marker).assumeRolePolicyDocument(TRUST)).role().arn());
            iam.putRolePolicy(request -> request.roleName(name).policyName("source").policyDocument(policy));
        }
        application = client.createApplication(request -> request.name(marker).description(marker)).id();
        profile = client.createConfigurationProfile(request -> request.applicationId(application)
                .name(marker + "-primary").description(marker).locationUri(location()).retrievalRoleArn(roleArns.getFirst())
                .validators(Validator.builder().type("JSON_SCHEMA").content(SCHEMA).build())).id();
        environment = client.createEnvironment(request -> request.applicationId(application).name(marker).description(marker)).id();
        strategy = client.createDeploymentStrategy(request -> request.name(marker).description(marker)
                .deploymentDurationInMinutes(0).finalBakeTimeInMinutes(0).growthFactor(100.0F)
                .growthType("LINEAR").replicateTo("NONE")).id();
    }

    @Test
    void s3ProfileDefaultsToFreeformAndUpdatesItsRetrievalRole() {
        GetConfigurationProfileResponse before = getProfile(profile);
        Map<String, AwsResponse> old = snapshot();
        assertEquals(200, client.updateConfigurationProfile(request -> request.applicationId(application)
                .configurationProfileId(profile).name(marker + "-renamed").retrievalRoleArn(roleArns.get(1)))
                .sdkHttpResponse().statusCode());
        GetConfigurationProfileResponse after = getProfile(profile);
        Map<String, AwsResponse> current = snapshot();
        assertAll(() -> assertEquals("AWS.Freeform", before.type()),
                () -> assertEquals(roleArns.getFirst(), before.retrievalRoleArn()),
                () -> assertEquals(roleArns.get(1), after.retrievalRoleArn()),
                () -> assertEquals(marker + "-renamed", after.name()),
                () -> assertEquals(before.validators(), after.validators()),
                () -> assertEquals(before.description(), after.description()),
                () -> assertEquals(before.locationUri(), after.locationUri()),
                () -> assertEquals(withoutProfiles(old), withoutProfiles(current)));
    }

    @Test
    void explicitOlderSourceVersionIsServedWithoutAHostedVersionLabel() {
        StartDeploymentResponse deployment = start(profile, sourceVersions.getFirst());
        GetLatestConfigurationResponse selected = poll(newSession(profile));
        GetConfigurationResponse legacy = legacy(profile, null);
        assertAll(() -> assertEquals(sourceVersions.getFirst(), deployment.configurationVersion()),
                () -> assertEquals(location(), deployment.configurationLocationUri()),
                () -> assertData(selected, FIRST), () -> assertLegacy(legacy, FIRST, sourceVersions.getFirst()));
    }

    @Test
    void schemaValidationChecksTheSelectedVersionWithoutPublishingState() {
        Map<String, AwsResponse> before = snapshot();
        Map<String, Object> sourceBefore = sourceSnapshot();
        Outcome valid = attempt(() -> client.validateConfiguration(request -> request.applicationId(application)
                .configurationProfileId(profile).configurationVersion(sourceVersions.getFirst())));
        Map<String, AwsResponse> afterValid = snapshot();
        Map<String, Object> sourceAfterValid = sourceSnapshot();
        Outcome invalid = attempt(() -> client.validateConfiguration(request -> request.applicationId(application)
                .configurationProfileId(profile).configurationVersion(sourceVersions.get(2))));
        Map<String, AwsResponse> afterInvalid = snapshot();
        Map<String, Object> sourceAfterInvalid = sourceSnapshot();
        assertAll(() -> assertEquals(new Outcome(204, null), valid),
                () -> assertEquals(new Outcome(400, "BadRequestException"), invalid),
                () -> assertEquals(before, afterValid), () -> assertEquals(before, afterInvalid),
                () -> assertEquals(sourceBefore, sourceAfterValid), () -> assertEquals(sourceBefore, sourceAfterInvalid));
    }

    @Test
    void refusedSourcesLeaveThePublishedSelectionAndWholeManagementStateUntouched() {
        start(profile, sourceVersions.getFirst());
        Map<String, AwsResponse> before = snapshot();
        Map<String, Object> sourceBefore = sourceSnapshot();
        Outcome missing = attempt(() -> start(profile, "missing-" + marker));
        Map<String, AwsResponse> afterMissing = snapshot();
        Map<String, Object> sourceAfterMissing = sourceSnapshot();
        Outcome invalid = attempt(() -> start(profile, sourceVersions.get(2)));
        Map<String, AwsResponse> afterInvalid = snapshot();
        Map<String, Object> sourceAfterInvalid = sourceSnapshot();
        GetLatestConfigurationResponse selected = poll(newSession(profile));
        GetConfigurationResponse legacy = legacy(profile, null);
        assertAll(() -> assertEquals(new Outcome(400, "BadRequestException"), missing),
                () -> assertEquals(new Outcome(400, "BadRequestException"), invalid),
                () -> assertEquals(before, afterMissing), () -> assertEquals(before, afterInvalid),
                () -> assertEquals(sourceBefore, sourceAfterMissing), () -> assertEquals(sourceBefore, sourceAfterInvalid),
                () -> assertData(selected, FIRST), () -> assertLegacy(legacy, FIRST, sourceVersions.getFirst()));
    }

    @Test
    void unchangedS3DataOmitsTheLabelAndRotatesTheTokenAtTheReturnedInterval() throws InterruptedException {
        start(profile, sourceVersions.getFirst());
        GetLatestConfigurationResponse first = poll(newSession(profile));
        int interval = first.nextPollIntervalInSeconds();
        assertTrue(interval >= 15 && interval <= 60);
        Thread.sleep(Duration.ofSeconds(interval).plusMillis(100));
        GetLatestConfigurationResponse unchanged = poll(first.nextPollConfigurationToken());
        GetConfigurationResponse legacy = legacy(profile, sourceVersions.getFirst());
        assertAll(() -> assertData(first, FIRST), () -> assertData(unchanged, new byte[0]),
                () -> assertFalse(first.nextPollConfigurationToken().equals(unchanged.nextPollConfigurationToken())),
                () -> assertLegacy(legacy, new byte[0], sourceVersions.getFirst()));
    }

    @Test
    void admittedBytesSurviveSourceDeletionAndCompletedRevert() {
        start(profile, sourceVersions.getFirst());
        StartDeploymentResponse second = start(profile, sourceVersions.get(1));
        GetDeploymentResponse completed = getDeployment(second.deploymentNumber());
        assertEquals("COMPLETE", completed.stateAsString());
        Map<String, AwsResponse> beforeDeletion = snapshot();
        for (String version : sourceVersions.subList(0, 2)) {
            assertEquals(204, s3.deleteObject(request -> request.bucket(marker).key(key).versionId(version)
                    .expectedBucketOwner(account)).sdkHttpResponse().statusCode());
        }
        assertEquals(List.of(sourceVersions.get(2)), objectVersions().stream().map(ObjectVersion::versionId).toList());
        Map<String, AwsResponse> afterDeletion = snapshot();
        GetLatestConfigurationResponse retained = poll(newSession(profile));
        GetConfigurationResponse retainedLegacy = legacy(profile, null);
        assertEquals(202, client.stopDeployment(request -> request.applicationId(application).environmentId(environment)
                .deploymentNumber(second.deploymentNumber()).allowRevert(true)).sdkHttpResponse().statusCode());
        GetDeploymentResponse reverted = getDeployment(second.deploymentNumber());
        GetLatestConfigurationResponse previous = poll(newSession(profile));
        GetConfigurationResponse previousLegacy = legacy(profile, null);
        assertAll(() -> assertEquals(beforeDeletion, afterDeletion),
                () -> assertData(retained, SECOND), () -> assertLegacy(retainedLegacy, SECOND, sourceVersions.get(1)),
                () -> assertEquals("REVERTED", reverted.stateAsString()),
                () -> assertEquals(completed.startedAt(), reverted.startedAt()),
                () -> assertEquals(completed.completedAt(), reverted.completedAt()),
                () -> assertData(previous, FIRST), () -> assertLegacy(previousLegacy, FIRST, sourceVersions.getFirst()));
    }

    @Test
    void cloudFormationCreatesAnS3ProfileThatCanSelectAnExistingVersion() throws IOException, InterruptedException {
        String selectedProfile = createStack(roleArns.getFirst());
        GetConfigurationProfileResponse created = getProfile(selectedProfile);
        StartDeploymentResponse deployment = start(selectedProfile, sourceVersions.getFirst());
        GetLatestConfigurationResponse selected = poll(newSession(selectedProfile));
        GetConfigurationResponse legacy = legacy(selectedProfile, null);
        assertAll(() -> assertEquals(roleArns.getFirst(), created.retrievalRoleArn()),
                () -> assertEquals(location(), created.locationUri()),
                () -> assertEquals(selectedProfile, deployment.configurationProfileId()),
                () -> assertData(selected, FIRST), () -> assertLegacy(legacy, FIRST, sourceVersions.getFirst()));
    }

    @Test
    void cloudFormationUpdatesTheRoleWithoutReplacingItsProfile() throws IOException, InterruptedException {
        String original = createStack(roleArns.getFirst());
        cfn.updateStack(request -> request.stackName(stackName).templateBody(template(roleArns.get(1), marker + "-updated")));
        awaitStack("UPDATE_COMPLETE");
        String current = stackProfile();
        GetConfigurationProfileResponse updated = getProfile(current);
        assertAll(() -> assertEquals(original, current),
                () -> assertEquals(roleArns.get(1), updated.retrievalRoleArn()),
                () -> assertEquals(marker + "-updated", updated.name()),
                () -> assertEquals(marker, updated.description()), () -> assertEquals(location(), updated.locationUri()));
    }

    private String location() {
        return "s3://" + marker + "/" + key;
    }

    private String template(String role, String name) {
        try {
            return JSON.writeValueAsString(Map.of("Resources", Map.of("Profile", Map.of(
                    "Type", "AWS::AppConfig::ConfigurationProfile", "Properties", Map.of(
                            "ApplicationId", application, "Name", name, "Description", marker,
                            "LocationUri", location(), "RetrievalRoleArn", role)))));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private String createStack(String role) throws InterruptedException {
        stackName = marker + "-stack";
        cfn.createStack(request -> request.stackName(stackName).templateBody(template(role, marker + "-cfn")));
        awaitStack("CREATE_COMPLETE");
        return stackProfile();
    }

    private String stackProfile() {
        return cfn.describeStackResource(request -> request.stackName(stackName).logicalResourceId("Profile"))
                .stackResourceDetail().physicalResourceId();
    }

    private void awaitStack(String expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Stack stack = cfn.describeStacks(request -> request.stackName(stackName)).stacks().getFirst();
            if (expected.equals(stack.stackStatusAsString())) {
                return;
            }
            assertTrue(stack.stackStatusAsString().endsWith("_IN_PROGRESS"), stack.stackStatusAsString());
            Thread.sleep(100);
        }
        throw new AssertionError("Owned stack did not reach " + expected);
    }

    private GetConfigurationProfileResponse getProfile(String selected) {
        return client.getConfigurationProfile(request -> request.applicationId(application).configurationProfileId(selected));
    }

    private GetDeploymentResponse getDeployment(int number) {
        return client.getDeployment(request -> request.applicationId(application).environmentId(environment).deploymentNumber(number));
    }

    private StartDeploymentResponse start(String selected, String version) {
        return client.startDeployment(request -> request.applicationId(application).environmentId(environment)
                .configurationProfileId(selected).configurationVersion(version).deploymentStrategyId(strategy).description(marker));
    }

    private String newSession(String selected) {
        return data.startConfigurationSession(request -> request.applicationIdentifier(application).environmentIdentifier(environment)
                .configurationProfileIdentifier(selected).requiredMinimumPollIntervalInSeconds(15)).initialConfigurationToken();
    }

    private GetLatestConfigurationResponse poll(String token) {
        return data.getLatestConfiguration(request -> request.configurationToken(token));
    }

    private GetConfigurationResponse legacy(String selected, String version) {
        return client.getConfiguration(request -> request.application(application).environment(environment)
                .configuration(selected).clientId(marker).clientConfigurationVersion(version));
    }

    private static void assertData(GetLatestConfigurationResponse response, byte[] expected) {
        assertAll(() -> assertEquals(200, response.sdkHttpResponse().statusCode()),
                () -> assertArrayEquals(expected, response.configuration().asByteArray()),
                () -> assertEquals("application/json", response.contentType()),
                () -> assertNull(response.versionLabel()),
                () -> assertTrue(response.sdkHttpResponse().firstMatchingHeader("version-label").isEmpty()),
                () -> assertNotNull(response.nextPollConfigurationToken()));
    }

    private static void assertLegacy(GetConfigurationResponse response, byte[] expected, String version) {
        assertAll(() -> assertEquals(200, response.sdkHttpResponse().statusCode()),
                () -> assertArrayEquals(expected, response.content().asByteArray()),
                () -> assertEquals("application/json", response.contentType()),
                () -> assertEquals(version, response.configurationVersion()),
                () -> assertEquals(version, response.sdkHttpResponse().firstMatchingHeader("configuration-version").orElseThrow()));
    }

    private static Outcome attempt(Supplier<? extends AwsResponse> action) {
        try {
            return new Outcome(action.get().sdkHttpResponse().statusCode(), null);
        } catch (AppConfigException error) {
            return new Outcome(error.statusCode(), error.awsErrorDetails().errorCode());
        }
    }

    private static AwsResponse modeled(AwsResponse response) {
        AwsResponse.Builder builder = response.toBuilder();
        builder.sdkHttpResponse(null);
        builder.responseMetadata(null);
        AwsResponse result = builder.build();
        assertTrue(response.equalsBySdkFields(result));
        return result;
    }

    private Map<String, AwsResponse> snapshot() {
        int firstCall = wire.calls.size();
        Map<String, AwsResponse> result = new LinkedHashMap<>();
        result.put("application", modeled(client.getApplication(request -> request.applicationId(application))));
        result.put("strategy", modeled(client.getDeploymentStrategy(request -> request.deploymentStrategyId(strategy))));
        int profilePage = 0;
        for (ListConfigurationProfilesResponse page : client.listConfigurationProfilesPaginator(request ->
                request.applicationId(application).maxResults(50))) {
            result.put("profiles/" + profilePage++, modeled(page));
            for (ConfigurationProfileSummary item : page.items()) {
                result.put("profile/" + item.id(), modeled(getProfile(item.id())));
                int hostedPage = 0;
                for (ListHostedConfigurationVersionsResponse hosted : client.listHostedConfigurationVersionsPaginator(request ->
                        request.applicationId(application).configurationProfileId(item.id()).maxResults(50))) {
                    result.put("hosted/" + item.id() + "/" + hostedPage++, modeled(hosted));
                    for (HostedConfigurationVersionSummary version : hosted.items()) {
                        result.put("hosted-version/" + item.id() + "/" + version.versionNumber(),
                                modeled(client.getHostedConfigurationVersion(request -> request.applicationId(application)
                                        .configurationProfileId(item.id()).versionNumber(version.versionNumber()))));
                    }
                }
            }
        }
        int environmentPage = 0;
        for (ListEnvironmentsResponse page : client.listEnvironmentsPaginator(request ->
                request.applicationId(application).maxResults(50))) {
            result.put("environments/" + environmentPage++, modeled(page));
            for (Environment item : page.items()) {
                result.put("environment/" + item.id(), modeled(client.getEnvironment(request ->
                        request.applicationId(application).environmentId(item.id()))));
                int deploymentPage = 0;
                for (ListDeploymentsResponse deployments : client.listDeploymentsPaginator(request ->
                        request.applicationId(application).environmentId(item.id()).maxResults(50))) {
                    result.put("deployments/" + item.id() + "/" + deploymentPage++, modeled(deployments));
                    for (DeploymentSummary selected : deployments.items()) {
                        result.put("deployment/" + item.id() + "/" + selected.deploymentNumber(), modeled(client.getDeployment(request ->
                                request.applicationId(application).environmentId(item.id()).deploymentNumber(selected.deploymentNumber()))));
                    }
                }
            }
        }
        wire.snapshots.add(Map.of("kind", "appconfig", "firstCall", firstCall, "lastCall", wire.calls.size() - 1));
        return result;
    }

    private Map<String, Object> sourceSnapshot() {
        int firstCall = wire.calls.size();
        Map<String, Object> result = new LinkedHashMap<>();
        int pageNumber = 0;
        for (ListObjectVersionsResponse page : s3.listObjectVersionsPaginator(request -> request.bucket(marker)
                .expectedBucketOwner(account).maxKeys(50))) {
            result.put("page/" + pageNumber++, modeled(page));
            assertTrue(page.deleteMarkers().isEmpty());
            for (ObjectVersion version : page.versions()) {
                ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(request -> request.bucket(marker)
                        .key(version.key()).versionId(version.versionId()).expectedBucketOwner(account));
                result.put("metadata/" + version.versionId(), modeled(object.response()));
                result.put("content/" + version.versionId(), Base64.getEncoder().encodeToString(object.asByteArray()));
            }
        }
        wire.snapshots.add(Map.of("kind", "s3", "firstCall", firstCall, "lastCall", wire.calls.size() - 1));
        return result;
    }

    private static Map<String, AwsResponse> withoutProfiles(Map<String, AwsResponse> input) {
        Map<String, AwsResponse> result = new LinkedHashMap<>(input);
        result.keySet().removeIf(key -> key.startsWith("profile/") || key.startsWith("profiles/"));
        return result;
    }

    private List<Application> applications() {
        List<Application> result = new ArrayList<>();
        client.listApplicationsPaginator(request -> request.maxResults(50)).forEach(page -> result.addAll(page.items()));
        return result.stream().sorted(Comparator.comparing(Application::id)).toList();
    }

    private List<DeploymentStrategy> strategies() {
        List<DeploymentStrategy> result = new ArrayList<>();
        client.listDeploymentStrategiesPaginator(request -> request.maxResults(50)).forEach(page -> result.addAll(page.items()));
        return result.stream().sorted(Comparator.comparing(DeploymentStrategy::id)).toList();
    }

    private List<Bucket> buckets() {
        List<Bucket> result = new ArrayList<>();
        s3.listBucketsPaginator().forEach(page -> result.addAll(page.buckets()));
        return result.stream().sorted(Comparator.comparing(Bucket::name)).toList();
    }

    private List<Role> roles() {
        List<Role> result = new ArrayList<>();
        iam.listRolesPaginator(request -> request.maxItems(50)).forEach(page -> result.addAll(page.roles()));
        return result.stream().sorted(Comparator.comparing(Role::roleId)).toList();
    }

    private List<Stack> stacks() {
        List<Stack> result = new ArrayList<>();
        cfn.describeStacksPaginator().forEach(page -> result.addAll(page.stacks()));
        return result.stream().sorted(Comparator.comparing(Stack::stackId)).toList();
    }

    private List<ObjectVersion> objectVersions() {
        List<ObjectVersion> result = new ArrayList<>();
        for (ListObjectVersionsResponse page : s3.listObjectVersionsPaginator(request -> request.bucket(marker)
                .expectedBucketOwner(account).maxKeys(50))) {
            assertTrue(page.deleteMarkers().isEmpty());
            result.addAll(page.versions());
        }
        return result.stream().sorted(Comparator.comparing(ObjectVersion::versionId)).toList();
    }

    @AfterEach
    void removeEveryOwnedResourceAndRetainCompleteCapture() {
        List<Executable> failures = new ArrayList<>();
        if (client != null) {
            cleanupAttempt(failures, () -> {
                for (Application app : applications()) {
                    if (marker.equals(app.name()) && marker.equals(app.description())) {
                        cleanupAttempt(failures, () -> removeApplication(app.id(), failures));
                    }
                }
            });
            cleanupAttempt(failures, () -> {
                for (DeploymentStrategy item : strategies()) {
                    if (marker.equals(item.name()) && marker.equals(item.description())) {
                        cleanupAttempt(failures, () -> {
                            assertEquals(204, client.deleteDeploymentStrategy(request -> request.deploymentStrategyId(item.id())).sdkHttpResponse().statusCode());
                            assertEquals(404, assertThrows(ResourceNotFoundException.class, () -> client.getDeploymentStrategy(request ->
                                    request.deploymentStrategyId(item.id()))).statusCode());
                        });
                    }
                }
            });
        }
        if (s3 != null && initialBuckets != null) {
            cleanupAttempt(failures, () -> {
                if (buckets().stream().anyMatch(value -> marker.equals(value.name()))) {
                    for (ObjectVersion version : objectVersions()) {
                        cleanupAttempt(failures, () -> {
                            assertEquals(key, version.key());
                            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(request -> request.bucket(marker)
                                    .key(key).versionId(version.versionId()).expectedBucketOwner(account));
                            assertEquals(Map.of("floci-owner", marker), object.response().metadata());
                            assertEquals("application/json", object.response().contentType());
                            assertEquals("AES256", object.response().serverSideEncryptionAsString());
                            String content = Base64.getEncoder().encodeToString(object.asByteArray());
                            assertTrue(List.of(FIRST, SECOND, INVALID).stream()
                                    .map(bytes -> Base64.getEncoder().encodeToString(bytes)).anyMatch(content::equals));
                            assertEquals(204, s3.deleteObject(request -> request.bucket(marker).key(key)
                                    .versionId(version.versionId()).expectedBucketOwner(account)).sdkHttpResponse().statusCode());
                            assertEquals(404, assertThrows(S3Exception.class, () -> s3.getObjectAsBytes(request ->
                                    request.bucket(marker).key(key).versionId(version.versionId()).expectedBucketOwner(account))).statusCode());
                        });
                    }
                    assertTrue(objectVersions().isEmpty());
                    assertEquals(204, s3.deleteBucket(request -> request.bucket(marker).expectedBucketOwner(account)).sdkHttpResponse().statusCode());
                    assertEquals(404, assertThrows(S3Exception.class, () -> s3.getBucketLocation(request ->
                            request.bucket(marker).expectedBucketOwner(account))).statusCode());
                }
            });
        }
        if (iam != null && initialRoles != null) {
            cleanupAttempt(failures, () -> {
                for (Role role : roles()) {
                    if (roleNames.contains(role.roleName())) {
                        cleanupAttempt(failures, () -> removeRole(role));
                    }
                }
            });
        }
        if (initialApplications != null) {
            cleanupAttempt(failures, () -> assertEquals(initialApplications, applications()));
        }
        if (initialStrategies != null) {
            cleanupAttempt(failures, () -> assertEquals(initialStrategies, strategies()));
        }
        if (initialBuckets != null) {
            cleanupAttempt(failures, () -> assertEquals(initialBuckets, buckets()));
        }
        if (initialRoles != null) {
            cleanupAttempt(failures, () -> assertEquals(initialRoles, roles()));
        }
        if (initialStacks != null) {
            cleanupAttempt(failures, () -> assertEquals(initialStacks, stacks()));
        }
        cleanupAttempt(failures, wire::save);
        if (cfn != null) {
            cleanupAttempt(failures, cfn::close);
        }
        if (s3 != null) {
            cleanupAttempt(failures, s3::close);
        }
        if (iam != null) {
            cleanupAttempt(failures, iam::close);
        }
        if (data != null) {
            cleanupAttempt(failures, data::close);
        }
        if (client != null) {
            cleanupAttempt(failures, client::close);
        }
        assertAll("Owned children, typed absence, complete inventories and capture", failures);
    }

    private void removeApplication(String owner, List<Executable> failures) {
        cleanupAttempt(failures, () -> client.listEnvironmentsPaginator(request -> request.applicationId(owner).maxResults(50)).forEach(page -> {
            for (Environment item : page.items()) {
                cleanupAttempt(failures, () -> {
                    assertEquals(marker, item.name());
                    assertEquals(marker, item.description());
                    assertEquals(204, client.deleteEnvironment(request -> request.applicationId(owner)
                            .environmentId(item.id()).deletionProtectionCheck("BYPASS")).sdkHttpResponse().statusCode());
                    assertEquals(404, assertThrows(ResourceNotFoundException.class, () -> client.getEnvironment(request ->
                            request.applicationId(owner).environmentId(item.id()))).statusCode());
                });
            }
        }));
        if (stackName != null) {
            cleanupAttempt(failures, () -> {
                List<Stack> owned = stacks().stream()
                        .filter(value -> stackName.equals(value.stackName())).toList();
                for (Stack stack : owned) {
                    cleanupAttempt(failures, () -> {
                        assertTrue(stack.stackName().startsWith(marker));
                        cfn.deleteStack(request -> request.stackName(stack.stackId()));
                        awaitDeletedStack(stack.stackId());
                        awaitStackNameAbsence(stack.stackId());
                    });
                }
            });
        }
        cleanupAttempt(failures, () -> client.listConfigurationProfilesPaginator(request -> request.applicationId(owner).maxResults(50)).forEach(page -> {
            for (ConfigurationProfileSummary item : page.items()) {
                cleanupAttempt(failures, () -> {
                    assertTrue(item.id().equals(profile) || item.name().startsWith(marker));
                    assertEquals(marker, client.getConfigurationProfile(request -> request.applicationId(owner)
                            .configurationProfileId(item.id())).description());
                    assertFalse(client.listHostedConfigurationVersionsPaginator(request -> request.applicationId(owner)
                            .configurationProfileId(item.id())).items().iterator().hasNext());
                    assertEquals(204, client.deleteConfigurationProfile(request -> request.applicationId(owner)
                            .configurationProfileId(item.id()).deletionProtectionCheck("BYPASS")).sdkHttpResponse().statusCode());
                    assertEquals(404, assertThrows(ResourceNotFoundException.class, () -> client.getConfigurationProfile(request ->
                            request.applicationId(owner).configurationProfileId(item.id()))).statusCode());
                });
            }
        }));
        cleanupAttempt(failures, () -> {
            assertTrue(client.listConfigurationProfilesPaginator(request -> request.applicationId(owner)).items().stream().findAny().isEmpty());
            assertTrue(client.listEnvironmentsPaginator(request -> request.applicationId(owner)).items().stream().findAny().isEmpty());
            assertEquals(204, client.deleteApplication(request -> request.applicationId(owner)).sdkHttpResponse().statusCode());
            assertEquals(404, assertThrows(ResourceNotFoundException.class, () -> client.getApplication(request -> request.applicationId(owner))).statusCode());
        });
    }

    private void awaitDeletedStack(String stackId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Stack stack = cfn.describeStacks(request -> request.stackName(stackId)).stacks().getFirst();
            if ("DELETE_COMPLETE".equals(stack.stackStatusAsString())) {
                return;
            }
            assertEquals("DELETE_IN_PROGRESS", stack.stackStatusAsString());
            Thread.sleep(100);
        }
        throw new AssertionError("Owned stack did not reach DELETE_COMPLETE");
    }

    private void awaitStackNameAbsence(String stackId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                List<Stack> found = cfn.describeStacks(request -> request.stackName(stackName)).stacks();
                assertEquals(1, found.size());
                assertEquals(stackId, found.getFirst().stackId());
                assertEquals("DELETE_COMPLETE", found.getFirst().stackStatusAsString());
            } catch (CloudFormationException absent) {
                assertEquals(400, absent.statusCode());
                assertEquals("ValidationError", absent.awsErrorDetails().errorCode());
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Owned deleted stack name did not become absent");
    }

    private void removeRole(Role role) throws IOException {
        assertEquals("/floci-s3-contract/", role.path());
        assertEquals(marker, iam.getRole(request -> request.roleName(role.roleName())).role().description());
        List<String> names = new ArrayList<>();
        iam.listRolePoliciesPaginator(request -> request.roleName(role.roleName()).maxItems(50)).forEach(page -> names.addAll(page.policyNames()));
        assertTrue(names.isEmpty() || names.equals(List.of("source")));
        for (String name : names) {
            String document = iam.getRolePolicy(request -> request.roleName(role.roleName()).policyName(name)).policyDocument();
            assertEquals(JSON.readTree(policy), JSON.readTree(document.startsWith("{") ? document : URLDecoder.decode(document, StandardCharsets.UTF_8)));
            iam.deleteRolePolicy(request -> request.roleName(role.roleName()).policyName(name));
            assertEquals(404, assertThrows(NoSuchEntityException.class, () -> iam.getRolePolicy(request ->
                    request.roleName(role.roleName()).policyName(name))).statusCode());
        }
        assertFalse(iam.listAttachedRolePoliciesPaginator(request -> request.roleName(role.roleName())).attachedPolicies().iterator().hasNext());
        assertFalse(iam.listRolePoliciesPaginator(request -> request.roleName(role.roleName())).policyNames().iterator().hasNext());
        iam.deleteRole(request -> request.roleName(role.roleName()));
        assertEquals(404, assertThrows(NoSuchEntityException.class, () -> iam.getRole(request -> request.roleName(role.roleName()))).statusCode());
    }

    private static void cleanupAttempt(List<Executable> failures, Executable action) {
        try {
            action.execute();
        } catch (Throwable failure) {
            failures.add(() -> { throw failure; });
        }
    }

    private record Outcome(int status, String code) {}

    private final class WireReceipt implements ExecutionInterceptor {
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private final List<Map<String, Object>> snapshots = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            assertTrue(calls.size() < 2000);
            int index = calls.size();
            attributes.putAttribute(WIRE_ID, index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index);
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            Map<String, List<String>> headers = new LinkedHashMap<>(context.httpRequest().headers());
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("authorization") || name.equalsIgnoreCase("x-amz-security-token"));
            row.put("requestHeaders", headers);
            try (InputStream stream = context.requestBody().isPresent()
                    ? context.requestBody().orElseThrow().contentStreamProvider().newStream() : InputStream.nullInputStream()) {
                row.put("requestBase64", encode(stream));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            calls.add(row);
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context, ExecutionAttributes attributes) {
            Map<String, Object> row = calls.get(attributes.getAttribute(WIRE_ID));
            row.put("status", context.httpResponse().statusCode());
            row.put("responseHeaders", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElseGet(InputStream::nullInputStream)) {
                String encoded = encode(stream);
                row.put("responseBase64", encoded);
                return Optional.of(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        private String encode(InputStream stream) throws IOException {
            byte[] bytes = stream.readNBytes(262145);
            assertTrue(bytes.length <= 262144);
            return Base64.getEncoder().encodeToString(bytes);
        }

        private void save() throws IOException {
            Path directory = Path.of(System.getProperty("audit.appconfig.s3-source.directory", "target/s3-source-wire"));
            Files.createDirectories(directory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(),
                    Map.of("case", caseName, "marker", marker, "calls", calls, "snapshots", snapshots));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")));
        }
    }
}
