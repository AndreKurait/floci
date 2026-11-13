package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupFirewallRetirementRaceTest extends SecurityGroupFirewallPeerFixture {
    @Test
    void aHelperRemovedAfterInspectionIsRetiredWithinTheSameReconciliation() {
        SecurityGroupFirewallManager manager = ready(2);
        beforeInstall = (helper, script) -> {
            if (helper.equals(helper(0))) {
                missingHelpers.add(helper);
                installed.remove(helper);
            }
        };

        assertDoesNotThrow(manager::reconcileAll);

        assertSurvivor(1);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
        assertDoesNotThrow(manager::reconcileAll);
        assertSurvivor(1);
    }

    @Test
    void furtherRetirementsDuringRecoveryEachMakeProgress() {
        SecurityGroupFirewallManager manager = ready(3);
        beforeInstall = (helper, script) -> {
            if (script.contains("add rule") && !helper.equals(helper(2))) {
                missingHelpers.add(helper);
                installed.remove(helper);
            }
        };

        assertDoesNotThrow(manager::reconcileAll);

        assertSurvivor(2);
        assertEquals(2, missingHelpers.size());
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void aMissingDockerObjectDoesNotRetireAHelperThatStillExists() {
        SecurityGroupFirewallManager manager = ready(2);
        AtomicBoolean once = new AtomicBoolean();
        beforeInstall = (helper, script) -> {
            if (once.compareAndSet(false, true)) {
                throw new NotFoundException("No such exec");
            }
        };

        assertThrows(IllegalStateException.class, manager::reconcileAll);

        for (int index = 0; index < 2; index++) {
            assertEquals("flush chain inet floci_sg ingress\nflush chain inet floci_sg egress\n",
                    installed.get(helper(index)));
        }
        assertDoesNotThrow(manager::reconcileAll);
        assertFinal(2);
    }

    private SecurityGroupFirewallManager ready(int count) {
        SecurityGroupFirewallManager manager = manager();
        for (int index = 0; index < count; index++) {
            manager.register(endpoint(index), helper(index), Map.of());
        }
        assertFinal(count);
        installs.clear();
        return manager;
    }

    private void assertSurvivor(int index) {
        assertEquals(canonical(SecurityGroupNftCompiler.compile(
                        endpoint(index), List.of(endpoint(index)), Map.of(), List.of(), false)),
                canonical(installed.get(helper(index))));
    }
}
