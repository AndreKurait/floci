package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.eks.model.AccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EksAccessPolicyCatalogServiceTest {
    @Test
    void pagesExhaustThePopulatedCatalogWithoutDuplicates() {
        PaginatedResult<AccessPolicy> all = EksAccessPolicyCatalog.list("aws", null, null);
        assertTrue(all.items().size() > 2);
        assertNull(all.nextToken());
        List<AccessPolicy> paged = new ArrayList<>();
        String token = null;
        Set<String> tokens = new HashSet<>();
        do {
            PaginatedResult<AccessPolicy> page = EksAccessPolicyCatalog.list("aws", 2, token);
            assertFalse(page.items().isEmpty());
            assertTrue(page.items().size() <= 2);
            paged.addAll(page.items());
            token = page.nextToken();
            if (token != null) {
                assertTrue(tokens.add(token), "a continuation must make progress");
            }
        } while (token != null);
        assertEquals(new HashSet<>(all.items()), new HashSet<>(paged));
        assertEquals(all.items().size(), paged.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void outOfRangePageSizesAreRejected(int size) {
        AwsException error = assertThrows(AwsException.class,
                () -> EksAccessPolicyCatalog.list("aws", size, null));
        assertEquals("InvalidParameterException", error.getErrorCode());
    }

    @Test
    void unrelatedAndOtherPartitionTokensAreRejected() {
        String commercialToken = EksAccessPolicyCatalog.list("aws", 1, null).nextToken();
        assertThrows(AwsException.class,
                () -> EksAccessPolicyCatalog.list("aws-cn", 1, commercialToken));
        String unrelated = Base64Url.encode("eks:access-entries:some-cluster:principal");
        assertThrows(AwsException.class, () -> EksAccessPolicyCatalog.list("aws", 1, unrelated));
        assertThrows(AwsException.class, () -> EksAccessPolicyCatalog.list("aws", 1, "%%%"));
    }
}
