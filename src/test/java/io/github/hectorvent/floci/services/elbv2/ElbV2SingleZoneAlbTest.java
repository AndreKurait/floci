package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.model.AvailabilityZone;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static io.github.hectorvent.floci.services.elbv2.ElbV2PublicationTestSupport.allowPublications;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code floci.services.elbv2.single-zone-alb-accounts}: AWS refuses an Application Load Balancer in
 * fewer than two Availability Zones. The setting lowers that minimum to one zone for the accounts it lists,
 * on CreateLoadBalancer and SetSubnets, for either scheme. Every other account, and every account
 * while the setting is unset, keeps the two-zone rule; Network and Gateway Load Balancers are unchanged.
 */
class ElbV2SingleZoneAlbTest {

    private static final String REGION = "us-west-2";
    private static final String GRANTED = "111111111111";
    private static final String OTHER = "222222222222";
    private static final String TWO_ZONES = "At least two subnets in two different Availability Zones must be specified";

    private ElbV2Service service;
    private Ec2Service ec2Service;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        service = new ElbV2Service();
        ElbV2DataPlane dataPlane = mock(ElbV2DataPlane.class);
        allowPublications(dataPlane);
        service.dataPlane = dataPlane;
        service.healthChecker = mock(ElbV2HealthChecker.class);
        ec2Service = mock(Ec2Service.class);
        service.ec2Service = ec2Service;
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        service.config = config;
        lenient().when(ec2Service.supplyWithSubnetReferencesHeld(anyString(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        lenient().doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(ec2Service).runWithSubnetReferencesHeld(anyString(), any());
        Subnet a = subnet("subnet-a", REGION + "a");
        Subnet a2 = subnet("subnet-a2", REGION + "a");
        Subnet b = subnet("subnet-b", REGION + "b");
        lenient().when(ec2Service.describeSubnets(eq(REGION), eq(List.of("subnet-a")), eq(Map.of())))
                .thenReturn(List.of(a));
        lenient().when(ec2Service.describeSubnets(eq(REGION), eq(List.of("subnet-a", "subnet-a2")), eq(Map.of())))
                .thenReturn(List.of(a, a2));
        lenient().when(ec2Service.describeSubnets(eq(REGION), eq(List.of("subnet-a", "subnet-b")), eq(Map.of())))
                .thenReturn(List.of(a, b));
    }

    private void grant(String... accounts) {
        when(config.services().elbv2().singleZoneAlbAccounts()).thenReturn(Optional.of(List.of(accounts)));
        service.readSingleZoneAlbAccounts();
    }

    private void callAs(String account) {
        service.regionResolver = new RegionResolver(REGION, account);
    }

    @ParameterizedTest
    @ValueSource(strings = {"internal", "internet-facing"})
    void aGrantedAccountCreatesAnApplicationLoadBalancerOnOneSubnet(String scheme) {
        grant(GRANTED);
        callAs(GRANTED);

        LoadBalancer lb = service.createLoadBalancer(REGION, "one-zone", scheme, "application", null,
                List.of("subnet-a"), List.of(), Map.of());

        assertEquals(scheme, lb.getScheme());
        assertEquals(List.of(REGION + "a"), zones(lb));
        assertEquals("vpc-a", lb.getVpcId());
    }

    /** Through the Query API, with SubnetMappings. */
    @Test
    void aGrantedAccountCreatesOneSubnetBalancerThroughTheQueryApi() {
        grant(GRANTED);
        callAs(GRANTED);
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("Name", "query-one-zone");
        params.add("Scheme", "internal");
        params.add("SubnetMappings.member.1.SubnetId", "subnet-a");

        Response response = new ElbV2QueryHandler(service).handle("CreateLoadBalancer", params, REGION);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
    }

    @Test
    void aGrantedAccountSetsAnApplicationLoadBalancerOntoOneSubnet() {
        grant(GRANTED);
        callAs(GRANTED);
        LoadBalancer lb = service.createLoadBalancer(REGION, "shrink", "internet-facing", "application", null,
                List.of("subnet-a", "subnet-b"), List.of(), Map.of());
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("LoadBalancerArn", lb.getLoadBalancerArn());
        params.add("Subnets.member.1", "subnet-a");

        Response response = new ElbV2QueryHandler(service).handle("SetSubnets", params, REGION);

        assertEquals(200, response.getStatus(), (String) response.getEntity());
        assertEquals(List.of(REGION + "a"), zones(describe(lb)));
    }

    /** Neither form at all is still refused for a granted account: one zone is the floor, not none. */
    @Test
    void aGrantedAccountStillNeedsOneSubnet() {
        grant(GRANTED);
        callAs(GRANTED);

        AwsException refusal = assertThrows(AwsException.class, () -> service.createLoadBalancer(REGION,
                "no-subnets", "internal", "application", null, List.of(), List.of(), Map.of()));

        assertEquals("ValidationError", refusal.getErrorCode());
        assertEquals(TWO_ZONES, refusal.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"internal", "internet-facing"})
    void anotherAccountKeepsTheTwoZoneRuleOnCreate(String scheme) {
        grant(GRANTED);
        callAs(OTHER);

        AwsException one = assertThrows(AwsException.class, () -> service.createLoadBalancer(REGION,
                "one-zone", scheme, "application", null, List.of("subnet-a"), List.of(), Map.of()));
        assertEquals("ValidationError", one.getErrorCode());
        assertEquals(TWO_ZONES, one.getMessage());

        AwsException sameZone = assertThrows(AwsException.class, () -> service.createLoadBalancer(REGION,
                "same-zone", scheme, "application", null, List.of("subnet-a", "subnet-a2"), List.of(), Map.of()));
        assertEquals("InvalidConfigurationRequest", sameZone.getErrorCode());
        assertTrue(service.describeLoadBalancers(REGION, null, null, null, null).isEmpty());
    }

    @Test
    void anotherAccountKeepsTheTwoZoneRuleOnSetSubnets() {
        grant(GRANTED);
        callAs(OTHER);
        LoadBalancer lb = service.createLoadBalancer(REGION, "keep", "internal", "application", null,
                List.of("subnet-a", "subnet-b"), List.of(), Map.of());

        AwsException refusal = assertThrows(AwsException.class,
                () -> service.setSubnets(REGION, lb.getLoadBalancerArn(), List.of("subnet-a")));

        assertEquals(TWO_ZONES, refusal.getMessage());
        assertEquals(List.of(REGION + "a", REGION + "b"), zones(describe(lb)));
    }

    /** The default: no account is granted, so the granted account of the other tests is refused. */
    @Test
    void withTheSettingUnsetEveryAccountKeepsTheTwoZoneRule() {
        when(config.services().elbv2().singleZoneAlbAccounts()).thenReturn(Optional.empty());
        service.readSingleZoneAlbAccounts();
        callAs(GRANTED);

        AwsException refusal = assertThrows(AwsException.class, () -> service.createLoadBalancer(REGION,
                "one-zone", "internal", "application", null, List.of("subnet-a"), List.of(), Map.of()));

        assertEquals(TWO_ZONES, refusal.getMessage());
    }

    /** A blank value, as an empty environment variable or a trailing comma gives, lists no account. */
    @Test
    void aBlankSettingListsNoAccount() {
        when(config.services().elbv2().singleZoneAlbAccounts()).thenReturn(Optional.of(List.of("", " ")));
        service.readSingleZoneAlbAccounts();
        callAs(GRANTED);

        AwsException refusal = assertThrows(AwsException.class, () -> service.createLoadBalancer(REGION,
                "one-zone", "internal", "application", null, List.of("subnet-a"), List.of(), Map.of()));

        assertEquals(TWO_ZONES, refusal.getMessage());
    }

    /** Entries are trimmed, so a list written with spaces after its commas grants every account it names. */
    @Test
    void entriesAreTrimmed() {
        grant(OTHER, " " + GRANTED);
        callAs(GRANTED);

        LoadBalancer lb = service.createLoadBalancer(REGION, "trimmed", "internal", "application", null,
                List.of("subnet-a"), List.of(), Map.of());

        assertEquals(List.of(REGION + "a"), zones(lb));
    }

    @Test
    void aNetworkLoadBalancerIsUnchangedByTheGrant() {
        grant(GRANTED);
        callAs(OTHER);

        LoadBalancer lb = service.createLoadBalancer(REGION, "nlb", "internal", "network", null,
                List.of("subnet-a"), List.of(), Map.of());
        assertEquals(List.of(REGION + "a"), zones(lb));

        AwsException none = assertThrows(AwsException.class, () -> service.createLoadBalancer(REGION,
                "nlb-none", "internal", "network", null, List.of(), List.of(), Map.of()));
        assertEquals("At least one subnet must be specified", none.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1111111111112", "abcdefghijkl", "11111111111a", "*"})
    void aSettingThatIsNotAnAccountIdIsRefusedAtStartup(String value) {
        when(config.services().elbv2().singleZoneAlbAccounts()).thenReturn(Optional.of(List.of(value)));

        IllegalStateException error = assertThrows(IllegalStateException.class, service::readSingleZoneAlbAccounts);

        assertTrue(error.getMessage().contains("floci.services.elbv2.single-zone-alb-accounts"), error.getMessage());
    }

    private LoadBalancer describe(LoadBalancer lb) {
        return service.describeLoadBalancers(REGION, List.of(lb.getLoadBalancerArn()), null, null, null).getFirst();
    }

    private static List<String> zones(LoadBalancer lb) {
        return lb.getAvailabilityZones().stream().map(AvailabilityZone::getZoneName).toList();
    }

    private static Subnet subnet(String subnetId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setAvailabilityZone(availabilityZone);
        subnet.setVpcId("vpc-a");
        return subnet;
    }
}
