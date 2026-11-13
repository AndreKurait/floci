package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.apigateway.model.Authorizer;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.CreateAuthorizerRequest;
import software.amazon.awssdk.services.apigateway.model.CreateRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.CreateRestApiResponse;
import software.amazon.awssdk.services.apigateway.model.DeleteRestApiRequest;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RestAuthorizerCredentialsCleanupTest {
    enum Path { SDK, STORED, IMPORTED }

    private final ApiGatewayClient client = mock(ApiGatewayClient.class);
    private final ApiGatewayService service = mock(ApiGatewayService.class);
    private final RestAuthorizerCredentialsIntegrationTest fixture = new RestAuthorizerCredentialsIntegrationTest() {
        @Override
        ApiGatewayClient client() {
            return client;
        }
    };

    RestAuthorizerCredentialsCleanupTest() {
        fixture.service = service;
        fixture.mapper = new ObjectMapper();
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void primaryFailureRetainsCleanupFailure(Path path) {
        AssertionError primary = new AssertionError("primary assertion");
        IllegalStateException cleanup = new IllegalStateException("cleanup unavailable");
        allocate(path);
        failBody(path, primary);
        failCleanup(path, cleanup);
        AssertionError actual = assertThrows(AssertionError.class, body(path));
        assertSame(primary, actual);
        assertArrayEquals(new Throwable[]{cleanup}, actual.getSuppressed());
        verifyCleanup(path);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void primaryFailureStillCleansExactlyOnce(Path path) {
        AssertionError primary = new AssertionError("primary assertion");
        allocate(path);
        failBody(path, primary);
        assertSame(primary, assertThrows(AssertionError.class, body(path)));
        assertEquals(0, primary.getSuppressed().length);
        verifyCleanup(path);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void failedAllocationDoesNotDeleteUnownedApi(Path path) {
        IllegalStateException failure = new IllegalStateException("allocation failed");
        switch (path) {
            case SDK -> when(client.createRestApi(anyCreateApi())).thenThrow(failure);
            case STORED -> when(service.createRestApi(eq("us-east-1"), anyMap())).thenThrow(failure);
            case IMPORTED -> when(service.importRestApi(eq("us-east-1"), anyString())).thenThrow(failure);
        }
        assertSame(failure, assertThrows(IllegalStateException.class, body(path)));
        verify(service, never()).deleteRestApi(anyString(), anyString());
        verify(client, never()).deleteRestApi(anyDeleteApi());
        if (path == Path.SDK) {
            verify(client).close();
        }
    }

    @Test
    void successfulBodySurfacesCleanupFailure() {
        successfulStoredBody();
        IllegalStateException cleanup = new IllegalStateException("cleanup unavailable");
        failCleanup(Path.STORED, cleanup);
        assertSame(cleanup, assertThrows(IllegalStateException.class, body(Path.STORED)));
        assertEquals(0, cleanup.getSuppressed().length);
        verifyCleanup(Path.STORED);
    }

    @Test
    void successfulBodyCleansExactlyOnce() throws Exception {
        successfulStoredBody();
        fixture.storedAuthorizerJsonKeepsCredentialsAndReadsLegacyRecords();
        verifyCleanup(Path.STORED);
    }

    @Test
    void sdkCloseFailureDoesNotReplacePrimaryOrDeleteFailure() {
        AssertionError primary = new AssertionError("primary assertion");
        IllegalStateException cleanup = new IllegalStateException("cleanup unavailable");
        IllegalStateException close = new IllegalStateException("client close failed");
        allocate(Path.SDK);
        failBody(Path.SDK, primary);
        failCleanup(Path.SDK, cleanup);
        doThrow(close).when(client).close();
        assertSame(primary, assertThrows(AssertionError.class, body(Path.SDK)));
        assertArrayEquals(new Throwable[]{cleanup, close}, primary.getSuppressed());
        verifyCleanup(Path.SDK);
    }

    private void successfulStoredBody() {
        allocate(Path.STORED);
        Authorizer authorizer = new Authorizer();
        authorizer.setAuthorizerCredentials("arn:aws:iam::000000000000:role/authorizer");
        when(service.createAuthorizer(eq("us-east-1"), eq("owned-api"), anyMap())).thenReturn(authorizer);
    }

    private void allocate(Path path) {
        RestApi api = mock(RestApi.class);
        when(api.getId()).thenReturn("owned-api");
        switch (path) {
            case SDK -> when(client.createRestApi(anyCreateApi()))
                    .thenReturn(CreateRestApiResponse.builder().id("owned-api").build());
            case STORED -> when(service.createRestApi(eq("us-east-1"), anyMap())).thenReturn(api);
            case IMPORTED -> when(service.importRestApi(eq("us-east-1"), anyString())).thenReturn(api);
        }
    }

    private void failBody(Path path, AssertionError failure) {
        switch (path) {
            case SDK -> when(client.createAuthorizer(anyCreateAuthorizer())).thenThrow(failure);
            case STORED -> when(service.createAuthorizer(eq("us-east-1"), eq("owned-api"), anyMap()))
                    .thenThrow(failure);
            case IMPORTED -> when(service.getAuthorizers("us-east-1", "owned-api")).thenThrow(failure);
        }
    }

    private void failCleanup(Path path, RuntimeException failure) {
        if (path == Path.SDK) {
            when(client.deleteRestApi(anyDeleteApi())).thenThrow(failure);
        } else {
            doThrow(failure).when(service).deleteRestApi("us-east-1", "owned-api");
        }
    }

    private void verifyCleanup(Path path) {
        if (path == Path.SDK) {
            verify(client).deleteRestApi(argThat(
                    (Consumer<DeleteRestApiRequest.Builder> consumer) -> {
                        DeleteRestApiRequest.Builder request = DeleteRestApiRequest.builder();
                        consumer.accept(request);
                        return "owned-api".equals(request.build().restApiId());
                    }));
            verify(client).close();
        } else {
            verify(service).deleteRestApi("us-east-1", "owned-api");
        }
    }

    private Executable body(Path path) {
        return switch (path) {
            case SDK -> fixture::sdkRetainsCredentialsAndReplacesOrClearsThemAtomically;
            case STORED -> fixture::storedAuthorizerJsonKeepsCredentialsAndReadsLegacyRecords;
            case IMPORTED -> fixture::importedCredentialsPreserveOmittedMergeClearExplicitNullAndRollbackInvalidType;
        };
    }

    private static Consumer<CreateRestApiRequest.Builder> anyCreateApi() {
        return any();
    }

    private static Consumer<CreateAuthorizerRequest.Builder> anyCreateAuthorizer() {
        return any();
    }

    private static Consumer<DeleteRestApiRequest.Builder> anyDeleteApi() {
        return any();
    }
}
