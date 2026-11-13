package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetHealth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DescribeTargetHealth names the port the health checks go to, which need not be the traffic port. */
class ElbV2TargetHealthCheckPortTest {

    private static final String REGION = "us-west-2";

    private ElbV2Service service;

    @BeforeEach
    void setUp() {
        ElbV2HealthChecker healthChecker = mock(ElbV2HealthChecker.class);
        when(healthChecker.getHealth(anyString(), anyString(), anyInt()))
                .thenReturn(new ElbV2HealthChecker.TargetHealthStatus("initial", "Elb.RegistrationInProgress",
                        "Target registration is in progress"));
        service = ElbV2ServiceTest.serviceWithStorage(ElbV2ServiceTest.sharedStorageFactory(),
                mock(ElbV2DataPlane.class), healthChecker);
    }

    @Test
    void theHealthCheckPortOfTheGroupIsReportedNotTheTrafficPort() {
        assertEquals("8081", healthCheckPortReportedFor("8081"));
    }

    @Test
    void aGroupCheckingTheTrafficPortReportsTheTrafficPort() {
        assertEquals("80", healthCheckPortReportedFor("traffic-port"));
    }

    private String healthCheckPortReportedFor(String healthCheckPort) {
        String targetGroupArn = service.createTargetGroup(REGION, "checked", "HTTP", "HTTP1", 80, "vpc-a", "ip",
                "HTTP", healthCheckPort, true, "/ready", 30, 5, 5, 2, "200", "ipv4", Map.of()).getTargetGroupArn();
        TargetDescription target = new TargetDescription();
        target.setId("10.0.0.10");
        service.registerTargets(REGION, targetGroupArn, List.of(target));
        List<TargetHealth> health = service.describeTargetHealth(REGION, targetGroupArn, null);
        return health.getFirst().getHealthCheckPort();
    }
}
