package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.net.VpcRoutingManager;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@QuarkusTest
class Ec2VpcRoutingFailureIntegrationTest {
    @InjectMock
    VpcRoutingManager routing;

    @Test
    void aRouteWriteReportsRuntimeFailureAndCanBeReconciledAgain() {
        when(routing.enabled()).thenReturn(true);
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "111111112233", Region.US_EAST_1)) {
            String vpc = client.createVpc(request -> request.cidrBlock("10.135.0.0/16")).vpc().vpcId();
            String table = client.createRouteTable(request -> request.vpcId(vpc)).routeTable().routeTableId();
            String gateway = client.createInternetGateway().internetGateway().internetGatewayId();
            try {
                client.attachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
                client.createRoute(request -> request.routeTableId(table)
                        .destinationCidrBlock("0.0.0.0/0").gatewayId(gateway));
                doThrow(new IllegalStateException("Cannot program the owned network namespace"))
                        .when(routing).reconcile();
                Ec2Exception failure = assertThrows(Ec2Exception.class, () -> client.replaceRoute(
                        request -> request.routeTableId(table).destinationCidrBlock("0.0.0.0/0").gatewayId(gateway)));
                assertEquals(500, failure.statusCode());

                doNothing().when(routing).reconcile();
                client.replaceRoute(request -> request.routeTableId(table)
                        .destinationCidrBlock("0.0.0.0/0").gatewayId(gateway));
                Route recovered = client.describeRouteTables(request -> request.routeTableIds(table))
                        .routeTables().getFirst().routes().stream()
                        .filter(route -> "0.0.0.0/0".equals(route.destinationCidrBlock())).findFirst().orElseThrow();
                assertEquals(gateway, recovered.gatewayId());
                assertEquals("active", recovered.stateAsString());
            } finally {
                doNothing().when(routing).reconcile();
                client.deleteRouteTable(request -> request.routeTableId(table));
                client.detachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
                client.deleteInternetGateway(request -> request.internetGatewayId(gateway));
                client.deleteVpc(request -> request.vpcId(vpc));
            }
        }
    }
}
