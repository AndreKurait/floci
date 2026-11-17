package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AddedPrincipal;
import software.amazon.awssdk.services.ec2.model.AllowedPrincipal;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.ServiceConfiguration;
import software.amazon.awssdk.services.ec2.model.ServiceDetail;
import software.amazon.awssdk.services.ec2.model.ServiceTypeDetail;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.UnsuccessfulItem;
import software.amazon.awssdk.services.ec2.model.VpcEndpointConnection;
import software.amazon.awssdk.services.ec2.model.VpcEndpointType;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancerTypeEnum;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VPC endpoint services (PrivateLink, provider side) over the AWS SDK: the provider's configuration,
 * permissions and connection operations, and a consumer's interface endpoint to the service, across
 * accounts. Twelve-digit access keys name the account they act in.
 */
@QuarkusTest
class Ec2VpcEndpointServiceSdkIntegrationTest {

    private static final String PROVIDER = "000000000411";
    private static final String CONSUMER = "000000000412";
    private static final String OTHER = "000000000413";
    private static final String UNKNOWN_ENDPOINT = "vpce-0123456789abcdef0";

    @Test
    void providerCreatesDescribesModifiesAndDeletesAService() {
        try (Provider provider = new Provider("10.81")) {
            ServiceConfiguration created = provider.ec2.createVpcEndpointServiceConfiguration(r -> r
                    .networkLoadBalancerArns(provider.nlbArn).acceptanceRequired(true)
                    .tagSpecifications(s -> s.resourceType(ResourceType.VPC_ENDPOINT_SERVICE)
                            .tags(Tag.builder().key("team").value("payments").build())))
                    .serviceConfiguration();
            String id = created.serviceId();
            assertTrue(id.matches("vpce-svc-[0-9a-f]{17}"), id);
            assertEquals("com.amazonaws.vpce.us-east-1." + id, created.serviceName());
            assertEquals("Available", created.serviceStateAsString());
            assertEquals(List.of("Interface"), created.serviceType().stream()
                    .map(ServiceTypeDetail::serviceTypeAsString).toList());
            assertTrue(created.acceptanceRequired());
            assertEquals(List.of(provider.nlbArn), created.networkLoadBalancerArns());
            assertEquals(List.of("us-east-1a", "us-east-1b"), created.availabilityZones());
            assertEquals(List.of(id + ".us-east-1.vpce.amazonaws.com"), created.baseEndpointDnsNames());
            assertEquals(List.of("ipv4"), created.supportedIpAddressTypesAsStrings());
            assertEquals("payments", created.tags().getFirst().value());

            assertEquals(List.of(created), provider.ec2.describeVpcEndpointServiceConfigurations(r -> r
                    .serviceIds(id)).serviceConfigurations());
            assertEquals(List.of(id), provider.ec2.describeVpcEndpointServiceConfigurations(r -> r
                    .filters(filter("service-name", created.serviceName()), filter("tag:team", "payments")))
                    .serviceConfigurations().stream().map(ServiceConfiguration::serviceId).toList());
            assertCode("InvalidFilter", () -> provider.ec2.describeVpcEndpointServiceConfigurations(r -> r
                    .filters(filter("owner", PROVIDER))));
            // Another account does not own the service, so it cannot describe its configuration.
            try (Ec2Client other = ec2(OTHER)) {
                assertCode("InvalidVpcEndpointServiceId.NotFound",
                        () -> other.describeVpcEndpointServiceConfigurations(r -> r.serviceIds(id)));
            }

            assertTrue(provider.ec2.modifyVpcEndpointServiceConfiguration(r -> r.serviceId(id)
                    .acceptanceRequired(false).privateDnsName("svc.example.com")
                    .addSupportedIpAddressTypes("ipv6")).returnValue());
            ServiceConfiguration modified = provider.describe(id);
            assertFalse(modified.acceptanceRequired());
            assertEquals("svc.example.com", modified.privateDnsName());
            assertEquals("pendingVerification", modified.privateDnsNameConfiguration().stateAsString());
            assertEquals("TXT", modified.privateDnsNameConfiguration().type());
            assertTrue(modified.privateDnsNameConfiguration().value().startsWith("vpce:"));
            assertTrue(modified.privateDnsNameConfiguration().name().startsWith("_"));
            assertEquals(List.of("ipv4", "ipv6"), modified.supportedIpAddressTypesAsStrings());
            assertCode("InvalidParameterValue", () -> provider.ec2.modifyVpcEndpointServiceConfiguration(r -> r
                    .serviceId(id).removeSupportedRegions("us-east-1")));
            assertCode("InvalidParameterCombination", () -> provider.ec2.modifyVpcEndpointServiceConfiguration(r -> r
                    .serviceId(id).removeNetworkLoadBalancerArns(provider.nlbArn)));

            assertTrue(provider.ec2.deleteVpcEndpointServiceConfigurations(r -> r.serviceIds(id))
                    .unsuccessful().isEmpty());
            assertCode("InvalidVpcEndpointServiceId.NotFound",
                    () -> provider.ec2.describeVpcEndpointServiceConfigurations(r -> r.serviceIds(id)));
            UnsuccessfulItem gone = provider.ec2.deleteVpcEndpointServiceConfigurations(r -> r.serviceIds(id))
                    .unsuccessful().getFirst();
            assertEquals(id, gone.resourceId());
            assertEquals("InvalidVpcEndpointServiceId.NotFound", gone.error().code());
        }
    }

