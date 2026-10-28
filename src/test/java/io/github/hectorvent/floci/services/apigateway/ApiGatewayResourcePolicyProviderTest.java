package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider.ResourcePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiGatewayResourcePolicyProviderTest {

    private final ApiGatewayService service = mock(ApiGatewayService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApiGatewayResourcePolicyProvider provider = new ApiGatewayResourcePolicyProvider(service, mapper);

    @Test
    void expandsOnlyResourcesWithoutChangingStoredPolicy() throws Exception {
        String arn = "arn:aws-cn:execute-api:cn-north-1:111122223333:api/prod/GET/items";
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":"*","Action":"execute-api:Invoke",
                  "Resource":["execute-api:/prod/GET/items","*"],
                  "Condition":{"StringEquals":{"literal":"execute-api:/unchanged"}}},
                  {"Effect":"Deny","NotResource":"execute-api:/prod/GET/items"}]}
                """;
        RestApi api = new RestApi();
        api.setPolicy(policy);
        when(service.getRestApi("cn-north-1", "api")).thenReturn(api);
        List<ResourcePolicy> policies = provider.getResourcePolicies("execute-api", arn);
        assertEquals("111122223333", policies.getFirst().ownerAccountId());
        assertEquals(arn, mapper.readTree(policies.getFirst().policyDocument()).at("/Statement/0/Resource/0").asText());
        assertEquals("*", mapper.readTree(policies.getFirst().policyDocument()).at("/Statement/0/Resource/1").asText());
        assertEquals(arn, mapper.readTree(policies.getFirst().policyDocument()).at("/Statement/1/NotResource").asText());
        assertEquals("execute-api:/unchanged", mapper.readTree(policies.getFirst().policyDocument())
                .at("/Statement/0/Condition/StringEquals/literal").asText());
        assertEquals(policy, api.getPolicy());
    }

    @Test
    void returnsOwnerWithoutPolicyAndDoesNotSwallowStorageErrors() {
        when(service.getRestApi("us-east-1", "api")).thenReturn(new RestApi());
        String arn = "arn:aws:execute-api:us-east-1:111122223333:api/prod/GET/items";
        assertEquals(List.of(new ResourcePolicy(null, "111122223333")), provider.getResourcePolicies("execute-api", arn));
        when(service.getRestApi("us-east-1", "api")).thenThrow(new AwsException("InternalFailure", "failure", 500));
        assertThrows(AwsException.class, () -> provider.getResourcePolicies("execute-api", arn));
    }

    @Test
    void otherServicesAndMissingRestApisDoNotSupplyPolicies() {
        assertTrue(provider.getResourcePolicies("lambda", "arn:aws:lambda:us-east-1:111122223333:function:f").isEmpty());
        verifyNoInteractions(service);
        when(service.getRestApi("us-east-1", "api")).thenThrow(new AwsException("NotFoundException", "missing", 404));
        assertTrue(provider.getResourcePolicies("execute-api", "arn:aws:execute-api:us-east-1:111122223333:api/prod/GET/").isEmpty());
    }

    @Test
    void malformedDocumentRemainsForSharedEvaluator() {
        RestApi api = new RestApi();
        api.setPolicy("not JSON");
        when(service.getRestApi("us-east-1", "api")).thenReturn(api);
        assertEquals("not JSON", provider.getResourcePolicies("execute-api",
                "arn:aws:execute-api:us-east-1:111122223333:api/prod/GET/").getFirst().policyDocument());
    }
}
