package io.floci.aws.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubscriptionFilter {

    private String filterName;
    private String logGroupName;
    private String filterPattern;
    private String destinationArn;
    private String distribution;
    private Boolean applyOnTransformedLogs;
    private long creationTime;

    public SubscriptionFilter() {}

    public SubscriptionFilter(SubscriptionFilter source) {
        filterName = source.filterName;
        logGroupName = source.logGroupName;
        filterPattern = source.filterPattern;
        destinationArn = source.destinationArn;
        distribution = source.distribution;
        applyOnTransformedLogs = source.applyOnTransformedLogs;
        creationTime = source.creationTime;
    }

    public String getFilterName() { return filterName; }
    public void setFilterName(String filterName) { this.filterName = filterName; }

    public String getLogGroupName() { return logGroupName; }
    public void setLogGroupName(String logGroupName) { this.logGroupName = logGroupName; }

    public String getFilterPattern() { return filterPattern; }
    public void setFilterPattern(String filterPattern) { this.filterPattern = filterPattern; }

    public String getDestinationArn() { return destinationArn; }
    public void setDestinationArn(String destinationArn) { this.destinationArn = destinationArn; }

    public String getDistribution() { return distribution; }
    public void setDistribution(String distribution) { this.distribution = distribution; }

    public Boolean getApplyOnTransformedLogs() { return applyOnTransformedLogs; }
    public void setApplyOnTransformedLogs(Boolean applyOnTransformedLogs) {
        this.applyOnTransformedLogs = applyOnTransformedLogs;
    }

    public long getCreationTime() { return creationTime; }
    public void setCreationTime(long creationTime) { this.creationTime = creationTime; }
}
