package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Request-local, field-wise launch overrides; never changes an AMI or template. */
public final class Ec2BlockDevices {
    private Ec2BlockDevices() {}

    public static List<LaunchTemplateData.BlockDeviceMapping> merge(
            List<LaunchTemplateData.BlockDeviceMapping> inherited,
            List<LaunchTemplateData.BlockDeviceMapping> overrides) {
        Map<String, LaunchTemplateData.BlockDeviceMapping> result = new LinkedHashMap<String, LaunchTemplateData.BlockDeviceMapping>();
        for (List<LaunchTemplateData.BlockDeviceMapping> layer : List.of(inherited, overrides)) {
            Set<String> seen = new HashSet<>();
            for (LaunchTemplateData.BlockDeviceMapping mapping : layer) {
                String device = mapping.getDeviceName();
                if (device == null || !device.startsWith("/dev/") || !seen.add(device)) {
                    throw new AwsException("InvalidBlockDeviceMapping", "Missing or duplicate device name.", 400);
                }
                if (mapping.getVirtualName() != null) {
                    throw new AwsException("UnsupportedOperation", "Instance-store launch mappings are not supported.", 400);
                }
                if (mapping.getNoDevice() != null && mapping.getEbs() != null) {
                    throw new AwsException("InvalidBlockDeviceMapping", "NoDevice and Ebs cannot be combined.", 400);
                }
                LaunchTemplateData.BlockDeviceMapping copy = new LaunchTemplateData.BlockDeviceMapping();
                copy.setDeviceName(device);
                copy.setNoDevice(mapping.getNoDevice());
                LaunchTemplateData.BlockDeviceMapping old = result.get(device);
                if (mapping.getNoDevice() == null) {
                    copy.setEbs(mergeEbs(old == null ? null : old.getEbs(), mapping.getEbs()));
                }
                result.put(device, copy);
            }
        }
        return new ArrayList<>(result.values());
    }

    private static LaunchTemplateData.Ebs mergeEbs(LaunchTemplateData.Ebs original,
                                                   LaunchTemplateData.Ebs override) {
        LaunchTemplateData.Ebs a = original == null ? new LaunchTemplateData.Ebs() : original;
        LaunchTemplateData.Ebs b = override == null ? new LaunchTemplateData.Ebs() : override;
        LaunchTemplateData.Ebs result = new LaunchTemplateData.Ebs();
        result.setSnapshotId(value(b.getSnapshotId(), a.getSnapshotId()));
        result.setVolumeSize(value(b.getVolumeSize(), a.getVolumeSize()));
        result.setVolumeType(value(b.getVolumeType(), a.getVolumeType()));
        result.setIops(value(b.getIops(), a.getIops()));
        result.setThroughput(value(b.getThroughput(), a.getThroughput()));
        result.setEncrypted(value(b.getEncrypted(), a.getEncrypted()));
        result.setDeleteOnTermination(value(b.getDeleteOnTermination(), a.getDeleteOnTermination()));
        result.setKmsKeyId(value(b.getKmsKeyId(), a.getKmsKeyId()));
        return result;
    }

    private static <T> T value(T override, T inherited) {
        return override == null ? inherited : override;
    }
}
