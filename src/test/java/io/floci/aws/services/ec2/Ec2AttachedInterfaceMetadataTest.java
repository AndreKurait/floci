package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import io.floci.aws.services.ec2instanceconnect.InstanceConnectKeys;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.SessionCredential;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class Ec2AttachedInterfaceMetadataTest {

    @Test
    void existingTokenSeesAttachmentAndDetachmentWithoutExtendingItsLifetime() throws Exception {
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start();
            String token = fixture.putToken("60").body();
            String mac = "02:11:22:33:44:55";
            String path = "/latest/meta-data/network/interfaces/macs/" + mac + "/interface-id";
            assertEquals(404, fixture.get(path, token).statusCode());

            Instance attached = Ec2MetadataTestFixture.instance(fixture.instance().getInstanceId());
            InstanceNetworkInterface eni = new InstanceNetworkInterface();
            eni.setMacAddress(mac);
            eni.setNetworkInterfaceId("eni-attached");
            eni.setDeviceIndex(1);
            eni.setPrivateIpAddress("10.2.0.10");
            attached.setNetworkInterfaces(List.of(eni));
            fixture.server().refreshInstance(attached);

            assertEquals("eni-attached", fixture.get(path, token).body());
            assertEquals("eni-attached", fixture.get(path, null).body());
            assertEquals(mac + "/", fixture.get("/latest/meta-data/network/interfaces/macs/", token).body());
            assertEquals("1", fixture.get(path.replace("interface-id", "device-number"), token).body());
            assertEquals(404, fixture.get(path.replace("interface-id", "owner-id"), token).statusCode());

            Instance detached = Ec2MetadataTestFixture.instance(attached.getInstanceId());
            fixture.server().refreshInstance(detached);
            assertEquals(404, fixture.get(path, token).statusCode());
            assertEquals(200, fixture.get("/latest/meta-data/instance-id", token).statusCode());
            fixture.clock().advance(Duration.ofSeconds(60));
            assertEquals(401, fixture.get("/latest/meta-data/instance-id", token).statusCode());
        }
    }

    @Test
    void refreshDoesNotReplaceAnIdenticalIdInAnotherAccountOrRegion() {
        Ec2MetadataServer server = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance target = instance("111111111111", "us-east-1");
        Instance otherAccount = instance("222222222222", "us-east-1");
        Instance otherRegion = instance("111111111111", "us-west-2");
        server.registerContainer("192.0.2.1", target.getInstanceId(), target);
        server.registerContainer("192.0.2.2", otherAccount.getInstanceId(), otherAccount);
        server.registerContainer("192.0.2.3", otherRegion.getInstanceId(), otherRegion);
        Instance refreshed = instance("111111111111", "us-east-1");

        server.refreshInstance(refreshed);

        assertSame(refreshed, server.registeredContainer("192.0.2.1").orElseThrow());
        assertSame(otherAccount, server.registeredContainer("192.0.2.2").orElseThrow());
        assertSame(otherRegion, server.registeredContainer("192.0.2.3").orElseThrow());
    }

    @Test
    void refreshingCredentialsPreservesTheirIdentityAndRevokesThemWithTheCurrentRecord() {
        IamService iam = mock(IamService.class);
        Ec2InstanceCredentials credentials = new Ec2InstanceCredentials(iam);
        Instance previous = instance("111111111111", "us-east-1");
        Instance current = instance("111111111111", "us-east-1");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        credentials.register(previous);
        SessionCredential session = credentials.identity(previous, previous.getOwnerId(), now).orElseThrow();

        credentials.refresh(previous, current);
        credentials.refresh(current, current);
        credentials.unregister(previous);

        assertSame(session, credentials.identity(current, current.getOwnerId(), now).orElseThrow());
        assertTrue(credentials.identity(previous, previous.getOwnerId(), now).isEmpty());
        verify(iam, never()).unregisterSession(session.getOriginAccountId(), session.getAccessKeyId());
        credentials.unregister(current);
        verify(iam).unregisterSession(session.getOriginAccountId(), session.getAccessKeyId());
        assertTrue(credentials.identity(current, current.getOwnerId(), now).isEmpty());
    }

    @Test
    void metadataRefreshKeepsIssuedCredentialsUntilTheCurrentInstanceIsUnregistered() throws Exception {
        IamService iam = mock(IamService.class);
        Instance previous = instance("111111111111", "us-east-1");
        Instance current = instance("111111111111", "us-east-1");
        String path = "/latest/meta-data/identity-credentials/ec2/security-credentials/ec2-instance";
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start(iam, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), previous);
            String token = fixture.putToken("60").body();
            JsonObject issued = new JsonObject(fixture.get(path, token).body());
            String accessKey = issued.getString("AccessKeyId");

            fixture.server().refreshInstance(current);
            fixture.server().unregisterInstance(previous);

            assertEquals(200, fixture.get(path, token).statusCode());
            assertEquals(accessKey, new JsonObject(fixture.get(path, token).body()).getString("AccessKeyId"));
            verify(iam, never()).unregisterSession(current.getOwnerId(), accessKey);
            fixture.server().unregisterInstance(current);
            verify(iam).unregisterSession(current.getOwnerId(), accessKey);
            assertEquals(404, fixture.get(path, token).statusCode());
        }
    }

    private static Instance instance(String account, String region) {
        Instance instance = Ec2MetadataTestFixture.instance("i-shared-id");
        instance.setOwnerId(account);
        instance.setRegion(region);
        return instance;
    }
}
