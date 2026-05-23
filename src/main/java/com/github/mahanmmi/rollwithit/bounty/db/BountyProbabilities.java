package com.github.mahanmmi.rollwithit.bounty.db;

import com.github.mahanmmi.rollwithit.bounty.filter.BountyFilter;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Level-pinned probability calculator for a single {@link BountyDatabase} snapshot.
 * <p>
 * Encodes VH's bounty roll as a simple two-step generative model:
 * <ol>
 *   <li>Pick a task type {@code T} with probability {@code weight(T) / Σ weight}
 *       (normalized over types that actually have entries at the current vault level).</li>
 *   <li>Within {@code (T, level)}, pick a task entry {@code E} (a {@code (value, rewardPool)} pair)
 *       with probability {@code E.weight / Σ entry weights}.</li>
 * </ol>
 * Reward-item probabilities then marginalize over the resulting reward pool and apply the
 * exact "at least one of N stacks" expectation {@code E_N[1 − (1 − p)^N]} where {@code N} is
 * uniform over {@code [minTotalStacks, maxTotalStacks]} (matching VH's
 * {@code ItemStackPool.getRandomAmount()} / {@code getRandomEntries()} with replacement).
 * <p>
 * Construction is O(tasks + rewards); lookups are O(rows for that type/pool). Reuse one instance
 * per screen rebuild rather than per row.
 */
public final class BountyProbabilities {

    private final int level;

    /** Tasks active at this level, grouped by type. */
    private final Map<ResourceLocation, List<BountyTaskRow>> tasksByType;
    /** Rewards active at this level, keyed by pool name. */
    private final Map<String, RewardRow> rewardsByPool;

    /** Master task-type weights, restricted to types that have entries at this level. */
    private final Map<ResourceLocation, Integer> typeWeights;
    private final long totalTypeWeight;

    /** Cache of P(rewardPool = P at this level) since several queries hit it. */
    private final Map<String, Double> poolProbCache;

    public BountyProbabilities(BountyDatabase db, int level) {
        this.level = level;
        this.tasksByType = db.tasksForLevel(level);
        this.rewardsByPool = db.rewardsForLevel(level);

        Map<ResourceLocation, Integer> tw = new HashMap<>();
        long total = 0;
        for (TaskWeight w : db.taskTypeWeights()) {
            if (!tasksByType.containsKey(w.taskType())) continue; // unrollable at this level
            tw.put(w.taskType(), w.weight());
            total += w.weight();
        }
        this.typeWeights = Map.copyOf(tw);
        this.totalTypeWeight = total;

        this.poolProbCache = computePoolProbabilities();
    }

    public int level() { return level; }

    // -------------------------------------------------- task probabilities

    /** P(rolled bounty has this task type). */
    public double taskType(ResourceLocation type) {
        Integer w = typeWeights.get(type);
        if (w == null || totalTypeWeight == 0) return 0.0;
        return w / (double) totalTypeWeight;
    }

    /** P(rolled bounty has task type {@code type} AND task value {@code value}). */
    public double taskValue(ResourceLocation type, String value) {
        List<BountyTaskRow> rows = tasksByType.get(type);
        if (rows == null || rows.isEmpty()) return 0.0;
        long total = 0;
        long matching = 0;
        for (BountyTaskRow r : rows) {
            total += r.weight();
            if (value.equals(r.value())) matching += r.weight();
        }
        if (total == 0) return 0.0;
        return taskType(type) * (matching / (double) total);
    }

    // -------------------------------------------------- reward probabilities

    /**
     * P(rolled bounty's reward contains at least one stack of {@code itemRl}). Combines:
     * <ul>
     *   <li>marginal P(rewardPool = P) across all task types, and</li>
     *   <li>per-pool P(item appears in ≥1 of E[N] stacks).</li>
     * </ul>
     */
    public double rewardItem(ResourceLocation itemRl) {
        if (poolProbCache.isEmpty() || rewardsByPool.isEmpty()) return 0.0;
        String itemId = itemRl.toString();
        double p = 0.0;
        for (Map.Entry<String, Double> pe : poolProbCache.entrySet()) {
            RewardRow row = rewardsByPool.get(pe.getKey());
            if (row == null) continue;

            long total = 0;
            long matching = 0;
            for (ItemRewardEntry it : row.items()) {
                total += it.weight();
                if (itemId.equals(it.itemId())) matching += it.weight();
            }
            if (total == 0 || matching == 0) continue;

            double pPerStack = matching / (double) total;
            double pInPool = atLeastOneOverUniformN(pPerStack, row.minTotalStacks(), row.maxTotalStacks());

            p += pe.getValue() * pInPool;
        }
        return p;
    }

    // -------------------------------------------------- filter-match probability

