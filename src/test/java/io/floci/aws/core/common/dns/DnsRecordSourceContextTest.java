package io.floci.aws.core.common.dns;

import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsRecordSourceContextTest {
    private static final ClientVpc OWNER = new ClientVpc("111122223333", "us-east-1", "vpc-owner");
    private static final ClientVpc OTHER = new ClientVpc("444455556666", "us-east-1", "vpc-owner");
    private static final String NAME = "service.example.test";
    private static final DnsAnswer OWNED = DnsAnswer.records(List.of("10.20.0.8"), 60);
    private static final DnsAnswer UNSCOPED = DnsAnswer.records(List.of("192.0.2.9"), 60);

    @Test
    void receivingListenerDoesNotDiscardAnAuthoritativeProvidersVpc() {
        DnsRecordSource records = scopedSource();
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of(), List.of(records), List.of(),
                List.of(client -> Optional.of(OWNER)));

        assertEquals(Optional.of(OWNED), dns.planQuery(NAME, "172.18.0.8", "127.0.0.1", 1).answer());
    }

    @Test
    void anUnownedVpcCannotFallBackToTheProvidersUnscopedAnswer() {
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of(), List.of(scopedSource()), List.of(),
                List.of(client -> Optional.of(OTHER)));

        assertTrue(dns.planQuery(NAME, "172.18.0.8", "127.0.0.1", 1).answer().isEmpty());
    }

    @Test
    void aliasEntryPointsPreserveTheProviderOrigin() {
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of(), List.of(scopedSource()));

        assertEquals(Optional.of(OWNED), dns.resolveAliasTargetInVpc(NAME, "ZONE", 1, OWNER));
        assertEquals(Optional.of(OWNED), dns.resolveAliasTarget(NAME, "ZONE", 1, "127.0.0.1", OWNER));
        assertTrue(dns.resolveAliasTarget(NAME, "ZONE", 1, "127.0.0.1", OTHER).isEmpty());
    }

    private static DnsRecordSource scopedSource() {
        return new DnsRecordSource() {
            @Override
            public Optional<DnsAnswer> resolveIpv4(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolveAuthoritative(String name, int type) {
                return Optional.of(UNSCOPED);
            }

            @Override
            public Optional<DnsAnswer> resolveAuthoritative(String name, int type, ClientVpc origin) {
                return OWNER.equals(origin) ? Optional.of(OWNED) : Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type) {
                return Optional.of(UNSCOPED);
            }

            @Override
            public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type, ClientVpc origin) {
                return OWNER.equals(origin) ? Optional.of(OWNED) : Optional.empty();
            }
        };
    }
}
