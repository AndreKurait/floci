package io.github.hectorvent.floci.services.ssm;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

public class SsmDirectCommandProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.ssm.direct-command-execution-enabled", "true");
    }
}
