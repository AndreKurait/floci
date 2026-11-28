package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.cloudformation.model.ChangeSet;
import io.floci.aws.services.iam.IamService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudFormationOperationContextTest {
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/execution";
    private static final String BOUNDARY = "arn:aws:iam::000000000000:policy/delegation-boundary";
    private final CloudFormationService service = mock(CloudFormationService.class);
    private final IamService iam = mock(IamService.class);
    private final RequestContext request = mock(RequestContext.class);
    private final CloudFormationQueryHandler handler = new CloudFormationQueryHandler(
            service, mock(StackSetService.class), iam, request);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createRetainsDelegationWhilePassingRoleRollbackPreferenceAndToken(boolean legacyConstructor) {
        ChangeSet initial = new ChangeSet();
        initial.setStackId("arn:aws:cloudformation:us-east-1:000000000000:stack/context/id");
        initial.setChangeSetId("initial-id");
        when(service.createChangeSetForCreateStack(anyString(), anyString(), any(), any(), any(), any(), any(),
                anyString(), any(), anyBoolean(), any())).thenReturn(initial);
        when(request.getAccessKeyId()).thenReturn("caller");
        when(iam.delegatedRoleBoundaryArn("caller")).thenReturn(Optional.of(BOUNDARY));
        MultivaluedMap<String, String> params = operationParameters();
        params.putSingle("TemplateBody", "{\"Resources\":{}}");
        params.putSingle("DisableRollback", "true");
        params.putSingle("EnableTerminationProtection", "true");

        CloudFormationQueryHandler selectedHandler = legacyConstructor
                ? new CloudFormationQueryHandler(service, mock(StackSetService.class)) : handler;
        try (Response response = selectedHandler.handle("CreateStack", params, REGION)) {
            assertEquals(200, response.getStatus());
            assertTrue(response.getEntity().toString().contains(initial.getStackId()));
        }
        InOrder order = inOrder(service);
        order.verify(service).createChangeSetForCreateStack(eq("context"), eq("initial-create"), any(), any(),
                any(), any(), any(), eq(REGION), eq(ROLE), eq(true), any());
        order.verify(service).recordDelegatedRoleBoundary(initial.getStackId(), REGION, legacyConstructor ? null : BOUNDARY);
        order.verify(service).executeCreateChangeSet(initial.getStackId(), "initial-id", REGION, true, "request-token");
    }

    @Test
    void deleteKeepsExecutionRoleAndClientTokenSeparate() {
        try (Response response = handler.handle("DeleteStack", operationParameters(), REGION)) {
            assertEquals(200, response.getStatus());
        }
        verify(service).deleteStackWithRole(eq("context"), any(), eq(REGION), eq(ROLE), eq("request-token"));
    }

    @Test
    void continueRollbackKeepsExecutionRoleAndClientTokenSeparate() {
        try (Response response = handler.handle("ContinueUpdateRollback", operationParameters(), REGION)) {
            assertEquals(200, response.getStatus());
        }
        verify(service).continueUpdateRollback(eq("context"), any(), eq(false), eq(REGION), eq(ROLE), eq("request-token"));
    }

    private static MultivaluedMap<String, String> operationParameters() {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("StackName", "context");
        params.putSingle("RoleARN", ROLE);
        params.putSingle("ClientRequestToken", "request-token");
        return params;
    }
}
