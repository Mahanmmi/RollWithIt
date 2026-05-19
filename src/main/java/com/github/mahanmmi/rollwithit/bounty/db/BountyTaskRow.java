package com.github.mahanmmi.rollwithit.bounty.db;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * One concrete (taskType × level-bracket × value) that VH can roll for the player.
 *
 * @param taskType        e.g. {@code the_vault:mining}
 * @param minLevel        lowest player level this bracket applies to
 * @param value           string form of the entry value (entity-id, block-id, item-id, completion-id, or
 *                        an {@code EntityPredicate.toString()} for kill_entity rows)
 * @param minAmount       minimum required count
 * @param maxAmount       maximum required count
 * @param vaultOnly       whether the task only progresses inside a vault dimension
 * @param validDimensions explicit dimension whitelist (empty = "any")
 * @param rewardPool      name of the reward pool to roll from (key into {@link RewardRow#poolName()})
 * @param weight          relative roll weight within this (taskType, level) bucket
 */
public record BountyTaskRow(
        ResourceLocation taskType,
        int minLevel,
        String value,
        int minAmount,
        int maxAmount,
        boolean vaultOnly,
        Set<ResourceLocation> validDimensions,
        String rewardPool,
        int weight
) {}
