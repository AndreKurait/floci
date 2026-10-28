package io.github.hectorvent.floci.core.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** Bounded inflation for the AWS JSON operations that accept compressed requests. */
final class AwsJsonGzipBody {

    private AwsJsonGzipBody() {
    }

    static byte[] decode(InputStream original, int maxBodyBytes) {
        try (GZIPInputStream compressed = new GZIPInputStream(original)) {
            byte[] body = compressed.readNBytes(maxBodyBytes + 1);
            if (body.length > maxBodyBytes) {
                throw new AwsException("RequestEntityTooLargeException",
                        "Request size exceeded " + maxBodyBytes + " bytes", 413);
            }
            return body;
        } catch (IOException error) {
            throw new AwsException("InvalidRequestException", "Failed to decode request body", 400);
        }
    }
}
