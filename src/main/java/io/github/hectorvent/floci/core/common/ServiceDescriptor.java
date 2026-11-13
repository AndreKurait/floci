package io.github.hectorvent.floci.core.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record ServiceDescriptor(
        String externalKey,
        String configKey,
        boolean enabled,
        boolean includeInStatus,
        String storageKey,
        String storageMode,
        long storageFlushIntervalMs,
        String xmlNamespace,
        ServiceProtocol defaultProtocol,
        Set<ServiceProtocol> supportedProtocols,
        Set<String> targetPrefixes,
        Set<String> credentialScopes,
        Set<String> cborSdkServiceIds,
        Set<Class<?>> resourceClasses,
        Set<String> jsonOnlyTargetPrefixes,
        boolean acceptsLegacyAuthorization,
        Set<Class<?>> credentialRequiredRestClasses,
        Map<Class<?>, Set<String>> endpointOwnedRestMethods
) {

    public ServiceDescriptor {
        credentialRequiredRestClasses = Set.copyOf(credentialRequiredRestClasses);
        Map<Class<?>, Set<String>> methods = new LinkedHashMap<>();
        endpointOwnedRestMethods.forEach((resource, names) -> methods.put(resource, Set.copyOf(names)));
        endpointOwnedRestMethods = Map.copyOf(methods);
        if (!credentialRequiredRestClasses.containsAll(endpointOwnedRestMethods.keySet())) {
            throw new IllegalArgumentException("Endpoint authentication exceptions must name a credential-required resource");
        }
        if (!resourceClasses.containsAll(credentialRequiredRestClasses)) {
            throw new IllegalArgumentException("Authentication ownership must name a registered REST resource");
        }
    }

    public ServiceDescriptor(String externalKey, String configKey, boolean enabled, boolean includeInStatus,
                             String storageKey, String storageMode, long storageFlushIntervalMs,
                             String xmlNamespace, ServiceProtocol defaultProtocol,
                             Set<ServiceProtocol> supportedProtocols, Set<String> targetPrefixes,
                             Set<String> credentialScopes, Set<String> cborSdkServiceIds,
                             Set<Class<?>> resourceClasses, Set<String> jsonOnlyTargetPrefixes,
                             boolean acceptsLegacyAuthorization) {
        this(externalKey, configKey, enabled, includeInStatus, storageKey, storageMode, storageFlushIntervalMs,
                xmlNamespace, defaultProtocol, supportedProtocols, targetPrefixes, credentialScopes,
                cborSdkServiceIds, resourceClasses, jsonOnlyTargetPrefixes, acceptsLegacyAuthorization, Set.of(), Map.of());
    }

    public ServiceDescriptor(String externalKey, String configKey, boolean enabled, boolean includeInStatus,
                             String storageKey, String storageMode, long storageFlushIntervalMs,
                             String xmlNamespace, ServiceProtocol defaultProtocol,
                             Set<ServiceProtocol> supportedProtocols, Set<String> targetPrefixes,
                             Set<String> credentialScopes, Set<String> cborSdkServiceIds,
                             Set<Class<?>> resourceClasses, Set<String> jsonOnlyTargetPrefixes) {
        this(externalKey, configKey, enabled, includeInStatus, storageKey, storageMode, storageFlushIntervalMs,
                xmlNamespace, defaultProtocol, supportedProtocols, targetPrefixes, credentialScopes,
                cborSdkServiceIds, resourceClasses, jsonOnlyTargetPrefixes, false);
    }

    public ServiceDescriptor withLegacyAuthorization() {
        return new ServiceDescriptor(externalKey, configKey, enabled, includeInStatus, storageKey, storageMode,
                storageFlushIntervalMs, xmlNamespace, defaultProtocol, supportedProtocols, targetPrefixes,
                credentialScopes, cborSdkServiceIds, resourceClasses, jsonOnlyTargetPrefixes, true,
                credentialRequiredRestClasses, endpointOwnedRestMethods);
    }

    /** Required credentials are route metadata, independent of IAM action mappings and signing aliases. */
    public ServiceDescriptor withRequiredRestAuthentication(Set<Class<?>> resourceClassesRequiringCredentials) {
        return new ServiceDescriptor(externalKey, configKey, enabled, includeInStatus, storageKey, storageMode,
                storageFlushIntervalMs, xmlNamespace, defaultProtocol, supportedProtocols, targetPrefixes,
                credentialScopes, cborSdkServiceIds, resourceClasses, jsonOnlyTargetPrefixes,
                acceptsLegacyAuthorization, resourceClassesRequiringCredentials, endpointOwnedRestMethods);
    }

    /** Declares a service whose registered REST controllers all require AWS credentials. */
    public ServiceDescriptor withRequiredRestAuthentication() {
        return withRequiredRestAuthentication(resourceClasses);
    }

    /** Mixed controllers retain endpoint-owned authentication on these specific Java resource methods. */
    public ServiceDescriptor withEndpointOwnedRestMethods(Map<Class<?>, Set<String>> methods) {
        return new ServiceDescriptor(externalKey, configKey, enabled, includeInStatus, storageKey, storageMode,
                storageFlushIntervalMs, xmlNamespace, defaultProtocol, supportedProtocols, targetPrefixes,
                credentialScopes, cborSdkServiceIds, resourceClasses, jsonOnlyTargetPrefixes,
                acceptsLegacyAuthorization, credentialRequiredRestClasses, methods);
    }

    public boolean requiresRestCredentials(Class<?> resourceClass, String methodName) {
        return credentialRequiredRestClasses.contains(resourceClass)
                && !endpointOwnedRestMethods.getOrDefault(resourceClass, Set.of()).contains(methodName);
    }

    public boolean supportsStorage() {
        return storageKey != null;
    }

    public boolean supportsProtocol(ServiceProtocol protocol) {
        return supportedProtocols.contains(protocol);
    }
}
