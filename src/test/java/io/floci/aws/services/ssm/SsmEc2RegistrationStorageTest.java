package io.floci.aws.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.SessionCredential;
import io.floci.aws.testing.ParkingStore;
import io.floci.aws.testing.ParkingStoreAssertions;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SsmEc2RegistrationStorageTest {
    private static final String ACCOUNT = "246813579012";
    private static final String REGION = "us-east-1";
    private static final String INSTANCE_ID = "i-0123456789abcdef0";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void registrationSurvivesReloadAndAnIdenticalRetryPreservesItsRegistrationTime(String mode) throws Exception {
        SessionCredential session = new SessionCredential(
                "local-instance-key", "local-secret", "local-token",
                "arn:aws:iam::" + ACCOUNT + ":role/aws:ec2-instance",
                Instant.now().plusSeconds(3600), null, ACCOUNT);
        session.setEc2InstanceId(INSTANCE_ID);
        session.setEc2InstanceArn("arn:aws:ec2:" + REGION + ":" + ACCOUNT + ":instance/" + INSTANCE_ID);
        RequestContext context = new RequestContext();
        context.setAccountId(ACCOUNT);
        context.setVerifiedSession(session, session.getSecretAccessKey(), session.getSessionToken());
        IamService iam = mock(IamService.class);
        when(iam.ec2InstanceIdentitySession(session.getAccessKeyId())).thenReturn(Optional.of(session));
        Ec2Service ec2 = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setInstanceId(INSTANCE_ID);
        instance.setOwnerId(ACCOUNT);
        instance.setState(InstanceState.running());
        when(ec2.getInstance(REGION, INSTANCE_ID)).thenReturn(Optional.of(instance));
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        String key = Base64.getEncoder().encodeToString(keys.generateKeyPair().getPublic().getEncoded());
        JsonNode request = new ObjectMapper().valueToTree(
                Map.of("Fingerprint", INSTANCE_ID, "PublicKey", key, "PublicKeyType", "Rsa"));

        StorageFactory factory = StorageFactories.open(directory, mode, ACCOUNT);
        SsmEc2RegistrationService.Registration registered;
        try {
            SsmEc2RegistrationService service = new SsmEc2RegistrationService(factory, iam, ec2, context);
            assertEquals(Map.of("InstanceId", INSTANCE_ID), service.register(request, REGION));
            AccountAwareStorageBackend<SsmEc2RegistrationService.Registration> store = registrations(factory);
            registered = store.getForAccount(ACCOUNT, REGION + "::" + INSTANCE_ID).orElseThrow();
            assertEquals("Rsa", registered.keyType());
            assertEquals(key, registered.publicKey());
            assertEquals(INSTANCE_ID, registered.instanceId());
            service.register(request, REGION);
            assertEquals(registered, store.getForAccount(ACCOUNT, REGION + "::" + INSTANCE_ID).orElseThrow());
            assertTrue(store.getForAccount("111111111111", REGION + "::" + INSTANCE_ID).isEmpty());
        } finally {
            factory.shutdownAll();
        }
        StorageFactory reopened = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            SsmEc2RegistrationService service = new SsmEc2RegistrationService(reopened, iam, ec2, context);
            assertEquals(registered,
                    registrations(reopened).getForAccount(ACCOUNT, REGION + "::" + INSTANCE_ID).orElseThrow());
            assertEquals(Map.of("InstanceId", INSTANCE_ID), service.register(request, REGION));
            assertEquals(registered,
                    registrations(reopened).getForAccount(ACCOUNT, REGION + "::" + INSTANCE_ID).orElseThrow());
        } finally {
            reopened.shutdownAll();
        }
    }

    @Test
    void concurrentIdenticalRetriesRetainTheFirstRegistration() throws Exception {
        SessionCredential session = new SessionCredential(
                "local-instance-key", "local-secret", "local-token",
                "arn:aws:iam::" + ACCOUNT + ":role/aws:ec2-instance",
                Instant.now().plusSeconds(3600), null, ACCOUNT);
        session.setEc2InstanceId(INSTANCE_ID);
        session.setEc2InstanceArn("arn:aws:ec2:" + REGION + ":" + ACCOUNT + ":instance/" + INSTANCE_ID);
        RequestContext context = new RequestContext();
        context.setAccountId(ACCOUNT);
        context.setVerifiedSession(session, session.getSecretAccessKey(), session.getSessionToken());
        IamService iam = mock(IamService.class);
        when(iam.ec2InstanceIdentitySession(session.getAccessKeyId())).thenReturn(Optional.of(session));
        Ec2Service ec2 = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setInstanceId(INSTANCE_ID);
        instance.setOwnerId(ACCOUNT);
        instance.setState(InstanceState.running());
        when(ec2.getInstance(REGION, INSTANCE_ID)).thenReturn(Optional.of(instance));
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        String key = Base64.getEncoder().encodeToString(keys.generateKeyPair().getPublic().getEncoded());
        JsonNode request = new ObjectMapper().valueToTree(
                Map.of("Fingerprint", INSTANCE_ID, "PublicKey", key, "PublicKeyType", "Rsa"));

        ParkingStore<SsmEc2RegistrationService.Registration> backing = new ParkingStore<>();
        AccountAwareStorageBackend<SsmEc2RegistrationService.Registration> store =
                new AccountAwareStorageBackend<>(backing, null, ACCOUNT);
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(store).when(factory).create(eq("ssm"), eq("ssm-ec2-registrations.json"), any());
        SsmEc2RegistrationService service = new SsmEc2RegistrationService(factory, iam, ec2, context);
        AtomicReference<SsmEc2RegistrationService.Registration> first = new AtomicReference<>();
        backing.parkNextWrite();
        ParkingStoreAssertions.assertOperationWaitsForParkedStore(backing, () -> {
            service.register(request, REGION);
            first.set(store.get(REGION + "::" + INSTANCE_ID).orElseThrow());
        }, () -> service.register(request, REGION));
        assertEquals(first.get(), store.get(REGION + "::" + INSTANCE_ID).orElseThrow());
        service.register(request, REGION);
        assertEquals(first.get(), store.get(REGION + "::" + INSTANCE_ID).orElseThrow());
    }

    private static AccountAwareStorageBackend<SsmEc2RegistrationService.Registration> registrations(
            StorageFactory factory) {
        return factory.create("ssm", "ssm-ec2-registrations.json", new TypeReference<>() {});
    }
}
