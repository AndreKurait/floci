package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.testutil.FreePorts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/** Real TLS boot layout, including the PROXY protocol required by the private listeners. */
public class StepFunctionsProxyTlsProfile extends SelfSignedTlsProfile {
    private static final int PUBLIC_PORT = publicPort();

    private static int publicPort() {
        try {
            return FreePorts.loopbackIpv4Port();
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot select a port for the public TLS proxy", error);
        }
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("floci.port", Integer.toString(PUBLIC_PORT));
        overrides.put("floci.services.iam.enforcement-enabled", "true");
        overrides.put("floci.services.iam.issued-session-verification-enabled", "true");
        overrides.put("quarkus.http.host", "127.0.0.1");
        overrides.put("quarkus.http.test-port", Integer.toString(TlsConfigSource.HTTP_INTERNAL_PORT));
        overrides.put("quarkus.http.test-ssl-port", Integer.toString(TlsConfigSource.HTTPS_INTERNAL_PORT));
        overrides.put("quarkus.http.proxy.use-proxy-protocol", "true");
        return overrides;
    }
}
