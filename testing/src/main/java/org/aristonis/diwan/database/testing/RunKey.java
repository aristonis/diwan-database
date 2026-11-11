package org.aristonis.diwan.database.testing;

/** A root-store key: one owner object, compared by identity, and what is recorded for it. */
final class RunKey {
    private final Object owner;
    private final String kind;

    RunKey(Object owner, String kind) {
        this.owner = owner;
        this.kind = kind;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RunKey && ((RunKey) other).owner == owner && ((RunKey) other).kind.equals(kind);
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(owner) * 31 + kind.hashCode();
    }
}
