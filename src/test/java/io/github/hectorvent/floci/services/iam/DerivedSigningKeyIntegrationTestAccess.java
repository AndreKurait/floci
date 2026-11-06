package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.response.Response;

/** Lets tests in other packages share the enabled profile and the signed derive call. */
public final class DerivedSigningKeyIntegrationTestAccess {

    public static final String SERVICE = DerivedSigningKeyIntegrationTest.SERVICE;

    private DerivedSigningKeyIntegrationTestAccess() {
    }

    /** The same overrides as {@link DerivedSigningKeyIntegrationTest.Enabled}, so the app is shared. */
    public static class Enabled extends DerivedSigningKeyIntegrationTest.Enabled {
    }

    public static Response derive(String accessKeyId, String sessionToken) throws Exception {
        return DerivedSigningKeyIntegrationTest.derive(
                DerivedSigningKeyIntegrationTest.request(accessKeyId, sessionToken, "signing"));
    }

    public static String today() {
        return DerivedSigningKeyIntegrationTest.today();
    }
}
