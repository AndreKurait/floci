package com.floci.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Registers emulator fixtures for management API tests; it does not run or impersonate a live agent execution. */
final class SsmManagedNodeFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = client();

    private SsmManagedNodeFixture() {}

    static void register(String instance) throws Exception {
        if (TestFixtures.isRealAws()) {
            throw new IllegalStateException("This managed-node fixture requires an emulator endpoint.");
        }
        byte[] body = JSON.writeValueAsBytes(Map.of("InstanceId", instance,
                "AgentName", "compatibility-fixture", "AgentVersion", "0.0.0", "PlatformType", "Linux"));
        SdkHttpFullRequest signed = Aws4Signer.create().sign(SdkHttpFullRequest.builder()
                        .uri(TestFixtures.endpoint()).method(SdkHttpMethod.POST)
                        .putHeader("Content-Type", "application/x-amz-json-1.1")
                        .putHeader("X-Amz-Target", "AmazonSSM.UpdateInstanceInformation")
                        .contentStreamProvider(() -> new ByteArrayInputStream(body)).build(),
                Aws4SignerParams.builder().awsCredentials(AwsBasicCredentials.create("test", "test"))
                        .signingRegion(TestFixtures.region()).signingName("ssm").build());
        HttpRequest.Builder request = HttpRequest.newBuilder(TestFixtures.endpoint()).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        signed.headers().forEach((key, values) -> {
            if (!"host".equalsIgnoreCase(key) && !"content-length".equalsIgnoreCase(key)) {
                values.forEach(value -> request.header(key, value));
            }
        });
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage("Managed node registration: %s", response.body()).isEqualTo(200);
    }

    private static HttpClient client() {
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (!TestFixtures.isRealAws() && "https".equalsIgnoreCase(TestFixtures.endpoint().getScheme())) {
            try {
                builder.sslContext(TestFixtures.flociCaTrust());
            } catch (Exception failure) {
                throw new IllegalStateException("Could not trust the emulator CA.", failure);
            }
        }
        return builder.build();
    }
}
