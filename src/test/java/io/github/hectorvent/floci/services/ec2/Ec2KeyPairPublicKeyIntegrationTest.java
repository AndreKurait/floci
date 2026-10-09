package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class Ec2KeyPairPublicKeyIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261009/us-east-1/ec2/aws4_request";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicKeyIsReturnedOnlyWhenRequested(boolean imported) {
        String name = "public-key-" + UUID.randomUUID();
        Ec2KeyMaterial.Generated generated = Ec2KeyMaterial.generateRsa();
        RequestSpecification create = given().header("Authorization", AUTH)
                .formParam("Action", imported ? "ImportKeyPair" : "CreateKeyPair")
                .formParam("KeyName", name);
        if (imported) {
            create.formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(
                    generated.openSshPublicKey().getBytes(StandardCharsets.UTF_8)));
        }
        String responseName = imported ? "ImportKeyPairResponse" : "CreateKeyPairResponse";
        String id = create.post("/").then().statusCode(200)
                .extract().path(responseName + ".keyPairId");
        try {
            for (String selection : new String[]{"KeyName.1", "KeyPairId.1"}) {
                String value = selection.startsWith("KeyName") ? name : id;
                for (String include : new String[]{null, "false", "true"}) {
                    RequestSpecification describe = given().header("Authorization", AUTH)
                            .formParam("Action", "DescribeKeyPairs").formParam(selection, value);
                    if (include != null) {
                        describe.formParam("IncludePublicKey", include);
                    }
                    ValidatableResponse response = describe.post("/").then().statusCode(200)
                            .body("DescribeKeyPairsResponse.keySet.item.keyName", equalTo(name))
                            .body("DescribeKeyPairsResponse.keySet.item.keyPairId", equalTo(id))
                            .body("DescribeKeyPairsResponse.keySet.item.keyMaterial.size()", equalTo(0))
                            .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()",
                                    equalTo("true".equals(include) ? 1 : 0));
                    if ("true".equals(include)) {
                        String publicKey = response.extract().path(
                                "DescribeKeyPairsResponse.keySet.item.publicKey");
                        assertFalse(publicKey.isBlank());
                        if (imported) {
                            assertEquals(generated.openSshPublicKey(), publicKey);
                        } else {
                            assertFalse(Ec2KeyMaterial.fingerprintOf(publicKey).isBlank());
                        }
                    }
                }
            }
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                    .formParam("KeyPairId", id).post("/").then().statusCode(200);
        }
    }
}
