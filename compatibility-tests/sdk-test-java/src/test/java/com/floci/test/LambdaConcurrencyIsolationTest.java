package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;
import software.amazon.awssdk.services.lambda.model.Runtime;

import static org.assertj.core.api.Assertions.assertThat;

class LambdaConcurrencyIsolationTest {

    @Test
    void reservationsAndTheirRemovalPreserveOtherAccountsInTheSameRegion() {
        String name = TestFixtures.uniqueName("lambda-concurrency-isolation");
        try (LambdaClient first = client("111111111111");
                LambdaClient other = client("222222222222")) {
            try {
                create(first, "111111111111", name);
                create(other, "222222222222", name);
                int limit = first.getAccountSettings().accountLimit().concurrentExecutions();
                int reserved = limit / 2 + 1;
                first.putFunctionConcurrency(request -> request.functionName(name)
                        .reservedConcurrentExecutions(reserved));
                other.putFunctionConcurrency(request -> request.functionName(name)
                        .reservedConcurrentExecutions(reserved));
                assertReservation(first, name, reserved, limit);
                assertReservation(other, name, reserved, limit);
                first.deleteFunctionConcurrency(request -> request.functionName(name));
                assertThat(first.getAccountSettings().accountLimit().unreservedConcurrentExecutions())
                        .isEqualTo(limit);
                assertReservation(other, name, reserved, limit);
                first.deleteFunction(request -> request.functionName(name));
                assertReservation(other, name, reserved, limit);
            } finally {
                cleanup(first, name);
                cleanup(other, name);
            }
        }
    }

    private static LambdaClient client(String account) {
        return LambdaClient.builder().endpointOverride(TestFixtures.endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private static void create(LambdaClient client, String account, String name) {
        client.createFunction(request -> request.functionName(name).runtime(Runtime.NODEJS20_X)
                .role("arn:aws:iam::" + account + ":role/sdk-test-lambda-role")
                .handler("index.handler")
                .code(code -> code.zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))));
    }

    private static void assertReservation(LambdaClient client, String name, int reserved, int limit) {
        assertThat(client.getFunctionConcurrency(request -> request.functionName(name))
                .reservedConcurrentExecutions()).isEqualTo(reserved);
        assertThat(client.getAccountSettings().accountLimit().unreservedConcurrentExecutions())
                .isEqualTo(limit - reserved);
    }

    private static void cleanup(LambdaClient client, String name) {
        try {
            client.getFunction(request -> request.functionName(name));
        } catch (ResourceNotFoundException expected) {
            return;
        }
        client.deleteFunction(request -> request.functionName(name));
    }
}
