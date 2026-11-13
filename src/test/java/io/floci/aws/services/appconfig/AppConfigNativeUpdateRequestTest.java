package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigNativeUpdateRequestTest {
    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "1", "true", "{", ""})
    void nonObjectOrMalformedJsonIsABadRequest(String body) {
        AwsException error = assertThrows(AwsException.class,
                () -> AppConfigUpdateRequest.read(new ObjectMapper(), body));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }
}
