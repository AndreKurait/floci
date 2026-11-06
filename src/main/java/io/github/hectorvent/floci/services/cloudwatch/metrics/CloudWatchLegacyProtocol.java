package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Map;
import java.util.Set;

/** Operation availability on CloudWatch's legacy console protocol, independent of newer models. */
public final class CloudWatchLegacyProtocol {

    public static final String VERSION = "2013-01-16";

    private static final Set<String> OPERATIONS = Set.of(
            "DeleteAlarms", "DeleteDashboards", "DeleteInsightRules", "DescribeAlarmHistory",
            "DescribeAlarms", "DescribeAlarmsForMetric", "DisableAlarmActions", "DisableInsightRules",
            "EnableAlarmActions", "EnableInsightRules", "GetDashboard", "GetInsightRuleReport",
            "GetMetricData", "GetMetricStatistics", "GetMetricWidgetImage", "ListDashboards",
            "ListMetrics", "PutCompositeAlarm", "PutDashboard", "PutInsightRule", "PutMetricAlarm",
            "PutMetricData", "SetAlarmState");

    private static final Set<String> QUERY_READS = Set.of(
            "DescribeAlarms", "GetMetricStatistics", "ListMetrics");

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

    /** Recognition does not imply that a legacy operation's input contract is implemented. */
    public static Set<String> operations() {
        return OPERATIONS;
    }

    /** Query reads whose input members are shared with the current protocol. */
    static Set<String> queryReads() {
        return QUERY_READS;
    }

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
