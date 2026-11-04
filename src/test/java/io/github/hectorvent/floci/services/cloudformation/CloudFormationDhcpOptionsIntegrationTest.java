package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DhcpConfiguration;
import software.amazon.awssdk.services.ec2.model.DhcpOptions;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudFormationDhcpOptionsIntegrationTest.StrictResources.class)
class CloudFormationDhcpOptionsIntegrationTest {
    public static class StrictResources implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.cloudformation.allow-stub-unsupported-resource-types", "false",
                    "floci.services.cloudformation.allow-stub-lambda-code", "false");
        }
    }

    private final String name = "dhcp-options-" + UUID.randomUUID();
    private final List<String> externalVpcs = new ArrayList<>();
    private final List<String> externalOptions = new ArrayList<>();
    private CloudFormationClient cfn;
    private Ec2Client ec2;

    @BeforeEach
    void clients() {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
    }

    @AfterEach
    void cleanup() {
        try {
            deleteStack();
            for (String vpc : externalVpcs) {
                ec2.deleteVpc(request -> request.vpcId(vpc));
            }
            for (String options : externalOptions) {
                ec2.deleteDhcpOptions(request -> request.dhcpOptionsId(options));
            }
            assertTrue(ec2.describeVpcs().vpcs().stream().allMatch(vpc -> Boolean.TRUE.equals(vpc.isDefault())),
                    "No VPC created by the stack or fixture may remain");
            assertTrue(customOptions().isEmpty(), "No replaced or rolled-back options may remain");
        } finally {
            ec2.close();
            cfn.close();
        }
    }

    @Test
    void createsActualOptionsAndAssociationThenUpdatesTagsAndReplacesSettings() {
        create("first.internal", "first");
        Map<String, String> first = outputs();
        String id = first.get("OptionsId");
        assertEquals(id, first.get("OptionsAttribute"));
        assertTrue(id.matches("dopt-[0-9a-f]+"));
        assertBinding(first.get("VpcId"), id);
        assertConfiguration(id, "first.internal", "first");

        update("first.internal", "second", template());
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(id, outputs().get("OptionsId"), "A tag update must preserve the DHCP options ID");
        assertConfiguration(id, "first.internal", "second");

        update("second.internal", "third", template());
        awaitStatus("UPDATE_COMPLETE");
        String replacement = outputs().get("OptionsId");
        assertNotEquals(id, replacement, "Changing a DHCP setting requires replacement");
        assertBinding(first.get("VpcId"), replacement);
        assertConfiguration(replacement, "second.internal", "third");
        assertEquals(List.of(replacement), customOptions().stream().map(DhcpOptions::dhcpOptionsId).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedLaterResourceRestoresTheAssociationOptionsAndTags(boolean replaceOptions) throws Exception {
        create("original.internal", "original");
        Map<String, String> before = outputs();
        ObjectNode failed = (ObjectNode) new ObjectMapper().readTree(template());
        ObjectNode subnet = failed.withObject("/Resources").putObject("InvalidSubnet");
        subnet.put("Type", "AWS::EC2::Subnet").put("DependsOn", "Association");
        ObjectNode props = subnet.putObject("Properties");
        props.putObject("VpcId").put("Ref", "Vpc");
        props.put("CidrBlock", "192.0.2.0/24");

        update(replaceOptions ? "replacement.internal" : "original.internal", "attempted", failed.toString());
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");

        assertEquals(before, outputs());
        assertBinding(before.get("VpcId"), before.get("OptionsId"));
        assertConfiguration(before.get("OptionsId"), "original.internal", "original");
        assertEquals(List.of(before.get("OptionsId")), customOptions().stream().map(DhcpOptions::dhcpOptionsId).toList());
    }

    @Test
    void replacingTheAssociationMovesItBetweenVpcsAndRetiresTheOldBinding() {
        String firstVpc = externalVpc("10.91.0.0/16");
        String secondVpc = externalVpc("10.92.0.0/16");
        String options = externalOptions();
        cfn.createStack(request -> request.stackName(name).templateBody(association(firstVpc, options)));
        awaitStatus("CREATE_COMPLETE");
        assertBinding(firstVpc, options);

        cfn.updateStack(request -> request.stackName(name).templateBody(association(secondVpc, options)));
        awaitStatus("UPDATE_COMPLETE");
        assertBinding(firstVpc, "default");
        assertBinding(secondVpc, options);

        deleteStack();
        assertBinding(secondVpc, "default");
        assertEquals(1, customOptions().size(), "An association does not own its externally created options");
    }

    @Test
    void deletingAnOldAssociationPreservesTheNewerBinding() {
        String vpc = externalVpc("10.93.0.0/16");
        String oldOptions = externalOptions();
        String newerOptions = externalOptions();
        cfn.createStack(request -> request.stackName(name).templateBody(association(vpc, oldOptions)));
        awaitStatus("CREATE_COMPLETE");
        ec2.associateDhcpOptions(request -> request.vpcId(vpc).dhcpOptionsId(newerOptions));

        deleteStack();

        assertBinding(vpc, newerOptions);
        assertEquals(2, customOptions().size());
    }

    @Test
    void failedAssociationMoveRestoresBothVpcBindings() throws Exception {
        String firstVpc = externalVpc("10.94.0.0/16");
        String secondVpc = externalVpc("10.95.0.0/16");
        String ownedOptions = externalOptions();
        String previousTargetOptions = externalOptions();
        ec2.associateDhcpOptions(request -> request.vpcId(secondVpc).dhcpOptionsId(previousTargetOptions));
        cfn.createStack(request -> request.stackName(name).templateBody(association(firstVpc, ownedOptions)));
        awaitStatus("CREATE_COMPLETE");
        ObjectNode failed = (ObjectNode) new ObjectMapper().readTree(association(secondVpc, ownedOptions));
        ObjectNode subnet = failed.withObject("/Resources").putObject("InvalidSubnet");
        subnet.put("Type", "AWS::EC2::Subnet").put("DependsOn", "Association");
        subnet.putObject("Properties").put("VpcId", secondVpc).put("CidrBlock", "192.0.2.0/24");

        cfn.updateStack(request -> request.stackName(name).templateBody(failed.toString()));
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");

        assertBinding(firstVpc, ownedOptions);
        assertBinding(secondVpc, previousTargetOptions);
        assertEquals(2, customOptions().size());
    }

    private void create(String domain, String tag) {
        cfn.createStack(request -> request.stackName(name).templateBody(template()).parameters(parameters(domain, tag)));
        awaitStatus("CREATE_COMPLETE");
    }

    private void update(String domain, String tag, String body) {
        cfn.updateStack(request -> request.stackName(name).templateBody(body).parameters(parameters(domain, tag)));
    }

    private static List<Parameter> parameters(String domain, String tag) {
        return List.of(Parameter.builder().parameterKey("Domain").parameterValue(domain).build(),
                Parameter.builder().parameterKey("Name").parameterValue(tag).build());
    }

    private Map<String, String> outputs() {
        return cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst().outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private void assertBinding(String vpc, String options) {
        assertEquals(options, ec2.describeVpcs(request -> request.vpcIds(vpc)).vpcs().getFirst().dhcpOptionsId());
    }

    private void assertConfiguration(String id, String domain, String tag) {
        DhcpOptions options = ec2.describeDhcpOptions(request -> request.dhcpOptionsIds(id)).dhcpOptions().getFirst();
        Map<String, List<String>> configurations = options.dhcpConfigurations().stream().collect(Collectors.toMap(
                DhcpConfiguration::key,
                configuration -> configuration.values().stream().map(value -> value.value()).toList()));
        assertEquals(Map.of("domain-name", List.of(domain), "domain-name-servers", List.of("AmazonProvidedDNS"),
                "ntp-servers", List.of("192.0.2.123"), "netbios-name-servers", List.of("192.0.2.124"),
                "netbios-node-type", List.of("2"), "ipv6-address-preferred-lease-time", List.of("140")), configurations);
        assertEquals(Map.of("Name", tag), options.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value)));
    }

    private List<DhcpOptions> customOptions() {
        return ec2.describeDhcpOptions().dhcpOptions().stream()
                .filter(options -> !"dopt-default".equals(options.dhcpOptionsId())).toList();
    }

    private String externalVpc(String cidr) {
        String id = ec2.createVpc(request -> request.cidrBlock(cidr)).vpc().vpcId();
        externalVpcs.add(id);
        return id;
    }

    private String externalOptions() {
        String id = ec2.createDhcpOptions(request -> request.dhcpConfigurations(configuration ->
                configuration.key("domain-name-servers").values("AmazonProvidedDNS"))).dhcpOptions().dhcpOptionsId();
        externalOptions.add(id);
        return id;
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(expected, cfn.describeStacks(request -> request.stackName(name))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private void deleteStack() {
        cfn.deleteStack(request -> request.stackName(name));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            CloudFormationException missing = assertThrows(CloudFormationException.class,
                    () -> cfn.describeStacks(request -> request.stackName(name)));
            assertEquals("ValidationError", missing.awsErrorDetails().errorCode());
            assertTrue(missing.getMessage().contains("does not exist"));
        });
    }

    private static String association(String vpc, String options) {
        return """
                {"Resources":{"Association":{"Type":"AWS::EC2::VPCDHCPOptionsAssociation",
                  "Properties":{"VpcId":"%s","DhcpOptionsId":"%s"}}}}
                """.formatted(vpc, options);
    }

    private static String template() {
        return """
                {
                  "Parameters":{"Domain":{"Type":"String"},"Name":{"Type":"String"}},
                  "Resources":{
                    "Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.90.0.0/16"}},
                    "Options":{"Type":"AWS::EC2::DHCPOptions","Properties":{
                      "DomainName":{"Ref":"Domain"},"DomainNameServers":{"Fn::Split":[",","AmazonProvidedDNS"]},
                      "NtpServers":["192.0.2.123"],"NetbiosNameServers":["192.0.2.124"],
                      "NetbiosNodeType":2,"Ipv6AddressPreferredLeaseTime":140,
                      "Tags":[{"Key":"Name","Value":{"Ref":"Name"}}]}},
                    "Association":{"Type":"AWS::EC2::VPCDHCPOptionsAssociation",
                      "Properties":{"VpcId":{"Ref":"Vpc"},"DhcpOptionsId":{"Ref":"Options"}}}
                  },
                  "Outputs":{
                    "VpcId":{"Value":{"Ref":"Vpc"}},"OptionsId":{"Value":{"Ref":"Options"}},
                    "OptionsAttribute":{"Value":{"Fn::GetAtt":["Options","DhcpOptionsId"]}}
                  }
                }
                """;
    }
}
