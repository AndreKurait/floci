package io.floci.aws.services.codedeploy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.ServiceRoleCallAuthorizer;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.codedeploy.model.Deployment;
import io.floci.aws.services.codedeploy.model.DeploymentGroup;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.ssm.SsmCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resource reload and rollback recovery. The rollback case uses independent persistent factories
 * and on-disk JSON; the catalog cases share in-memory backends.
 */
class CodeDeployServicePersistenceTest {

    private static final String REGION = "us-east-1";

    @Test
    void durableResourcesAndBuiltInsSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeDeployService first = serviceWithStorage(storage);
        first.createApplication(REGION, "web-app", "Server",
                List.of(Map.of("Key", "team", "Value", "platform")));
        first.createDeploymentGroup(REGION, "web-app", "prod-group",
                "CodeDeployDefault.OneAtATime", "arn:aws:iam::000000000000:role/cd", null,
                List.of(Map.of("Key", "owner", "Value", "release")));
        first.createDeploymentConfig(REGION, "custom-cfg",
                Map.of("type", "HOST_COUNT", "value", 1), "Server", null, null);
        first.registerOnPremisesInstance(REGION, "onprem-1",
                "arn:aws:sts::000000000000:session/s", "arn:aws:iam::000000000000:user/u");
        first.tagResource(first.applicationArn(REGION, "web-app"),
                List.of(Map.of("Key", "env", "Value", "prod")));

        CodeDeployService reloaded = serviceWithStorage(storage);

        assertEquals(List.of("web-app"), reloaded.listApplications(REGION));
        DeploymentGroup group = reloaded.getDeploymentGroup(REGION, "web-app", "prod-group");
        assertEquals("CodeDeployDefault.OneAtATime", group.getDeploymentConfigName());
        assertNotNull(reloaded.getDeploymentConfig(REGION, "custom-cfg"));
        // built-in configs survive (they are persisted, and topped up on load)
        assertNotNull(reloaded.getDeploymentConfig(REGION, "CodeDeployDefault.OneAtATime"));
        assertTrue(reloaded.listDeploymentConfigs(REGION).size() >= 18,
                "17 built-ins + custom config expected after restart");
        assertEquals("Registered",
                reloaded.getOnPremisesInstance(REGION, "onprem-1").getRegistrationStatus());
        Map<String, String> appTags = reloaded.listTagsForResource(first.applicationArn(REGION, "web-app"))
                .stream().collect(Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
        assertEquals("prod", appTags.get("env"));   // tagResource
        assertEquals("platform", appTags.get("team")); // createApplication tags
        Map<String, String> groupTags = reloaded.listTagsForResource(
                first.deploymentGroupArn(REGION, "web-app", "prod-group")).stream()
                .collect(Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
        assertEquals("release", groupTags.get("owner"));
    }

    @Test
    void deletesAndUntagArePersistedAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        CodeDeployService first = serviceWithStorage(storage);
        first.createApplication(REGION, "keep-app", "Server", null);
        first.createApplication(REGION, "drop-app", "Server", null);
        first.createDeploymentGroup(REGION, "keep-app", "g1", null, "arn:r", null, null);
        first.createDeploymentGroup(REGION, "keep-app", "g2", null, "arn:r", null, null);
        first.deleteApplication(REGION, "drop-app");
        first.deleteDeploymentGroup(REGION, "keep-app", "g2");
        String arn = first.applicationArn(REGION, "keep-app");
        first.tagResource(arn, List.of(Map.of("Key", "env", "Value", "prod"),
                Map.of("Key", "team", "Value", "sec")));
        first.untagResource(arn, List.of("team"));

        CodeDeployService reloaded = serviceWithStorage(storage);

        assertEquals(List.of("keep-app"), reloaded.listApplications(REGION));
        assertEquals(List.of("g1"), reloaded.listDeploymentGroups(REGION, "keep-app"));
        assertThrows(Exception.class, () -> reloaded.getDeploymentGroup(REGION, "keep-app", "g2"));
        Map<String, String> tags = reloaded.listTagsForResource(arn).stream()
                .collect(Collectors.toMap(t -> t.get("Key"), t -> t.get("Value")));
        assertEquals("prod", tags.get("env"));
        assertTrue(!tags.containsKey("team"), "untagged key must not reappear after restart");
    }

