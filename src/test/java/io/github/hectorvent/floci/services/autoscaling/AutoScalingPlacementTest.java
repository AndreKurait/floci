package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfiguration;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoScalingPlacementTest {
    private static final String REGION = "us-east-1";

    @Test
    void repeatedGrowthBalancesActualZonesAndReportsEachInstancePlacement() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.grow(2);
        assertEquals(Map.of(REGION + "a", 1L, REGION + "b", 1L), fixture.counts());
        fixture.grow(3);
        assertEquals(Map.of(REGION + "a", 2L, REGION + "b", 1L), fixture.counts());
        fixture.grow(4);
        assertEquals(Map.of(REGION + "a", 2L, REGION + "b", 2L), fixture.counts());
        fixture.assertReportedZones();
    }

    @Test
    void unequalSubnetCountsDoNotWeightOneAvailabilityZoneMoreHeavily() {
        Fixture fixture = new Fixture(Map.of("subnet-a1", REGION + "a", "subnet-a2", REGION + "a",
                "subnet-b", REGION + "b"), List.of("subnet-a1", "subnet-a2", "subnet-b"));
        fixture.grow(4);
        assertEquals(Map.of(REGION + "a", 2L, REGION + "b", 2L), fixture.counts());
        fixture.assertReportedZones();
    }

    @Test
    void singleZoneSubnetsUseTheirActualZoneWithoutAnExplicitZoneList() {
        Fixture fixture = new Fixture(Map.of("subnet-b1", REGION + "b", "subnet-b2", REGION + "b"),
                List.of("subnet-b1", "subnet-b2"));
        fixture.grow(3);
        assertEquals(Map.of(REGION + "b", 3L), fixture.counts());
        fixture.assertReportedZones();
    }

    @Test
    void declaredZoneWithoutSubnetsReachesNativeEc2Placement() {
        Fixture fixture = new Fixture(Map.of(), List.of());
        fixture.group.setAvailabilityZones(List.of(REGION + "b"));
        fixture.grow(2);
        assertEquals(Map.of(REGION + "b", 2L), fixture.counts());
        fixture.assertReportedZones();
    }

    @Test
    void existingEc2PlacementWinsOverStaleGroupZoneMetadata() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.grow(2);
        fixture.group.getInstances().forEach(instance -> instance.setAvailabilityZone(REGION + "a"));
        fixture.grow(3);
        assertEquals(REGION + "a", fixture.instances.get("i-3").getPlacement().getAvailabilityZone());
        assertEquals(Map.of(REGION + "a", 2L, REGION + "b", 1L), fixture.counts());
    }

    @Test
    void missingDeclaredSubnetRefusesBeforeAnyAllocation() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a"),
                List.of("subnet-a", "subnet-missing"));
        fixture.grow(2);
        assertEquals(Map.of(), fixture.instances);
        assertEquals(0, fixture.group.getInstances().size());
    }

    @Test
    void laterAllocationFailureRetainsAlreadyLaunchedGroupMembers() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.failAfter = 1;
        fixture.grow(2);
        assertEquals(1, fixture.instances.size());
        assertEquals(List.of("i-1"), fixture.group.getInstances().stream()
                .map(instance -> instance.getInstanceId()).toList());
        assertTrue(fixture.savedMembers.contains(List.of("i-1")));
    }

    @Test
    void deletedGroupAfterPartialFailureTerminatesOnlyItsSuccessfulAllocations() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.instances.put("i-unrelated", new Instance());
        fixture.failAfter = 1;
        fixture.deleteOnFailure = true;
        fixture.grow(2);
        assertTrue(fixture.savedMembers.contains(List.of("i-1")));
        verify(fixture.ec2).terminateInstances(REGION, List.of("i-1"));
        verify(fixture.ec2, times(1)).terminateInstances(eq(REGION), anyList());
        assertTrue(fixture.instances.containsKey("i-unrelated"));
    }

    private static class Fixture {
        final AutoScalingGroup group = new AutoScalingGroup();
        final Map<String, Instance> instances = new LinkedHashMap<>();
        final AutoScalingReconciler reconciler;
        final Ec2Service ec2 = mock(Ec2Service.class);
        final List<List<String>> savedMembers = new ArrayList<>();
        boolean groupPresent = true;
        boolean deleteOnFailure;
        int allocations;
        int failAfter = Integer.MAX_VALUE;

        Fixture(Map<String, String> zones, List<String> declaredSubnets) {
            AutoScalingService groups = mock(AutoScalingService.class);
            reconciler = new AutoScalingReconciler(groups, ec2, mock(ElbV2Service.class));
            group.setRegion(REGION);
            group.setAutoScalingGroupName("placement-group");
            group.setLaunchConfigurationName("launch");
            group.setSubnetIds(declaredSubnets);
            LaunchConfiguration launch = new LaunchConfiguration();
            launch.setImageId("ami-test");
            launch.setInstanceType("m5.large");
            when(groups.describeLaunchConfigurations(REGION, List.of("launch"))).thenReturn(List.of(launch));
            when(groups.saveAutoScalingGroupIfPresent(group)).thenAnswer(call -> {
                savedMembers.add(group.getInstances().stream().map(instance -> instance.getInstanceId()).toList());
                return groupPresent;
            });
            List<Subnet> subnets = zones.entrySet().stream().map(entry -> {
                Subnet subnet = new Subnet();
                subnet.setSubnetId(entry.getKey());
                subnet.setAvailabilityZone(entry.getValue());
                return subnet;
            }).toList();
            when(ec2.describeSubnets(eq(REGION), anyList(), any())).thenReturn(subnets);
            when(ec2.getInstance(eq(REGION), anyString()))
                    .thenAnswer(call -> Optional.ofNullable(instances.get(call.<String>getArgument(1))));
            when(ec2.isInstanceContainerRunning(anyString())).thenReturn(true);
            when(ec2.describeInstances(eq(REGION), anyList(), any())).thenAnswer(call -> {
                List<String> ids = call.getArgument(1);
                Reservation reservation = new Reservation();
                reservation.setInstances(ids.stream().map(instances::get).toList());
                return List.of(reservation);
            });
            when(ec2.runInstances(any(), any(), any(), anyInt(), anyInt(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), eq(false),
                    any(), any(), any())).thenAnswer(call -> {
                        if (allocations >= failAfter) {
                            if (deleteOnFailure) {
                                groupPresent = false;
                            }
                            throw new AwsException("InsufficientInstanceCapacity", "fixture allocation failure", 400);
                        }
                        String subnet = call.getArgument(7);
                        String zone = call.getArgument(15);
                        if (subnet != null) {
                            zone = zones.get(subnet);
                        }
                        if (zone == null) {
                            zone = REGION + "a";
                        }
                        List<Instance> launched = new ArrayList<>();
                        int count = call.getArgument(3);
                        for (int index = 0; index < count; index++) {
                            Instance instance = new Instance();
                            instance.setInstanceId("i-" + (++allocations));
                            instance.setSubnetId(subnet);
                            instance.setPlacement(new Placement(zone));
                            instance.setState(new InstanceState(0, "pending"));
                            instances.put(instance.getInstanceId(), instance);
                            launched.add(instance);
                        }
                        Reservation reservation = new Reservation();
                        reservation.setInstances(launched);
                        return reservation;
                    });
        }

        void grow(int desired) {
            group.setDesiredCapacity(desired);
            reconciler.reconcile(group);
        }

        Map<String, Long> counts() {
            return instances.values().stream().collect(Collectors.groupingBy(
                    instance -> instance.getPlacement().getAvailabilityZone(), Collectors.counting()));
        }

        void assertReportedZones() {
            group.getInstances().forEach(instance -> assertEquals(
                    instances.get(instance.getInstanceId()).getPlacement().getAvailabilityZone(),
                    instance.getAvailabilityZone()));
        }
    }
}
