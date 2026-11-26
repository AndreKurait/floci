package io.floci.aws.core.common;

import io.floci.aws.services.ec2.model.LaunchTemplate;
import io.floci.aws.services.iam.model.SessionCredential;
import jakarta.enterprise.context.RequestScoped;

import java.util.Objects;

/**
 * Holds per-request derived values, account ID, region and partition, extracted from the
 * incoming AWS credential and Authorization header. Populated by
 * {@link AccountContextFilter} before any handler runs.
 */
@RequestScoped
public class RequestContext {

    private String accountId;
    private String region;
    private String partition;
    private String accessKeyId;
    private String signatureVerifiedAccessKeyId;
    private String sessionToken;
    /** Set only after the native SigV4 header verifier authenticates this request. */
    private SessionCredential verifiedSession;
    private String verifiedSecret;
    private String verifiedToken;

    private Boolean iamGovernanceBypass;
    /**
     * What this request's MFA members amounted to, or null until something asks. Held here because
     * the enforcement filter and the STS operation both read it and a code is spent when it
     * authenticates, so the two have to read one answer rather than each take their own.
     */
    private RequestMfaClaim mfaClaim;
    /** Dispatch must keep the launch template snapshot that passed this request's permission checks. */
    private LaunchTemplate authorizedEc2LaunchTemplate;

    /** Authentication provenance, distinct from an account-scoped internal request. */
    public boolean isSignatureVerifiedFor(String accessKey) {
        return accessKey != null && accessKey.equals(signatureVerifiedAccessKeyId);
    }

    /** Only the signature filter in this package records a successful verification. */
    void recordVerifiedSignature(String accessKey) {
        signatureVerifiedAccessKeyId = accessKey;
    }

    public SessionCredential getVerifiedSession() {
        return verifiedSession != null && Objects.equals(verifiedSecret, verifiedSession.getSecretAccessKey())
                && Objects.equals(verifiedToken, verifiedSession.getSessionToken()) ? verifiedSession : null;
    }

    public void setVerifiedSession(SessionCredential session, String secret, String token) {
        verifiedSession = session;
        verifiedSecret = secret;
        verifiedToken = token;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * The partition the request's signing region belongs to ({@code aws}, {@code aws-cn}, ...),
     * or null when nothing set it, in which case {@link RegionResolver#getPartition()} answers
     * with the deployment's partition.
     */
    public String getPartition() {
        return partition;
    }

    public void setPartition(String partition) {
        this.partition = partition;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }

    /**
     * IAM's decision on an S3 delete or retention change that sends
     * {@code x-amz-bypass-governance-retention}: true when it overrides a governance lock and the caller
     * may bypass it, false when it overrides none, so no bypass permission was checked, and null when
     * IAM did not decide. S3Controller lets the operation bypass a lock only as far as this allows.
     */
    public RequestMfaClaim getMfaClaim() {
        return mfaClaim;
    }

    public void setMfaClaim(RequestMfaClaim mfaClaim) {
        this.mfaClaim = mfaClaim;
    }

    public Boolean getIamGovernanceBypass() {
        return iamGovernanceBypass;
    }

    public void setIamGovernanceBypass(Boolean iamGovernanceBypass) {
        this.iamGovernanceBypass = iamGovernanceBypass;
    }

    public LaunchTemplate getAuthorizedEc2LaunchTemplate() {
        return authorizedEc2LaunchTemplate;
    }

    public void setAuthorizedEc2LaunchTemplate(LaunchTemplate template) {
        authorizedEc2LaunchTemplate = template;
    }
}
