package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
