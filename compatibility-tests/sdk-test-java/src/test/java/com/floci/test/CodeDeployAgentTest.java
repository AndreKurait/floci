package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.util.io.pem.PemReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.BundleType;
import software.amazon.awssdk.services.codedeploy.model.ComputePlatform;
import software.amazon.awssdk.services.codedeploy.model.DeploymentStatus;
import software.amazon.awssdk.services.codedeploy.model.EC2TagFilter;
import software.amazon.awssdk.services.codedeploy.model.EC2TagFilterType;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocation;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocationType;
import software.amazon.awssdk.services.codedeploy.model.S3Location;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Exercises signed command envelopes against the standalone server, including native-image CI. */
class CodeDeployAgentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> PHASES = List.of("ApplicationStop", "DownloadBundle", "BeforeInstall",
            "Install", "AfterInstall", "ApplicationStart", "ValidateService");

    @Test
    @DisplayName("Signed agent specifications and lifecycle commands complete a Server deployment")
    void signsSpecificationsAndTracksWireCommandCompletion() throws Exception {
        assumeFalse(TestFixtures.isRealAws(), "This fixture simulates an agent on a local EC2 instance");
        String name = TestFixtures.uniqueName("agent-wire");
        Deque<AutoCloseable> cleanup = new ArrayDeque<>();
        HttpClient.Builder httpBuilder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if ("https".equals(TestFixtures.endpoint().getScheme())) {
            httpBuilder.sslContext(TestFixtures.flociCaTrust());
        }
        HttpClient http = httpBuilder.build();
        try (Ec2Client ec2 = TestFixtures.ec2Client();
             IamClient iam = TestFixtures.iamClient();
             S3Client s3 = TestFixtures.s3Client();
             CodeDeployClient deployments = TestFixtures.codeDeployClient()) {
            Throwable primaryFailure = null;
            try {
                String vpc = ec2.createVpc(request -> request.cidrBlock("10.202.0.0/16")).vpc().vpcId();
                cleanup.push(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
                String subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.202.1.0/24")
                        .availabilityZone(TestFixtures.availabilityZone())).subnet().subnetId();
                cleanup.push(() -> ec2.deleteSubnet(request -> request.subnetId(subnet)));
                String instance = ec2.runInstances(request -> request.imageId("ami-0abcdef1234567890")
                        .instanceType(InstanceType.T3_MICRO).minCount(1).maxCount(1).subnetId(subnet)
                        .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.INSTANCE)
                                .tags(Tag.builder().key("agent-wire").value(name).build()).build()))
                        .instances().get(0).instanceId();
                cleanup.push(() -> {
                    ec2.terminateInstances(request -> request.instanceIds(instance));
                    assertThat(ec2.waiter().waitUntilInstanceTerminated(
                            DescribeInstancesRequest.builder().instanceIds(instance).build(),
                            TestFixtures.waiterOverride(Duration.ofSeconds(30))).matched().response()).isPresent();
                });
                String role = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                         "Principal":{"Service":"codedeploy.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                        """)).role().arn();
                cleanup.push(() -> iam.deleteRole(request -> request.roleName(name)));
                s3.createBucket(request -> {
                    request.bucket(name);
                    if (!"us-east-1".equals(TestFixtures.region().id())) {
                        request.createBucketConfiguration(configuration ->
                                configuration.locationConstraint(TestFixtures.region().id()));
                    }
                });
                cleanup.push(() -> s3.deleteBucket(request -> request.bucket(name)));
                s3.putObject(request -> request.bucket(name).key("revision.zip"), RequestBody.fromBytes(revision()));
                cleanup.push(() -> s3.deleteObject(request -> request.bucket(name).key("revision.zip")));
                deployments.createApplication(request -> request.applicationName(name).computePlatform(ComputePlatform.SERVER));
                cleanup.push(() -> deployments.deleteApplication(request -> request.applicationName(name)));
                deployments.createDeploymentGroup(request -> request.applicationName(name).deploymentGroupName(name)
                        .serviceRoleArn(role).deploymentConfigName("CodeDeployDefault.OneAtATime")
                        .ec2TagFilters(EC2TagFilter.builder().key("agent-wire").value(name)
                                .type(EC2TagFilterType.KEY_AND_VALUE).build()));
                cleanup.push(() -> deployments.deleteDeploymentGroup(request ->
                        request.applicationName(name).deploymentGroupName(name)));
                String deployment = deployments.createDeployment(request -> request.applicationName(name)
                        .deploymentGroupName(name).revision(RevisionLocation.builder()
                                .revisionType(RevisionLocationType.S3).s3Location(S3Location.builder()
                                        .bucket(name).key("revision.zip").bundleType(BundleType.ZIP).build()).build()))
                        .deploymentId();
                cleanup.push(() -> {
                    DeploymentStatus status = deployments.getDeployment(request -> request.deploymentId(deployment))
                            .deploymentInfo().status();
                    if (!Set.of(DeploymentStatus.SUCCEEDED, DeploymentStatus.FAILED, DeploymentStatus.STOPPED)
                            .contains(status)) {
                        deployments.stopDeployment(request -> request.deploymentId(deployment));
                    }
                });
                String host = TestFixtures.arn("ec2", "000000000000", "instance/" + instance);
                for (String phase : PHASES) {
                    JsonNode command = call(http, "PollHostCommand", Map.of("HostIdentifier", host)).path("HostCommand");
                    assertThat(command.path("CommandName").asText()).isEqualTo(phase);
                    String commandId = command.path("HostCommandIdentifier").asText();
                    assertThat(commandId).isNotBlank();
                    if ("DownloadBundle".equals(phase)) {
                        JsonNode envelope = call(http, "GetDeploymentSpecification", Map.of(
                                "HostIdentifier", host,
                                "DeploymentExecutionId", command.path("DeploymentExecutionId").asText()))
                                .path("DeploymentSpecification").path("GenericEnvelope");
                        assertThat(envelope.path("Format").asText()).isEqualTo("PKCS7/JSON");
                        JsonNode specification = verifyEnvelope(envelope.path("Payload").asText());
                        assertThat(specification.path("DeploymentId").asText()).isEqualTo(deployment);
                        assertThat(specification.path("ApplicationName").asText()).isEqualTo(name);
                        assertThat(specification.path("Revision").path("S3Revision").path("Bucket").asText()).isEqualTo(name);
                        assertThat(specification.path("Revision").path("S3Revision").path("Key").asText())
                                .isEqualTo("revision.zip");
                    }
                    assertThat(call(http, "PutHostCommandAcknowledgement", Map.of("HostCommandIdentifier", commandId))
                            .path("CommandStatus").asText()).isEqualTo("InProgress");
                    call(http, "PostHostCommandUpdate", Map.of("HostCommandIdentifier", commandId));
                    call(http, "PutHostCommandComplete", Map.of("HostCommandIdentifier", commandId,
                            "CommandStatus", "Succeeded", "Diagnostics",
                            Map.of("Format", "JSON", "Payload", "{\"error_code\":0}")));
                }
                TestFixtures.await(() -> deployments.getDeployment(request -> request.deploymentId(deployment))
                                .deploymentInfo().status() == DeploymentStatus.SUCCEEDED,
                        deployment, "Succeeded", Duration.ofSeconds(20).toNanos(), 100);
                assertThat(deployments.getDeploymentTarget(request ->
                                request.deploymentId(deployment).targetId(instance))
                        .deploymentTarget().instanceTarget().statusAsString()).isEqualTo("Succeeded");
            } catch (Exception | Error failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                Exception failure = null;
                while (!cleanup.isEmpty()) {
                    try {
                        cleanup.pop().close();
                    } catch (Exception | Error problem) {
                        if (failure == null) {
                            failure = new Exception("CodeDeploy wire fixture cleanup failed");
                        }
                        failure.addSuppressed(problem);
                    }
                }
                if (failure != null) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(failure);
                    } else {
                        throw failure;
                    }
                }
            }
        }
    }

    private static JsonNode call(HttpClient http, String operation, Map<String, Object> payload) throws Exception {
        byte[] body = JSON.writeValueAsBytes(payload);
        SdkHttpFullRequest signed = Aws4Signer.create().sign(SdkHttpFullRequest.builder()
                        .uri(TestFixtures.endpoint()).method(SdkHttpMethod.POST)
                        .putHeader("Content-Type", "application/x-amz-json-1.1")
                        .putHeader("X-Amz-Target", "CodeDeployCommandService." + operation)
                        .contentStreamProvider(() -> new ByteArrayInputStream(body)).build(),
                Aws4SignerParams.builder().awsCredentials(AwsBasicCredentials.create("test", "test"))
                        .signingRegion(TestFixtures.region()).signingName("codedeploy-commands-secure").build());
        HttpRequest.Builder request = HttpRequest.newBuilder(TestFixtures.endpoint()).timeout(Duration.ofSeconds(70))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        signed.headers().forEach((key, values) -> {
            if (!"host".equalsIgnoreCase(key) && !"content-length".equalsIgnoreCase(key)) {
                values.forEach(value -> request.header(key, value));
            }
        });
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage("%s: %s", operation, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static JsonNode verifyEnvelope(String value) throws Exception {
        try (PemReader pem = new PemReader(new StringReader(value))) {
            CMSSignedData envelope = new CMSSignedData(pem.readPemObject().getContent());
            assertThat(envelope.getSignerInfos().getSigners()).hasSize(1);
            SignerInformation signer = envelope.getSignerInfos().getSigners().iterator().next();
            Collection<X509CertificateHolder> certificates = envelope.getCertificates().getMatches(signer.getSID());
            assertThat(certificates).hasSize(1);
            assertThat(signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(certificates.iterator().next())))
                    .isTrue();
            return JSON.readTree((byte[]) envelope.getSignedContent().getContent());
        }
    }

    private static byte[] revision() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("appspec.yml"));
            zip.write("version: 0.0\nos: linux\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
