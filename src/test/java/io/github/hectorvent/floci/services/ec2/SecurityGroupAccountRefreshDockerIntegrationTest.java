package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IpPermission;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK rule updates exercise real namespace filtering for two independent service accounts. */
@QuarkusTest
@TestProfile(SecurityGroupAccountRefreshDockerIntegrationTest.Enforced.class)
class SecurityGroupAccountRefreshDockerIntegrationTest {
    private static final String REGION = "us-east-1";
    private static final String CIDR = "10.73.1.0/24";

    public static class Enforced implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false",
                    "floci.network.security-group-enforcement.enabled", "true",
                    "floci.storage.mode", "memory");
        }
    }

    @Inject SecurityGroupFirewallManager firewall;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject ContainerBuilder builder;
    @Inject DockerClient docker;
    @Inject Ec2Service service;
    @TestHTTPResource("/") URI endpoint;

    private final Deque<Executable> cleanup = new ArrayDeque<>();

    @AfterEach
    void retireEveryOwnedResource() {
        assertAll("owned network and API resources", cleanup);
    }

    @ParameterizedTest
    @CsvSource({"222222222222,us-east-1", "111111111111,us-west-2"})
    void ruleUpdatesStayWithinTheirAccountAndRegion(String otherAccount, String otherRegion) throws Exception {
        assertTrue(firewall.enabled());
        try {
            Assumptions.assumeTrue("linux".equals(docker.infoCmd().exec().getOsType()));
        } catch (Exception unavailable) {
            Assumptions.abort("Linux Docker is unavailable");
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String networkName = "floci-sg-accounts-" + suffix;
        String networkId = docker.createNetworkCmd().withName(networkName).exec().getId();
        cleanup.addFirst(() -> docker.removeNetworkCmd(networkId).exec());

        Account first = account("111111111111", REGION, suffix);
        Account second = account(otherAccount, otherRegion, suffix);
        Connection firstConnection = connection(first, networkName, suffix + "-a");
        Connection secondConnection = connection(second, networkName, suffix + "-b");
        assertEquals(0, connect(firstConnection), "first account baseline");
        assertEquals(0, connect(secondConnection), "second account baseline");

        first.client().revokeSecurityGroupIngress(request ->
                request.groupId(first.groupId()).ipPermissions(permission()));
        assertAll("changing account A must leave account B reachable",
                () -> assertNotEquals(0, connect(firstConnection), "account A rule was removed"),
                () -> assertEquals(0, connect(secondConnection), "account B rules must remain installed"));

        first.client().authorizeSecurityGroupIngress(request ->
                request.groupId(first.groupId()).ipPermissions(permission()));
        assertEquals(0, connect(firstConnection), "account A recovers through the SDK ingress update");
        assertEquals(0, connect(secondConnection), "account B remains reachable");

        second.client().revokeSecurityGroupIngress(request ->
                request.groupId(second.groupId()).ipPermissions(permission()));
        assertAll("the same isolation applies when account B changes",
                () -> assertEquals(0, connect(firstConnection), "account A remains reachable"),
                () -> assertNotEquals(0, connect(secondConnection), "account B rule was removed"));

        second.client().authorizeSecurityGroupIngress(request ->
                request.groupId(second.groupId()).ipPermissions(permission()));
        assertEquals(0, connect(secondConnection));
        assertThrows(IllegalStateException.class, () ->
                firewall.refreshPolicies(first.id(), first.region(), Map.of(), Map.of()));
        assertAll("an incomplete policy snapshot quarantines only its owning account and region",
                () -> assertNotEquals(0, connect(firstConnection)),
                () -> assertEquals(0, connect(secondConnection)));
    }

    private Account account(String accountId, String region, String suffix) {
        Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(10)))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accountId, "test")))
                .build();
        cleanup.addFirst(client::close);
        String vpcId = client.createVpc(request -> request.cidrBlock("10.73.0.0/16")).vpc().vpcId();
        cleanup.addFirst(() -> client.deleteVpc(request -> request.vpcId(vpcId)));
        String groupId = client.createSecurityGroup(request -> request.vpcId(vpcId)
                .groupName("account-refresh-" + suffix).description("account refresh regression")).groupId();
        cleanup.addFirst(() -> client.deleteSecurityGroup(request -> request.groupId(groupId)));
        client.authorizeSecurityGroupIngress(request -> request.groupId(groupId).ipPermissions(permission()));
        assertEquals(accountId, client.describeSecurityGroups(request -> request.groupIds(groupId))
                .securityGroups().getFirst().ownerId());
        return new Account(accountId, region, vpcId, groupId, client);
    }

    private Connection connection(Account account, String networkName, String suffix) throws Exception {
        SecurityGroup group = RequestScopes.callAs(account.id(), account.region(), () ->
                service.describeSecurityGroups(account.region(), List.of(account.groupId()), List.of(), Map.of())
                        .getFirst());
        SecurityGroupFirewallManager.Namespace source = namespace(account, group, networkName,
                suffix + "-source", "10.73.1.10");
        SecurityGroupFirewallManager.Namespace target = namespace(account, group, networkName,
                suffix + "-target", "10.73.1.20");
        String worker = lifecycle.createAndStart(builder.newContainer("alpine:3.21")
                .withName("floci-sg-account-target-" + suffix)
                .withNetworkMode("container:" + target.helperId())
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of("exec nc -lk -p 8080 -e /bin/cat")).build()).containerId();
        cleanup.addFirst(() -> lifecycle.stopAndRemoveStrict(worker, null, 1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (execute(target.helperId(), "nc", "-z", "-w", "1", "127.0.0.1", "8080") != 0) {
            assertTrue(System.nanoTime() < deadline, "fixture listener did not start");
            Thread.sleep(50);
        }
        return new Connection(source.helperId(), target.transportAddress());
    }

    private SecurityGroupFirewallManager.Namespace namespace(Account account, SecurityGroup group,
            String networkName, String suffix, String logicalAddress) {
        SecurityGroupFirewallManager.Namespace namespace = firewall.createNamespace("ec2", suffix,
                account.id(), account.region(), Optional.of(networkName), Map.of());
        cleanup.addFirst(() -> lifecycle.removeIfExists(namespace.helperId()));
        String eniId = "eni-account-refresh-" + suffix;
        cleanup.addFirst(() -> firewall.unregister(eniId));
        firewall.register(new SecurityGroupNftCompiler.Endpoint(account.id(), account.region(), account.vpcId(),
                        eniId, logicalAddress, namespace.transportAddress(), Set.of(account.groupId()), List.of(group)),
                namespace.helperId(), Map.of());
        return namespace;
    }

    private int connect(Connection connection) throws Exception {
        return execute(connection.source(), "nc", "-z", "-w", "1", connection.target(), "8080");
    }

    private int execute(String container, String... command) throws Exception {
        String id = docker.execCreateCmd(container).withAttachStdout(true).withAttachStderr(true)
                .withCmd(command).exec().getId();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>()) {
            assertTrue(docker.execStartCmd(id).exec(callback).awaitCompletion(5, TimeUnit.SECONDS));
        }
        return docker.inspectExecCmd(id).exec().getExitCodeLong().intValue();
    }

    private static IpPermission permission() {
        return IpPermission.builder().ipProtocol("tcp").fromPort(8080).toPort(8080)
                .ipRanges(range -> range.cidrIp(CIDR)).build();
    }

    private record Account(String id, String region, String vpcId, String groupId, Ec2Client client) {}
    private record Connection(String source, String target) {}
}
