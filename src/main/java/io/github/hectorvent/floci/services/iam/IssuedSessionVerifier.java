package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Verifies current issuer records without exporting credentials or granting permissions. */
@ApplicationScoped
public class IssuedSessionVerifier {
    private static final Pattern ACCESS_KEY = Pattern.compile("ASIA[A-Z0-9]{16}");
    private static final Pattern PRINCIPAL_ID = Pattern.compile("AROA[A-Z0-9]+:[A-Za-z0-9_+=,.@-]{2,64}");
    private static final Pattern PRINCIPAL_ARN = Pattern.compile("arn:" + AwsArnUtils.PARTITION_REGEX
            + ":sts::[0-9]{12}:assumed-role/[A-Za-z0-9_+=,.@-]{1,64}/[A-Za-z0-9_+=,.@-]{2,64}");
    private static final DateTimeFormatter SIGNED_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withResolverStyle(ResolverStyle.STRICT);
    private final IamService iam;
    private final Clock clock;

    @Inject
    public IssuedSessionVerifier(IamService iam) {
        this(iam, Clock.systemUTC());
    }

    IssuedSessionVerifier(IamService iam, Clock clock) {
        this.iam = iam;
        this.clock = clock;
    }

    /** Safe response only. Lookup does not authenticate; Verify authenticates the supplied proof only. */
    @RegisterForReflection
    public record Identity(String correlationId, String operation, String kind, String accountId,
                           String originAccountId, String roleArn, String roleId, String roleSessionName,
                           String principalArn, String principalId, String expiresAt, boolean sessionPolicyPresent) {}

    record Snapshot(String accessKey, String secret, String token, String account, String origin,
                            String roleArn, String roleId, String sessionName, String principalArn,
                            String principalId, Instant expiry, boolean policyPresent) {
        @Override
        public String toString() {
            return "IssuedSessionSnapshot[redacted]";
        }

        Identity identity(String correlation, String operation, Instant expiration) {
            return new Identity(correlation, operation, "ASSUMED_ROLE", account, origin, roleArn, roleId,
                    sessionName, principalArn, principalId, expiration.toString(), policyPresent);
        }
    }

    Identity verify(Map<String, String> request) {
        List<Snapshot> initial = snapshots("accessKeyId", request.get("accessKeyId"));
        require(initial.size() == 1);
        Snapshot session = initial.getFirst();
        verifyProof(session, request, clock, 900, 300);
        require(initial.equals(snapshots("accessKeyId", request.get("accessKeyId"))));
        return session.identity(request.get("correlationId"), "Verify", session.expiry());
    }

    static Instant verifyProof(Snapshot session, Map<String, String> request, Clock clock,
                               long maxAgeSeconds, long futureSkewSeconds) {
        String[] scope = validateStringToSign(request.get("stringToSign"), clock, maxAgeSeconds, futureSkewSeconds);
        require(constantEquals(session.token(), request.get("sessionToken")));
        try {
            byte[] signingKey = SigV4RequestValidator.deriveSigningKey(session.secret(), scope[0], scope[1], scope[2]);
            byte[] expected;
            try {
                expected = SigV4RequestValidator.hmacSha256(signingKey, request.get("stringToSign"));
            } finally {
                Arrays.fill(signingKey, (byte) 0);
            }
            require(MessageDigest.isEqual(expected, HexFormat.of().parseHex(request.get("signature"))));
            return LocalDateTime.parse(request.get("stringToSign").split("\n", -1)[1], SIGNED_TIME)
                    .toInstant(ZoneOffset.UTC);
        } catch (Exception error) {
            throw refused();
        }
    }

    Identity lookup(Map<String, String> request) {
        String selector = request.keySet().stream().filter(key -> !key.equals("correlationId"))
                .findFirst().orElseThrow(IssuedSessionVerifier::refused);
        List<Snapshot> initial = snapshots(selector, request.get(selector));
        require(!initial.isEmpty() && initial.size() <= 128);
        Snapshot first = initial.getFirst();
        Identity reference = first.identity("", "Lookup", Instant.EPOCH);
        for (Snapshot candidate : initial) {
            require(reference.equals(candidate.identity("", "Lookup", Instant.EPOCH)));
        }
        Instant expiry = initial.stream().map(Snapshot::expiry).min(Comparator.naturalOrder()).orElseThrow();
        require(initial.equals(snapshots(selector, request.get(selector))));
        return first.identity(request.get("correlationId"), "Lookup", expiry);
    }

    static boolean validSelector(String selector, String value) {
        return switch (selector) {
            case "accessKeyId" -> ACCESS_KEY.matcher(value).matches();
            case "principalId" -> PRINCIPAL_ID.matcher(value).matches();
            case "principalArn" -> PRINCIPAL_ARN.matcher(value).matches();
            default -> false;
        };
    }

