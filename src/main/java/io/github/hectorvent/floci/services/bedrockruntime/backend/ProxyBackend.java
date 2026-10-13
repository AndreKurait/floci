package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Forwards Bedrock Converse requests to any OpenAI-compatible {@code /chat/completions}
 * endpoint (Ollama, OpenRouter, LiteLLM, vLLM, ...). InvokeModel is not yet supported and
 * fails fast rather than returning a fabricated response.
 */
@ApplicationScoped
public class ProxyBackend implements BedrockBackend {

    private static final Logger LOG = Logger.getLogger(ProxyBackend.class);

    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;
    private final CurrentVertxRequest currentRequest;

    // Config is immutable for the process's lifetime, so the mapping string is parsed
    // once here rather than on every Converse request.
    private final Map<String, String> modelMapping;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Inject
    public ProxyBackend(ObjectMapper objectMapper, EmulatorConfig config, CurrentVertxRequest currentRequest) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.currentRequest = currentRequest;
        this.modelMapping = parseModelMapping(config.services().bedrockRuntime().proxy().modelMapping().orElse(""));
    }

    @Override
    public ObjectNode converse(String modelId, ObjectNode bedrockRequest) {
        EmulatorConfig.BedrockProxyConfig proxyConfig = config.services().bedrockRuntime().proxy();
        String resolvedModel = resolveModel(modelId, proxyConfig);
        ObjectNode openAiRequest = BedrockOpenAiTranslator.toOpenAiRequest(objectMapper, bedrockRequest, resolvedModel, false);
        HttpRequest request = buildHttpRequest(proxyConfig, openAiRequest);

        long start = System.nanoTime();
        HttpResponse<String> response = invoke(modelId, request, HttpResponse.BodyHandlers.ofString());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        if (response.statusCode() >= 300) {
            throw failedRequest(response.statusCode(), response.body());
        }

        JsonNode openAiResponse;
        try {
            openAiResponse = objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AwsException("ModelErrorException", "Proxy backend returned malformed JSON: " + e.getMessage(), 424);
        }

        return BedrockOpenAiTranslator.toBedrockResponse(objectMapper, openAiResponse, latencyMs);
    }

    @Override
    public byte[] invokeModel(String modelId, byte[] body) {
        throw new AwsException("ValidationException",
                "InvokeModel is not supported by the bedrock-runtime proxy backend; use Converse.", 400);
    }

    @Override
    public Consumer<OutputStream> converseStream(String modelId, ObjectNode bedrockRequest) {
        EmulatorConfig.BedrockProxyConfig proxyConfig = config.services().bedrockRuntime().proxy();
        String resolvedModel = resolveModel(modelId, proxyConfig);
        ObjectNode openAiRequest = BedrockOpenAiTranslator.toOpenAiRequest(objectMapper, bedrockRequest, resolvedModel, true);

        // Everything that can fail with a proper HTTP status - resolving the model, opening the
        // connection, checking the upstream status code - happens here, before any bytes of our
        // own response are written. Once the returned Consumer is invoked, JAX-RS has
        // already committed a 200, so failures discovered while consuming the body stream can
        // only be reported as an in-band Bedrock stream exception event (see
        // BedrockOpenAiTranslator.streamBedrockEvents).
        HttpRequest request = buildHttpRequest(proxyConfig, openAiRequest);
        long startNanos = System.nanoTime();
        // A stalled upstream must be closed on caller disconnect without waiting for another token.
        RoutingContext context = currentRequest.getCurrent();
        CompletableFuture<HttpResponse<InputStream>> pending = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        AtomicBoolean complete = new AtomicBoolean();
        Thread monitor = monitorCancellation(context.response()::closed, complete, () -> {
            pending.thenAccept(response -> closeBody(response.body()));
            pending.cancel(true);
        });
        HttpResponse<InputStream> response;
        try {
            response = pending.get();
            if (response.statusCode() >= 300) {
                String errorBody;
                try {
                    errorBody = new String(response.body().readNBytes(8192), StandardCharsets.UTF_8);
                } catch (IOException unavailable) {
                    LOG.warnv("Failed to read Bedrock proxy error response: modelId={0}, status={1}",
                            modelId, response.statusCode());
                    errorBody = "";
                } finally {
                    closeBody(response.body());
                }
                throw failedRequest(response.statusCode(), errorBody);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pending.thenAccept(completed -> closeBody(completed.body()));
            pending.cancel(true);
            complete.set(true);
            monitor.interrupt();
            throw new AwsException("ModelErrorException", "Proxy stream interrupted.", 424);
        } catch (ExecutionException | CancellationException failure) {
            complete.set(true);
            monitor.interrupt();
            if (failure.getCause() instanceof HttpTimeoutException) {
                LOG.warnv("Bedrock proxy backend timed out: modelId={0}", modelId);
                throw new AwsException("ModelTimeoutException", "Proxy stream timed out.", 408);
            }
            if (failure instanceof ExecutionException) {
                LOG.warnv("Bedrock proxy backend call failed: modelId={0}", modelId);
            }
            throw new AwsException("ModelErrorException", "Proxy stream unavailable or disconnected.", 424);
        } catch (RuntimeException failure) {
            complete.set(true);
            monitor.interrupt();
            throw failure;
        }
        return output -> {
            try (InputStream body = response.body();
                 Stream<String> lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)).lines()) {
                BedrockOpenAiTranslator.streamBedrockEvents(objectMapper, lines, output, startNanos);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            } finally {
                complete.set(true);
                monitor.interrupt();
            }
        };
    }

    private static void closeBody(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // The transport is already disconnected; no response data belongs in diagnostics.
        }
    }

    private static AwsException failedRequest(int response, String errorBody) {
        LOG.warnv("Bedrock proxy backend returned HTTP {0}", response);
        return new AwsException("ModelErrorException",
                "Proxy backend returned HTTP %d: %s".formatted(response, truncate(errorBody, 512)), 424);
    }

    private <T> HttpResponse<T> invoke(String modelId, HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        RoutingContext context = currentRequest.getCurrent();
        CompletableFuture<HttpResponse<T>> pending = httpClient.sendAsync(request, bodyHandler);
        AtomicBoolean complete = new AtomicBoolean();
        Thread monitor = monitorCancellation(context.response()::closed, complete, () -> pending.cancel(true));
        try {
            return pending.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            throw new AwsException("ModelErrorException", "Proxy request interrupted.", 424);
        } catch (ExecutionException | CancellationException failure) {
            if (failure.getCause() instanceof HttpTimeoutException) {
                LOG.warnv("Bedrock proxy backend timed out: modelId={0}", modelId);
                throw new AwsException("ModelTimeoutException", "Proxy backend timed out.", 408);
            }
            if (failure instanceof ExecutionException) {
                LOG.warnv("Bedrock proxy backend call failed: modelId={0}", modelId);
            }
            throw new AwsException("ModelErrorException", "Proxy backend unavailable or disconnected.", 424);
        } finally {
            complete.set(true);
            monitor.interrupt();
        }
    }

    private static Thread monitorCancellation(BooleanSupplier shouldCancel, AtomicBoolean complete, Runnable cancelAction) {
        return Thread.ofVirtual().name("bedrock-proxy-cancellation").start(() -> {
            try {
                while (!complete.get()) {
                    if (shouldCancel.getAsBoolean()) {
                        cancelAction.run();
                        return;
                    }
                    Thread.sleep(100);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private HttpRequest buildHttpRequest(EmulatorConfig.BedrockProxyConfig proxyConfig, ObjectNode openAiRequest) {
        String baseUrl = proxyConfig.url()
                .filter(url -> !url.isBlank())
                .orElseThrow(() -> new AwsException("ValidationException",
                        "floci.services.bedrock-runtime.proxy.url is required when backend=proxy.", 400));

        byte[] requestBody;
        try {
            requestBody = objectMapper.writeValueAsBytes(openAiRequest);
        } catch (Exception e) {
            throw new AwsException("InternalServerException", "Failed to serialize proxy request: " + e.getMessage(), 500);
        }

        HttpRequest.Builder builder;
        try {
            URI uri = URI.create(stripTrailingSlash(baseUrl) + "/chat/completions");
            builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .timeout(Duration.ofSeconds(proxyConfig.requestTimeoutSeconds()))
                    .header("Content-Type", "application/json");
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException",
                    "floci.services.bedrock-runtime.proxy.url is not a valid URL: " + e.getMessage(), 400);
        }
        proxyConfig.apiKey()
                .filter(key -> !key.isBlank())
                .ifPresent(key -> builder.header("Authorization", "Bearer " + key));

        return builder.build();
    }

    String resolveModel(String bedrockModelId, EmulatorConfig.BedrockProxyConfig proxyConfig) {
        String mapped = modelMapping.get(bedrockModelId);
        if (mapped != null) {
            return mapped;
        }
        if (proxyConfig.passthrough()) {
            return bedrockModelId;
        }
        if (proxyConfig.defaultModel().isPresent()) {
            return proxyConfig.defaultModel().get();
        }
        throw new AwsException("ValidationException",
                "No model mapping found for: " + bedrockModelId
                        + ". Set FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_MODEL_MAPPING or "
                        + "FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_DEFAULT_MODEL", 400);
    }

    static Map<String, String> parseModelMapping(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                LOG.warnv("Ignoring malformed bedrock-runtime proxy model-mapping entry: {0}", trimmed);
                continue;
            }
            result.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return result;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) + "…" : value;
    }
}
