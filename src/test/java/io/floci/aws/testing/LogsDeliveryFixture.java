package io.floci.aws.testing;

import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A real delivery stream and a Logs-assumable role, scoped to one IAM fixture. */
public final class LogsDeliveryFixture implements AutoCloseable {
    private final String name = "logs-iam-delivery-" + UUID.randomUUID();
    private final IamClient iam;
    private final KinesisClient kinesis;
    private final String targetArn;
    private final String roleArn;
    private boolean roleCreated;
    private boolean policyCreated;
    private boolean streamCreated;

    public LogsDeliveryFixture(URI endpoint, String account, String region) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test"));
        iam = IamClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        kinesis = KinesisClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        targetArn = "arn:aws:kinesis:" + region + ":" + account + ":stream/" + name;
        roleArn = "arn:aws:iam::" + account + ":role/" + name;
        try {
            iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                      "Principal":{"Service":"logs.amazonaws.com"},"Action":"sts:AssumeRole",
                      "Condition":{"StringEquals":{"aws:SourceAccount":"%s"},
                        "ArnLike":{"aws:SourceArn":"arn:aws:logs:%s:%s:*"}}}]}
                    """.formatted(account, region, account)));
            roleCreated = true;
            iam.putRolePolicy(request -> request.roleName(name).policyName("delivery").policyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                      "Action":"kinesis:PutRecord","Resource":"%s"}]}
                    """.formatted(targetArn)));
            policyCreated = true;
            kinesis.createStream(request -> request.streamName(name).shardCount(1));
            streamCreated = true;
            assertEquals("ACTIVE", kinesis.describeStream(request -> request.streamName(name))
                    .streamDescription().streamStatusAsString());
        } catch (RuntimeException | AssertionError failure) {
            try {
                close();
            } catch (RuntimeException | AssertionError cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public String targetArn() {
        return targetArn;
    }

    public String roleArn() {
        return roleArn;
    }

    @Override
    public void close() {
        List<Executable> cleanup = new ArrayList<>();
        if (streamCreated) {
            cleanup.add(() -> kinesis.deleteStream(request -> request.streamName(name)));
        }
        if (policyCreated) {
            cleanup.add(() -> iam.deleteRolePolicy(request -> request.roleName(name).policyName("delivery")));
        }
        if (roleCreated) {
            cleanup.add(() -> iam.deleteRole(request -> request.roleName(name)));
        }
        cleanup.add(kinesis::close);
        cleanup.add(iam::close);
        assertAll("owned Logs delivery fixture cleanup", cleanup);
    }
}
