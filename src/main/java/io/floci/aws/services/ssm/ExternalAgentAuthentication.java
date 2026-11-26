package io.floci.aws.services.ssm;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ssm.SsmChannelAuthentication.Caller;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.Node;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;

/** Retains authenticated external agents registered with SSM outside Floci's EC2 instances. */
@ApplicationScoped
public class ExternalAgentAuthentication {
    private final SsmCommandService commands;
    private final Ec2Service ec2;
    private final SsmChannelAuthentication authentication;

    @Inject
    public ExternalAgentAuthentication(SsmCommandService commands, Ec2Service ec2,
                                       SsmChannelAuthentication authentication) {
        this.commands = commands;
        this.ec2 = ec2;
        this.authentication = authentication;
    }

    public boolean supports(Node node) {
        return RequestScopes.callAs(node.accountId(), node.region(), () ->
                commands.isInstanceRegistered(node.instanceId(), node.region())
                        && ec2.findInstanceForAccount(node.accountId(), node.region(), node.instanceId()).isEmpty());
    }

    public void authorize(HttpServerRequest request, byte[] body, String action, String resource, Node expected) {
        Caller caller = authentication.verify(request.method().name(), URI.create(request.absoluteURI()),
                request::getHeader, request::getParam, body, request.remoteAddress().hostAddress(), request.isSSL());
        if (!caller.account().equals(expected.accountId()) || !caller.region().equals(expected.region())
                || !supports(expected)) {
            throw SsmChannelAuthentication.denied("The external agent does not own this channel.");
        }
        authentication.authorizeNode(caller, expected.instanceId());
        String operation = action.substring(action.indexOf(':') + 1);
        if ("*".equals(resource)) {
            RequestScopes.runAs(caller.account(), caller.region(), () -> authentication.authorize(caller, operation));
        } else {
            authentication.authorizeResource(caller, operation, resource);
        }
    }
}
