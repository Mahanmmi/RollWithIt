package com.github.mahanmmi.rollwithit.mixin.client;

import iskallia.vault.config.bounty.BountyOresConfig;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.HashMap;
import java.util.List;

@Mixin(BountyOresConfig.class)
public interface BountyOresConfigAccessor {

    @Accessor("ores")
    HashMap<ResourceLocation, List<ResourceLocation>> rwi$getOres();
}
