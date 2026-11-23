package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Shared admission checks for Query and CloudFormation path-pattern conditions. */
public final class PathPatternRegexValues {

    private PathPatternRegexValues() {
    }

    public static void validate(List<String> regexValues, boolean hasGlobValues) {
        if (hasGlobValues) {
            throw new AwsException("ValidationError",
                    "A path-pattern condition can specify either Values or RegexValues, not both.", 400);
        }
        for (String regex : regexValues) {
            if (regex.isEmpty() || regex.length() > 128) {
                throw new AwsException("ValidationError",
                        "Each path-pattern regex value must be between 1 and 128 characters.", 400);
            }
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new AwsException("ValidationError",
                        "Invalid path-pattern regex value '" + regex + "'.", 400);
            }
        }
    }
}
