package io.floci.aws.services.iam;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.iam.model.IamRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamServiceLinkedRolePublicationTest {
    @Test
    void theFirstPublishedRoleHasItsServiceMarkerAndManagedPolicy() {
        RecordingRoles roles = new RecordingRoles(false);
        service(roles).createServiceLinkedRole("opensearchservice.amazonaws.com", null, null);

        assertTrue(roles.firstServiceLinked);
        assertEquals(List.of("arn:aws:iam::aws:policy/aws-service-role/AmazonOpenSearchServiceRolePolicy"),
                roles.firstPolicies);
        assertEquals(1, roles.publications);
    }

    @Test
    void creatingTheLinkedRoleDoesNotNeedASecondPublication() {
        RecordingRoles roles = new RecordingRoles(true);
        IamRole role = service(roles).createServiceLinkedRole("opensearchservice.amazonaws.com", null, null);

        assertTrue(role.isServiceLinkedRole());
        assertEquals(1, roles.publications);
        assertEquals(role.getAttachedPolicyArns(), roles.firstPolicies);
    }

    private static IamService service(RecordingRoles roles) {
        return new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), roles,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"), false);
    }

    private static final class RecordingRoles extends InMemoryStorage<String, IamRole> {
        private final boolean rejectSecondPublication;
        private int publications;
        private boolean firstServiceLinked;
        private List<String> firstPolicies;

        private RecordingRoles(boolean rejectSecondPublication) {
            this.rejectSecondPublication = rejectSecondPublication;
        }

        @Override
        public synchronized void put(String key, IamRole role) {
            publications++;
            if (publications == 1) {
                firstServiceLinked = role.isServiceLinkedRole();
                firstPolicies = List.copyOf(role.getAttachedPolicyArns());
            } else if (rejectSecondPublication) {
                throw new IllegalStateException("The second role publication was rejected");
            }
            super.put(key, role);
        }
    }
}
