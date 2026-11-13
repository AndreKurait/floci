package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.PutParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.Tag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Timeout(90)
class SsmGetParametersOrderingIntegrationTest {
    private static final Logger LOG = Logger.getLogger(SsmGetParametersOrderingIntegrationTest.class);
    private static final String ACCOUNT = "741661402501";
    private static final ExecutionAttribute<WireCall> WIRE = new ExecutionAttribute<>("ssm-order-wire");

    private final List<Reservation> reservations = new ArrayList<>();
    private final List<WireCall> calls = new ArrayList<>();
    private SsmClient client;
    private String marker;

    @BeforeEach
    void openClient() {
        marker = "batch-order-" + UUID.randomUUID();
        client = AwsTestClients.create(SsmClient.builder().overrideConfiguration(config -> config
                .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(12))
                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(new WireReceipt())),
                ACCOUNT, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(10)));
    }

    @AfterEach
    void removeOnlyFreshlyVerifiedOwnedParameters() {
        List<Throwable> failures = new ArrayList<>();
        try {
            for (Reservation reservation : reservations) {
                if (!reservation.attempted) {
                    continue;
                }
                try {
                    Parameter current = readOwned(reservation);
                    LOG.infov("SSM_ORDER_DELETE_OWNER marker={0} parameter={1} tags={2}",
                            marker, current, reservation.tags);
                    assertEquals(200, client.deleteParameter(request -> request.name(reservation.name))
                            .sdkHttpResponse().statusCode());
                    assertAbsent(reservation.name);
                    reservation.deleted = true;
                    LOG.infov("SSM_ORDER_CLEANUP_COMPLETE marker={0} name={1} deleted={2}",
                            marker, reservation.name, reservation.deleted);
                } catch (Throwable failure) {
                    LOG.errorv(failure, "SSM_ORDER_CLEANUP_RETAINED marker={0} name={1}", marker, reservation.name);
                    failures.add(failure);
                }
            }
            try {
                for (WireCall call : calls) {
                    assertNotNull(call.request, "Every dispatched call must retain request bytes");
                    assertNotNull(call.response, "Every completed call must retain response bytes before parsing");
                    assertTrue(call.status >= 100, "Every completed call must retain its HTTP status");
                }
            } catch (Throwable failure) {
                failures.add(failure);
            }
        } finally {
            client.close();
        }
        assertAll("Every attempted owned write and SDK call retains its receipt",
                failures.stream().map(failure -> () -> {
                    throw failure;
                }));
    }

    @Test
    void reversedNamesReturnAlphabeticallyWithCompleteStoredParameters() {
        List<Reservation> values = three();
        List<String> input = List.of(values.get(2).name, values.get(1).name, values.get(0).name);
        assertAlphabetical(batch(input), snapshots(values), List.of());
    }

    @Test
    void permutedNamesReturnAlphabeticallyWithCompleteStoredParameters() {
        List<Reservation> values = three();
        List<String> input = List.of(values.get(1).name, values.get(2).name, values.get(0).name);
        assertAlphabetical(batch(input), snapshots(values), List.of());
    }

    @Test
    void missingNamesDoNotChangeTheAlphabeticalOrderOfSuccessfulResults() {
        List<Reservation> values = three();
        String missingA = "/" + marker + "/missing-a";
        String missingB = "/" + marker + "/missing-b";
        assertAbsent(missingA);
        assertAbsent(missingB);
        List<String> input = List.of(missingB, values.get(2).name, values.get(0).name,
                missingA, values.get(1).name);
        assertAlphabetical(batch(input), snapshots(values), List.of(missingA, missingB));
    }

    @Test
    void selectedVersionsKeepTheirWholeValuesWhileDifferentNamesSortAlphabetically() {
        List<Reservation> values = three();
        Reservation alpha = values.getFirst();
        addSecondVersionAndLabel(alpha);
        List<String> input = List.of(values.get(2).name, alpha.name + ":2", values.get(1).name,
                alpha.name + ":1", alpha.name + ":pinned");
        List<Parameter> expected = input.stream()
                .map(name -> client.getParameter(request -> request.name(name)).parameter()).toList();
        assertEquals(List.of(2L, 1L, 1L), expected.stream().filter(value -> value.name().equals(alpha.name))
                .map(Parameter::version).toList());
        expected.stream().filter(value -> value.name().equals(alpha.name))
                .forEach(value -> assertEquals(alpha.values.get(value.version()), value.value()));
        assertAlphabetical(batch(input), expected, List.of());
    }

    @Test
    void sameNameSelectorsRetainEverySnapshotWithoutATieOrderClaim() {
        Reservation alpha = create("alpha");
        addSecondVersionAndLabel(alpha);
        List<String> input = List.of(alpha.name + ":2", alpha.name + ":1", alpha.name + ":pinned", alpha.name);
        List<Parameter> expected = input.stream()
                .map(name -> client.getParameter(request -> request.name(name)).parameter()).toList();
        assertEquals(List.of(":2", ":1", ":pinned", ""), expected.stream()
                .map(value -> value.selector() == null ? "" : value.selector()).toList());
        assertEquals(List.of(2L, 1L, 1L, 2L), expected.stream().map(Parameter::version).toList());
        expected.forEach(value -> assertEquals(alpha.values.get(value.version()), value.value()));
        GetParametersResponse response = batch(input);
        assertWholeMembers(response, expected, List.of());
        LOG.infov("SSM_ORDER_TIE_OBSERVATION marker={0} requested={1} returned={2}",
                marker, input, response.parameters());
    }

    @Test
    void singleNameRetainsEveryStoredField() {
        Reservation value = create("alpha");
        assertAlphabetical(batch(List.of(value.name)), List.of(value.snapshot), List.of());
    }

    @Test
    void allMissingNamesHaveNoSuccessfulParametersWithoutAnInvalidOrderClaim() {
        String a = "/" + marker + "/missing-a";
        String b = "/" + marker + "/missing-b";
        assertAbsent(a);
        assertAbsent(b);
        assertWholeMembers(batch(List.of(b, a)), List.of(), List.of(a, b));
    }

    private List<Reservation> three() {
        return List.of(create("alpha"), create("bravo"), create("charlie"));
    }

    private List<Parameter> snapshots(List<Reservation> values) {
        return values.stream().map(value -> value.snapshot).toList();
    }

    private Reservation create(String slot) {
        String name = "/" + marker + "/" + slot;
        Reservation reservation = new Reservation(name, Map.of("audit-owner", marker, "audit-slot", slot));
        reservation.values.put(1L, marker + ":" + slot + ":first");
        reservations.add(reservation);
        LOG.infov("SSM_ORDER_RESERVATION marker={0} name={1} tags={2}", marker, name, reservation.tags);
        assertAbsent(name);
        reservation.attempted = true;
        PutParameterResponse response = client.putParameter(request -> request.name(name).type("String")
                .dataType("text").tier("Standard").value(reservation.values.get(1L)).overwrite(false)
                .tags(reservation.tags.entrySet().stream()
                        .map(tag -> Tag.builder().key(tag.getKey()).value(tag.getValue()).build()).toList()));
        LOG.infov("SSM_ORDER_CREATE marker={0} name={1} response={2}", marker, name, response);
        assertEquals(200, response.sdkHttpResponse().statusCode());
        assertEquals(1L, response.version());
        readOwned(reservation);
        return reservation;
    }

    private void addSecondVersionAndLabel(Reservation reservation) {
        readOwned(reservation);
        reservation.values.put(2L, marker + ":alpha:second");
        PutParameterResponse response = client.putParameter(request -> request.name(reservation.name).type("String")
                .value(reservation.values.get(2L)).overwrite(true));
        LOG.infov("SSM_ORDER_UPDATE marker={0} name={1} response={2}", marker, reservation.name, response);
        assertEquals(200, response.sdkHttpResponse().statusCode());
        assertEquals(2L, response.version());
        readOwned(reservation);
        assertEquals(200, client.labelParameterVersion(request -> request.name(reservation.name)
                .parameterVersion(1L).labels("pinned")).sdkHttpResponse().statusCode());
        readOwned(reservation);
    }

    private Parameter readOwned(Reservation reservation) {
        Parameter current = client.getParameter(request -> request.name(reservation.name)
                .withDecryption(false)).parameter();
        Map<String, String> actualTags = new LinkedHashMap<>();
        client.listTagsForResource(request -> request.resourceType("Parameter").resourceId(reservation.name))
                .tagList().forEach(tag -> assertNull(actualTags.put(tag.key(), tag.value()), "Duplicate tag key"));
        LOG.infov("SSM_ORDER_OWNED_READ marker={0} parameter={1} tags={2}", marker, current, actualTags);
        assertEquals(reservation.name, current.name());
        assertEquals("arn:aws:ssm:us-east-1:" + ACCOUNT + ":parameter" + reservation.name, current.arn());
        assertEquals("String", current.typeAsString());
        assertEquals("text", current.dataType());
        assertTrue(reservation.values.containsKey(current.version()), "Only reserved versions establish ownership");
        assertEquals(reservation.values.get(current.version()), current.value());
        assertNotNull(current.lastModifiedDate());
        assertNull(current.selector());
        assertNull(current.sourceResult());
        assertEquals(reservation.tags, actualTags);
        if (reservation.snapshot != null && reservation.snapshot.version().equals(current.version())) {
            assertTrue(reservation.snapshot.equalsBySdkFields(current), "Every stored field must remain unchanged");
        }
        reservation.snapshot = current;
        return current;
    }

    private void assertAbsent(String name) {
        SsmException response = assertThrows(SsmException.class,
                () -> client.getParameter(request -> request.name(name)));
        assertEquals(400, response.statusCode());
        assertEquals("ParameterNotFound", response.awsErrorDetails().errorCode());
    }

    private GetParametersResponse batch(List<String> names) {
        GetParametersResponse response = client.getParameters(request -> request.names(names).withDecryption(false));
        LOG.infov("SSM_ORDER_BATCH marker={0} requested={1} response={2}", marker, names, response);
        return response;
    }

    private static void assertWholeMembers(GetParametersResponse response, List<Parameter> expected,
                                           List<String> missing) {
        assertEquals(200, response.sdkHttpResponse().statusCode());
        assertEquals(expected.size(), response.parameters().size());
        assertEquals(missing.size(), response.invalidParameters().size());
        assertEquals(missing.stream().sorted().toList(), response.invalidParameters().stream().sorted().toList());
        Map<String, Parameter> expectedBySelector = new HashMap<>();
        for (Parameter value : expected) {
            assertNull(expectedBySelector.put(selectorKey(value), value), "Fixture queries must be distinct");
        }
        for (Parameter value : response.parameters()) {
            Parameter original = expectedBySelector.remove(selectorKey(value));
            assertNotNull(original, "No unrequested or duplicate result may replace a requested member");
            assertTrue(original.equalsBySdkFields(value), "All modeled parameter fields must match its exact read");
        }
        assertTrue(expectedBySelector.isEmpty());
    }

    private static void assertAlphabetical(GetParametersResponse response, List<Parameter> expected,
                                           List<String> missing) {
        assertWholeMembers(response, expected, missing);
        List<String> names = response.parameters().stream().map(Parameter::name).toList();
        assertEquals(names.stream().sorted().toList(), names,
                "GetParameters documents alphabetical result order by returned Name");
    }

    private static String selectorKey(Parameter value) {
        return value.name() + (value.selector() == null ? "" : value.selector());
    }

    private static final class Reservation {
        private final String name;
        private final Map<String, String> tags;
        private final Map<Long, String> values = new LinkedHashMap<>();
        private Parameter snapshot;
        private boolean attempted;
        private boolean deleted;

        private Reservation(String name, Map<String, String> tags) {
            this.name = name;
            this.tags = tags;
        }
    }

    private static final class WireCall {
        private final int id;
        private byte[] request;
        private byte[] response;
        private int status;

        private WireCall(int id) {
            this.id = id;
        }
    }

    private final class WireReceipt implements ExecutionInterceptor {
        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            WireCall call = new WireCall(calls.size() + 1);
            calls.add(call);
            attributes.putAttribute(WIRE, call);
            try (InputStream stream = context.requestBody().orElseThrow().contentStreamProvider().newStream()) {
                call.request = stream.readNBytes(1024 * 1024 + 1);
                LOG.infov("SSM_ORDER_REQUEST marker={0} id={1} method={2} uri={3} target={4} base64={5}",
                        marker, call.id, context.httpRequest().method(), context.httpRequest().getUri(),
                        context.httpRequest().firstMatchingHeader("X-Amz-Target").orElseThrow(),
                        Base64.getEncoder().encodeToString(call.request));
                assertTrue(call.request.length <= 1024 * 1024, "Fixture request capture bound");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            WireCall call = attributes.getAttribute(WIRE);
            call.status = context.httpResponse().statusCode();
            if (context.responseBody().isEmpty()) {
                call.response = new byte[0];
                LOG.infov("SSM_ORDER_RESPONSE marker={0} id={1} status={2} headers={3} base64=",
                        marker, call.id, call.status, context.httpResponse().headers());
                return Optional.empty();
            }
            try (InputStream stream = context.responseBody().orElseThrow()) {
                call.response = stream.readNBytes(1024 * 1024 + 1);
                LOG.infov("SSM_ORDER_RESPONSE marker={0} id={1} status={2} headers={3} base64={4}",
                        marker, call.id, call.status, context.httpResponse().headers(),
                        Base64.getEncoder().encodeToString(call.response));
                assertTrue(call.response.length <= 1024 * 1024, "Fixture response capture bound");
                return Optional.of(new ByteArrayInputStream(call.response));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
