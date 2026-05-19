package com.github.mahanmmi.rollwithit.bounty.db;

/**
 * One weighted reward item entry inside a {@link RewardRow}.
 *
 * @param itemId   registry name (e.g. {@code the_vault:vault_diamond})
 * @param minCount min stack size
 * @param maxCount max stack size
 * @param weight   relative roll weight in the parent pool
 */
public record ItemRewardEntry(
        String itemId,
        int minCount,
        int maxCount,
        int weight
) {}
