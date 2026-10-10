package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsEndpoints;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.EndpointType;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.vertx.http.HttpServer;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** Regional, same-account REST API requests under the state machine's IAM execution role. */
@ApplicationScoped
public class ApiGatewayTaskInvoker {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final ApiGatewayService apiGateway;
    private final IamService iam;
    private final AssumeRolePolicyEvaluator trust;
    private final IamPolicyEvaluator policies;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final HttpServer httpServer;

    @Inject
    public ApiGatewayTaskInvoker(ApiGatewayService apiGateway, IamService iam,
                                 AssumeRolePolicyEvaluator trust, IamPolicyEvaluator policies,
                                 ObjectMapper mapper, HttpServer httpServer) {
        this.apiGateway = apiGateway;
        this.iam = iam;
        this.trust = trust;
        this.policies = policies;
        this.mapper = mapper;
        this.httpServer = httpServer;
    }

    @PreDestroy
    void close() {
        http.close();
    }

    public JsonNode invoke(JsonNode input, StateMachine machine) throws Exception {
        if (!"IAM_ROLE".equals(input.path("AuthType").asText())
                || !"POST".equals(input.path("Method").asText())
                || input.has("QueryParameters") || input.has("AllowNullValues")) {
            throw unsupported("only REST POST with IAM_ROLE and JSON request body is supported");
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        String endpoint = input.path("ApiEndpoint").asText();
        int separator = endpoint.indexOf('.');
        String apiId = separator < 0 ? "" : endpoint.substring(0, separator);
        if (!apiId.matches("[a-z0-9]+")
                || !endpoint.equals(AwsEndpoints.executeApiHost(apiId, owner.region()))) {
            throw unsupported("endpoint must be a regional execute-api hostname in the execution region");
        }
        ApiGatewayService.ApiOwner apiOwner = apiGateway.findRestApiOwner(apiId)
                .orElseThrow(() -> failure(404, "API does not exist"));
        if (!owner.accountId().equals(apiOwner.accountId()) || !owner.region().equals(apiOwner.region())) {
            throw failure(403, "API belongs to a different account or region");
        }
        RestApi api = apiGateway.getRestApi(owner.region(), apiId);
        if (api.getEndpointConfiguration() != null
                && api.getEndpointConfiguration().getTypes().contains(EndpointType.PRIVATE)) {
            throw unsupported("private APIs are not supported by this integration");
        }
        if (api.getPolicy() != null && !api.getPolicy().isBlank()) {
            throw unsupported("API resource policies are not supported by this integration");
        }
        String stage = input.path("Stage").asText();
        String path = input.path("Path").asText();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!stage.matches("[A-Za-z0-9_-]+") || path.isBlank()
                || List.of(path.split("/", -1)).stream().anyMatch(part -> part.isEmpty()
                    || part.equals(".") || part.equals(".."))) {
            throw unsupported("a named stage and non-empty resource path are required");
        }
        IamRole role = authorize(machine, owner, apiId, stage, path);
        TreeMap<String, String> headers = requestHeaders(input.path("Headers"));
        headers.putIfAbsent("content-type", "application/json");
        JsonNode body = input.path("RequestBody");
        if (!body.isObject() || containsNull(body)) {
            throw unsupported("request body must be a JSON object without null values");
        }
        byte[] payload = mapper.writeValueAsBytes(body);
        int httpPort = httpServer.getPort();
        if (httpPort <= 0) {
            throw new AslExecutor.FailStateException("ApiGateway.IllegalStateException", "API listener is not ready");
        }
        String encodedPath = List.of(path.split("/", -1)).stream()
                .map(SigV4Canonicalization::uriEncode).collect(Collectors.joining("/"));
        URI uri = URI.create("http://127.0.0.1:" + httpPort + "/restapis/" + apiId + "/" + stage
                + "/_user_request_/" + encodedPath);
        String accessKey = accessKey();
        String secret = randomSecret(30);
        String token = randomSecret(48);
        String sessionName = "StepFunctions-" + UUID.randomUUID();
        iam.registerSession(accessKey, secret, token, role.getArn(), Instant.now().plusSeconds(120),
                null, owner.accountId(), sessionName, role.getRoleId() + ":" + sessionName);
        try {
            sign(headers, uri, payload, owner.region(), accessKey, secret, token);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            headers.forEach((name, value) -> {
                if (!"host".equals(name)) {
                    request.header(name, value);
                }
            });
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            String text = new String(response.body(), StandardCharsets.UTF_8);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure(response.statusCode(), text);
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("StatusCode", response.statusCode());
            Response.Status status = Response.Status.fromStatusCode(response.statusCode());
            result.put("StatusText", status == null ? "" : status.getReasonPhrase());
            result.set("Headers", mapper.valueToTree(response.headers().map()));
            try {
                result.set("ResponseBody", mapper.readTree(text));
            } catch (IOException ignored) {
                result.put("ResponseBody", text);
            }
            return result;
        } catch (IOException error) {
            throw new AslExecutor.FailStateException("ApiGateway." + error.getClass().getSimpleName(),
                    "API request could not complete");
        } finally {
            iam.unregisterSession(owner.accountId(), accessKey);
        }
    }

