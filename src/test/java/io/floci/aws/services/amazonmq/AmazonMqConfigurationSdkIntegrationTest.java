package io.floci.aws.services.amazonmq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.mq.MqClient;
import software.amazon.awssdk.services.mq.model.AuthenticationStrategy;
import software.amazon.awssdk.services.mq.model.Configuration;
import software.amazon.awssdk.services.mq.model.ConfigurationRevision;
import software.amazon.awssdk.services.mq.model.CreateConfigurationRequest;
import software.amazon.awssdk.services.mq.model.CreateConfigurationResponse;
import software.amazon.awssdk.services.mq.model.DeleteConfigurationResponse;
import software.amazon.awssdk.services.mq.model.DescribeConfigurationResponse;
import software.amazon.awssdk.services.mq.model.DescribeConfigurationRevisionResponse;
import software.amazon.awssdk.services.mq.model.EngineType;
import software.amazon.awssdk.services.mq.model.ListConfigurationRevisionsResponse;
import software.amazon.awssdk.services.mq.model.ListConfigurationsResponse;
import software.amazon.awssdk.services.mq.model.MqException;
import software.amazon.awssdk.services.mq.model.UpdateConfigurationResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Timeout(90)
class AmazonMqConfigurationSdkIntegrationTest {

    private static final Logger LOG = Logger.getLogger(AmazonMqConfigurationSdkIntegrationTest.class);
    private static final String ACCOUNT = "591100001201";
    private static final String VERSION = "3.13";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Reservation> reservations = new ArrayList<>();
    private MqClient client;
    private String marker;

    @BeforeEach
    void openClient() {
        marker = "mq-sdk-" + UUID.randomUUID();
        client = AwsTestClients.create(MqClient.builder().overrideConfiguration(config -> config
                .apiCallTimeout(Duration.ofSeconds(10))
                .apiCallAttemptTimeout(Duration.ofSeconds(10))
                .retryPolicy(RetryPolicy.none())
                .addExecutionInterceptor(new WireReceipt())), ACCOUNT, Region.US_EAST_1);
    }

