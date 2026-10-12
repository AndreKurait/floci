package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SesSendAddressListTypeIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/email/aws4_request";
    private static final String TO = "success@simulator.amazonses.com";

    private static void assertSerializationException(String path, String body) {
        given()
            .contentType("application/json")
            .header("Authorization", AUTH)
            .body(body)
        .when()
            .post(path)
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    private static String bulkSend(String replyTo, String entryDestination) {
        return """
            {
                "FromEmailAddress": "sender@example.com",
                %s
                "DefaultContent": {"Template": {
                    "TemplateContent": {"Subject": "s", "Text": "b"},
                    "TemplateData": "{}"}},
                "BulkEmailEntries": [{"Destination": %s}]
            }
            """.formatted(replyTo, entryDestination);
    }

    @Test
    void v2SendEmail_nonArrayReplyTo_returnsSerializationException() {
        assertSerializationException("/v2/email/outbound-emails", """
            {
                "FromEmailAddress": "sender@example.com",
                "Destination": {"ToAddresses": ["%s"]},
                "ReplyToAddresses": "reply@example.com",
                "Content": {"Simple": {"Subject": {"Data": "s"}, "Body": {"Text": {"Data": "b"}}}}
            }
            """.formatted(TO));
    }

    @Test
    void v2SendBulkEmail_nonArrayReplyTo_returnsSerializationException() {
        assertSerializationException("/v2/email/outbound-bulk-emails",
                bulkSend("\"ReplyToAddresses\": \"reply@example.com\",", "{\"ToAddresses\": [\"" + TO + "\"]}"));
    }

    @Test
    void v2SendBulkEmail_nonArrayEntryDestinationMember_returnsSerializationException() {
        assertSerializationException("/v2/email/outbound-bulk-emails",
                bulkSend("", "{\"ToAddresses\": [\"" + TO + "\"], \"CcAddresses\": \"bad-shape\"}"));
    }
}
