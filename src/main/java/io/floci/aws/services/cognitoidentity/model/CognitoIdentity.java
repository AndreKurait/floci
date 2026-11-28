package io.floci.aws.services.cognitoidentity.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

/** Immutable persisted identity; developer links have one authoritative representation. */
@RegisterForReflection
public record CognitoIdentity(String identityId, String identityPoolId, String accountId, String region,
                              Instant creationDate, Instant lastModifiedDate, boolean authenticated,
                              boolean active, List<String> developerUserIdentifiers) {
    public CognitoIdentity {
        developerUserIdentifiers = developerUserIdentifiers == null ? List.of()
                : List.copyOf(developerUserIdentifiers);
    }

    public CognitoIdentity withDeveloperUsers(List<String> users, Instant now) {
        return new CognitoIdentity(identityId, identityPoolId, accountId, region, creationDate,
                now, true, !users.isEmpty(), users);
    }
}
