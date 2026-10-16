package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.redshiftserverless.model.ResourcePolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.function.Supplier;

/**
 * The Redshift Serverless actions that only describe or record metadata. The main
 * {@link RedshiftServerlessJsonHandler} hands every action it does not know to this class. The
 * request constraints (required members, patterns, lengths) are checked against the AWS model by
 * {@link RedshiftServerlessRequestShapes} before the action reaches it.
 */
@ApplicationScoped
public class RedshiftServerlessAdminJsonHandler {

    private static final List<String> TRACK_NAMES = RedshiftServerlessService.TRACK_NAMES;

    private final ObjectMapper objectMapper;
    private final RedshiftServerlessResourcePolicyService resourcePolicies;

    @Inject
    public RedshiftServerlessAdminJsonHandler(ObjectMapper objectMapper,
                                              RedshiftServerlessResourcePolicyService resourcePolicies) {
        this.objectMapper = objectMapper;
        this.resourcePolicies = resourcePolicies;
    }

    Supplier<Response> operation(String action, JsonNode request, String region) {
        return switch (action) {
            case "PutResourcePolicy" -> () -> resourcePolicyResponse(resourcePolicies.putResourcePolicy(
                    ServerlessSupport.text(request, "resourceArn"), ServerlessSupport.text(request, "policy"),
                    region));
            case "GetResourcePolicy" -> () -> resourcePolicyResponse(resourcePolicies.getResourcePolicy(
                    ServerlessSupport.text(request, "resourceArn"), region));
            case "DeleteResourcePolicy" -> () -> {
                resourcePolicies.deleteResourcePolicy(ServerlessSupport.text(request, "resourceArn"), region);
                return Response.ok(objectMapper.createObjectNode()).build();
            };
            case "GetTrack" -> () -> getTrack(ServerlessSupport.text(request, "trackName"));
            case "ListTracks" -> () -> listTracks(request);
            case "ListManagedWorkgroups" -> () -> listWithoutItems(request, "managedWorkgroups");
            case "ListReservations" -> () -> listWithoutItems(request, "reservationsList");
            case "ListReservationOfferings" -> () -> listWithoutItems(request, "reservationOfferingsList");
            case "GetReservation" -> () -> getReservation(request);
            case "GetReservationOffering" -> () -> getReservationOffering(request);
            case "CreateReservation" -> () -> createReservation(request);
            default -> null;
        };
    }

    private Response getReservation(JsonNode request) {
        throw ServerlessSupport.notFound("The reservation "
                + ServerlessSupport.text(request, "reservationId") + " was not found.");
    }

    private Response getReservationOffering(JsonNode request) {
        throw ServerlessSupport.notFound("The reservation offering "
                + ServerlessSupport.text(request, "offeringId") + " was not found.");
    }

    private Response createReservation(JsonNode request) {
        ServerlessSupport.integer(request, "capacity");
        ServerlessSupport.text(request, "clientToken");
        throw ServerlessSupport.notFound("The reservation offering "
                + ServerlessSupport.text(request, "offeringId") + " was not found.");
    }

    // ── Resource policy ────────────────────────────────────────────────────

    private Response resourcePolicyResponse(ResourcePolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("policy", policy.getPolicy());
        node.put("resourceArn", policy.getResourceArn());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourcePolicy", node);
        return Response.ok(response).build();
    }

    // ── Tracks, managed workgroups and reservations ────────────────────────

    /**
     * The release tracks AWS defines. The model gives a track name only a pattern, not an enum, so an
     * unknown name is a not-found here. Floci runs one PostgreSQL version, so a track names no workgroup version.
     */
    private Response getTrack(String trackName) {
        if (!TRACK_NAMES.contains(trackName)) {
            throw ServerlessSupport.notFound("The track " + trackName + " was not found.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("track", trackNode(trackName));
        return Response.ok(response).build();
    }

    private Response listTracks(JsonNode request) {
        PaginatedResult<String> page = Pagination.paginate(TRACK_NAMES, name -> name,
                ServerlessSupport.maxResults(request), ServerlessSupport.text(request, "nextToken"),
                100, 100, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("tracks");
        page.items().forEach(name -> items.add(trackNode(name)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode trackNode(String trackName) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("trackName", trackName);
        node.putArray("updateTargets");
        return node;
    }

    /**
     * A list that is always empty, still validating the paging members the way AWS does. Floci models no
     * Glue zero-ETL managed workgroups, publishes no reservation offerings and sells no reservations.
     */
    private Response listWithoutItems(JsonNode request, String listName) {
        Pagination.paginate(List.<String>of(), name -> name, ServerlessSupport.maxResults(request),
                ServerlessSupport.text(request, "nextToken"), 100, 100, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(listName);
        return Response.ok(response).build();
    }
}
