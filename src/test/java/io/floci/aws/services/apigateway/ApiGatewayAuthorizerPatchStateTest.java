package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.apigateway.model.Authorizer;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class ApiGatewayAuthorizerPatchStateTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String OLD_ROLE = "arn:aws:iam::000000000000:role/original";
    private static final String NEW_ROLE = "arn:aws:iam::000000000000:role/replacement";
    private ApiGatewayService service;
    private String api;
    private Authorizer authorizer;

    @BeforeEach
    void createAuthorizer() {
        service = new ApiGatewayService(new InMemoryStorageFactory(ACCOUNT),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(TlsCertificateManager.class),
                new RegionResolver(REGION, ACCOUNT));
        api = service.createRestApi(REGION, Map.of("name", "authorizer-patch")).getId();
        authorizer = service.createAuthorizer(REGION, api, Map.of(
                "name", "original", "type", "TOKEN", "identitySource", "method.request.header.Authorization",
                "authorizerCredentials", OLD_ROLE, "providerARNs", List.of("original-pool")));
    }

    @Test
    void invalidIdentityPatchDoesNotPartiallyReplaceCredentials() {
        assertThrows(AwsException.class, () -> service.updateAuthorizer(REGION, api, authorizer.getId(), List.of(
                patch("replace", "/authorizerCredentials", NEW_ROLE),
                patch("replace", "/name", "changed"),
                Map.of("op", "remove", "path", "/identitySource"))));
        Authorizer stored = service.getAuthorizer(REGION, api, authorizer.getId());
        assertEquals(OLD_ROLE, stored.getAuthorizerCredentials());
        assertEquals("original", stored.getName());
        assertEquals("method.request.header.Authorization", stored.getIdentitySource());
    }

    @Test
    void credentialsAndProviderArnChangesCommitTogether() {
        service.updateAuthorizer(REGION, api, authorizer.getId(), List.of(
                patch("replace", "/authorizerCredentials", NEW_ROLE),
                patch("remove", "/providerARNs", "original-pool"),
                patch("add", "/providerARNs", "replacement-pool")));
        assertEquals(NEW_ROLE, authorizer.getAuthorizerCredentials());
        assertEquals(List.of("replacement-pool"), authorizer.getProviderARNs());
    }

    @Test
    void nullReplacementClearsCredentialsButAddCannotChangeThem() {
        assertThrows(AwsException.class, () -> service.updateAuthorizer(REGION, api, authorizer.getId(),
                List.of(patch("add", "/authorizerCredentials", NEW_ROLE))));
        assertEquals(OLD_ROLE, authorizer.getAuthorizerCredentials());
        Map<String, String> clear = new HashMap<>();
        clear.put("op", "replace");
        clear.put("path", "/authorizerCredentials");
        clear.put("value", null);
        service.updateAuthorizer(REGION, api, authorizer.getId(), List.of(clear));
        assertNull(authorizer.getAuthorizerCredentials());
    }

    @Test
    void snapshotRestorationRestoresCredentialsAndNullableFields() {
        Authorizer snapshot = new Authorizer();
        snapshot.setId(authorizer.getId());
        snapshot.setName("snapshot");
        snapshot.setType("REQUEST");
        snapshot.setAuthorizerResultTtlInSeconds("0");
        snapshot.setAuthorizerCredentials(NEW_ROLE);
        service.restoreAuthorizer(REGION, api, snapshot);
        Authorizer stored = service.getAuthorizer(REGION, api, authorizer.getId());
        assertEquals(NEW_ROLE, stored.getAuthorizerCredentials());
        assertEquals("snapshot", stored.getName());
        assertEquals("REQUEST", stored.getType());
        assertNull(stored.getAuthorizerUri());
        assertNull(stored.getIdentitySource());
        assertNull(stored.getProviderARNs());
        snapshot.setAuthorizerCredentials(null);
        service.restoreAuthorizer(REGION, api, snapshot);
        assertNull(stored.getAuthorizerCredentials());
    }

    private static Map<String, String> patch(String operation, String path, String value) {
        return Map.of("op", operation, "path", path, "value", value);
    }
}
