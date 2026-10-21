package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AclHeaderValidationIntegrationTest {

    private static final String BUCKET = "acl-header-validation-bucket";
    private static final String ALL_USERS_GROUP_URI = "http://acs.amazonaws.com/groups/global/AllUsers";
    private static final String CONFLICTING_ACL_MESSAGE =
            "Specifying both Canned ACLs and Header Grants is not allowed";

    @BeforeEach
    void createBucket() {
        given()
            .header("x-amz-object-ownership", "ObjectWriter")
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
        setOwnership(BUCKET, "ObjectWriter");
    }

    @Order(2)
    @ParameterizedTest
    @ValueSource(strings = {
            "x-amz-grant-read",
            "x-amz-grant-write",
            "x-amz-grant-full-control",
            "x-amz-grant-read-acp",
            "x-amz-grant-write-acp"
    })
    void putObjectRejectsConflictingCannedAclAndGrantHeaders(String grantHeader) {
        assertInvalidRequest(given()
            .header("x-amz-acl", "private")
            .header(grantHeader, "uri=\"" + ALL_USERS_GROUP_URI + "\"")
            .body("conflict body")
        .when()
            .put("/" + BUCKET + "/conflict-put-" + grantHeader + ".txt")
        .then());
    }

    @Test
    @Order(3)
    void putObjectAllowsCannedAclWithBlankGrantHeader() {
        given()
            .header("x-amz-acl", "private")
            .header("x-amz-grant-read", "   ")
            .body("blank grant body")
        .when()
            .put("/" + BUCKET + "/blank-grant-put.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void putObjectAllowsGrantHeaderWithBlankCannedAcl() {
        String ownerId = given().when().get("/" + BUCKET + "?acl")
                .then().statusCode(200).extract().xmlPath().getString("AccessControlPolicy.Owner.ID");
        given()
            .header("x-amz-acl", "   ")
            .header("x-amz-grant-read", "id=\"" + ownerId + "\"")
            .body("blank canned acl body")
        .when()
            .put("/" + BUCKET + "/blank-canned-put.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void putBucketAclRejectsConflictingCannedAclAndGrantHeaders() {
        assertInvalidRequest(given()
            .header("x-amz-acl", "private")
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
        .when()
            .put("/" + BUCKET + "?acl")
        .then());
    }

    @Test
    @Order(6)
    void putObjectAclRejectsConflictingCannedAclAndGrantHeaders() {
        given()
            .body("target object")
        .when()
            .put("/" + BUCKET + "/acl-target.txt")
        .then()
            .statusCode(200);

        assertInvalidRequest(given()
            .header("x-amz-acl", "private")
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
        .when()
            .put("/" + BUCKET + "/acl-target.txt?acl")
        .then());
    }

    @Test
    @Order(7)
    void initiateMultipartUploadRejectsConflictingCannedAclAndGrantHeaders() {
        assertInvalidRequest(given()
            .header("x-amz-acl", "private")
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
        .when()
            .post("/" + BUCKET + "/multipart-conflict.txt?uploads")
        .then());
    }

    @Test
    @Order(8)
    void copyObjectRejectsConflictingCannedAclAndGrantHeaders() {
        given()
            .body("copy source content")
        .when()
            .put("/" + BUCKET + "/copy-source.txt")
        .then()
            .statusCode(200);

        assertInvalidRequest(given()
            .header("x-amz-copy-source", "/" + BUCKET + "/copy-source.txt")
            .header("x-amz-acl", "private")
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
        .when()
            .put("/" + BUCKET + "/copy-conflict.txt")
        .then());
    }

    @Order(9)
    @ParameterizedTest
    @ValueSource(strings = {"put", "bucket-acl", "object-acl", "multipart", "copy"})
    void conflictingHeadersPrecedeEnforcedOwnershipRejection(String operation) {
        String bucket = BUCKET + "-enforced-" + operation;
        given().when().put("/" + bucket).then().statusCode(200);
        try {
            setOwnership(bucket, "BucketOwnerEnforced");
            given().body("source").when().put("/" + bucket + "/source").then().statusCode(200);
            String ownerId = given().when().get("/" + bucket + "?acl")
                    .then().statusCode(200).extract().xmlPath().getString("AccessControlPolicy.Owner.ID");
            String grant = "id=\"" + ownerId + "\"";
            assertInvalidRequest(aclOperation(operation, bucket,
                    given().header("x-amz-acl", "private").header("x-amz-grant-read", grant)));
            aclOperation(operation, bucket, given().header("x-amz-grant-read", grant))
                    .statusCode(400)
                    .body("Error.Code", equalTo("AccessControlListNotSupported"))
                    .body("Error.Message", equalTo("The bucket does not allow ACLs"));
            given().when().head("/" + bucket + "/target").then().statusCode(404);
            given().when().get("/" + bucket + "?uploads").then().statusCode(200)
                    .body("ListMultipartUploadsResult.Upload.size()", equalTo(0));
        } finally {
            deleteBucket(bucket);
        }
    }

    private static ValidatableResponse aclOperation(String operation, String bucket,
                                                    RequestSpecification request) {
        return switch (operation) {
            case "put" -> request.body("target").when().put("/" + bucket + "/target").then();
            case "bucket-acl" -> request.when().put("/" + bucket + "?acl").then();
            case "object-acl" -> request.when().put("/" + bucket + "/source?acl").then();
            case "multipart" -> request.when().post("/" + bucket + "/target?uploads").then();
            case "copy" -> request.header("x-amz-copy-source", "/" + bucket + "/source")
                    .when().put("/" + bucket + "/target").then();
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private static void setOwnership(String bucket, String ownership) {
        given().contentType("application/xml").body("""
                <OwnershipControls xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Rule><ObjectOwnership>%s</ObjectOwnership></Rule>
                </OwnershipControls>
                """.formatted(ownership))
                .when().put("/" + bucket + "?ownershipControls").then().statusCode(200);
        given().when().get("/" + bucket + "?ownershipControls").then().statusCode(200)
                .body("OwnershipControls.Rule.ObjectOwnership", equalTo(ownership));
    }

    @AfterEach
    void deleteBucket() {
        deleteBucket(BUCKET);
    }

    private static void deleteBucket(String bucket) {
        List<String> keys = given().when().get("/" + bucket)
                .then().statusCode(200).extract().xmlPath().getList("ListBucketResult.Contents.Key");
        for (String key : keys) {
            given().when().delete("/" + bucket + "/" + key).then().statusCode(204);
        }
        given().when().delete("/" + bucket).then().statusCode(204);
    }

    private static void assertInvalidRequest(ValidatableResponse response) {
        response.statusCode(400)
                .body("Error.Code", equalTo("InvalidRequest"))
                .body("Error.Message", equalTo(CONFLICTING_ACL_MESSAGE));
    }
}
