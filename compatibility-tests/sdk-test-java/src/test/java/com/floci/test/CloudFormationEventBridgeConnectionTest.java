package com.floci.test;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.DescribeConnectionResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation EventBridge Connection and API destination lifecycle")
class CloudFormationEventBridgeConnectionTest {

    @Test
    void createsDeliversUpdatesReplacesRollsBackAndDeletes() throws Exception {
        String stack = TestFixtures.uniqueName("compat-cfn-connection");
        String host = System.getenv("FLOCI_API_DESTINATION_SINK_HOST");
        if (host == null || host.isBlank()) {
            host = switch (TestFixtures.endpoint().getHost()) {
                case "localhost", "127.0.0.1", "::1", "[::1]" -> "127.0.0.1";
                default -> InetAddress.getLocalHost().getHostAddress();
            };
        }
        LinkedBlockingQueue<String> deliveries = new LinkedBlockingQueue<>();
        HttpServer sink = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        sink.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            deliveries.add(exchange.getRequestURI().getPath() + ":" + exchange.getRequestHeaders().getFirst("X-Key"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        sink.start();
        String endpoint = "http://" + host + ":" + sink.getAddress().getPort();
        try (CloudFormationClient cfn = TestFixtures.cloudFormationClient();
                EventBridgeClient events = TestFixtures.eventBridgeClient()) {
            try {
                String initial = template(stack, endpoint, "initial", false);
                cfn.createStack(r -> r.stackName(stack).templateBody(initial));
                await(cfn, stack, "CREATE_COMPLETE");
                Map<String, String> original = outputs(cfn, stack);
                assertAttributes(events, original);
                publish(events, stack, deliveries, "initial");
                assertThatThrownBy(() -> cfn.updateStack(r -> r.stackName(stack).templateBody(initial)))
                        .isInstanceOf(CloudFormationException.class).hasMessageContaining("No updates");

                cfn.updateStack(r -> r.stackName(stack).templateBody(template(stack, endpoint, "updated", false)));
                await(cfn, stack, "UPDATE_COMPLETE");
                assertThat(outputs(cfn, stack)).isEqualTo(original);
                assertThat(events.describeConnection(r -> r.name(original.get("ConnectionName"))).description())
                        .isEqualTo("updated");
                assertThat(events.describeApiDestination(r -> r.name(original.get("DestinationName")))
                        .invocationRateLimitPerSecond()).isEqualTo(84);
                publish(events, stack, deliveries, "updated");

                String broken = template(stack, endpoint, "broken", false).replace("\"Rule\":{", """
                        "Broken":{"Type":"AWS::Events::Connection","DependsOn":"Rule",
                        "Properties":{"AuthorizationType":"INVALID","AuthParameters":{}}},"Rule":{
                        """);
                cfn.updateStack(r -> r.stackName(stack).templateBody(broken));
                await(cfn, stack, "UPDATE_ROLLBACK_COMPLETE");
                assertThat(outputs(cfn, stack)).isEqualTo(original);
                publish(events, stack, deliveries, "updated");

                cfn.updateStack(r -> r.stackName(stack).templateBody(template(stack, endpoint, "renamed", true)));
                await(cfn, stack, "UPDATE_COMPLETE");
                Map<String, String> renamed = outputs(cfn, stack);
                assertAttributes(events, renamed);
                assertThat(renamed.get("ConnectionName")).isEqualTo(stack + "-auth");
                assertThat(renamed.get("DestinationName")).isEqualTo(stack + "-destination");
                assertMissing(events, original);
                publish(events, stack, deliveries, "renamed");

                cfn.deleteStack(r -> r.stackName(stack));
                awaitDeleted(cfn, stack);
                assertMissing(events, renamed);
                assertThatThrownBy(() -> events.describeRule(r -> r.name(stack)))
                        .isInstanceOf(ResourceNotFoundException.class);
            } finally {
                try {
                    cfn.deleteStack(r -> r.stackName(stack));
                    awaitDeleted(cfn, stack);
                } catch (CloudFormationException expected) {
                    // A successfully deleted stack is already absent during final cleanup.
                    if (!expected.getMessage().contains("does not exist")) {
                        throw expected;
                    }
                }
            }
        } finally {
            sink.stop(0);
        }
    }

    private static void assertAttributes(EventBridgeClient events, Map<String, String> outputs) {
        DescribeConnectionResponse connection = events.describeConnection(r -> r.name(outputs.get("ConnectionName")));
        assertThat(outputs.get("ConnectionArn")).isEqualTo(connection.connectionArn());
        assertThat(outputs.get("SecretArn")).isEqualTo(connection.secretArn());
        assertThat(outputs.get("ConnectionPolicyArn"))
                .isEqualTo(connection.connectionArn().substring(0, connection.connectionArn().lastIndexOf('/')));
        String destinationArn = events.describeApiDestination(r -> r.name(outputs.get("DestinationName")))
                .apiDestinationArn();
        assertThat(outputs.get("DestinationArn")).isEqualTo(destinationArn);
        assertThat(outputs.get("DestinationPolicyArn"))
                .isEqualTo(destinationArn.substring(0, destinationArn.lastIndexOf('/')));
    }

    private static void assertMissing(EventBridgeClient events, Map<String, String> outputs) {
        assertThatThrownBy(() -> events.describeConnection(r -> r.name(outputs.get("ConnectionName"))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> events.describeApiDestination(r -> r.name(outputs.get("DestinationName"))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static void publish(EventBridgeClient events, String stack, LinkedBlockingQueue<String> deliveries,
                                String phase) throws InterruptedException {
        assertThat(events.putEvents(r -> r.entries(PutEventsRequestEntry.builder().source(stack)
                .detailType("test").detail("{}").build())).failedEntryCount()).isZero();
        assertThat(deliveries.poll(10, TimeUnit.SECONDS)).isEqualTo("/" + phase + ":synthetic-" + phase);
    }

    private static Map<String, String> outputs(CloudFormationClient cfn, String stack) {
        return cfn.describeStacks(r -> r.stackName(stack)).stacks().get(0).outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static void await(CloudFormationClient cfn, String stack, String expected) throws InterruptedException {
        assertThat(TestFixtures.awaitStackTerminal(cfn, stack, Duration.ofSeconds(60))).isEqualTo(expected);
    }

    private static void awaitDeleted(CloudFormationClient cfn, String stack) throws InterruptedException {
        TestFixtures.await(() -> {
            try {
                return "DELETE_COMPLETE".equals(cfn.describeStacks(r -> r.stackName(stack))
                        .stacks().get(0).stackStatusAsString());
            } catch (CloudFormationException expected) {
                if (expected.getMessage().contains("does not exist")) {
                    return true;
                }
                throw expected;
            }
        }, stack, "DELETE_COMPLETE", Duration.ofSeconds(60).toNanos(), 100);
    }

    private static String template(String stack, String endpoint, String phase, boolean named) {
        String connectionName = named ? "\"Name\":\"" + stack + "-auth\"," : "";
        String destinationName = named ? "\"Name\":\"" + stack + "-destination\"," : "";
        return """
                {"Resources":{
                  "Connection":{"Type":"AWS::Events::Connection","Properties":{
                    %1$s"Description":"%3$s","AuthorizationType":"API_KEY","AuthParameters":{
                      "ApiKeyAuthParameters":{"ApiKeyName":"X-Key","ApiKeyValue":"synthetic-%3$s"}}}},
                  "Destination":{"Type":"AWS::Events::ApiDestination","Properties":{
                    %2$s"ConnectionArn":{"Fn::GetAtt":["Connection","Arn"]},"HttpMethod":"POST",
                    "InvocationEndpoint":"%4$s/%3$s","InvocationRateLimitPerSecond":%6$d}},
                  "Rule":{"Type":"AWS::Events::Rule","Properties":{"Name":"%5$s",
                    "EventPattern":{"source":["%5$s"]},"Targets":[{"Id":"http",
                      "Arn":{"Fn::GetAtt":["Destination","Arn"]},
                      "RoleArn":{"Fn::Sub":"arn:${AWS::Partition}:iam::${AWS::AccountId}:role/synthetic"},
                      "RetryPolicy":{"MaximumRetryAttempts":0,"MaximumEventAgeInSeconds":60}}]}}},
                 "Outputs":{
                   "ConnectionName":{"Value":{"Ref":"Connection"}},
                   "ConnectionArn":{"Value":{"Fn::GetAtt":["Connection","Arn"]}},
                   "ConnectionPolicyArn":{"Value":{"Fn::GetAtt":["Connection","ArnForPolicy"]}},
                   "SecretArn":{"Value":{"Fn::GetAtt":["Connection","SecretArn"]}},
                   "DestinationName":{"Value":{"Ref":"Destination"}},
                   "DestinationArn":{"Value":{"Fn::GetAtt":["Destination","Arn"]}},
                   "DestinationPolicyArn":{"Value":{"Fn::GetAtt":["Destination","ArnForPolicy"]}}}}
                """.formatted(connectionName, destinationName, phase, endpoint, stack, "initial".equals(phase) ? 42 : 84);
    }
}
