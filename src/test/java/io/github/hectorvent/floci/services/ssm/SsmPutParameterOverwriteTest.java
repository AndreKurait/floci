package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class SsmPutParameterOverwriteTest {

    private static final String REGION = "us-east-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void anOverwriteWithoutATypeKeepsTheTypeOfTheParameter() {
        SsmService ssmService = new SsmService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), 5);
        SsmJsonHandler handler = new SsmJsonHandler(ssmService, null, null, objectMapper,
                mock(IamEnforcementFilter.class), mock(SsmPatchBaselineService.class), null, null, null, null);
        ObjectNode create = objectMapper.createObjectNode()
                .put("Name", "/app/password").put("Value", "first").put("Type", "SecureString");
        handler.handle("PutParameter", create, REGION, "auth");

        ObjectNode overwrite = objectMapper.createObjectNode()
                .put("Name", "/app/password").put("Value", "second").put("Overwrite", true);
        handler.handle("PutParameter", overwrite, REGION, "auth");

        assertEquals("SecureString", ssmService.getParameter("/app/password", false, REGION).getType());
        assertEquals(2, ssmService.getParameter("/app/password", false, REGION).getVersion());
    }
}
