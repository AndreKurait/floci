package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** The real ELBv2 data plane, with load balancers named in the AWS DNS form. */
public class AwsElbV2DnsNamesProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.elbv2.mock", "false",
                "floci.services.elbv2.dns-name-strategy", "aws");
    }
}
