package io.floci.aws.core.common.dns;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsConfigSource;
import io.floci.aws.core.common.DomainNames;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Route 53 private hosted zone associated with a VPC answers for that VPC ahead of public
 * resolution, including for an AWS endpoint name: on AWS this is how a VPC is pointed at a
 * centralized endpoint for a service name. Floci's AWS-endpoint spoofing stands in for public
 * resolution, so it must yield to such a zone for the associated VPC, and only for that VPC.
 */
class EmbeddedDnsPrivateZoneAwsNameTest {
    private static final String NAME = "lambda.eu-west-1.amazonaws.com";
    private static final List<String> NAMES = List.of(NAME, "safe-dynamic-config-regional.eu-west-1.amazonaws.com");
    private static final String ASSOCIATED = "10.243.0.10";
    private static final String OTHER_VPC = "10.87.1.10";
    private static final String NON_VPC = "172.17.0.20";
    private static final String CONTROL = "172.17.0.1";
    private static final DnsClientVpcSource.ClientVpc ASSOCIATED_VPC =
            new DnsClientVpcSource.ClientVpc("222222222222", "eu-west-1", "vpc-associated");
    private static final DnsClientVpcSource.ClientVpc CALLER =
            new DnsClientVpcSource.ClientVpc("111111111111", "eu-west-1", "vpc-caller");

    /** A private zone for {@link #NAME} associated only with {@link #ASSOCIATED_VPC}. */
    private static final DnsRecordSource PRIVATE_ZONE = new DnsRecordSource() {
        @Override
        public Optional<DnsAnswer> resolveIpv4(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<DnsAnswer> resolvePrivate(String name, int type, DnsClientVpcSource.ClientVpc origin) {
            if (!ASSOCIATED_VPC.equals(origin) || !DomainNames.isWithin(name.toLowerCase(), "eu-west-1.amazonaws.com")) {
                return Optional.empty();
            }
            if (NAMES.stream().noneMatch(candidate -> candidate.equalsIgnoreCase(name))) {
                return Optional.of(DnsAnswer.nxDomain());
            }
            return Optional.of(type == 1 ? DnsAnswer.records(List.of("198.51.100.2"), 60) : DnsAnswer.noData());
        }
    };

    /** A source that answers an AWS name for every client, as a public Cloud Map namespace could. */
    private static final DnsRecordSource UNSCOPED = new DnsRecordSource() {
        @Override
        public Optional<DnsAnswer> resolveIpv4(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<DnsAnswer> resolve(String name, int type, DnsClientVpcSource.ClientVpc origin) {
            return DomainNames.isWithin(name.toLowerCase(), "us-east-1.amazonaws.com")
                    ? Optional.of(DnsAnswer.nxDomain()) : Optional.empty();
        }
    };

    @Test
    void associatedVpcReceivesItsPrivateZoneAnswerForAnAwsName() {
        for (String name : NAMES) {
            for (boolean publicApi : new boolean[]{true, false}) {
                assertEquals(List.of("198.51.100.2"), addresses(server(publicApi), name, ASSOCIATED),
                        "The associated VPC must not receive the emulator's spoofed answer (public API " + publicApi + ")");
            }
        }
    }

    @Test
    void otherClientsKeepTheEmulatorAnswer() {
        for (String name : NAMES) {
            EmbeddedDnsServer dns = server(true);
            assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS), addresses(dns, name, OTHER_VPC));
            assertEquals(List.of(CONTROL), addresses(dns, name, NON_VPC));
            assertEquals(List.of(CONTROL), addresses(server(false), name, OTHER_VPC));
        }
    }

    @Test
    void aNameTheZoneCoversButDoesNotHoldIsNxdomainNotTheSpoof() {
        DnsAnswer answer = server(true).planQuery("sts.eu-west-1.amazonaws.com", ASSOCIATED, CONTROL, 1)
                .answer().orElseThrow();
        assertTrue(answer.isEmpty(), "A covering private zone answers for every name below it");
        assertFalse(answer.nameExists());
        assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS),
                addresses(server(true), "sts.us-east-1.amazonaws.com", ASSOCIATED),
                "Names outside the zone keep the emulator answer for the associated VPC");
    }

    @Test
    void otherRecordTypesComeFromTheZone() {
        for (String name : NAMES) {
            DnsAnswer absent = server(true).planQuery("sts.eu-west-1.amazonaws.com", ASSOCIATED, CONTROL, 28)
                    .answer().orElseThrow();
            assertTrue(absent.isEmpty());
            assertFalse(absent.nameExists(), "AAAA below the zone for a name it does not hold is NXDOMAIN, not the spoof's NODATA");
            DnsAnswer held = server(true).planQuery(name, ASSOCIATED, CONTROL, 28).answer().orElseThrow();
            assertTrue(held.isEmpty());
            assertTrue(held.nameExists(), "AAAA for a name holding only A is NODATA from the zone");
        }
    }

    @Test
    void onlyVpcScopedSourcesOverrideTheSpoof() {
        EmbeddedDnsServer dns = server(true, List.of(PRIVATE_ZONE, UNSCOPED));
        assertEquals(List.of(EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS),
                addresses(dns, "sts.us-east-1.amazonaws.com", ASSOCIATED),
                "A source that answers every client must not break AWS names for VPC clients alone");
    }

    @Test
    void flociOwnedAndEc2InternalNamesAreNotOverridden() {
        DnsRecordSource everything = new DnsRecordSource() {
            @Override
            public Optional<DnsAnswer> resolveIpv4(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolvePrivate(String name, int type, DnsClientVpcSource.ClientVpc origin) {
                return Optional.of(DnsAnswer.nxDomain());
            }
        };
        EmbeddedDnsServer dns = server(true, List.of(everything));
        assertEquals(List.of(CONTROL), addresses(dns, "bucket.localhost.floci.io", ASSOCIATED));
        assertEquals(List.of("10.243.0.10"), addresses(dns, "ip-10-243-0-10.ec2.internal", ASSOCIATED));
    }

    private static EmbeddedDnsServer server(boolean publicApi) {
        return server(publicApi, List.of(PRIVATE_ZONE));
    }

    private static EmbeddedDnsServer server(boolean publicApi, List<DnsRecordSource> sources) {
        DnsClientVpcSource clients = address -> switch (address) {
            case ASSOCIATED -> Optional.of(ASSOCIATED_VPC);
            case OTHER_VPC -> Optional.of(CALLER);
            default -> Optional.empty();
        };
        return new EmbeddedDnsServer(List.copyOf(TlsConfigSource.awsEndpointSuffixes()),
                sources, List.of(), List.of(clients), publicApi);
    }

    private static List<String> addresses(EmbeddedDnsServer server, String name, String client) {
        return server.planQuery(name, client, CONTROL, 1).answer().orElseThrow().addresses();
    }
}
