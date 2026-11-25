package io.floci.aws.services.cognitoidentity;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cognitoidentity.model.CognitoIdentity;
import io.floci.aws.services.cognitoidentity.model.IdentityPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CognitoIdentityStateTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String PROVIDER = "login.example.test";
    @TempDir Path directory;

    @Test
    void linksPaginationDeactivationAndDeletionSurvivePersistentReload() {
        CognitoIdentityService first = service(ACCOUNT);
        IdentityPool pool = first.createIdentityPool(spec(), REGION);
        CognitoIdentity guest = first.getId(pool.getIdentityPoolId(), ACCOUNT, Map.of(), REGION);
        CognitoIdentity linked = first.developerIdentity(pool.getIdentityPoolId(), guest.identityId(),
                Map.of(PROVIDER, "alice"), REGION);
        first.developerIdentity(pool.getIdentityPoolId(), linked.identityId(), Map.of(PROVIDER, "bob"), REGION);
        assertThrows(AwsException.class, () -> CognitoIdentityService.requireGuest(pool, linked));
        CognitoIdentityService restarted = service(ACCOUNT);
        CognitoIdentity restored = restarted.describeIdentity(linked.identityId(), REGION);
        assertEquals(guest.creationDate(), restored.creationDate());
        assertEquals(List.of("alice", "bob"), restored.developerUserIdentifiers());
        CognitoIdentityService.DeveloperLookup page = restarted.lookupDeveloper(pool.getIdentityPoolId(),
                restored.identityId(), null, 1, null, REGION);
        assertEquals(List.of("alice"), page.developerUserIdentifiers());
        assertEquals(List.of("bob"), restarted.lookupDeveloper(pool.getIdentityPoolId(), restored.identityId(),
                null, 1, page.nextToken(), REGION).developerUserIdentifiers());
        restarted.unlinkDeveloper(pool.getIdentityPoolId(), restored.identityId(), PROVIDER, "alice", REGION);
        restarted.unlinkDeveloper(pool.getIdentityPoolId(), restored.identityId(), PROVIDER, "bob", REGION);
        CognitoIdentityService afterUnlink = service(ACCOUNT);
        CognitoIdentity disabled = afterUnlink.describeIdentity(restored.identityId(), REGION);
        assertFalse(disabled.active());
        assertTrue(disabled.authenticated(), "Last unlink must never turn an authenticated identity into a guest");
        assertTrue(afterUnlink.listIdentities(pool.getIdentityPoolId(), 60, null, true, REGION).identities().isEmpty());
        assertThrows(AwsException.class, () -> CognitoIdentityService.requireGuest(pool, disabled));
        afterUnlink.deleteIdentities(List.of(disabled.identityId()), REGION);
        assertThrows(AwsException.class, () -> service(ACCOUNT).describeIdentity(disabled.identityId(), REGION));
        assertEquals(pool.getIdentityPoolId(), service(ACCOUNT).describeIdentityPool(pool.getIdentityPoolId(), REGION)
                .getIdentityPoolId());
    }

    @Test
    void refusesCrossPoolLinksForeignAdministrativeReadsAndInvalidDeleteBeforeMutation() {
        CognitoIdentityService owner = service(ACCOUNT);
        IdentityPool pool = owner.createIdentityPool(spec(), REGION);
        IdentityPool other = owner.createIdentityPool(spec(), REGION);
        CognitoIdentity alice = owner.developerIdentity(pool.getIdentityPoolId(), null, Map.of(PROVIDER, "alice"), REGION);
        CognitoIdentity bob = owner.developerIdentity(pool.getIdentityPoolId(), null, Map.of(PROVIDER, "bob"), REGION);
        assertThrows(AwsException.class, () -> owner.developerIdentity(pool.getIdentityPoolId(), bob.identityId(),
                Map.of(PROVIDER, "alice"), REGION));
        assertThrows(AwsException.class, () -> owner.developerIdentity(other.getIdentityPoolId(), alice.identityId(),
                Map.of(PROVIDER, "alice"), REGION));
        assertThrows(AwsException.class, () -> owner.deleteIdentities(List.of(alice.identityId(), "invalid"), REGION));
        assertEquals(alice, owner.describeIdentity(alice.identityId(), REGION));
        CognitoIdentityService foreign = service("999999999999");
        assertThrows(AwsException.class, () -> foreign.describeIdentity(alice.identityId(), REGION));
        assertThrows(AwsException.class, () -> foreign.lookupDeveloper(pool.getIdentityPoolId(), null, "alice",
                60, null, REGION));
        assertEquals(alice, foreign.publicIdentity(alice.identityId(), REGION), "Public identity lookup resolves its owner");
        assertThrows(AwsException.class, () -> owner.publicIdentity(alice.identityId(), "us-west-2"));
        assertThrows(AwsException.class, () -> owner.getId(pool.getIdentityPoolId(), "999999999999", Map.of(), REGION));
        owner.deleteIdentityPool(pool.getIdentityPoolId(), REGION);
        assertThrows(AwsException.class, () -> service(ACCOUNT).publicIdentity(alice.identityId(), REGION));
        assertEquals(other.getIdentityPoolId(), service(ACCOUNT).describeIdentityPool(other.getIdentityPoolId(), REGION)
                .getIdentityPoolId());
    }

    @Test
    void failedTokenPreparationDoesNotPublishADeveloperLink() {
        CognitoIdentityService service = service(ACCOUNT);
        IdentityPool pool = service.createIdentityPool(spec(), REGION);
        assertThrows(IllegalStateException.class, () -> service.developerIdentity(pool.getIdentityPoolId(), null,
                Map.of(PROVIDER, "alice"), REGION, identity -> {
                    throw new IllegalStateException("signing unavailable");
                }));
        assertTrue(service(ACCOUNT).listIdentities(pool.getIdentityPoolId(), 60, null, false, REGION)
                .identities().isEmpty());
    }

    private CognitoIdentityService service(String account) {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenAnswer(call ->
                store(account, call.getArgument(1), call.getArgument(2)));
        return new CognitoIdentityService(factory, new RegionResolver(REGION, account));
    }

    private <T> StorageBackend<String, T> store(String account, String filename, TypeReference<Map<String, T>> type) {
        PersistentStorage<String, T> storage = new PersistentStorage<>(directory.resolve(filename), type);
        storage.load();
        return new AccountAwareStorageBackend<>(storage, null, account);
    }

    private IdentityPool spec() {
        IdentityPool pool = new IdentityPool();
        pool.setIdentityPoolName("identity-state");
        pool.setDeveloperProviderName(PROVIDER);
        pool.setAllowUnauthenticatedIdentities(true);
        pool.setAllowClassicFlow(true);
        return pool;
    }
}
