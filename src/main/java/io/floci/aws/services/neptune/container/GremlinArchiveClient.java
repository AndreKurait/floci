package io.floci.aws.services.neptune.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Executes the bounded GraphSON archive operations against an owned Gremlin backend. */
public final class GremlinArchiveClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] MIME = "application/vnd.gremlin-v3.0+json".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int REQUEST_TIMEOUT_SECONDS = 120;

    private GremlinArchiveClient() {}

    public static void write(String host, int port, String archivePath) {
        execute(host, port, "g.io(archivePath).with(IO.writer,IO.graphson).write().iterate();true", archivePath);
    }

    public static void read(String host, int port, String archivePath) {
        execute(host, port, "g.io(archivePath).with(IO.reader,IO.graphson).read().iterate();true", archivePath);
    }

    private static void execute(String host, int port, String script, String archivePath) {
        String requestId = UUID.randomUUID().toString();
        ObjectNode request = MAPPER.createObjectNode();
        request.putObject("requestId").put("@type", "g:UUID").put("@value", requestId);
        request.put("op", "eval").put("processor", "");
        ObjectNode arguments = request.putObject("args");
        arguments.put("gremlin", script).put("language", "gremlin-groovy");
        arguments.putObject("bindings").put("archivePath", archivePath);
        Reply reply = new Reply(requestId);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            WebSocket socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(endpoint(host, port), reply).get(10, TimeUnit.SECONDS);
            try {
                byte[] document = MAPPER.writeValueAsBytes(request);
                ByteBuffer frame = ByteBuffer.allocate(1 + MIME.length + document.length);
                frame.put((byte) MIME.length).put(MIME).put(document).flip();
                socket.sendBinary(frame, true).get(10, TimeUnit.SECONDS);
                reply.completed.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                socket.abort();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Gremlin archive operation", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Gremlin archive operation failed", exception);
        }
    }

    private static URI endpoint(String host, int port) throws URISyntaxException {
        return new URI("ws", null, host, port, "/gremlin", null, null);
    }

    private static final class Reply implements WebSocket.Listener {
        private final String requestId;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private final ByteArrayOutputStream message = new ByteArrayOutputStream();

        private Reply(String requestId) {
            this.requestId = requestId;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (message.size() + data.remaining() > MAX_RESPONSE_BYTES) {
                completed.completeExceptionally(new IllegalStateException("Gremlin archive response is too large"));
                webSocket.abort();
                return CompletableFuture.completedFuture(null);
            }
            byte[] fragment = new byte[data.remaining()];
            data.get(fragment);
            message.writeBytes(fragment);
            if (last) {
                accept(message.toByteArray());
                message.reset();
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            completed.completeExceptionally(new IllegalStateException("Expected a binary GraphSON response"));
            webSocket.abort();
            return CompletableFuture.completedFuture(null);
        }

        private void accept(byte[] bytes) {
            try {
                JsonNode response = MAPPER.readTree(bytes);
                if (!requestId.equals(response.path("requestId").asText())) {
                    throw new IllegalStateException("Gremlin archive response has a different request identifier");
                }
                int status = response.path("status").path("code").asInt();
                JsonNode data = response.path("result").path("data");
                if ("g:List".equals(data.path("@type").asText())) {
                    data = data.path("@value");
                }
                if (status != 200 || !data.isArray() || data.size() != 1 || !data.get(0).isBoolean()
                        || !data.get(0).booleanValue()) {
                    throw new IllegalStateException("Gremlin archive did not complete: "
                            + response.path("status").path("message").asText());
                }
                completed.complete(null);
            } catch (Exception exception) {
                completed.completeExceptionally(exception);
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            completed.completeExceptionally(new IllegalStateException("Gremlin closed before archive completion"));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            completed.completeExceptionally(error);
        }
    }
}
