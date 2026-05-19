package com.github.mahanmmi.rollwithit.mixin.client;

import iskallia.vault.config.entry.ItemStackPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackPool.class)
public interface ItemStackPoolAccessor {

    @Accessor("minTotalStacks")
    int rwi$getMinTotalStacks();

    @Accessor("maxTotalStacks")
    int rwi$getMaxTotalStacks();
}
