package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A listener port that is not a number is the caller's mistake: ValidationError, not a server fault. */
class ElbClassicNumberParameterTest {

    @Test
    void aListenerPortToDeleteThatIsNotANumberIsAnsweredWithValidationError() {
        ElbClassicService service = mock(ElbClassicService.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().elb().enabled()).thenReturn(true);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("LoadBalancerName", "numbers");
        params.putSingle("LoadBalancerPorts.member.1", "eighty");

        Response response = new ElbClassicQueryHandler(service, config)
                .handle("DeleteLoadBalancerListeners", params, "us-west-2");

        assertEquals(400, response.getStatus());
        assertThat(response.getEntity().toString(), containsString("<Code>ValidationError</Code>"));
        assertThat(response.getEntity().toString(), containsString("eighty&apos; is not a valid integer."));
        verifyNoInteractions(service);
    }
}
