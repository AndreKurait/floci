package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.KeyValueStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFrontKeyValueStoreEtagTest {
    @Test
    void validStaleVersionsFailPreconditionsWithoutChangingControlOrDataState() {
        CloudFrontKeyValueStoreService service = service();
        KeyValueStore first = service.create("versions", "before");
        KeyValueStore data = service.putKey(first.getArn(), first.getDataEtag(), "key", "value");
        KeyValueStore current = service.update(first.getName(), first.getEtag(), "after");

        for (String stale : List.of(first.getEtag(), "E0000000000000")) {
            assertError("PreconditionFailed", 412, () -> service.update("versions", stale, "lost"));
            assertError("PreconditionFailed", 412, () -> service.delete("versions", stale));
            assertEquals("after", service.describe("versions").getComment());
            assertEquals(current.getEtag(), service.describe("versions").getEtag());
            assertEquals(data.getDataEtag(), service.describeByArn(first.getArn()).getDataEtag());
            assertEquals("value", service.getKey(first.getArn(), "key").value());
        }
        service.delete("versions", current.getEtag());
        assertError("EntityNotFound", 404, () -> service.describe("versions"));
    }

    @Test
    void missingEmptyAndMalformedVersionsKeepTheInvalidIfMatchError() {
        CloudFrontKeyValueStoreService service = service();
        KeyValueStore current = service.create("versions", "unchanged");
        assertError("InvalidIfMatchVersion", 400, () -> service.update("versions", null, "lost"));
        assertError("InvalidIfMatchVersion", 400, () -> service.delete("versions", null));
        for (String malformed : List.of("", "stale", "E0000", " E0000000000000", "e0000000000000")) {
            assertError("InvalidIfMatchVersion", 400, () -> service.update("versions", malformed, "lost"));
            assertError("InvalidIfMatchVersion", 400, () -> service.delete("versions", malformed));
        }
        assertEquals("unchanged", service.describe("versions").getComment());
        assertEquals(current.getEtag(), service.describe("versions").getEtag());
    }

    private static CloudFrontKeyValueStoreService service() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any()))
                .thenAnswer(call -> AccountAwareStorageBackend.inMemory("000000000000"));
        return new CloudFrontKeyValueStoreService(factory, new RegionResolver("us-east-1", "000000000000"));
    }

    private static void assertError(String code, int status, Runnable action) {
        AwsException failure = assertThrows(AwsException.class, action::run);
        assertEquals(code, failure.getErrorCode());
        assertEquals(status, failure.getHttpStatus());
    }
}
