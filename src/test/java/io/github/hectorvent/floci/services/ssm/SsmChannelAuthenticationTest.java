package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CallerCallAuthorizer;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SsmChannelAuthenticationTest {
    private static final URI URI_VALUE = URI.create(
            "https://ssmmessages.us-east-1.amazonaws.com/v1/control-channel/i-0123456789abcdef0");
    private static final byte[] BODY = """
            {"MessageSchemaVersion":"1.0","RequestId":"00000000-0000-0000-0000-000000000001"}
            """.strip().getBytes(StandardCharsets.UTF_8);
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private SsmChannelAuthentication authentication;

    @BeforeEach
    void signedAgentRequest() throws Exception {
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getDefaultAccountId()).thenReturn("000000000000");
        when(regions.buildGlobalArn("iam", "000000000000", "root")).thenReturn("arn:aws:iam::000000000000:root");
        CallerCallAuthorizer authorizer = mock(CallerCallAuthorizer.class);
        when(authorizer.authorizeUnscopedRequest(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean())).thenReturn(Decision.ALLOW);
        authentication = new SsmChannelAuthentication(mock(IamService.class), regions, authorizer,
                Clock.systemUTC(), mock(Ec2Service.class));
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", Integer.toString(BODY.length));
        headers.putAll(AwsRequestSigner.signedAs("test", "test", "ssmmessages")
                .headersFor("POST", URI_VALUE, Map.of("content-type", "application/json",
                        "content-length", Integer.toString(BODY.length)), BODY));
    }

    @Test
    void http2AuthoritySuppliesTheSignedHostWithoutAHostHeader() {
        assertFalse(headers.containsKey("Host"));
        assertEquals("us-east-1", authenticate(URI_VALUE, BODY).region());
    }

    @Test
    void http1HostIsStillVerified() {
        headers.put("Host", URI_VALUE.getAuthority());
        assertEquals("000000000000", authenticate(URI_VALUE, BODY).account());
        headers.put("Host", "other.example");
        assertThrows(AwsException.class, () -> authenticate(URI_VALUE, BODY));
    }

    @Test
    void changedAuthorityOrBodyDoesNotVerify() {
        assertThrows(AwsException.class, () -> authenticate(
                URI.create(URI_VALUE.toString().replace("ssmmessages.", "tampered.")), BODY));
        assertThrows(AwsException.class, () -> authenticate(URI_VALUE, "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void presignedGatewayRequestsUseTheBodyHashAndBindTheQuery() throws Exception {
        URI signed = AwsRequestSigner.signedAs("test", "test", "ssmmessages")
                .presignedUri("GET", URI_VALUE, Map.of("role", "subscribe", "stream", "input"), new byte[0]);
        assertEquals("us-east-1", authenticatePresigned(signed, new byte[0]).region());
        assertThrows(AwsException.class, () -> authenticatePresigned(
                URI.create(signed.toString().replace("stream=input", "stream=other")), new byte[0]));
        assertThrows(AwsException.class, () -> authenticatePresigned(signed, BODY));
    }

    private SsmChannelAuthentication.Caller authenticatePresigned(URI uri, byte[] body) {
        Map<String, String> query = new TreeMap<>();
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return authentication.authenticate("GET", uri, name -> null, query::get, body,
                "OpenControlChannel", "192.0.2.1", true);
    }

    private SsmChannelAuthentication.Caller authenticate(URI uri, byte[] body) {
        return authentication.authenticate("POST", uri, headers::get, name -> null, body,
                "CreateControlChannel", "192.0.2.1", true);
    }
}
