package com.github.mahanmmi.rollwithit.bounty.filter;

import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabase;
import iskallia.vault.bounty.Bounty;
import iskallia.vault.bounty.TaskReward;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

/**
 * User-defined "Super Refresh" criteria. Every non-empty set is an "any-of" filter; empty sets mean
 * "don't check this dimension". A bounty matches iff {@link #matchesTask(Bounty)} AND
 * {@link #matchesReward(Bounty)} hold.
 *
 * @param taskTypes     restrict to these task types (e.g. {@code the_vault:mining}); empty = any.
 * @param taskValues    restrict to these task-value strings (block-id, entity-id, etc.); empty = any.
 *                      Compared as plain strings against {@link BountyValueExtractor#extractTaskValue}.
 * @param rewardItems   require the bounty's pre-rolled reward to contain at least one item whose
 *                      registry id is in this set; empty = any.
 * @param minVaultExp   require the reward's vault XP to be ≥ this value; empty = any.
 * @param maxAttempts   hard cap on Super Refresh iterations (clamped to
 *                      [{@value #MIN_MAX_ATTEMPTS}, {@value #MAX_MAX_ATTEMPTS}]).
 * @param tickCooldown  client-side delay between reroll packets, in game ticks (≥ 0).
 */
public record BountyFilter(
        Set<ResourceLocation> taskTypes,
        Set<String> taskValues,
        Set<ResourceLocation> rewardItems,
        OptionalInt minVaultExp,
        int maxAttempts,
        int tickCooldown
) {
    public static final int DEFAULT_MAX_ATTEMPTS = 50;
    public static final int DEFAULT_TICK_COOLDOWN = 5;
    public static final int MIN_MAX_ATTEMPTS = 1;
    public static final int MAX_MAX_ATTEMPTS = 500;

    public BountyFilter {
        taskTypes   = Set.copyOf(taskTypes == null ? Set.of() : taskTypes);
        taskValues  = Set.copyOf(taskValues == null ? Set.of() : taskValues);
        rewardItems = Set.copyOf(rewardItems == null ? Set.of() : rewardItems);
        if (minVaultExp == null) minVaultExp = OptionalInt.empty();
        if (maxAttempts < MIN_MAX_ATTEMPTS) maxAttempts = MIN_MAX_ATTEMPTS;
        if (maxAttempts > MAX_MAX_ATTEMPTS) maxAttempts = MAX_MAX_ATTEMPTS;
        if (tickCooldown < 0) tickCooldown = 0;
    }

    public static BountyFilter empty() {
        return new BountyFilter(
                Set.of(), Set.of(), Set.of(), OptionalInt.empty(),
                DEFAULT_MAX_ATTEMPTS, DEFAULT_TICK_COOLDOWN
        );
    }

    /** @return {@code true} if nothing would be checked — Super Refresh should refuse to start. */
    public boolean isUnconstrained() {
        return taskTypes.isEmpty()
            && taskValues.isEmpty()
            && rewardItems.isEmpty()
            && minVaultExp.isEmpty();
    }

    public boolean matchesTask(Bounty bounty) {
        if (taskTypes.isEmpty() && taskValues.isEmpty()) return true;
        if (bounty == null || bounty.getTask() == null) return false;

        ResourceLocation type = bounty.getTask().getTaskType();
        if (!taskTypes.isEmpty() && (type == null || !taskTypes.contains(type))) return false;

        if (!taskValues.isEmpty()) {
            String value = BountyValueExtractor.extractTaskValue(bounty);
            if (value == null || !taskValues.contains(value)) return false;
        }
        return true;
    }

    public boolean matchesReward(Bounty bounty) {
        if (rewardItems.isEmpty() && minVaultExp.isEmpty()) return true;
        if (bounty == null || bounty.getTask() == null) return false;
        TaskReward reward = bounty.getTask().getTaskReward();
        if (reward == null) return false;

        if (minVaultExp.isPresent() && reward.getVaultExp() < minVaultExp.getAsInt()) {
            return false;
        }
        if (!rewardItems.isEmpty()) {
            Set<ResourceLocation> ids = BountyValueExtractor.extractRewardItemIds(bounty);
            // ANY-of: at least one rolled reward item must be in the filter set.
            if (Collections.disjoint(ids, rewardItems)) return false;
        }
        return true;
    }

    public boolean matches(Bounty bounty) {
        return matchesTask(bounty) && matchesReward(bounty);
    }

    // ------------------------------------------------------------ pruning

    /**
     * Drop entries from {@code this} that can't appear in the bounty pool for {@code vaultLevel}
     * given {@code db}. {@link #minVaultExp()} is left alone (it's a threshold, not an enumeration).
     */
    public PruneResult prunedFor(BountyDatabase db, int vaultLevel) {
        if (db == null) return PruneResult.unchanged(this);

        Set<ResourceLocation> validTypes = db.tasksForLevel(vaultLevel).keySet();

        Set<ResourceLocation> newTypes = new HashSet<>(taskTypes);
        int removedTypes = newTypes.retainAll(validTypes) ? (taskTypes.size() - newTypes.size()) : 0;

        // For task-value validation, use the (pruned) selected types if any, else all types
        // available at this level — mirrors what the UI does when no type is selected.
        Iterable<ResourceLocation> typesForValues = newTypes.isEmpty() ? validTypes : newTypes;
        Set<String> validValues = new HashSet<>();
        for (ResourceLocation t : typesForValues) {
            validValues.addAll(db.taskValuesFor(t, vaultLevel));
        }
        Set<String> newValues = new HashSet<>(taskValues);
        int removedValues = newValues.retainAll(validValues) ? (taskValues.size() - newValues.size()) : 0;

        Set<ResourceLocation> validRewards = db.rewardItemsForLevel(vaultLevel);
        Set<ResourceLocation> newRewards = new HashSet<>(rewardItems);
        int removedRewards = newRewards.retainAll(validRewards)
                ? (rewardItems.size() - newRewards.size()) : 0;

        if (removedTypes == 0 && removedValues == 0 && removedRewards == 0) {
            return PruneResult.unchanged(this);
        }
        BountyFilter pruned = new BountyFilter(
                newTypes, newValues, newRewards, minVaultExp, maxAttempts, tickCooldown
        );
        return new PruneResult(pruned, removedTypes, removedValues, removedRewards);
    }

    /** Result of {@link #prunedFor(BountyDatabase, int)}. */
    public record PruneResult(
            BountyFilter filter,
            int removedTaskTypes,
            int removedTaskValues,
            int removedRewardItems
    ) {
        public static PruneResult unchanged(BountyFilter f) {
            return new PruneResult(f, 0, 0, 0);
        }

        public boolean changed() {
            return removedTaskTypes + removedTaskValues + removedRewardItems > 0;
        }

        public int totalRemoved() {
            return removedTaskTypes + removedTaskValues + removedRewardItems;
        }
    }
}
