package com.github.mahanmmi.rollwithit.bounty.db;

import com.github.mahanmmi.rollwithit.Rollwithit;
import com.github.mahanmmi.rollwithit.mixin.client.BountyConfigAccessor;
import com.github.mahanmmi.rollwithit.mixin.client.BountyEntitiesConfigAccessor;
import com.github.mahanmmi.rollwithit.mixin.client.BountyOresConfigAccessor;
import com.github.mahanmmi.rollwithit.mixin.client.ItemStackPoolAccessor;
import com.github.mahanmmi.rollwithit.mixin.client.TaskConfigAccessor;
import com.github.mahanmmi.rollwithit.mixin.client.TaskEntryAccessor;
import iskallia.vault.config.bounty.BountyConfig;
import iskallia.vault.config.bounty.BountyEntitiesConfig;
import iskallia.vault.config.bounty.BountyOresConfig;
import iskallia.vault.config.bounty.RewardConfig;
import iskallia.vault.config.bounty.task.TaskConfig;
import iskallia.vault.config.bounty.task.entry.GenericEntry;
import iskallia.vault.config.bounty.task.entry.TaskEntry;
import iskallia.vault.config.entry.IntRangeEntry;
import iskallia.vault.config.entry.ItemStackEntry;
import iskallia.vault.config.entry.ItemStackPool;
import iskallia.vault.config.entry.LevelEntryMap;
import iskallia.vault.init.ModConfigs;
import iskallia.vault.util.data.WeightedList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks VH's static bounty configuration and produces an immutable {@link BountyDatabase} snapshot.
 * <p>
 * Safe to call multiple times. Always called on the client (see {@code ClientSetup}).
 */
public final class BountyDatabaseBuilder {

    /**
     * Reward-pool names from VH's {@code bounty/rewards.json} that we deliberately exclude from
     * our database. Legendary bounties are not user-rerollable (they're spawned through a separate
     * legendary slot in {@link iskallia.vault.container.BountyContainer#getLegendary()}), so
     * surfacing the {@code "legendary"} pool in our filter would let players configure goals that
     * can never be hit by Super Refresh.
     */
    private static final Set<String> EXCLUDED_REWARD_POOLS = Set.of("legendary");

    private BountyDatabaseBuilder() {}

    public static BountyDatabase build() {
        BountyConfig bountyConfig = ModConfigs.BOUNTY_CONFIG;
        RewardConfig rewardConfig = ModConfigs.REWARD_CONFIG;
        BountyOresConfig oresConfig = ModConfigs.BOUNTY_ORES;
        BountyEntitiesConfig entitiesConfig = ModConfigs.BOUNTY_ENTITIES;

        if (bountyConfig == null || rewardConfig == null) {
            Rollwithit.LOGGER.warn("RollWithIt: VH bounty configs not initialized yet; returning empty DB.");
            return BountyDatabase.empty();
        }

        return new BountyDatabase(
                collectTaskTypeWeights(bountyConfig),
                collectTaskRows(),
                collectRewardRows(rewardConfig),
                collectOreExpansion(oresConfig),
                collectEntityExpansion(entitiesConfig)
        );
    }

    // -------------------------------------------------- task type weights

    private static List<TaskWeight> collectTaskTypeWeights(BountyConfig bountyConfig) {
        WeightedList<ResourceLocation> weighted = ((BountyConfigAccessor) bountyConfig).rwi$getWeightedTaskList();
        if (weighted == null) return List.of();

        List<TaskWeight> out = new ArrayList<>(weighted.size());
        for (WeightedList.Entry<ResourceLocation> e : weighted) {
            out.add(new TaskWeight(e.value, e.weight));
        }
        return List.copyOf(out);
    }

