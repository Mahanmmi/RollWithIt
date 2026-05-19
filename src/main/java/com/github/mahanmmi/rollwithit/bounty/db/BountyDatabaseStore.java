package com.github.mahanmmi.rollwithit.bounty.db;

/**
 * Single source of truth for the currently active {@link BountyDatabase} snapshot.
 * <p>
 * Written from the client lifecycle (mod setup + login). Read from anywhere on the client.
 */
public final class BountyDatabaseStore {

    private static volatile BountyDatabase CURRENT = BountyDatabase.empty();

    private BountyDatabaseStore() {}

    public static BountyDatabase get() {
        return CURRENT;
    }

    public static void set(BountyDatabase db) {
        CURRENT = db == null ? BountyDatabase.empty() : db;
    }
}
