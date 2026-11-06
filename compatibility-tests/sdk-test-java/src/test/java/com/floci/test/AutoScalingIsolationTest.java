package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DeregisterImageRequest;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.AlreadyInOrganizationException;
import software.amazon.awssdk.services.organizations.model.CreateAccountState;
import software.amazon.awssdk.services.organizations.model.CreateAccountStatus;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import static org.assertj.core.api.Assertions.assertThat;

@ResourceLock("organizations")
class AutoScalingIsolationTest {

    private static final AwsCredentials ROOT = AwsBasicCredentials.create("test", "test");
    private static AwsCredentials memberCredentials;
    private static String memberAccount;
    private static boolean organizationCreated;

    @BeforeAll
    static void createMemberSession() {
        try (OrganizationsClient organizations = TestFixtures.organizationsClient();
                StsClient sts = TestFixtures.stsClient()) {
            try {
                organizations.createOrganization(request -> request.featureSet("ALL"));
                organizationCreated = true;
            } catch (AlreadyInOrganizationException ignored) {
                organizationCreated = false;
            }
            String name = TestFixtures.uniqueName("asg-isolation-account");
            CreateAccountStatus status = organizations.createAccount(request -> request
                    .accountName(name).email(name + "@example.com")).createAccountStatus();
            assertThat(status.state()).isEqualTo(CreateAccountState.SUCCEEDED);
            memberAccount = status.accountId();
            Credentials session = sts.assumeRole(request -> request
                    .roleArn(TestFixtures.globalArn("iam", memberAccount, "role/OrganizationAccountAccessRole"))
                    .roleSessionName("asg-isolation")).credentials();
            memberCredentials = AwsSessionCredentials.create(
                    session.accessKeyId(), session.secretAccessKey(), session.sessionToken());
        }
    }

    @AfterAll
    static void removeMemberAccount() {
        try (OrganizationsClient organizations = TestFixtures.organizationsClient()) {
            if (memberAccount != null) {
                organizations.removeAccountFromOrganization(request -> request.accountId(memberAccount));
            }
            if (organizationCreated) {
                organizations.deleteOrganization();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"us-west-2,false", "us-east-1,true", "us-east-1,false"})
    void deletingAGroupOrPolicyPreservesOtherRegionalAccountAndGroupScopes(String otherRegion, boolean otherAccount) {
        String name = TestFixtures.uniqueName("asg-isolation");
        String otherName = otherRegion.equals("us-east-1") && !otherAccount
                ? name + "-other" : name;
        AwsCredentials otherCredentials = otherAccount ? memberCredentials : ROOT;
        try (AutoScalingClient first = client("us-east-1", ROOT);
                AutoScalingClient other = client(otherRegion, otherCredentials);
                Ec2Client firstEc2 = ec2("us-east-1", ROOT);
                Ec2Client otherEc2 = ec2(otherRegion, otherCredentials)) {
            String firstImage = null;
            String otherImage = null;
            try {
                firstImage = firstEc2.registerImage(request -> request.name(name + "-first")
                        .architecture("x86_64").rootDeviceName("/dev/sda1")).imageId();
                otherImage = otherEc2.registerImage(request -> request.name(name + "-other")
                        .architecture("x86_64").rootDeviceName("/dev/sda1")).imageId();
                create(first, "us-east-1", name, name + "-first-lc", firstImage);
                create(other, otherRegion, otherName, name + "-other-lc", otherImage);
                for (AutoScalingClient client : new AutoScalingClient[]{first, other}) {
                    String group = client == first ? name : otherName;
                    client.putLifecycleHook(request -> request.autoScalingGroupName(group)
                            .lifecycleHookName(name).lifecycleTransition("autoscaling:EC2_INSTANCE_LAUNCHING"));
                    client.putScalingPolicy(request -> request.autoScalingGroupName(group)
                            .policyName(name).adjustmentType("ChangeInCapacity").scalingAdjustment(1));
                }
                assertThat(other.describeLifecycleHooks(request -> request.autoScalingGroupName(otherName))
                        .lifecycleHooks()).hasSize(1);
                first.deletePolicy(request -> request.autoScalingGroupName(name).policyName(name));
                assertThat(other.describePolicies(request -> request.autoScalingGroupName(otherName))
                        .scalingPolicies()).hasSize(1);
                first.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
                assertThat(other.describeAutoScalingGroups(request -> request.autoScalingGroupNames(otherName))
                        .autoScalingGroups()).hasSize(1);
                assertThat(other.describeLifecycleHooks(request -> request.autoScalingGroupName(otherName))
                        .lifecycleHooks()).hasSize(1);
                assertThat(other.describePolicies(request -> request.autoScalingGroupName(otherName))
                        .scalingPolicies()).hasSize(1);
            } finally {
                try {
                    cleanup(first, firstEc2, name, name + "-first-lc", firstImage);
                } finally {
                    cleanup(other, otherEc2, otherName, name + "-other-lc", otherImage);
                }
            }
        }
    }

    private static AutoScalingClient client(String region, AwsCredentials credentials) {
        return AutoScalingClient.builder().endpointOverride(TestFixtures.endpoint())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }

    private static Ec2Client ec2(String region, AwsCredentials credentials) {
        return Ec2Client.builder().endpointOverride(TestFixtures.endpoint())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }

    private static void create(AutoScalingClient client, String region, String name,
                               String launchConfiguration, String imageId) {
        client.createLaunchConfiguration(request -> request.launchConfigurationName(launchConfiguration)
                .imageId(imageId).instanceType("t3.micro"));
        client.createAutoScalingGroup(request -> request.autoScalingGroupName(name)
                .launchConfigurationName(launchConfiguration).minSize(0).maxSize(0).desiredCapacity(0)
                .availabilityZones(region + "a"));
    }

    private static void cleanup(AutoScalingClient client, Ec2Client ec2, String name,
                                String launchConfiguration, String imageId) {
        if (!client.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().isEmpty()) {
            client.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name).forceDelete(true));
        }
        if (!client.describeLaunchConfigurations(request -> request.launchConfigurationNames(launchConfiguration))
                .launchConfigurations().isEmpty()) {
            client.deleteLaunchConfiguration(request -> request.launchConfigurationName(launchConfiguration));
        }
        if (imageId != null) {
            ec2.deregisterImage(DeregisterImageRequest.builder().imageId(imageId).build());
        }
    }
}