    private List<Snapshot> snapshots(String selector, String value) {
        List<Snapshot> result = new ArrayList<>();
        for (SessionCredential candidate : iam.issuedSessionCandidates(
                selector.equals("accessKeyId") ? value : null)) {
            Snapshot snapshot = snapshot(candidate);
            if (snapshot == null) {
                continue;
            }
            String selected = switch (selector) {
                case "accessKeyId" -> snapshot.accessKey();
                case "principalId" -> snapshot.principalId();
                case "principalArn" -> snapshot.principalArn();
                default -> throw refused();
            };
            if (value.equals(selected)) {
                result.add(snapshot);
                require(result.size() <= 128);
            }
        }
        result.sort(Comparator.comparing(Snapshot::accessKey));
        return List.copyOf(result);
    }

    private Snapshot snapshot(SessionCredential session) {
        return snapshot(session, false);
    }

    Snapshot snapshot(SessionCredential session, boolean lambdaRoot) {
        if (lambdaRoot ? !session.isLambdaExecutionRole() || session.getLambdaExecution() == null
                : !session.isAssumeRoleIssued() || session.isLambdaExecutionRole()) {
            return null;
        }
        if (session.getEc2InstanceId() != null || session.getEcsTaskArn() != null
                || session.getPresignedAction() != null || session.getIssuerArn() != null
                || (!lambdaRoot && session.getExpiration() == null)
                || (session.getExpiration() != null && !session.getExpiration().isAfter(clock.instant()))
                || !nonempty(session.getSecretAccessKey()) || !nonempty(session.getSessionToken())
                || !nonempty(session.getRoleArn()) || !nonempty(session.getRoleSessionName())
                || !nonempty(session.getAssumedRoleId()) || !nonempty(session.getOriginAccountId())
                || !session.getOriginAccountId().matches("[0-9]{12}")) {
            return null;
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(session.getRoleArn());
        } catch (IllegalArgumentException error) {
            return null;
        }
        if (!arn.partition().matches(AwsArnUtils.PARTITION_REGEX) || !arn.service().equals("iam")
                || !arn.region().isEmpty() || !arn.accountId().matches("[0-9]{12}")
                || !arn.resource().startsWith("role/")) {
            return null;
        }
        String roleName = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        IamRole role = iam.findRole(arn.accountId(), roleName).orElse(null);
        if (role == null || !Objects.equals(role.getArn(), session.getRoleArn())
                || !nonempty(role.getRoleId()) || !session.getAssumedRoleId().equals(
                role.getRoleId() + ":" + session.getRoleSessionName())) {
            return null;
        }
        String principalArn = AwsArnUtils.Arn.global(arn.partition(), "sts", arn.accountId(),
                "assumed-role/" + roleName + "/" + session.getRoleSessionName()).toString();
        return new Snapshot(session.getAccessKeyId(), session.getSecretAccessKey(), session.getSessionToken(),
                arn.accountId(), session.getOriginAccountId(), role.getArn(), role.getRoleId(),
                session.getRoleSessionName(), principalArn, session.getAssumedRoleId(),
                session.getExpiration() == null ? Instant.MAX : session.getExpiration(),
                session.getSessionPolicyDocument() != null || session.isManagedSessionPolicyPresent());
    }

    private static String[] validateStringToSign(String value, Clock clock, long maxAgeSeconds,
                                                 long futureSkewSeconds) {
        try {
            String[] lines = value.split("\n", -1);
            require(lines.length == 4 && lines[0].equals("AWS4-HMAC-SHA256")
                    && lines[3].matches("[a-fA-F0-9]{64}"));
            Instant signed = LocalDateTime.parse(lines[1], SIGNED_TIME).toInstant(ZoneOffset.UTC);
            Instant now = clock.instant();
            require(!signed.isBefore(now.minusSeconds(maxAgeSeconds)) && !signed.isAfter(now.plusSeconds(futureSkewSeconds)));
            String[] scope = lines[2].split("/", -1);
            require(scope.length == 4 && scope[0].equals(lines[1].substring(0, 8))
                    && scope[1].matches("[a-z][a-z0-9-]{0,62}")
                    && scope[2].matches("[A-Za-z][A-Za-z0-9_-]{0,63}") && scope[3].equals("aws4_request"));
            return scope;
        } catch (RuntimeException error) {
            throw refused();
        }
    }

    private static boolean nonempty(String value) {
        return value != null && !value.isEmpty();
    }

    private static boolean constantEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw refused();
        }
    }

    private static IllegalArgumentException refused() {
        return new IllegalArgumentException("Issued-session verification refused");
    }
}
