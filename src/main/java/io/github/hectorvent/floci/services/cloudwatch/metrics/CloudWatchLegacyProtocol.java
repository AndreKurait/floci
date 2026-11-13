package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Map;
import java.util.Set;

/**
 * The input contracts served on CloudWatch's legacy console protocol: the
 * {@code CloudWatchVersion20130116.} JSON target and Query {@code Version=2013-01-16}. Which
 * operation names that protocol recognizes is decided where requests are routed; recognition does
 * not imply that an operation's legacy input contract is implemented here.
 */
final class CloudWatchLegacyProtocol {

    static final String VERSION = "2013-01-16";

    private static final Set<String> QUERY_READS = Set.of(
            "DescribeAlarms", "GetMetricStatistics", "ListMetrics");

    /**
     * The operations served through the legacy protocol, each with the request members accepted
     * there: some of the ones the handlers honour. ListMetrics honours {@code NextToken};
     * DescribeAlarms' {@code MaxRecords} and {@code NextToken} still change nothing because every
     * alarm comes back at once. DescribeAlarms' {@code StateValue}, {@code ActionPrefix},
     * {@code ChildrenOfAlarmName} and {@code ParentsOfAlarmName} stay refused here until the
     * console's API is known to give them the same shapes.
     */
    private static final Map<String, Set<String>> MEMBERS = Map.of(
            "ListMetrics", Set.of("Namespace", "MetricName", "Dimensions", "NextToken"),
            "DescribeAlarms", Set.of("AlarmNames", "AlarmNamePrefix", "MaxRecords", "NextToken"),
            "GetMetricStatistics", Set.of("Namespace", "MetricName", "Dimensions", "StartTime", "EndTime",
                    "Period", "Statistics", "Unit"),
            "DeleteAlarms", Set.of("AlarmNames"));

    private static final Set<String> QUERY_PROTOCOL_MEMBERS = Set.of(
            "Action", "Operation", "Version", "AWSAccessKeyId", "Signature", "SignatureMethod",
            "SignatureVersion", "Timestamp", "Expires", "SecurityToken");

    private CloudWatchLegacyProtocol() {
    }

    /** Query reads whose input members are shared with the current protocol. */
    static Set<String> queryReads() {
        return QUERY_READS;
    }

    /** The members served for {@code action}, or {@code null} when the operation is not served. */
    static Set<String> members(String action) {
        return MEMBERS.get(action);
    }

    static void validateQueryMembers(String action, MultivaluedMap<String, String> params) {
        for (String name : params.keySet()) {
            if (QUERY_PROTOCOL_MEMBERS.contains(name) || name.startsWith("X-Amz-")) {
                continue;
            }
            int separator = name.indexOf('.');
            String member = separator < 0 ? name : name.substring(0, separator);
            if (!members(action).contains(member)) {
                throw new AwsException("UnsupportedOperation", "CloudWatch parameter " + member
                        + " of " + action + " for API version " + VERSION + " is not implemented.", 400);
            }
        }
    }
}
