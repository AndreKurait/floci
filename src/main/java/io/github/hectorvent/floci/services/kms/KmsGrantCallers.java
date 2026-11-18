package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The calling principal of a KMS request, for grants on another account's key, and the switch
 * into the key owner's account partition where that key and its grants are stored.
 */
@ApplicationScoped
public class KmsGrantCallers {

    private final Instance<RequestContext> requestContext;
    private final Instance<IamService> iamService;

    @Inject
    public KmsGrantCallers(Instance<RequestContext> requestContext, Instance<IamService> iamService) {
        this.requestContext = requestContext;
        this.iamService = iamService;
    }

    /**
     * The principals this request's credential stands for: an IAM user's ARN; a role session's
     * assumed-role ARN and its role's ARN; and the account root, which also stands for an
     * account-id access key that names no IAM identity.
     */
    public KmsService.GrantCaller current() {
        RequestContext context = requestContext.get();
        String account = context.getAccountId();
        String partition = context.getPartition() == null ? "aws" : context.getPartition();
        String root = AwsArnUtils.Arn.global(partition, "iam", account, "root").toString();
        Set<String> principals = new LinkedHashSet<>();
        Optional<CallerArns> arns = iamService.isResolvable()
                ? iamService.get().resolveCallerArns(context.getAccessKeyId()) : Optional.empty();
        arns.ifPresent(a -> {
            principals.add(a.callerArn());
            if (a.principalArn() != null) {
                principals.add(a.principalArn());
            }
        });
        principals.add(root);
        return new KmsService.GrantCaller(account, arns.map(CallerArns::callerArn).orElse(root), principals);
    }

    /** Runs {@code call} with the request's account set to {@code owner}, then restores the caller's. */
    public <T> T asAccount(String owner, Supplier<T> call) {
        RequestContext context = requestContext.get();
        String caller = context.getAccountId();
        context.setAccountId(owner);
        try {
            return call.get();
        } finally {
            context.setAccountId(caller);
        }
    }
}
