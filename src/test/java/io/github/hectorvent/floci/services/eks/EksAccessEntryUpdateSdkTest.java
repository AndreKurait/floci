package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.AccessEntry;
import software.amazon.awssdk.services.eks.model.EksException;
import software.amazon.awssdk.services.eks.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iam.IamClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EksAccessEntryUpdateSdkTest {
    static final String ACCOUNT = "741686000191";

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-cn,cn-north-1", "aws-us-gov,us-gov-west-1"})
    void nativeSdkPartialUpdatesPreserveTheCompleteEntryAndAssociation(String partition, String region) {
        try (Fixture fixture = new Fixture(endpoint, region)) {
            AccessEntry before = fixture.createEntry(fixture.principal, "STANDARD");
            AccessEntry sibling = fixture.createEntry(fixture.siblingPrincipal, "STANDARD");
            String policy = "arn:" + partition + ":eks::aws:cluster-access-policy/AmazonEKSViewPolicy";
            fixture.eks.associateAccessPolicy(request -> request.clusterName(fixture.name).principalArn(fixture.principal)
                    .policyArn(policy).accessScope(scope -> scope.type("namespace").namespaces("owned")));
            AccessEntry groups = fixture.eks.updateAccessEntry(request -> request.clusterName(fixture.name)
                    .principalArn(fixture.principal).kubernetesGroups("readers", "writers")).accessEntry();
            assertEquals(before.username(), groups.username());
            assertEquals(List.of("readers", "writers"), groups.kubernetesGroups());
            AccessEntry username = fixture.eks.updateAccessEntry(request -> request.clusterName(fixture.name)
                    .principalArn(fixture.principal).username("updated:{{SessionNameRaw}}")).accessEntry();
            assertEquals(groups.kubernetesGroups(), username.kubernetesGroups());
            AccessEntry both = fixture.eks.updateAccessEntry(request -> request.clusterName(fixture.name)
                    .principalArn(fixture.principal).username("release").kubernetesGroups("release-group")).accessEntry();
            assertEquals("release", both.username());
            assertEquals(List.of("release-group"), both.kubernetesGroups());
            AccessEntry cleared = fixture.eks.updateAccessEntry(request -> request.clusterName(fixture.name)
                    .principalArn(fixture.principal).kubernetesGroups(List.of())).accessEntry();
            assertTrue(cleared.kubernetesGroups().isEmpty());
            AccessEntry omitted = fixture.eks.updateAccessEntry(request -> request.clusterName(fixture.name)
                    .principalArn(fixture.principal)).accessEntry();
            assertEquals(cleared.username(), omitted.username());
            assertEquals(cleared.kubernetesGroups(), omitted.kubernetesGroups());
            assertIdentity(before, omitted);
            assertNotNull(omitted.modifiedAt());
            assertFalse(omitted.modifiedAt().isBefore(before.modifiedAt()));
            assertEquals(omitted, fixture.read(fixture.principal));
            assertEquals(sibling, fixture.read(fixture.siblingPrincipal));
            assertEquals(List.of(policy), fixture.eks.listAssociatedAccessPolicies(request -> request
                    .clusterName(fixture.name).principalArn(fixture.principal)).associatedAccessPolicies().stream()
                    .map(value -> value.policyArn()).toList());
            fixture.eks.deleteAccessEntry(request -> request.clusterName(fixture.name).principalArn(fixture.principal));
            assertEquals(404, assertThrows(ResourceNotFoundException.class, () -> fixture.eks.updateAccessEntry(request ->
                    request.clusterName(fixture.name).principalArn(fixture.principal).username("missing"))).statusCode());
            assertEquals(sibling, fixture.read(fixture.siblingPrincipal));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"username\":\"system:admin\",\"kubernetesGroups\":[\"changed\"]}",
            "{\"username\":\"changed\",\"kubernetesGroups\":[\"\"]}",
            "{\"username\":123}", "{\"kubernetesGroups\":\"bad\"}", "{\"kubernetesGroups\":[null]}"})
    void malformedAndInvalidRequestsPreserveFullNativeReadback(String body) {
        try (Fixture fixture = new Fixture(endpoint, "us-east-1")) {
            AccessEntry before = fixture.createEntry(fixture.principal, "STANDARD");
            given().port(endpoint.getPort()).header("Authorization", EnforcementFixtures.auth(ACCOUNT, "eks"))
                    .contentType("application/json").urlEncodingEnabled(false).body(body)
                    .post(fixture.entryPath()).then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
            assertEquals(before, fixture.read(fixture.principal));
        }
    }

    @Test
    void foreignAccountRegionAndNodeOverridesRefuseWithoutMutation() {
        try (Fixture fixture = new Fixture(endpoint, "us-east-1");
                EksClient foreign = client(endpoint, "us-east-1", "741686000192", "test");
                EksClient otherRegion = client(endpoint, "us-west-2", ACCOUNT, "test")) {
            AccessEntry before = fixture.createEntry(fixture.principal, "STANDARD");
            for (EksClient denied : List.of(foreign, otherRegion)) {
                assertEquals(404, assertThrows(ResourceNotFoundException.class, () -> denied.updateAccessEntry(request ->
                        request.clusterName(fixture.name).principalArn(fixture.principal).username("foreign"))).statusCode());
            }
            assertEquals(before, fixture.read(fixture.principal));
            AccessEntry node = fixture.createEntry(fixture.siblingPrincipal, "EC2_LINUX");
            assertEquals(400, assertThrows(EksException.class, () -> fixture.eks.updateAccessEntry(request ->
                    request.clusterName(fixture.name).principalArn(fixture.siblingPrincipal).username("node"))).statusCode());
            assertEquals(400, assertThrows(EksException.class, () -> fixture.eks.updateAccessEntry(request ->
                    request.clusterName(fixture.name).principalArn(fixture.siblingPrincipal)
                            .kubernetesGroups(List.of()))).statusCode());
            assertEquals(node, fixture.read(fixture.siblingPrincipal));
        }
    }

    static void assertIdentity(AccessEntry before, AccessEntry after) {
        assertEquals(before.accessEntryArn(), after.accessEntryArn());
        assertEquals(before.clusterName(), after.clusterName());
        assertEquals(before.principalArn(), after.principalArn());
        assertEquals(before.type(), after.type());
        assertEquals(before.tags(), after.tags());
        assertEquals(before.createdAt(), after.createdAt());
    }

    static EksClient client(URI endpoint, String region, String key, String secret) {
        return EksClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    /** All native fixtures own actual roles, VPC subnets and the parent cluster; no compute is enabled. */
    static final class Fixture implements AutoCloseable {
        final EksClient eks;
        final IamClient iam;
        final Ec2Client ec2;
        final String name = "entry-update-" + UUID.randomUUID().toString().substring(0, 12);
        final Deque<Runnable> cleanup = new ArrayDeque<>();
        String principal;
        String siblingPrincipal;

        Fixture(URI endpoint, String region) {
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test"));
            eks = client(endpoint, region, ACCOUNT, "test");
            iam = IamClient.builder().endpointOverride(endpoint).region(Region.of(region)).credentialsProvider(credentials)
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.of(region)).credentialsProvider(credentials)
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            try {
                String clusterRole = role("cluster", "/");
                principal = role("principal", "/ci/access-policies/");
                siblingPrincipal = role("sibling", "/");
                String vpc = ec2.createVpc(request -> request.cidrBlock("10.241.0.0/16")).vpc().vpcId();
                cleanup.push(() -> ec2.deleteVpc(request -> request.vpcId(vpc)));
                String first = subnet(vpc, "10.241.1.0/24", region + "a");
                String second = subnet(vpc, "10.241.2.0/24", region + "b");
                eks.createCluster(request -> request.name(name).roleArn(clusterRole)
                        .resourcesVpcConfig(vpcConfig -> vpcConfig.subnetIds(first, second))
                        .accessConfig(access -> access.authenticationMode("API").bootstrapClusterCreatorAdminPermissions(false)));
                cleanup.push(() -> {
                    eks.deleteCluster(request -> request.name(name));
                    assertThrows(ResourceNotFoundException.class, () -> eks.describeCluster(request -> request.name(name)));
                });
            } catch (RuntimeException | AssertionError failed) {
                try {
                    close();
                } catch (RuntimeException | AssertionError cleanupFailure) {
                    failed.addSuppressed(cleanupFailure);
                }
                throw failed;
            }
        }

        private String role(String suffix, String path) {
            String roleName = name + "-" + suffix;
            String arn = iam.createRole(request -> request.roleName(roleName).path(path).assumeRolePolicyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                    "Principal":{"Service":"eks.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                    """))
                    .role().arn();
            cleanup.push(() -> iam.deleteRole(request -> request.roleName(roleName)));
            return arn;
        }

        private String subnet(String vpc, String cidr, String zone) {
            String id = ec2.createSubnet(request -> request.vpcId(vpc).cidrBlock(cidr).availabilityZone(zone)).subnet().subnetId();
            cleanup.push(() -> ec2.deleteSubnet(request -> request.subnetId(id)));
            return id;
        }

        AccessEntry createEntry(String arn, String type) {
            AccessEntry entry = eks.createAccessEntry(request -> {
                request.clusterName(name).principalArn(arn).type(type).tags(Map.of("team", "platform"));
                if ("STANDARD".equals(type)) {
                    request.username("original").kubernetesGroups("initial");
                }
            }).accessEntry();
            cleanup.push(() -> {
                try {
                    eks.deleteAccessEntry(request -> request.clusterName(name).principalArn(arn));
                } catch (ResourceNotFoundException expected) {
                    // A lifecycle test may already have removed its owned entry.
                }
                assertThrows(ResourceNotFoundException.class, () -> read(arn));
            });
            return entry;
        }

        AccessEntry read(String arn) {
            return eks.describeAccessEntry(request -> request.clusterName(name).principalArn(arn)).accessEntry();
        }

        String entryPath() {
            return "/clusters/" + name + "/access-entries/" + URLEncoder.encode(principal, StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            while (!cleanup.isEmpty()) {
                try {
                    cleanup.pop().run();
                } catch (RuntimeException | AssertionError error) {
                    if (failure == null) {
                        failure = new IllegalStateException("Owned EKS fixture cleanup failed", error);
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
            ec2.close();
            iam.close();
            eks.close();
            if (failure != null) {
                throw failure;
            }
        }
    }
}
