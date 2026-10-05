package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Direct Query handler tests with mocked services, without a Quarkus application or HTTP server. */
class IamStsQueryTimestampTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE_NAME = "TimestampRole";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/TimestampRole";
    private static final String CERTIFICATE_NAME = "TimestampCertificate";

    static Stream<Arguments> timestampCases() {
        return Stream.of(
                Arguments.of("2026-10-05T05:47:29.777826302Z", "2026-10-05T05:47:29.777Z"),
                Arguments.of("2026-10-05T05:47:29.785090420Z", "2026-10-05T05:47:29.785Z"),
                Arguments.of("2026-10-05T06:02:29.788548039Z", "2026-10-05T06:02:29.788Z"),
                Arguments.of("2026-10-05T05:47:29Z", "2026-10-05T05:47:29Z"),
                Arguments.of("2026-10-05T05:47:29.000000001Z", "2026-10-05T05:47:29Z"),
                Arguments.of("2026-10-05T05:47:29.000999999Z", "2026-10-05T05:47:29Z"),
                Arguments.of("2026-10-05T05:47:29.001000000Z", "2026-10-05T05:47:29.001Z"),
                Arguments.of("2026-10-05T05:47:29.010000000Z", "2026-10-05T05:47:29.010Z"),
                Arguments.of("2026-12-31T23:59:59.999999999Z", "2026-12-31T23:59:59.999Z"),
                Arguments.of("1969-12-31T23:59:59.999999999Z", "1969-12-31T23:59:59.999Z"),
                Arguments.of("2026-10-05T11:17:29.777826302+05:30", "2026-10-05T05:47:29.777Z"),
                Arguments.of("2026-10-04T22:47:29.777826302-07:00", "2026-10-05T05:47:29.777Z"));
    }

    @ParameterizedTest
    @MethodSource("timestampCases")
    void certificateResponsesUseMillisecondsWithoutChangingStoredInstants(String input, String expected)
            throws Exception {
        Instant timestamp = OffsetDateTime.parse(input).toInstant();
        ServerCertificate certificate = new ServerCertificate();
        certificate.setServerCertificateName(CERTIFICATE_NAME);
        certificate.setServerCertificateId("certificate-id");
        certificate.setArn("arn:aws:iam::000000000000:server-certificate/TimestampCertificate");
        certificate.setCertificateBody("fabricated-certificate");
        certificate.setUploadDate(timestamp);
        certificate.setExpiration(timestamp);

        IamService service = mock(IamService.class);
        when(service.uploadServerCertificate(CERTIFICATE_NAME, "/", "fabricated-certificate",
                "fabricated-key", null, Map.of())).thenReturn(certificate);
        when(service.getServerCertificate(CERTIFICATE_NAME)).thenReturn(certificate);
        when(service.listServerCertificates(null)).thenReturn(List.of(certificate));
        IamQueryHandler handler = new IamQueryHandler(service, null, null, null, null, null, null);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ServerCertificateName", CERTIFICATE_NAME);
        params.putSingle("Path", "/");
        params.putSingle("CertificateBody", "fabricated-certificate");
        params.putSingle("PrivateKey", "fabricated-key");

        for (String action : List.of("UploadServerCertificate", "GetServerCertificate", "ListServerCertificates")) {
            try (Response response = handler.handle(action, params, null)) {
                assertXmlMember(response, "UploadDate", expected);
                assertXmlMember(response, "Expiration", expected);
            }
            assertSame(timestamp, certificate.getUploadDate());
            assertSame(timestamp, certificate.getExpiration());
        }
    }

    @ParameterizedTest
    @MethodSource("timestampCases")
    void roleResponsesUseMillisecondsWithoutChangingStoredInstant(String input, String expected) throws Exception {
        Instant timestamp = OffsetDateTime.parse(input).toInstant();
        IamRole role = role(timestamp);
        IamService service = mock(IamService.class);
        when(service.createRole(ROLE_NAME, "/", "{}", null, 3600, Map.of(), null)).thenReturn(role);
        when(service.getRole(ROLE_NAME)).thenReturn(role);
        IamQueryHandler handler = new IamQueryHandler(service, null, null, null, null, null, null);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleName", ROLE_NAME);
        params.putSingle("Path", "/");
        params.putSingle("AssumeRolePolicyDocument", "{}");

        for (String action : List.of("CreateRole", "GetRole")) {
            try (Response response = handler.handle(action, params, null)) {
                assertXmlMember(response, "CreateDate", expected);
            }
            assertSame(timestamp, role.getCreateDate());
        }
    }

    @ParameterizedTest
    @MethodSource("timestampCases")
    void assumeRoleUsesMillisecondsWithoutChangingSessionExpiration(String input, String expected) throws Exception {
        Instant expiration = OffsetDateTime.parse(input).toInstant();
        Instant now = expiration.minusSeconds(900);
        IamRole role = role(now);
        IamService service = mock(IamService.class);
        when(service.findRole(ACCOUNT_ID, ROLE_NAME)).thenReturn(Optional.of(role));
        when(service.resolveCallerArns(null)).thenReturn(Optional.empty());

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.IamServiceConfig iam = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.iam()).thenReturn(iam);
        when(iam.enforcementEnabled()).thenReturn(false);
        StsQueryHandler handler = new StsQueryHandler(service, mock(AccountResolver.class),
                new RegionResolver("us-east-1", ACCOUNT_ID), config, null, null, null, null, null, null);

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("RoleArn", ROLE_ARN);
        params.putSingle("RoleSessionName", "timestamp-session");
        params.putSingle("DurationSeconds", "900");

        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(now);
            try (Response response = handler.handle("AssumeRole", params)) {
                assertXmlMember(response, "Expiration", expected);
                assertXmlMember(response, "PackedPolicySize", "0");
            }
        }

        ArgumentCaptor<Instant> storedExpiration = ArgumentCaptor.forClass(Instant.class);
        verify(service).registerSession(anyString(), anyString(), anyString(), eq(ROLE_ARN),
                storedExpiration.capture(), isNull(), eq(ACCOUNT_ID), eq("timestamp-session"), anyString());
        assertEquals(expiration, storedExpiration.getValue());
    }

    private static IamRole role(Instant createDate) {
        IamRole role = new IamRole();
        role.setRoleName(ROLE_NAME);
        role.setRoleId("role-id");
        role.setPath("/");
        role.setArn(ROLE_ARN);
        role.setAssumeRolePolicyDocument("{}");
        role.setCreateDate(createDate);
        return role;
    }

    private static void assertXmlMember(Response response, String member, String expected) throws Exception {
        assertEquals(200, response.getStatus());
        Document document = XmlParser.parseDocument((String) response.getEntity());
        NodeList matches = document.getElementsByTagNameNS("*", member);
        assertEquals(1, matches.getLength(), member);
        assertEquals(expected, matches.item(0).getTextContent(), member);
    }
}
