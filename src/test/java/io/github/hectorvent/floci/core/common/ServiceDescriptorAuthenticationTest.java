package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ServiceDescriptorAuthenticationTest {
    @Test
    void requiresExplicitOwnershipIndependentOfSigningScope() {
        ServiceDescriptor descriptor = descriptor();
        assertTrue(descriptor.credentialRequiredRestClasses().isEmpty());
        ServiceDescriptor selected = descriptor.withRequiredRestAuthentication(Set.of(Management.class));
        assertEquals(Set.of(Management.class), selected.credentialRequiredRestClasses());
        assertFalse(selected.credentialRequiredRestClasses().contains(Public.class));
        assertEquals(Set.of("service", "alias"), selected.credentialScopes());
    }

    @Test
    void refusesUnregisteredController() {
        assertThrows(IllegalArgumentException.class,
                () -> descriptor().withRequiredRestAuthentication(Set.of(String.class)));
    }

    @Test
    void ownershipIsImmutableAndSurvivesLegacySignatureConfiguration() {
        Set<Class<?>> classes = new HashSet<>(Set.of(Management.class));
        ServiceDescriptor selected = descriptor().withRequiredRestAuthentication(classes).withLegacyAuthorization();
        classes.clear();
        assertEquals(Set.of(Management.class), selected.credentialRequiredRestClasses());
        assertTrue(selected.acceptsLegacyAuthorization());
        assertThrows(UnsupportedOperationException.class, () -> selected.credentialRequiredRestClasses().clear());
    }

    @Test
    void mixedControllerRetainsOnlyItsDeclaredEndpointAuthenticationMethods() {
        Set<String> methods = new HashSet<>(Set.of("data"));
        ServiceDescriptor descriptor = descriptor().withRequiredRestAuthentication(Set.of(Management.class))
                .withEndpointOwnedRestMethods(Map.of(Management.class, methods)).withLegacyAuthorization();
        methods.clear();
        assertFalse(descriptor.requiresRestCredentials(Management.class, "data"));
        assertTrue(descriptor.requiresRestCredentials(Management.class, "management"));
        assertFalse(descriptor.requiresRestCredentials(Public.class, "management"));
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.endpointOwnedRestMethods().get(Management.class).clear());
        assertThrows(IllegalArgumentException.class,
                () -> descriptor.withEndpointOwnedRestMethods(Map.of(Public.class, Set.of("data"))));
    }

    private ServiceDescriptor descriptor() {
        return new ServiceDescriptor("service", "service", true, true, null, null, 0, null,
                ServiceProtocol.REST_JSON, Set.of(ServiceProtocol.REST_JSON), Set.of(),
                Set.of("service", "alias"), Set.of(), Set.of(Management.class, Public.class), Set.of());
    }

    private static class Management { }

    private static class Public { }
}
