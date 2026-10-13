package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.createUser;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class DynamoDbTransactionAuthorizationIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureContentType() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete", "Get", "ConditionCheck"})
    void itemPermissionAloneAllowsItsTransactionalOperation(String operation) {
        String table = table();
        String user = user(policy("dynamodb:" + operation + "Item", ""));
        transaction(user, operation, table).statusCode(200);
        assertResult(operation, table);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete", "Get", "ConditionCheck"})
    void broadPermissionControlExecutesTheSameTransaction(String operation) {
        String table = table();
        transaction(user(policy("dynamodb:*", "")), operation, table).statusCode(200);
        assertResult(operation, table);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete", "Get", "ConditionCheck"})
    void transactionVerbAloneDoesNotGrantItemPermission(String operation) {
        String table = table();
        String user = user(policy("dynamodb:" + enclosing(operation), ""));
        transaction(user, operation, table).statusCode(400).body(containsString("AccessDeniedException"));
        request("test", "GetItem", keyBody(table)).statusCode(200).body("Item.value.S", equalTo("original"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Get"})
    void enclosingOperationConditionAllowsTransactionsButNotStandaloneCalls(String operation) {
        String table = table();
        String condition = ",\"Condition\":{\"ForAnyValue:StringEquals\":{"
                + "\"dynamodb:EnclosingOperation\":[\"" + enclosing(operation) + "\"]}}";
        String user = user(policy("dynamodb:" + operation + "Item", condition));
        transaction(user, operation, table).statusCode(200);
        request(user, operation + "Item", itemBody(operation, table)).statusCode(400)
                .body(containsString("AccessDeniedException"));
    }

    @Test
    void deniedLaterItemPreventsAllWrites() {
        String first = table();
        String second = table();
        String user = user("""
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"dynamodb:*","Resource":"*"},
                {"Effect":"Deny","Action":"dynamodb:DeleteItem","Resource":"*"}]}
                """);
        request(user, "TransactWriteItems", "{\"TransactItems\":[{\"Put\":" + itemBody("Put", first)
                + "},{\"Delete\":" + itemBody("Delete", second) + "}]}")
                .statusCode(400).body(containsString("AccessDeniedException"));
        request("test", "GetItem", keyBody(first)).statusCode(200).body("Item.value.S", equalTo("original"));
        request("test", "GetItem", keyBody(second)).statusCode(200).body("Item.value.S", equalTo("original"));
    }

    @Test
    void eachTransactionalItemContributesItsLeadingKeyWithoutNullGuard() {
        String table = table();
        String user = user(policy("dynamodb:*", ",\"Condition\":{\"ForAllValues:StringEquals\":{"
                + "\"dynamodb:LeadingKeys\":[\"allowed\"]}}"));
        transaction(user, "Put", table).statusCode(400).body(containsString("AccessDeniedException"));
        request("test", "GetItem", keyBody(table)).statusCode(200).body("Item.value.S", equalTo("original"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete", "Get", "ConditionCheck"})
    void extraEnvelopeFieldCannotBypassItemAuthorization(String operation) {
        String table = table();
        String user = user(policy("dynamodb:" + enclosing(operation), ""));
        request(user, enclosing(operation), "{\"TransactItems\":[{\"Ignored\":true,\"" + operation
                + "\":" + itemBody(operation, table) + "}]}")
                .statusCode(400).body(containsString("AccessDeniedException"));
        request("test", "GetItem", keyBody(table)).statusCode(200).body("Item.value.S", equalTo("original"));
    }

    @Test
    void eachItemUsesItsOwnActionAndTablePolicy() {
        String first = table();
        String second = table();
        String policy = """
                {"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Action":"dynamodb:PutItem",
                 "Resource":"arn:aws:dynamodb:us-east-1:000000000000:table/%s"},
                {"Effect":"Allow","Action":"dynamodb:DeleteItem",
                 "Resource":"arn:aws:dynamodb:us-east-1:000000000000:table/%s"}]}
                """.formatted(first, second);
        String user = user(policy);
        request(user, "TransactWriteItems", "{\"TransactItems\":[{\"Put\":" + itemBody("Put", first)
                + "},{\"Delete\":" + itemBody("Delete", second) + "}]}").statusCode(200);
        assertResult("Put", first);
        assertResult("Delete", second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete", "ConditionCheck"})
    void transactionFailureImagesRequireTheRequestedReturnValues(String operation) {
        String table = table();
        String user = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"StringEqualsIfExists\":{\"dynamodb:ReturnValues\":\"NONE\"}}"));
        String item = itemBody(operation, table);
        String disallowed = item.substring(0, item.length() - 1)
                + ",\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\"}";
        request(user, enclosing(operation), "{\"TransactItems\":[{\"" + operation + "\":" + disallowed + "}]}")
                .statusCode(400).body(containsString("AccessDeniedException"));
        request("test", "GetItem", keyBody(table)).statusCode(200).body("Item.value.S", equalTo("original"));
        transaction(user, operation, table).statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete", "ConditionCheck"})
    void strictReturnValuesConditionRequiresAnExplicitValue(String operation) {
        String table = table();
        String user = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":\"NONE\"}}"));
        transaction(user, operation, table).statusCode(400).body(containsString("AccessDeniedException"));
        for (String value : new String[]{"null", "\"\"", "\"ALL_OLD\""}) {
            request(user, enclosing(operation), transactionWithReturnValues(operation, table, value))
                    .statusCode(400).body(containsString("AccessDeniedException"));
        }
        request("test", "GetItem", keyBody(table)).statusCode(200).body("Item.value.S", equalTo("original"));
        request(user, enclosing(operation), transactionWithReturnValues(operation, table, "\"NONE\""))
                .statusCode(200);
        assertResult(operation, table);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete", "ConditionCheck"})
    void nullReturnValuesConditionDistinguishesMissingFromExplicitNone(String operation) {
        String table = table();
        String user = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"Null\":{\"dynamodb:ReturnValues\":\"true\"}}"));
        request(user, enclosing(operation), transactionWithReturnValues(operation, table, "\"NONE\""))
                .statusCode(400).body(containsString("AccessDeniedException"));
        request("test", "GetItem", keyBody(table)).statusCode(200).body("Item.value.S", equalTo("original"));
        transaction(user, operation, table).statusCode(200);
        assertResult(operation, table);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete", "ConditionCheck"})
    void explicitFailureImageValueCanBeAuthorized(String operation) {
        String table = table();
        String user = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":\"ALL_OLD\"}}"));
        request(user, enclosing(operation), transactionWithReturnValues(operation, table, "\"ALL_OLD\""))
                .statusCode(200);
        assertResult(operation, table);
    }

    @Test
    void explicitReturnValuesOnOneItemCannotAuthorizeAnotherItem() {
        String first = table();
        String second = table();
        String user = user(policy("dynamodb:PutItem",
                ",\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":\"NONE\"}}"));
        String firstItem = itemBody("Put", first);
        firstItem = firstItem.substring(0, firstItem.length() - 1)
                + ",\"ReturnValuesOnConditionCheckFailure\":\"NONE\"}";
        request(user, "TransactWriteItems", "{\"TransactItems\":[{\"Put\":" + firstItem
                + "},{\"Put\":" + itemBody("Put", second) + "}]}")
                .statusCode(400).body(containsString("AccessDeniedException"));
        request("test", "GetItem", keyBody(first)).statusCode(200).body("Item.value.S", equalTo("original"));
        request("test", "GetItem", keyBody(second)).statusCode(200).body("Item.value.S", equalTo("original"));
    }

    private static String transactionWithReturnValues(String operation, String table, String value) {
        String item = itemBody(operation, table);
        item = item.substring(0, item.length() - 1) + ",\"ReturnValuesOnConditionCheckFailure\":" + value + "}";
        return "{\"TransactItems\":[{\"" + operation + "\":" + item + "}]}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"Get", "Update", "ConditionCheck"})
    void itemAttributesAreEnforcedWithoutNullGuard(String operation) {
        String table = table();
        String item = itemBody(operation, table);
        if ("Get".equals(operation)) {
            item = item.substring(0, item.length() - 1)
                    + ",\"ProjectionExpression\":\"#v\",\"ExpressionAttributeNames\":{\"#v\":\"value\"}}";
        } else if ("ConditionCheck".equals(operation)) {
            item = keyBody(table);
            item = item.substring(0, item.length() - 1)
                    + ",\"ConditionExpression\":\"attribute_exists(#v)\","
                    + "\"ExpressionAttributeNames\":{\"#v\":\"value\"}}";
        }
        String request = "{\"TransactItems\":[{\"" + operation + "\":" + item + "}]}";
        String denied = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"ForAllValues:StringEquals\":{\"dynamodb:Attributes\":[\"PK\"]}}"));
        request(denied, enclosing(operation), request).statusCode(400)
                .body(containsString("AccessDeniedException"));
        request("test", "GetItem", keyBody(table)).statusCode(200).body("Item.value.S", equalTo("original"));
        String allowed = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"ForAllValues:StringEquals\":{\"dynamodb:Attributes\":[\"PK\",\"value\"]}}"));
        ValidatableResponse response = request(allowed, enclosing(operation), request).statusCode(200);
        if ("Get".equals(operation)) {
            response.body("Responses[0].Item.value.S", equalTo("original"));
        }
        assertResult(operation, table);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eachTableUsesItsOwnPartitionKeySchema(boolean forbiddenLaterKey) {
        String first = table();
        String second = table("tenant");
        String secondItem = itemBody("Put", second).replace("\"PK\"", "\"tenant\"");
        if (forbiddenLaterKey) {
            secondItem = secondItem.replace("\"item\"", "\"forbidden\"");
        }
        String caller = user(policy("dynamodb:PutItem", ",\"Condition\":{\"ForAllValues:StringEquals\":{"
                + "\"dynamodb:LeadingKeys\":[\"item\"]}}"));
        ValidatableResponse response = request(caller, "TransactWriteItems", "{\"TransactItems\":[{\"Put\":"
                + itemBody("Put", first) + "},{\"Put\":" + secondItem + "}]}");
        if (forbiddenLaterKey) {
            response.statusCode(400).body(containsString("AccessDeniedException"));
            request("test", "GetItem", keyBody(second).replace("\"PK\"", "\"tenant\"")
                    .replace("\"item\"", "\"forbidden\"")).statusCode(200).body(equalTo("{}"));
        } else {
            response.statusCode(200);
        }
        String expected = forbiddenLaterKey ? "original" : "changed";
        request("test", "GetItem", keyBody(first)).statusCode(200).body("Item.value.S", equalTo(expected));
        request("test", "GetItem", keyBody(second).replace("\"PK\"", "\"tenant\""))
                .statusCode(200).body("Item.value.S", equalTo(expected));
    }

    private void assertResult(String operation, String table) {
        ValidatableResponse response = request("test", "GetItem", keyBody(table)).statusCode(200);
        if ("Delete".equals(operation)) {
            response.body(equalTo("{}"));
        } else {
            response.body("Item.value.S", equalTo("Put".equals(operation) || "Update".equals(operation)
                    ? "changed" : "original"));
        }
    }

    private ValidatableResponse transaction(String user, String operation, String table) {
        return request(user, enclosing(operation), "{\"TransactItems\":[{\"" + operation + "\":"
                + itemBody(operation, table) + "}]}");
    }

    private static String enclosing(String operation) {
        return "Get".equals(operation) ? "TransactGetItems" : "TransactWriteItems";
    }

    private static String itemBody(String operation, String table) {
        if ("Put".equals(operation)) {
            return "{\"TableName\":\"" + table + "\",\"Item\":{\"PK\":{\"S\":\"item\"},"
                    + "\"value\":{\"S\":\"changed\"}}}";
        }
        String base = keyBody(table);
        if ("Update".equals(operation)) {
            return base.substring(0, base.length() - 1) + ",\"UpdateExpression\":\"SET #v = :v\","
                    + "\"ExpressionAttributeNames\":{\"#v\":\"value\"},"
                    + "\"ExpressionAttributeValues\":{\":v\":{\"S\":\"changed\"}}}";
        }
        if ("ConditionCheck".equals(operation)) {
            return base.substring(0, base.length() - 1) + ",\"ConditionExpression\":\"attribute_exists(PK)\"}";
        }
        return base;
    }

    private static String keyBody(String table) {
        return "{\"TableName\":\"" + table + "\",\"Key\":{\"PK\":{\"S\":\"item\"}}}";
    }

    private String table() {
        return table("PK");
    }

    private String table(String partitionKey) {
        String table = "tx-auth-" + UUID.randomUUID();
        int port = endpoint.getPort();
        cleanup.register(() -> given().port(port).header("Authorization", auth("test", "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType("application/x-amz-json-1.0").body("{\"TableName\":\"" + table + "\"}")
                .post("/").then().statusCode(200));
        request("test", "CreateTable", """
                {"TableName":"%s","KeySchema":[{"AttributeName":"%s","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"%s","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """.formatted(table, partitionKey, partitionKey)).statusCode(200);
        request("test", "PutItem", itemBody("Put", table).replace("changed", "original")
                .replace("\"PK\"", "\"" + partitionKey + "\"")).statusCode(200);
        return table;
    }

    private String user(String policy) {
        String name = "tx-auth-" + UUID.randomUUID();
        String key = createUser(cleanup, endpoint.getPort(), auth("test", "iam"), name).accessKeyId();
        given().header("Authorization", auth("test", "iam")).formParam("Action", "PutUserPolicy")
                .formParam("UserName", name).formParam("PolicyName", "transaction")
                .formParam("PolicyDocument", policy).post("/").then().statusCode(200);
        return key;
    }

    private static String policy(String action, String condition) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"*\"" + condition + "}]}";
    }

    private ValidatableResponse request(String key, String action, String body) {
        return given().header("Authorization", auth(key, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then();
    }
}
