package io.floci.aws.services.s3;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.core.common.XmlParser.XmlElement;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** The bounded delivery subset of a bucket's existing, unmodified logging XML. */
record S3AccessLogConfiguration(String destination, String prefix, DateSource dateSource) {

    private static final DateTimeFormatter NAME_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DIRECTORY_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    enum DateSource { SIMPLE, EVENT, DELIVERY }

    static S3AccessLogConfiguration parse(String document) {
        if (document == null || document.length() > 16_384) {
            return null;
        }
        XmlElement root = XmlParser.parseDocumentElementTree(document);
        if (!container(root, Set.of("LoggingEnabled")) || !"BucketLoggingStatus".equals(root.name())
                || root.count("LoggingEnabled") != 1) {
            return null;
        }
        XmlElement enabled = root.child("LoggingEnabled");
        if (!container(enabled, Set.of("TargetBucket", "TargetPrefix", "TargetObjectKeyFormat"))
                || enabled.count("TargetBucket") != 1 || enabled.count("TargetPrefix") != 1
                || enabled.count("TargetObjectKeyFormat") > 1
                || !leaf(enabled.child("TargetBucket")) || !leaf(enabled.child("TargetPrefix"))) {
            return null;
        }
        String destination = enabled.child("TargetBucket").rawText();
        String prefix = enabled.child("TargetPrefix").rawText();
        if (destination.isBlank() || !destination.equals(destination.trim())) {
            return null;
        }
        DateSource dateSource = DateSource.SIMPLE;
        XmlElement format = enabled.child("TargetObjectKeyFormat");
        if (format != null) {
            if (!container(format, Set.of("SimplePrefix", "PartitionedPrefix"))
                    || format.children().size() != 1) {
                return null;
            }
            XmlElement simple = format.child("SimplePrefix");
            XmlElement partitioned = format.child("PartitionedPrefix");
            if (simple != null) {
                if (!container(simple, Set.of()) || !simple.children().isEmpty()) {
                    return null;
                }
            } else {
                if (!container(partitioned, Set.of("PartitionDateSource"))
                        || partitioned.count("PartitionDateSource") > 1) {
                    return null;
                }
                XmlElement source = partitioned.child("PartitionDateSource");
                if (source != null && !leaf(source)) {
                    return null;
                }
                String value = source == null ? "EventTime" : source.text();
                dateSource = switch (value) {
                    case "EventTime" -> DateSource.EVENT;
                    case "DeliveryTime" -> DateSource.DELIVERY;
                    default -> null;
                };
                if (dateSource == null) {
                    return null;
                }
            }
        }
        return new S3AccessLogConfiguration(destination, prefix, dateSource);
    }

    String objectKey(String account, String region, String bucket, Instant received,
                     Instant delivered, String unique) {
        Instant date = dateSource == DateSource.EVENT ? received.truncatedTo(ChronoUnit.DAYS) : delivered;
        String directory = dateSource == DateSource.SIMPLE ? ""
                : account + "/" + region + "/" + bucket + "/" + DIRECTORY_DATE.format(date) + "/";
        return prefix + directory + NAME_TIME.format(date) + "-" + unique;
    }

    static boolean supportsDestinationEncryption(String document) {
        if (document == null || document.isBlank()) {
            return true;
        }
        if (document.length() > 16_384) {
            return false;
        }
        XmlElement root = XmlParser.parseDocumentElementTree(document);
        if (!container(root, Set.of("Rule")) || !"ServerSideEncryptionConfiguration".equals(root.name())
                || root.count("Rule") != 1) {
            return false;
        }
        XmlElement rule = root.child("Rule");
        if (!container(rule, Set.of("ApplyServerSideEncryptionByDefault", "BucketKeyEnabled"))
                || rule.count("ApplyServerSideEncryptionByDefault") != 1) {
            return false;
        }
        XmlElement defaults = rule.child("ApplyServerSideEncryptionByDefault");
        return container(defaults, Set.of("SSEAlgorithm")) && defaults.count("SSEAlgorithm") == 1
                && leaf(defaults.child("SSEAlgorithm")) && "AES256".equals(defaults.child("SSEAlgorithm").text());
    }

    private static boolean container(XmlElement element, Set<String> names) {
        return element != null && element.text().isEmpty()
                && element.children().stream().allMatch(child -> names.contains(child.name()));
    }

    private static boolean leaf(XmlElement element) {
        return element != null && element.children().isEmpty();
    }
}
