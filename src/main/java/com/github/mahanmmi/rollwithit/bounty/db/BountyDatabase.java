package com.github.mahanmmi.rollwithit.bounty.db;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable snapshot of every static piece of VH bounty configuration we care about.
 * <p>
 * Built once per "world load" / "client setup" by {@link BountyDatabaseBuilder} and stored in
 * {@link BountyDatabaseStore}. All collections inside are unmodifiable.
 *
 * @param taskTypeWeights roll-probability of each task type (from {@code BountyConfig.weightedTaskList})
 * @param tasks           every concrete task entry, flattened across types and level brackets
 * @param rewards         every concrete reward entry, flattened across pools and level brackets
 * @param oreExpansion    {@code BountyOresConfig}: tag-key → list of resolved block ids
 * @param entityExpansion {@code BountyEntitiesConfig}: tag-key → list of resolved entity ids
 */
public record BountyDatabase(
        List<TaskWeight> taskTypeWeights,
        List<BountyTaskRow> tasks,
        List<RewardRow> rewards,
        Map<ResourceLocation, List<ResourceLocation>> oreExpansion,
        Map<ResourceLocation, List<ResourceLocation>> entityExpansion
) {
    private static final BountyDatabase EMPTY = new BountyDatabase(
            List.of(), List.of(), List.of(), Map.of(), Map.of()
    );

    public static BountyDatabase empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return tasks.isEmpty() && rewards.isEmpty();
    }

    // -------------------------------------------------- level-aware queries
    //
    // VH selects the active bracket per (taskType / poolName) by picking the highest
    // {@code minLevel} that is still ≤ the player's vault level (see {@code LevelEntryMap.getForLevel}).
    // The helpers below mirror that semantic so UI dropdowns only ever show options the player
    // can actually roll right now.

    /**
     * @return for each task type, the list of {@link BountyTaskRow} rows that belong to the
     *         currently-active level bracket for the given vault {@code level}. Types whose
     *         lowest bracket is above {@code level} are omitted.
     */
    public Map<ResourceLocation, List<BountyTaskRow>> tasksForLevel(int level) {
        Map<ResourceLocation, TreeMap<Integer, List<BountyTaskRow>>> byType = new HashMap<>();
        for (BountyTaskRow row : tasks) {
            byType.computeIfAbsent(row.taskType(), k -> new TreeMap<>())
                  .computeIfAbsent(row.minLevel(), k -> new ArrayList<>())
                  .add(row);
        }
        Map<ResourceLocation, List<BountyTaskRow>> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, TreeMap<Integer, List<BountyTaskRow>>> e : byType.entrySet()) {
            Integer activeBracket = e.getValue().floorKey(level);
            if (activeBracket == null) continue;
            out.put(e.getKey(), List.copyOf(e.getValue().get(activeBracket)));
        }
        return Map.copyOf(out);
    }

    /**
     * @return for each reward pool, the single {@link RewardRow} that is active at {@code level}.
     *         Pools with no bracket ≤ {@code level} are omitted.
     */
    public Map<String, RewardRow> rewardsForLevel(int level) {
        Map<String, TreeMap<Integer, RewardRow>> byPool = new HashMap<>();
        for (RewardRow r : rewards) {
            byPool.computeIfAbsent(r.poolName(), k -> new TreeMap<>()).put(r.minLevel(), r);
        }
        Map<String, RewardRow> out = new HashMap<>();
        for (Map.Entry<String, TreeMap<Integer, RewardRow>> e : byPool.entrySet()) {
            Map.Entry<Integer, RewardRow> active = e.getValue().floorEntry(level);
            if (active != null) out.put(e.getKey(), active.getValue());
        }
        return Map.copyOf(out);
    }

    /**
     * Union of every item id that could appear as a reward at this player level, across all
     * pools that this level can touch. Drives the "reward contains item" multi-select.
     */
    public Set<ResourceLocation> rewardItemsForLevel(int level) {
        Set<ResourceLocation> out = new HashSet<>();
        for (RewardRow r : rewardsForLevel(level).values()) {
            for (ItemRewardEntry ie : r.items()) {
                ResourceLocation rl = ResourceLocation.tryParse(ie.itemId());
                if (rl != null) out.add(rl);
            }
        }
        return Set.copyOf(out);
    }

    /**
     * Distinct, ordered task-value strings (entity-id / block-id / item-id / completion-id /
     * predicate-toString) that can be rolled for a specific task type at the player's current
     * level. Drives the "task target" multi-select.
     */
    public List<String> taskValuesFor(ResourceLocation taskType, int level) {
        List<BountyTaskRow> rows = tasksForLevel(level).getOrDefault(taskType, List.of());
        if (rows.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(rows.size());
        Set<String> seen = new HashSet<>();
        for (BountyTaskRow r : rows) {
            String v = r.value();
            if (v != null && seen.add(v)) out.add(v);
        }
        return List.copyOf(out);
    }
}
