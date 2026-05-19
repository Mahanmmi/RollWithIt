package com.github.mahanmmi.rollwithit.mixin.client;

import iskallia.vault.client.gui.screen.bounty.element.BountyElement;
import iskallia.vault.client.gui.screen.bounty.element.BountyTableContainerElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the package-private {@code bountyElement} field on
 * {@link BountyTableContainerElement} so we can programmatically set the selected bounty
 * (used by Super Refresh to highlight the new bounty after each reroll).
 */
@Mixin(BountyTableContainerElement.class)
public interface BountyTableElementAccessor {
    @Accessor("bountyElement")
    BountyElement rollwithit$getBountyElement();
}
