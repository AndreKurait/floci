package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RunInstances accepts the launch-time public-IP override in two wire shapes:
 * the primary network-interface spec (what Terraform sends) and the legacy
 * top-level parameter (what the CLI and older SDKs send). Both must reach
 * Ec2Service; absent must stay null so the subnet's MapPublicIpOnLaunch
 * default still applies.
 */
class Ec2RunInstancesPublicIpParamTest {

    private static final String REGION = "us-east-1";

    @Test
    void topLevelAssociatePublicIpAddressReachesTheService() {
        assertForwarded(params("AssociatePublicIpAddress", "true"), Boolean.TRUE);
        assertForwarded(params("AssociatePublicIpAddress", "false"), Boolean.FALSE);
    }

    @Test
    void networkInterfaceSpecStillReachesTheService() {
        assertForwarded(params("NetworkInterface.1.AssociatePublicIpAddress", "true"), Boolean.TRUE);
        assertForwarded(params("NetworkInterface.1.AssociatePublicIpAddress", "false"), Boolean.FALSE);
    }

    @Test
    void networkInterfaceSpecWinsWhenBothArePresent() {
        MultivaluedMap<String, String> p = params("NetworkInterface.1.AssociatePublicIpAddress", "false");
        p.putSingle("AssociatePublicIpAddress", "true");
        assertForwarded(p, Boolean.FALSE);
    }

    @Test
    void absentStaysNullSoTheSubnetDefaultApplies() {
        assertForwarded(baseParams(), null);
    }

    @Test
    void templateTagsApplyOnlyToCreatedPrimaryBeforeRequestOverrides() {
        assertTemplateTagTargets(false, false);
    }

    @Test
    void templateTagsDoNotApplyToTemplateSelectedExistingInterface() {
        assertTemplateTagTargets(true, false);
    }

    @Test
    void templateTagsDoNotApplyToRequestSelectedExistingInterface() {
        assertTemplateTagTargets(false, true);
    }

    @Test
    void combinedTemplateAndRequestInterfaceTagsRejectBeforeLaunch() {
        assertInterfaceTagAdmission(50, "additional", false, false, true);
    }

    @Test
    void requestOverrideAtInterfaceTagLimitStillLaunches() {
        assertInterfaceTagAdmission(50, "key0", false, false, false);
    }

    @Test
    void combinedInterfaceTagsAtLimitStillLaunch() {
        assertInterfaceTagAdmission(49, "additional", false, false, false);
    }

    @Test
    void templateTagsExcludedForTemplateSuppliedInterface() {
        assertInterfaceTagAdmission(50, "additional", true, false, false);
    }

    @Test
    void templateTagsExcludedForRequestSuppliedInterface() {
        assertInterfaceTagAdmission(50, "additional", false, true, false);
    }

    @Test
    void requestOnlyInterfaceTagsStillLaunch() {
        assertInterfaceTagAdmission(0, "additional", false, false, false);
    }

