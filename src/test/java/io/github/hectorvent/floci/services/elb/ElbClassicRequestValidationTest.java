package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A value the load balancer could never run with is refused when it is sent, with the error a
 * client can act on, instead of being stored and failing later where no caller sees why.
 */
class ElbClassicRequestValidationTest {

    private static final String REGION = "us-west-2";

    private ElbClassicService service;

    @BeforeEach
    void setUp() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        when(ec2Service.describeSubnets(anyString(), anyList(), any())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            return ids.stream().map(id -> {
                Subnet subnet = new Subnet();
                subnet.setSubnetId(id);
                subnet.setVpcId("vpc-1");
                subnet.setAvailabilityZone(REGION + id.substring(id.length() - 1));
                return subnet;
            }).toList();
        });
        service = new ElbClassicService(ec2Service, mock(ElbClassicHealthChecker.class), null, null);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, 70000})
    void aListenerOnAPortThatDoesNotExistIsRefused(int loadBalancerPort) {
        AwsException refused = assertThrows(AwsException.class, () -> create("web", listener(loadBalancerPort)));

        assertEquals("ValidationError", refused.getErrorCode());
        assertEquals(400, refused.getHttpStatus());
        assertTrue(service.describeLoadBalancers(REGION, List.of()).isEmpty(), "nothing was created");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 80, 65535})
    void aListenerOnAnyRealPortIsAccepted(int loadBalancerPort) {
        ClassicLoadBalancer created = create("web", listener(loadBalancerPort));

        assertEquals(loadBalancerPort, created.getListeners().getFirst().getLoadBalancerPort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"8080", "FTP:21", "HTTP:eighty/", "TCP:", "TCP:0", "SSL:65536"})
    void aHealthCheckTargetTheCheckerCannotReadIsRefused(String target) {
        create("web", listener(80));
        ClassicHealthCheck check = ClassicHealthCheck.defaults();
        check.setTarget(target);

        AwsException refused = assertThrows(AwsException.class,
                () -> service.configureHealthCheck(REGION, "web", check));

        assertEquals("ValidationError", refused.getErrorCode());
        assertEquals(400, refused.getHttpStatus());
        assertEquals(ClassicHealthCheck.defaults().getTarget(),
                service.requireLoadBalancer(REGION, "web").getHealthCheck().getTarget(),
                "the health check in force is unchanged");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP:8080/health", "https:443/", "TCP:1", "SSL:65535"})
    void aHealthCheckTargetTheCheckerCanReadIsAccepted(String target) {
        create("web", listener(80));
        ClassicHealthCheck check = ClassicHealthCheck.defaults();
        check.setTarget(target);

        service.configureHealthCheck(REGION, "web", check);

        assertEquals(target, service.requireLoadBalancer(REGION, "web").getHealthCheck().getTarget());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 4001})
    void anIdleTimeoutOutsideOneToFourThousandSecondsIsRefused(int idleTimeout) {
        ClassicLoadBalancer created = create("web", listener(80));
        int inForce = created.getAttributes().getIdleTimeout();

        AwsException refused = assertThrows(AwsException.class,
                () -> service.modifyLoadBalancerAttributes(REGION, "web", idleTimeout(idleTimeout)));

        assertEquals("InvalidConfigurationRequest", refused.getErrorCode());
        assertEquals(400, refused.getHttpStatus());
        assertEquals(inForce, service.requireLoadBalancer(REGION, "web").getAttributes().getIdleTimeout());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4000})
    void anIdleTimeoutOfOneToFourThousandSecondsIsStored(int idleTimeout) {
        create("web", listener(80));

        service.modifyLoadBalancerAttributes(REGION, "web", idleTimeout(idleTimeout));

        assertEquals(idleTimeout, service.requireLoadBalancer(REGION, "web").getAttributes().getIdleTimeout());
    }

    private ClassicLoadBalancer create(String name, ClassicListener listener) {
        return service.createLoadBalancer(REGION, name, List.of(listener), List.of(), List.of("subnet-a"),
                List.of("sg-1"), null, Map.of());
    }

    private static ClassicListener listener(int loadBalancerPort) {
        ClassicListener listener = new ClassicListener();
        listener.setProtocol("HTTP");
        listener.setLoadBalancerPort(loadBalancerPort);
        listener.setInstanceProtocol("HTTP");
        listener.setInstancePort(8080);
        return listener;
    }

    private static ElbClassicService.AttributeUpdate idleTimeout(int seconds) {
        return new ElbClassicService.AttributeUpdate(null, null, null, null, null, null, null, seconds, Map.of());
    }
}
