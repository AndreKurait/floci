package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.CreateApiResponse;
import software.amazon.awssdk.services.apigatewayv2.model.CreateRouteResponse;
import software.amazon.awssdk.services.apigatewayv2.model.NotFoundException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesResponse;
import software.amazon.awssdk.services.cloudformation.model.StackResource;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class ApiGatewayV2CfnChildDeletionCleanupTest {
    private final ApiGatewayV2Client api = mock(ApiGatewayV2Client.class);
    private final CloudFormationClient cfn = mock(CloudFormationClient.class);
    private final ApiGatewayV2CfnChildDeletionIntegrationTest fixture = spy(new ApiGatewayV2CfnChildDeletionIntegrationTest());
    private final IllegalStateException primary = new IllegalStateException("response failed after allocation");

    ApiGatewayV2CfnChildDeletionCleanupTest() {
        when(api.createApi(any(Consumer.class))).thenReturn(CreateApiResponse.builder().apiId("owned-api").build());
        when(api.createRoute(any(Consumer.class))).thenReturn(CreateRouteResponse.builder().routeId("independent").build());
        when(api.getApi(any(Consumer.class))).thenThrow(NotFoundException.builder().message("gone").build());
    }

    @Test
    void createResponseFailureStillDeletesStackAndApi() {
        when(cfn.createStack(any(Consumer.class))).thenThrow(primary);
        try (MockedStatic<CfnStackWaits> waits = mockStatic(CfnStackWaits.class, withSettings().mockMaker(MockMakers.INLINE))) {
            assertSame(primary, assertThrows(IllegalStateException.class, () -> fixture.exercise(false, false, api, cfn, "owned")));
            verify(cfn).deleteStack(any(Consumer.class));
            verify(api).deleteApi(any(Consumer.class));
            waits.verify(() -> CfnStackWaits.awaitStackDeleted("owned"));
        }
    }

    @Test
    void stackCleanupFailureIsSuppressedAndApiCleanupStillRuns() {
        IllegalArgumentException cleanup = new IllegalArgumentException("stack cleanup failed");
        when(cfn.createStack(any(Consumer.class))).thenThrow(primary);
        when(cfn.deleteStack(any(Consumer.class))).thenThrow(cleanup);
        try (MockedStatic<CfnStackWaits> waits = mockStatic(CfnStackWaits.class, withSettings().mockMaker(MockMakers.INLINE))) {
            assertSame(primary, assertThrows(IllegalStateException.class, () -> fixture.exercise(false, false, api, cfn, "owned")));
            assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
            verify(api).deleteApi(any(Consumer.class));
        }
    }

    @Test
    void firstChildFailureStillDeletesApiWithoutDeletingUnattemptedStack() {
        when(api.createRoute(any(Consumer.class))).thenThrow(primary);
        assertSame(primary, assertThrows(IllegalStateException.class, () -> fixture.exercise(false, false, api, cfn, "owned")));
        verify(api).deleteApi(any(Consumer.class));
        verifyNoInteractions(cfn);
    }

    @Test
    void restoreFailurePreservesBodyFailureAndStillAttemptsBothDeletions() {
        IllegalArgumentException restore = new IllegalArgumentException("spy restore failed");
        when(cfn.describeStackResources(any(Consumer.class))).thenReturn(DescribeStackResourcesResponse.builder()
                .stackResources(StackResource.builder().logicalResourceId("Authorizer").physicalResourceId("owned-authorizer").build())
                .build());
        when(api.getRoutes(any(Consumer.class))).thenThrow(primary);
        doThrow(restore).when(fixture).restoreAuthorizer("owned-api", "owned-authorizer");
        try (MockedStatic<CfnStackWaits> waits = mockStatic(CfnStackWaits.class, withSettings().mockMaker(MockMakers.INLINE))) {
            assertSame(primary, assertThrows(IllegalStateException.class, () -> fixture.exercise(false, false, api, cfn, "owned")));
            assertArrayEquals(new Throwable[]{restore}, primary.getSuppressed());
            verify(cfn).deleteStack(any(Consumer.class));
            verify(api).deleteApi(any(Consumer.class));
        }
    }

    @Test
    void exactAbsentStackDoesNotMaskCreateFailure() {
        when(cfn.createStack(any(Consumer.class))).thenThrow(primary);
        when(cfn.describeStacks(any(Consumer.class))).thenThrow(error("ValidationError", "Stack with id owned does not exist"));
        assertSame(primary, assertThrows(IllegalStateException.class, () -> fixture.exercise(false, false, api, cfn, "owned")));
        assertEquals(0, primary.getSuppressed().length);
        verify(cfn, never()).deleteStack(any(Consumer.class));
        verify(api).deleteApi(any(Consumer.class));
    }

    @Test
    void unrelatedDescribeFailureIsRetainedWhileApiIsDeleted() {
        CloudFormationException denied = error("AccessDenied", "denied");
        when(cfn.createStack(any(Consumer.class))).thenThrow(primary);
        when(cfn.describeStacks(any(Consumer.class))).thenThrow(denied);
        assertSame(primary, assertThrows(IllegalStateException.class, () -> fixture.exercise(false, false, api, cfn, "owned")));
        assertArrayEquals(new Throwable[]{denied}, primary.getSuppressed());
        verify(api).deleteApi(any(Consumer.class));
    }

    private CloudFormationException error(String code, String message) {
        CloudFormationException.Builder builder = CloudFormationException.builder();
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(message).build());
        return (CloudFormationException) builder.build();
    }
}