    @Test
    void createNeedsOneKindOfExistingLoadBalancer() {
        try (Provider provider = new Provider("10.82")) {
            assertCode("InvalidParameterCombination",
                    () -> provider.ec2.createVpcEndpointServiceConfiguration(r -> r.acceptanceRequired(true)));
            assertCode("InvalidNetworkLoadBalancerArn.Malformed",
                    () -> provider.ec2.createVpcEndpointServiceConfiguration(r -> r.networkLoadBalancerArns(
                            "arn:aws:elasticloadbalancing:us-east-1:" + PROVIDER + ":loadbalancer/app/web/0123")));
            assertCode("InvalidNetworkLoadBalancerArn.NotFound",
                    () -> provider.ec2.createVpcEndpointServiceConfiguration(r -> r.networkLoadBalancerArns(
                            "arn:aws:elasticloadbalancing:us-east-1:" + PROVIDER + ":loadbalancer/net/none/0123")));
            assertCode("InvalidParameterValue", () -> provider.ec2.createVpcEndpointServiceConfiguration(r -> r
                    .networkLoadBalancerArns(provider.nlbArn).supportedIpAddressTypes("ipv5")));
            assertTrue(provider.ec2.describeVpcEndpointServiceConfigurations(r -> { })
                    .serviceConfigurations().isEmpty());
        }
    }

