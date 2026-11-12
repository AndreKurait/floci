package io.floci.aws.services.cognitoidentity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.cognitoidentity.model.CognitoIdentity;
import io.floci.aws.services.cognitoidentity.model.IdentityPool;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.WebIdentityTrustPolicyEvaluator;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Typed Cognito-to-STS exchange; every returned credential is an actual IAM session. */
@ApplicationScoped
public class CognitoIdentityCredentials {
    private static final String PRINCIPAL = ServicePrincipals.of("cognito-identity");
    private static final List<String> GUEST_INLINE_ACTIONS = List.of(
            "cloudwatch:*", "logs:*", "dynamodb:*", "kinesis:*", "mobileanalytics:*", "s3:*", "ses:*",
            "sns:*", "sqs:*", "lambda:*", "machinelearning:*", "execute-api:*", "iot:*", "gamelift:*",
            "cognito-identity:*", "cognito-idp:*", "lex:*", "polly:*", "comprehend:*", "translate:*",
            "transcribe:*", "rekognition:*", "mobiletargeting:*", "firehose:*", "appsync:*", "personalize:*",
            "sagemaker:InvokeEndpoint");
    private static final List<String> DEFAULT_REGION_ACTIONS = List.of(
            "cognito-sync:*", "codewhisperer:*", "textract:DetectDocumentText", "textract:AnalyzeDocument", "sdb:*");
    private static final List<String> GUEST_MANAGED_ACTIONS = List.of(
            "rum:PutRumEvents", "polly:*", "comprehend:*", "translate:*", "transcribe:*", "rekognition:*",
            "mobiletargeting:*", "firehose:*", "personalize:*", "sagemaker:InvokeEndpoint",
            "geo:GetMap*", "geo:SearchPlaceIndex*", "geo:GetPlace", "geo:CalculateRoute*", "geo:*Geofence",
            "geo:*Geofences", "geo:*DevicePosition*", "kms:Encrypt", "kms:Decrypt", "kms:ReEncryptTo",
            "kms:ReEncryptFrom", "kms:GenerateDataKey", "kms:GenerateDataKeyPair",
            "kms:GenerateDataKeyPairWithoutPlaintext", "kms:GenerateDataKeyWithoutPlaintext");
    private final CognitoIdentityService identities;
    private final CognitoIdentityTokens tokens;
    private final IamService iam;
    private final WebIdentityTrustPolicyEvaluator trust;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public CognitoIdentityCredentials(CognitoIdentityService identities, CognitoIdentityTokens tokens,
                                      IamService iam, WebIdentityTrustPolicyEvaluator trust, ObjectMapper mapper) {
        this.identities = identities;
        this.tokens = tokens;
        this.iam = iam;
        this.trust = trust;
        this.mapper = mapper;
    }

    public record Credentials(String identityId, String accessKeyId, String secretKey, String sessionToken,
                              Instant expiration, String assumedRoleArn, String assumedRoleId,
                              String issuer, String audience) {}

    public boolean recognizes(String token, String roleArn) {
        if (tokens.recognizes(token)) {
            return true;
        }
        if (tokens.identifiesIssuer(token)) {
            return false;
        }
        try {
            JsonNode statements = mapper.readTree(role(roleArn).getAssumeRolePolicyDocument()).path("Statement");
            List<JsonNode> values = new ArrayList<>();
            if (statements.isArray()) {
                statements.forEach(values::add);
            } else {
                values.add(statements);
            }
            return values.stream().anyMatch(statement -> {
                JsonNode federated = statement.path("Principal").path("Federated");
                if (federated.isTextual()) {
                    return PRINCIPAL.equals(federated.asText());
                }
                for (JsonNode value : federated) {
                    if (PRINCIPAL.equals(value.asText())) {
                        return true;
                    }
                }
                return false;
            });
        } catch (AwsException | JsonProcessingException e) {
            return false;
        }
    }

