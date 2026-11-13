package io.floci.aws.services.amazonmq;

import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.SignedServiceRoute;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/** MQ and MSK share their public configuration paths; signing scope selects the controller. */
@Provider
@PreMatching
public class AmazonMqConfigurationRouteFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/_amazonmq-config";
    private final SignedServiceRoute route;

    @Inject
    public AmazonMqConfigurationRouteFilter(ResolvedServiceCatalog catalog) {
        route = new SignedServiceRoute(catalog, "mq", INTERNAL_PREFIX,
                SignedServiceRoute.under("/v1/configurations"));
    }

    @Override
    public void filter(ContainerRequestContext context) {
        route.apply(context);
    }
}
