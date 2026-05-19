package com.github.mahanmmi.rollwithit.bounty.filter;

import iskallia.vault.bounty.Bounty;
import iskallia.vault.bounty.TaskReward;
import iskallia.vault.bounty.task.properties.CompletionProperties;
import iskallia.vault.bounty.task.properties.DamageProperties;
import iskallia.vault.bounty.task.properties.ItemDiscoveryProperties;
import iskallia.vault.bounty.task.properties.ItemSubmissionProperties;
import iskallia.vault.bounty.task.properties.KillEntityProperties;
import iskallia.vault.bounty.task.properties.MiningProperties;
import iskallia.vault.bounty.task.properties.TaskProperties;
import iskallia.vault.container.oversized.OverSizedItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pulls the comparable "value" out of a runtime {@link Bounty} so it can be matched against the
 * strings stored in {@link com.github.mahanmmi.rollwithit.bounty.db.BountyTaskRow#value()}.
 * <p>
 * Both the DB builder and this extractor format the value via {@code String.valueOf(...)} on the
 * exact same underlying field (block id, entity id, item id, completion id, or
 * {@code EntityPredicate.toString()}), so filter equality is reflexive.
 */
public final class BountyValueExtractor {

    private BountyValueExtractor() {}

    /** @return the task-value string for the given bounty, or {@code null} if unknown. */
    public static String extractTaskValue(Bounty bounty) {
        if (bounty == null || bounty.getTask() == null) return null;
        return extractTaskValue(bounty.getTask().getProperties());
    }

    public static String extractTaskValue(TaskProperties props) {
        if (props == null) return null;
        if (props instanceof KillEntityProperties k)     return stringify(k.getFilter());
        if (props instanceof MiningProperties m)         return stringify(m.getBlockId());
        if (props instanceof DamageProperties d)         return stringify(d.getEntityId());
        if (props instanceof ItemSubmissionProperties i) return stringify(i.getItemId());
        if (props instanceof ItemDiscoveryProperties i)  return stringify(i.getItemId());
        if (props instanceof CompletionProperties c)     return c.getId();
        return null;
    }

    /**
     * @return distinct registry-name ids of every item currently sitting in this bounty's
     *         pre-rolled reward list (server has already chosen exact items by the time we see them).
     */
    public static Set<ResourceLocation> extractRewardItemIds(Bounty bounty) {
        if (bounty == null || bounty.getTask() == null) return Set.of();
        TaskReward reward = bounty.getTask().getTaskReward();
        if (reward == null) return Set.of();
        List<OverSizedItemStack> stacks = reward.getRewardItems();
        if (stacks == null || stacks.isEmpty()) return Set.of();

        Set<ResourceLocation> out = new HashSet<>();
        for (OverSizedItemStack ois : stacks) {
            if (ois == null) continue;
            ItemStack stack = ois.stack();
            if (stack == null || stack.isEmpty() || stack.getItem() == null) continue;
            ResourceLocation rl = stack.getItem().getRegistryName();
            if (rl != null) out.add(rl);
        }
        return out;
    }

    private static String stringify(Object o) {
        return o == null ? null : o.toString();
    }
}
