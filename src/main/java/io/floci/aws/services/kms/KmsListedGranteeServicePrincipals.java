package io.floci.aws.services.kms;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How ListGrants and ListRetirableGrants list a grant's grantee and retiring principal. KMS
 * GrantListEntry: "When a grant is created with the GranteePrincipal field, the ListGrants response
 * usually contains the user or role designated as the grantee principal in the grant. However, if the
 * grantee principal is an AWS service, the GranteePrincipal field contains an AWS service principal,
 * which might correspond to several different grantee principals, such as an IAM user, IAM role, or AWS
 * account." Which accounts belong to which service is deployment data, so
 * {@code floci.services.kms.listed-grantee-service-principals} names it as
 * {@code <account-id>=<service principal>} entries. Empty by default, which lists every principal as
 * stored. The grant keeps the principal it was created with, and the list filters use that.
 */
@ApplicationScoped
public class KmsListedGranteeServicePrincipals {

    private static final Pattern ACCOUNT = Pattern.compile("\\d{12}");
    // GrantListEntry GranteeServicePrincipal's pattern.
    private static final Pattern SERVICE_PRINCIPAL =
            Pattern.compile("([A-Za-z0-9\\-]+)\\.([A-Za-z0-9\\-]+)(\\.[A-Za-z0-9\\-]+)+");

    private final Map<String, String> listedAs;

    @Inject
    public KmsListedGranteeServicePrincipals(EmulatorConfig config) {
        this(config.services().kms().listedGranteeServicePrincipals().orElse(List.of()));
    }

    KmsListedGranteeServicePrincipals(List<String> entries) {
        Map<String, String> parsed = new HashMap<>();
        for (String raw : entries) {
            String entry = raw.strip();
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.indexOf('=');
            String account = separator < 0 ? "" : entry.substring(0, separator);
            String principal = separator < 0 ? "" : entry.substring(separator + 1);
            if (!ACCOUNT.matcher(account).matches() || !SERVICE_PRINCIPAL.matcher(principal).matches()
                    || parsed.putIfAbsent(account, principal) != null) {
                throw new IllegalArgumentException("floci.services.kms.listed-grantee-service-principals "
                        + "requires one <account-id>=<service principal> entry per account: " + entry);
            }
        }
        listedAs = Map.copyOf(parsed);
    }

    /** The principal as ListGrants lists it: a listed account's IAM or STS principal as its service principal. */
    public String listed(String principal) {
        if (listedAs.isEmpty() || principal == null || !AwsArnUtils.isArn(principal)) {
            return principal;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(principal);
        if (!("iam".equals(arn.service()) || "sts".equals(arn.service())) || !arn.region().isEmpty()) {
            return principal;
        }
        return listedAs.getOrDefault(arn.accountId(), principal);
    }
}