    private void assertInterfaceTagAdmission(int templateCount, String requestKey,
                                             boolean templateExisting, boolean requestExisting, boolean rejected) {
        Ec2Service service = mock(Ec2Service.class);
        LaunchTemplateData data = new LaunchTemplateData();
        LaunchTemplateData.NetworkInterface primary = new LaunchTemplateData.NetworkInterface();
        primary.setDeviceIndex(0);
        if (templateExisting) {
            primary.setNetworkInterfaceId("eni-primary");
        }
        data.setNetworkInterfaces(List.of(primary));
        List<Tag> tags = new ArrayList<>();
        for (int i = 0; i < templateCount; i++) {
            tags.add(new Tag("key" + i, "template"));
        }
        data.setTagSpecifications(List.of(new LaunchTemplateData.TagSpecification("network-interface", tags)));
        when(service.resolveLaunchTemplateData(eq(REGION), eq("lt-tags"), nullable(String.class),
                nullable(String.class), eq(true))).thenReturn(data);
        when(service.runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                nullable(Boolean.class), nullable(String.class), anyInt(), nullable(String.class),
                nullable(LaunchTemplateData.MetadataOptions.class), nullable(String.class), nullable(String.class), eq(false),
                nullable(String.class), nullable(String.class), nullable(List.class), eq(false), nullable(String.class),
                nullable(LaunchTemplateData.CapacityReservationSpecification.class), nullable(Boolean.class)))
                .thenAnswer(invocation -> {
                    if (rejected) {
                        throw new AssertionError("RunInstances allocated before combined interface tag validation");
                    }
                    return new Reservation();
                });
        MultivaluedMap<String, String> p = baseParams();
        p.putSingle("LaunchTemplate.LaunchTemplateId", "lt-tags");
        if (requestExisting) {
            p.putSingle("NetworkInterface.1.NetworkInterfaceId", "eni-primary");
        }
        p.putSingle("TagSpecification.1.ResourceType", "network-interface");
        p.putSingle("TagSpecification.1.Tag.1.Key", requestKey);
        p.putSingle("TagSpecification.1.Tag.1.Value", "request");
        Ec2QueryHandler handler = new Ec2QueryHandler(service, mock(EmulatorConfig.class),
                mock(FlowLogService.class), mock(Ec2EbsEncryptionService.class),
                mock(Ec2SnapshotBlockPublicAccessService.class), mock(Ec2IpamService.class),
                mock(RegionResolver.class));
        if (rejected) {
            Response response = handler.handle("RunInstances", p, REGION);
            assertEquals(400, response.getStatus());
            assertTrue(response.getEntity().toString().contains("TagLimitExceeded"));
        } else {
            assertEquals(200, handler.handle("RunInstances", p, REGION).getStatus());
        }
    }

    private void assertTemplateTagTargets(boolean templateExisting, boolean requestExisting) {
        Ec2Service service = mock(Ec2Service.class);
        LaunchTemplateData data = new LaunchTemplateData();
        LaunchTemplateData.NetworkInterface primary = new LaunchTemplateData.NetworkInterface();
        primary.setDeviceIndex(0);
        if (templateExisting) {
            primary.setNetworkInterfaceId("eni-primary");
        }
        data.setNetworkInterfaces(List.of(primary));
        List<Tag> templateTags = List.of(new Tag("shared", "template"), new Tag("template", "only"));
        data.setTagSpecifications(List.of(new LaunchTemplateData.TagSpecification("network-interface", templateTags)));
        when(service.resolveLaunchTemplateData(eq(REGION), eq("lt-tags"), nullable(String.class),
                nullable(String.class), eq(true))).thenReturn(data);
        Instance instance = new Instance();
        instance.setInstanceId("i-tags");
        InstanceNetworkInterface secondary = new InstanceNetworkInterface();
        secondary.setNetworkInterfaceId("eni-secondary");
        secondary.setDeviceIndex(1);
        InstanceNetworkInterface created = new InstanceNetworkInterface();
        created.setNetworkInterfaceId("eni-primary");
        created.setDeviceIndex(0);
        instance.setNetworkInterfaces(List.of(secondary, created));
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(instance));
        when(service.runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                nullable(Boolean.class), nullable(String.class), anyInt(), nullable(String.class),
                nullable(LaunchTemplateData.MetadataOptions.class), nullable(String.class), nullable(String.class), eq(false),
                nullable(String.class), nullable(String.class), nullable(List.class), eq(false), nullable(String.class),
                nullable(LaunchTemplateData.CapacityReservationSpecification.class), nullable(Boolean.class)))
                .thenReturn(reservation);
        MultivaluedMap<String, String> p = baseParams();
        p.putSingle("LaunchTemplate.LaunchTemplateId", "lt-tags");
        if (requestExisting) {
            p.putSingle("NetworkInterface.1.NetworkInterfaceId", "eni-primary");
        }
        p.putSingle("TagSpecification.1.ResourceType", "network-interface");
        p.putSingle("TagSpecification.1.Tag.1.Key", "shared");
        p.putSingle("TagSpecification.1.Tag.1.Value", "request");
        Ec2QueryHandler handler = new Ec2QueryHandler(service, mock(EmulatorConfig.class),
                mock(FlowLogService.class), mock(Ec2EbsEncryptionService.class),
                mock(Ec2SnapshotBlockPublicAccessService.class), mock(Ec2IpamService.class),
                mock(RegionResolver.class));
        handler.handle("RunInstances", p, REGION);
        InOrder order = inOrder(service);
        if (templateExisting || requestExisting) {
            verify(service, never()).createTags(REGION, List.of("eni-primary"), templateTags);
        } else {
            order.verify(service).createTags(REGION, List.of("eni-primary"), templateTags);
        }
        ArgumentCaptor<List<Tag>> requestTags = ArgumentCaptor.forClass(List.class);
        order.verify(service).createTags(eq(REGION), eq(List.of("eni-secondary", "eni-primary")), requestTags.capture());
        assertEquals(1, requestTags.getValue().size());
        assertEquals("request", requestTags.getValue().getFirst().getValue());
    }

    private void assertForwarded(MultivaluedMap<String, String> p, Boolean expected) {
        Ec2Service service = mock(Ec2Service.class);
        when(service.runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                nullable(Boolean.class), nullable(String.class), anyInt(), nullable(String.class),
                nullable(LaunchTemplateData.MetadataOptions.class), nullable(String.class), nullable(String.class), eq(false),
                nullable(String.class), nullable(String.class), nullable(List.class), eq(false), nullable(String.class),
                nullable(LaunchTemplateData.CapacityReservationSpecification.class), nullable(Boolean.class)))
                .thenReturn(new Reservation());

        Ec2QueryHandler handler = new Ec2QueryHandler(service, mock(EmulatorConfig.class),
                mock(FlowLogService.class), mock(Ec2EbsEncryptionService.class),
                mock(Ec2SnapshotBlockPublicAccessService.class), mock(Ec2IpamService.class),
                mock(RegionResolver.class));
        handler.handle("RunInstances", p, REGION);

        ArgumentCaptor<Boolean> associatePublicIp = ArgumentCaptor.forClass(Boolean.class);
        verify(service).runInstances(anyString(), nullable(String.class), nullable(String.class),
                anyInt(), anyInt(), nullable(String.class), anyList(), nullable(String.class),
                nullable(String.class), anyList(), nullable(String.class), nullable(String.class),
                associatePublicIp.capture(), nullable(String.class), anyInt(), nullable(String.class),
                nullable(LaunchTemplateData.MetadataOptions.class), nullable(String.class), nullable(String.class), eq(false),
                nullable(String.class), nullable(String.class), nullable(List.class), eq(false), nullable(String.class),
                nullable(LaunchTemplateData.CapacityReservationSpecification.class), nullable(Boolean.class));
        if (expected == null) {
            assertNull(associatePublicIp.getValue(),
                    "an absent override must stay null so the subnet default decides");
        } else {
            assertEquals(expected, associatePublicIp.getValue());
        }
    }

    private static MultivaluedMap<String, String> baseParams() {
        MultivaluedMap<String, String> p = new MultivaluedHashMap<>();
        p.putSingle("ImageId", "ami-0abcdef1234567890");
        p.putSingle("InstanceType", "t3.micro");
        p.putSingle("MinCount", "1");
        p.putSingle("MaxCount", "1");
        return p;
    }

    private static MultivaluedMap<String, String> params(String key, String value) {
        MultivaluedMap<String, String> p = baseParams();
        p.putSingle(key, value);
        return p;
    }
}
