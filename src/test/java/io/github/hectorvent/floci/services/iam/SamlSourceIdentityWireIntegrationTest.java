package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithSamlResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SamlSourceIdentityWireIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String ISSUER = "https://idp.example.test/saml";
    private static final String SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String SOURCE_ATTRIBUTE = "https://aws.amazon.com/SAML/Attributes/SourceIdentity";
    private static final String SOURCE = "Person_A";

    @TestHTTPResource("/")
    URI endpoint;
    @InjectSpy
    IamService iam;
    @Inject
    SAMLProviderService providers;

    @BeforeAll
    static void signingCertificate() throws Exception {
        AssumeRoleWithSamlValidationIntegrationTest.createSigningCertificate();
    }

    @ParameterizedTest
    @ValueSource(strings = {"present", "absent", "chaining"})
    void verifiedSamlSessionEnforcesDownstreamSourceIdentity(String scenario) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Credentials session = fixture.assume(scenario).credentials();
            // Exercise downstream authorization before checking response metadata so the control reaches it.
            if ("chaining".equals(scenario)) {
                fixture.assertChain(session);
            } else {
                try (IamClient caller = fixture.caller(session)) {
                    if ("absent".equals(scenario)) {
                        assertEquals("AccessDenied", assertThrows(IamException.class, caller::listRoles)
                                .awsErrorDetails().errorCode());
                    } else {
                        assertDoesNotThrow(() -> caller.listRoles());
                        fixture.sourcePolicy("Other");
                        assertEquals("AccessDenied", assertThrows(IamException.class, caller::listRoles)
                                .awsErrorDetails().errorCode());
                    }
                }
            }
            assertEquals("absent".equals(scenario) ? null : SOURCE,
                    iam.findSourceIdentity(session.accessKeyId(), session.sessionToken()));
            assertNull(iam.findSourceIdentity(session.accessKeyId(), "wrong-token"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-value", "two-values", "two-attributes"})
    void signedInvalidSourceCardinalityDoesNotIssueCredentials(String scenario) throws Exception {
        try (Fixture fixture = new Fixture()) {
            clearInvocations(iam);
            StsException denied = assertThrows(StsException.class, () -> fixture.assume(scenario));
            assertEquals("InvalidIdentityToken", denied.awsErrorDetails().errorCode());
            verify(iam, never()).registerSession(anyString(), anyString(), anyString(), anyString(),
                    any(Instant.class), nullable(String.class), anyString(), anyString(), anyString(),
                    anyMap(), anyMap(), nullable(String.class));
        }
    }

    private final class Fixture implements AutoCloseable {
        private final SignedIamUsers.User user;
        private final IamClient admin;
        private final StsClient anonymous;
        private final String name = "saml-source-wire-" + UUID.randomUUID();
        private final String childName = name + "-chain";
        private final List<Credentials> sessions = new ArrayList<>();
        private String providerArn;
        private Role role;

        private Fixture() throws Exception {
            user = SignedIamUsers.createUser(endpoint, "saml-source-admin-", "admin",
                    "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}");
            admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            user.accessKeyId(), user.secretAccessKey()))).build();
            anonymous = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(AnonymousCredentialsProvider.create()).build();
            try {
                String metadata = "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\""
                        + ISSUER + "\"><md:IDPSSODescriptor><md:KeyDescriptor use=\"signing\">"
                        + "<ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:X509Data>"
                        + "<ds:X509Certificate>" + AssumeRoleWithSamlValidationIntegrationTest.certificateBase64
                        + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>"
                        + "</md:IDPSSODescriptor></md:EntityDescriptor>";
                providerArn = admin.createSAMLProvider(b -> b.name(name).samlMetadataDocument(metadata))
                        .samlProviderArn();
                String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\""
                        + providerArn + "\"},\"Action\":[\"sts:AssumeRoleWithSAML\",\"sts:SetSourceIdentity\"]}}";
                role = admin.createRole(b -> b.roleName(name).path("/teams/saml/")
                        .assumeRolePolicyDocument(trust)).role();
                sourcePolicy(SOURCE);
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (Exception | Error cleanup) {
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }

        private void sourcePolicy(String source) {
            admin.putRolePolicy(b -> b.roleName(name).policyName("source").policyDocument(
                    "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"iam:ListRoles\",\"Resource\":\"*\","
                            + "\"Condition\":{\"StringEquals\":{\"aws:SourceIdentity\":\"" + source + "\"}}}}"));
        }

        private AssumeRoleWithSamlResponse assume(String scenario) {
            String assertion = AssumeRoleWithSamlValidationIntegrationTest.assertion(role.arn(), providerArn,
                    Instant.now().plusSeconds(1800), "urn:amazon:webservices", ISSUER, true, false,
                    factory -> {
                        try {
                            return List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null));
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    }, document -> addSource(document, scenario));
            AssumeRoleWithSamlResponse response = anonymous.assumeRoleWithSAML(b -> b.roleArn(role.arn())
                    .principalArn(providerArn).samlAssertion(assertion).durationSeconds(900));
            sessions.add(response.credentials());
            return response;
        }

        private IamClient caller(Credentials credentials) {
            return IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(credentials(credentials)).build();
        }

        private void assertChain(Credentials credentials) {
            String trust = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + role.arn()
                    + "\"},\"Action\":[\"sts:AssumeRole\",\"sts:SetSourceIdentity\"],"
                    + "\"Condition\":{\"StringEquals\":{\"sts:SourceIdentity\":\"" + SOURCE + "\"}}}}";
            Role child = admin.createRole(b -> b.roleName(childName).path("/teams/saml/")
                    .assumeRolePolicyDocument(trust)).role();
            admin.putRolePolicy(b -> b.roleName(name).policyName("chain").policyDocument(
                    "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":[\"sts:AssumeRole\",\"sts:SetSourceIdentity\"],"
                            + "\"Resource\":\"" + child.arn() + "\"}}"));
            admin.putRolePolicy(b -> b.roleName(childName).policyName("source").policyDocument(
                    "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"iam:ListRoles\",\"Resource\":\"*\","
                            + "\"Condition\":{\"StringEquals\":{\"aws:SourceIdentity\":\"" + SOURCE + "\"}}}}"));
            try (StsClient caller = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                    .credentialsProvider(credentials(credentials)).build()) {
                AssumeRoleResponse chained = caller.assumeRole(b -> b.roleArn(child.arn()).roleSessionName("chain"));
                sessions.add(chained.credentials());
                assertEquals(SOURCE, chained.sourceIdentity());
                try (IamClient downstream = caller(chained.credentials())) {
                    assertDoesNotThrow(() -> downstream.listRoles());
                }
                clearInvocations(iam);
                StsException changed = assertThrows(StsException.class, () -> sessions.add(caller.assumeRole(b ->
                        b.roleArn(child.arn()).roleSessionName("changed").sourceIdentity("Other")).credentials()));
                assertEquals("AccessDenied", changed.awsErrorDetails().errorCode());
                verify(iam, never()).registerSession(anyString(), anyString(), anyString(), anyString(),
                    any(Instant.class), nullable(String.class), anyString(), anyString(), anyString(),
                    anyMap(), anyMap(), nullable(String.class));
            }
        }

        private void deleteRole(String roleName) throws Exception {
            if (iam.findRole(ACCOUNT, roleName).isEmpty()) {
                return;
            }
            try (AutoCloseable roleCleanup = () -> {
                admin.deleteRole(b -> b.roleName(roleName));
                assertTrue(iam.findRole(ACCOUNT, roleName).isEmpty());
            }; AutoCloseable sourceCleanup = () -> deletePolicy(roleName, "source");
                 AutoCloseable chainCleanup = () -> deletePolicy(roleName, "chain")) {
                // Reverse cleanup attempts every policy and role while preserving the first failure.
            }
        }

        private void deletePolicy(String roleName, String policyName) {
            try {
                admin.deleteRolePolicy(b -> b.roleName(roleName).policyName(policyName));
            } catch (NoSuchEntityException ignored) {
                // Setup may fail before creating this policy on the owned role.
            }
        }

        @Override
        public void close() throws Exception {
            try (AutoCloseable userCleanup = user::close;
                 AutoCloseable adminCleanup = admin::close;
                 AutoCloseable stsCleanup = anonymous::close;
                 AutoCloseable providerCleanup = this::deleteProvider;
                 AutoCloseable roleCleanup = () -> deleteRole(name);
                 AutoCloseable childCleanup = () -> deleteRole(childName)) {
                for (Credentials session : sessions.reversed()) {
                    iam.unregisterSession(ACCOUNT, session.accessKeyId());
                    assertTrue(iam.resolveCallerArns(session.accessKeyId()).isEmpty());
                }
            }
        }

        private void deleteProvider() {
            String ownedProvider = "arn:aws:iam::" + ACCOUNT + ":saml-provider/" + name;
            if (providers.find(ownedProvider).isPresent()) {
                admin.deleteSAMLProvider(b -> b.samlProviderArn(ownedProvider));
            }
            assertTrue(providers.find(ownedProvider).isEmpty());
        }
    }

    private static StaticCredentialsProvider credentials(Credentials credentials) {
        return StaticCredentialsProvider.create(AwsSessionCredentials.create(credentials.accessKeyId(),
                credentials.secretAccessKey(), credentials.sessionToken()));
    }

    private static void addSource(Document document, String scenario) {
        if ("absent".equals(scenario)) {
            return;
        }
        Element attribute = document.createElementNS(SAML, "saml:Attribute");
        attribute.setAttribute("Name", SOURCE_ATTRIBUTE);
        if (!"no-value".equals(scenario)) {
            Element value = document.createElementNS(SAML, "saml:AttributeValue");
            value.setTextContent(SOURCE);
            attribute.appendChild(value);
            if ("two-values".equals(scenario)) {
                attribute.appendChild(value.cloneNode(true));
            }
        }
        Element statement = (Element) document.getElementsByTagNameNS(SAML, "AttributeStatement").item(0);
        statement.appendChild(attribute);
        if ("two-attributes".equals(scenario)) {
            statement.appendChild(attribute.cloneNode(true));
        }
    }
}
