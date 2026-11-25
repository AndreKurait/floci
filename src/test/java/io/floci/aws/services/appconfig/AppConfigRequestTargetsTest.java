package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.appconfig.model.Environment;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigRequestTargetsTest {
    private final AppConfigService service = new AppConfigService(new InMemoryStorageFactory("000000000000"),
            new RegionResolver("us-east-1", "000000000000"));
    private final AppConfigRequestTargets targets = new AppConfigRequestTargets(service);

    @Test
    void requestKeepsResolvedIdentityButReadsUpdatedEnvironmentById() {
        String app = service.createApplication(Map.of("Name", "orders")).getId();
        String original = service.createEnvironment(app, Map.of("Name", "production")).getId();
        String sibling = service.createEnvironment(app, Map.of("Name", "staging")).getId();
        assertEquals(original, targets.environment("orders", "production").environmentId());
        service.updateEnvironment(app, original, Map.of("Name", "retired", "Description", "updated"));
        service.updateEnvironment(app, sibling, Map.of("Name", "production"));
        Environment selected = targets.getEnvironment("orders", "production");
        assertEquals(original, selected.getId());
        assertEquals("updated", selected.getDescription());
        assertEquals(sibling, new AppConfigRequestTargets(service).getEnvironment("orders", "production").getId());
    }

    @Test
    void parentAndEnvironmentResolutionUseTheSameApplicationIdentity() {
        String app = service.createApplication(Map.of("Name", "orders")).getId();
        String env = service.createEnvironment(app, Map.of("Name", "production")).getId();
        String other = service.createApplication(Map.of("Name", "other")).getId();
        service.createEnvironment(other, Map.of("Name", "production"));
        assertEquals(app, targets.application("orders"));
        service.updateApplication(app, Map.of("Name", "retired"));
        service.updateApplication(other, Map.of("Name", "orders"));
        assertEquals(env, targets.getEnvironment("orders", "production").getId());
    }

    @Test
    void deletedIdIsNeverReinterpretedAsAName() {
        String app = service.createApplication(Map.of("Name", "orders")).getId();
        String original = service.createEnvironment(app, Map.of("Name", "production")).getId();
        targets.environment(app, original);
        service.deleteEnvironment(app, original);
        service.createEnvironment(app, Map.of("Name", original));
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> targets.getEnvironment(app, original)).getErrorCode());
    }

    @Test
    void missingSelectionCannotBecomeANewTargetDuringTheRequest() {
        String app = service.createApplication(Map.of("Name", "orders")).getId();
        assertEquals("Environment not found", assertThrows(AwsException.class,
                () -> targets.environment(app, "production")).getMessage());
        service.createEnvironment(app, Map.of("Name", "production"));
        assertEquals("Environment not found", assertThrows(AwsException.class,
                () -> targets.getEnvironment(app, "production")).getMessage());
    }

    @Test
    void missingApplicationRetainsItsOriginalError() {
        assertEquals("Application not found", assertThrows(AwsException.class,
                () -> targets.environment("orders", "production")).getMessage());
        String app = service.createApplication(Map.of("Name", "orders")).getId();
        service.createEnvironment(app, Map.of("Name", "production"));
        assertEquals("Application not found", assertThrows(AwsException.class,
                () -> targets.getEnvironment("orders", "production")).getMessage());
    }

    @Test
    void handlerWithoutPriorIamResolutionStillAcceptsNames() {
        String app = service.createApplication(Map.of("Name", "orders")).getId();
        String env = service.createEnvironment(app, Map.of("Name", "production")).getId();
        assertEquals(env, targets.getEnvironment("orders", "production").getId());
    }
}