    private IamRole authorize(StateMachine machine, AwsArnUtils.Arn owner,
                              String apiId, String stage, String path) {
        AwsArnUtils.Arn roleId;
        try {
            roleId = AwsArnUtils.parse(machine.getRoleArn());
        } catch (IllegalArgumentException error) {
            throw failure(403, "Execution role is invalid");
        }
        if (!"iam".equals(roleId.service()) || !roleId.region().isEmpty()
                || !roleId.resource().startsWith("role/")
                || !roleId.accountId().equals(owner.accountId()) || !roleId.partition().equals(owner.partition())) {
            throw failure(403, "Execution role must belong to the state machine account");
        }
        String name = roleId.resource().substring(roleId.resource().lastIndexOf('/') + 1);
        IamRole role = iam.findRole(owner.accountId(), name)
                .filter(found -> machine.getRoleArn().equals(found.getArn()))
                .orElseThrow(() -> failure(403, "Execution role does not exist"));
        if (!trust.allowsService(role.getAssumeRolePolicyDocument(),
                "states." + AwsRegions.dnsSuffixFor(owner.region()), machine.getStateMachineArn(), owner.accountId())) {
            throw failure(403, "Execution role does not trust Step Functions");
        }
        String resource = new AwsArnUtils.Arn(owner.partition(), "execute-api", owner.region(), owner.accountId(),
                apiId + "/" + stage + "/POST/" + path).toString();
        CallerContext caller = iam.resolvePrincipalContext(role.getArn()).withPrincipalArn(role.getArn());
        if (policies.simulatePrincipalPolicy(caller, "execute-api:Invoke", resource,
                Map.of("aws:RequestedRegion", List.of(owner.region()),
                        "aws:PrincipalArn", List.of(role.getArn()))) != IamPolicyEvaluator.SimulationDecision.ALLOWED) {
            throw failure(403, "Execution role cannot invoke this API resource");
        }
        return role;
    }

    private static boolean containsNull(JsonNode value) {
        if (value.isNull()) {
            return true;
        }
        for (JsonNode child : value) {
            if (containsNull(child)) {
                return true;
            }
        }
        return false;
    }

    private static TreeMap<String, String> requestHeaders(JsonNode input) {
        TreeMap<String, String> result = new TreeMap<>();
        if (input.isMissingNode()) {
            return result;
        }
        if (!input.isObject()) {
            throw unsupported("headers must be an object of single-value string arrays");
        }
        input.properties().forEach(entry -> {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            JsonNode values = entry.getValue();
            if (!name.matches("[a-z0-9-]+") || name.startsWith("x-amz") || name.startsWith("x-forwarded")
                    || List.of("authorization", "host", "content-length", "connection").contains(name)
                    || !values.isArray() || values.size() != 1 || !values.get(0).isTextual()
                    || values.get(0).asText().contains("\r") || values.get(0).asText().contains("\n")
                    || result.putIfAbsent(name, values.get(0).asText()) != null) {
                throw unsupported("header name or value is not supported");
            }
        });
        return result;
    }

    private static void sign(TreeMap<String, String> headers, URI uri, byte[] payload, String region,
                             String accessKey, String secret, String token) throws Exception {
        String stamp = AMZ_DATE.format(Instant.now());
        headers.put("host", uri.getAuthority());
        headers.put("x-amz-date", stamp);
        headers.put("x-amz-security-token", token);
        String signed = String.join(";", headers.keySet());
        CredentialScope scope = new CredentialScope(accessKey, stamp.substring(0, 8), region, "execute-api");
        String canonical = "POST\n" + uri.getRawPath() + "\n\n"
                + SigV4Canonicalization.canonicalHeaders(signed, headers::get) + "\n" + signed + "\n"
                + SigV4RequestValidator.sha256Hex(payload);
        String toSign = "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope.credentialScope() + "\n"
                + SigV4RequestValidator.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(secret, scope.date(), region, "execute-api"), toSign));
        headers.put("authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope.credentialScope()
                + ", SignedHeaders=" + signed + ", Signature=" + signature);
    }

    private static String accessKey() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder value = new StringBuilder("ASIA");
        for (int index = 0; index < 16; index++) {
            value.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private static String randomSecret(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    private static AslExecutor.FailStateException failure(int status, String cause) {
        return new AslExecutor.FailStateException("ApiGateway." + status, cause);
    }

    private static AslExecutor.FailStateException unsupported(String feature) {
        return new AslExecutor.FailStateException("States.TaskFailed", "Unsupported apigateway:invoke feature: " + feature);
    }
}
