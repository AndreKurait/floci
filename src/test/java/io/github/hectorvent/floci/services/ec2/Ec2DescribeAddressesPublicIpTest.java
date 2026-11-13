package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

/** DescribeAddresses with PublicIp.N answers with the named addresses and no other. */
class Ec2DescribeAddressesPublicIpTest {

    private static final String REGION = "us-east-1";

    @Test
    void onlyTheAddressNamedByItsPublicIpIsDescribed() {
        Ec2Service service = Ec2ServiceFixtures.mockModeService();
        Ec2QueryHandler handler = new Ec2QueryHandler(service, mock(EmulatorConfig.class),
                mock(FlowLogService.class), mock(Ec2EbsEncryptionService.class),
                mock(Ec2BlockPublicAccessService.class), mock(Ec2IpamService.class),
                new RegionResolver(REGION, "000000000000"));
        Address other = service.allocateAddress(REGION);
        Address named = service.allocateAddress(REGION);
        assertNotEquals(other.getPublicIp(), named.getPublicIp());
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("PublicIp.1", named.getPublicIp());

        Response response = handler.handle("DescribeAddresses", params, REGION);

        assertEquals(200, response.getStatus());
        String answer = response.getEntity().toString();
        assertThat(answer, containsString(named.getAllocationId()));
        assertThat(answer, not(containsString(other.getAllocationId())));
    }
}
