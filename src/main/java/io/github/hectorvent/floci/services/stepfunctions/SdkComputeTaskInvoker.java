package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsProxyServer;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.vertx.http.HttpServer;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** EC2 and SSM SDK tasks use the public protocol, IAM checks and service implementation. */
@ApplicationScoped
public class SdkComputeTaskInvoker {
    private static final int WIRE_LIMIT = 8 * 1024 * 1024;
    private final IamService iam;
    private final AssumeRolePolicyEvaluator trust;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private final HttpServer httpServer;
    private final TlsProxyServer proxy;
    private final LambdaTaskAuthorizer authorizer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Inject
    public SdkComputeTaskInvoker(IamService iam, AssumeRolePolicyEvaluator trust, EmulatorConfig config,
                                 ObjectMapper mapper, HttpServer httpServer, LambdaTaskAuthorizer authorizer,
                                 TlsProxyServer proxy) {
        this.iam = iam;
        this.trust = trust;
        this.config = config;
        this.mapper = mapper;
        this.httpServer = httpServer;
        this.authorizer = authorizer;
        this.proxy = proxy;
    }

    @PreDestroy
    void close() {
        http.close();
    }

    static boolean supports(StatesIntegration integration) {
        return integration != null && integration.sdk() && integration.suffix().isEmpty()
                && !integration.api().isEmpty() && Character.isLowerCase(integration.api().charAt(0))
                && SdkComputeModel.supports(integration.service(), AslExecutor.capitalizeFirst(integration.api()));
    }

    JsonNode invoke(StatesIntegration integration, JsonNode input, StateMachine machine,
                    String credentialsRoleArn) throws Exception {
        String service = integration.service();
        String prefix = "ec2".equals(service) ? "Ec2" : "Ssm";
        String action = AslExecutor.capitalizeFirst(integration.api());
        SdkComputeModel model = new SdkComputeModel(service, action);
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        AwsArnUtils.Arn role = authorize(machine, owner, credentialsRoleArn);
        String roleArn = credentialsRoleArn == null ? machine.getRoleArn() : credentialsRoleArn;
        URI listener = proxy.localApiEndpoint(httpServer.getLocalBaseUri());
        if (listener == null) {
            throw failure(model, prefix, "ServiceUnavailable", "The AWS API listener is not ready");
        }
        URI uri = listener.resolve("/");
        String accessKey = ApiGatewayTaskInvoker.accessKey();
        String secret = ApiGatewayTaskInvoker.randomSecret(30);
        String token = ApiGatewayTaskInvoker.randomSecret(48);
        String session = "StepFunctions-" + UUID.randomUUID();
        String roleName = role.resource().substring(role.resource().lastIndexOf('/') + 1);
        String principal = iam.findRole(role.accountId(), roleName).map(IamRole::getRoleId).orElse(roleName);
        iam.registerSessionForAccount(role.accountId(), accessKey, secret, token, roleArn,
                Instant.now().plusSeconds(120), null, session, principal + ":" + session);
        try {
            byte[] payload = "ec2".equals(service) ? model.query(action, input)
                    : mapper.writeValueAsBytes(model.jsonRequest(input));
            TreeMap<String, List<String>> headers = new TreeMap<>();
            headers.put("content-type", List.of("ec2".equals(service)
                    ? "application/x-www-form-urlencoded; charset=utf-8" : "application/x-amz-json-1.1"));
            if ("ssm".equals(service)) {
                headers.put("x-amz-target", List.of(model.target(action)));
            }
            ApiGatewayTaskInvoker.sign(headers, uri, payload, owner.region(), service, accessKey, secret, token);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            headers.forEach((name, values) -> {
                if (!"host".equals(name)) {
                    values.forEach(value -> request.header(name, value));
                }
            });
            HttpResponse<byte[]> response = send(http, request.build(), Duration.ofSeconds(60));
            String text = new String(response.body(), StandardCharsets.UTF_8);
            if (response.statusCode() >= 300) {
                if ("ec2".equals(service)) {
                    throw failure(model, prefix, XmlParser.extractFirst(text, "Code", "ServiceException"),
                            XmlParser.extractFirst(text, "Message", "The EC2 request failed"));
                }
                JsonNode error = mapper.readTree(text);
                String code = error.path("__type").asText("ServiceException");
                throw failure(model, prefix, code.substring(code.lastIndexOf('#') + 1),
                        error.path("message").asText(error.path("Message").asText("The SSM request failed")));
            }
            return "ec2".equals(service) ? model.queryResponse(text) : model.jsonResponse(mapper.readTree(text));
        } catch (AwsException error) {
            throw failure(model, prefix, error.getErrorCode(), error.getMessage());
        } catch (TimeoutException error) {
            throw new AslExecutor.FailStateException(prefix + ".SdkClientException",
                    "The AWS API did not return its complete response before the SDK deadline");
        } catch (ExecutionException error) {
            throw new AslExecutor.FailStateException(prefix + ".SdkClientException",
                    "The AWS API response could not be read: " + error.getCause().getMessage());
        } finally {
            iam.unregisterSession(role.accountId(), accessKey);
        }
    }

