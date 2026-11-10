package io.github.hectorvent.floci.services.codedeploy;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.ec2.AmiImageResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.ResolvedAmiImage;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.DeploymentInfo;
import software.amazon.awssdk.services.codedeploy.model.DeploymentStatus;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocation;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.when;

/** Shared stock-agent image, owning ASG and S3 revision fixture. */
abstract class CodeDeployAgentDockerFixture {
    static final Logger LOG = Logger.getLogger(CodeDeployAgentDockerFixture.class);
    static final String REGION = "us-east-1";
    static final String VERSION = "2.1.2";
    static final StaticCredentialsProvider ADMIN = StaticCredentialsProvider.create(
            AwsBasicCredentials.create("test", "test"));
    final Deque<Executable> cleanup = new ArrayDeque<>();
    private List<String> targetInstances = List.of();

    public static class AgentProfile implements QuarkusTestProfile {
        static final String NAMESPACE = "codedeploy-agent-" + UUID.randomUUID();

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(Map.of("floci.services.ec2.mock", "false",
                    "floci.services.iam.enforcement-enabled", "true",
                    "floci.auth.validate-signatures", "true",
                    "floci.services.s3.enforce-auth", "true",
                    "floci.docker.resource-namespace", NAMESPACE));
            String hostDnsAddress = System.getenv("FLOCI_TEST_HOST_DNS_ADDRESS");
            if (hostDnsAddress != null && !hostDnsAddress.isBlank()) {
                overrides.put("floci.dns.listen-address", hostDnsAddress);
            }
            return overrides;
        }
    }

    @Inject DockerClient docker;
    @Inject Ec2Service instances;
    @InjectMock AmiImageResolver images;
    @TestHTTPResource URI endpoint;
    @TempDir Path imageContext;

    @BeforeEach
    @Timeout(value = 5, unit = TimeUnit.MINUTES) // Allows the 90-second download and 180-second cold image build.
    void prepareOfficialAgentImage() throws Exception {
        assumeTrue(DockerAvailability.isAvailable(), "Docker is required for the AWS CodeDeploy agent");
        String architecture = docker.infoCmd().exec().getArchitecture();
        boolean arm = "aarch64".equals(architecture) || "arm64".equals(architecture);
        assertTrue(arm || "x86_64".equals(architecture) || "amd64".equals(architecture), architecture);
        String packageName = "codedeploy-agent_" + VERSION + "_" + (arm ? "arm64" : "amd64") + ".deb";
        String digest = arm
                ? "fd38262592bdd2742ead0cbd5b074d9252fe747d16732a0c9f66f131b44cbb23"
                : "ecafc903fd2dce929c7b4dba4c5c54593507245a177eedccec41d033a37325ac";
        URI source = URI.create("https://github.com/aws/aws-codedeploy-agent/releases/download/v"
                + VERSION + "/" + packageName);
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build()) {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(source)
                    .timeout(Duration.ofSeconds(90)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                assertEquals(200, response.statusCode());
                byte[] bytes = body.readNBytes(32 * 1024 * 1024 + 1);
                assertTrue(bytes.length <= 32 * 1024 * 1024, "Agent package exceeds the download bound");
                assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                Files.write(imageContext.resolve("agent.deb"), bytes);
            }
        }
        try (InputStream dockerfile = getClass().getResourceAsStream("/codedeploy/agent/Dockerfile")) {
            assertNotNull(dockerfile);
            Files.copy(dockerfile, imageContext.resolve("Dockerfile"));
        }
        try (CodeDeployImageBuild build = new CodeDeployImageBuild()) {
            docker.buildImageCmd(imageContext.toFile())
                    .withBuildArg("BASE_IMAGE", TestImages.UBUNTU_NOBLE).exec(build);
            String image = build.awaitImageId(180, TimeUnit.SECONDS);
            cleanup.addFirst(() -> docker.removeImageCmd(image).exec());
            when(images.resolveImage("ami-codedeploy-agent")).thenReturn(
                    new ResolvedAmiImage(image, ResolvedAmiImage.IMAGE_RUNTIME, false, null));
        }
    }

    @AfterEach
    void deleteOwnedResources() {
        List<Executable> actions = new ArrayList<>();
        for (String target : targetInstances) {
            actions.add(() -> LOG.infof("AWS agent log for %s:%n%s", target,
                    output(target, "tail", "-n", "80", "/var/log/aws/codedeploy-agent/codedeploy-agent.log")));
        }
        actions.addAll(cleanup);
        assertAll("CodeDeploy agent diagnostics and resources", actions);
    }

    Fleet fleet() {
        Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(ADMIN).build();
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(ADMIN).build();
        AutoScalingClient scaling = AutoScalingClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(ADMIN).build();
        S3Client s3 = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(ADMIN)
                .forcePathStyle(true).build();
        CodeDeployClient deployments = CodeDeployClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(ADMIN).build();
        cleanup.addFirst(ec2::close);
        cleanup.addFirst(iam::close);
        cleanup.addFirst(scaling::close);
        cleanup.addFirst(s3::close);
        cleanup.addFirst(deployments::close);
        String name = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        s3.createBucket(request -> request.bucket(name));
        cleanup.addFirst(() -> {
            s3.listObjectVersions(request -> request.bucket(name)).versions().forEach(version ->
                    s3.deleteObject(request -> request.bucket(name).key(version.key()).versionId(version.versionId())));
            s3.deleteBucket(request -> request.bucket(name));
        });
        s3.putBucketVersioning(request -> request.bucket(name)
                .versioningConfiguration(configuration -> configuration.status("Enabled")));
        String guestRole = createRole(iam, name + "-guest", "ec2.amazonaws.com");
        iam.putRolePolicy(request -> request.roleName(name + "-guest").policyName("agent")
                .policyDocument("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"codedeploy-commands-secure:*","Resource":"*"},
                          {"Effect":"Allow","Action":["s3:GetObject","s3:GetObjectVersion"],"Resource":"arn:aws:s3:::%s/*"}
                        ]}
                        """.formatted(name)));
        cleanup.addFirst(() -> iam.deleteRolePolicy(request -> request.roleName(name + "-guest").policyName("agent")));
        iam.createInstanceProfile(request -> request.instanceProfileName(name));
        cleanup.addFirst(() -> iam.deleteInstanceProfile(request -> request.instanceProfileName(name)));
        iam.addRoleToInstanceProfile(request -> request.instanceProfileName(name).roleName(name + "-guest"));
        cleanup.addFirst(() -> iam.removeRoleFromInstanceProfile(request ->
                request.instanceProfileName(name).roleName(name + "-guest")));
        String deploymentRole = createRole(iam, name + "-deployment", "codedeploy.amazonaws.com");
        iam.putRolePolicy(request -> request.roleName(name + "-deployment").policyName("lifecycle")
                .policyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":[
                          "autoscaling:PutLifecycleHook","autoscaling:DeleteLifecycleHook",
                          "autoscaling:RecordLifecycleActionHeartbeat","autoscaling:CompleteLifecycleAction"
                        ],"Resource":"*"}]}
                        """));
        cleanup.addFirst(() -> iam.deleteRolePolicy(request ->
                request.roleName(name + "-deployment").policyName("lifecycle")));
        assertTrue(guestRole.contains(":role/"));

        String vpc = ec2.createVpc(request -> request.cidrBlock("10.94.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
        String subnet = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.94.1.0/24")
                .availabilityZone("us-east-1a")).subnet().subnetId();
        cleanup.addFirst(() -> ec2.deleteSubnet(request -> request.subnetId(subnet)));
        String gateway = docker.inspectNetworkCmd().withNetworkId("bridge").exec()
                .getIpam().getConfig().getFirst().getGateway();
        String agentEndpoint = "http://" + gateway + ":" + endpoint.getPort();
        scaling.createLaunchConfiguration(request -> request.launchConfigurationName(name)
                .imageId("ami-codedeploy-agent").instanceType("t3.micro").iamInstanceProfile(name)
                .userData(Base64.getEncoder().encodeToString(userData(agentEndpoint).getBytes(StandardCharsets.UTF_8))));
        cleanup.addFirst(() -> scaling.deleteLaunchConfiguration(request -> request.launchConfigurationName(name)));
        scaling.createAutoScalingGroup(request -> request.autoScalingGroupName(name).launchConfigurationName(name)
                .minSize(2).maxSize(2).desiredCapacity(2).vpcZoneIdentifier(subnet));
        cleanup.addFirst(() -> {
            List<String> ids = members(scaling, name);
            scaling.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
            await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                    assertTrue(ec2.describeInstances(request -> request.instanceIds(ids)).reservations().stream()
                            .flatMap(reservation -> reservation.instances().stream())
                            .allMatch(instance -> "terminated".equals(instance.state().nameAsString()))));
        });
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            assertEquals(2, members(scaling, name).size());
            for (String id : members(scaling, name)) {
                assertEquals("running", localInstance(id).getState().getName());
                assertEquals(0, guest(id, "/opt/codedeploy-agent/bin/codedeploy-agent", "status").exitCode());
                assertEquals(0, guest(id, "test", "-f", "/var/lib/floci-agent-bootstrap-complete").exitCode());
            }
        });
        List<String> targets = members(scaling, name);
        targetInstances = targets;
        deployments.createApplication(request -> request.applicationName(name).computePlatform("Server"));
        cleanup.addFirst(() -> deployments.deleteApplication(request -> request.applicationName(name)));
        deployments.createDeploymentGroup(request -> request.applicationName(name).deploymentGroupName(name)
                .serviceRoleArn(deploymentRole).autoScalingGroups(name)
                .deploymentConfigName("CodeDeployDefault.OneAtATime"));
        cleanup.addFirst(() -> deployments.deleteDeploymentGroup(request ->
                request.applicationName(name).deploymentGroupName(name)));

        return new Fleet(name, s3, deployments, targets, ec2, iam, subnet);
    }

    String createRole(IamClient iam, String name, String principal) {
        String arn = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument(
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\""
                        + principal + "\"},\"Action\":\"sts:AssumeRole\"}]}")).role().arn();
        cleanup.addFirst(() -> iam.deleteRole(request -> request.roleName(name)));
        return arn;
    }

    static List<String> members(AutoScalingClient scaling, String name) {
        return scaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst().instances().stream()
                .map(instance -> instance.instanceId()).toList();
    }

    static String userData(String endpoint) {
        return """
                #!/bin/sh
                set -eu
                cat > /etc/codedeploy-agent/conf/codedeployagent.yml <<'CONFIG'
                :deploy_control_endpoint: %s
                :s3_endpoint_override: %s
                :enable_auth_policy: true
                :disable_imds_v1: true
                CONFIG
                nohup /opt/codedeploy-agent/bin/codedeploy-agent start \
                    > /var/log/codedeploy-agent-console.log 2>&1 < /dev/null &
                touch /var/lib/floci-agent-bootstrap-complete
                """.formatted(endpoint, endpoint);
    }

    static RevisionLocation upload(S3Client s3, String bucket, String key, byte[] bytes) {
        PutObjectResponse response = s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
        assertNotNull(response.versionId());
        return RevisionLocation.builder().revisionType("S3").s3Location(location -> location
                .bucket(bucket).key(key).bundleType("zip").version(response.versionId()).eTag(response.eTag())).build();
    }

    static String deploy(CodeDeployClient deployments, String name, RevisionLocation revision) {
        return deploy(deployments, name, revision, "DISALLOW");
    }

    static String deploy(CodeDeployClient deployments, String name, RevisionLocation revision,
                                 String fileExistsBehavior) {
        return deployments.createDeployment(request -> request.applicationName(name).deploymentGroupName(name)
                .fileExistsBehavior(fileExistsBehavior).revision(revision)).deploymentId();
    }

    static void assertDeployment(CodeDeployClient deployments, String id, DeploymentStatus expected) {
        long started = System.nanoTime();
        DeploymentInfo info = await().atMost(Duration.ofMinutes(2)).until(() ->
                        deployments.getDeployment(request -> request.deploymentId(id)).deploymentInfo(),
                state -> List.of(DeploymentStatus.SUCCEEDED, DeploymentStatus.FAILED, DeploymentStatus.STOPPED)
                        .contains(state.status()));
        LOG.infof("AWS agent deployment %s reached %s after %.3f seconds",
                id, info.status(), (System.nanoTime() - started) / 1_000_000_000.0);
        assertEquals(expected, info.status(), () -> id + ": " + info);
    }

    Instance localInstance(String id) {
        return instances.describeInstances(REGION, List.of(id), Map.of()).getFirst().getInstances().getFirst();
    }

    ContainerExec.Result guest(String instance, String... command) {
        return ContainerExec.run(docker, localInstance(instance).getDockerContainerId(), command, 10);
    }

    String output(String instance, String... command) {
        ContainerExec.Result result = guest(instance, command);
        assertEquals(0, result.exitCode(), result.summary());
        return result.stdout();
    }

    void assertEvents(String instance, String deployment, List<String> expected) {
        List<String> events = output(instance, "cat", "/var/lib/floci-agent-events").lines()
                .filter(line -> line.startsWith(deployment + " "))
                .map(line -> line.substring(deployment.length() + 1)).toList();
        assertEquals(expected, events);
    }

    void assertInstalledPayload(String instance, String marker) throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((marker + "\n").getBytes(StandardCharsets.UTF_8)));
        String actual = output(instance, "sha256sum", "/opt/floci-agent-proof/payload.txt").split(" ")[0];
        assertEquals(expected, actual);
        LOG.infof("AWS agent installed %s on %s with SHA-256 %s", marker, instance, actual);
    }

    static byte[] bundle(String marker, boolean fail) throws Exception {
        return bundle(marker, fail, false);
    }

    static byte[] bundle(String marker, boolean fail, boolean unmanagedFile) throws Exception {
        return bundle(marker, fail, unmanagedFile, false);
    }

    static byte[] bundle(String marker, boolean fail, boolean unmanagedFile, boolean block) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(bytes)) {
            write(zip, "payload.txt", marker + "\n");
            StringBuilder appspec = new StringBuilder("""
                    version: 0.0
                    os: linux
                    files:
                      - source: payload.txt
                        destination: /opt/floci-agent-proof
                    """);
            if (unmanagedFile) {
                write(zip, "unmanaged.txt", "bundle-version");
                appspec.append("""
                          - source: unmanaged.txt
                            destination: /opt/floci-agent-proof
                        """);
            }
            appspec.append("hooks:\n");
            for (String phase : List.of("ApplicationStop", "BeforeInstall", "AfterInstall",
                    "ApplicationStart", "ValidateService")) {
                appspec.append("  ").append(phase).append(":\n    - location: scripts/")
                        .append(phase).append(".sh\n      timeout: ").append(block ? 60 : 10)
                        .append("\n      runas: root\n");
                String hook = "#!/bin/sh\nset -eu\nprintf '%s %s\\n' \"$DEPLOYMENT_ID\" \"$LIFECYCLE_EVENT\""
                        + " >> /var/lib/floci-agent-events\n";
                if ("AfterInstall".equals(phase) && fail) {
                    hook += "echo deliberate-hook-failure >&2\nexit 42\n";
                }
                if ("AfterInstall".equals(phase) && block) {
                    hook += "mkfifo /var/lib/floci-agent-gate\n"
                            + "cat /var/lib/floci-agent-gate > /dev/null\n"
                            + "printf released > /var/lib/floci-agent-released\n";
                }
                if ("ValidateService".equals(phase)) {
                    hook += "test \"$(cat /opt/floci-agent-proof/payload.txt)\" = '" + marker + "'\n";
                }
                write(zip, "scripts/" + phase + ".sh", hook);
            }
            write(zip, "appspec.yml", appspec.toString());
        }
        return bytes.toByteArray();
    }

    static void write(ZipArchiveOutputStream zip, String name, String contents) throws Exception {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setUnixMode(name.endsWith(".sh") ? 0100755 : 0100644);
        zip.putArchiveEntry(entry);
        zip.write(contents.getBytes(StandardCharsets.UTF_8));
        zip.closeArchiveEntry();
    }

    record Fleet(String name, S3Client s3, CodeDeployClient deployments, List<String> targets,
                 Ec2Client ec2, IamClient iam, String subnet) {}
}