    // -------------------------------------------------- task rows

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<BountyTaskRow> collectTaskRows() {
        Map<ResourceLocation, ? extends TaskConfig<?, ?>> configs = TaskConfig.getTaskConfigs();
        if (configs == null || configs.isEmpty()) return List.of();

        List<BountyTaskRow> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, ? extends TaskConfig<?, ?>> ce : configs.entrySet()) {
            ResourceLocation taskType = ce.getKey();
            TaskConfig<?, ?> cfg = ce.getValue();
            LevelEntryMap<?> levels = ((TaskConfigAccessor) cfg).rwi$getLevels();
            if (levels == null) continue;

            for (Map.Entry<Integer, ?> lvlEntry : levels.entrySet()) {
                int minLevel = lvlEntry.getKey();
                Object raw = lvlEntry.getValue();
                if (!(raw instanceof TaskEntry<?> te)) continue;

                WeightedList<?> pool = ((TaskEntryAccessor) te).rwi$getPool();
                if (pool == null) continue;

                for (Object entryObj : pool) {
                    WeightedList.Entry<?> w = (WeightedList.Entry<?>) entryObj;
                    if (!(w.value instanceof GenericEntry<?> generic)) continue;

                    IntRangeEntry amount = generic.getAmount();
                    Set<ResourceLocation> dims = generic.getValidDimensions();
                    out.add(new BountyTaskRow(
                            taskType,
                            minLevel,
                            String.valueOf(generic.getValue()),
                            amount != null ? amount.getMin() : 0,
                            amount != null ? amount.getMax() : 0,
                            generic.isVaultOnly(),
                            dims != null ? Set.copyOf(dims) : Set.of(),
                            generic.getRewardPool(),
                            w.weight
                    ));
                }
            }
        }
        return List.copyOf(out);
    }

    // -------------------------------------------------- reward rows

    private static List<RewardRow> collectRewardRows(RewardConfig rewardConfig) {
        Map<String, LevelEntryMap<RewardConfig.RewardEntry>> pools = rewardConfig.getPOOLS();
        if (pools == null || pools.isEmpty()) return List.of();

        List<RewardRow> out = new ArrayList<>();
        for (Map.Entry<String, LevelEntryMap<RewardConfig.RewardEntry>> p : pools.entrySet()) {
            String poolName = p.getKey();
            if (EXCLUDED_REWARD_POOLS.contains(poolName)) continue; // e.g. "legendary"
            LevelEntryMap<RewardConfig.RewardEntry> levelMap = p.getValue();
            if (levelMap == null) continue;

            for (Map.Entry<Integer, RewardConfig.RewardEntry> lvl : levelMap.entrySet()) {
                int minLevel = lvl.getKey();
                RewardConfig.RewardEntry re = lvl.getValue();
                if (re == null) continue;

                IntRangeEntry vx = re.vaultExp;
                ItemStackPool itemPool = re.getItemPool();

                int minStacks = 0, maxStacks = 0;
                List<ItemRewardEntry> items = List.of();
                if (itemPool != null) {
                    ItemStackPoolAccessor pa = (ItemStackPoolAccessor) itemPool;
                    minStacks = pa.rwi$getMinTotalStacks();
                    maxStacks = pa.rwi$getMaxTotalStacks();
                    items = collectItems(itemPool);
                }

                List<ResourceLocation> models = re.discoverModels != null
                        ? List.copyOf(re.discoverModels)
                        : List.of();

                out.add(new RewardRow(
                        poolName,
                        minLevel,
                        vx != null ? vx.getMin() : 0,
                        vx != null ? vx.getMax() : 0,
                        models,
                        minStacks,
                        maxStacks,
                        items
                ));
            }
        }
        return List.copyOf(out);
    }

    private static List<ItemRewardEntry> collectItems(ItemStackPool itemPool) {
        List<WeightedList.Entry<ItemStackEntry>> entries = itemPool.getPool();
        if (entries == null || entries.isEmpty()) return List.of();

        List<ItemRewardEntry> out = new ArrayList<>(entries.size());
        for (WeightedList.Entry<ItemStackEntry> e : entries) {
            ItemStackEntry isEntry = e.value;
            if (isEntry == null) continue;

            ItemStack stack = isEntry.getMatchingStack();
            ResourceLocation rl = (stack == null || stack.getItem() == null)
                    ? null
                    : stack.getItem().getRegistryName();

            out.add(new ItemRewardEntry(
                    rl != null ? rl.toString() : "unknown",
                    isEntry.getMinCount(),
                    isEntry.getMaxCount(),
                    e.weight
            ));
        }
        return List.copyOf(out);
    }

    // -------------------------------------------------- expansions

    private static Map<ResourceLocation, List<ResourceLocation>> collectOreExpansion(BountyOresConfig cfg) {
        if (cfg == null) return Map.of();
        HashMap<ResourceLocation, List<ResourceLocation>> raw = ((BountyOresConfigAccessor) cfg).rwi$getOres();
        return raw == null ? Map.of() : copyMap(raw);
    }

    private static Map<ResourceLocation, List<ResourceLocation>> collectEntityExpansion(BountyEntitiesConfig cfg) {
        if (cfg == null) return Map.of();
        HashMap<ResourceLocation, List<ResourceLocation>> raw = ((BountyEntitiesConfigAccessor) cfg).rwi$getEntities();
        return raw == null ? Map.of() : copyMap(raw);
    }

    private static Map<ResourceLocation, List<ResourceLocation>> copyMap(
            Map<ResourceLocation, List<ResourceLocation>> raw) {
        Map<ResourceLocation, List<ResourceLocation>> out = new HashMap<>(raw.size());
        for (Map.Entry<ResourceLocation, List<ResourceLocation>> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Map.copyOf(out);
    }
}
