package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.kms.model.KmsKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KmsRotationPublicationServiceTest {
    private static class Keys extends InMemoryStorage<String, KmsKey> {
        private boolean reject;
        private final IllegalStateException fault = new IllegalStateException("synthetic rejected write");

        @Override
        public void put(String name, KmsKey key) {
            if (reject) {
                throw fault;
            }
            super.put(name, key);
        }
    }

    @Test
    void rejectedWritePreservesSnapshotAndSuccessfulRetryPublishesOnce() throws Exception {
        Keys keys = new Keys();
        KmsService service = new KmsService(keys, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
        KmsKey key = service.createKey("synthetic publication regression", "us-east-1");
        ObjectMapper mapper = new ObjectMapper();
        byte[] plaintext = "before rejected rotation".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = service.encrypt(key.getKeyId(), plaintext, "us-east-1");
        String location = "us-east-1::" + key.getKeyId();
        ObjectNode before = mapper.valueToTree(keys.get(location).orElseThrow());
        keys.reject = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.rotateKeyOnDemand(key.getKeyId(), "us-east-1"));

        assertSame(keys.fault, failure);
        assertEquals(before, mapper.valueToTree(keys.get(location).orElseThrow()));
        assertTrue(service.listKeyRotations(key.getKeyId(), null, "us-east-1", null, null).items().isEmpty());
        assertArrayEquals(plaintext, service.decrypt(ciphertext, "us-east-1"));
        keys.reject = false;

        service.rotateKeyOnDemand(key.getKeyId(), "us-east-1");

        KmsKey rotated = keys.get(location).orElseThrow();
        assertNotSame(key, rotated);
        assertEquals(1, rotated.getOnDemandRotationCount());
        assertEquals(2, rotated.getBackingKeys().size());
        assertEquals(1, service.listKeyRotations(key.getKeyId(), null, "us-east-1", null, null).items().size());
        assertArrayEquals(plaintext, service.decrypt(ciphertext, "us-east-1"));
        ObjectNode after = mapper.valueToTree(rotated);
        for (String field : List.of("onDemandRotationCount", "backingKeys", "currentBackingKeyId",
                "backingKeyRotationDates")) {
            before.remove(field);
            after.remove(field);
        }
        assertEquals(before, after);
    }
}
