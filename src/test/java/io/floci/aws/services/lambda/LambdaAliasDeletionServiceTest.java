package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.lambda.model.LambdaAlias;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.zip.CodeStore;
import io.floci.aws.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LambdaAliasDeletionServiceTest {
    private static final String REGION = "us-east-1";
    private static final String FUNCTION = "routing-snapshot";

    @TempDir
    Path directory;

    private LambdaService service;
    private LambdaFunctionStore functions;
    private LambdaAlias alias;

    @BeforeEach
    void setUp() {
        functions = new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>());
        LambdaAliasStore aliases = mock(LambdaAliasStore.class);
        alias = spy(new LambdaAlias());
        alias.setName("live");
        alias.setFunctionName(FUNCTION);
        alias.setFunctionVersion("2");
        when(aliases.list(REGION, FUNCTION)).thenReturn(List.of(alias));
        service = new LambdaService(functions, null, new LambdaConcurrencyLimiter(),
                mock(WarmPool.class), new CodeStore(directory), new ZipExtractor(), null,
                new RegionResolver(REGION, "000000000000"), null, aliases,
                new LambdaTargetResolver(functions, aliases), null, null, null, null, null,
                null, null, null, null, null, new ObjectMapper());
        service.createFunction(REGION, Map.of("FunctionName", FUNCTION, "Runtime", "nodejs20.x",
                "Role", "arn:aws:iam::000000000000:role/test-role", "Handler", "index.handler"));
        service.publishVersion(REGION, FUNCTION, null);
    }

    @Test
    void weightedReferenceSurvivesRoutingClearAfterItsRead() {
        clearRoutingAfterRead(Map.of("1", 0.1));

        AwsException failure = assertThrows(AwsException.class,
                () -> service.deleteFunction(REGION, FUNCTION, "1"));

        assertEquals("ResourceConflictException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("live"));
        assertTrue(functions.get(REGION, FUNCTION, "1").isPresent());
        verify(alias, times(1)).getRoutingConfig();
    }

    @Test
    void unrelatedRoutingClearDoesNotPreventDeletion() {
        clearRoutingAfterRead(Map.of("3", 0.1));

        service.deleteFunction(REGION, FUNCTION, "1");

        assertTrue(functions.get(REGION, FUNCTION, "1").isEmpty());
        verify(alias, times(1)).getRoutingConfig();
    }

    @Test
    void primaryReferenceStillRefusesDeletionWithoutReadingRouting() {
        alias.setFunctionVersion("1");

        AwsException failure = assertThrows(AwsException.class,
                () -> service.deleteFunction(REGION, FUNCTION, "1"));

        assertEquals("ResourceConflictException", failure.getErrorCode());
        assertTrue(functions.get(REGION, FUNCTION, "1").isPresent());
        verify(alias, never()).getRoutingConfig();
    }

    @Test
    void absentRoutingAllowsUnreferencedDeletion() {
        service.deleteFunction(REGION, FUNCTION, "1");

        assertTrue(functions.get(REGION, FUNCTION, "1").isEmpty());
    }

    private void clearRoutingAfterRead(Map<String, Double> routing) {
        alias.setRoutingConfig(routing);
        doAnswer(invocation -> {
            Map<String, Double> observed = (Map<String, Double>) invocation.callRealMethod();
            alias.setRoutingConfig(null);
            return observed;
        }).when(alias).getRoutingConfig();
    }
}
