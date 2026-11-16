package io.floci.aws.services.elbv2;

import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.services.elbv2.model.LoadBalancer;

import java.util.List;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/** Supplies owned resource metadata to composed DNS tests without starting load balancers. */
public final class ElbV2DnsOwnerFixture {
    private ElbV2DnsOwnerFixture() {
    }

    public static void targets(ElbV2Service service, String name, LoadBalancer... targets) {
        when(service.findByDnsName(name)).thenReturn(List.of(targets));
    }

    public static void frontendAddresses(ElbV2FrontendManager frontends, LoadBalancer target,
                                         ClientVpc owner, List<String> addresses) {
        doReturn(addresses).when(frontends).addresses(target, owner);
    }
}
