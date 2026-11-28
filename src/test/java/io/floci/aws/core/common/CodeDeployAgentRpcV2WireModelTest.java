package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeDeployAgentRpcV2WireModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @ParameterizedTest
    @ValueSource(strings = {"CodeDeployCommandService", "CodeDeployCommandService_v20141006"})
    void agentStringsRemainStringsAndCompletionHasNoOutput(String service) throws Exception {
        RpcV2CborCodec codec = RpcV2CborCodec.load();
        assertTrue(codec.supports(service));
        assertTrue(codec.supports("CodeDeploy_20141006"));
        assertTrue(codec.hasOutput(service, "PollHostCommand"));
        assertFalse(codec.hasOutput(service, "PutHostCommandComplete"));

        JsonNode request = JSON.readTree("""
                {"HostCommandIdentifier":"cmd-1","EstimatedCompletionTime":"2026-02-19T12:00:00Z",
                 "Diagnostics":{"Format":"JSON","Payload":"{\\"error_code\\":0}"}}
                """);
        assertEquals(request, codec.decodeRequest(service, "PostHostCommandUpdate", request.deepCopy()));
        JsonNode response = JSON.readTree("""
                {"HostCommand":{"HostCommandIdentifier":"cmd-1","Nonce":42},
                 "DeploymentSpecification":{"GenericEnvelope":{"Format":"PKCS7/JSON","Payload":"AQID"}}}
                """);
        JsonNode encoded = CBOR.readTree(codec.encodeResponse(service, "GetDeploymentSpecification", response));
        assertEquals(response, encoded);
        assertTrue(encoded.at("/DeploymentSpecification/GenericEnvelope/Payload").isTextual());
        assertTrue(encoded.at("/HostCommand/Nonce").isIntegralNumber());
    }
}
