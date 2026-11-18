package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.acmpca.AcmPcaService;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcmPcaCfnProvisionerTest {

    private static final String TYPE = "AWS::ACMPCA::CertificateAuthority";
    private final ObjectMapper mapper = new ObjectMapper();
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final AcmPcaService service = mock(AcmPcaService.class);
    private final AcmPcaCfnProvisioner provisioner = new AcmPcaCfnProvisioner(service, config);
    private final CloudFormationResourceRegistry registry = new CloudFormationResourceRegistry(List.of(provisioner));
    private final CfnResourceDispatcher dispatcher = new CfnResourceDispatcher(mapper, registry,
            mock(CfnDynamicReferences.class), config);

    @Test
    void enabledAuthorityRemainsRoutedToItsProvisioner() {
        when(config.services().acmPca().enabled()).thenReturn(true);
        assertFalse(provisioner.serviceDisabled());
        assertSame(provisioner, registry.forType(TYPE).orElseThrow());
    }

    @Test
    void disabledAuthorityUsesTheConfiguredStubPath() {
        when(config.services().cloudformation().allowStubUnsupportedResourceTypes()).thenReturn(true);
        StackResource resource = create();
        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals("true", resource.getAttributes().get(CloudFormationTemplateEngine.SERVICE_DISABLED_ATTR));
        assertTrue(resource.getStatusReason().contains("floci.services.acm-pca.enabled=true"));
        verifyNoInteractions(service);
    }

    @Test
    void disabledAuthorityFailsWhenStubsAreForbidden() {
        StackResource resource = create();
        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("floci.services.acm-pca.enabled=true"));
        verifyNoInteractions(service);
    }

    @Test
    void disabledAuthorityCannotUpdateAnExistingResource() {
        StackResource resource = dispatcher.provision("Authority", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "test-stack", "existing-authority", Map.of());
        assertEquals("CREATE_FAILED", resource.getStatus());
        assertEquals("existing-authority", resource.getPhysicalId());
        assertTrue(resource.getStatusReason().contains("cannot be updated"));
        verifyNoInteractions(service);
    }

    private StackResource create() {
        return dispatcher.provision("Authority", TYPE, mapper.createObjectNode(), null,
                "us-east-1", "000000000000", "test-stack");
    }
}
