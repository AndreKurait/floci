package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsConfigSource;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.route53.Route53DnsRecordSource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real Route 53 private zones behind the real embedded DNS server: a zone for an AWS endpoint name,
 * associated with one VPC, answers that VPC instead of the emulator's spoofed answer.
 */
class EmbeddedDnsRoute53PrivateZoneTest {
    private static final String ACCOUNT = "000000000000";
    private static final String CONTROL = "172.17.0.1";
    private static final String ASSOCIATED = "10.243.0.10";
    private static final String OTHER_VPC = "10.87.1.10";
    private static final String NON_VPC = "172.17.0.20";
    private static final String API = EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS;

    private final Route53Service route53 = newService();
    private final DnsLookupHelper upstream = Mockito.mock(DnsLookupHelper.class);
    private final EmbeddedDnsServer dns = server();

    @Test
    void anAssociatedZoneAnswersAnAwsEndpointNameForItsVpcOnly() {
        String name = "safe-dynamic-config-regional.us-east-1.amazonaws.com";
        String zone = privateZone(name, "vpc-sdpds");
        record(zone, name, "A", "198.51.100.2");

        assertEquals(List.of("198.51.100.2"), addresses(name, ASSOCIATED));
        assertEquals(List.of(API), addresses(name, OTHER_VPC));
        assertEquals(List.of(CONTROL), addresses(name, NON_VPC));
    }

    @Test
    void aCentralizedEndpointZoneChasesItsTargetToTheEmulatorNotUpstream() {
        String name = "s3.us-east-1.amazonaws.com";
        String target = "vpce-0abc-1234.s3.us-east-1.vpce.amazonaws.com";
        String zone = privateZone(name, "vpc-sdpds");
        record(zone, name, "CNAME", target + ".");
        alias(zone, "bucket." + name, target);

        assertEquals(List.of(API), addresses(name, ASSOCIATED));
        assertEquals(List.of(API), addresses("bucket." + name, ASSOCIATED));
        Mockito.verifyNoInteractions(upstream);
    }

    @Test
    void anOrdinaryPrivateZoneCnameToAnAwsNameGetsTheEmulatorNotUpstream() {
        String zone = privateZone("corp.internal", "vpc-sdpds");
        record(zone, "db.corp.internal", "CNAME", "mydb.abc.us-east-1.rds.amazonaws.com.");

        assertEquals(List.of(API), addresses("db.corp.internal", ASSOCIATED));
        Mockito.verifyNoInteractions(upstream);
    }

    @Test
    void withSpoofingOffAPrivateZoneCnameToAnAwsNameStillGoesUpstream() {
        Mockito.when(upstream.resolveIpv4("mydb.abc.us-east-1.rds.amazonaws.com")).thenReturn(List.of("52.0.0.5"));
        EmbeddedDnsServer unspoofed = server(false);
        String zone = privateZone("corp.internal", "vpc-sdpds");
        record(zone, "db.corp.internal", "CNAME", "mydb.abc.us-east-1.rds.amazonaws.com.");

        assertEquals(List.of("52.0.0.5"),
                unspoofed.planQuery("db.corp.internal", ASSOCIATED, CONTROL, 1).answer().orElseThrow().addresses());
    }

    private EmbeddedDnsServer server() {
        return server(true);
    }

    private EmbeddedDnsServer server(boolean spoofed) {
        List<DnsRecordSource> sources = new ArrayList<>();
        DnsClientVpcSource clients = address -> switch (address) {
            case ASSOCIATED -> Optional.of(new DnsClientVpcSource.ClientVpc(ACCOUNT, "us-east-1", "vpc-sdpds"));
            case OTHER_VPC -> Optional.of(new DnsClientVpcSource.ClientVpc(ACCOUNT, "us-east-1", "vpc-caller"));
            default -> Optional.empty();
        };
        EmbeddedDnsServer server = new EmbeddedDnsServer(
                spoofed ? List.copyOf(TlsConfigSource.awsEndpointSuffixes()) : List.of(),
                sources, List.of(), List.of(clients), spoofed);
        sources.add(new Route53DnsRecordSource(route53, null, upstream, server));
        return server;
    }

    private List<String> addresses(String name, String client) {
        return dns.planQuery(name, client, CONTROL, 1).answer().orElseThrow().addresses();
    }

    private String privateZone(String name, String vpc) {
        return route53.createHostedZone(name + ".", UUID.randomUUID().toString(), "private",
                new VpcAssociation(vpc, "us-east-1")).zone().getId();
    }

    private void record(String zone, String name, String type, String value) {
        ResourceRecordSet set = new ResourceRecordSet();
        set.setName(name);
        set.setType(type);
        set.setTtl(60L);
        set.setRecords(List.of(new ResourceRecord(value)));
        route53.changeResourceRecordSets(zone, List.of(Map.of("action", "CREATE", "rrs", set)), null);
    }

    private void alias(String zone, String name, String target) {
        ResourceRecordSet set = new ResourceRecordSet();
        set.setName(name);
        set.setType("A");
        AliasTarget alias = new AliasTarget();
        alias.setDnsName(target);
        alias.setHostedZoneId("Z7HUB22UULQXV");
        alias.setEvaluateTargetHealth(false);
        set.setAliasTarget(alias);
        route53.changeResourceRecordSets(zone, List.of(Map.of("action", "CREATE", "rrs", set)), null);
    }

    private static Route53Service newService() {
        StorageFactory storage = Mockito.mock(StorageFactory.class);
        Mockito.when(storage.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(config.defaultAccountId()).thenReturn(ACCOUNT);
        Mockito.when(config.services().route53().defaultNameserver1()).thenReturn(Optional.of("ns-1.awsdns-00.com."));
        Mockito.when(config.services().route53().defaultNameserver2()).thenReturn(Optional.of("ns-2.awsdns-00.net."));
        Mockito.when(config.services().route53().defaultNameserver3()).thenReturn(Optional.of("ns-3.awsdns-00.org."));
        Mockito.when(config.services().route53().defaultNameserver4()).thenReturn(Optional.of("ns-4.awsdns-00.co.uk."));
        Mockito.when(config.services().route53().vpcAssociationControlPlaneDelayMs()).thenReturn(0L);
        return new Route53Service(storage, config, new RegionResolver("us-east-1", ACCOUNT), Mockito.mock(Ec2Service.class));
    }
}
