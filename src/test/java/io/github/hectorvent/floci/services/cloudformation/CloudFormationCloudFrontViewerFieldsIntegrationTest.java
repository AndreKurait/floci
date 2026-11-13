package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator.GeneratedCertificate;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.ViewerCertificate;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CloudFormation preserves viewer certificate fields through the public SDK on create and update. */
@QuarkusTest
class CloudFormationCloudFrontViewerFieldsIntegrationTest {
    private static final String ACCOUNT = "454545454545";

    @Inject
    FlociCertificateAuthority ca;

    @ParameterizedTest
    @ValueSource(strings = {"literal", "ViewerCertificate", "DistributionConfig"})
    void templateReferencesReachTheGlobalDistributionOnCreateAndUpdate(String conditionalProperty) throws Exception {
        String name = "viewer-certificate-" + UUID.randomUUID().toString().substring(0, 8);
        String alias = name + ".example.test";
        Deque<Executable> cleanup = new ArrayDeque<>();
        try (AcmClient acm = AwsTestClients.create(AcmClient.builder(), ACCOUNT, Region.US_EAST_1);
             CloudFrontClient viewer = AwsTestClients.create(CloudFrontClient.builder(), ACCOUNT, Region.US_EAST_1);
             CloudFormationClient cfn = AwsTestClients.create(
                     CloudFormationClient.builder(), ACCOUNT, Region.EU_WEST_1);
             AutoCloseable owned = () -> assertAll("Viewer certificate cleanup", cleanup)) {
            String first = certificate(acm, alias);
            cleanup.push(() -> acm.deleteCertificate(r -> r.certificateArn(first)));
            String second = certificate(acm, alias);
            cleanup.push(() -> acm.deleteCertificate(r -> r.certificateArn(second)));
            String literalTemplate = """
                    {"Parameters":{"ViewerArn":{"Type":"String"}},"Resources":{"Distribution":{
                      "Type":"AWS::CloudFront::Distribution","Properties":{"DistributionConfig":{
                        "Enabled":true,"Aliases":["%s"],
                        "Origins":[{"Id":"origin","DomainName":"origin.example.test",
                          "CustomOriginConfig":{"OriginProtocolPolicy":"https-only"}}],
                        "DefaultCacheBehavior":{"TargetOriginId":"origin","ViewerProtocolPolicy":"https-only"},
                        "ViewerCertificate":{"AcmCertificateArn":{"Ref":"ViewerArn"},
                          "SslSupportMethod":"sni-only","MinimumProtocolVersion":"TLSv1.2_2021"}
                      }}}}}
                    """.formatted(alias);
            String template = conditionalTemplate(literalTemplate, conditionalProperty);
            cfn.createStack(r -> r.stackName(name).templateBody(template)
                    .parameters(p -> p.parameterKey("ViewerArn").parameterValue(first)));
            cleanup.push(() -> {
                cfn.deleteStack(r -> r.stackName(name));
                await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(50))
                        .untilAsserted(() -> {
                    CloudFormationException absent = assertThrows(CloudFormationException.class,
                            () -> cfn.describeStacks(r -> r.stackName(name)));
                    assertEquals("ValidationError", absent.awsErrorDetails().errorCode());
                });
            });
            CfnStackWaits.awaitStatus(cfn, name, "CREATE_COMPLETE");
            String distribution = cfn.describeStackResource(r -> r.stackName(name)
                    .logicalResourceId("Distribution")).stackResourceDetail().physicalResourceId();
            assertViewer(viewer, distribution, first);

            cfn.updateStack(r -> r.stackName(name).usePreviousTemplate(true)
                    .parameters(p -> p.parameterKey("ViewerArn").parameterValue(second)));
            CfnStackWaits.awaitStatus(cfn, name, "UPDATE_COMPLETE");
            assertEquals(distribution, cfn.describeStackResource(r -> r.stackName(name)
                    .logicalResourceId("Distribution")).stackResourceDetail().physicalResourceId());
            assertViewer(viewer, distribution, second);
        }
    }

    private String conditionalTemplate(String template, String property) throws Exception {
        if ("literal".equals(property)) {
            return template;
        }
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode document = (ObjectNode) mapper.readTree(template);
        document.putObject("Conditions").putObject("UseImported")
                .putArray("Fn::Equals").add("imported").add("imported");
        ObjectNode properties = (ObjectNode) document.path("Resources").path("Distribution").path("Properties");
        ObjectNode config = (ObjectNode) properties.path("DistributionConfig");
        ObjectNode defaultCertificate = mapper.createObjectNode().put("CloudFrontDefaultCertificate", true);
        if ("ViewerCertificate".equals(property)) {
            JsonNode certificate = config.get(property);
            config.putObject(property).putArray("Fn::If")
                    .add("UseImported").add(certificate).add(defaultCertificate);
            JsonNode aliases = config.get("Aliases");
            config.putObject("Aliases").putArray("Fn::If")
                    .add("UseImported").add(aliases).add(mapper.createArrayNode());
        } else {
            ObjectNode fallback = config.deepCopy();
            fallback.putArray("Aliases");
            fallback.set("ViewerCertificate", defaultCertificate);
            properties.putObject(property).putArray("Fn::If")
                    .add("UseImported").add(config).add(fallback);
        }
        return mapper.writeValueAsString(document);
    }

    private String certificate(AcmClient acm, String alias) {
        GeneratedCertificate material = ca.issueServerCertificate(alias, List.of(alias), KeyAlgorithm.RSA_2048, null);
        return acm.importCertificate(r -> r.certificate(SdkBytes.fromUtf8String(material.certificatePem()))
                .privateKey(SdkBytes.fromUtf8String(material.privateKeyPem()))
                .certificateChain(SdkBytes.fromUtf8String(ca.caPem()))).certificateArn();
    }

    private void assertViewer(CloudFrontClient client, String distribution, String expectedArn) {
        ViewerCertificate actual = client.getDistributionConfig(r -> r.id(distribution))
                .distributionConfig().viewerCertificate();
        assertEquals(expectedArn, actual.acmCertificateArn());
        assertEquals("sni-only", actual.sslSupportMethodAsString());
        assertEquals("TLSv1.2_2021", actual.minimumProtocolVersionAsString());
    }
}
