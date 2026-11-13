package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.CreateFunctionResponse;
import software.amazon.awssdk.services.cloudfront.model.CreateKeyValueStoreResponse;
import software.amazon.awssdk.services.cloudfront.model.DescribeFunctionResponse;
import software.amazon.awssdk.services.cloudfront.model.KeyValueStoreAssociation;
import software.amazon.awssdk.services.cloudfront.model.ListKeyValueStoresResponse;
import software.amazon.awssdk.services.cloudfront.model.UpdateKeyValueStoreResponse;
import software.amazon.awssdk.services.cloudfrontkeyvaluestore.CloudFrontKeyValueStoreClient;
import software.amazon.awssdk.services.cloudfrontkeyvaluestore.model.CloudFrontKeyValueStoreException;
import software.amazon.awssdk.services.cloudfrontkeyvaluestore.model.DeleteKeyRequestListItem;
import software.amazon.awssdk.services.cloudfrontkeyvaluestore.model.DeleteKeyResponse;
import software.amazon.awssdk.services.cloudfrontkeyvaluestore.model.DescribeKeyValueStoreResponse;
import software.amazon.awssdk.services.cloudfrontkeyvaluestore.model.PutKeyRequestListItem;
import software.amazon.awssdk.services.cloudfrontkeyvaluestore.model.PutKeyResponse;
import software.amazon.awssdk.services.cloudfrontkeyvaluestore.model.UpdateKeysResponse;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native CloudFront XML and KVS JSON clients, including canonical SigV4A credential parsing and scoped IAM. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudFrontKeyValueStoreSdkIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void nativeDataLifecycleUsesIndependentVersionsAndPopulatedPagination() {
        AtomicInteger signedRequests = new AtomicInteger();
        try (CloudFrontClient control = control("test", "test");
             CloudFrontKeyValueStoreClient data = data("test", "test", false, signedRequests)) {
            String name = uniqueName();
            CreateKeyValueStoreResponse created = control.createKeyValueStore(r -> r.name(name).comment("original"));
            String arn = created.keyValueStore().arn();
            String controlVersion = created.eTag();
            try {
                DescribeKeyValueStoreResponse empty = data.describeKeyValueStore(r -> r.kvsARN(arn));
                assertEquals(arn, empty.kvsARN());
                assertEquals(0, empty.itemCount().intValue());
                assertNotNull(empty.created());
                assertNotEquals(controlVersion, empty.eTag());
                PutKeyResponse put =
                        data.putKey(r -> r.kvsARN(arn).ifMatch(empty.eTag()).key("nested/key").value("unicode-é"));
                assertEquals("unicode-é", data.getKey(r -> r.kvsARN(arn).key("nested/key")).value());
                UpdateKeysResponse batch = data.updateKeys(r -> r.kvsARN(arn).ifMatch(put.eTag())
                        .puts(PutKeyRequestListItem.builder().key("alpha").value("1").build(),
                                PutKeyRequestListItem.builder().key("beta").value("2").build(),
                                PutKeyRequestListItem.builder().key("gamma").value("3").build())
                        .deletes(DeleteKeyRequestListItem.builder().key("nested/key").build()));
                assertEquals(3, batch.itemCount().intValue());
                Map<String, String> values = new LinkedHashMap<>();
                AtomicInteger pages = new AtomicInteger();
                data.listKeysPaginator(r -> r.kvsARN(arn).maxResults(1)).forEach(page -> {
                    pages.incrementAndGet();
                    assertEquals(1, page.items().size());
                    page.items().forEach(item -> assertEquals(null, values.put(item.key(), item.value())));
                });
                assertEquals(3, pages.get());
                assertEquals(Map.of("alpha", "1", "beta", "2", "gamma", "3"), values);
                assertDataError("ResourceNotFoundException", 404,
                        () -> data.getKey(r -> r.kvsARN(arn).key("nested/key")));
                assertDataError("ValidationException", 400,
                        () -> data.putKey(r -> r.kvsARN(arn).ifMatch(put.eTag()).key("lost").value("no")));
                assertEquals(batch.eTag(), data.describeKeyValueStore(r -> r.kvsARN(arn)).eTag());
                assertEquals(controlVersion, control.describeKeyValueStore(r -> r.name(name)).eTag());
                UpdateKeyValueStoreResponse changed =
                        control.updateKeyValueStore(r -> r.name(name).ifMatch(controlVersion).comment("updated"));
                assertEquals(batch.eTag(), data.describeKeyValueStore(r -> r.kvsARN(arn)).eTag());
                DeleteKeyResponse deleted = data.deleteKey(r -> r.kvsARN(arn).ifMatch(batch.eTag()).key("alpha"));
                assertEquals(2, deleted.itemCount().intValue());
                assertEquals(changed.eTag(), control.describeKeyValueStore(r -> r.name(name)).eTag());
                assertDataError("ResourceNotFoundException", 404,
                        () -> data.getKey(r -> r.kvsARN(arn).key("alpha")));
                assertTrue(signedRequests.get() >= 10);
            } finally {
                String current = control.describeKeyValueStore(r -> r.name(name)).eTag();
                control.deleteKeyValueStore(r -> r.name(name).ifMatch(current));
            }
            assertDataError("ResourceNotFoundException", 404, () -> data.describeKeyValueStore(r -> r.kvsARN(arn)));
        }
    }

    @Test
    void globalStoresWithTheSameNameStayInTheirOwningAccount() {
        try (CloudFrontClient first = control("000000000000", "test");
             CloudFrontClient other = control("111111111111", "test");
             CloudFrontKeyValueStoreClient firstData = data("000000000000", "test", false, new AtomicInteger());
             CloudFrontKeyValueStoreClient otherData = data("111111111111", "test", false, new AtomicInteger())) {
            String name = uniqueName();
            CreateKeyValueStoreResponse a = first.createKeyValueStore(r -> r.name(name).comment("first"));
            CreateKeyValueStoreResponse b = other.createKeyValueStore(r -> r.name(name).comment("other"));
            String arnA = a.keyValueStore().arn();
            String arnB = b.keyValueStore().arn();
            try {
                assertTrue(arnA.startsWith("arn:aws:cloudfront::000000000000:key-value-store/"));
                assertTrue(arnB.startsWith("arn:aws:cloudfront::111111111111:key-value-store/"));
                assertNotEquals(arnA, arnB);
                assertEquals(arnA, firstData.describeKeyValueStore(r -> r.kvsARN(arnA)).kvsARN());
                assertEquals(arnB, otherData.describeKeyValueStore(r -> r.kvsARN(arnB)).kvsARN());
                assertDataError("ResourceNotFoundException", 404, () -> firstData.describeKeyValueStore(r -> r.kvsARN(arnB)));
                assertDataError("ResourceNotFoundException", 404, () -> otherData.describeKeyValueStore(r -> r.kvsARN(arnA)));
                String unknown = "arn:aws:cloudfront::000000000000:key-value-store/" + UUID.randomUUID();
                assertDataError("ResourceNotFoundException", 404,
                        () -> firstData.describeKeyValueStore(r -> r.kvsARN(unknown)));
                ListKeyValueStoresResponse listed = first.listKeyValueStores(r -> r.maxItems("100"));
                assertTrue(listed.keyValueStoreList().items().stream().anyMatch(v -> arnA.equals(v.arn())));
                assertFalse(listed.keyValueStoreList().items().stream().anyMatch(v -> arnB.equals(v.arn())));
            } finally {
                first.deleteKeyValueStore(r -> r.name(name).ifMatch(a.eTag()));
                other.deleteKeyValueStore(r -> r.name(name).ifMatch(b.eTag()));
            }
        }
    }

    @Test
    void scopedNativeClientsCannotUseOperationHintsToReadAnotherStore() throws Exception {
        try (CloudFrontClient root = control("test", "test");
             CloudFrontKeyValueStoreClient rootData = data("test", "test", false, new AtomicInteger())) {
            String firstName = uniqueName();
            String deniedName = uniqueName();
            CreateKeyValueStoreResponse first = root.createKeyValueStore(r -> r.name(firstName).comment("allowed"));
            CreateKeyValueStoreResponse denied = root.createKeyValueStore(r -> r.name(deniedName).comment("denied"));
            String arn = first.keyValueStore().arn();
            String deniedArn = denied.keyValueStore().arn();
            try {
                String firstVersion = rootData.describeKeyValueStore(r -> r.kvsARN(arn)).eTag();
                rootData.putKey(r -> r.kvsARN(arn).ifMatch(firstVersion).key("key").value("allowed"));
                String deniedVersion = rootData.describeKeyValueStore(r -> r.kvsARN(deniedArn)).eTag();
                rootData.putKey(r -> r.kvsARN(deniedArn).ifMatch(deniedVersion).key("key").value("secret"));
                String policy = """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":["cloudfront:DescribeKeyValueStore",
                            "cloudfront-keyvaluestore:GetKey"],"Resource":"%s"},
                          {"Effect":"Allow","Action":["cloudfront:ListKeyValueStores",
                            "cloudfront-keyvaluestore:ListKeys"],"Resource":"*"}]}
                        """.formatted(arn);
                try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "kvs-reader-", "scope", policy);
                     CloudFrontClient control = control(user.accessKeyId(), user.secretAccessKey());
                     CloudFrontKeyValueStoreClient data = data(user.accessKeyId(), user.secretAccessKey(),
                             true, new AtomicInteger())) {
                    assertEquals(arn, control.describeKeyValueStore(r -> r.name(firstName)).keyValueStore().arn());
                    CloudFrontException refused = assertThrows(CloudFrontException.class,
                            () -> control.describeKeyValueStore(r -> r.name(deniedName)));
                    assertEquals(403, refused.statusCode());
                    assertEquals("allowed", data.getKey(r -> r.kvsARN(arn).key("key")).value());
                    assertDataError("AccessDeniedException", 403,
                            () -> data.getKey(r -> r.kvsARN(deniedArn).key("key")));
                    assertDataError("AccessDeniedException", 403,
                            () -> data.deleteKey(r -> r.kvsARN(arn).ifMatch(firstVersion).key("key")));
                }
                assertEquals("secret", rootData.getKey(r -> r.kvsARN(deniedArn).key("key")).value());
                assertEquals("allowed", rootData.getKey(r -> r.kvsARN(arn).key("key")).value());
            } finally {
                root.deleteKeyValueStore(r -> r.name(firstName).ifMatch(first.eTag()));
                root.deleteKeyValueStore(r -> r.name(deniedName).ifMatch(denied.eTag()));
            }
        }
    }

    @Test
    void nativeFunctionAssociationIsReturnedAndPreventsStoreDeletion() {
        try (CloudFrontClient control = control("test", "test")) {
            String storeName = uniqueName();
            String functionName = uniqueName();
            CreateKeyValueStoreResponse store = control.createKeyValueStore(r -> r.name(storeName).comment("associated"));
            String arn = store.keyValueStore().arn();
            CreateFunctionResponse function = control.createFunction(r -> r.name(functionName)
                    .functionCode(SdkBytes.fromUtf8String("function handler(event) { return event.request; }"))
                    .functionConfig(c -> c.comment("kvs").runtime("cloudfront-js-2.0")
                            .keyValueStoreAssociations(a -> a.quantity(1).items(KeyValueStoreAssociation.builder()
                                    .keyValueStoreARN(arn).build()))));
            try {
                DescribeFunctionResponse described =
                        control.describeFunction(r -> r.name(functionName).stage("DEVELOPMENT"));
                assertEquals(arn, described.functionSummary().functionConfig().keyValueStoreAssociations()
                        .items().getFirst().keyValueStoreARN());
                CloudFrontException inUse = assertThrows(CloudFrontException.class,
                        () -> control.deleteKeyValueStore(r -> r.name(storeName).ifMatch(store.eTag())));
                assertEquals(409, inUse.statusCode());
                assertEquals("CannotDeleteEntityWhileInUse", inUse.awsErrorDetails().errorCode());
                assertEquals(store.eTag(), control.describeKeyValueStore(r -> r.name(storeName)).eTag());
            } finally {
                control.deleteFunction(r -> r.name(functionName).ifMatch(function.eTag()));
                control.deleteKeyValueStore(r -> r.name(storeName).ifMatch(store.eTag()));
            }
        }
    }

    private CloudFrontClient control(String accessKey, String secret) {
        return AwsTestClients.create(CloudFrontClient.builder(), accessKey, secret, Region.US_EAST_1, endpoint);
    }

    private CloudFrontKeyValueStoreClient data(String accessKey, String secret, boolean spoof,
                                               AtomicInteger signedRequests) {
        return CloudFrontKeyValueStoreClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.addExecutionInterceptor(new ExecutionInterceptor() {
                    @Override
                    public SdkHttpRequest modifyHttpRequest(Context.ModifyHttpRequest context,
                                                            ExecutionAttributes attributes) {
                        // SDK endpoint rules add an account host prefix. Route to loopback before signing,
                        // retaining the SDK's SigV4A auth scheme, path, modeled payload and region set.
                        SdkHttpRequest.Builder request = context.httpRequest().toBuilder()
                                .protocol(endpoint.getScheme()).host(endpoint.getHost()).port(endpoint.getPort());
                        if (spoof) {
                            request.putHeader("X-Amz-Target", "CloudFrontKeyValueStore.ListKeys")
                                    .putRawQueryParameter("Action", "ListKeys");
                        }
                        return request.build();
                    }

                    @Override
                    public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
                        String authorization = context.httpRequest().firstMatchingHeader("Authorization").orElseThrow();
                        assertTrue(authorization.startsWith("AWS4-ECDSA-P256-SHA256 "), authorization);
                        assertTrue(authorization.matches(".*Credential=[^/]+/\\d{8}/cloudfront-keyvaluestore/aws4_request,.*"),
                                authorization);
                        assertTrue(context.httpRequest().firstMatchingHeader("X-Amz-Region-Set").isPresent());
                        signedRequests.incrementAndGet();
                    }
                })).build();
    }

    private static String uniqueName() {
        return "sdk-kvs-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void assertDataError(String code, int status, Runnable call) {
        CloudFrontKeyValueStoreException failure = assertThrows(CloudFrontKeyValueStoreException.class, call::run);
        assertEquals(code, failure.awsErrorDetails().errorCode());
        assertEquals(status, failure.statusCode());
    }
}
