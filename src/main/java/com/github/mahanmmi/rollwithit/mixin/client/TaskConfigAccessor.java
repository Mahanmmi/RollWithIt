package com.github.mahanmmi.rollwithit.mixin.client;

import iskallia.vault.config.bounty.task.TaskConfig;
import iskallia.vault.config.entry.LevelEntryMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TaskConfig.class)
public interface TaskConfigAccessor {

    @Accessor("LEVELS")
    LevelEntryMap<?> rwi$getLevels();
}
