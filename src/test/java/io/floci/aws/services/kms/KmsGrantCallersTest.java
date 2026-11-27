package io.floci.aws.services.kms;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.IamService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KmsGrantCallersTest {
    @Test
    @SuppressWarnings("unchecked")
    void unsetRequestPartitionUsesDeploymentPartitionAndOwnerScopeAlwaysRestoresCaller() {
        RequestContext context = new RequestContext();
        context.setAccountId("000000000312");
        Instance<RequestContext> contexts = mock(Instance.class);
        when(contexts.get()).thenReturn(context);
        Instance<IamService> iam = mock(Instance.class);
        KmsGrantCallers callers = new KmsGrantCallers(contexts, iam, new RegionResolver("us-gov-west-1", "000000000312"));

        assertEquals("arn:aws-us-gov:iam::000000000312:root", callers.current().arn());
        assertTrue(callers.current().principals().contains("arn:aws-us-gov:iam::000000000312:root"));
        assertEquals("000000000311", callers.asAccount("000000000311", context::getAccountId));
        assertEquals("000000000312", context.getAccountId());
        assertThrows(IllegalStateException.class, () -> callers.asAccount("000000000311", () -> {
            throw new IllegalStateException("operation failed");
        }));
        assertEquals("000000000312", context.getAccountId());
    }
}