    public Credentials enhanced(String identityId, Map<String, String> logins, String customRoleArn, String region) {
        CognitoIdentity identity = identities.publicIdentity(identityId, region);
        IdentityPool pool = identities.publicPool(identity.identityPoolId(), region);
        CognitoIdentityService.requireSupportedRoleConfiguration(pool);
        if (customRoleArn != null) {
            throw new AwsException("InvalidParameterException", "CustomRoleArn requires a supported external provider.", 400);
        }
        CognitoIdentityTokens.Verified verified;
        if (logins.isEmpty()) {
            CognitoIdentityService.requireGuest(pool, identity);
            verified = new CognitoIdentityTokens.Verified(CognitoIdentityTokens.issuer(region), pool.getIdentityPoolId(),
                    identityId, pool.getAccountId(), region, List.of("unauthenticated"));
        } else {
            if (logins.size() != 1 || !identity.active() || !identity.authenticated()) {
                throw unauthorized();
            }
            Map.Entry<String, String> login = logins.entrySet().iterator().next();
            try {
                verified = tokens.verify(login.getValue());
            } catch (AwsException e) {
                throw unauthorized();
            }
            if (!login.getKey().equals(verified.issuer()) || !identityId.equals(verified.identityId())
                    || !pool.getIdentityPoolId().equals(verified.poolId())
                    || !pool.getAccountId().equals(verified.accountId())
                    || !verified.methods().contains("authenticated") || pool.getDeveloperProviderName() == null
                    || !verified.methods().contains(pool.getDeveloperProviderName())) {
                throw unauthorized();
            }
        }
        String type = identity.authenticated() ? "authenticated" : "unauthenticated";
        String roleArn = pool.getRoles().get(type);
        IamRole role = role(roleArn);
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(role.getArn());
        if (!pool.getAccountId().equals(parsed.accountId())
                || !AwsArnUtils.parse(pool.getArn()).partition().equals(parsed.partition())) {
            throw new AwsException("InvalidIdentityPoolConfigurationException",
                    "The enhanced-flow role must belong to the identity pool account and partition.", 400);
        }
        try {
            requireTrust(role, verified);
        } catch (AwsException e) {
            throw new AwsException("InvalidIdentityPoolConfigurationException",
                    "The configured role does not trust this identity pool and authentication type.", 400);
        }
        return issue(role, verified, "CognitoIdentityCredentials", 3600, enhancedPolicy(identity.authenticated(), region), 200);
    }

    public Credentials assumeRole(String token, String roleArn, String sessionName, int duration, String policy,
                                   int minimumSessionTokenSize) {
        CognitoIdentityTokens.Verified verified = tokens.verify(token);
        CognitoIdentity identity = identities.publicIdentity(verified.identityId(), verified.region());
        IdentityPool pool = identities.publicPool(identity.identityPoolId(), verified.region());
        if (!identity.active() || !pool.isAllowClassicFlow()) {
            throw new AwsException("AccessDenied", "The identity is not eligible for classic authentication.", 403);
        }
        IamRole role;
        try {
            role = role(roleArn);
        } catch (AwsException e) {
            throw new AwsException("AccessDenied", "The requested role is not available.", 403);
        }
        if (policy != null) {
            try {
                JsonNode document = mapper.readTree(policy);
                if (document == null || !document.isObject() || !document.has("Statement")) {
                    throw new AwsException("MalformedPolicyDocument", "The session policy is invalid.", 400);
                }
            } catch (JsonProcessingException e) {
                throw new AwsException("MalformedPolicyDocument", "The session policy is invalid.", 400);
            }
        }
        requireTrust(role, verified);
        return issue(role, verified, sessionName, duration, policy, minimumSessionTokenSize);
    }

