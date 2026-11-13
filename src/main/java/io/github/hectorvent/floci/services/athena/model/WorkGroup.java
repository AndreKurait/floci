package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class WorkGroup {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("State")
    private String state;

    @JsonProperty("CreationTime")
    private Instant creationTime;

    @JsonProperty("Tags")
    private List<WorkGroupTag> tags = new ArrayList<>();

    @JsonProperty("Configuration")
    private WorkGroupConfiguration configuration;

    @JsonProperty("PreparedStatements")
    private Map<String, PreparedStatement> preparedStatements = Map.of();

    public WorkGroup() {}

    public Map<String, PreparedStatement> getPreparedStatements() { return preparedStatements; }
    public void setPreparedStatements(Map<String, PreparedStatement> statements) {
        this.preparedStatements = statements == null ? Map.of() : Map.copyOf(statements);
    }

    public WorkGroup withPreparedStatements(Map<String, PreparedStatement> statements) {
        WorkGroup copy = new WorkGroup();
        copy.setName(name);
        copy.setDescription(description);
        copy.setState(state);
        copy.setCreationTime(creationTime);
        copy.setTags(tags);
        copy.setConfiguration(configuration);
        copy.setPreparedStatements(statements);
        return copy;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public List<WorkGroupTag> getTags() { return tags; }
    public void setTags(List<WorkGroupTag> tags) { this.tags = tags; }
    public WorkGroupConfiguration getConfiguration() { return configuration; }
    public void setConfiguration(WorkGroupConfiguration configuration) { this.configuration = configuration; }
}
