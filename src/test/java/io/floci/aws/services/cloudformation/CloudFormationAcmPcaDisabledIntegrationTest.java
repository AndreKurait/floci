package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.acmpca.AcmPcaService;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.StackResourceDetail;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(CloudFormationAcmPcaDisabledIntegrationTest.DisabledPcaProfile.class)
@Timeout(120)
class CloudFormationAcmPcaDisabledIntegrationTest {

    @Inject
    AcmPcaService authorities;

    @Test
    void disabledPcaStubsTheStackResourceWithoutCreatingAnAuthority() {
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(authorities.list(mapper.createObjectNode(), "us-east-1").path("CertificateAuthorities").isEmpty());
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(),
                "000000000000", Region.US_EAST_1)) {
            String name = "disabled-pca-" + UUID.randomUUID().toString().substring(0, 8);
            String id = cfn.createStack(request -> request.stackName(name).templateBody("""
                    {"Resources":{"Authority":{"Type":"AWS::ACMPCA::CertificateAuthority","Properties":{
                      "Type":"SUBORDINATE","KeyAlgorithm":"RSA_2048","SigningAlgorithm":"SHA256WITHRSA",
                      "Subject":{"CommonName":"Disabled authority"}}}}}
                    """)).stackId();
            try {
                CfnStackWaits.awaitStatus(cfn, id, "CREATE_COMPLETE");
                StackResourceDetail resource = cfn.describeStackResource(request -> request.stackName(id)
                        .logicalResourceId("Authority")).stackResourceDetail();
                assertNotNull(resource);
                assertNotNull(resource.resourceStatusReason());
                assertTrue(resource.resourceStatusReason().contains("floci.services.acm-pca.enabled=true"));
                assertTrue(authorities.list(mapper.createObjectNode(), "us-east-1")
                        .path("CertificateAuthorities").isEmpty());
            } finally {
                cfn.deleteStack(request -> request.stackName(id));
                CfnStackWaits.awaitStatus(cfn, id, "DELETE_COMPLETE");
            }
        }
    }

    public static class DisabledPcaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.acm-pca.enabled", "false",
                    "floci.services.cloudformation.allow-stub-unsupported-resource-types", "true");
        }
    }
}
