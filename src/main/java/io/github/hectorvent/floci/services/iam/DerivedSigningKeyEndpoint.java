package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.NetworkExposureGuard;
import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.TreeSet;

/**
 * Startup gate of the derived signing key endpoint. When it is disabled nothing below
 * {@code enabled} is read. When it is enabled, invalid settings refuse startup, as
 * {@link NetworkExposureGuard} refuses an unsafe bind.
 */
@ApplicationScoped
public class DerivedSigningKeyEndpoint {

    private static final Logger LOG = Logger.getLogger(DerivedSigningKeyEndpoint.class);

    private final EmulatorConfig config;
    private final ResolvedServiceCatalog catalog;
    private final Config mpConfig;
    private volatile DerivedSigningKeySettings settings;

    @Inject
    public DerivedSigningKeyEndpoint(EmulatorConfig config, ResolvedServiceCatalog catalog, Config mpConfig) {
        this.config = config;
        this.catalog = catalog;
        this.mpConfig = mpConfig;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE + 1) StartupEvent ignored) {
        settings();
    }

    /** The validated settings, or empty when the endpoint is disabled. */
    Optional<DerivedSigningKeySettings> settings() {
        EmulatorConfig.DerivedSigningKeysConfig keys = config.services().iam().derivedSigningKeys();
        if (!keys.enabled()) {
            return Optional.empty();
        }
        DerivedSigningKeySettings resolved = settings;
        if (resolved == null) {
            synchronized (this) {
                resolved = settings;
                if (resolved == null) {
                    resolved = DerivedSigningKeySettings.from(keys, config.services().iam().enabled(),
                            name -> catalog.byCredentialScope(name).isPresent());
                    settings = resolved;
                    LOG.infov("Derived signing key endpoint enabled: {0} regions, {1} services, levels {2}",
                            resolved.regions().size(), resolved.services().size(), new TreeSet<>(resolved.levels()));
                    if (!NetworkExposureGuard.isLoopback(NetworkExposureGuard.publicHost(mpConfig))
                            && !config.tls().enabled()) {
                        LOG.warn("Derived signing key endpoint is enabled on a non-loopback listener without"
                                + " floci.tls.enabled: derived keys will cross the network in clear text.");
                    }
                }
            }
        }
        return Optional.of(resolved);
    }
}
