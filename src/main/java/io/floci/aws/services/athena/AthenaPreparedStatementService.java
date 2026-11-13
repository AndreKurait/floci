package io.floci.aws.services.athena;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.athena.model.PreparedStatement;
import io.floci.aws.services.athena.model.WorkGroup;
import io.floci.aws.services.athena.model.WorkGroupTag;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Stored SQL metadata; these operations do not prepare or execute SQL in the query engine. */
@ApplicationScoped
public class AthenaPreparedStatementService {

    private static final int MAX_STATEMENTS = 1000;
    private static final int MAX_BATCH_SIZE = 256;
    private static final Pattern NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_@:]{1,256}");
    private static final Pattern WORK_GROUP = Pattern.compile("[a-zA-Z0-9._-]{1,128}");
    private static final Set<String> ACTIONS = Set.of("athena:CreatePreparedStatement", "athena:GetPreparedStatement",
            "athena:UpdatePreparedStatement", "athena:DeletePreparedStatement", "athena:BatchGetPreparedStatement");
    private final AthenaService athenaService;

    public AthenaPreparedStatementService(AthenaService athenaService) {
        this.athenaService = athenaService;
    }

    public void create(String region, String workGroup, String name, String query, String description) {
        validate(workGroup, name, query, description);
        synchronized (athenaService.workGroupLock()) {
            WorkGroup group = athenaService.preparedStatementWorkGroup(region, workGroup);
            Map<String, PreparedStatement> current = group.getPreparedStatements();
            if (current.containsKey(name)) {
                throw invalid("Prepared statement " + name + " already exists in workgroup " + workGroup + ".");
            }
            if (current.size() >= MAX_STATEMENTS) {
                throw invalid("A workgroup can contain at most 1000 prepared statements.");
            }
            Map<String, PreparedStatement> updated = new LinkedHashMap<>(current);
            updated.put(name, new PreparedStatement(name, workGroup, query, description, Instant.now()));
            athenaService.savePreparedStatements(region, group, updated);
        }
    }

    public PreparedStatement get(String region, String workGroup, String name) {
        validateSelector(workGroup, name);
        synchronized (athenaService.workGroupLock()) {
            return requireStatement(athenaService.preparedStatementWorkGroup(region, workGroup), name);
        }
    }

    public void update(String region, String workGroup, String name, String query, String description) {
        validate(workGroup, name, query, description);
        synchronized (athenaService.workGroupLock()) {
            WorkGroup group = athenaService.preparedStatementWorkGroup(region, workGroup);
            PreparedStatement current = requireStatement(group, name);
            String updatedDescription = description == null ? current.description() : description;
            Map<String, PreparedStatement> updated = new LinkedHashMap<>(group.getPreparedStatements());
            updated.put(name, new PreparedStatement(name, workGroup, query, updatedDescription, Instant.now()));
            athenaService.savePreparedStatements(region, group, updated);
        }
    }

    public void delete(String region, String workGroup, String name) {
        validateSelector(workGroup, name);
        synchronized (athenaService.workGroupLock()) {
            WorkGroup group = athenaService.preparedStatementWorkGroup(region, workGroup);
            requireStatement(group, name);
            Map<String, PreparedStatement> updated = new LinkedHashMap<>(group.getPreparedStatements());
            updated.remove(name);
            athenaService.savePreparedStatements(region, group, updated);
        }
    }

    public BatchResult batchGet(String region, String workGroup, List<String> names) {
        validateWorkGroup(workGroup);
        if (names == null) {
            throw invalid("PreparedStatementNames is required.");
        }
        if (names.size() > MAX_BATCH_SIZE) {
            throw invalid("PreparedStatementNames must contain at most 256 names.");
        }
        for (String name : names) {
            validateName(name);
        }
        List<String> requested = List.copyOf(names);
        synchronized (athenaService.workGroupLock()) {
            Map<String, PreparedStatement> current = athenaService.preparedStatementWorkGroup(region, workGroup)
                    .getPreparedStatements();
            List<PreparedStatement> found = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String name : requested) {
                PreparedStatement statement = current.get(name);
                if (statement == null) {
                    missing.add(name);
                } else {
                    found.add(statement);
                }
            }
            return new BatchResult(List.copyOf(found), List.copyOf(missing));
        }
    }

    public static boolean supportsAction(String action) {
        return ACTIONS.contains(action);
    }

    /** Tags are read from the same account and region as the prepared-statement request. */
    public Map<String, List<String>> resourceTagConditions(String region, String workGroup) {
        validateWorkGroup(workGroup);
        synchronized (athenaService.workGroupLock()) {
            WorkGroup group = athenaService.preparedStatementWorkGroup(region, workGroup);
            Map<String, List<String>> conditions = new LinkedHashMap<>();
            if (group.getTags() != null) {
                for (WorkGroupTag tag : group.getTags()) {
                    if (tag != null && tag.getKey() != null && tag.getValue() != null) {
                        conditions.put("aws:ResourceTag/" + tag.getKey(), List.of(tag.getValue()));
                    }
                }
            }
            return conditions;
        }
    }

    private static PreparedStatement requireStatement(WorkGroup group, String name) {
        PreparedStatement statement = group.getPreparedStatements().get(name);
        if (statement == null) {
            throw new AwsException("ResourceNotFoundException", "Prepared statement " + name + " was not found.", 400);
        }
        return statement;
    }

    private static void validate(String workGroup, String name, String query, String description) {
        validateSelector(workGroup, name);
        length("QueryStatement", query, 1, 262144);
        if (description != null) {
            length("Description", description, 1, 1024);
        }
    }

    private static void validateSelector(String workGroup, String name) {
        validateWorkGroup(workGroup);
        validateName(name);
    }

    private static void validateWorkGroup(String name) {
        if (name == null || !WORK_GROUP.matcher(name).matches()) {
            throw invalid("WorkGroup must match [a-zA-Z0-9._-]{1,128}.");
        }
    }

    private static void validateName(String name) {
        length("StatementName", name, 1, 256);
        if (!NAME.matcher(name).matches()) {
            throw invalid("StatementName must match [a-zA-Z_][a-zA-Z0-9_@:]{1,256}.");
        }
    }

    private static void length(String member, String value, int minimum, int maximum) {
        int size = value == null ? 0 : value.codePointCount(0, value.length());
        if (size < minimum || size > maximum) {
            throw invalid(member + " must contain " + minimum + " to " + maximum + " characters.");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    public record BatchResult(List<PreparedStatement> statements, List<String> missing) {
    }
}
