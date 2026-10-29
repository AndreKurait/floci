package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ApiGatewayV2CfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudFormationOwnershipRecoveryTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "eu-west-1";
    private final ApiGatewayV2Service api = mock(ApiGatewayV2Service.class);
    @SuppressWarnings("unchecked")
    private final AccountAwareStorageBackend<Stack> storage = mock(AccountAwareStorageBackend.class);

    @Test
    void recoveryPersistenceFailurePreservesOriginalMetadataAndCanRetry() {
        CloudFormationService service = service();
        Stack stack = stack("\"original-api\"");
        IllegalStateException failure = new IllegalStateException("recovery write failed");
        doThrow(failure).doNothing().when(storage).putForAccount(eq(ACCOUNT), anyString(), any());
        try {
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> service.deleteStackResources(stack, REGION, ACCOUNT)));
            assertEquals("DELETE_FAILED", stack.getStatus());
            assertEquals(failure.getMessage(), stack.getStatusReason());
            assertFalse(stack.getResources().get("Child").getAttributes().containsKey("ApiId"));
            assertEquals("CREATE_COMPLETE", stack.getResources().get("Child").getStatus());
            verifyNoInteractions(api);
            service.deleteStackResources(stack, REGION, ACCOUNT);
            assertEquals("DELETE_COMPLETE", stack.getStatus());
            verify(api).deleteStage(REGION, "original-api", "prod");
        } finally {
            service.stop();
        }
    }

    @Test
    void ownershipIsStoredForTheStackAccountBeforeDeletingAChild() {
        CloudFormationService service = service();
        Stack stack = stack("\"original-api\"");
        doAnswer(call -> {
            Stack persisted = call.getArgument(2);
            assertEquals("original-api", persisted.getResources().get("Child").getAttributes().get("ApiId"));
            verifyNoInteractions(api);
            return null;
        }).when(storage).putForAccount(eq(ACCOUNT), anyString(), any());
        try {
            service.deleteStackResources(stack, REGION, ACCOUNT);
            assertEquals("DELETE_COMPLETE", stack.getStatus());
            InOrder order = inOrder(storage, api);
            order.verify(storage).putForAccount(eq(ACCOUNT), anyString(), same(stack));
            order.verify(api).deleteStage(REGION, "original-api", "prod");
        } finally {
            service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "[]", "{}", "{\"Ref\":\"Unknown\"}",
            "{\"Fn::ImportValue\":\"LiveOwner\"}", "\"{{resolve:ssm:/owner}}\""})
    void unavailableHistoricalOwnerDoesNotDeleteOrPersistAGuess(String value) {
        CloudFormationService service = service();
        Stack stack = stack(value);
        try {
            assertThrows(RuntimeException.class, () -> service.deleteStackResources(stack, REGION, ACCOUNT));
            assertEquals("DELETE_FAILED", stack.getStatus());
            assertFalse(stack.getResources().get("Child").getAttributes().containsKey("ApiId"));
            verifyNoInteractions(api);
        } finally {
            service.stop();
        }
    }

    @Test
    void priorParameterDefaultCanRecoverAnOlderRecordWithoutResolvedParameters() {
        CloudFormationService service = service();
        Stack stack = stack("{\"Ref\":\"Owner\"}");
        stack.setTemplateBody(stack.getTemplateBody().replace("\"Resources\":", "\"Parameters\":{"
                + "\"Owner\":{\"Type\":\"String\",\"Default\":\"original-api\"}},\"Resources\":"));
        try {
            service.deleteStackResources(stack, REGION, ACCOUNT);
            assertEquals("DELETE_COMPLETE", stack.getStatus());
            verify(api).deleteStage(REGION, "original-api", "prod");
        } finally {
            service.stop();
        }
    }

    @Test
    void missingResolvedSsmValueIsNotReplacedByItsCurrentParameterName() {
        CloudFormationService service = service();
        Stack stack = stack("{\"Ref\":\"Owner\"}");
        stack.setTemplateBody(stack.getTemplateBody().replace("\"Resources\":", "\"Parameters\":{"
                + "\"Owner\":{\"Type\":\"AWS::SSM::Parameter::Value<String>\","
                + "\"Default\":\"original-api\"}},\"Resources\":"));
        try {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> service.deleteStackResources(stack, REGION, ACCOUNT));
            assertTrue(failure.getMessage().contains("stored resolved SSM parameters"));
            assertFalse(stack.getResources().get("Child").getAttributes().containsKey("ApiId"));
            verifyNoInteractions(api);
        } finally {
            service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Retain", "RetainExceptOnCreate"})
    void retainedChildrenDoNotRequireOwnershipRecoveryDuringStackDeletion(String policy) {
        CloudFormationService service = service();
        Stack stack = stack("{\"Ref\":\"UnknownOwner\"}");
        stack.getResources().get("Child").setDeletionPolicy(policy);
        try {
            assertDoesNotThrow(() -> service.deleteStackResources(stack, REGION, ACCOUNT));
            assertEquals("DELETE_COMPLETE", stack.getStatus());
            assertEquals("DELETE_SKIPPED", stack.getResources().get("Child").getStatus());
            verifyNoInteractions(api);
        } finally {
            service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void genuineStubNeedsNoOwnerWhenItsServiceBecomesAvailable(boolean formerlyDisabled) {
        CloudFormationService service = service();
        Stack stack = stack("{\"Ref\":\"UnknownOwner\"}");
        StackResource child = stack.getResources().get("Child");
        child.setPhysicalId("Child-1a2b3c4d");
        child.getAttributes().put("Arn", "arn:aws:stub:::Child");
        if (formerlyDisabled) {
            child.getAttributes().put(CloudFormationTemplateEngine.SERVICE_DISABLED_ATTR, "true");
        }
        try {
            assertDoesNotThrow(() -> service.deleteStackResources(stack, REGION, ACCOUNT));
            assertEquals("DELETE_COMPLETE", stack.getStatus());
            assertFalse(child.getAttributes().containsKey("ApiId"));
            verifyNoInteractions(api);
        } finally {
            service.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "Child-1a2b3c4d"})
    void realChildWithAStaleStubMarkerStillRecoversItsOwner(String physicalId) {
        CloudFormationService service = service();
        Stack stack = stack("\"original-api\"");
        StackResource child = stack.getResources().get("Child");
        child.setPhysicalId(physicalId);
        child.getAttributes().put("Arn", "arn:aws:stub:::Child");
        if (physicalId.startsWith("Child-")) {
            child.getAttributes().put("StageName", physicalId);
        }
        try {
            service.deleteStackResources(stack, REGION, ACCOUNT);
            assertEquals("DELETE_COMPLETE", stack.getStatus());
            verify(api).deleteStage(REGION, "original-api", physicalId);
        } finally {
            service.stop();
        }
    }

    private CloudFormationService service() {
        ObjectMapper mapper = new ObjectMapper();
        S3Service s3 = mock(S3Service.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<Stack>create(eq("cloudformation"), eq("cloudformation-stacks.json"), any())).thenReturn(storage);
        CfnResourceDispatcher dispatcher = new CfnResourceDispatcher(mapper, new CloudFormationResourceRegistry(
                List.of(new ApiGatewayV2CfnProvisioner(api, s3, mapper))), mock(CfnDynamicReferences.class), config);
        return new CloudFormationService(dispatcher, s3, mock(SsmService.class),
                mock(GetStackOutputRoleAuthorizer.class), mock(CfnDynamicReferences.class), mapper,
                config, mock(RegionResolver.class), Clock.systemUTC(), factory);
    }

    private Stack stack(String apiId) {
        Stack stack = new Stack();
        stack.setStackId("legacy-stack-id");
        stack.setStackName("legacy-stack");
        stack.setRegion(REGION);
        stack.setAccountId(ACCOUNT);
        stack.setStatus("DELETE_IN_PROGRESS");
        stack.setTemplateBody("{\"Resources\":{\"Child\":{\"Type\":\"AWS::ApiGatewayV2::Stage\","
                + "\"Properties\":{\"StageName\":\"prod\",\"ApiId\":" + apiId + "}}}}");
        StackResource child = new StackResource();
        child.setLogicalId("Child");
        child.setResourceType("AWS::ApiGatewayV2::Stage");
        child.setPhysicalId("prod");
        child.setStatus("CREATE_COMPLETE");
        stack.getResources().put("Child", child);
        return stack;
    }
}