    private IamRole role(String roleArn) {
        if (!AwsArnUtils.isArn(roleArn)) {
            throw new AwsException("InvalidIdentityPoolConfigurationException", "A valid IAM role must be configured.", 400);
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(roleArn);
        if (!"iam".equals(parsed.service()) || !parsed.region().isEmpty() || !parsed.resource().startsWith("role/")) {
            throw new AwsException("InvalidIdentityPoolConfigurationException", "A valid IAM role must be configured.", 400);
        }
        String name = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        return iam.findRole(parsed.accountId(), name).filter(value -> IamService.roleArnMatches(roleArn, value))
                .orElseThrow(() -> new AwsException("InvalidIdentityPoolConfigurationException",
                        "The configured IAM role was not found.", 400));
    }

    private void requireTrust(IamRole role, CognitoIdentityTokens.Verified verified) {
        if (!AwsRegions.partitionFor(verified.region()).equals(AwsArnUtils.parse(role.getArn()).partition())) {
            throw new AwsException("AccessDenied", "The role and identity must belong to the same partition.", 403);
        }
        String document = role.getAssumeRolePolicyDocument();
        if (!trust.allows(document, PRINCIPAL, PRINCIPAL, verified.trustClaims())) {
            throw new AwsException("AccessDenied", "The role does not trust this Cognito identity.", 403);
        }
        if (!AwsArnUtils.parse(role.getArn()).accountId().equals(verified.accountId())
                && !trust.allows(audienceRestrictedPolicy(document), PRINCIPAL, PRINCIPAL, verified.trustClaims())) {
            throw new AwsException("AccessDenied", "Cross-account Cognito role trust must restrict the pool audience.", 403);
        }
    }

    /** Retains all denies and only allow statements that positively constrain this provider's audience. */
    private String audienceRestrictedPolicy(String document) {
        try {
            JsonNode original = mapper.readTree(document).path("Statement");
            List<JsonNode> statements = new ArrayList<>();
            if (original.isArray()) {
                original.forEach(statements::add);
            } else if (original.isObject()) {
                statements.add(original);
            }
            ObjectNode result = mapper.createObjectNode().put("Version", "2012-10-17");
            ArrayNode accepted = result.putArray("Statement");
            for (JsonNode statement : statements) {
                JsonNode conditions = statement.path("Condition");
                if ("Deny".equalsIgnoreCase(statement.path("Effect").asText())
                        || conditions.path("StringEquals").has(PRINCIPAL + ":aud")
                        || conditions.path("StringLike").has(PRINCIPAL + ":aud")
                        || conditions.path("ForAnyValue:StringEquals").has(PRINCIPAL + ":aud")) {
                    accepted.add(statement);
                }
            }
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new AwsException("AccessDenied", "The role trust policy is invalid.", 403);
        }
    }

    private Credentials issue(IamRole role, CognitoIdentityTokens.Verified verified, String sessionName,
                              int duration, String policy, int minimumSessionTokenSize) {
        int maximum = role.getMaxSessionDuration();
        if (duration < 900 || duration > maximum || duration > 43200) {
            throw new AwsException("ValidationError", "The requested duration exceeds the role session limit.", 400);
        }
        if (sessionName == null || !sessionName.matches("[A-Za-z0-9_+=,.@-]{2,64}")) {
            throw new AwsException("ValidationError", "A valid RoleSessionName is required.", 400);
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(role.getArn());
        String roleName = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        String assumedArn = AwsArnUtils.Arn.global(parsed.partition(), "sts", parsed.accountId(),
                "assumed-role/" + roleName + "/" + sessionName).toString();
        String assumedId = role.getRoleId() + ":" + sessionName;
        StringBuilder accessKeySuffix = new StringBuilder();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int index = 0; index < 16; index++) {
            accessKeySuffix.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        String accessKey = "ASIA" + accessKeySuffix;
        String secret = randomString(40);
        String sessionToken = randomString(Math.max(200, minimumSessionTokenSize));
        Instant expiration = Instant.now().plusSeconds(duration);
        iam.registerCognitoIdentitySession(verified.accountId(), accessKey, secret, sessionToken, role.getArn(),
                expiration, policy, verified.accountId(), sessionName, assumedId, verified.trustClaims());
        return new Credentials(verified.identityId(), accessKey, secret, sessionToken, expiration,
                assumedArn, assumedId, verified.issuer(), verified.poolId());
    }

    String enhancedPolicy(boolean authenticated, String region) {
        List<String> actions = new ArrayList<>(authenticated ? List.of("*") : GUEST_INLINE_ACTIONS);
        if (!authenticated) {
            actions.addAll(GUEST_MANAGED_ACTIONS);
            if (CognitoIdentityTokens.GLOBAL_ISSUER_REGIONS.contains(region)) {
                actions.addAll(DEFAULT_REGION_ACTIONS);
            }
        }
        try {
            return mapper.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(
                    Map.of("Effect", "Allow", "Action", actions, "Resource", "*"),
                    Map.of("Effect", "Deny", "Action", List.of("cognito-identity:*", "cognito-sync:*"),
                            "Resource", "*"))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to encode Cognito session policy", e);
        }
    }

    private String randomString(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
    }

    private static AwsException unauthorized() {
        return new AwsException("NotAuthorizedException", "The login token does not authorize this identity.", 400);
    }
}