    @Test
    void aConsumerConnectsOnceAllowedAndTheProviderAcceptsOrRejects() {
        try (Provider provider = new Provider("10.83"); Consumer consumer = new Consumer(CONSUMER, "10.84");
             Consumer other = new Consumer(OTHER, "10.92")) {
            ServiceConfiguration service = provider.ec2.createVpcEndpointServiceConfiguration(r -> r
                    .networkLoadBalancerArns(provider.nlbArn).acceptanceRequired(true)).serviceConfiguration();
            String id = service.serviceId();
            String name = service.serviceName();
            try {
                // Not yet allowed: the consumer can neither discover the service nor connect to it.
                assertCode("InvalidServiceName", () -> consumer.connect(name));
                assertCode("InvalidServiceName",
                        () -> consumer.ec2.describeVpcEndpointServices(r -> r.serviceNames(name)));
                assertTrue(consumer.ec2.describeVpcEndpointServices(r -> { }).serviceDetails().stream()
                        .noneMatch(detail -> name.equals(detail.serviceName())));

                List<AddedPrincipal> added = provider.ec2.modifyVpcEndpointServicePermissions(r -> r.serviceId(id)
                        .addAllowedPrincipals("arn:aws:iam::" + CONSUMER + ":root")).addedPrincipals();
                assertEquals("Account", added.getFirst().principalTypeAsString());
                assertEquals(id, added.getFirst().serviceId());
                AllowedPrincipal allowed = provider.ec2.describeVpcEndpointServicePermissions(r -> r.serviceId(id))
                        .allowedPrincipals().getFirst();
                assertEquals("arn:aws:iam::" + CONSUMER + ":root", allowed.principal());
                assertEquals("Account", allowed.principalTypeAsString());

                ServiceDetail detail = consumer.ec2.describeVpcEndpointServices(r -> r.serviceNames(name))
                        .serviceDetails().getFirst();
                assertEquals(id, detail.serviceId());
                assertEquals(PROVIDER, detail.owner());
                assertTrue(detail.acceptanceRequired());
                assertEquals("Interface", detail.serviceType().getFirst().serviceTypeAsString());
                assertEquals(List.of(id + ".us-east-1.vpce.amazonaws.com"), detail.baseEndpointDnsNames());
                assertTrue(consumer.ec2.describeVpcEndpointServices(r -> r.filters(filter("owner", PROVIDER)))
                        .serviceNames().contains(name));
                // Only the consumer account was allowed.
                assertCode("InvalidServiceName", () -> other.connect(name));

                String endpointId = consumer.connect(name);
                assertEquals("pendingAcceptance", consumer.state(endpointId));
                VpcEndpointConnection connection = provider.ec2.describeVpcEndpointConnections(r -> r
                        .filters(filter("service-id", id))).vpcEndpointConnections().getFirst();
                assertEquals(endpointId, connection.vpcEndpointId());
                assertEquals(CONSUMER, connection.vpcEndpointOwner());
                assertEquals("pendingAcceptance", connection.vpcEndpointStateAsString());
                assertEquals(List.of(provider.nlbArn), connection.networkLoadBalancerArns());
                assertEquals("ExistingVpcEndpointConnections", provider.ec2.deleteVpcEndpointServiceConfigurations(
                        r -> r.serviceIds(id)).unsuccessful().getFirst().error().code());

                List<UnsuccessfulItem> accepted = provider.ec2.acceptVpcEndpointConnections(r -> r.serviceId(id)
                        .vpcEndpointIds(endpointId, UNKNOWN_ENDPOINT)).unsuccessful();
                assertEquals(1, accepted.size());
                assertEquals(UNKNOWN_ENDPOINT, accepted.getFirst().resourceId());
                assertEquals("InvalidVpcEndpoint.NotFound", accepted.getFirst().error().code());
                assertEquals("available", consumer.state(endpointId));
                assertCode("ExistingVpcEndpointConnections", () -> provider.ec2.modifyVpcEndpointServiceConfiguration(
                        r -> r.serviceId(id).removeNetworkLoadBalancerArns(provider.nlbArn)));

                assertTrue(provider.ec2.rejectVpcEndpointConnections(r -> r.serviceId(id).vpcEndpointIds(endpointId))
                        .unsuccessful().isEmpty());
                assertEquals("rejected", consumer.state(endpointId));
                assertEquals(List.of(endpointId), provider.ec2.describeVpcEndpointConnections(r -> r
                        .filters(filter("vpc-endpoint-state", "rejected"), filter("vpc-endpoint-owner", CONSUMER)))
                        .vpcEndpointConnections().stream().map(VpcEndpointConnection::vpcEndpointId).toList());
                assertEquals("InvalidState", provider.ec2.acceptVpcEndpointConnections(r -> r.serviceId(id)
                        .vpcEndpointIds(endpointId)).unsuccessful().getFirst().error().code());
                // A rejected endpoint does not hold the service.
                assertTrue(provider.ec2.deleteVpcEndpointServiceConfigurations(r -> r.serviceIds(id))
                        .unsuccessful().isEmpty());
            } finally {
                consumer.deleteEndpoints();
                provider.deleteService(id);
            }
        }
    }

    @Test
    void ownEndpointsAndServicesWithoutAcceptanceAreAvailableAtOnce() {
        try (Provider provider = new Provider("10.85"); Consumer own = new Consumer(PROVIDER, "10.86");
             Consumer other = new Consumer(OTHER, "10.87")) {
            ServiceConfiguration service = provider.ec2.createVpcEndpointServiceConfiguration(r -> r
                    .networkLoadBalancerArns(provider.nlbArn).acceptanceRequired(true)).serviceConfiguration();
            try {
                // Acceptance applies to requests from other accounts; the owner needs no permission either.
                assertEquals("available", own.state(own.connect(service.serviceName())));
                assertCode("InvalidVpcEndpointType", () -> own.ec2.createVpcEndpoint(r -> r.vpcId(own.vpcId)
                        .serviceName(service.serviceName()).vpcEndpointType(VpcEndpointType.GATEWAY)));

                provider.ec2.modifyVpcEndpointServiceConfiguration(r -> r.serviceId(service.serviceId())
                        .acceptanceRequired(false));
                assertEquals("All", provider.ec2.modifyVpcEndpointServicePermissions(r -> r
                        .serviceId(service.serviceId()).addAllowedPrincipals("*"))
                        .addedPrincipals().getFirst().principalTypeAsString());
                assertEquals("available", other.state(other.connect(service.serviceName())));
                assertEquals(2, provider.ec2.describeVpcEndpointConnections(r -> r
                        .filters(filter("service-id", service.serviceId()), filter("vpc-endpoint-state", "available")))
                        .vpcEndpointConnections().size());
            } finally {
                own.deleteEndpoints();
                other.deleteEndpoints();
                provider.deleteService(service.serviceId());
            }
        }
    }

