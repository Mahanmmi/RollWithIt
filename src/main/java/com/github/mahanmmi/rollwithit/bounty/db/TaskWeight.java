package com.github.mahanmmi.rollwithit.bounty.db;

import net.minecraft.resources.ResourceLocation;

/** One row of {@link com.github.mahanmmi.rollwithit.bounty.db.BountyDatabase#taskTypeWeights()}. */
public record TaskWeight(ResourceLocation taskType, int weight) {}
