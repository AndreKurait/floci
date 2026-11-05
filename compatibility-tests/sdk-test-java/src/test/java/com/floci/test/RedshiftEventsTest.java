package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.DescribeEventsResponse;
import software.amazon.awssdk.services.redshift.model.Event;
import software.amazon.awssdk.services.redshift.model.EventCategoriesMap;
import software.amazon.awssdk.services.redshift.model.Parameter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Redshift event history")
class RedshiftEventsTest {

    @Test
    @DisplayName("SDK reads parameter-group events and their category metadata")
    void parameterGroupEventsAndCategories() {
        assumeFalse(TestFixtures.isRealAws(), "Checks immediate local event recording");
        String name = "compat-rsevent-" + UUID.randomUUID().toString().substring(0, 12);
        String sourceType = "cluster-parameter-group";
        Instant start = Instant.now().minusSeconds(1);
        try (RedshiftClient redshift = TestFixtures.redshiftClient()) {
            redshift.createClusterParameterGroup(request -> request.parameterGroupName(name)
                    .parameterGroupFamily("redshift-1.0").description("SDK event compatibility"));
            try {
                redshift.modifyClusterParameterGroup(request -> request.parameterGroupName(name)
                        .parameters(Parameter.builder().parameterName("statement_timeout")
                                .parameterValue("5000").build()));
                DescribeEventsResponse history = redshift.describeEvents(request -> request
                        .sourceIdentifier(name).sourceType(sourceType).startTime(start));
                assertThat(history.events()).extracting(Event::eventId)
                        .contains("REDSHIFT-EVENT-1003", "REDSHIFT-EVENT-1005");
                assertThat(history.events()).allSatisfy(event -> {
                    assertThat(event.sourceIdentifier()).isEqualTo(name);
                    assertThat(event.sourceTypeAsString()).isEqualTo(sourceType);
                    assertThat(event.date()).isAfterOrEqualTo(start);
                    assertThat(event.message()).isNotBlank();
                });

                List<EventCategoriesMap> categories = redshift.describeEventCategories(request -> request
                        .sourceType(sourceType)).eventCategoriesMapList();
                assertThat(categories).hasSize(1);
                assertThat(categories.get(0).sourceType()).isEqualTo(sourceType);
                assertThat(categories.get(0).events()).anySatisfy(info -> {
                    assertThat(info.eventId()).isEqualTo("REDSHIFT-EVENT-1003");
                    assertThat(info.eventCategories()).containsExactly("configuration");
                    assertThat(info.severity()).isEqualTo("INFO");
                });
            } finally {
                redshift.deleteClusterParameterGroup(request -> request.parameterGroupName(name));
            }
        }
    }
}
