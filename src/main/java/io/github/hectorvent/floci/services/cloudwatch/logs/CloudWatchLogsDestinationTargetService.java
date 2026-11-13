package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogDestination;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer.Permission;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recipient-owned Kinesis targets used by logical Logs destinations. */
@ApplicationScoped
public class CloudWatchLogsDestinationTargetService {
    private final KinesisService kinesis;
    private final IamService iam;
    private final ServiceRoleAuthorizer roles;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public CloudWatchLogsDestinationTargetService(KinesisService kinesis, IamService iam,
                                                  ServiceRoleAuthorizer roles, RegionResolver regions,
                                                  ObjectMapper mapper) {
        this.kinesis = kinesis;
        this.iam = iam;
        this.roles = roles;
        this.regions = regions;
        this.mapper = mapper;
    }

    /** PutDestination tests delivery on the recipient's behalf before publishing metadata. */
    public void admit(LogDestination destination) {
        AwsArnUtils.Arn logical = parse(destination.getArn());
        String sourceArn = new AwsArnUtils.Arn(logical.partition(), "logs", logical.region(),
                logical.accountId(), "*").toString();
        try {
            byte[] control = CloudWatchLogsSubscriptionDeliveryService.gzip(mapper.writeValueAsBytes(
                    Map.of("messageType", "CONTROL_MESSAGE", "owner", logical.accountId())));
            deliver(destination, logical.accountId(), sourceArn, control, UUID.randomUUID().toString());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize the destination test message.", failure);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot compress the destination test message.", failure);
        }
    }

    /** Subscription data uses the source account, while the assumed role remains recipient-owned. */
    public void deliver(LogDestination destination, String sourceAccount, String sourceArn,
                        byte[] payload, String partitionKey) {
        AwsArnUtils.Arn target = requireTarget(destination, sourceAccount, sourceArn);
        kinesis.putRecordForAccount(target.accountId(), target.resource().substring("stream/".length()),
                payload, partitionKey, target.region());
    }

    public void validate(LogDestination destination, String sourceAccount, String sourceArn) {
        requireTarget(destination, sourceAccount, sourceArn);
    }

    private AwsArnUtils.Arn requireTarget(LogDestination destination, String sourceAccount, String sourceArn) {
        AwsArnUtils.Arn logical = parse(destination.getArn());
        AwsArnUtils.Arn target = parse(destination.getTargetArn());
        AwsArnUtils.Arn role = parse(destination.getRoleArn());
        if (!"kinesis".equals(target.service()) || !target.resource().matches("stream/[a-zA-Z0-9_.-]{1,128}")
                || target.region().isBlank()
                || !target.accountId().equals(logical.accountId())
                || !target.partition().equals(logical.partition())
                || !regions.partitionForRegion(target.region()).equals(target.partition())) {
            throw invalid("Logical destinations currently require a recipient-owned Kinesis stream "
                    + "in the destination's partition.");
        }
        if (!"iam".equals(role.service()) || !role.region().isEmpty()
                || !role.resource().startsWith("role/") || !role.accountId().equals(logical.accountId())
                || !role.partition().equals(logical.partition())) {
            throw invalid("roleArn must name an IAM role in the destination account and partition.");
        }
        String roleName = role.resource().substring(role.resource().lastIndexOf('/') + 1);
        if (iam.findRole(role.accountId(), roleName)
                .filter(found -> IamService.roleArnMatches(role.toString(), found)).isEmpty()) {
            throw invalid("The destination role does not exist.");
        }
        KinesisStream stream;
        try {
            stream = kinesis.describeStreamForAccount(target.accountId(),
                    target.resource().substring("stream/".length()), target.region());
        } catch (AwsException failure) {
            throw invalid("The destination stream does not exist.");
        }
        if (!target.toString().equals(stream.getStreamArn()) || !"ACTIVE".equals(stream.getStreamStatus())) {
            throw invalid("The destination must name the exact active Kinesis stream.");
        }
        roles.refusal(role.toString(), logical.accountId(), ServicePrincipals.of("logs"), sourceArn, sourceAccount,
                List.of(new Permission("kinesis:PutRecord", target.toString())))
                .ifPresent(reason -> { throw invalid(reason); });
        return target;
    }

    private static AwsArnUtils.Arn parse(String value) {
        try {
            return AwsArnUtils.parse(value);
        } catch (IllegalArgumentException failure) {
            throw invalid("The destination, target and role must be valid ARNs.");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