    @Test
    void taggedDeploymentGroupKeepsTagsWhenRenamed() {
        SharedStorageFactory storage = new SharedStorageFactory();
        CodeDeployService service = serviceWithStorage(storage);
        service.createApplication(REGION, "rename-app", "Server", null);
        service.createDeploymentGroup(REGION, "rename-app", "old-group", null, "arn:r", null, null);
        String originalArn = service.deploymentGroupArn(REGION, "rename-app", "old-group");
        service.tagResource(originalArn, List.of(Map.of("Key", "team", "Value", "red")));

        service.updateDeploymentGroup(REGION, "rename-app", "old-group", "new-group", null, null, null);

        CodeDeployService reloaded = serviceWithStorage(storage);
        String renamedArn = reloaded.deploymentGroupArn(REGION, "rename-app", "new-group");
        assertEquals(List.of(Map.of("Key", "team", "Value", "red")), reloaded.listTagsForResource(renamedArn));
        assertEquals(List.of(), reloaded.listTagsForResource(originalArn));
    }

    @Test
    void renamingDeploymentGroupToExistingNamePreservesBothGroupsAndTags() {
        SharedStorageFactory storage = new SharedStorageFactory();
        CodeDeployService service = serviceWithStorage(storage);
        service.createApplication(REGION, "rename-conflict-app", "Server", null);
        DeploymentGroup source = service.createDeploymentGroup(REGION, "rename-conflict-app", "source",
                null, "arn:source-role", null, List.of(Map.of("Key", "team", "Value", "red")));
        DeploymentGroup destination = service.createDeploymentGroup(REGION, "rename-conflict-app", "destination",
                null, "arn:destination-role", null, List.of(Map.of("Key", "team", "Value", "blue")));
        String sourceArn = service.deploymentGroupArn(REGION, "rename-conflict-app", "source");
        String destinationArn = service.deploymentGroupArn(REGION, "rename-conflict-app", "destination");

        AwsException error = assertThrows(AwsException.class, () -> service.updateDeploymentGroup(
                REGION, "rename-conflict-app", "source", "destination", null, "arn:changed-role", null));

        assertEquals("DeploymentGroupAlreadyExistsException", error.getErrorCode());
        CodeDeployService reloaded = serviceWithStorage(storage);
        assertEquals(source.getDeploymentGroupId(),
                reloaded.getDeploymentGroup(REGION, "rename-conflict-app", "source").getDeploymentGroupId());
        assertEquals(destination.getDeploymentGroupId(),
                reloaded.getDeploymentGroup(REGION, "rename-conflict-app", "destination").getDeploymentGroupId());
        assertEquals("arn:source-role",
                reloaded.getDeploymentGroup(REGION, "rename-conflict-app", "source").getServiceRoleArn());
        assertEquals(List.of(Map.of("Key", "team", "Value", "red")), reloaded.listTagsForResource(sourceArn));
        assertEquals(List.of(Map.of("Key", "team", "Value", "blue")), reloaded.listTagsForResource(destinationArn));
    }

    @Test
    void deletedDeploymentGroupDoesNotLeaveTagsForRecreatedName() {
        SharedStorageFactory storage = new SharedStorageFactory();
        CodeDeployService service = serviceWithStorage(storage);
        service.createApplication(REGION, "recreate-app", "Server", null);
        service.createDeploymentGroup(REGION, "recreate-app", "group", null, "arn:r", null, null);
        String arn = service.deploymentGroupArn(REGION, "recreate-app", "group");
        service.tagResource(arn, List.of(Map.of("Key", "team", "Value", "red")));

        service.deleteDeploymentGroup(REGION, "recreate-app", "group");
        service.createDeploymentGroup(REGION, "recreate-app", "group", null, "arn:r", null, null);

        CodeDeployService reloaded = serviceWithStorage(storage);
        assertEquals(List.of(), reloaded.listTagsForResource(arn));
    }

