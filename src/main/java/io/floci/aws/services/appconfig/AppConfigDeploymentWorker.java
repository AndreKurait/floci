package io.floci.aws.services.appconfig;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Keeps deployment monitoring active even when no caller polls deployment status. */
@ApplicationScoped
public class AppConfigDeploymentWorker {
    private static final Logger LOG = Logger.getLogger(AppConfigDeploymentWorker.class);
    private final AppConfigService service;
    private final ScheduledExecutorService scheduler;

    @Inject
    public AppConfigDeploymentWorker(AppConfigService service) {
        this(service, Executors.newSingleThreadScheduledExecutor(
                task -> new Thread(task, "appconfig-deployment-monitor")));
    }

    AppConfigDeploymentWorker(AppConfigService service, ScheduledExecutorService scheduler) {
        this.service = service;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    void onStart(@Observes StartupEvent event) {
        LOG.debug("AppConfig deployment monitor initialized");
    }

    void tick() {
        try {
            service.advanceDeployments();
        } catch (Exception failure) {
            LOG.warnv(failure, "AppConfig deployment scan failed: {0}", failure.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
