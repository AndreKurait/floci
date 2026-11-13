package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.ConfigurationProfileSummary;
import software.amazon.awssdk.services.appconfig.model.GetApplicationResponse;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationProfileResponse;
import software.amazon.awssdk.services.appconfig.model.GetHostedConfigurationVersionResponse;
import software.amazon.awssdk.services.appconfig.model.HostedConfigurationVersionSummary;
import software.amazon.awssdk.services.appconfig.model.ListApplicationsRequest;
import software.amazon.awssdk.services.appconfig.model.ListApplicationsResponse;
import software.amazon.awssdk.services.appconfig.model.ListConfigurationProfilesResponse;
import software.amazon.awssdk.services.appconfig.model.ListHostedConfigurationVersionsResponse;
import software.amazon.awssdk.services.appconfig.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourceResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesResponse;
import software.amazon.awssdk.services.cloudformation.model.ListStackResourcesResponse;
import software.amazon.awssdk.services.cloudformation.model.ListStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.ListStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cloudformation.model.StackResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(120)
class CloudFormationAppConfigHostedPublicIdIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-cfn-hosted-public-id-wire-id");
    private final String marker = UUID.randomUUID().toString();
    private final WireReceipt wire = new WireReceipt();
    private final List<String> profiles = new ArrayList<>();
    private final List<String> stacks = new ArrayList<>();
    private final Map<String, String> expectedContent = new LinkedHashMap<>();
    private final Map<String, String> stackIds = new LinkedHashMap<>();
    private AppConfigClient appconfig;
    private CloudFormationClient cfn;
    private String application;
    private String profile;
    private String independent;
    private int changes;

    @BeforeEach
    void createOwnedExternalParents() {
        String account = Long.toString(ThreadLocalRandom.current().nextLong(780000000000L, 789999999999L));
        appconfig = AwsTestClients.create(AppConfigClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        cfn = AwsTestClients.create(CloudFormationClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        assertNoApplications();
        assertEquals(List.of(), activeStacks());
        application = appconfig.createApplication(request -> request.name("cfn-hosted-" + marker)
                .description(marker)).id();
        profile = newProfile("primary");
        independent = newProfile("independent");
    }

    private String newProfile(String suffix) {
        String id = appconfig.createConfigurationProfile(request -> request.applicationId(application)
                .name(marker + "-" + suffix).description(marker).locationUri("hosted").type("AWS.Freeform")).id();
        profiles.add(id);
        return id;
    }

    @Test
    void allResourceViewsExposeNumericIdsWithoutChangingReferencesOrState() throws Exception {
        String stack = create(template(profile, "release-a", 0, "first"));
        Snapshot before = snapshot();
        List<String> ids = responseIds(stack, "Config");
        assertEquals(before, snapshot());
        assertVersion(stack, profile, 1, "release-a", "first");
        assertEquals(List.of("1", "1", "1"), ids);
    }

    @Test
    void createEventsProjectOnlyTheHostedResourceAndKeepStackIds() throws Exception {
        String stack = create(template(profile, null, null, "first"));
        Snapshot before = snapshot();
        DescribeStackEventsResponse response = cfn.describeStackEvents(request -> request.stackName(stack));
        assertNull(response.nextToken());
        assertEquals(before, snapshot());
        List<String> hostedIds = response.stackEvents().stream()
                .filter(event -> "Config".equals(event.logicalResourceId()))
                .map(StackEvent::physicalResourceId)
                .filter(value -> value != null && !value.isEmpty()).distinct().sorted().toList();
        List<String> stackEventIds = response.stackEvents().stream()
                .filter(event -> "AWS::CloudFormation::Stack".equals(event.resourceType()))
                .map(StackEvent::physicalResourceId)
                .filter(value -> value != null && !value.isEmpty()).distinct().toList();
        assertAll(() -> assertEquals(List.of("1"), hostedIds),
                () -> assertEquals(List.of(stackIds.get(stack)), stackEventIds));
    }

    @Test
    void replacementPreviewProjectsTheOldIdWithoutMutatingHostedState() throws Exception {
        ObjectNode template = template(profile, null, null, "first");
        String stack = create(template);
        properties(template).put("Content", content("replacement"));
        properties(template).put("Description", description("replacement"));
        Snapshot before = snapshot();
        String change = preview(stack, template);
        DescribeChangeSetResponse response = cfn.describeChangeSet(request -> request.stackName(stack).changeSetName(change));
        assertNull(response.nextToken());
        assertEquals(before, snapshot());
        List<String> ids = response.changes().stream().filter(value ->
                        "Config".equals(value.resourceChange().logicalResourceId()))
                .map(value -> value.resourceChange().physicalResourceId()).toList();
        assertEquals(List.of("1"), ids);
    }

    @Test
    void replacementAndDeletionKeepDistinctInternalOwnershipAcrossProfiles() throws Exception {
        ObjectNode template = template(profile, null, null, "first");
        String stack = create(template);
        String other = create(template(independent, "independent", 0, "independent"));
        Snapshot before = snapshot();
        properties(template).put("Content", content("replacement"));
        properties(template).put("Description", description("replacement"));
        execute(stack, preview(stack, template), "UPDATE_COMPLETE");
        List<String> replacementIds = responseIds(stack, "Config");
        assertVersion(stack, profile, 2, null, "replacement");
        assertAbsentVersion(profile, 1);
        removeStack(stack);
        assertEquals(List.of(), versions(profile));
        assertEquals(before.profiles().get(independent), snapshot().profiles().get(independent));
        assertVersion(other, independent, 1, "independent", "independent");
        DescribeStackEventsResponse events = cfn.describeStackEvents(request -> request.stackName(stackIds.get(stack)));
        assertNull(events.nextToken());
        List<String> eventIds = events.stackEvents().stream().filter(event ->
                        "Config".equals(event.logicalResourceId()))
                .map(StackEvent::physicalResourceId)
                .filter(value -> value != null && !value.isEmpty()).distinct().sorted().toList();
        assertAll(() -> assertEquals(List.of("2", "2", "2"), replacementIds),
                () -> assertEquals(List.of("1", "2"), eventIds));
    }

    @Test
    void independentProfilesSharePublicVersionOneAndDeleteIndependently() throws Exception {
        String first = create(template(profile, null, null, "first"));
        String second = create(template(independent, null, null, "second"));
        assertNotEquals(profile, independent);
        List<String> ids = List.of(physicalId(first), physicalId(second));
        Snapshot before = snapshot();
        removeStack(first);
        assertEquals(List.of(), versions(profile));
        assertEquals(before.profiles().get(independent), snapshot().profiles().get(independent));
        assertVersion(second, independent, 1, null, "second");
        assertEquals(List.of("1", "1"), ids);
    }

    @Test
    void retentionPreservesBothVersionsWhilePublicIdsRemainNumeric() throws Exception {
        ObjectNode template = template(profile, "first", 0, "first");
        ((ObjectNode) template.path("Resources").path("Config"))
                .put("DeletionPolicy", "Retain").put("UpdateReplacePolicy", "Retain");
        String stack = create(template);
        properties(template).put("Content", content("second"));
        properties(template).put("Description", description("second"));
        properties(template).put("VersionLabel", "second");
        properties(template).put("LatestVersionNumber", 1);
        execute(stack, preview(stack, template), "UPDATE_COMPLETE");
        List<String> ids = responseIds(stack, "Config");
        Snapshot before = snapshot();
        removeStack(stack);
        assertEquals(before, snapshot());
        assertEquals(List.of(1, 2), versionNumbers(profile));
        assertEquals(List.of("2", "2", "2"), ids);
    }

    @Test
    void failedReplacementRestoresTheNumericIdAndWholeHostedState() throws Exception {
        ObjectNode template = template(profile, null, null, "first");
        String stack = create(template);
        Snapshot before = snapshot();
        Map<String, String> originalOutputs = outputs(stack);
        properties(template).put("Content", content("replacement"));
        properties(template).put("Description", description("replacement"));
        ((ObjectNode) template.path("Resources")).set("Invalid", JSON.valueToTree(Map.of(
                "Type", "AWS::AppConfig::HostedConfigurationVersion", "DependsOn", "Config",
                "Properties", Map.of("ApplicationId", application, "ConfigurationProfileId", profile,
                        "Content", "missing required content type"))));
        execute(stack, preview(stack, template), "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(before, snapshot());
        assertEquals(originalOutputs, outputs(stack));
        assertAbsentVersion(profile, 2);
        assertEquals(List.of("1", "1", "1"), responseIds(stack, "Config"));
    }

    @Test
    void ordinaryApplicationResourceIdsKeepTheirDefaultRepresentation() throws Exception {
        ObjectNode template = JSON.valueToTree(Map.of("Resources", Map.of("Application", Map.of(
                "Type", "AWS::AppConfig::Application", "Properties", Map.of(
                        "Name", "cfn-public-id-" + marker, "Description", marker))),
                "Outputs", Map.of("Application", Map.of("Value", Map.of("Ref", "Application")))));
        String stack = create(template);
        String id = outputs(stack).get("Application");
        Snapshot before = snapshot();
        assertEquals(marker, appconfig.getApplication(request -> request.applicationId(id)).description());
        List<String> ids = responseIds(stack, "Application");
        assertEquals(before, snapshot());
        assertEquals(List.of(id, id, id), ids);
        removeStack(stack);
        ResourceNotFoundException missing = assertThrows(ResourceNotFoundException.class,
                () -> appconfig.getApplication(request -> request.applicationId(id)));
        assertEquals(404, missing.statusCode());
    }

    private List<String> responseIds(String stack, String logicalId) {
        DescribeStackResourcesResponse described = cfn.describeStackResources(request -> request.stackName(stack));
        ListStackResourcesResponse listed = cfn.listStackResources(request -> request.stackName(stack));
        assertNull(listed.nextToken());
        DescribeStackResourceResponse detail = cfn.describeStackResource(request -> request.stackName(stack).logicalResourceId(logicalId));
        return List.of(described.stackResources().stream().filter(value -> logicalId.equals(value.logicalResourceId()))
                        .map(StackResource::physicalResourceId).findFirst().orElseThrow(),
                listed.stackResourceSummaries().stream().filter(value -> logicalId.equals(value.logicalResourceId()))
                        .map(value -> value.physicalResourceId()).findFirst().orElseThrow(),
                detail.stackResourceDetail().physicalResourceId());
    }

    private ObjectNode template(String selected, String label, Integer latest, String text) {
        ObjectNode value = JSON.valueToTree(Map.of("Resources", Map.of("Config", Map.of(
                "Type", "AWS::AppConfig::HostedConfigurationVersion", "Properties", Map.of(
                        "ApplicationId", application, "ConfigurationProfileId", selected, "Content", content(text),
                        "ContentType", "text/plain", "Description", description(text)))),
                "Outputs", Map.of("RefVersion", Map.of("Value", Map.of("Ref", "Config")),
                        "AttributeVersion", Map.of("Value", Map.of("Fn::GetAtt", List.of("Config", "VersionNumber"))),
                        "SubVersion", Map.of("Value", Map.of("Fn::Sub", "v-${Config}")))));
        if (label != null) {
            properties(value).put("VersionLabel", label);
        }
        if (latest != null) {
            properties(value).put("LatestVersionNumber", latest);
        }
        return value;
    }

    private static ObjectNode properties(ObjectNode template) {
        return (ObjectNode) template.path("Resources").path("Config").path("Properties");
    }

    private String content(String suffix) {
        String value = "café λ " + marker + " " + suffix;
        expectedContent.put(description(suffix), value);
        return value;
    }

    private String description(String suffix) {
        return marker + ":" + suffix;
    }

    private String reserveStack() {
        String name = "hosted-" + marker + "-" + stacks.size();
        stacks.add(name);
        return name;
    }

    private String create(ObjectNode template) throws Exception {
        String name = reserveStack();
        stackIds.put(name, cfn.createStack(request -> request.stackName(name).templateBody(template.toString())
                .tags(tag -> tag.key("cfn-hosted-owner").value(marker))).stackId());
        Stack stack = awaitStack(name, value -> !value.stackStatusAsString().endsWith("_IN_PROGRESS"));
        assertEquals("CREATE_COMPLETE", stack.stackStatusAsString(), stack.stackStatusReason());
        return name;
    }

    private String createChangeSet(String stack, ObjectNode template) throws Exception {
        String name = "change-" + (++changes);
        cfn.createChangeSet(request -> request.stackName(stack).changeSetName(name).changeSetType("UPDATE")
                .templateBody(template.toString()).description(marker));
        for (int attempt = 0; attempt < 100; attempt++) {
            DescribeChangeSetResponse response = cfn.describeChangeSet(request ->
                    request.stackName(stack).changeSetName(name));
            if (!response.statusAsString().endsWith("_IN_PROGRESS")
                    && !response.statusAsString().endsWith("_PENDING")) {
                assertEquals("CREATE_COMPLETE", response.statusAsString(), response.statusReason());
                return name;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Change-set preparation exceeded its bounded wait");
    }

    private String preview(String stack, ObjectNode template) throws Exception {
        String name = createChangeSet(stack, template);
        DescribeChangeSetResponse response = cfn.describeChangeSet(request ->
                request.stackName(stack).changeSetName(name));
        List<String> replacements = response.changes().stream().filter(change ->
                        "Config".equals(change.resourceChange().logicalResourceId()))
                .map(change -> change.resourceChange().replacementAsString()).toList();
        assertEquals(List.of("True"), replacements);
        return name;
    }

    private void execute(String stack, String change, String expected) throws Exception {
        cfn.executeChangeSet(request -> request.stackName(stack).changeSetName(change));
        Stack result = awaitStack(stack, value -> !value.stackStatusAsString().endsWith("_IN_PROGRESS"));
        assertEquals(expected, result.stackStatusAsString(), result.stackStatusReason());
    }

    private Stack awaitStack(String name, Predicate<Stack> done) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            Stack stack = cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst();
            assertEquals(name, stack.stackName());
            assertTrue(stack.tags().stream().anyMatch(tag ->
                    "cfn-hosted-owner".equals(tag.key()) && marker.equals(tag.value())));
            String captured = stackIds.putIfAbsent(name, stack.stackId());
            if (captured != null) {
                assertEquals(captured, stack.stackId());
            }
            if (done.test(stack)) {
                return stack;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Stack operation exceeded its bounded wait: " + name);
    }

    private Map<String, String> outputs(String stack) {
        return cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst().outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private String physicalId(String stack) {
        return cfn.describeStackResources(request -> request.stackName(stack)).stackResources().stream()
                .filter(resource -> resource.logicalResourceId().equals("Config"))
                .map(StackResource::physicalResourceId).findFirst().orElseThrow();
    }

    private void assertVersion(String stack, String selected, int number, String label, String suffix) {
        Map<String, String> outputs = outputs(stack);
        GetHostedConfigurationVersionResponse value = getVersion(selected, number);
        assertAll(() -> assertEquals(Integer.toString(number), outputs.get("RefVersion")),
                () -> assertEquals(Integer.toString(number), outputs.get("AttributeVersion")),
                () -> assertEquals("v-" + number, outputs.get("SubVersion")),
                () -> assertEquals(application, value.applicationId()),
                () -> assertEquals(selected, value.configurationProfileId()),
                () -> assertEquals(number, value.versionNumber()),
                () -> assertEquals(label, value.versionLabel()),
                () -> assertEquals(content(suffix), value.content().asUtf8String()),
                () -> assertEquals(description(suffix), value.description()),
                () -> assertEquals("text/plain", value.contentType()));
    }

    private int directCreate(String selected, String label, String suffix) {
        return appconfig.createHostedConfigurationVersion(request -> request.applicationId(application)
                .configurationProfileId(selected).content(SdkBytes.fromUtf8String(content(suffix)))
                .contentType("text/plain").description(description(suffix)).versionLabel(label)).versionNumber();
    }

    private GetHostedConfigurationVersionResponse getVersion(String selected, int version) {
        return appconfig.getHostedConfigurationVersion(request -> request.applicationId(application)
                .configurationProfileId(selected).versionNumber(version));
    }

    private List<HostedConfigurationVersionSummary> versions(String selected) {
        // Each owned profile has at most three versions; a next token would invalidate this complete snapshot.
        ListHostedConfigurationVersionsResponse response = appconfig.listHostedConfigurationVersions(request ->
                request.applicationId(application).configurationProfileId(selected).maxResults(50));
        assertNull(response.nextToken());
        return response.items().stream()
                .sorted(Comparator.comparingInt(HostedConfigurationVersionSummary::versionNumber)).toList();
    }

    private List<Integer> versionNumbers(String selected) {
        return versions(selected).stream().map(HostedConfigurationVersionSummary::versionNumber).toList();
    }

    private Snapshot snapshot() {
        GetApplicationResponse applicationResponse = appconfig.getApplication(request -> request.applicationId(application));
        GetApplicationResponse.Builder applicationState = applicationResponse.toBuilder();
        applicationState.sdkHttpResponse(null);
        applicationState.responseMetadata(null);
        assertTrue(applicationResponse.equalsBySdkFields(applicationState.build()));
        Map<String, ProfileState> state = new LinkedHashMap<>();
        for (String selected : profiles) {
            GetConfigurationProfileResponse metadata = appconfig.getConfigurationProfile(request ->
                    request.applicationId(application).configurationProfileId(selected));
            GetConfigurationProfileResponse.Builder modeled = metadata.toBuilder();
            modeled.sdkHttpResponse(null);
            modeled.responseMetadata(null);
            assertTrue(metadata.equalsBySdkFields(modeled.build()));
            List<HostedConfigurationVersionSummary> summaries = versions(selected);
            List<StoredVersion> full = new ArrayList<>();
            for (HostedConfigurationVersionSummary summary : summaries) {
                GetHostedConfigurationVersionResponse version = getVersion(selected, summary.versionNumber());
                full.add(new StoredVersion(version.applicationId(), version.configurationProfileId(), version.versionNumber(),
                        version.description(), version.contentType(), version.versionLabel(), version.kmsKeyArn(),
                        Base64.getEncoder().encodeToString(version.content().asByteArray())));
            }
            state.put(selected, new ProfileState(modeled.build(), summaries, full));
        }
        ListConfigurationProfilesResponse collection = appconfig.listConfigurationProfiles(request ->
                request.applicationId(application));
        assertNull(collection.nextToken());
        List<ConfigurationProfileSummary> allProfiles = collection.items().stream()
                .sorted(Comparator.comparing(ConfigurationProfileSummary::id)).toList();
        return new Snapshot(applicationState.build(), allProfiles, state);
    }

    private List<String> activeStacks() {
        ListStacksResponse response = cfn.listStacks(ListStacksRequest.builder().build());
        assertNull(response.nextToken());
        return response.stackSummaries().stream()
                .filter(stack -> !"DELETE_COMPLETE".equals(stack.stackStatusAsString()))
                .map(stack -> stack.stackId()).sorted().toList();
    }

    private void assertNoApplications() {
        ListApplicationsResponse response = appconfig.listApplications(ListApplicationsRequest.builder().build());
        assertNull(response.nextToken());
        assertEquals(List.of(), response.items());
    }

    private void assertAbsentVersion(String selected, int number) {
        ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class, () -> getVersion(selected, number));
        assertEquals(404, error.statusCode());
    }

    private void deleteVersion(String selected, int number) {
        assertEquals(204, appconfig.deleteHostedConfigurationVersion(request -> request.applicationId(application)
                .configurationProfileId(selected).versionNumber(number)).sdkHttpResponse().statusCode());
        assertAbsentVersion(selected, number);
    }

    private void removeStack(String name) throws Exception {
        try {
            Stack stack = awaitStack(name, value -> !value.stackStatusAsString().endsWith("_IN_PROGRESS"));
            if ("DELETE_COMPLETE".equals(stack.stackStatusAsString())) {
                return;
            }
            cfn.deleteStack(request -> request.stackName(name));
            awaitStack(name, value -> "DELETE_COMPLETE".equals(value.stackStatusAsString()));
        } catch (CloudFormationException error) {
            assertEquals(400, error.statusCode());
            assertEquals("ValidationError", error.awsErrorDetails().errorCode());
            assertTrue(error.getMessage().contains("does not exist"), "Only typed stack absence permits parent cleanup");
        }
    }

    @AfterEach
    void removeCapturedOwnedStacksBeforeExternalParents() {
        List<Executable> failures = new ArrayList<>();
        try {
            if (cfn != null) {
                for (String stack : stacks.reversed()) {
                    cleanupAttempt(failures, () -> removeStack(stack));
                }
            }
            if (failures.isEmpty() && appconfig != null && application != null) {
                cleanupAttempt(failures, () -> {
                    assertEquals(marker, appconfig.getApplication(request -> request.applicationId(application)).description());
                    assertEquals("cfn-hosted-" + marker,
                            appconfig.getApplication(request -> request.applicationId(application)).name());
                });
                if (failures.isEmpty()) {
                    for (String selected : profiles) {
                        cleanupAttempt(failures, () -> removeOwnedProfile(selected, failures));
                    }
                }
                if (failures.isEmpty()) {
                    cleanupAttempt(failures, () -> {
                        ListConfigurationProfilesResponse remaining = appconfig.listConfigurationProfiles(request ->
                                request.applicationId(application));
                        assertNull(remaining.nextToken());
                        assertEquals(List.of(), remaining.items());
                        appconfig.deleteApplication(request -> request.applicationId(application));
                        ResourceNotFoundException missing = assertThrows(ResourceNotFoundException.class,
                                () -> appconfig.getApplication(request -> request.applicationId(application)));
                        assertEquals(404, missing.statusCode());
                        assertNoApplications();
                        assertEquals(List.of(), activeStacks());
                    });
                }
            }
        } finally {
            cleanupAttempt(failures, wire::save);
            if (appconfig != null) {
                cleanupAttempt(failures, appconfig::close);
            }
            if (cfn != null) {
                cleanupAttempt(failures, cfn::close);
            }
        }
        assertAll("Owned cleanup, complete AppConfig pairs and full CloudFormation responses", failures);
    }

    private void removeOwnedProfile(String selected, List<Executable> failures) {
        assertEquals(marker, appconfig.getConfigurationProfile(request -> request.applicationId(application)
                .configurationProfileId(selected)).description());
        for (HostedConfigurationVersionSummary summary : versions(selected)) {
            cleanupAttempt(failures, () -> {
                GetHostedConfigurationVersionResponse value = getVersion(selected, summary.versionNumber());
                assertTrue(expectedContent.containsKey(value.description()), "Only reserved owned content is deleted");
                assertEquals(expectedContent.get(value.description()), value.content().asUtf8String());
                deleteVersion(selected, summary.versionNumber());
            });
        }
        assertEquals(List.of(), versions(selected));
        appconfig.deleteConfigurationProfile(request -> request.applicationId(application).configurationProfileId(selected));
        ResourceNotFoundException missing = assertThrows(ResourceNotFoundException.class,
                () -> appconfig.getConfigurationProfile(request -> request.applicationId(application)
                        .configurationProfileId(selected)));
        assertEquals(404, missing.statusCode());
    }

    private static void cleanupAttempt(List<Executable> failures, Executable action) {
        try {
            action.execute();
        } catch (Throwable failure) {
            failures.add(() -> {
                throw failure;
            });
        }
    }

    private record StoredVersion(String application, String profile, int version, String description,
                                 String contentType, String label, String kmsKeyArn, String base64) {}
    private record ProfileState(GetConfigurationProfileResponse metadata,
                                List<HostedConfigurationVersionSummary> summaries, List<StoredVersion> full) {}
    private record Snapshot(GetApplicationResponse application, List<ConfigurationProfileSummary> collection,
                            Map<String, ProfileState> profiles) {}

    private final class WireReceipt implements ExecutionInterceptor {
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            int id = calls.size();
            attributes.putAttribute(WIRE_ID, id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", id);
            row.put("service", context.request().getClass().getPackageName().contains(".cloudformation.")
                    ? "cloudformation" : "appconfig");
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("requestBodyPresent", context.requestBody().isPresent());
            Map<String, String> headers = new LinkedHashMap<>();
            for (String header : List.of("content-type", "description", "latest-version-number", "versionlabel")) {
                context.httpRequest().firstMatchingHeader(header).ifPresent(value -> headers.put(header, value));
            }
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
        public Optional<InputStream> modifyHttpResponseContent(
                Context.ModifyHttpResponse context, ExecutionAttributes attributes) {
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
            assertTrue(bytes.length <= 262144, "Wire capture byte limit");
            return Base64.getEncoder().encodeToString(bytes);
        }

        private void save() throws IOException {
            Path directory = Path.of(System.getProperty("audit.cfn.hosted-public-id.directory", "target/cfn-hosted-public-id-wire"));
            Files.createDirectories(directory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(),
                    Map.of("marker", marker, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")));
        }
    }
}
