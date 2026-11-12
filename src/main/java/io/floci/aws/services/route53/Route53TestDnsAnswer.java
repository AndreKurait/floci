package io.floci.aws.services.route53;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.DomainNames;
import io.floci.aws.services.route53.model.AliasTarget;
import io.floci.aws.services.route53.model.HostedZone;
import io.floci.aws.services.route53.model.ResourceRecord;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import io.netty.util.NetUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Evaluates a DNS answer from one explicitly selected zone, without recursive network lookups. */
final class Route53TestDnsAnswer {
    private static final Set<String> RECORD_TYPES = Set.of("SOA", "A", "TXT", "NS", "CNAME", "MX", "NAPTR",
            "PTR", "SRV", "SPF", "AAAA", "CAA", "DS", "TLSA", "SSHFP", "SVCB", "HTTPS");
    private static final int MAX_ALIAS_DEPTH = 8;

    private Route53TestDnsAnswer() {}

    static String validate(String hostedZoneId, String recordName, String recordType, String resolverIp,
                           String subnetIp, String subnetMask) {
        String id = hostedZoneId != null && hostedZoneId.startsWith("/hostedzone/")
                ? hostedZoneId.substring(12) : hostedZoneId;
        requireText(id, 32, "HostedZoneId");
        requireText(recordName, 1024, "RecordName");
        if (recordType == null || !RECORD_TYPES.contains(recordType)) {
            throw invalid("RecordType must be a supported DNS record type.");
        }
        validateIp(resolverIp, "ResolverIP");
        validateIp(subnetIp, "EDNS0ClientSubnetIP");
        if (subnetMask != null && !subnetMask.isEmpty()) {
            int maximum = subnetIp != null && NetUtil.isValidIpV4Address(subnetIp) ? 32 : 128;
            if (!subnetMask.matches("[0-9]{1,3}") || Integer.parseInt(subnetMask) > maximum) {
                throw invalid("EDNS0ClientSubnetMask is outside the address family's prefix range.");
            }
        }
        return id;
    }

    static Route53Service.TestDnsAnswerResult answer(HostedZone zone, List<ResourceRecordSet> records,
                                                    List<String> nameServers, String recordName, String recordType) {
        String normalizedName = Route53Service.normalizeRecordName(recordName);
        Answer answer = resolve(zone, records, normalizedName, recordType, new HashSet<>());
        return new Route53Service.TestDnsAnswerResult(nameServers.getFirst(), "UDP", recordName, recordType,
                answer.code(), answer.values());
    }

    private static Answer resolve(HostedZone zone, List<ResourceRecordSet> records, String name, String type,
                                  Set<String> visited) {
        String apex = Route53Service.normalizeRecordName(zone.getName());
        if (!DomainNames.isWithin(name, apex) || visited.size() >= MAX_ALIAS_DEPTH || !visited.add(name)) {
            return new Answer("REFUSED", List.of());
        }
        // Delegation records are referrals, not answers from this zone. In particular the API
        // does not return a child NS record pointing back to the parent's own name servers.
        // DS at the delegation owner is parent-zone data, unlike records below the zone cut.
        boolean delegated = records.stream().anyMatch(record -> "NS".equals(record.getType())
                && !Route53Service.normalizeRecordName(record.getName()).equals(apex)
                && DomainNames.isWithin(name, Route53Service.normalizeRecordName(record.getName()))
                && !("DS".equals(type) && name.equals(Route53Service.normalizeRecordName(record.getName()))));
        if (delegated) {
            return new Answer("NOERROR", List.of());
        }
        List<ResourceRecordSet> matches = Route53Service.findRecordsForName(name, List.of(records));
        if (matches.isEmpty()) {
            boolean nameExists = name.equals(apex) || records.stream()
                    .anyMatch(record -> Route53Service.normalizeRecordName(record.getName()).endsWith("." + name));
            return new Answer(nameExists ? "NOERROR" : "NXDOMAIN", List.of());
        }
        if (!"CNAME".equals(type) && matches.stream().anyMatch(record -> "CNAME".equals(record.getType()))) {
            return new Answer("REFUSED", List.of());
        }
        List<ResourceRecordSet> selected;
        try {
            selected = Route53RecordSelection.select(matches, type,
                    bound -> ThreadLocalRandom.current().nextLong(bound));
        } catch (IllegalArgumentException e) {
            return new Answer("REFUSED", List.of());
        }
        List<String> values = new ArrayList<>();
        for (ResourceRecordSet record : selected) {
            AliasTarget alias = record.getAliasTarget();
            if (alias != null) {
                String targetZone = alias.getHostedZoneId();
                if (targetZone != null && targetZone.startsWith("/hostedzone/")) {
                    targetZone = targetZone.substring(12);
                }
                if (!zone.getId().equals(targetZone) || alias.getDnsName() == null
                        || !("A".equals(type) || "AAAA".equals(type))) {
                    return new Answer("REFUSED", List.of());
                }
                Answer target = resolve(zone, records, Route53Service.normalizeRecordName(alias.getDnsName()), type,
                        new HashSet<>(visited));
                if (!"NOERROR".equals(target.code())) {
                    return target;
                }
                values.addAll(target.values());
            } else if (record.getRecords() != null) {
                for (ResourceRecord value : record.getRecords()) {
                    if (value.getValue() != null) {
                        values.add(value.getValue());
                    }
                }
            }
        }
        return new Answer("NOERROR", List.copyOf(values));
    }

    private static void requireText(String value, int maximum, String member) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw invalid(member + " must contain between 1 and " + maximum + " characters.");
        }
    }

    private static void validateIp(String ip, String member) {
        if (ip != null && (ip.length() > 45 || !NetUtil.isValidIpV4Address(ip) && !NetUtil.isValidIpV6Address(ip))) {
            throw invalid(member + " must be an IPv4 or IPv6 address.");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInput", message, 400);
    }

    private record Answer(String code, List<String> values) {}
}
