package com.github.mahanmmi.rollwithit.bounty.db;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One leveled reward configuration entry: at {@link #minLevel} or above, when a bounty references
 * {@link #poolName}, this is what it can hand out.
 *
 * @param poolName       VH reward-pool key (matches {@link BountyTaskRow#rewardPool()})
 * @param minLevel       lowest player level this bracket applies to
 * @param minVaultExp    min vault XP granted on completion
 * @param maxVaultExp    max vault XP granted on completion
 * @param discoverModels dynamic-model discovery rewards
 * @param minTotalStacks min number of stacks rolled out of the item pool
 * @param maxTotalStacks max number of stacks rolled out of the item pool
 * @param items          weighted item-pool entries
 */
public record RewardRow(
        String poolName,
        int minLevel,
        int minVaultExp,
        int maxVaultExp,
        List<ResourceLocation> discoverModels,
        int minTotalStacks,
        int maxTotalStacks,
        List<ItemRewardEntry> items
) {}