    @Test
    void permissionsNameAccountsRolesAndUsersByArnWithoutAPath() {
        try (Provider provider = new Provider("10.88")) {
            String id = provider.ec2.createVpcEndpointServiceConfiguration(r -> r
                    .networkLoadBalancerArns(provider.nlbArn)).serviceConfiguration().serviceId();
            try {
                String role = "arn:aws:iam::" + CONSUMER + ":role/app";
                String user = "arn:aws:iam::" + CONSUMER + ":user/dev";
                List<String> types = provider.ec2.modifyVpcEndpointServicePermissions(r -> r.serviceId(id)
                        .addAllowedPrincipals(role, user, "*")).addedPrincipals().stream()
                        .map(AddedPrincipal::principalTypeAsString).toList();
                assertEquals(List.of("Role", "User", "All"), types);
                assertCode("InvalidParameterValue", () -> provider.ec2.modifyVpcEndpointServicePermissions(r -> r
                        .serviceId(id).addAllowedPrincipals("arn:aws:iam::" + CONSUMER + ":role/team/app")));
                for (String unsupported : List.of("arn:aws:iam::" + CONSUMER + ":group/devs", CONSUMER)) {
                    assertCode("UnknownPrincipalType.Unsupported", () -> provider.ec2
                            .modifyVpcEndpointServicePermissions(r -> r.serviceId(id).addAllowedPrincipals(unsupported)));
                }
                provider.ec2.modifyVpcEndpointServicePermissions(r -> r.serviceId(id).removeAllowedPrincipals("*"));
                assertEquals(List.of(role), provider.ec2.describeVpcEndpointServicePermissions(r -> r.serviceId(id)
                        .filters(filter("principal-type", "Role"))).allowedPrincipals().stream()
                        .map(AllowedPrincipal::principal).toList());
                assertEquals(2, provider.ec2.describeVpcEndpointServicePermissions(r -> r.serviceId(id))
                        .allowedPrincipals().size());
                assertCode("InvalidVpcEndpointServiceId.NotFound", () -> provider.ec2
                        .describeVpcEndpointServicePermissions(r -> r.serviceId("vpce-svc-0123456789abcdef0")));
            } finally {
                provider.deleteService(id);
            }
        }
    }

    @Test
    void aUserPrincipalAdmitsThatUserAndNotTheRestOfItsAccount() {
        String userName = "vpce-" + UUID.randomUUID().toString().substring(0, 8);
        try (Provider provider = new Provider("10.89"); Consumer root = new Consumer(CONSUMER, "10.90");
             IamClient iam = AwsTestClients.create(IamClient.builder(), CONSUMER, Region.US_EAST_1)) {
            iam.createUser(r -> r.userName(userName));
            AccessKey key = iam.createAccessKey(r -> r.userName(userName)).accessKey();
            ServiceConfiguration service = provider.ec2.createVpcEndpointServiceConfiguration(r -> r
                    .networkLoadBalancerArns(provider.nlbArn)).serviceConfiguration();
            String userArn = "arn:aws:iam::" + CONSUMER + ":user/" + userName;
            provider.ec2.modifyVpcEndpointServicePermissions(r -> r.serviceId(service.serviceId())
                    .addAllowedPrincipals(userArn));
            try (Ec2Client asUser = AwsTestClients.create(Ec2Client.builder(), key.accessKeyId(),
                    key.secretAccessKey(), Region.US_EAST_1)) {
                assertCode("InvalidServiceName", () -> root.connect(service.serviceName()));
                String endpointId = connect(asUser, root.vpcId, root.subnetId, service.serviceName());
                assertEquals("available", root.state(endpointId));
            } finally {
                root.deleteEndpoints();
                provider.deleteService(service.serviceId());
                iam.deleteAccessKey(r -> r.userName(userName).accessKeyId(key.accessKeyId()));
                iam.deleteUser(r -> r.userName(userName));
            }
        }
    }

