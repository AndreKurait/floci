package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.testing.AwsTestClients;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.Monitor;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Actual owned prerequisites, including monitor roles and alarm; no deployment or alarm action runs. */
final class AppConfigNativeUpdateFixture implements AutoCloseable {
    static final String ACCOUNT = "000000000000";
    final URI endpoint;
    final Region region;
    final AppConfigClient admin;
    final IamClient iam;
    final CloudWatchClient cloudwatch;
    final String prefix = "patch-" + UUID.randomUUID();
    final List<String> environments = new ArrayList<>();
    final List<String> strategies = new ArrayList<>();
    final List<String> roleNames = new ArrayList<>();
    final List<String> roles = new ArrayList<>();
    String application;
    String alarm;
    String alarmArn;

    AppConfigNativeUpdateFixture(URI endpoint, Region region) {
        this.endpoint = endpoint;
        this.region = region;
        admin = client("test", "test");
        iam = AwsTestClients.create(IamClient.builder(), "test", region, endpoint);
        cloudwatch = AwsTestClients.create(CloudWatchClient.builder(), "test", region, endpoint);
        try {
            for (int index = 0; index < 2; index++) {
                String name = prefix + "-role-" + index;
                String arn = iam.createRole(r -> r.roleName(name).assumeRolePolicyDocument(
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                                + "\"Principal\":{\"Service\":\"" + ServicePrincipals.of("appconfig")
                                + "\"},\"Action\":\"sts:AssumeRole\"}]}")).role().arn();
                roleNames.add(name);
                roles.add(arn);
            }
            alarm = prefix + "-alarm";
            cloudwatch.putMetricAlarm(r -> r.alarmName(alarm).alarmDescription(prefix).actionsEnabled(false)
                    .namespace("Audit/AppConfigPatch").metricName(prefix).statistic("Average").period(60)
                    .evaluationPeriods(1).threshold(1.0).comparisonOperator("GreaterThanThreshold"));
            alarmArn = cloudwatch.describeAlarms(r -> r.alarmNames(alarm)).metricAlarms().getFirst().alarmArn();
            application = admin.createApplication(r -> r.name(prefix).description(prefix)).id();
            for (int index = 0; index < 2; index++) {
                String name = prefix + "-" + index;
                environments.add(admin.createEnvironment(r -> r.applicationId(application).name(name)
                        .description("initial").monitors(monitors(0))).id());
                strategies.add(admin.createDeploymentStrategy(r -> r.name(name).description("initial")
                        .deploymentDurationInMinutes(10).finalBakeTimeInMinutes(2).growthFactor(20f)
                        .growthType("LINEAR").replicateTo("NONE")).id());
            }
        } catch (RuntimeException | Error failure) {
            try {
                close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    AppConfigClient client(String accessKey, String secretKey) {
        // The JDK URLConnection transport does not support PATCH.
        return AppConfigClient.builder().endpointOverride(endpoint).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClientBuilder(ApacheHttpClient.builder()).build();
    }

    List<Monitor> monitors(int role) {
        return List.of(Monitor.builder().alarmArn(alarmArn).alarmRoleArn(roles.get(role)).build());
    }

    String applicationArn() {
        return arn("application/" + application);
    }

    String environmentArn(int index) {
        return arn("application/" + application + "/environment/" + environments.get(index));
    }

    String strategyArn(int index) {
        return arn("deploymentstrategy/" + strategies.get(index));
    }

    private String arn(String resource) {
        return AwsArnUtils.Arn.of("appconfig", region.id(), ACCOUNT, resource).toString();
    }

    @Override
    public void close() {
        List<Throwable> errors = new ArrayList<>();
        for (String environment : environments) {
            cleanup(errors, () -> {
                admin.deleteEnvironment(r -> r.applicationId(application).environmentId(environment));
                assertEquals(404, assertThrows(AppConfigException.class,
                        () -> admin.getEnvironment(r -> r.applicationId(application).environmentId(environment))).statusCode());
            });
        }
        for (String strategy : strategies) {
            cleanup(errors, () -> {
                admin.deleteDeploymentStrategy(r -> r.deploymentStrategyId(strategy));
                assertEquals(404, assertThrows(AppConfigException.class,
                        () -> admin.getDeploymentStrategy(r -> r.deploymentStrategyId(strategy))).statusCode());
            });
        }
        if (application != null) {
            cleanup(errors, () -> {
                assertEquals(List.of(), admin.listEnvironments(r -> r.applicationId(application)).items());
                admin.deleteApplication(r -> r.applicationId(application));
                assertEquals(404, assertThrows(AppConfigException.class,
                        () -> admin.getApplication(r -> r.applicationId(application))).statusCode());
            });
        }
        if (alarm != null) {
            cleanup(errors, () -> {
                cloudwatch.deleteAlarms(r -> r.alarmNames(alarm));
                assertEquals(List.of(), cloudwatch.describeAlarms(r -> r.alarmNames(alarm)).metricAlarms());
            });
        }
        for (String role : roleNames) {
            cleanup(errors, () -> {
                iam.deleteRole(r -> r.roleName(role));
                assertThrows(NoSuchEntityException.class, () -> iam.getRole(r -> r.roleName(role)));
            });
        }
        admin.close();
        cloudwatch.close();
        iam.close();
        if (!errors.isEmpty()) {
            AssertionError failure = new AssertionError("Owned AppConfig fixture cleanup failed");
            errors.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static void cleanup(List<Throwable> errors, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException | AssertionError failure) {
            errors.add(failure);
        }
    }
}
