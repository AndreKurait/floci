package io.floci.aws.services.acmpca;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class PrivateCaRequestsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"a", "𐐀"})
    void tagLimitsCountUnicodeCharacters(String letter) {
        String key = letter.repeat(128);
        String value = letter.repeat(256);
        ObjectNode request = tags(key, value);
        assertEquals(key, PrivateCaRequests.tags(request).getFirst().key());
        assertEquals(value, PrivateCaRequests.tags(request).getFirst().value());
        assertEquals("InvalidTagException", assertThrows(AwsException.class,
                () -> PrivateCaRequests.tags(tags(key + letter, value))).getErrorCode());
        assertEquals("InvalidTagException", assertThrows(AwsException.class,
                () -> PrivateCaRequests.tags(tags(key, value + letter))).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"😀", "\n", "\uD801"})
    void unsupportedCharactersRemainInvalid(String character) {
        assertThrows(AwsException.class, () -> PrivateCaRequests.tags(tags(character, "value")));
        assertThrows(AwsException.class, () -> PrivateCaRequests.tags(tags("key", character)));
    }

    private static ObjectNode tags(String key, String value) {
        ObjectNode request = MAPPER.createObjectNode();
        request.putArray("Tags").addObject().put("Key", key).put("Value", value);
        return request;
    }
}
