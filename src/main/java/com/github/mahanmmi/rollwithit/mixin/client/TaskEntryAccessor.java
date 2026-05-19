package com.github.mahanmmi.rollwithit.mixin.client;

import iskallia.vault.config.bounty.task.entry.TaskEntry;
import iskallia.vault.util.data.WeightedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TaskEntry.class)
public interface TaskEntryAccessor {

    @Accessor("pool")
    WeightedList<?> rwi$getPool();
}
