package com.github.mahanmmi.rollwithit.mixin.client;

import iskallia.vault.config.bounty.BountyConfig;
import iskallia.vault.util.data.WeightedList;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BountyConfig.class)
public interface BountyConfigAccessor {

    @Accessor("weightedTaskList")
    WeightedList<ResourceLocation> rwi$getWeightedTaskList();
}