    @AfterEach
    void removeOnlyProvenOwnedConfigurations() {
        List<Throwable> failures = new ArrayList<>();
        try {
            for (Reservation reservation : reservations) {
                try {
                    cleanup(reservation);
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }
        } finally {
            client.close();
        }
        assertAll("Every accepted configuration retains its cleanup owner",
                failures.stream().map(failure -> () -> {
                    throw failure;
                }));
    }

    @Test
    void independentConfigurationsRetainEveryRevisionAndListMetadata() {
        Reservation a = create("lineage-a");
        Reservation b = create("lineage-b");
        DescribeConfigurationRevisionResponse a1 = revision(a, 1);
        DescribeConfigurationRevisionResponse b1 = revision(b, 1);
        assertNull(a1.data());
        assertNull(b1.data());

        append(a, "consumer_timeout = 3600000\n", "a-second", 2);
        DescribeConfigurationRevisionResponse a2 = revision(a, 2);
        append(b, "heartbeat = 120\n", "b-second", 2);
        DescribeConfigurationRevisionResponse b2 = revision(b, 2);
        append(a, "consumer_timeout = 1800000\n", "a-third", 3);
        DescribeConfigurationRevisionResponse a3 = revision(a, 3);
        assertDocument(a2, "consumer_timeout = 3600000\n", "a-second");
        assertDocument(a3, "consumer_timeout = 1800000\n", "a-third");
        assertDocument(b2, "heartbeat = 120\n", "b-second");

        assertSdkFieldsEqual(a1, revision(a, 1));
        assertSdkFieldsEqual(a2, revision(a, 2));
        assertSdkFieldsEqual(a3, revision(a, 3));
        assertSdkFieldsEqual(b1, revision(b, 1));
        assertSdkFieldsEqual(b2, revision(b, 2));
        assertEquals(List.of(1, 2, 3), revisions(a).stream().map(ConfigurationRevision::revision).toList());
        assertEquals(List.of(1, 2), revisions(b).stream().map(ConfigurationRevision::revision).toList());
        List<Configuration> listed = configurations();
        Configuration listedA = listed.stream().filter(row -> a.response.id().equals(row.id()))
                .findFirst().orElseThrow();
        Configuration listedB = listed.stream().filter(row -> b.response.id().equals(row.id()))
                .findFirst().orElseThrow();
        assertEquals(a.response.arn(), listedA.arn());
        assertEquals(a.tags, listedA.tags());
        assertEquals(3, listedA.latestRevision().revision());
        assertEquals(b.response.arn(), listedB.arn());
        assertEquals(b.tags, listedB.tags());
        assertEquals(2, listedB.latestRevision().revision());
    }

    @Test
    void describeUsesTypedEnumsAndPreservesCreationIdentity() {
        Reservation owned = create("metadata");
        DescribeConfigurationResponse first = describe(owned);
        assertEquals(EngineType.RABBITMQ, first.engineType());
        assertEquals(AuthenticationStrategy.SIMPLE, first.authenticationStrategy());
        assertEquals(VERSION, first.engineVersion());
        assertEquals("", first.description());
        assertEquals(1, first.latestRevision().revision());
        assertNotNull(first.latestRevision().created());
        append(owned, "heartbeat = 120\n", "stored-description", 2);
        DescribeConfigurationResponse second = describe(owned);
        assertEquals(first.created(), second.created());
        assertEquals(first.arn(), second.arn());
        assertEquals(first.id(), second.id());
        assertEquals(first.tags(), second.tags());
        assertEquals("stored-description", second.description());
        assertEquals(2, second.latestRevision().revision());
    }

    @Test
    void sameNameCanLabelTwoDistinctConfigurationIdentities() {
        String name = marker + "-shared";
        Reservation first = createNamed(name, "first");
        Reservation second = createNamed(name, "second");
        assertNotEquals(first.response.id(), second.response.id());
        assertNotEquals(first.response.arn(), second.response.arn());
        assertEquals(name, describe(first).name());
        assertEquals(name, describe(second).name());
    }

    @Test
    void invalidNameIsRefusedWithoutPublishingAnOwnedConfiguration() {
        Reservation invalid = reserve(marker + " invalid", "invalid");
        MqException failure = assertThrows(MqException.class, () -> submit(invalid));
        assertError(failure, 400, "BadRequestException");
        assertTrue(configurations().stream().noneMatch(row -> invalid.name.equals(row.name())
                || marker.equals(row.tags().get("audit-owner"))));
    }

    @Test
    void invalidBase64DoesNotAdvanceOrReplaceTheStoredRevision() {
        Reservation owned = create("invalid-data");
        append(owned, "heartbeat = 120\n", "accepted", 2);
        DescribeConfigurationResponse before = describe(owned);
        DescribeConfigurationRevisionResponse stored = revision(owned, 2);
        MqException failure = assertThrows(MqException.class, () -> client.updateConfiguration(request -> request
                .configurationId(owned.response.id()).data("not!base64").description("must-not-publish")));
        assertError(failure, 400, "BadRequestException");
        assertSdkFieldsEqual(before, describe(owned));
        assertSdkFieldsEqual(stored, revision(owned, 2));
        assertEquals(List.of(1, 2), revisions(owned).stream().map(ConfigurationRevision::revision).toList());
    }

    @Test
    void nonexistentConfigurationReturnsModeledNotFound() {
        String missing = "c-" + UUID.randomUUID();
        MqException failure = assertThrows(MqException.class,
                () -> client.describeConfiguration(request -> request.configurationId(missing)));
        assertError(failure, 404, "NotFoundException");
        assertTrue(configurations().stream().noneMatch(row -> missing.equals(row.id())));
    }

    @Test
    void nonexistentRevisionDoesNotCreateOrAdvanceConfigurationState() {
        Reservation owned = create("missing-revision");
        DescribeConfigurationResponse before = describe(owned);
        MqException failure = assertThrows(MqException.class, () -> client.describeConfigurationRevision(request -> request
                .configurationId(owned.response.id()).configurationRevision("2")));
        assertError(failure, 404, "NotFoundException");
        assertSdkFieldsEqual(before, describe(owned));
        assertEquals(List.of(1), revisions(owned).stream().map(ConfigurationRevision::revision).toList());
    }

    @Test
    void deletedIdentityRejectsUpdateAndLeavesItsSiblingUnchanged() {
        Reservation deleted = create("retired");
        Reservation sibling = create("survivor");
        append(deleted, "consumer_timeout = 1800000\n", "retired-second", 2);
        append(sibling, "heartbeat = 120\n", "survivor-second", 2);
        DescribeConfigurationResponse siblingBefore = describe(sibling);
        DescribeConfigurationRevisionResponse siblingRevision = revision(sibling, 2);
        deleteOwned(deleted);
        MqException failure = assertThrows(MqException.class, () -> client.updateConfiguration(request -> request
                .configurationId(deleted.response.id()).data(encode("heartbeat = 60\n"))
                .description("cannot-resurrect")));
        assertError(failure, 409, "ConflictException");
        assertAbsent(deleted.response.id());
        assertSdkFieldsEqual(siblingBefore, describe(sibling));
        assertSdkFieldsEqual(siblingRevision, revision(sibling, 2));
        assertTrue(configurations().stream().noneMatch(row -> deleted.response.id().equals(row.id())));
    }

    private static void assertSdkFieldsEqual(SdkPojo expected, SdkPojo actual) {
        assertTrue(expected.equalsBySdkFields(actual), () -> "Expected SDK fields " + expected + " but received " + actual);
    }

    private Reservation create(String slot) {
        return createNamed(marker + "-" + slot, slot);
    }

    private Reservation createNamed(String name, String slot) {
        Reservation reservation = reserve(name, slot);
        CreateConfigurationResponse response = submit(reservation);
        assertNotNull(response.id(), "Create receipt retained before asserting MQ identity");
        assertFalse(response.id().isBlank());
        assertNotNull(response.arn());
        assertTrue(response.arn().endsWith(":configuration:" + response.id()));
        assertEquals(name, response.name());
        assertNotNull(response.created());
        assertEquals(AuthenticationStrategy.SIMPLE, response.authenticationStrategy());
        assertEquals(1, response.latestRevision().revision());
        describe(reservation);
        return reservation;
    }

    private Reservation reserve(String name, String slot) {
        Reservation reservation = new Reservation(name, Map.of("audit-owner", marker, "audit-slot", slot));
        reservations.add(reservation);
        LOG.infov("MQ_CREATE_RESERVATION name={0} tags={1}", name, reservation.tags);
        return reservation;
    }

    private CreateConfigurationResponse submit(Reservation reservation) {
        try {
            reservation.response = client.createConfiguration(request -> request.name(reservation.name)
                    .engineType(EngineType.RABBITMQ).engineVersion(VERSION).tags(reservation.tags));
            LOG.infov("MQ_CREATE_RECEIPT name={0} response={1}", reservation.name, reservation.response);
            return reservation.response;
        } catch (MqException failure) {
            reservation.uncertain = failure.statusCode() >= 500;
            LOG.infov("MQ_CREATE_REFUSAL name={0} status={1} code={2} body={3}",
                    reservation.name, failure.statusCode(), failure.awsErrorDetails().errorCode(),
                    failure.awsErrorDetails().rawResponse());
            throw failure;
        } catch (RuntimeException | AssertionError failure) {
            reservation.uncertain = true;
            throw failure;
        }
    }

    private DescribeConfigurationResponse describe(Reservation reservation) {
        DescribeConfigurationResponse response = client.describeConfiguration(request -> request
                .configurationId(reservation.response.id()));
        assertOwnership(reservation, response);
        return response;
    }

    private void assertOwnership(Reservation reservation, DescribeConfigurationResponse response) {
        assertEquals(reservation.response.id(), response.id());
        assertEquals(reservation.response.arn(), response.arn());
        assertEquals(reservation.response.created(), response.created());
        assertEquals(reservation.name, response.name());
        assertEquals(reservation.tags, response.tags());
    }

    private DescribeConfigurationRevisionResponse revision(Reservation reservation, int number) {
        DescribeConfigurationRevisionResponse response = client.describeConfigurationRevision(request -> request
                .configurationId(reservation.response.id()).configurationRevision(Integer.toString(number)));
        assertEquals(reservation.response.id(), response.configurationId());
        assertNotNull(response.created());
        return response;
    }

    private void append(Reservation reservation, String document, String description, int expectedRevision) {
        describe(reservation);
        UpdateConfigurationResponse response = client.updateConfiguration(request -> request
                .configurationId(reservation.response.id()).data(encode(document)).description(description));
        assertEquals(reservation.response.id(), response.id());
        assertEquals(reservation.response.arn(), response.arn());
        assertEquals(reservation.response.created(), response.created());
        assertEquals(reservation.name, response.name());
        assertEquals(expectedRevision, response.latestRevision().revision());
        assertEquals(description, response.latestRevision().description());
        assertNotNull(response.latestRevision().created());
        assertTrue(response.warnings().isEmpty());
    }

    private List<ConfigurationRevision> revisions(Reservation reservation) {
        List<ConfigurationRevision> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String token = null;
        do {
            String current = token;
            ListConfigurationRevisionsResponse page = client.listConfigurationRevisions(request -> request
                    .configurationId(reservation.response.id()).maxResults(100).nextToken(current));
            if (page.configurationId() != null) {
                assertEquals(reservation.response.id(), page.configurationId());
            }
            result.addAll(page.revisions());
            token = page.nextToken();
            if (token != null && !token.isEmpty()) {
                assertTrue(seen.add(token), "Revision page token repeated");
                assertTrue(seen.size() < 20, "Bounded fixture cannot claim a complete longer collection");
            }
        } while (token != null && !token.isEmpty());
        return result;
    }

    private List<Configuration> configurations() {
        List<Configuration> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String token = null;
        do {
            String current = token;
            ListConfigurationsResponse page = client.listConfigurations(request -> request
                    .maxResults(100).nextToken(current));
            result.addAll(page.configurations());
            token = page.nextToken();
            if (token != null && !token.isEmpty()) {
                assertTrue(seen.add(token), "Configuration page token repeated");
                assertTrue(seen.size() < 20, "Bounded fixture cannot claim a complete longer collection");
            }
        } while (token != null && !token.isEmpty());
        return result;
    }

    private void cleanup(Reservation reservation) {
        if (reservation.deleted) {
            return;
        }
        if (reservation.response == null && reservation.rawCreate != null
                && reservation.rawCreate.path("arn").asText().contains(":kafka:")) {
            reservation.response = CreateConfigurationResponse.builder()
                    .arn(reservation.rawCreate.path("arn").asText())
                    .name(reservation.rawCreate.path("name").asText()).build();
        }
        if (reservation.response == null && reservation.uncertain) {
            List<Configuration> candidates = configurations().stream()
                    .filter(row -> reservation.name.equals(row.name()) && reservation.tags.equals(row.tags())).toList();
            assertEquals(1, candidates.size(), "Uncertain create retains its reservation until ownership is resolved");
            Configuration row = candidates.getFirst();
            reservation.response = CreateConfigurationResponse.builder().id(row.id()).arn(row.arn())
                    .name(row.name()).created(row.created()).build();
            LOG.infov("MQ_RECOVERED_CREATE name={0} response={1}", reservation.name, reservation.response);
        }
        if (reservation.response == null) {
            return;
        }
        if (reservation.response.id() == null && reservation.response.arn() != null
                && reservation.response.arn().contains(":kafka:")) {
            cleanupUnexpectedMskReceipt(reservation);
            return;
        }
        assertNotNull(reservation.response.id(), "Unrecognized successful receipt remains retained");
        deleteOwned(reservation);
    }

    private void deleteOwned(Reservation reservation) {
        describe(reservation);
        DeleteConfigurationResponse deleted = client.deleteConfiguration(request -> request
                .configurationId(reservation.response.id()));
        if (deleted.configurationId() != null) {
            assertEquals(reservation.response.id(), deleted.configurationId());
        }
        assertAbsent(reservation.response.id());
        reservation.deleted = true;
        LOG.infov("MQ_OWNED_DELETION id={0} arn={1}", reservation.response.id(), reservation.response.arn());
    }

    private void cleanupUnexpectedMskReceipt(Reservation reservation) {
        String arn = reservation.response.arn();
        assertTrue(arn.startsWith("arn:aws:kafka:us-east-1:" + ACCOUNT + ":configuration/" + reservation.name + "/"));
        assertNotNull(reservation.rawCreate, "Unknown service cleanup needs the original raw receipt");
        JsonNode creationTime = reservation.rawCreate.get("creationTime");
        assertNotNull(creationTime, "Unknown service cleanup needs a captured creation identity");
        assertFalse(creationTime.isNull());
        String date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        String scope = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/" + date
                + "/us-east-1/kafka/aws4_request, SignedHeaders=host, Signature=00";
        Response described = given().header("Authorization", scope).get("/v1/configurations/{arn}", arn);
        assertEquals(200, described.statusCode());
        assertEquals(arn, described.jsonPath().getString("arn"));
        assertEquals(reservation.name, described.jsonPath().getString("name"));
        try {
            assertEquals(creationTime, JSON.readTree(described.asByteArray()).get("creationTime"));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        LOG.infov("MQ_UNEXPECTED_MSK_OWNERSHIP arn={0} body={1}", arn, described.asString());
        Response deleted = given().header("Authorization", scope).delete("/v1/configurations/{arn}", arn);
        assertEquals(200, deleted.statusCode());
        assertEquals(arn, deleted.jsonPath().getString("arn"));
        Response absent = given().header("Authorization", scope).get("/v1/configurations/{arn}", arn);
        assertEquals(400, absent.statusCode());
        assertEquals("BadRequestException", absent.header("X-Amzn-Errortype"));
        assertTrue(absent.jsonPath().getString("message").contains("Configuration ARN does not exist"));
        reservation.deleted = true;
        LOG.infov("MQ_UNEXPECTED_MSK_DELETION arn={0} absence={1}", arn, absent.asString());
    }

    private void assertAbsent(String id) {
        MqException failure = assertThrows(MqException.class,
                () -> client.describeConfiguration(request -> request.configurationId(id)));
        assertError(failure, 404, "NotFoundException");
    }

    private static void assertError(MqException failure, int status, String code) {
        assertEquals(status, failure.statusCode());
        assertEquals(code, failure.awsErrorDetails().errorCode());
    }

    private static void assertDocument(DescribeConfigurationRevisionResponse response,
                                       String document, String description) {
        assertEquals(document, new String(Base64.getDecoder().decode(response.data()), StandardCharsets.UTF_8));
        assertEquals(description, response.description());
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Reservation {
        private final String name;
        private final Map<String, String> tags;
        private CreateConfigurationResponse response;
        private JsonNode rawCreate;
        private boolean uncertain;
        private boolean deleted;

        private Reservation(String name, Map<String, String> tags) {
            this.name = name;
            this.tags = tags;
        }
    }

    private final class WireReceipt implements ExecutionInterceptor {
        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            LOG.infov("MQ_SDK_TRANSMISSION request={0} method={1} uri={2}", context.request(),
                    context.httpRequest().method(), context.httpRequest().getUri());
            context.requestBody().ifPresent(body -> {
                try (InputStream stream = body.contentStreamProvider().newStream()) {
                    LOG.infov("MQ_SDK_REQUEST_BYTES base64={0}", Base64.getEncoder().encodeToString(stream.readAllBytes()));
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        }

        @Override
        public void afterTransmission(Context.AfterTransmission context, ExecutionAttributes attributes) {
            LOG.infov("MQ_SDK_RESPONSE request={0} status={1} headers={2}", context.request(),
                    context.httpResponse().statusCode(), context.httpResponse().headers());
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            if (context.responseBody().isEmpty()) {
                return Optional.empty();
            }
            try (InputStream original = context.responseBody().orElseThrow()) {
                byte[] bytes = original.readNBytes(1024 * 1024 + 1);
                assertTrue(bytes.length <= 1024 * 1024, "Fixture response exceeds its capture bound");
                LOG.infov("MQ_SDK_RESPONSE_BYTES base64={0}", Base64.getEncoder().encodeToString(bytes));
                if (context.request() instanceof CreateConfigurationRequest request
                        && context.httpResponse().isSuccessful()) {
                    Reservation reservation = reservations.stream()
                            .filter(row -> request.name().equals(row.name) && row.rawCreate == null)
                            .findFirst().orElseThrow();
                    reservation.rawCreate = JSON.readTree(bytes);
                }
                return Optional.of(new ByteArrayInputStream(bytes));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
