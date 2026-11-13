package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestHost;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SessionTokenCheck;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.core.common.auth.SigV4SignedRequest;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Clock;
import java.util.function.UnaryOperator;

/** Authenticates both control-channel handshakes before they can receive a node's commands. */
@ApplicationScoped
public class SsmChannelAuthentication {
    private static final Logger LOG = Logger.getLogger(SsmChannelAuthentication.class);

    private final IamService iam;
    private final RegionResolver regions;
    private final CallerCallAuthorizer authorizer;
    private final Clock clock;
    private final Ec2Service ec2;

    @Inject
    public SsmChannelAuthentication(IamService iam, RegionResolver regions,
                                    CallerCallAuthorizer authorizer, Clock clock, Ec2Service ec2) {
        this.iam = iam;
        this.regions = regions;
        this.authorizer = authorizer;
        this.clock = clock;
        this.ec2 = ec2;
    }

    record Caller(String account, String region, String accessKey, String arn, String sourceIp,
                  boolean secureTransport) {}

    Caller authenticate(String method, URI uri, UnaryOperator<String> headers,
                        UnaryOperator<String> query, byte[] body, String action,
                        String sourceIp, boolean secureTransport) {
        String authorization = headers.apply("Authorization");
        SigV4SignedRequest signed = authorization == null || authorization.isBlank()
                ? SigV4SignedRequest.fromQuery(query)
                : SigV4SignedRequest.fromHeader(authorization, headers.apply("X-Amz-Date"));
        CredentialScope scope = signed == null ? null : CredentialScope.parse(signed.credential());
        if (scope == null || !"ssmmessages".equals(scope.service())
                || AwsPartitions.forRegion(scope.region()).isEmpty()
                || (signed.presigned() && !SigV4SignedRequest.ALGORITHM.equals(query.apply("X-Amz-Algorithm")))
                || !signed.amzDate().startsWith(scope.date())
                || signed.freshness(clock.instant()) != SigV4SignedRequest.Freshness.FRESH
                || !SigV4RequestValidator.containsHeader(signed.signedHeaders(), "host")) {
            throw denied("A current ssmmessages SigV4 signature is required.");
        }
        String token = signed.presigned() ? query.apply("X-Amz-Security-Token")
                : headers.apply("X-Amz-Security-Token");
        boolean localTest = "test".equals(scope.accessKeyId());
        String account = localTest ? regions.getDefaultAccountId()
                : iam.resolveAccountId(scope.accessKeyId()).orElseThrow(() -> denied("Invalid signing credential."));
        return RequestScopes.callAs(account, scope.region(), () -> {
            if (SessionTokenCheck.of(iam, scope.accessKeyId(), token) != SessionTokenCheck.VALID) {
                throw denied("Invalid session token.");
            }
            String secret = localTest ? "test" : iam.findSecretKey(scope.accessKeyId(), token)
                    .orElseThrow(() -> denied("Invalid signing credential."));
            try {
                String canonicalHeaders = SigV4Canonicalization.canonicalHeaders(signed.signedHeaders(),
                        name -> "host".equals(name) ? RequestHost.of(headers.apply(name), uri) : headers.apply(name));
                String payloadHash = SigV4Canonicalization.payloadHash(headers.apply("x-amz-content-sha256"),
                        signed.signedHeaders(), body, false);
                if (!SigV4Canonicalization.signatureMatches(method, uri.getRawPath(),
                        SigV4Canonicalization.canonicalQueryString(uri.getRawQuery(), signed.presigned()),
                        canonicalHeaders, signed.signedHeaders(), payloadHash, signed.amzDate(),
                        scope, secret, signed.signature())) {
                    throw denied("The request signature does not match.");
                }
            } catch (AwsException denied) {
                throw denied;
            } catch (Exception invalid) {
                LOG.debugv(invalid, "Could not verify an SSM control-channel signature");
                throw denied("The request signature could not be verified.");
            }
            String arn = localTest ? regions.buildGlobalArn("iam", account, "root")
                    : iam.resolveCallerArn(scope.accessKeyId())
                            .orElseThrow(() -> denied("Invalid signing identity."));
            Caller caller = new Caller(account, scope.region(), scope.accessKeyId(), arn, sourceIp, secureTransport);
            authorize(caller, action);
            return caller;
        });
    }

    void authorize(Caller caller, String action) {
        if (!"test".equals(caller.accessKey())
                && !iam.resolveAccountId(caller.accessKey()).filter(caller.account()::equals).isPresent()) {
            throw denied("The channel credential is no longer valid.");
        }
        Decision decision = authorizer.authorizeUnscopedRequest(caller.accessKey(), caller.account(),
                caller.arn(), "ssmmessages:" + action, caller.region(), caller.sourceIp(), caller.secureTransport());
        if (decision != Decision.ALLOW) {
            throw denied("The caller cannot perform ssmmessages:" + action + ".");
        }
    }

    void authorizeNode(Caller caller, String instanceId) {
        RequestScopes.runAs(caller.account(), caller.region(), () ->
                iam.findEc2SessionInstanceId(caller.accessKey()).ifPresent(ownedInstance -> {
                    if (!ownedInstance.equals(instanceId)
                            || ec2.findInstanceForAccount(caller.account(), caller.region(), instanceId).isEmpty()) {
                        throw denied("The instance credential belongs to a different managed node.");
                    }
                }));
    }

    static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }
}
