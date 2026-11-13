package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.DhcpConfiguration;
import io.github.hectorvent.floci.services.ec2.model.DhcpOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Ec2DhcpCfnProvisionerTest {
    private static final String REGION = "us-east-1";
    private static final String OPTIONS = "AWS::EC2::DHCPOptions";
    private static final String ASSOCIATION = "AWS::EC2::VPCDHCPOptionsAssociation";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2DhcpCfnProvisioner provisioner = new Ec2DhcpCfnProvisioner(ec2);

    @Test
    void createsRealOptionsWithEveryConfigurationAndThePublishedGetAtt() throws Exception {
        when(ec2.createDhcpOptions(eq(REGION), any())).thenReturn(options("dopt-created", "example.internal"));
        StackResource resource = resource(OPTIONS, null);
        provisioner.provision(resource, mapper.readTree("""
                {"DomainName":"example.internal","DomainNameServers":["AmazonProvidedDNS"],
                 "NtpServers":["192.0.2.123"],"NetbiosNameServers":["192.0.2.124"],
                 "NetbiosNodeType":2,"Ipv6AddressPreferredLeaseTime":140}
                """), context(null));

        assertEquals("dopt-created", resource.getPhysicalId());
        assertEquals("dopt-created", resource.getAttributes().get("DhcpOptionsId"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DhcpConfiguration>> captured = ArgumentCaptor.forClass(List.class);
        verify(ec2).createDhcpOptions(eq(REGION), captured.capture());
        assertEquals(Map.of("domain-name", List.of("example.internal"),
                "domain-name-servers", List.of("AmazonProvidedDNS"), "ntp-servers", List.of("192.0.2.123"),
                "netbios-name-servers", List.of("192.0.2.124"), "netbios-node-type", List.of("2"),
                "ipv6-address-preferred-lease-time", List.of("140")),
                captured.getValue().stream().collect(Collectors.toMap(
                        DhcpConfiguration::getKey, DhcpConfiguration::getValues)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"DomainName\":\"example.internal\"}",
            "{\"NetbiosNameServers\":[\"192.0.2.124\"]}",
            "{\"DomainNameServers\":[\"AmazonProvidedDNS\"],\"Tags\":[{\"Key\":\"aws:reserved\",\"Value\":\"x\"}]}"
    })
    void invalidConfigurationFailsBeforeCreatingAnything(String json) throws Exception {
        JsonNode props = mapper.readTree(json);
        assertThrows(AwsException.class, () -> provisioner.provision(resource(OPTIONS, null), props, context(null)));
        verifyNoInteractions(ec2);
    }

    @Test
    void replacementKeepsOldOptionsUntilTheStackUpdateCommits() throws Exception {
        DhcpOptions old = options("dopt-old", "old.internal");
        when(ec2.describeDhcpOptions(REGION, List.of("dopt-old"), List.of(), 0, null))
                .thenReturn(new PaginatedResult<>(List.of(old), null));
        when(ec2.createDhcpOptions(eq(REGION), any())).thenReturn(options("dopt-new", "new.internal"));
        StackResource resource = resource(OPTIONS, "dopt-old");

        provisioner.provision(resource, properties("new.internal"), context("dopt-old"));

        assertEquals("dopt-new", resource.getPhysicalId());
        verify(ec2, never()).deleteDhcpOptions(any(), any());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(ec2).deleteDhcpOptions(REGION, "dopt-old");
    }

    @Test
    void unchangedConfigurationReusesTheOptions() throws Exception {
        when(ec2.describeDhcpOptions(REGION, List.of("dopt-old"), List.of(), 0, null))
                .thenReturn(new PaginatedResult<>(List.of(options("dopt-old", "same.internal")), null));
        StackResource resource = resource(OPTIONS, "dopt-old");

        provisioner.provision(resource, properties("same.internal"), context("dopt-old"));

        assertEquals("dopt-old", resource.getPhysicalId());
        verify(ec2, never()).createDhcpOptions(any(), any());
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void failedTaggingRemovesTheJustCreatedOptionsAndReportsTheFailure() throws Exception {
        when(ec2.createDhcpOptions(eq(REGION), any())).thenReturn(options("dopt-created", "same.internal"));
        doThrow(new AwsException("InternalError", "tagging failed", 500))
                .when(ec2).createTags(eq(REGION), eq(List.of("dopt-created")), any());
        JsonNode props = mapper.readTree("""
                {"DomainNameServers":["AmazonProvidedDNS"],"Tags":[{"Key":"Name","Value":"test"}]}
                """);

        AwsException error = assertThrows(AwsException.class, () ->
                provisioner.provision(resource(OPTIONS, null), props, context(null)));

        assertEquals("tagging failed", error.getMessage());
        verify(ec2).deleteDhcpOptions(REGION, "dopt-created");
    }

    @Test
    void associationRollbackUsesThePreviousBindingReturnedByTheAtomicChange() throws Exception {
        when(ec2.associateDhcpOptionsAndGetPrevious(REGION, "dopt-new", "vpc-a")).thenReturn("dopt-old");
        when(ec2.associateDhcpOptionsIfCurrent(REGION, "dopt-old", "vpc-a", "dopt-new")).thenReturn(true);
        StackResource resource = resource(ASSOCIATION, "dopt-old|vpc-a");
        provisioner.provision(resource, mapper.readTree("""
                {"DhcpOptionsId":"dopt-new","VpcId":"vpc-a"}
                """), context("dopt-old|vpc-a"));

        assertEquals("dopt-new|vpc-a", resource.getPhysicalId());
        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("dopt-old|vpc-a", resource.getPhysicalId());
        verify(ec2).associateDhcpOptionsIfCurrent(REGION, "dopt-old", "vpc-a", "dopt-new");
    }

    @Test
    void associationDeletionOnlyReplacesTheBindingOwnedByThatResource() {
        provisioner.delete(ASSOCIATION, "dopt-old|vpc-a", REGION);
        verify(ec2).associateDhcpOptionsIfCurrent(REGION, "default", "vpc-a", "dopt-old");
        verify(ec2, never()).associateDhcpOptions(any(), any(), any());
    }

    @Test
    void deletionIgnoresMissingOptionsButDoesNotHideAnActiveDependency() {
        doThrow(new AwsException("InvalidDhcpOptionsID.NotFound", "gone", 400))
                .when(ec2).deleteDhcpOptions(REGION, "dopt-gone");
        provisioner.delete(OPTIONS, "dopt-gone", REGION);
        doThrow(new AwsException("DependencyViolation", "still associated", 400))
                .when(ec2).deleteDhcpOptions(REGION, "dopt-used");
        AwsException error = assertThrows(AwsException.class, () -> provisioner.delete(OPTIONS, "dopt-used", REGION));
        assertEquals("DependencyViolation", error.getErrorCode());
    }

    private JsonNode properties(String domain) throws Exception {
        return mapper.readTree("""
                {"DomainName":"%s","DomainNameServers":["AmazonProvidedDNS"]}
                """.formatted(domain));
    }

    private static DhcpOptions options(String id, String domain) {
        DhcpOptions options = new DhcpOptions();
        options.setDhcpOptionsId(id);
        options.setConfigurations(List.of(new DhcpConfiguration("domain-name", List.of(domain)),
                new DhcpConfiguration("domain-name-servers", List.of("AmazonProvidedDNS"))));
        return options;
    }

    private static StackResource resource(String type, String id) {
        StackResource resource = new StackResource();
        resource.setResourceType(type);
        resource.setLogicalId("Resource");
        resource.setPhysicalId(id);
        resource.setAttributes(new HashMap<>());
        if (OPTIONS.equals(type) && id != null) {
            resource.getAttributes().put("DhcpOptionsId", id);
        }
        return resource;
    }

    private static ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
        });
        return new ProvisionContext(engine, REGION, "123456789012", "dhcp-options", prior);
    }
}
