package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentity.CognitoIdentityClient;
import software.amazon.awssdk.services.cognitoidentity.model.Credentials;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.Get;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CognitoIdentityContextIamIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String PRINCIPAL = "cognito-identity.amazonaws.com";
    private static final AwsCredentialsProvider ROOT = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    @TestHTTPResource URI endpoint;
    @Inject IamService iamService;

    @Test
    void conditionalDenyAppliesToTransactionItemsAndCopySourceWithWrongAndMissingClaimControls() throws Exception {
        String name = "cognito-context-" + UUID.randomUUID().toString().replace("-", "");
        String tableArn = "arn:aws:dynamodb:us-east-1:" + ACCOUNT + ":table/" + name;
        String objectArn = "arn:aws:s3:::" + name + "/source";
        String roleName = name;
        String userName = name;
        String sessionKey = null;
        String userKey = null;
        boolean roleCreated = false;
        boolean userCreated = false;
        boolean tableCreated = false;
        boolean bucketCreated = false;
        try (IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                     .credentialsProvider(ROOT).build();
             CognitoIdentityClient admin = identities(ROOT);
             CognitoIdentityClient publicClient = identities(AnonymousCredentialsProvider.create());
             DynamoDbClient rootDdb = dynamoDb(ROOT); S3Client rootS3 = s3(ROOT)) {
            String pool = admin.createIdentityPool(r -> r.identityPoolName(name).allowUnauthenticatedIdentities(true)).identityPoolId();
            try {
                String trust = new ObjectMapper().writeValueAsString(Map.of("Statement", Map.of("Effect", "Allow",
                        "Principal", Map.of("Federated", PRINCIPAL), "Action", "sts:AssumeRoleWithWebIdentity",
                        "Condition", Map.of("StringEquals", Map.of(PRINCIPAL + ":aud", pool),
                                "ForAnyValue:StringEquals", Map.of(PRINCIPAL + ":amr", "unauthenticated")))));
                String roleArn = iam.createRole(r -> r.roleName(roleName).assumeRolePolicyDocument(trust)).role().arn();
                roleCreated = true;
                String wrongAudience = "us-east-1:ffffffff-ffff-ffff-ffff-ffffffffffff";
                String wrongPolicy = policy(wrongAudience, tableArn, objectArn, name);
                iam.putRolePolicy(r -> r.roleName(roleName).policyName("context").policyDocument(wrongPolicy));
                admin.setIdentityPoolRoles(r -> r.identityPoolId(pool).roles(Map.of("unauthenticated", roleArn)));
                rootDdb.createTable(r -> r.tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                        .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                        .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build()));
                tableCreated = true;
                rootS3.createBucket(r -> r.bucket(name));
                bucketCreated = true;
                rootS3.putObject(r -> r.bucket(name).key("source"), RequestBody.fromString("source-content"));
                String identity = publicClient.getId(r -> r.identityPoolId(pool)).identityId();
                Credentials credentials = publicClient.getCredentialsForIdentity(r -> r.identityId(identity)).credentials();
                sessionKey = credentials.accessKeyId();
                AwsCredentialsProvider guest = StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        credentials.accessKeyId(), credentials.secretKey(), credentials.sessionToken()));
                try (DynamoDbClient guestDdb = dynamoDb(guest); S3Client guestS3 = s3(guest)) {
                    // A nonmatching condition must not turn into an unconditional refusal.
                    write(guestDdb, name, "existing", "before");
                    assertEquals("before", read(guestDdb, name, "existing").get("value").s());
                    guestS3.copyObject(r -> r.bucket(name).key("wrong-audience-control").copySource(name + "/source"));
                    assertEquals("source-content", rootS3.getObjectAsBytes(r -> r.bucket(name)
                            .key("wrong-audience-control")).asUtf8String());
                    String deniedPolicy = policy(pool, tableArn, objectArn, name);
                    iam.putRolePolicy(r -> r.roleName(roleName).policyName("context").policyDocument(deniedPolicy));
                    DynamoDbException writeDenied = assertThrows(DynamoDbException.class, () -> guestDdb.transactWriteItems(r ->
                            r.transactItems(put(name, "existing", "changed"), put(name, "new", "not-created"))));
                    assertEquals("AccessDeniedException", writeDenied.awsErrorDetails().errorCode());
                    assertEquals("AccessDeniedException", assertThrows(DynamoDbException.class,
                            () -> read(guestDdb, name, "existing")).awsErrorDetails().errorCode());
                    assertEquals("before", rootDdb.getItem(r -> r.tableName(name).key(key("existing")))
                            .item().get("value").s());
                    assertTrue(rootDdb.getItem(r -> r.tableName(name).key(key("new"))).item().isEmpty());
                    assertEquals(403, assertThrows(S3Exception.class, () -> guestS3.copyObject(r ->
                            r.bucket(name).key("denied-copy").copySource(name + "/source"))).statusCode());
                    assertEquals(404, assertThrows(S3Exception.class, () -> rootS3.headObject(r ->
                            r.bucket(name).key("denied-copy"))).statusCode());
                    assertEquals("source-content", rootS3.getObjectAsBytes(r -> r.bucket(name).key("source")).asUtf8String());
                    // The same policy on an IAM user has no Cognito facts and must retain its broad allow.
                    iam.createUser(r -> r.userName(userName));
                    userCreated = true;
                    iam.putUserPolicy(r -> r.userName(userName).policyName("context").policyDocument(deniedPolicy));
                    AccessKey accessKey = iam.createAccessKey(r -> r.userName(userName)).accessKey();
                    userKey = accessKey.accessKeyId();
                    AwsCredentialsProvider ordinary = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            accessKey.accessKeyId(), accessKey.secretAccessKey()));
                    try (DynamoDbClient userDdb = dynamoDb(ordinary); S3Client userS3 = s3(ordinary)) {
                        write(userDdb, name, "ordinary", "no-cognito-facts");
                        assertEquals("no-cognito-facts", read(userDdb, name, "ordinary").get("value").s());
                        userS3.copyObject(r -> r.bucket(name).key("ordinary-control").copySource(name + "/source"));
                        assertEquals("source-content", rootS3.getObjectAsBytes(r -> r.bucket(name)
                                .key("ordinary-control")).asUtf8String());
                    }
                }
            } finally {
                if (sessionKey != null) {
                    iamService.unregisterSession(ACCOUNT, sessionKey);
                }
                if (bucketCreated) {
                    for (String key : List.of("source", "wrong-audience-control", "denied-copy", "ordinary-control")) {
                        rootS3.deleteObject(r -> r.bucket(name).key(key));
                    }
                    rootS3.deleteBucket(r -> r.bucket(name));
                }
                if (tableCreated) {
                    rootDdb.deleteTable(r -> r.tableName(name));
                }
                if (userCreated) {
                    if (userKey != null) {
                        String key = userKey;
                        iam.deleteAccessKey(r -> r.userName(userName).accessKeyId(key));
                    }
                    iam.deleteUserPolicy(r -> r.userName(userName).policyName("context"));
                    iam.deleteUser(r -> r.userName(userName));
                }
                if (roleCreated) {
                    iam.deleteRolePolicy(r -> r.roleName(roleName).policyName("context"));
                    iam.deleteRole(r -> r.roleName(roleName));
                }
                admin.deleteIdentityPool(r -> r.identityPoolId(pool));
            }
        }
    }

    private String policy(String audience, String tableArn, String objectArn, String bucket) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("Statement", List.of(
                Map.of("Effect", "Allow", "Action", List.of("dynamodb:*", "s3:*"),
                        "Resource", List.of(tableArn, "arn:aws:s3:::" + bucket + "/*")),
                Map.of("Effect", "Deny", "Action", List.of("dynamodb:PutItem", "dynamodb:GetItem", "s3:GetObject"),
                        "Resource", List.of(tableArn, objectArn), "Condition", Map.of(
                                "StringEquals", Map.of(PRINCIPAL + ":aud", audience),
                                "ForAnyValue:StringEquals", Map.of(PRINCIPAL + ":amr", "unauthenticated"))))));
    }

    private Map<String, AttributeValue> key(String key) {
        return Map.of("pk", AttributeValue.builder().s(key).build());
    }

    private TransactWriteItem put(String table, String key, String value) {
        return TransactWriteItem.builder().put(Put.builder().tableName(table).item(Map.of(
                "pk", AttributeValue.builder().s(key).build(), "value", AttributeValue.builder().s(value).build())).build()).build();
    }

    private void write(DynamoDbClient client, String table, String key, String value) {
        client.transactWriteItems(r -> r.transactItems(put(table, key, value)));
    }

    private Map<String, AttributeValue> read(DynamoDbClient client, String table, String key) {
        return client.transactGetItems(r -> r.transactItems(TransactGetItem.builder()
                .get(Get.builder().tableName(table).key(key(key)).build()).build())).responses().getFirst().item();
    }

    private DynamoDbClient dynamoDb(AwsCredentialsProvider credentials) {
        return DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1).credentialsProvider(credentials).build();
    }

    private S3Client s3(AwsCredentialsProvider credentials) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1).credentialsProvider(credentials)
                .forcePathStyle(true).build();
    }

    private CognitoIdentityClient identities(AwsCredentialsProvider credentials) {
        return CognitoIdentityClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
    }
}
