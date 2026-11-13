package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.net.URI;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class Ec2ImageIamIntegrationTest {

    private static final String SOURCE_ACCOUNT = "718400200001";
    private static final String DESTINATION_ACCOUNT = "718400200002";
    private static final String IMAGE_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
              "Action":["ec2:CopyImage","ec2:CreateTags","ec2:ModifySnapshotAttribute"],
              "Resource":["arn:aws:ec2:*::image/*","arn:aws:ec2:*::snapshot/*"]}%s]}""";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @Inject
    IamService identities;

    @Test
    void signedImageRoleCopiesTagsAndSharesSnapshotsButHonorsSourceAndTagDenies() {
        Ec2Client source = ec2(administrator(SOURCE_ACCOUNT), Region.US_EAST_1);
        AwsBasicCredentials root = administrator(DESTINATION_ACCOUNT);
        Ec2Client destination = ec2(root, Region.EU_WEST_1);
        IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(root)).build();
        cleanup.register(iam::close);
        StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(root)).build();
        cleanup.register(sts::close);

        String volume = source.createVolume(request -> request.availabilityZone("us-east-1a").size(1)).volumeId();
        cleanup.register(() -> source.deleteVolume(request -> request.volumeId(volume)));
        String snapshot = source.createSnapshot(request -> request.volumeId(volume)).snapshotId();
        cleanup.register(() -> source.deleteSnapshot(request -> request.snapshotId(snapshot)));
        String image = source.registerImage(request -> request.name("iam-source-" + UUID.randomUUID())
                .rootDeviceName("/dev/xvda").blockDeviceMappings(mapping -> mapping.deviceName("/dev/xvda")
                        .ebs(ebs -> ebs.snapshotId(snapshot)))).imageId();
        cleanup.register(() -> source.deregisterImage(request -> request.imageId(image)));
        source.modifyImageAttribute(request -> request.imageId(image).launchPermission(permission ->
                permission.add(grant -> grant.userId(DESTINATION_ACCOUNT))));
        source.modifySnapshotAttribute(request -> request.snapshotId(snapshot).createVolumePermission(permission ->
                permission.add(grant -> grant.userId(DESTINATION_ACCOUNT))));

        String role = "image-copy-" + UUID.randomUUID();
        String roleArn = iam.createRole(request -> request.roleName(role).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                  "Principal":{"AWS":"arn:aws:iam::718400200002:root"}}]}""")).role().arn();
        cleanup.register(() -> iam.deleteRole(request -> request.roleName(role)));
        iam.putRolePolicy(request -> request.roleName(role).policyName("image").policyDocument(IMAGE_POLICY.formatted("")));
        cleanup.register(() -> iam.deleteRolePolicy(request -> request.roleName(role).policyName("image")));
        Credentials credentials = sts.assumeRole(request -> request.roleArn(roleArn).roleSessionName("image-copy"))
                .credentials();
        Ec2Client actor = ec2(AwsSessionCredentials.create(credentials.accessKeyId(), credentials.secretAccessKey(),
                credentials.sessionToken()), Region.EU_WEST_1);
        String copy = actor.copyImage(request -> request.sourceRegion("us-east-1").sourceImageId(image)
                .name("iam-copy")).imageId();
        cleanup.register(() -> {
            String backingSnapshot = destination.describeImages(request -> request.imageIds(copy)).images()
                    .getFirst().blockDeviceMappings().getFirst().ebs().snapshotId();
            destination.deregisterImage(request -> request.imageId(copy));
            destination.deleteSnapshot(request -> request.snapshotId(backingSnapshot));
        });
        Image copied = destination.describeImages(request -> request.imageIds(copy)).images().getFirst();
        String copiedSnapshot = copied.blockDeviceMappings().getFirst().ebs().snapshotId();
        assertEquals(DESTINATION_ACCOUNT, copied.ownerId());
        actor.createTags(request -> request.resources(copy, copiedSnapshot)
                .tags(tag -> tag.key("Published").value("yes")));
        assertTrue(destination.describeImages(request -> request.imageIds(copy)).images().getFirst().tags()
                .stream().anyMatch(tag -> tag.key().equals("Published") && tag.value().equals("yes")));
        actor.modifySnapshotAttribute(request -> request.snapshotId(copiedSnapshot).createVolumePermission(permission ->
                permission.add(grant -> grant.userId(SOURCE_ACCOUNT))));
        assertTrue(destination.describeSnapshotAttribute(request -> request.snapshotId(copiedSnapshot)
                        .attribute("createVolumePermission")).createVolumePermissions().stream()
                .anyMatch(permission -> SOURCE_ACCOUNT.equals(permission.userId())));

        iam.putRolePolicy(request -> request.roleName(role).policyName("image").policyDocument(
                IMAGE_POLICY.formatted("""
                        ,{"Effect":"Deny","Action":"ec2:CopyImage",
                          "Resource":"arn:aws:ec2:us-east-1::image/%s"}""".formatted(image))));
        int before = destination.describeImages(request -> request.owners(DESTINATION_ACCOUNT)).images().size();
        Ec2Exception copyDenied = assertThrows(Ec2Exception.class, () -> actor.copyImage(request ->
                request.sourceRegion("us-east-1").sourceImageId(image).name("must-not-exist")));
        assertEquals(403, copyDenied.statusCode());
        assertEquals(before, destination.describeImages(request -> request.owners(DESTINATION_ACCOUNT)).images().size());

        iam.putRolePolicy(request -> request.roleName(role).policyName("image").policyDocument(
                IMAGE_POLICY.formatted("""
                        ,{"Effect":"Deny","Action":"ec2:CreateTags",
                          "Resource":"arn:aws:ec2:eu-west-1::snapshot/%s"}""".formatted(copiedSnapshot))));
        Ec2Exception tagDenied = assertThrows(Ec2Exception.class, () -> actor.createTags(request ->
                request.resources(copy, copiedSnapshot).tags(tag -> tag.key("Denied").value("yes"))));
        assertEquals(403, tagDenied.statusCode());
        assertTrue(destination.describeImages(request -> request.imageIds(copy)).images().getFirst().tags()
                .stream().noneMatch(tag -> tag.key().equals("Denied")));
    }

    private Ec2Client ec2(AwsCredentials credentials, Region region) {
        Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(region)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
        cleanup.register(client::close);
        return client;
    }

    private AwsBasicCredentials administrator(String account) {
        return inAccount(account, () -> {
            String name = "image-admin-" + UUID.randomUUID();
            identities.createUser(name, "/");
            cleanup.register(() -> inAccount(account, () -> {
                identities.deleteUser(name);
                return null;
            }));
            AccessKey key = identities.createAccessKey(name);
            cleanup.register(() -> inAccount(account, () -> {
                identities.deleteAccessKey(name, key.getAccessKeyId());
                return null;
            }));
            identities.putUserPolicy(name, "setup", """
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"*","Resource":"*"}]}""");
            cleanup.register(() -> inAccount(account, () -> {
                identities.deleteUserPolicy(name, "setup");
                return null;
            }));
            return AwsBasicCredentials.create(key.getAccessKeyId(), key.getSecretAccessKey());
        });
    }

    private <T> T inAccount(String account, Supplier<T> action) {
        ManagedContext scope = Arc.container().requestContext();
        boolean active = scope.isActive();
        if (!active) {
            scope.activate();
        }
        RequestContext context = Arc.container().instance(RequestContext.class).get();
        String previous = context.getAccountId();
        try {
            context.setAccountId(account);
            return action.get();
        } finally {
            context.setAccountId(previous);
            if (!active) {
                scope.terminate();
            }
        }
    }
}
