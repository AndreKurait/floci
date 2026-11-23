package io.floci.aws.services.elbv2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RdsMockProfile;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Rule;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RuleCondition;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.AlbFixtures.createListener;
import static io.floci.aws.testing.AlbFixtures.createLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.createTargetGroup;
import static io.floci.aws.testing.AlbFixtures.deleteLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.deleteTargetGroup;
import static io.floci.aws.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A weighted read-traffic split rule as a cloned blue/green upgrade writes it: a path-pattern
 * condition with {@code RegexValues} and a weighted forward to the primary and clone target groups.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class ElbV2PathRegexDataPlaneIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261122/us-east-1/elasticloadbalancing/aws4_request";

    @Inject
    Vertx vertx;

    private HttpServer primary;
    private HttpServer clone;
    private String loadBalancerArn;
    private String primaryTg;
    private String cloneTg;
    private String listenerArn;
    private int listenerPort;

    @BeforeEach
    void startTargetsAndListener() throws Exception {
        primary = backend("primary");
        clone = backend("clone");
        listenerPort = FreePorts.anyInterfacePort();
        loadBalancerArn = createLoadBalancer("regex-split-lb");
        primaryTg = createTargetGroup("regex-primary", "ip", primary.actualPort());
        cloneTg = createTargetGroup("regex-clone", "ip", clone.actualPort());
        registerTarget(primaryTg, "127.0.0.1", primary.actualPort());
        registerTarget(cloneTg, "127.0.0.1", clone.actualPort());
        listenerArn = createListener(loadBalancerArn, primaryTg, listenerPort);
        await().atMost(Duration.ofSeconds(3)).ignoreExceptions()
                .untilAsserted(() -> assertEquals("primary", get("/idx/_search").asString()));
    }

    @AfterEach
    void cleanUp() throws Exception {
        deleteLoadBalancer(loadBalancerArn);
        deleteTargetGroup(primaryTg);
        deleteTargetGroup(cloneTg);
        for (HttpServer backend : List.of(primary, clone)) {
            backend.close().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void regexRuleSendsMatchingPathsToTheWeightedTargetGroups() {
        String ruleArn = createSplitRule(0, 100);

        for (int i = 0; i < 20; i++) {
            assertEquals("clone", get("/idx/_search").asString());
            assertEquals("clone", get("/_count").asString());
            assertEquals("clone", get("/idx/_rank_eval").asString());
        }
        assertEquals("primary", get("/idx/_doc/1").asString());
        assertEquals("primary", get("/_bulk").asString());
        // The path, not the query string, is matched.
        assertEquals("primary", get("/idx/_doc/1?q=/_search").asString());

        modifySplitRule(ruleArn, 70, 30);
        int toClone = 0;
        int samples = 400;
        for (int i = 0; i < samples; i++) {
            if ("clone".equals(get("/idx/_search").asString())) {
                toClone++;
            }
        }
        assertTrue(toClone > samples * 0.15 && toClone < samples * 0.45,
                "expected about 30% on the clone, got " + toClone + " of " + samples);

        modifySplitRule(ruleArn, 100, 0);
        for (int i = 0; i < 20; i++) {
            assertEquals("primary", get("/idx/_search").asString());
        }
    }

    @Test
    void describeRulesReturnsRegexValuesToTheSdk() {
        createSplitRule(70, 30);
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            Rule rule = client.describeRules(r -> r.listenerArn(listenerArn)).rules().stream()
                    .filter(r -> "75".equals(r.priority())).findFirst().orElseThrow();
            RuleCondition condition = rule.conditions().get(0);
            assertEquals("path-pattern", condition.field());
            assertEquals(ElbV2PathRegexTest.READ_REGEXES, condition.pathPatternConfig().regexValues());
            assertTrue(condition.pathPatternConfig().values().isEmpty());
            assertEquals(List.of(70, 30), rule.actions().get(0).forwardConfig().targetGroups().stream()
                    .map(t -> t.weight()).toList());

            String sdkRule = client.createRule(r -> r.listenerArn(listenerArn).priority(80)
                    .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.regexValues("^/sdk/[0-9]+$")))
                    .actions(a -> a.type("forward").targetGroupArn(cloneTg)))
                    .rules().get(0).ruleArn();
            assertEquals(List.of("^/sdk/[0-9]+$"), client.describeRules(r -> r.ruleArns(sdkRule)).rules().get(0)
                    .conditions().get(0).pathPatternConfig().regexValues());
            assertEquals("clone", get("/sdk/42").asString());
            assertEquals("primary", get("/sdk/x").asString());
        }
    }

    @Test
    void valuesAndRegexValuesTogetherAreRejected() {
        given().header("Authorization", AUTH)
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", listenerArn)
                .formParam("Priority", "90")
                .formParam("Conditions.member.1.Field", "path-pattern")
                .formParam("Conditions.member.1.PathPatternConfig.Values.member.1", "/a/*")
                .formParam("Conditions.member.1.PathPatternConfig.RegexValues.member.1", "^/a/.*$")
                .formParam("Actions.member.1.Type", "forward")
                .formParam("Actions.member.1.TargetGroupArn", cloneTg)
                .post("/")
                .then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void unsupportedSyntaxCannotCreateOrModifySdkRules() {
        String ruleArn = createSplitRule(0, 100);
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            int initialCount = client.describeRules(r -> r.listenerArn(listenerArn)).rules().size();
            for (String regex : List.of("^/a(?=b)$", "^/(?>a)$", "^/\\p{L}+$")) {
                ElasticLoadBalancingV2Exception createError = assertThrows(ElasticLoadBalancingV2Exception.class,
                        () -> client.createRule(r -> r.listenerArn(listenerArn).priority(90)
                                .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.regexValues(regex)))
                                .actions(a -> a.type("forward").targetGroupArn(cloneTg))));
                assertEquals("ValidationError", createError.awsErrorDetails().errorCode());
                ElasticLoadBalancingV2Exception updateError = assertThrows(ElasticLoadBalancingV2Exception.class,
                        () -> client.modifyRule(r -> r.ruleArn(ruleArn)
                                .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.regexValues(regex)))));
                assertEquals("ValidationError", updateError.awsErrorDetails().errorCode());
            }
            assertEquals(initialCount, client.describeRules(r -> r.listenerArn(listenerArn)).rules().size());
            assertEquals(ElbV2PathRegexTest.READ_REGEXES, client.describeRules(r -> r.ruleArns(ruleArn))
                    .rules().get(0).conditions().get(0).pathPatternConfig().regexValues());
            assertEquals("clone", get("/idx/_search").asString());
        }
    }

    @Test
    void nestedRepetitionRoutesLongNonmatchingRequestsWithoutBacktracking() {
        try (ElasticLoadBalancingV2Client client = AwsTestClients.create(
                ElasticLoadBalancingV2Client.builder(), "000000000000", Region.US_EAST_1)) {
            client.createRule(r -> r.listenerArn(listenerArn).priority(80)
                    .conditions(c -> c.field("path-pattern").pathPatternConfig(p -> p.regexValues("^/(a+)+$")))
                    .actions(a -> a.type("forward").targetGroupArn(cloneTg)));
            assertEquals("clone", get("/aaaa").asString());
            assertTimeout(Duration.ofSeconds(3), () ->
                    assertEquals("primary", get("/" + "a".repeat(2000) + "!").asString()));
        }
    }

    private String createSplitRule(int primaryWeight, int cloneWeight) {
        RequestSpecification request = given().header("Authorization", AUTH)
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", listenerArn)
                .formParam("Priority", "75");
        conditionAndAction(request, primaryWeight, cloneWeight);
        return request.post("/").then().statusCode(200).extract()
                .path("CreateRuleResponse.CreateRuleResult.Rules.member.RuleArn");
    }

    private void modifySplitRule(String ruleArn, int primaryWeight, int cloneWeight) {
        RequestSpecification request = given().header("Authorization", AUTH)
                .formParam("Action", "ModifyRule")
                .formParam("RuleArn", ruleArn);
        conditionAndAction(request, primaryWeight, cloneWeight);
        Response response = request.post("/").then().statusCode(200).extract().response();
        assertEquals(ElbV2PathRegexTest.READ_REGEXES, response.xmlPath().getList(
                "ModifyRuleResponse.ModifyRuleResult.Rules.member.Conditions.member.PathPatternConfig.RegexValues.member"));
    }

    private void conditionAndAction(RequestSpecification request, int primaryWeight, int cloneWeight) {
        request.formParam("Conditions.member.1.Field", "path-pattern");
        for (int i = 0; i < ElbV2PathRegexTest.READ_REGEXES.size(); i++) {
            request.formParam("Conditions.member.1.PathPatternConfig.RegexValues.member." + (i + 1),
                    ElbV2PathRegexTest.READ_REGEXES.get(i));
        }
        request.formParam("Actions.member.1.Type", "forward")
                .formParam("Actions.member.1.ForwardConfig.TargetGroups.member.1.TargetGroupArn", primaryTg)
                .formParam("Actions.member.1.ForwardConfig.TargetGroups.member.1.Weight", primaryWeight)
                .formParam("Actions.member.1.ForwardConfig.TargetGroups.member.2.TargetGroupArn", cloneTg)
                .formParam("Actions.member.1.ForwardConfig.TargetGroups.member.2.Weight", cloneWeight);
    }

    private HttpServer backend(String body) throws Exception {
        return vertx.createHttpServer().requestHandler(request -> request.response().end(body))
                .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private Response get(String pathAndQuery) {
        return given().baseUri("http://127.0.0.1").port(listenerPort).urlEncodingEnabled(false).get(pathAndQuery);
    }
}
