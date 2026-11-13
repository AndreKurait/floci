package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Signs requests with the stock AWS SDK v2 SigV4 signer, as a real client of these endpoints would. */
public final class DerivedSigningKeyTestSigner {

    private DerivedSigningKeyTestSigner() {
    }

    /** The signed request's headers, Host included, in the SDK's own spelling. */
    public static Map<String, String> sign(String host, String pathAndQuery, byte[] body, String accessKeyId, String secret,
                                    String sessionToken, String region, String service, Instant at) {
        return sign(host, pathAndQuery, body, "application/json", accessKeyId, secret, sessionToken, region, service, at);
    }

    /** As {@link #sign}, for a body of another content type. */
    public static Map<String, String> sign(String host, String pathAndQuery, byte[] body, String contentType,
                                           String accessKeyId, String secret, String sessionToken, String region,
                                           String service, Instant at) {
        return sign(host, pathAndQuery, body, contentType, accessKeyId, secret, sessionToken, region, service, at,
                true);
    }

    /** As {@link #sign}; with {@code payloadSigning} false the SDK signs {@code UNSIGNED-PAYLOAD} instead of the body. */
    public static Map<String, String> sign(String host, String pathAndQuery, byte[] body, String contentType,
                                           String accessKeyId, String secret, String sessionToken, String region,
                                           String service, Instant at, boolean payloadSigning) {
        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(URI.create("https://" + host + pathAndQuery))
                .putHeader("Content-Type", contentType)
                .contentStreamProvider(ContentStreamProvider.fromByteArray(body))
                .build();
        AwsCredentialsIdentity identity = sessionToken == null
                ? AwsCredentialsIdentity.create(accessKeyId, secret)
                : AwsSessionCredentialsIdentity.create(accessKeyId, secret, sessionToken);
        SdkHttpRequest signed = AwsV4HttpSigner.create().sign(sign -> sign
                .identity(identity)
                .request(request)
                .payload(ContentStreamProvider.fromByteArray(body))
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service)
                .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, payloadSigning)
                .putProperty(HttpSigner.SIGNING_CLOCK, Clock.fixed(at, ZoneOffset.UTC))).request();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : signed.headers().entrySet()) {
            headers.put(header.getKey(), String.join(",", header.getValue()));
        }
        return headers;
    }

    /** The derived key's HMAC over a string to sign, as an outside verifier would compute it. */
    public static String hmacHex(byte[] key, String data) throws Exception {
        return SigV4RequestValidator.hexEncode(
                SigV4RequestValidator.hmacSha256(key, data));
    }
}
