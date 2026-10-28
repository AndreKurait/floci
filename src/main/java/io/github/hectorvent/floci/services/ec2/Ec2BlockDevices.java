package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Request-local, field-wise launch overrides; never changes an AMI or template. */
public final class Ec2BlockDevices {
    private Ec2BlockDevices() {}

    public static List<LaunchTemplateData.BlockDeviceMapping> merge(
            List<LaunchTemplateData.BlockDeviceMapping> inherited,
            List<LaunchTemplateData.BlockDeviceMapping> overrides) {
        Ec2Service.requireValidLaunchDevices(inherited);
        Ec2Service.requireValidLaunchDevices(overrides);
        Map<String, LaunchTemplateData.BlockDeviceMapping> result = new LinkedHashMap<>();
        for (List<LaunchTemplateData.BlockDeviceMapping> layer
                : List.of(inherited, Ec2Service.spelledAs(inherited, overrides))) {
            for (LaunchTemplateData.BlockDeviceMapping mapping : layer) {
                validatePerformance(mapping);
                LaunchTemplateData.BlockDeviceMapping old = result.get(mapping.getDeviceName());
                LaunchTemplateData.BlockDeviceMapping merged = old == null ? mapping
                        : LaunchTemplateData.BlockDeviceMapping.merge(List.of(old), List.of(mapping)).getFirst();
                if (old != null && old.getEbs() != null && mapping.getEbs() != null && merged.getEbs() != null
                        && mapping.getEbs().getVolumeType() != null
                        && !Objects.equals(mapping.getEbs().getVolumeType(), old.getEbs().getVolumeType())) {
                    merged.getEbs().setIops(mapping.getEbs().getIops());
                    merged.getEbs().setThroughput(mapping.getEbs().getThroughput());
                }
                result.put(mapping.getDeviceName(), merged);
            }
        }
        return new ArrayList<>(result.values());
    }

    private static void validatePerformance(LaunchTemplateData.BlockDeviceMapping mapping) {
        LaunchTemplateData.Ebs ebs = mapping.getEbs();
        if (mapping.getNoDevice() != null || mapping.namesInstanceStore() || ebs == null) {
            return;
        }
        if ((ebs.getIops() != null && ebs.getIops() <= 0)
                || (ebs.getThroughput() != null && ebs.getThroughput() <= 0)) {
            throw new AwsException("InvalidBlockDeviceMapping", "Invalid EBS size, type or performance settings.", 400);
        }
        if (ebs.getVolumeType() == null) {
            return;
        }
        String type = ebs.getVolumeType();
        if (ebs.getIops() != null && !Set.of("gp3", "io1", "io2").contains(type)) {
            throw new AwsException("InvalidParameterCombination", "The parameter Iops is not supported for " + type, 400);
        }
        if (ebs.getThroughput() != null && !"gp3".equals(type)) {
            throw new AwsException("InvalidParameterCombination", "The parameter Throughput is not supported for " + type, 400);
        }
    }
}
