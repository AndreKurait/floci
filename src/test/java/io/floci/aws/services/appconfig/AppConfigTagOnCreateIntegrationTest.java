package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.AppConfigResponse;
import software.amazon.awssdk.services.appconfig.model.CreateApplicationResponse;
import software.amazon.awssdk.services.appconfig.model.CreateDeploymentStrategyResponse;
import software.amazon.awssdk.services.appconfig.model.ListApplicationsResponse;
import software.amazon.awssdk.services.appconfig.model.ListDeploymentStrategiesResponse;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AppConfigTagOnCreateIntegrationTest {
    private static final Verdict DENIED = new Verdict(403, "AccessDeniedException");

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"CreateApplication,application", "CreateDeploymentStrategy,deploymentstrategy"})
    void taggedCreatesRequirePermissionOnTheirMintedResourceType(String operation, String type) throws Exception {
        List<String> created = new ArrayList<>();
        try (AppConfigClient admin = client("test", "test", Region.US_EAST_1);
             StsClient identity = AwsTestClients.create(
                     StsClient.builder(), "test", "test", Region.US_EAST_1, endpoint)) {
            String prefix = "arn:aws:appconfig:us-east-1:" + identity.getCallerIdentity(request -> {}).account() + ":";
            String resource = prefix + type + "/*";
            try {
                for (int trial = 0; trial < 4; trial++) {
                    List<Map<String, Object>> statements = new ArrayList<>();
                    statements.add(Map.of("Effect", "Allow", "Action", "appconfig:" + operation, "Resource", "*"));
                    if (trial > 0) {
                        statements.add(Map.of("Effect", "Allow", "Action", "appconfig:TagResource",
                                "Resource", trial == 1 ? prefix + "environment/*" : resource));
                    }
                    if (trial == 2) {
                        statements.add(Map.of("Effect", "Deny", "Action", "appconfig:TagResource", "Resource", resource));
                    }
                    try (SignedIamUsers.User user = user(statements);
                         AppConfigClient caller = client(user.accessKeyId(), user.secretAccessKey(), Region.US_EAST_1)) {
                        assertCaller(user, Region.US_EAST_1);
                        Map<String, List<Object>> before = createdResourceSnapshot(admin);
                        Verdict result = createTagged(caller, operation, "schema-tag-" + UUID.randomUUID(), created);
                        Map<String, List<Object>> after = createdResourceSnapshot(admin);
                        if (trial < 3) {
                            assertAll(() -> assertEquals(DENIED, result), () -> assertEquals(before, after));
                        } else {
                            assertEquals(new Verdict(201, null), result);
                            String collection = "application".equals(type) ? "applications" : "strategies";
                            String other = "application".equals(type) ? "strategies" : "applications";
                            String id = created.getLast();
                            List<Object> retained = after.get(collection).stream()
                                    .filter(value -> !id.equals(((Map<?, ?>) value).get("Id"))).toList();
                            assertAll(() -> assertEquals(before.get(other), after.get(other)),
                                    () -> assertEquals(before.get(collection), retained),
                                    () -> assertEquals(before.get(collection).size() + 1, after.get(collection).size()));
                        }
                    }
                }
            } finally {
                List<Executable> removals = new ArrayList<>();
                for (String id : created) {
                    removals.add(() -> {
                        if ("application".equals(type)) {
                            admin.deleteApplication(request -> request.applicationId(id));
                            absent(() -> admin.getApplication(request -> request.applicationId(id)));
                        } else {
                            admin.deleteDeploymentStrategy(request -> request.deploymentStrategyId(id));
                            absent(() -> admin.getDeploymentStrategy(request -> request.deploymentStrategyId(id)));
                        }
                    });
                }
                assertAll("Every captured tagged-create resource is removed independently", removals);
            }
        }
    }

    private static Verdict createTagged(AppConfigClient caller, String operation, String name, List<String> created) {
        return verdict(() -> {
            if ("CreateApplication".equals(operation)) {
                CreateApplicationResponse response = caller.createApplication(request -> request.name(name).tags(Map.of("audit", "owned")));
                created.add(response.id());
                return response;
            }
            CreateDeploymentStrategyResponse response = caller.createDeploymentStrategy(request -> request.name(name).deploymentDurationInMinutes(0)
                    .finalBakeTimeInMinutes(0).growthFactor(100.0f).growthType("LINEAR").replicateTo("NONE")
                    .tags(Map.of("audit", "owned")));
            created.add(response.id());
            return response;
        });
    }

    private static Map<String, List<Object>> createdResourceSnapshot(AppConfigClient admin) {
        List<Object> applications = new ArrayList<>();
        List<Object> strategies = new ArrayList<>();
        String next = null;
        for (int page = 0; page < 100; page++) {
            String token = next;
            ListApplicationsResponse response = admin.listApplications(request -> request.maxResults(50).nextToken(token));
            response.items().forEach(value -> applications.add(fields(value)));
            next = response.nextToken();
            if (next == null) {
                break;
            }
        }
        assertEquals(null, next, "Complete bounded application collection");
        for (int page = 0; page < 100; page++) {
            String token = next;
            ListDeploymentStrategiesResponse response = admin.listDeploymentStrategies(request -> request.maxResults(50).nextToken(token));
            response.items().forEach(value -> strategies.add(fields(value)));
            next = response.nextToken();
            if (next == null) {
                break;
            }
        }
        assertEquals(null, next, "Complete bounded strategy collection");
        applications.sort(Comparator.comparing(Object::toString));
        strategies.sort(Comparator.comparing(Object::toString));
        return Map.of("applications", applications, "strategies", strategies);
    }

    @ParameterizedTest
    @CsvSource({"CreateApplication,application,us-east-1,aws",
            "CreateDeploymentStrategy,deploymentstrategy,us-east-1,aws",
            "CreateApplication,application,cn-north-1,aws-cn",
            "CreateDeploymentStrategy,deploymentstrategy,cn-north-1,aws-cn",
            "CreateApplication,application,us-gov-west-1,aws-us-gov",
            "CreateDeploymentStrategy,deploymentstrategy,us-gov-west-1,aws-us-gov"})
    void secondaryGrantUsesOnlyTheCreatedTypeInTheRequestScope(String operation, String type,
                                                               String regionName, String partition) throws Exception {
        exerciseResourceScope(operation, type, Region.of(regionName), partition);
    }

    void exerciseResourceScope(String operation, String type, Region region, String partition) throws Exception {
        List<Executable> checks = new ArrayList<>();
        try (AppConfigClient admin = client("test", "test", region);
             StsClient identity = AwsTestClients.create(StsClient.builder(), "test", "test", region, endpoint)) {
            String account = identity.getCallerIdentity(request -> {}).account();
            String prefix = "arn:" + partition + ":appconfig:" + region.id() + ":" + account + ":";
            String resource = prefix + type + "/*";
            String otherType = "application".equals(type) ? "deploymentstrategy" : "application";
            List<String> granted = List.of(prefix + otherType + "/*", resource,
                    "arn:" + partition + ":appconfig:" + region.id() + ":999999999999:" + type + "/*",
                    "arn:" + partition + ":appconfig:eu-west-1:" + account + ":" + type + "/*",
                    "arn:" + ("aws".equals(partition) ? "aws-cn" : "aws")
                            + ":appconfig:" + region.id() + ":" + account + ":" + type + "/*");
            for (int index = 0; index < granted.size(); index++) {
                trial(admin, region, operation, type, Map.of("audit", "owned"),
                        List.of(grant("appconfig:" + operation, "*"),
                                grant("appconfig:TagResource", granted.get(index))),
                        index == 1, checks);
            }
            trial(admin, region, operation, type, Map.of("audit", "owned"),
                    List.of(grant("appconfig:" + operation, "*"), grant("appconfig:TagResource", "*"),
                            Map.of("Effect", "Deny", "Action", "appconfig:TagResource", "Resource", resource)),
                    false, checks);
        }
        assertAll("Every exact secondary resource grant and complete collection", checks);
    }

    @ParameterizedTest
    @CsvSource({"CreateApplication,application", "CreateDeploymentStrategy,deploymentstrategy"})
    void requestTagsAndTagKeysConstrainBothRequiredActions(String operation, String type) throws Exception {
        List<Executable> checks = new ArrayList<>();
        try (AppConfigClient admin = client("test", "test", Region.US_EAST_1);
             StsClient identity = AwsTestClients.create(
                     StsClient.builder(), "test", "test", Region.US_EAST_1, endpoint)) {
            String resource = "arn:aws:appconfig:us-east-1:"
                    + identity.getCallerIdentity(request -> {}).account() + ":" + type + "/*";
            Map<String, Object> condition = Map.of(
                    "StringEquals", Map.of("aws:RequestTag/audit", "owned"),
                    "ForAllValues:StringEquals", Map.of("aws:TagKeys", List.of("audit")));
            for (String constrained : List.of("appconfig:" + operation, "appconfig:TagResource")) {
                List<Map<String, Object>> policy = new ArrayList<>();
                for (String action : List.of("appconfig:" + operation, "appconfig:TagResource")) {
                    Map<String, Object> statement = new LinkedHashMap<>(
                            grant(action, action.endsWith(":TagResource") ? resource : "*"));
                    if (constrained.equals(action)) {
                        statement.put("Condition", condition);
                    }
                    policy.add(statement);
                }
                trial(admin, Region.US_EAST_1, operation, type, Map.of("audit", "owned"), policy, true, checks);
                trial(admin, Region.US_EAST_1, operation, type, Map.of("audit", "different"), policy, false, checks);
                trial(admin, Region.US_EAST_1, operation, type, Map.of("audit", "owned", "extra", "value"),
                        policy, false, checks);
            }
        }
        assertAll("Both actions evaluate the actual request tags and complete tag-key set", checks);
    }

    @ParameterizedTest
    @CsvSource({"CreateApplication,application", "CreateDeploymentStrategy,deploymentstrategy"})
    void anUntaggedCreateNeedsOnlyItsPrimaryAction(String operation, String type) throws Exception {
        List<Executable> checks = new ArrayList<>();
        try (AppConfigClient admin = client("test", "test", Region.US_EAST_1)) {
            trial(admin, Region.US_EAST_1, operation, type, Map.of(),
                    List.of(grant("appconfig:" + operation, "*")), true, checks);
        }
        assertAll(checks);
    }

    private void trial(AppConfigClient admin, Region region, String operation, String type, Map<String, String> tags,
                       List<Map<String, Object>> policy, boolean allowed, List<Executable> checks) throws Exception {
        List<String> created = new ArrayList<>();
        Map<String, List<Object>> before = createdResourceSnapshot(admin);
        try (SignedIamUsers.User user = user(policy);
             AppConfigClient caller = client(user.accessKeyId(), user.secretAccessKey(), region)) {
            assertCaller(user, region);
            Verdict result = create(caller, operation, tags, created);
            Map<String, List<Object>> after = createdResourceSnapshot(admin);
            checks.add(() -> assertEquals(allowed ? new Verdict(201, null) : DENIED, result, policy.toString()));
            if (allowed && !created.isEmpty()) {
                String collection = "application".equals(type) ? "applications" : "strategies";
                String other = "application".equals(type) ? "strategies" : "applications";
                String id = created.getLast();
                List<Object> retained = after.get(collection).stream()
                        .filter(value -> !id.equals(((Map<?, ?>) value).get("Id"))).toList();
                checks.add(() -> assertAll(() -> assertEquals(before.get(other), after.get(other)),
                        () -> assertEquals(before.get(collection), retained),
                        () -> assertEquals(before.get(collection).size() + 1, after.get(collection).size())));
            } else {
                checks.add(() -> assertEquals(before, after, "A denied create preserves both complete collections"));
            }
        } finally {
            for (String id : created) {
                if ("application".equals(type)) {
                    admin.deleteApplication(request -> request.applicationId(id));
                    absent(() -> admin.getApplication(request -> request.applicationId(id)));
                } else {
                    admin.deleteDeploymentStrategy(request -> request.deploymentStrategyId(id));
                    absent(() -> admin.getDeploymentStrategy(request -> request.deploymentStrategyId(id)));
                }
            }
            assertEquals(before, createdResourceSnapshot(admin), "Captured resources removed and collections restored");
        }
    }

    private static Map<String, Object> grant(String action, String resource) {
        return Map.of("Effect", "Allow", "Action", action, "Resource", resource);
    }

    private static Verdict create(AppConfigClient caller, String operation, Map<String, String> tags,
                                  List<String> created) {
        String name = "tag-admission-" + UUID.randomUUID();
        return verdict(() -> {
            if ("CreateApplication".equals(operation)) {
                CreateApplicationResponse response = caller.createApplication(request -> request.name(name).tags(tags));
                created.add(response.id());
                return response;
            }
            CreateDeploymentStrategyResponse response = caller.createDeploymentStrategy(request -> request.name(name)
                    .deploymentDurationInMinutes(0).finalBakeTimeInMinutes(0).growthFactor(100.0f)
                    .growthType("LINEAR").replicateTo("NONE").tags(tags));
            created.add(response.id());
            return response;
        });
    }

    private SignedIamUsers.User user(List<Map<String, Object>> statements) throws Exception {
        return SignedIamUsers.createUser(endpoint, "schema-iam-", "validate",
                new ObjectMapper().writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements)));
    }

    private void assertCaller(SignedIamUsers.User user, Region region) {
        try (StsClient identity = AwsTestClients.create(
                StsClient.builder(), user.accessKeyId(), user.secretAccessKey(), region, endpoint)) {
            assertTrue(identity.getCallerIdentity(request -> {}).arn().endsWith(":user/" + user.name()));
        }
    }

    private AppConfigClient client(String key, String secret, Region region) {
        return AwsTestClients.create(AppConfigClient.builder()
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))),
                key, secret, region, endpoint);
    }

    private static Object fields(Object value) {
        if (value instanceof SdkPojo pojo) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (SdkField<?> field : pojo.sdkFields()) {
                result.put(field.memberName(), fields(field.getValueOrDefault(pojo)));
            }
            return result;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(AppConfigTagOnCreateIntegrationTest::fields).toList();
        }
        return value;
    }

    private static Verdict verdict(Supplier<? extends AppConfigResponse> operation) {
        try {
            return new Verdict(operation.get().sdkHttpResponse().statusCode(), null);
        } catch (AppConfigException error) {
            return new Verdict(error.statusCode(), error.awsErrorDetails().errorCode());
        }
    }

    private static void absent(Supplier<? extends AppConfigResponse> operation) {
        assertEquals(new Verdict(404, "ResourceNotFoundException"), verdict(operation));
    }

    private record Verdict(int status, String code) {}
}
