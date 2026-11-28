package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RequestResources;
import io.floci.aws.services.iam.model.CallerContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LogsDeliverySourceAuthorizationTest {
    private static final String ACTION = "logs:DeleteDeliverySource";
    private static final String ACCOUNT = "111122223333";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(MAPPER);

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-cn,cn-north-1", "aws-us-gov,us-gov-west-1"})
    void anIdleSourceCanBeDeletedWithOnlyItsSourcePermission(String partition, String region) throws Exception {
        String source = source(partition, region, "selected");
        String policy = document(statement("Allow", source));
        assertEquals(List.of(source), resources(partition, region, "selected"));
        assertEquals(Decision.ALLOW, decide(policy, resources(partition, region, "selected")));
        assertEquals(Decision.DENY, decide(policy, resources(partition, region, "other")));
        assertEquals(Decision.DENY, decide(document(statement("Allow",
                source.replace("delivery-source:", "delivery-destination:"))),
                resources(partition, region, "selected")));
    }

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-cn,cn-north-1", "aws-us-gov,us-gov-west-1"})
    void aSourceDenyStillAppliesAfterItsDeliveriesAreRemoved(String partition, String region) throws Exception {
        String policy = document(statement("Allow", "*"), statement("Deny", source(partition, region, "selected")));
        assertEquals(Decision.DENY, decide(policy, resources(partition, region, "selected")));
        assertEquals(Decision.ALLOW, decide(policy, resources(partition, region, "other")));
    }

    private static List<String> resources(String partition, String region, String name) throws Exception {
        return RequestResources.ofBody(ACTION, MAPPER.readTree("{\"name\":\"" + name + "\"}"),
                partition, region, ACCOUNT, null);
    }

    private Decision decide(String policy, List<String> resources) {
        assertEquals(1, resources.size());
        return evaluator.evaluate(CallerContext.of(List.of(policy)), null, ACTION, resources.getFirst(), Map.of());
    }

    private static String source(String partition, String region, String name) {
        return "arn:" + partition + ":logs:" + region + ":" + ACCOUNT + ":delivery-source:" + name;
    }

    private static String statement(String effect, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + ACTION + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String document(String... statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + String.join(",", statements) + "]}";
    }
}
