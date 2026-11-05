package io.github.hectorvent.floci.services.redshift;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.DescribeEventsResponse;
import software.amazon.awssdk.services.redshift.model.Event;
import software.amazon.awssdk.services.redshift.model.EventCategoriesMap;
import software.amazon.awssdk.services.redshift.model.Parameter;
import software.amazon.awssdk.services.redshift.model.RedshiftException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RedshiftEventsIntegrationTest {

    private static final String ACCOUNT = "313131313131";
    private static final String SOURCE_TYPE = "cluster-parameter-group";

    @TestHTTPResource
    URI endpoint;

    @Test
    void sdkDescribesRealParameterGroupEventsAndCategoryMetadata() {
        String name = name();
        Instant before = Instant.now().minusSeconds(1);
        try (RedshiftClient redshift = redshift(Region.US_EAST_1)) {
            createGroup(redshift, name);
            try {
                redshift.modifyClusterParameterGroup(request -> request.parameterGroupName(name)
                        .parameters(Parameter.builder().parameterName("statement_timeout").parameterValue("5000").build()));
                DescribeEventsResponse response = redshift.describeEvents(request -> request.sourceIdentifier(name)
                        .sourceType(SOURCE_TYPE).startTime(before).endTime(Instant.now().plusSeconds(1)));
                assertTrue(response.events().size() >= 2);
                Event created = response.events().stream().filter(event -> "REDSHIFT-EVENT-1003".equals(event.eventId()))
                        .findFirst().orElseThrow();
                assertEquals(name, created.sourceIdentifier());
                assertEquals(SOURCE_TYPE, created.sourceTypeAsString());
                assertEquals("INFO", created.severity());
                assertEquals(List.of("configuration"), created.eventCategories());
                assertFalse(created.date().isBefore(before));
                assertTrue(created.message().contains(name));
                assertTrue(response.events().stream().anyMatch(event -> "REDSHIFT-EVENT-1005".equals(event.eventId())));

                List<EventCategoriesMap> categories = redshift.describeEventCategories(request -> request
                        .sourceType(SOURCE_TYPE)).eventCategoriesMapList();
                assertEquals(1, categories.size());
                assertEquals(SOURCE_TYPE, categories.getFirst().sourceType());
                assertTrue(categories.getFirst().events().stream().anyMatch(info ->
                        "REDSHIFT-EVENT-1003".equals(info.eventId())
                                && info.eventCategories().equals(List.of("configuration"))
                                && "INFO".equals(info.severity()) && !info.eventDescription().isBlank()));
            } finally {
                redshift.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
            }
            assertTrue(redshift.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                    .events().stream().anyMatch(event -> "REDSHIFT-EVENT-1004".equals(event.eventId())));
        }
    }

    @Test
    void sdkReceivesAwsErrorsForAnUnpairedSourceAndOutOfRangePages() {
        String name = name();
        try (RedshiftClient redshift = redshift(Region.US_EAST_1)) {
            RedshiftException unpaired = assertThrows(RedshiftException.class, () -> redshift.describeEvents(request ->
                    request.sourceIdentifier(name)));
            assertEquals("InvalidParameterCombination", unpaired.awsErrorDetails().errorCode());
            RedshiftException pageSize = assertThrows(RedshiftException.class, () -> redshift.describeEvents(request ->
                    request.maxRecords(19)));
            assertEquals("InvalidParameterValue", pageSize.awsErrorDetails().errorCode());
        }
    }

    @Test
    void historyIsIsolatedByRegion() {
        String name = name();
        try (RedshiftClient east = redshift(Region.US_EAST_1); RedshiftClient west = redshift(Region.US_WEST_2)) {
            createGroup(east, name);
            try {
                assertEquals(1, east.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                        .events().size());
                assertTrue(west.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                        .events().isEmpty());
            } finally {
                east.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
            }
        }
    }

    @Test
    void historyIsIsolatedByAccount() {
        String name = name();
        String otherAccount = "414141414141";
        try (RedshiftClient owner = redshift(Region.US_EAST_1, ACCOUNT);
                RedshiftClient other = redshift(Region.US_EAST_1, otherAccount)) {
            createGroup(owner, name);
            try {
                assertEquals(1, owner.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                        .events().size());
                assertTrue(other.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                        .events().isEmpty());
                createGroup(other, name);
                try {
                    assertEquals(1, other.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                            .events().size());
                    assertEquals(1, owner.describeEvents(request -> request.sourceIdentifier(name).sourceType(SOURCE_TYPE))
                            .events().size());
                } finally {
                    other.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
                }
            } finally {
                owner.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
            }
        }
    }

    private RedshiftClient redshift(Region region) {
        return redshift(region, ACCOUNT);
    }

    private RedshiftClient redshift(Region region, String account) {
        return RedshiftClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private static String name() {
        return "sdk-rsevent-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static void createGroup(RedshiftClient client, String name) {
        client.createClusterParameterGroup(request -> request.parameterGroupName(name)
                .parameterGroupFamily("redshift-1.0").description("SDK event integration"));
    }
}
