package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.appconfig.AppConfigService;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class AppConfigS3CfnCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String STACK = "s3-profile-compatibility";
    private static final String ROLE_A = "arn:aws:iam::000000000000:role/source-a";
    private static final String ROLE_B = "arn:aws:iam::000000000000:role/source-b";
    private AppConfigService service;
    private AppConfigCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;
    private String application;

    @BeforeEach
    void setUp() {
        service = spy(new AppConfigService(new InMemoryStorageFactory(ACCOUNT), new RegionResolver(REGION, ACCOUNT)));
        provisioner = new AppConfigCfnProvisioner(service);
        engine = CloudFormationTemplateEngine.standalone(ACCOUNT, REGION, STACK, JSON, value -> value);
        application = service.createApplication(Map.of("Name", STACK)).getId();
    }

    @ParameterizedTest
    @ValueSource(strings = {"hosted", "ssm-parameter://owned-configuration"})
    void nonS3RolePropertiesRemainIgnoredDuringCreateUpdateAndRollback(String location) {
        StackResource resource = create(location, Map.of("not", "a string"));
        String id = resource.getPhysicalId();
        JsonNode before = profile(id);

        update(resource, location, "not-an-arn");
        JsonNode updated = profile(id);
        assertEquals(id, resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        assertEquals("updated", updated.path("Name").asText());
        assertEquals("after", updated.path("Description").asText());
        assertEquals(location, updated.path("LocationUri").asText());
        assertFalse(updated.hasNonNull("RetrievalRoleArn"));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(before, profile(id));
        assertFalse(resource.getAttributes().containsKey(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3://owned-bucket/config.json", "arn:aws:s3:::owned-bucket/config.json"})
    void s3RoleUpdatesPreserveIdentityAndRollbackRestoresTheCompleteProfile(String location) {
        StackResource resource = create(location, ROLE_A);
        String id = resource.getPhysicalId();
        JsonNode before = profile(id);
        update(resource, location, ROLE_B);
        JsonNode updated = profile(id);
        assertTrue(provisioner.rollbackUpdate(resource));
        JsonNode restored = profile(id);

        assertEquals(id, resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        assertEquals(ROLE_A, before.path("RetrievalRoleArn").asText());
        assertEquals(ROLE_B, updated.path("RetrievalRoleArn").asText());
        assertEquals("updated", updated.path("Name").asText());
        assertEquals("after", updated.path("Description").asText());
        assertEquals(location, updated.path("LocationUri").asText());
        assertEquals(before, restored);
        assertFalse(resource.getAttributes().containsKey(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void failedS3CompensationRetainsTheSnapshotForAnExactRetry() {
        String location = "s3://owned-bucket/config.json";
        StackResource resource = create(location, ROLE_A);
        String id = resource.getPhysicalId();
        JsonNode before = profile(id);
        update(resource, location, ROLE_B);
        JsonNode updated = profile(id);
        AwsException refusal = new AwsException("InternalServerException", "owned restore refusal", 500);
        doThrow(refusal).doCallRealMethod().when(service).updateConfigurationProfile(anyString(), anyString(), anyMap());

        assertSame(refusal, assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource)));
        assertEquals(updated, profile(id));
        assertTrue(resource.getAttributes().containsKey(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(id, resource.getPhysicalId());
        assertEquals(ROLE_A, before.path("RetrievalRoleArn").asText());
        assertEquals(ROLE_B, updated.path("RetrievalRoleArn").asText());
        assertEquals(before, profile(id));
        assertFalse(resource.getAttributes().containsKey(CfnRollback.APPCONFIG_UPDATE_SNAPSHOT_ATTR));
    }

    private StackResource create(String location, Object role) {
        StackResource resource = new StackResource();
        resource.setLogicalId("Profile");
        resource.setResourceType("AWS::AppConfig::ConfigurationProfile");
        provisioner.provision(resource, JSON.valueToTree(properties(location, role, "original", "before")), context(null));
        return resource;
    }

    private void update(StackResource resource, String location, Object role) {
        provisioner.provision(resource, JSON.valueToTree(properties(location, role, "updated", "after")),
                context(resource.getPhysicalId()));
    }

    private Map<String, Object> properties(String location, Object role, String name, String description) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("ApplicationId", application);
        properties.put("Name", name);
        properties.put("Description", description);
        properties.put("LocationUri", location);
        properties.put("RetrievalRoleArn", role);
        return properties;
    }

    private JsonNode profile(String id) {
        return JSON.valueToTree(service.getConfigurationProfile(application, id));
    }

    private ProvisionContext context(String prior) {
        return new ProvisionContext(engine, REGION, ACCOUNT, STACK, prior);
    }
}
