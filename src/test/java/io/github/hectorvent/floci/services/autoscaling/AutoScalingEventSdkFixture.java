package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** SDK-owned resources and complete HTTP/model captures, shared by mock and native EC2 profiles. */
final class AutoScalingEventSdkFixture implements AutoCloseable {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String ACCOUNT = "815670000071";
    static final String LAUNCH = "EC2 Instance Launch Successful";
    static final String TERMINATE = "EC2 Instance Terminate Successful";
    final String marker = "asg-events-" + UUID.randomUUID().toString().replace("-", "");
    final String name;
    final String account;
    final Region region;
    final AutoScalingClient scaling;
    final Ec2Client ec2;
    final EventBridgeClient events;
    final SqsClient sqs;
    final Set<String> instances = new LinkedHashSet<>();
    final List<JsonNode> messages = new ArrayList<>();
    Consumer<JsonNode> onEvent = ignored -> {
        // Native-profile tests inspect physical instance cleanup at event receipt.
    };
    private final String caseName;
    private final WireReceipt wire = new WireReceipt();
    private final Set<String> eventIds = new HashSet<>();
    private String imageId;
    private String groupArn;
    private String queueUrl;
    private String queueArn;
    private String ruleArn;
    private boolean queueAttempted;
    private boolean ruleAttempted;
    private boolean launchConfigurationAttempted;
    private boolean groupAttempted;
    private boolean forwardComplete;
    private Instant deadline;

