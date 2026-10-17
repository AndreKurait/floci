package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmithyValidationErrorsTest {

    @Test
    void oneViolation_isSingular() {
        assertEquals("1 validation error detected: Value at 'a' failed to satisfy constraint: x",
                SmithyValidationErrors.message(List.of("Value at 'a' failed to satisfy constraint: x")));
    }

    @Test
    void severalViolations_arePluralAndJoinedInOrder() {
        assertEquals("2 validation errors detected: second; first",
                SmithyValidationErrors.message(List.of("second", "first")));
    }

    @Test
    void exception_carriesTheGivenCodeAsA400() {
        AwsException e = SmithyValidationErrors.exception("ValidationError", List.of("v"));
        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("1 validation error detected: v", e.getMessage());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void noViolations_isAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> SmithyValidationErrors.message(List.of()));
    }
}
