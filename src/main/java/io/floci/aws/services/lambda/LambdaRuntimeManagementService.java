package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.lambda.model.LambdaAlias;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.lambda.model.LambdaRuntimeManagementConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class LambdaRuntimeManagementService {

    private static final Set<String> MODES = Set.of("Auto", "FunctionUpdate", "Manual");
    private static final Pattern RUNTIME_ARN = Pattern.compile(
            "arn:(aws[a-zA-Z-]*):lambda:" // partition-literal: exact public RuntimeVersionArn validation shape.
                    + "(eusc-)?[a-z]{2}((-gov)|(-iso([a-z]?)))?-[a-z]+-\\d{1}::runtime:.+");

    private final LambdaFunctionStore functions;
    private final LambdaAliasStore aliases;
    private final RegionResolver regions;

    @Inject
    public LambdaRuntimeManagementService(
            LambdaFunctionStore functions, LambdaAliasStore aliases, RegionResolver regions) {
        this.functions = functions;
        this.aliases = aliases;
        this.regions = regions;
    }

    public record Result(String functionArn, LambdaRuntimeManagementConfig configuration) {}

    public Result get(String region, String reference, String qualifier) {
        String account = regions.getAccountId();
        LambdaRuntimeManagementReference target = LambdaRuntimeManagementReference.resolve(
                reference, qualifier, region, account);
        synchronized (functions.lockForConcurrencyOp(target.functionArn())) {
            LambdaFunction latest = requireFunction(account, region, target.name(), "$LATEST");
            String version = resolveVersion(account, region, target);
            return new Result(responseArn(target, version),
                    functions.runtimeManagementConfig(account, region, latest, version));
        }
    }

    public Result put(String region, String reference, String qualifier, JsonNode request) {
        LambdaRuntimeManagementConfig configuration = validate(request);
        String account = regions.getAccountId();
        LambdaRuntimeManagementReference target = LambdaRuntimeManagementReference.resolve(
                reference, qualifier, region, account);
        synchronized (functions.lockForConcurrencyOp(target.functionArn())) {
            LambdaFunction latest = requireFunction(account, region, target.name(), "$LATEST");
            String version = resolveVersion(account, region, target);
            functions.putRuntimeManagementConfig(account, region, latest, version, configuration);
            return new Result(responseArn(target, version), configuration);
        }
    }

    /** IAM reads only the owner's live tags, without migrating or initializing legacy state. */
    public Map<String, String> authorizationTags(
            String region, String account, String reference, String qualifier) {
        try {
            LambdaRuntimeManagementReference target = LambdaRuntimeManagementReference.resolve(
                    reference, qualifier, region, account);
            synchronized (functions.lockForConcurrencyOp(target.functionArn())) {
                LambdaFunction latest = requireFunction(account, region, target.name(), "$LATEST");
                resolveVersion(account, region, target);
                return latest.getTags() == null ? Map.of() : Map.copyOf(latest.getTags());
            }
        } catch (AwsException expected) {
            // Invalid and missing resources contribute no stored condition values.
            return Map.of();
        }
    }

    private String resolveVersion(String account, String region, LambdaRuntimeManagementReference target) {
        String version = target.qualifier() == null ? "$LATEST" : target.qualifier();
        if ("$LATEST.PUBLISHED".equals(version)) {
            throw LambdaRuntimeManagementReference.missing(target.authorizationArn());
        }
        if (!"$LATEST".equals(version) && !version.chars().allMatch(Character::isDigit)) {
            LambdaAlias alias = aliases.getReadOnlyForAccount(account, region, target.name(), version)
                    .orElseThrow(() -> LambdaRuntimeManagementReference.missing(target.authorizationArn()));
            version = alias.getFunctionVersion();
            if (version == null || version.isEmpty() || !version.chars().allMatch(Character::isDigit)) {
                throw LambdaRuntimeManagementReference.missing(target.authorizationArn());
            }
        }
        requireFunction(account, region, target.name(), version);
        return version;
    }

    private LambdaFunction requireFunction(String account, String region, String name, String version) {
        return functions.getReadOnlyForAccount(account, region, name, version)
                .orElseThrow(() -> LambdaRuntimeManagementReference.missing(name + ":" + version));
    }

    private static String responseArn(LambdaRuntimeManagementReference target, String version) {
        return "$LATEST".equals(version) ? target.functionArn() : target.authorizationArn();
    }

    static LambdaRuntimeManagementConfig validate(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw LambdaRuntimeManagementReference.invalid("Request body must be a JSON object");
        }
        JsonNode modeNode = request.get("UpdateRuntimeOn");
        if (modeNode == null || !modeNode.isTextual() || !MODES.contains(modeNode.textValue())) {
            throw LambdaRuntimeManagementReference.invalid(
                    "UpdateRuntimeOn must be Auto, FunctionUpdate, or Manual");
        }
        String mode = modeNode.textValue();
        JsonNode pinNode = request.get("RuntimeVersionArn");
        String pin = null;
        if (pinNode != null && !pinNode.isNull()) {
            if (!pinNode.isTextual() || pinNode.textValue().length() < 26
                    || pinNode.textValue().length() > 2048 || !RUNTIME_ARN.matcher(pinNode.textValue()).matches()) {
                throw LambdaRuntimeManagementReference.invalid("Invalid RuntimeVersionArn");
            }
            pin = pinNode.textValue();
        }
        if ("Manual".equals(mode) && pin == null) {
            throw LambdaRuntimeManagementReference.invalid("RuntimeVersionArn is required for Manual mode");
        }
        return new LambdaRuntimeManagementConfig(mode, "Manual".equals(mode) ? pin : null);
    }
}
