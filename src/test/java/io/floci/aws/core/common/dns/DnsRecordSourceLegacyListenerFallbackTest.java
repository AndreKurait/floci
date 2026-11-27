package io.floci.aws.core.common.dns;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class DnsRecordSourceLegacyListenerFallbackTest {
    private static final DnsClientVpcSource.ClientVpc ORIGIN =
            new DnsClientVpcSource.ClientVpc("111111111111", "us-east-1", "vpc-test");

    @Test
    void legacyListenerAwareSourceKeepsItsAnswerForARegisteredVpcCaller() {
        DnsAnswer answer = DnsAnswer.records(List.of("127.0.0.4"), 60);
        DnsRecordSource source = new DnsRecordSource() {
            @Override
            public Optional<DnsAnswer> resolveIpv4(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<DnsAnswer> resolveAliasTarget(String name, String zone, int type, String listener) {
                return "127.0.0.4".equals(listener) ? Optional.of(answer) : Optional.empty();
            }
        };
        assertSame(answer, source.resolveAliasTarget("resource.example", "zone", 1, "127.0.0.4", ORIGIN)
                .orElseThrow());
    }

    @Test
    void scopedRefusalAndNoDataCannotFallBackToALegacyAddress() {
        for (DnsAnswer scoped : List.of(DnsAnswer.refused(), DnsAnswer.noData())) {
            AtomicBoolean fallback = new AtomicBoolean();
            DnsRecordSource source = new DnsRecordSource() {
                @Override
                public Optional<DnsAnswer> resolveIpv4(String name) {
                    return Optional.empty();
                }

                @Override
                public Optional<DnsAnswer> resolveAliasTargetInVpc(
                        String name, String zone, int type, DnsClientVpcSource.ClientVpc origin) {
                    return Optional.of(scoped);
                }

                @Override
                public Optional<DnsAnswer> resolveAliasTarget(String name, String zone, int type, String listener) {
                    fallback.set(true);
                    return Optional.of(DnsAnswer.records(List.of("127.0.0.4"), 60));
                }
            };
            assertSame(scoped, source.resolveAliasTarget("resource.example", "zone", 1, "127.0.0.4", ORIGIN)
                    .orElseThrow());
            assertFalse(fallback.get());
        }
    }
}
