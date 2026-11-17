package io.github.hectorvent.floci.core.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** A bounded set of monitors for callers to synchronize operations sharing a key. */
public final class StripedLocks {

    private final Typed<Object> locks;

    public StripedLocks(int stripeCount) {
        locks = new Typed<>(stripeCount, Object::new);
    }

    public Object lockFor(Object key) {
        return locks.lockFor(key);
    }

    /** Stable indices let multi-lock callers acquire stripes in a consistent total order. */
    public int stripeOf(Object key) {
        return locks.stripeOf(key);
    }

    public Object lockAt(int stripe) {
        return locks.lockAt(stripe);
    }

    /** Stripes for explicit locks, retaining the caller's lock type and fairness policy. */
    public static final class Typed<T> {

        private final List<T> locks;

        public Typed(int stripeCount, Supplier<T> factory) {
            if (stripeCount <= 0) {
                throw new IllegalArgumentException("stripeCount must be positive");
            }
            locks = new ArrayList<>(stripeCount);
            for (int i = 0; i < stripeCount; i++) {
                locks.add(Objects.requireNonNull(factory.get(), "lock"));
            }
        }

        public T lockFor(Object key) {
            return lockAt(stripeOf(key));
        }

        public int stripeOf(Object key) {
            return Math.floorMod(key.hashCode(), locks.size());
        }

        public T lockAt(int stripe) {
            return locks.get(stripe);
        }
    }
}
