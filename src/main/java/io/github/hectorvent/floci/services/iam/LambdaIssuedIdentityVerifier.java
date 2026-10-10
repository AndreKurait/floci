package io.github.hectorvent.floci.services.iam;

import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.iam.model.LambdaExecutionBinding;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** No caller-supplied principal binding: identity comes from the current issuer and launch records. */
@ApplicationScoped
public class LambdaIssuedIdentityVerifier {
    private final IamService iam;
    private final IssuedSessionVerifier sessions;
    private final LambdaFunctionStore functions;
    private final ContainerLifecycleManager containers;
    private final Clock clock;

    @Inject
    public LambdaIssuedIdentityVerifier(IamService iam, IssuedSessionVerifier sessions,
                                        LambdaFunctionStore functions, ContainerLifecycleManager containers) {
        this(iam, sessions, functions, containers, Clock.systemUTC());
    }

    LambdaIssuedIdentityVerifier(IamService iam, IssuedSessionVerifier sessions,
                                  LambdaFunctionStore functions, ContainerLifecycleManager containers, Clock clock) {
        this.iam = iam;
        this.sessions = sessions;
        this.functions = functions;
        this.containers = containers;
        this.clock = clock;
    }

    @RegisterForReflection
    public record LambdaIdentity(String accountId, String functionArn, String functionVersion, String roleArn,
                                 String principalArn, String principalId, String codeSha256,
                                 String containerId, String containerStartedAt) {}

    @RegisterForReflection
    public record Identity(String correlationId, String operation, String kind, String accountId,
                           String roleArn, String roleId, String principalArn, String principalId,
                           boolean sessionPolicyPresent, String expiresAt, LambdaIdentity lambda) {}

    private record Observation(SessionCredential session, SessionCredential root,
                               IssuedSessionVerifier.Snapshot current, IssuedSessionVerifier.Snapshot original,
                               LambdaExecutionBinding binding) {}

    Identity observe(Map<String, String> request, boolean verify) {
        String selector = verify ? "accessKeyId" : request.keySet().stream()
                .filter(key -> !key.equals("correlationId")).findFirst().orElseThrow();
        Observation before = unique(selector, request.get(selector));
        Instant expires = clock.instant().plusSeconds(60);
        if (verify) {
            Instant signed = IssuedSessionVerifier.verifyProof(before.current(), request, clock, 60, 5);
            expires = earlier(expires, signed.plusSeconds(60));
        }
        expires = earlier(expires, before.current().expiry());
        expires = earlier(expires, before.original().expiry());
        require(before.equals(unique(selector, request.get(selector))) && expires.isAfter(clock.instant()));
        LambdaExecutionBinding binding = before.binding();
        IssuedSessionVerifier.Snapshot current = before.current();
        IssuedSessionVerifier.Snapshot root = before.original();
        return new Identity(request.get("correlationId"), verify ? "Verify" : "Lookup",
                before.session() == before.root() ? "LAMBDA_EXECUTION" : "LAMBDA_ASSUMED_ROLE",
                current.account(), current.roleArn(), current.roleId(), current.principalArn(), current.principalId(),
                current.policyPresent(), expires.toString(), new LambdaIdentity(binding.accountId(), binding.functionArn(),
                binding.version(), root.roleArn(), root.principalArn(), root.principalId(), binding.codeSha256(),
                binding.containerId(), binding.containerStartedAt()));
    }

    private Observation unique(String selector, String value) {
        List<Observation> matches = new ArrayList<>();
        for (SessionCredential candidate : iam.issuedSessionCandidates(selector.equals("accessKeyId") ? value : null)) {
            SessionCredential root = candidate.isLambdaExecutionRole() ? candidate : candidate.getLambdaParentSession();
            if (root == null || !root.isLambdaExecutionRole() || root.getLambdaParentSession() != null) {
                continue;
            }
            IssuedSessionVerifier.Snapshot current = sessions.snapshot(candidate, candidate == root);
            IssuedSessionVerifier.Snapshot original = sessions.snapshot(root, true);
            if (current == null || original == null) {
                continue;
            }
            String selected = switch (selector) {
                case "accessKeyId" -> current.accessKey();
                case "principalId" -> current.principalId();
                case "principalArn" -> current.principalArn();
                default -> throw refused();
            };
            if (!value.equals(selected)) {
                continue;
            }
            List<SessionCredential> roots = iam.issuedSessionCandidates(root.getAccessKeyId());
            require(roots.size() == 1 && roots.getFirst() == root);
            LambdaExecutionBinding binding = root.getLambdaExecution();
            require(binding != null && binding.accountId().equals(original.account())
                    && binding.roleArn().equals(original.roleArn())
                    && binding.containerId() != null && binding.containerId().matches("[a-f0-9]{64}")
                    && binding.containerStartedAt() != null && !binding.containerStartedAt().isBlank()
                    && binding.codeSha256() != null && binding.codeSha256().matches("[A-Za-z0-9+/]{43}="));
            AwsArnUtils.Arn functionArn = AwsArnUtils.parse(binding.functionArn());
            require("lambda".equals(functionArn.service()) && binding.accountId().equals(functionArn.accountId())
                    && binding.region().equals(functionArn.region())
                    && (functionArn.resource().equals("function:" + binding.functionName())
                    || functionArn.resource().equals("function:" + binding.functionName() + ":" + binding.version())));
            LambdaFunction function = functions.getForAccount(binding.accountId(), binding.region(),
                    binding.functionName(), binding.version()).orElseThrow(LambdaIssuedIdentityVerifier::refused);
            require(!function.isHotReload() && Objects.equals(binding.functionArn(), function.getFunctionArn())
                    && Objects.equals(binding.roleArn(), function.getRole())
                    && Objects.equals(binding.codeSha256(), function.getCodeSha256())
                    && Objects.equals(binding.revisionId(), function.getRevisionId()));
            InspectContainerResponse container = containers.getDockerClient().inspectContainerCmd(binding.containerId()).exec();
            require(binding.containerId().equals(container.getId()) && container.getState() != null
                    && Boolean.TRUE.equals(container.getState().getRunning())
                    && binding.containerStartedAt().equals(container.getState().getStartedAt()));
            matches.add(new Observation(candidate, root, current, original, binding));
            require(matches.size() == 1);
        }
        require(matches.size() == 1);
        return matches.getFirst();
    }

    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static void require(boolean condition) {
        if (!condition) {
            throw refused();
        }
    }
    private static IllegalArgumentException refused() {
        return new IllegalArgumentException("Lambda identity refused");
    }
}
