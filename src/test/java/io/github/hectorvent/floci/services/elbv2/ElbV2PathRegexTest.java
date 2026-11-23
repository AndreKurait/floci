package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code PathPatternConfig.RegexValues}: validation and the data plane's full-path match. */
class ElbV2PathRegexTest {

    /** The two values an OpenSearch read-traffic split rule carries (anchored alternations of read globs). */
    static final List<String> READ_REGEXES = List.of(
            "^(.*/_search.*|.*/_msearch.*|.*/_count.*|.*/_mget.*|.*/_explain.*|.*/_validate.*|.*/_field_caps.*|.*/_terms_enum.*)$",
            "^(.*/_rank_eval.*|.*/_render.*)$");

    @Test
    void readRegexesMatchReadPathsOnly() {
        List<Pattern> patterns = ElbV2DataPlane.compilePathRegexes(READ_REGEXES);
        for (String read : List.of("/_search", "/idx/_search", "/idx/_search/template", "/a,b/_count",
                "/_mget", "/idx/_explain/1", "/idx/_validate/query", "/_field_caps", "/idx/_terms_enum",
                "/idx/_rank_eval", "/_render/template", "/_msearch")) {
            assertTrue(ElbV2DataPlane.pathRegexMatches(patterns, read), read);
        }
        for (String other : List.of("/", "/idx/_doc/1", "/_bulk", "/idx", "/_cluster/health", "_search")) {
            assertFalse(ElbV2DataPlane.pathRegexMatches(patterns, other), other);
        }
    }

    @Test
    void matchIsAgainstTheWholePath() {
        List<Pattern> unanchored = ElbV2DataPlane.compilePathRegexes(List.of("/api"));
        assertTrue(ElbV2DataPlane.pathRegexMatches(unanchored, "/api"));
        assertFalse(ElbV2DataPlane.pathRegexMatches(unanchored, "/api/v1"));
        assertFalse(ElbV2DataPlane.pathRegexMatches(unanchored, "/v1/api"));
    }

    @Test
    void matchIsCaseSensitive() {
        List<Pattern> patterns = ElbV2DataPlane.compilePathRegexes(List.of("/Api/.*"));
        assertTrue(ElbV2DataPlane.pathRegexMatches(patterns, "/Api/x"));
        assertFalse(ElbV2DataPlane.pathRegexMatches(patterns, "/api/x"));
    }

    @Test
    void anExpressionThatDoesNotCompileNeverMatches() {
        List<Pattern> patterns = ElbV2DataPlane.compilePathRegexes(List.of("(", "/ok"));
        assertEquals(1, patterns.size());
        assertTrue(ElbV2DataPlane.pathRegexMatches(patterns, "/ok"));
        assertFalse(ElbV2DataPlane.pathRegexMatches(patterns, null));
    }

    @Test
    void validationAcceptsTheReadRegexes() {
        assertDoesNotThrow(() -> ElbV2QueryHandler.validatePathPatternRegexValues(READ_REGEXES, false));
    }

    @Test
    void validationRejectsValuesTogetherWithRegexValues() {
        AwsException e = assertThrows(AwsException.class,
                () -> ElbV2QueryHandler.validatePathPatternRegexValues(List.of("^/a$"), true));
        assertEquals("ValidationError", e.getErrorCode());
    }

    @Test
    void validationRejectsInvalidAndOverlongExpressions() {
        assertThrows(AwsException.class,
                () -> ElbV2QueryHandler.validatePathPatternRegexValues(List.of("(unclosed"), false));
        assertThrows(AwsException.class,
                () -> ElbV2QueryHandler.validatePathPatternRegexValues(List.of("a".repeat(129)), false));
        assertDoesNotThrow(() -> ElbV2QueryHandler.validatePathPatternRegexValues(List.of("a".repeat(128)), false));
    }
}
