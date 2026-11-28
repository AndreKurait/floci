package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acmpca.AcmPcaClient;
import software.amazon.awssdk.services.acmpca.model.CertificateAuthority;
import software.amazon.awssdk.services.acmpca.model.Tag;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code AWS::ACMPCA::CertificateAuthority} in a stack: {@code Ref} and {@code Arn} are the authority's ARN and
 * {@code CertificateSigningRequest} its CSR; it waits for its certificate; tags update in place, a create-only
 * change replaces it, and deleting the stack deletes it.
 */
@QuarkusTest
@Timeout(120)
class CloudFormationAcmPcaIntegrationTest {

    private static final String TEMPLATE = """
            {"Resources": {"Authority": {"Type": "AWS::ACMPCA::CertificateAuthority", "Properties": {
               "Type": "SUBORDINATE", "KeyAlgorithm": "RSA_2048", "SigningAlgorithm": "SHA256WITHRSA",
               "Subject": {"Country": "US", "Organization": "Example Org", "CommonName": "%s"},
               "KeyStorageSecurityStandard": "FIPS_140_2_LEVEL_3_OR_HIGHER", "UsageMode": "GENERAL_PURPOSE",
               "Tags": [%s]}}},
             "Outputs": {
               "Ref": {"Value": {"Ref": "Authority"}},
               "Arn": {"Value": {"Fn::GetAtt": ["Authority", "Arn"]}},
               "Csr": {"Value": {"Fn::GetAtt": ["Authority", "CertificateSigningRequest"]}}}}
            """;

