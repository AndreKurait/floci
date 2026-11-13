package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbCapacityUsage;


public class ConditionalCheckFailedException extends AwsException {

    private final JsonNode item;
    private DynamoDbCapacityUsage capacityUsage;

    public ConditionalCheckFailedException(JsonNode Item) {
        super("ConditionalCheckFailedException", "The conditional request failed", 400);
        this.item = Item;
    }

    public JsonNode getItem() {
        return item;
    }

    @JsonIgnore
    public DynamoDbCapacityUsage capacityUsage() {
        return capacityUsage;
    }

    @JsonIgnore
    public void recordCapacityUsage(DynamoDbCapacityUsage usage) {
        this.capacityUsage = usage;
    }
}