    /**
     * Probability that a single Super Refresh roll produces a bounty satisfying the given filter.
     * <p>
     * Mirrors {@link BountyFilter#matches} for the matching semantics:
     * <ul>
     *   <li>Task side: a row passes if its type is in {@code taskTypes} (or that set is empty)
     *       <strong>and</strong> its value is in {@code taskValues} (or that set is empty).</li>
     *   <li>Reward side: the reward pool linked to that row must produce at least one item from
     *       {@code rewardItems} (or that set is empty, in which case the reward side passes
     *       trivially). Multi-stack reward pools use the {@code 1 − (1 − p)^E[N]} approximation.</li>
     * </ul>
     * Returns {@code 0.0} for an {@linkplain BountyFilter#isUnconstrained() unconstrained} filter
     * so callers can treat it as the "would not actually run" sentinel.
     * <p>
     * {@link BountyFilter#minVaultExp()} is intentionally ignored here — VH XP is a continuous
     * value sampled from a range, not enumerable from our DB, so we'd over- or under-estimate.
     * The UI doesn't surface that knob anyway.
     */
    public double matchPerRoll(BountyFilter f) {
        if (f == null || f.isUnconstrained()) return 0.0;

        Set<String> rewardItemIds = new HashSet<>(f.rewardItems().size());
        for (ResourceLocation rl : f.rewardItems()) rewardItemIds.add(rl.toString());

        double pMatch = 0.0;
        for (Map.Entry<ResourceLocation, List<BountyTaskRow>> e : tasksByType.entrySet()) {
            ResourceLocation type = e.getKey();
            if (!f.taskTypes().isEmpty() && !f.taskTypes().contains(type)) continue;
            double pT = taskType(type);
            if (pT <= 0) continue;

            long totalW = 0;
            for (BountyTaskRow r : e.getValue()) totalW += r.weight();
            if (totalW == 0) continue;

            for (BountyTaskRow r : e.getValue()) {
                if (!f.taskValues().isEmpty() && !f.taskValues().contains(r.value())) continue;
                double pEntry = pT * (r.weight() / (double) totalW);
                pMatch += pEntry * rewardPoolMatchProbability(r.rewardPool(), rewardItemIds);
            }
        }
        return pMatch;
    }

    /**
     * Expected number of Super Refresh rolls to land a first match for {@code filter}. Returns
     * {@link Double#POSITIVE_INFINITY} when the per-roll probability is zero (no possible match
     * given current pool & filter), and {@code 1.0} when the filter is satisfied on every roll.
     */
    public double expectedRollsToMatch(BountyFilter filter) {
        double p = matchPerRoll(filter);
        if (p <= 0.0) return Double.POSITIVE_INFINITY;
        return 1.0 / p;
    }

    /** P(reward rolled from pool {@code poolName} contains at least one item in {@code targets}). */
    private double rewardPoolMatchProbability(String poolName, Set<String> targets) {
        if (targets.isEmpty()) return 1.0; // no reward filter ⇒ any reward passes
        RewardRow row = rewardsByPool.get(poolName);
        if (row == null) return 0.0;

        long total = 0;
        long matching = 0;
        for (ItemRewardEntry it : row.items()) {
            total += it.weight();
            if (targets.contains(it.itemId())) matching += it.weight();
        }
        if (total == 0 || matching == 0) return 0.0;

        double pPerStack = matching / (double) total;
        return atLeastOneOverUniformN(pPerStack, row.minTotalStacks(), row.maxTotalStacks());
    }

    /**
     * Exact {@code E_N[1 − (1 − p)^N]} where {@code N} is uniform over the inclusive integer
     * range {@code [min, max]}. Matches VH's {@link iskallia.vault.config.entry.ItemStackPool}
     * which rolls {@code N = MathUtilities.getRandomInt(min, max + 1)} stacks, each an
     * independent weighted draw (with replacement) from the pool's {@code WeightedList}.
     * Returns {@code 0} when the range is empty or invalid (pool produces no stacks).
     */
    private static double atLeastOneOverUniformN(double pPerStack, int min, int max) {
        if (max < min || max <= 0) return 0.0;
        int lo = Math.max(min, 0);
        double q = 1.0 - pPerStack;
        double sum = 0.0;
        for (int n = lo; n <= max; n++) sum += 1.0 - Math.pow(q, n);
        return sum / (max - lo + 1);
    }

    // -------------------------------------------------- helpers

    /** P(reward pool P will be the rolled bounty's pool), marginalized over task types. */
    private Map<String, Double> computePoolProbabilities() {
        if (typeWeights.isEmpty()) return Map.of();
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, List<BountyTaskRow>> e : tasksByType.entrySet()) {
            double pT = taskType(e.getKey());
            if (pT <= 0) continue;
            long total = 0;
            Map<String, Long> byPool = new HashMap<>();
            for (BountyTaskRow r : e.getValue()) {
                total += r.weight();
                byPool.merge(r.rewardPool(), (long) r.weight(), Long::sum);
            }
            if (total == 0) continue;
            for (Map.Entry<String, Long> bp : byPool.entrySet()) {
                out.merge(bp.getKey(), pT * (bp.getValue() / (double) total), Double::sum);
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Compact percent renderer for button labels. {@code 0.123} → {@code "12.3%"}; very small but
     * non-zero values clamp to {@code "<0.01%"} so users can still tell something is rollable.
     */
    public static String formatPercent(double p) {
        double pct = p * 100.0;
        if (pct <= 0)     return "0%";
        if (pct < 0.01)   return "<0.01%";
        if (pct < 1.0)    return String.format(Locale.ROOT, "%.2f%%", pct);
        return String.format(Locale.ROOT, "%.1f%%", pct);
    }
}