    AutoScalingEventSdkFixture(String caseName, String account, Region region, String groupName) {
        this.caseName = caseName;
        this.account = account;
        this.region = region;
        this.name = groupName == null ? marker : groupName;
        scaling = AwsTestClients.create(AutoScalingClient.builder().overrideConfiguration(c ->
                c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, region);
        ec2 = AwsTestClients.create(Ec2Client.builder().overrideConfiguration(c ->
                c.apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(55))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, region);
        events = AwsTestClients.create(EventBridgeClient.builder().overrideConfiguration(c ->
                c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, region);
        sqs = AwsTestClients.create(SqsClient.builder().overrideConfiguration(c ->
                c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(25))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, region);
    }

    void create(String image) {
        imageId = image;
        absentQueue();
        queueAttempted = true;
        queueUrl = sqs.createQueue(b -> b.queueName(marker).tags(Map.of("fixture", marker))).queueUrl();
        queueArn = sqs.getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        assertEquals(arn("sqs", marker), queueArn);
        missing(assertThrows(AwsServiceException.class, () -> events.describeRule(b -> b.name(marker))),
                400, Set.of("ResourceNotFoundException"));
        ruleAttempted = true;
        ruleArn = events.putRule(b -> b.name(marker).description(marker).eventPattern(pattern())
                .tags(t -> t.key("fixture").value(marker))).ruleArn();
        assertEquals(arn("events", "rule/" + marker), ruleArn);
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"events.amazonaws.com"},
                "Action":"sqs:SendMessage","Resource":"%s","Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}
                """.formatted(queueArn, ruleArn);
        sqs.setQueueAttributes(b -> b.queueUrl(queueUrl).attributes(Map.of(QueueAttributeName.POLICY, policy)));
        assertEquals(0, events.putTargets(b -> b.rule(marker).targets(t -> t.id("queue").arn(queueArn)))
                .failedEntryCount());
        assertTrue(scaling.describeLaunchConfigurations(b -> b.launchConfigurationNames(marker))
                .launchConfigurations().isEmpty());
        launchConfigurationAttempted = true;
        scaling.createLaunchConfiguration(b -> b.launchConfigurationName(marker).imageId(imageId)
                .instanceType("t3.micro"));
        assertTrue(scaling.describeAutoScalingGroups(b -> b.autoScalingGroupNames(name)).autoScalingGroups().isEmpty());
        groupAttempted = true;
        scaling.createAutoScalingGroup(b -> b.autoScalingGroupName(name).launchConfigurationName(marker)
                .minSize(0).maxSize(2).desiredCapacity(0).availabilityZones(region.id() + "a")
                .tags(t -> t.key("fixture").value(marker).propagateAtLaunch(true)));
        groupArn = group().autoScalingGroupARN();
        assertTrue(groupArn.startsWith(arn("autoscaling", "autoScalingGroup:")));
    }

    String pattern() {
        return """
                {"source":["aws.autoscaling"],"detail-type":["EC2 Instance Launch Successful",
                "EC2 Instance Terminate Successful"],"detail":{"AutoScalingGroupName":["%s"]}}
                """.formatted(name);
    }

    void desired(int capacity) {
        scaling.setDesiredCapacity(b -> b.autoScalingGroupName(name).desiredCapacity(capacity)
                .honorCooldown(false));
    }

    AutoScalingGroup group() {
        List<AutoScalingGroup> groups =
                scaling.describeAutoScalingGroups(b -> b.autoScalingGroupNames(name)).autoScalingGroups();
        assertEquals(1, groups.size());
        AutoScalingGroup group = groups.getFirst();
        assertEquals(name, group.autoScalingGroupName());
        assertEquals(marker, group.launchConfigurationName());
        assertTrue(group.tags().stream().anyMatch(t -> "fixture".equals(t.key()) && marker.equals(t.value())));
        if (groupArn != null) {
            assertEquals(groupArn, group.autoScalingGroupARN());
        }
        group.instances().forEach(i -> instances.add(i.instanceId()));
        return group;
    }

    List<String> ready(int count) {
        until(() -> {
            AutoScalingGroup group = group();
            return group.instances().size() == count
                    && group.instances().stream().allMatch(i -> "InService".equals(i.lifecycleStateAsString()));
        });
        return group().instances().stream().map(i -> i.instanceId()).sorted().toList();
    }

    Instance instance(String id) {
        Instance instance = ec2.describeInstances(b -> b.instanceIds(id)).reservations().stream()
                .flatMap(r -> r.instances().stream()).filter(i -> id.equals(i.instanceId())).findFirst().orElseThrow();
        assertTrue(instance.tags().stream().anyMatch(t -> "fixture".equals(t.key()) && marker.equals(t.value())));
        return instance;
    }

    List<JsonNode> receive(String type, Set<String> expectedIds) {
        int offset = messages.size();
        until(() -> {
            drain();
            Set<String> observed = new HashSet<>();
            messages.subList(offset, messages.size()).stream()
                    .filter(e -> type.equals(e.path("detail-type").asText()))
                    .forEach(e -> observed.add(e.path("detail").path("EC2InstanceId").asText()));
            return observed.equals(expectedIds);
        });
        List<JsonNode> found = messages.subList(offset, messages.size()).stream()
                .filter(e -> type.equals(e.path("detail-type").asText())).toList();
        assertEquals(expectedIds.size(), found.size());
        return found;
    }

    void receiveBoth(String oldInstance, String replacement) {
        int offset = messages.size();
        until(() -> {
            drain();
            List<JsonNode> found = messages.subList(offset, messages.size());
            return found.stream().anyMatch(e -> TERMINATE.equals(e.path("detail-type").asText())
                    && oldInstance.equals(e.path("detail").path("EC2InstanceId").asText()))
                    && found.stream().anyMatch(e -> LAUNCH.equals(e.path("detail-type").asText())
                    && replacement.equals(e.path("detail").path("EC2InstanceId").asText()));
        });
        assertEquals(2, messages.size() - offset);
        assertEquals("terminated", instance(oldInstance).state().nameAsString());
        assertEquals("running", instance(replacement).state().nameAsString());
    }

    void quiet() {
        int before = messages.size();
        Instant end = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(end)) {
            drain();
            pause();
        }
        assertEquals(before, messages.size(), "No success event for unchanged or rejected lifecycle state");
    }

    void drain() {
        List<Message> batch = sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(10)
                .waitTimeSeconds(0)).messages();
        for (Message message : batch) {
            try {
                JsonNode event = MAPPER.readTree(message.body());
                assertEquals("0", event.path("version").asText());
                assertEquals("aws.autoscaling", event.path("source").asText());
                assertEquals(account, event.path("account").asText());
                assertEquals(region.id(), event.path("region").asText());
                assertNotNull(Instant.parse(event.path("time").asText()));
                String type = event.path("detail-type").asText();
                assertTrue(Set.of(LAUNCH, TERMINATE).contains(type));
                JsonNode detail = event.path("detail");
                assertEquals(name, detail.path("AutoScalingGroupName").asText());
                String id = detail.path("EC2InstanceId").asText();
                assertTrue(id.startsWith("i-"));
                instances.add(id);
                assertEquals(groupArn, event.path("resources").get(0).asText());
                assertEquals(arn("ec2", "instance/" + id), event.path("resources").get(1).asText());
                assertEquals(type.equals(LAUNCH) ? "EC2" : "AutoScalingGroup", detail.path("Origin").asText());
                assertEquals(type.equals(LAUNCH) ? "AutoScalingGroup" : "EC2", detail.path("Destination").asText());
                assertFalse(detail.path("ActivityId").asText().isBlank());
                assertTrue(scaling.describeScalingActivities(b -> b.autoScalingGroupName(name)
                        .includeDeletedGroups(true)).activities().stream()
                        .anyMatch(activity -> detail.path("ActivityId").asText().equals(activity.activityId())),
                        "Event ActivityId names an actual recorded scaling activity");
                assertFalse(detail.path("RequestId").asText().isBlank());
                assertFalse(Instant.parse(detail.path("EndTime").asText())
                        .isBefore(Instant.parse(detail.path("StartTime").asText())));
                onEvent.accept(event);
                if (eventIds.add(event.path("id").asText())) {
                    messages.add(event);
                }
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        }
    }

    void deleteGroup() {
        group();
        scaling.deleteAutoScalingGroup(b -> b.autoScalingGroupName(name).forceDelete(true));
        until(() -> scaling.describeAutoScalingGroups(b -> b.autoScalingGroupNames(name)).autoScalingGroups().isEmpty());
    }

    void success() {
        forwardComplete = true;
    }

    private String arn(String service, String resource) {
        String partition = region.id().startsWith("cn-") ? "aws-cn" : "aws";
        return "arn:" + partition + ":" + service + ":" + region.id() + ":" + account + ":" + resource;
    }

    void until(BooleanSupplier condition) {
        deadline = Instant.now().plusSeconds(90);
        while (!condition.getAsBoolean()) {
            assertTrue(Instant.now().isBefore(deadline), "Timed out waiting for owned Auto Scaling lifecycle");
            pause();
        }
    }

    private void absentQueue() {
        missing(assertThrows(AwsServiceException.class, () -> sqs.getQueueUrl(b -> b.queueName(marker))),
                400, Set.of("QueueDoesNotExist", "AWS.SimpleQueueService.NonExistentQueue"));
    }

    @Override
    public void close() {
        IllegalStateException failures = new IllegalStateException("owned Auto Scaling event cleanup failed");
        cleanup(() -> {
            if (groupAttempted && !scaling.describeAutoScalingGroups(b -> b.autoScalingGroupNames(name))
                    .autoScalingGroups().isEmpty()) {
                deleteGroup();
            }
            if (groupAttempted) {
                assertTrue(scaling.describeAutoScalingGroups(b -> b.autoScalingGroupNames(name))
                        .autoScalingGroups().isEmpty());
            }
        }, failures);
        cleanup(() -> {
            List<Instance> remaining = ec2.describeInstances(b -> b.filters(f ->
                            f.name("tag:fixture").values(marker))).reservations().stream()
                    .flatMap(r -> r.instances().stream()).toList();
            for (Instance instance : remaining) {
                assertTrue(instance.tags().stream().anyMatch(t -> "fixture".equals(t.key()) && marker.equals(t.value())));
                instances.add(instance.instanceId());
                if (!Set.of("terminated", "shutting-down").contains(instance.state().nameAsString())) {
                    ec2.terminateInstances(b -> b.instanceIds(instance.instanceId()));
                }
            }
            until(() -> ec2.describeInstances(b -> b.filters(f -> f.name("tag:fixture").values(marker)))
                    .reservations().stream().flatMap(r -> r.instances().stream())
                    .allMatch(i -> "terminated".equals(i.state().nameAsString())));
        }, failures);
        cleanup(() -> {
            if (launchConfigurationAttempted) {
                scaling.describeLaunchConfigurations(b -> b.launchConfigurationNames(marker))
                        .launchConfigurations().forEach(configuration -> {
                            assertEquals(marker, configuration.launchConfigurationName());
                            assertEquals(imageId, configuration.imageId());
                            scaling.deleteLaunchConfiguration(b -> b.launchConfigurationName(marker));
                        });
                assertTrue(scaling.describeLaunchConfigurations(b -> b.launchConfigurationNames(marker))
                        .launchConfigurations().isEmpty());
            }
        }, failures);
        cleanup(() -> {
            if (ruleAttempted) {
                try {
                    assertEquals(marker, events.describeRule(b -> b.name(marker)).description());
                    assertEquals(arn("events", "rule/" + marker), events.describeRule(b -> b.name(marker)).arn());
                    assertEquals(0, events.removeTargets(b -> b.rule(marker).ids("queue")).failedEntryCount());
                    events.deleteRule(b -> b.name(marker));
                } catch (AwsServiceException absent) {
                    missing(absent, 400, Set.of("ResourceNotFoundException"));
                }
                missing(assertThrows(AwsServiceException.class, () -> events.describeRule(b -> b.name(marker))),
                        400, Set.of("ResourceNotFoundException"));
            }
        }, failures);
        cleanup(() -> {
            if (queueAttempted) {
                try {
                    String currentUrl = sqs.getQueueUrl(b -> b.queueName(marker)).queueUrl();
                    assertEquals(marker, sqs.listQueueTags(b -> b.queueUrl(currentUrl)).tags().get("fixture"));
                    assertEquals(arn("sqs", marker), sqs.getQueueAttributes(b -> b.queueUrl(currentUrl)
                            .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN));
                    sqs.deleteQueue(b -> b.queueUrl(currentUrl));
                } catch (AwsServiceException absent) {
                    missing(absent, 400, Set.of("QueueDoesNotExist", "AWS.SimpleQueueService.NonExistentQueue"));
                }
                absentQueue();
            }
        }, failures);
        Map<String, Object> ownership = new LinkedHashMap<>();
        ownership.put("account", account);
        ownership.put("region", region.id());
        ownership.put("groupName", name);
        ownership.put("groupArn", groupArn);
        ownership.put("imageId", imageId);
        ownership.put("instanceIds", List.copyOf(instances));
        ownership.put("forwardComplete", forwardComplete);
        ownership.put("events", messages);
        cleanup(() -> wire.save(caseName, marker, failures.getSuppressed().length == 0, ownership), failures);
        scaling.close();
        ec2.close();
        events.close();
        sqs.close();
        if (failures.getSuppressed().length > 0) {
            throw failures;
        }
    }

    private static void missing(AwsServiceException failure, int status, Set<String> codes) {
        assertEquals(status, failure.statusCode());
        assertTrue(codes.contains(failure.awsErrorDetails().errorCode()), failure.awsErrorDetails().errorCode());
    }

    private static void cleanup(Runnable action, IllegalStateException failures) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            failures.addSuppressed(failure);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
    private static final class WireReceipt implements ExecutionInterceptor {
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("autoscaling-event-wire-id");
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            assertTrue(calls.size() < 10000);
            attributes.putAttribute(ID, calls.size());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", calls.size());
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("requestModel", model(context.request()));
            Map<String, List<String>> headers = new LinkedHashMap<>(context.httpRequest().headers());
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("authorization")
                    || name.equalsIgnoreCase("x-amz-security-token"));
            row.put("requestHeaders", headers);
            row.put("requestBodyAvailable", context.requestBody().isPresent());
            try (InputStream stream = context.requestBody().isPresent()
                    ? context.requestBody().orElseThrow().contentStreamProvider().newStream()
                    : InputStream.nullInputStream()) {
                row.put("requestBase64", encode(stream));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            calls.add(row);
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            Map<String, Object> row = calls.get(attributes.getAttribute(ID));
            row.put("status", context.httpResponse().statusCode());
            row.put("responseHeaders", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElseGet(InputStream::nullInputStream)) {
                String encoded = encode(stream);
                row.put("responseBase64", encoded);
                return Optional.of(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
            calls.get(attributes.getAttribute(ID)).put("responseModel", model(context.response()));
        }

        @Override
        public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
            Integer id = attributes.getAttribute(ID);
            if (id == null) {
                return;
            }
            Map<String, Object> row = calls.get(id);
            row.put("exceptionType", context.exception().getClass().getName());
            if (context.exception() instanceof AwsServiceException failure) {
                row.put("errorCode", failure.awsErrorDetails().errorCode());
                row.put("errorMessage", failure.awsErrorDetails().errorMessage());
                row.put("errorStatus", failure.statusCode());
            }
        }

        private static Object model(Object value) {
            if (value instanceof SdkPojo pojo) {
                Map<String, Object> fields = new LinkedHashMap<>();
                pojo.sdkFields().forEach(field -> {
                    Object member = field.getValueOrDefault(pojo);
                    if (member != null) {
                        fields.put(field.memberName(), model(member));
                    }
                });
                return fields;
            }
            if (value instanceof List<?> list) {
                return list.stream().map(WireReceipt::model).toList();
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> fields = new LinkedHashMap<>();
                map.forEach((key, member) -> fields.put(key.toString(), model(member)));
                return fields;
            }
            if (value instanceof SdkBytes bytes) {
                return Base64.getEncoder().encodeToString(bytes.asByteArray());
            }
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                return value;
            }
            return value.toString();
        }

        private static String encode(InputStream stream) throws IOException {
            byte[] bytes = stream.readNBytes(1_048_577);
            assertTrue(bytes.length <= 1_048_576);
            return Base64.getEncoder().encodeToString(bytes);
        }

        private void save(String caseName, String marker, boolean cleanupComplete, Map<String, Object> ownership) {
            try {
                Path directory = Path.of(System.getProperty("audit.autoscaling.events.directory",
                        "target/autoscaling-event-wire"));
                Files.createDirectories(directory);
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(),
                        Map.of("case", caseName, "marker", marker, "cleanupComplete", cleanupComplete, "calls", calls,
                                "ownership", ownership));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "every attempted transmission has a complete captured response");
        }
    }
}