    @Test
    void theSuccessfulRevisionSurvivesADiskReloadBeforeADeploymentFails(@TempDir Path directory) throws Exception {
        Map<String, Object> good = Map.of("revisionType", "S3",
                "s3Location", Map.of("bucket", "revisions", "key", "original.zip", "bundleType", "zip", "version", "good"));
        StorageFactory originalStorage = persistentStorage(directory);
        String first;
        try {
            CodeDeployService original = serviceWithStorage(originalStorage, completingAgent());
            original.createApplication(REGION, "app", "Server", null);
            original.createDeploymentGroup(REGION, "app", "group", "CodeDeployDefault.OneAtATime",
                    new RegionResolver(REGION, "000000000000").buildGlobalArn("iam", "role/deploy"),
                    Map.of("autoRollbackConfiguration", Map.of("enabled", true, "events", List.of("DEPLOYMENT_FAILURE"))), null);
            first = original.createDeployment(REGION, "app", "group", null, good, null, "OVERWRITE");
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    List.of("Succeeded", "Failed", "Stopped").contains(original.getDeployment(REGION, first).getStatus()));
            assertEquals("Succeeded", original.getDeployment(REGION, first).getStatus());
        } finally {
            originalStorage.shutdownAll();
        }
        assertTrue(Files.readString(directory.resolve("codedeploy-agent-successes.json")).contains(first));

