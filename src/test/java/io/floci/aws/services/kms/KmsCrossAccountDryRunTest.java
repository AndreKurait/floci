package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.kms.model.KmsGrant;
import io.floci.aws.services.kms.model.KmsKey;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KmsCrossAccountDryRunTest {
    private static final String OWNER = "111122223333";
    private static final String CALLER = "444455556666";
    private static final String REGION = "us-east-1";

    @Test
    void foreignGrantDryRunChecksPermissionCreatesNothingAndRestoresTheCaller() {
        Fixture fixture = fixture(true);
        ObjectNode request = fixture.mapper().createObjectNode();
        request.put("KeyId", fixture.key().getArn());
        request.put("GranteePrincipal", "arn:aws:iam::" + CALLER + ":root");
        request.putArray("Operations").add("Encrypt");
        request.put("DryRun", true);
        AwsException error = assertThrows(AwsException.class,
                () -> fixture.handler().handle("CreateGrant", request, REGION));
        assertEquals("DryRunOperationException", error.getErrorCode());
        assertEquals(1, fixture.grants().keys().size());
        assertEquals(CALLER, fixture.context().getAccountId());
    }

    @Test
    void foreignDryRunWithoutPermissionIsDeniedBeforeItReportsSuccess() {
        Fixture fixture = fixture(false);
        ObjectNode request = fixture.mapper().createObjectNode();
        request.put("KeyId", fixture.key().getArn());
        request.put("Plaintext", "aGVsbG8=");
        request.put("DryRun", true);
        AwsException error = assertThrows(AwsException.class,
                () -> fixture.handler().handle("Encrypt", request, REGION));
        assertEquals("AccessDeniedException", error.getErrorCode());
        assertTrue(fixture.grants().keys().isEmpty());
        assertEquals(CALLER, fixture.context().getAccountId());
    }

    @Test
    void foreignEncryptionDryRunValidatesTheRequestAndRestoresTheCaller() {
        Fixture fixture = fixture(true);
        ObjectNode request = fixture.mapper().createObjectNode();
        request.put("KeyId", fixture.key().getArn());
        request.put("Plaintext", "aGVsbG8=");
        request.put("DryRun", "yes");
        AwsException error = assertThrows(AwsException.class,
                () -> fixture.handler().handle("Encrypt", request, REGION));
        assertEquals("DryRunOperationException", error.getErrorCode());
        assertEquals(1, fixture.grants().keys().size());
        assertEquals(CALLER, fixture.context().getAccountId());
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(boolean grantAccess) {
        RequestContext context = new RequestContext();
        context.setAccountId(OWNER);
        context.setRegion(REGION);
        context.setPartition("aws");
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        when(contexts.isResolvable()).thenReturn(true);
        Instance<IamService> iam = mock(Instance.class);
        RegionResolver regions = spy(new RegionResolver(REGION, OWNER));
        doAnswer(call -> context.getAccountId()).when(regions).getAccountId();
        InMemoryStorage<String, KmsGrant> grants = new InMemoryStorage<>();
        KmsService service = new KmsService(new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, OWNER),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), contexts, OWNER),
                new AccountAwareStorageBackend<>(grants, contexts, OWNER), regions);
        KmsKey key = service.createKey("shared", REGION);
        if (grantAccess) {
            service.createGrant(key.getKeyId(), "arn:aws:iam::" + CALLER + ":root",
                    List.of("Encrypt", "CreateGrant"), REGION);
        }
        context.setAccountId(CALLER);
        ObjectMapper mapper = new ObjectMapper();
        KmsJsonHandler handler = new KmsJsonHandler(service, mapper, regions, null, new KmsGrantCallers(contexts, iam));
        return new Fixture(context, grants, key, mapper, handler);
    }

    private record Fixture(RequestContext context, InMemoryStorage<String, KmsGrant> grants,
                           KmsKey key, ObjectMapper mapper, KmsJsonHandler handler) {}
}
