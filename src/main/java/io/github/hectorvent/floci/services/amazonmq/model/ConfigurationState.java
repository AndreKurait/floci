package io.github.hectorvent.floci.services.amazonmq.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One detached scope value is the publication unit for configurations and their revisions. */
@RegisterForReflection
public record ConfigurationState(Scope scope, Map<String, Configuration> configurations,
                                 Map<String, Instant> retired) {

    public ConfigurationState {
        configurations = Collections.unmodifiableMap(new LinkedHashMap<>(configurations));
        retired = Collections.unmodifiableMap(new LinkedHashMap<>(retired));
    }

    @RegisterForReflection
    public record Scope(String partition, String accountId, String region) {
        public String key() {
            return partition + "/" + region;
        }
    }

    @RegisterForReflection
    public record Configuration(String id, String arn, String name, String engineType, String engineVersion,
                                String authenticationStrategy, Instant created, Map<String, String> tags,
                                List<Revision> revisions) {
        public Configuration {
            tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
            revisions = List.copyOf(revisions);
        }

        public Revision latest() {
            return revisions.getLast();
        }
    }

    @RegisterForReflection
    public record Revision(int revision, Instant created, String description, String data) {}
}