    static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, Duration deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(request,
                ignored -> HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofByteArray(), WIRE_LIMIT));
        try {
            return response.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            response.cancel(true);
        }
    }

    private AwsArnUtils.Arn authorize(StateMachine machine, AwsArnUtils.Arn owner, String credentialsRoleArn) {
        AwsArnUtils.Arn role = AwsArnUtils.parse(machine.getRoleArn());
        if (!"iam".equals(role.service()) || !role.region().isEmpty() || !role.resource().startsWith("role/")
                || !owner.accountId().equals(role.accountId()) || !owner.partition().equals(role.partition())) {
            throw new AslExecutor.FailStateException("States.Permissions",
                    "The execution role must belong to the state machine account and partition");
        }
        if (config.services().iam().enforcementEnabled()) {
            String name = role.resource().substring(role.resource().lastIndexOf('/') + 1);
            IamRole existing = iam.findRole(role.accountId(), name)
                    .filter(found -> IamService.roleArnMatches(machine.getRoleArn(), found))
                    .orElseThrow(() -> new AslExecutor.FailStateException("States.Permissions",
                            "The execution role does not exist"));
            if (!trust.allowsService(existing.getAssumeRolePolicyDocument(), ServicePrincipals.of("states"),
                    machine.getStateMachineArn(), owner.accountId())) {
                throw new AslExecutor.FailStateException("States.Permissions",
                        "The execution role does not trust Step Functions");
            }
        }
        if (credentialsRoleArn == null) {
            return role;
        }
        AwsArnUtils.Arn selected = AwsArnUtils.parse(credentialsRoleArn);
        if (!"iam".equals(selected.service()) || !selected.region().isEmpty()
                || !selected.resource().startsWith("role/") || !owner.partition().equals(selected.partition())) {
            throw new AslExecutor.FailStateException("States.Permissions", "Invalid task role");
        }
        String name = selected.resource().substring(selected.resource().lastIndexOf('/') + 1);
        if (iam.findRole(selected.accountId(), name)
                .filter(found -> IamService.roleArnMatches(credentialsRoleArn, found)).isEmpty()) {
            throw new AslExecutor.FailStateException("States.Permissions", "The task role does not exist");
        }
        if (config.services().iam().enforcementEnabled()) {
            authorizer.assumeRefusal(machine.getRoleArn(), credentialsRoleArn, machine.getStateMachineArn())
                    .ifPresent(reason -> {
                        throw new AslExecutor.FailStateException("States.Permissions", reason);
                    });
        }
        return selected;
    }

    private static AslExecutor.FailStateException failure(SdkComputeModel model, String prefix, String code,
                                                         String message) {
        return new AslExecutor.FailStateException(model.exceptionName(prefix, code), code + ": " + message);
    }
}