    @Test
    void aSubordinateAuthorityGetsItsAttributesUpdatesItsTagsAndGoesWithItsStack() {
        String account = Long.toString(840_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
             AcmPcaClient pca = AwsTestClients.create(AcmPcaClient.builder(), account, Region.US_EAST_1)) {
            String stack = "pca-" + UUID.randomUUID().toString().substring(0, 8);
            String stackId = cfn.createStack(request -> request.stackName(stack).templateBody(template(
                    "Example Issuing CA", "{\"Key\": \"team\", \"Value\": \"pki\"}"))).stackId();
            Map<String, String> outputs = outputs(CfnStackWaits.awaitStatus(cfn, stack, "CREATE_COMPLETE"));
            String arn = outputs.get("Ref");
            assertTrue(arn.matches("arn:aws:acm-pca:us-east-1:" + account + ":certificate-authority/"
                    + "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"), arn);
            assertEquals(arn, outputs.get("Arn"));
            assertEquals(pca.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(arn)).csr(),
                    outputs.get("Csr"));
            assertTrue(outputs.get("Csr").startsWith("-----BEGIN CERTIFICATE REQUEST-----"));
            CertificateAuthority created = describe(pca, arn);
            assertEquals("SUBORDINATE", created.typeAsString());
            assertEquals("PENDING_CERTIFICATE", created.statusAsString());
            assertEquals("FIPS_140_2_LEVEL_3_OR_HIGHER", created.keyStorageSecurityStandardAsString());
            assertEquals("US", created.certificateAuthorityConfiguration().subject().country());
            assertEquals("Example Org", created.certificateAuthorityConfiguration().subject().organization());
            assertEquals(Map.of("team", "pki"), tags(pca, arn));

            cfn.updateStack(request -> request.stackName(stack).templateBody(template("Example Issuing CA",
                    "{\"Key\": \"team\", \"Value\": \"platform\"}, {\"Key\": \"tier\", \"Value\": \"issuing\"}")));
            assertEquals(arn, outputs(CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_COMPLETE")).get("Ref"));
            assertEquals(Map.of("team", "platform", "tier", "issuing"), tags(pca, arn));

            cfn.updateStack(request -> request.stackName(stack).templateBody(template("Renamed Issuing CA",
                    "{\"Key\": \"tier\", \"Value\": \"issuing\"}")));
            String replacement = outputs(CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_COMPLETE")).get("Ref");
            assertNotEquals(arn, replacement);
            assertEquals("Renamed Issuing CA",
                    describe(pca, replacement).certificateAuthorityConfiguration().subject().commonName());
            assertEquals(Map.of("tier", "issuing"), tags(pca, replacement));
            assertEquals("DELETED", describe(pca, arn).statusAsString());

            cfn.deleteStack(request -> request.stackName(stack));
            CfnStackWaits.awaitStatus(cfn, stackId, "DELETE_COMPLETE");
            assertEquals("DELETED", describe(pca, replacement).statusAsString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CrlConfiguration", "OcspConfiguration"})
    void pendingAuthorityRejectsUnsupportedRevocationBeforeChangingTags(String mechanism) {
        String account = Long.toString(840_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
             AcmPcaClient pca = AwsTestClients.create(AcmPcaClient.builder(), account, Region.US_EAST_1)) {
            String stack = "pca-revocation-" + UUID.randomUUID().toString().substring(0, 8);
            String stackId = cfn.createStack(request -> request.stackName(stack).templateBody(template(
                    "Pending CA", "{\"Key\": \"team\", \"Value\": \"original\"}"))).stackId();
            try {
                Map<String, String> before = outputs(CfnStackWaits.awaitStatus(cfn, stack, "CREATE_COMPLETE"));
                String arn = before.get("Arn");
                cfn.updateStack(request -> request.stackName(stack).templateBody(revocationTemplate(mechanism, true)));
                Stack rejected = CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals(before, outputs(rejected));
                assertEquals(Map.of("team", "original"), tags(pca, arn));
                assertEquals("PENDING_CERTIFICATE", describe(pca, arn).statusAsString());
                assertTrue(cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents().stream()
                        .anyMatch(event -> "Authority".equals(event.logicalResourceId())
                                && event.resourceStatusReason() != null
                                && event.resourceStatusReason().contains("revocation delivery is not supported")));

                cfn.updateStack(request -> request.stackName(stack).templateBody(revocationTemplate(mechanism, false)));
                assertEquals(before, outputs(CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_COMPLETE")));
                assertEquals(Map.of("team", "updated"), tags(pca, arn));
                assertEquals("PENDING_CERTIFICATE", describe(pca, arn).statusAsString());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStatus(cfn, stackId, "DELETE_COMPLETE");
            }
        }
    }

    @Test
    void csrExtensionUpdateRejectsUnsupportedReplacementWithoutChangingTheAuthority() {
        String account = Long.toString(840_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
             AcmPcaClient pca = AwsTestClients.create(AcmPcaClient.builder(), account, Region.US_EAST_1)) {
            String stack = "pca-csr-" + UUID.randomUUID().toString().substring(0, 8);
            String initial = template("Pending CA", "{\"Key\": \"team\", \"Value\": \"original\"}");
            String stackId = cfn.createStack(request -> request.stackName(stack).templateBody(initial)).stackId();
            try {
                Map<String, String> before = outputs(CfnStackWaits.awaitStatus(cfn, stack, "CREATE_COMPLETE"));
                String arn = before.get("Arn");
                String changed = template("Pending CA", "{\"Key\": \"team\", \"Value\": \"updated\"}")
                        .replace("\"UsageMode\": \"GENERAL_PURPOSE\",",
                                "\"UsageMode\": \"GENERAL_PURPOSE\", \"CsrExtensions\": {},");
                cfn.updateStack(request -> request.stackName(stack).templateBody(changed));
                Stack rejected = CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals(before, outputs(rejected));
                assertEquals(Map.of("team", "original"), tags(pca, arn));
                assertEquals("PENDING_CERTIFICATE", describe(pca, arn).statusAsString());
                assertTrue(cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents().stream()
                        .anyMatch(event -> "Authority".equals(event.logicalResourceId())
                                && event.resourceStatusReason() != null
                                && event.resourceStatusReason().contains("CsrExtensions")));
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStatus(cfn, stackId, "DELETE_COMPLETE");
            }
        }
    }

    @Test
    void laterResourceFailureRestoresAuthorityTagsAndKeepsTheCommittedSnapshotFresh() {
        String account = Long.toString(840_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
             AcmPcaClient pca = AwsTestClients.create(AcmPcaClient.builder(), account, Region.US_EAST_1)) {
            String stack = "pca-rollback-" + UUID.randomUUID().toString().substring(0, 8);
            String initial = template("Pending CA", "{\"Key\":\"team\",\"Value\":\"original\"}");
            String stackId = cfn.createStack(request -> request.stackName(stack).templateBody(initial)).stackId();
            try {
                Map<String, String> before = outputs(CfnStackWaits.awaitStatus(cfn, stack, "CREATE_COMPLETE"));
                String arn = before.get("Arn");
                String committed = template("Pending CA", "{\"Key\":\"team\",\"Value\":\"committed\"}");
                cfn.updateStack(request -> request.stackName(stack).templateBody(committed));
                CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_COMPLETE");
                String changed = template("Pending CA", "{\"Key\":\"added\",\"Value\":\"temporary\"}")
                        .replace("\"Resources\": {", "\"Resources\": {\"Failure\": {"
                                + "\"Type\":\"AWS::SecretsManager::Secret\",\"DependsOn\":\"Authority\","
                                + "\"Properties\":{\"SecretString\":\"explicit\","
                                + "\"GenerateSecretString\":{\"PasswordLength\":32}}},");
                cfn.updateStack(request -> request.stackName(stack).templateBody(changed));
                Stack rolledBack = CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals(before, outputs(rolledBack));
                assertEquals(Map.of("team", "committed"), tags(pca, arn));
                assertEquals("PENDING_CERTIFICATE", describe(pca, arn).statusAsString());
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStatus(cfn, stackId, "DELETE_COMPLETE");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementRollbackPreservesPriorAuthorityAndCommitHonorsRetention(boolean retain) {
        String account = Long.toString(840_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
             AcmPcaClient pca = AwsTestClients.create(AcmPcaClient.builder(), account, Region.US_EAST_1)) {
            String stack = "pca-replace-" + UUID.randomUUID().toString().substring(0, 8);
            String initial = replacementTemplate("Prior CA", retain);
            String stackId = cfn.createStack(request -> request.stackName(stack).templateBody(initial)).stackId();
            String priorArn = null;
            try {
                Map<String, String> before = outputs(CfnStackWaits.awaitStatus(cfn, stack, "CREATE_COMPLETE"));
                priorArn = before.get("Arn");
                String changed = replacementTemplate("Replacement CA", retain);
                String failing = changed.replace("\"Resources\": {", "\"Resources\": {\"Failure\": {"
                        + "\"Type\":\"AWS::SecretsManager::Secret\",\"DependsOn\":\"Authority\","
                        + "\"Properties\":{\"SecretString\":\"explicit\","
                        + "\"GenerateSecretString\":{\"PasswordLength\":32}}},");
                cfn.updateStack(request -> request.stackName(stack).templateBody(failing));
                assertEquals(before, outputs(CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_ROLLBACK_COMPLETE")));
                assertEquals("PENDING_CERTIFICATE", describe(pca, priorArn).statusAsString());
                assertEquals(1, pca.listCertificateAuthorities().certificateAuthorities().stream()
                        .filter(authority -> !"DELETED".equals(authority.statusAsString())).count());
                cfn.updateStack(request -> request.stackName(stack).templateBody(changed));
                String replacement = outputs(CfnStackWaits.awaitStatus(cfn, stack, "UPDATE_COMPLETE")).get("Arn");
                assertNotEquals(priorArn, replacement);
                assertEquals(retain ? "PENDING_CERTIFICATE" : "DELETED", describe(pca, priorArn).statusAsString());
                assertEquals("PENDING_CERTIFICATE", describe(pca, replacement).statusAsString());
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStatus(cfn, stackId, "DELETE_COMPLETE");
                assertEquals("DELETED", describe(pca, replacement).statusAsString());
                if (retain) {
                    assertEquals("PENDING_CERTIFICATE", describe(pca, priorArn).statusAsString());
                }
            } finally {
                cfn.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStatus(cfn, stackId, "DELETE_COMPLETE");
                for (CertificateAuthority authority : pca.listCertificateAuthorities().certificateAuthorities()) {
                    if (!"DELETED".equals(authority.statusAsString())) {
                        pca.deleteCertificateAuthority(request -> request.certificateAuthorityArn(authority.arn()));
                    }
                }
            }
        }
    }

    private static String replacementTemplate(String name, boolean retain) {
        return template(name, "{\"Key\":\"team\",\"Value\":\"original\"}")
                .replace("\"Authority\": {", "\"Authority\": {\"UpdateReplacePolicy\":\""
                        + (retain ? "Retain" : "Delete") + "\",");
    }

    private static String revocationTemplate(String mechanism, boolean enabled) {
        return template("Pending CA", "{\"Key\": \"team\", \"Value\": \"updated\"}")
                .replace("\"UsageMode\": \"GENERAL_PURPOSE\",", "\"UsageMode\": \"GENERAL_PURPOSE\","
                        + "\"RevocationConfiguration\": {\"" + mechanism + "\": {\"Enabled\": " + enabled + "}},");
    }

    private static String template(String commonName, String tags) {
        return TEMPLATE.formatted(commonName, tags);
    }

    private static Map<String, String> outputs(Stack stack) {
        return stack.outputs().stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static CertificateAuthority describe(AcmPcaClient pca, String arn) {
        return pca.describeCertificateAuthority(request -> request.certificateAuthorityArn(arn)).certificateAuthority();
    }

    private static Map<String, String> tags(AcmPcaClient pca, String arn) {
        return pca.listTags(request -> request.certificateAuthorityArn(arn)).tags().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }
}