        StorageFactory reloadedStorage = persistentStorage(directory);
        try {
            CodeDeployService reloaded = serviceWithStorage(reloadedStorage, completingAgent());
            assertThrows(AwsException.class, () -> reloaded.getDeployment(REGION, first));
            Map<String, Object> bad = Map.of("revisionType", "S3",
                    "s3Location", Map.of("bucket", "revisions", "key", "bad.zip", "bundleType", "zip", "version", "bad"));
            String failed = reloaded.createDeployment(REGION, "app", "group", null, bad, null, "RETAIN");
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    "Failed".equals(reloaded.getDeployment(REGION, failed).getStatus()));
            String rollback = reloaded.getDeployment(REGION, failed).getRollbackInfo().get("rollbackDeploymentId");
            assertNotNull(rollback);
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    "Succeeded".equals(reloaded.getDeployment(REGION, rollback).getStatus()));
            assertEquals(good, reloaded.getDeployment(REGION, rollback).getRevision());
            assertEquals("OVERWRITE", reloaded.getDeployment(REGION, rollback).getFileExistsBehavior());
            reloaded.deleteApplication(REGION, "app");
        } finally {
            reloadedStorage.shutdownAll();
        }
        assertEquals(0, new ObjectMapper().readTree(
                directory.resolve("codedeploy-agent-successes.json").toFile()).size());
    }

    @Test
    void omittedGroupFieldsSurviveUpdateAndReload(@TempDir Path directory) {
        verifyGroupFieldUpdate(directory, false);
    }

    @Test
    void explicitNullGroupFieldsStayClearedAfterReload(@TempDir Path directory) {
        verifyGroupFieldUpdate(directory, true);
    }

    private void verifyGroupFieldUpdate(Path directory, boolean clear) {
        Map<String, Object> fields = new HashMap<>();
        for (String name : List.of("ec2TagFilters", "onPremisesInstanceTagFilters",
                "triggerConfigurations", "ecsServices")) {
            fields.put(name, List.of(Map.of("name", "retained")));
        }
        for (String name : List.of("deploymentStyle", "blueGreenDeploymentConfiguration",
                "loadBalancerInfo", "ec2TagSet", "onPremisesTagSet", "alarmConfiguration",
                "autoRollbackConfiguration")) {
            fields.put(name, Map.of("enabled", true));
        }
        StorageFactory storage = persistentStorage(directory);
        try {
            CodeDeployService service = serviceWithStorage(storage);
            service.createApplication(REGION, "fields-app", "Server", null);
            service.createDeploymentGroup(REGION, "fields-app", "group", null, "arn:role", fields, null);
            Map<String, Object> update = new HashMap<>();
            if (clear) {
                fields.keySet().forEach(name -> update.put(name, null));
            }
            service.updateDeploymentGroup(REGION, "fields-app", "group", null, null, null, update);
            assertGroupFields(service, fields, clear);
        } finally {
            storage.shutdownAll();
        }
        StorageFactory reloaded = persistentStorage(directory);
        try {
            assertGroupFields(serviceWithStorage(reloaded), fields, clear);
        } finally {
            reloaded.shutdownAll();
        }
    }

    private void assertGroupFields(CodeDeployService service, Map<String, Object> original, boolean clear) {
        Map<String, Object> actual = new ObjectMapper().convertValue(
                service.getDeploymentGroup(REGION, "fields-app", "group"), new TypeReference<>() {});
        for (String name : original.keySet()) {
            assertEquals(clear ? null : original.get(name), actual.get(name), name);
        }
    }

    private static CodeDeployService serviceWithStorage(StorageFactory storage) {
        return serviceWithStorage(storage, mock(CodeDeployAgentDeployment.class));
    }

    private static CodeDeployService serviceWithStorage(StorageFactory storage, CodeDeployAgentDeployment agent) {
        CodeDeployService service = new CodeDeployService(
                mock(LambdaService.class), mock(EcsService.class), mock(ElbV2Service.class),
                mock(SsmCommandService.class), mock(Ec2Service.class), new ObjectMapper(),
                new RegionResolver(REGION, "000000000000"), storage, agent,
                CodeDeployTestRoles.allowAll());
        service.initializeStorage();
        return service;
    }

    private static StorageFactory persistentStorage(Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.defaultAccountId()).thenReturn("000000000000");
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("codedeploy")).thenReturn("persistent");
        return new StorageFactory(config, access);
    }

    private static CodeDeployAgentDeployment completingAgent() {
        CodeDeployAgentDeployment agent = mock(CodeDeployAgentDeployment.class);
        CodeDeploySpecificationSigner signer = mock(CodeDeploySpecificationSigner.class);
        when(signer.sign(anyString())).thenAnswer(call -> call.getArgument(0));
        CodeDeployAgentCommands mailbox = new CodeDeployAgentCommands(new ObjectMapper(), signer,
                Duration.ZERO, Duration.ZERO);
        when(agent.prepare(eq(REGION), any(DeploymentGroup.class), anyString(), anyMap(), anyString()))
                .thenAnswer(call -> new CodeDeployAgentDeployment.Prepared("000000000000", REGION,
                        call.<DeploymentGroup>getArgument(1).getDeploymentGroupId(), call.getArgument(2),
                        Map.of(), Map.of("i-target", AwsArnUtils.Arn.of("ec2", REGION,
                                "000000000000", "instance/i-target").toString()), List.of("i-target"),
                        call.getArgument(4), null));
        when(agent.open(any(), any())).thenAnswer(call -> {
            CodeDeployAgentDeployment.Prepared prepared = call.getArgument(0);
            Deployment deployment = call.getArgument(1);
            return mailbox.open(prepared.account(), prepared.region(), deployment.getDeploymentId(),
                    prepared.groupId(), prepared.hosts().values(), Map.of());
        });
        doAnswer(call -> {
            Deployment deployment = call.getArgument(2);
            Consumer<CodeDeployAgentDeployment.Outcome> complete = call.getArgument(5);
            boolean failed = "bad".equals(((Map<?, ?>) deployment.getRevision().get("s3Location")).get("version"));
            mailbox.close(call.getArgument(1));
            complete.accept(new CodeDeployAgentDeployment.Outcome(failed ? "Failed" : "Succeeded",
                    failed ? "HEALTH_CONSTRAINTS" : null, null));
            return null;
        }).when(agent).run(any(), any(), any(), anyList(), any(AtomicBoolean.class), any());
        return agent;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
