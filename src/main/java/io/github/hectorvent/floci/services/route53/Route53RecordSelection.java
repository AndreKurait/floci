package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/** Shared selection for supported simple and weighted public records. */
final class Route53RecordSelection {
    private Route53RecordSelection() {}

    static List<ResourceRecordSet> select(List<ResourceRecordSet> records, String type,
                                           LongUnaryOperator weightedDraw) {
        List<ResourceRecordSet> matching = records.stream().filter(record -> type.equals(record.getType())).toList();
        Set<String> identifiers = new HashSet<>();
        long totalWeight = 0;
        for (ResourceRecordSet record : matching) {
            if (record.getRegion() != null || record.getFailover() != null || record.getHealthCheckId() != null
                    || record.getAliasTarget() != null && record.getAliasTarget().isEvaluateTargetHealth()) {
                throw new IllegalArgumentException("Unsupported public DNS routing policy");
            }
            Long weight = record.getWeight();
            if (weight == null) {
                if (record.getSetIdentifier() != null) {
                    throw new IllegalArgumentException("Unsupported public DNS routing identifier");
                }
            } else {
                String identifier = record.getSetIdentifier();
                if (weight < 0 || weight > 255 || identifier == null || identifier.isBlank()
                        || !identifiers.add(identifier)) {
                    throw new IllegalArgumentException("Invalid weighted DNS record");
                }
                totalWeight += weight;
            }
        }
        if (identifiers.isEmpty()) {
            return matching;
        }
        if (identifiers.size() != matching.size()) {
            throw new IllegalArgumentException("Mixed simple and weighted DNS records");
        }
        // Zero-weight records participate only when the entire group has weight zero.
        long draw = weightedDraw.applyAsLong(totalWeight == 0 ? matching.size() : totalWeight);
        if (totalWeight == 0) {
            return List.of(matching.get(Math.toIntExact(draw)));
        }
        for (ResourceRecordSet record : matching) {
            if (draw < record.getWeight()) {
                return List.of(record);
            }
            draw -= record.getWeight();
        }
        throw new IllegalArgumentException("Weighted DNS selection is outside the group");
    }

}
