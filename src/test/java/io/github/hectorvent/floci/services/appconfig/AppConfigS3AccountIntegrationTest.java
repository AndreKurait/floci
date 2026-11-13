package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.BadRequestException;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(AppConfigS3AccountIntegrationTest.CrossAccountS3Profile.class)
class AppConfigS3AccountIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"us-east-1,false", "us-east-1,true", "cn-north-1,false", "cn-north-1,true"})
    void crossAccountRetrievalRequiresTheBucketPolicyAndKeepsTheApplicationOwner(String regionName, boolean objectArn)
            throws JsonProcessingException {
        Region region = Region.of(regionName);
        String name = "appconfig-s3-" + UUID.randomUUID();
        String key = "settings.json";
        byte[] content = "{\"account\":\"111111111111\"}".getBytes(StandardCharsets.UTF_8);
        List<Executable> cleanup = new ArrayList<>();
        try (S3Client s3 = AwsTestClients.create(
                S3Client.builder().forcePathStyle(true), "222222222222", region);
                IamClient iam = AwsTestClients.create(IamClient.builder(), "111111111111", region);
                AppConfigClient appconfig = AwsTestClients.create(
                        AppConfigClient.builder(), "111111111111", region);
                AppConfigDataClient data = AwsTestClients.create(
                        AppConfigDataClient.builder(), "111111111111", region)) {
            try {
                CreateBucketRequest.Builder bucket = CreateBucketRequest.builder().bucket(name);
                if (!"us-east-1".equals(regionName)) {
                    bucket.createBucketConfiguration(config -> config.locationConstraint(regionName));
                }
                s3.createBucket(bucket.build());
                cleanup.add(() -> s3.deleteBucket(request -> request.bucket(name)));
                s3.putBucketVersioning(request -> request.bucket(name)
                        .versioningConfiguration(config -> config.status("Enabled")));
                String version = s3.putObject(request -> request.bucket(name).key(key)
                        .contentType("application/json"), RequestBody.fromBytes(content)).versionId();
                cleanup.add(() -> s3.deleteObject(request -> request.bucket(name).key(key).versionId(version)));
                String role = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"Service":"appconfig.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                        """)).role().arn();
                cleanup.add(() -> iam.deleteRole(request -> request.roleName(name)));
                String bucketArn = "arn:" + role.split(":")[1] + ":s3:::" + name;
                String policy = MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(Map.of(
                        "Effect", "Allow", "Action", List.of("s3:GetBucketVersioning", "s3:GetObjectVersion"),
                        "Resource", List.of(bucketArn, bucketArn + "/" + key)))));
                iam.putRolePolicy(request -> request.roleName(name).policyName("source").policyDocument(policy));
                cleanup.add(() -> iam.deleteRolePolicy(request -> request.roleName(name).policyName("source")));
                String app = appconfig.createApplication(request -> request.name(name)).id();
                cleanup.add(() -> appconfig.deleteApplication(request -> request.applicationId(app)));
                String env = appconfig.createEnvironment(request -> request.applicationId(app).name("test")).id();
                cleanup.add(() -> appconfig.deleteEnvironment(request -> request.applicationId(app)
                        .environmentId(env).deletionProtectionCheck("BYPASS")));
                String location = (objectArn ? bucketArn : "s3://" + name) + "/" + key;
                String profile = appconfig.createConfigurationProfile(request -> request.applicationId(app)
                        .name("settings").locationUri(location).retrievalRoleArn(role)).id();
                cleanup.add(() -> appconfig.deleteConfigurationProfile(request -> request.applicationId(app)
                        .configurationProfileId(profile).deletionProtectionCheck("BYPASS")));
                String strategy = appconfig.createDeploymentStrategy(request -> request.name(name)
                        .deploymentDurationInMinutes(0).finalBakeTimeInMinutes(0).growthFactor(100f)
                        .growthType("LINEAR").replicateTo("NONE")).id();
                cleanup.add(() -> appconfig.deleteDeploymentStrategy(request -> request.deploymentStrategyId(strategy)));
                assertThrows(BadRequestException.class, () -> appconfig.startDeployment(request ->
                        request.applicationId(app).environmentId(env).configurationProfileId(profile)
                                .configurationVersion(version).deploymentStrategyId(strategy)));
                String crossAccountPolicy = MAPPER.writeValueAsString(Map.of(
                        "Version", "2012-10-17", "Statement", List.of(Map.of(
                                "Effect", "Allow", "Principal", Map.of("AWS", role),
                                "Action", List.of("s3:GetBucketVersioning", "s3:GetObjectVersion"),
                                "Resource", List.of(bucketArn, bucketArn + "/" + key)))));
                s3.putBucketPolicy(request -> request.bucket(name).policy(crossAccountPolicy));
                cleanup.add(() -> s3.deleteBucketPolicy(request -> request.bucket(name)));
                int deployment = appconfig.startDeployment(request -> request.applicationId(app).environmentId(env)
                        .configurationProfileId(profile).configurationVersion(version)
                        .deploymentStrategyId(strategy)).deploymentNumber();
                cleanup.add(() -> appconfig.stopDeployment(request -> request.applicationId(app).environmentId(env)
                        .deploymentNumber(deployment).allowRevert(true)));
                assertEquals("COMPLETE", appconfig.getDeployment(request -> request.applicationId(app)
                        .environmentId(env).deploymentNumber(deployment)).stateAsString());
                String token = data.startConfigurationSession(request -> request.applicationIdentifier(app)
                        .environmentIdentifier(env).configurationProfileIdentifier(profile)).initialConfigurationToken();
                assertArrayEquals(content, data.getLatestConfiguration(
                        request -> request.configurationToken(token)).configuration().asByteArray());
                assertEquals("AWS.Freeform", appconfig.getConfigurationProfile(request -> request.applicationId(app)
                        .configurationProfileId(profile)).type());
            } finally {
                assertAll(cleanup.reversed());
            }
        }
    }

    public static class CrossAccountS3Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.global-bucket-namespace", "true",
                    "floci.services.iam.enforcement-enabled", "true");
        }
    }
}