    private static String connect(Ec2Client ec2, String vpcId, String subnetId, String serviceName) {
        return ec2.createVpcEndpoint(r -> r.vpcId(vpcId).serviceName(serviceName)
                .vpcEndpointType(VpcEndpointType.INTERFACE).subnetIds(subnetId)).vpcEndpoint().vpcEndpointId();
    }

    private static Ec2Client ec2(String account) {
        return AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
    }

    private static Filter filter(String name, String... values) {
        return Filter.builder().name(name).values(values).build();
    }

    private static void assertCode(String code, Runnable request) {
        Ec2Exception failure = assertThrows(Ec2Exception.class, request::run);
        assertEquals(code, failure.awsErrorDetails().errorCode());
        assertFalse(failure.awsErrorDetails().errorMessage().isBlank());
    }

    /** A provider account's VPC with a subnet in each of two zones, behind a Network Load Balancer. */
    private static final class Provider implements AutoCloseable {
        private final Ec2Client ec2 = ec2(PROVIDER);
        private final ElasticLoadBalancingV2Client elb =
                AwsTestClients.create(ElasticLoadBalancingV2Client.builder(), PROVIDER, Region.US_EAST_1);
        private final String vpcId;
        private final List<String> subnetIds = new ArrayList<>();
        private final String nlbArn;

        private Provider(String prefix) {
            vpcId = ec2.createVpc(r -> r.cidrBlock(prefix + ".0.0/16")).vpc().vpcId();
            for (int i = 0; i < 2; i++) {
                String zone = "us-east-1" + (char) ('a' + i);
                String cidr = prefix + "." + (i + 1) + ".0/24";
                subnetIds.add(ec2.createSubnet(r -> r.vpcId(vpcId).availabilityZone(zone).cidrBlock(cidr))
                        .subnet().subnetId());
            }
            nlbArn = elb.createLoadBalancer(r -> r.name("vpce-" + UUID.randomUUID().toString().substring(0, 8))
                    .type(LoadBalancerTypeEnum.NETWORK).scheme("internal").subnets(subnetIds))
                    .loadBalancers().getFirst().loadBalancerArn();
        }

        private ServiceConfiguration describe(String serviceId) {
            return ec2.describeVpcEndpointServiceConfigurations(r -> r.serviceIds(serviceId))
                    .serviceConfigurations().getFirst();
        }

        private void deleteService(String serviceId) {
            if (serviceId != null) {
                ec2.deleteVpcEndpointServiceConfigurations(r -> r.serviceIds(serviceId));
            }
        }

        @Override
        public void close() {
            try {
                elb.deleteLoadBalancer(r -> r.loadBalancerArn(nlbArn));
                subnetIds.forEach(subnetId -> ec2.deleteSubnet(r -> r.subnetId(subnetId)));
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            } finally {
                elb.close();
                ec2.close();
            }
        }
    }

    /** A consumer account's VPC and subnet, and the interface endpoints made from it. */
    private static final class Consumer implements AutoCloseable {
        private final Ec2Client ec2;
        private final String vpcId;
        private final String subnetId;

        private Consumer(String account, String prefix) {
            ec2 = ec2(account);
            vpcId = ec2.createVpc(r -> r.cidrBlock(prefix + ".0.0/16")).vpc().vpcId();
            subnetId = ec2.createSubnet(r -> r.vpcId(vpcId).availabilityZone("us-east-1a")
                    .cidrBlock(prefix + ".1.0/24")).subnet().subnetId();
        }

        private String connect(String serviceName) {
            return Ec2VpcEndpointServiceSdkIntegrationTest.connect(ec2, vpcId, subnetId, serviceName);
        }

        private String state(String endpointId) {
            return ec2.describeVpcEndpoints(r -> r.vpcEndpointIds(endpointId)).vpcEndpoints().getFirst().stateAsString();
        }

        private void deleteEndpoints() {
            List<String> ids = ec2.describeVpcEndpoints(r -> r.filters(filter("vpc-id", vpcId))).vpcEndpoints()
                    .stream().map(endpoint -> endpoint.vpcEndpointId()).toList();
            if (!ids.isEmpty()) {
                ec2.deleteVpcEndpoints(r -> r.vpcEndpointIds(ids));
            }
        }

        @Override
        public void close() {
            try {
                deleteEndpoints();
                ec2.deleteSubnet(r -> r.subnetId(subnetId));
                ec2.deleteVpc(r -> r.vpcId(vpcId));
            } finally {
                ec2.close();
            }
        }
    }
}
