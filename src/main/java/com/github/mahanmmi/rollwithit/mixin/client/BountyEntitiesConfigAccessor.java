package com.github.mahanmmi.rollwithit.mixin.client;

import iskallia.vault.config.bounty.BountyEntitiesConfig;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.HashMap;
import java.util.List;

@Mixin(BountyEntitiesConfig.class)
public interface BountyEntitiesConfigAccessor {

    @Accessor("entities")
    HashMap<ResourceLocation, List<ResourceLocation>> rwi$getEntities();
}
