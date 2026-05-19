package com.github.mahanmmi.rollwithit.mixin.client;

import com.github.mahanmmi.rollwithit.Rollwithit;
import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabaseStore;
import com.github.mahanmmi.rollwithit.bounty.filter.BountyFilter;
import com.github.mahanmmi.rollwithit.bounty.filter.BountyFilterStore;
import com.github.mahanmmi.rollwithit.bounty.refresh.SuperRefreshController;
import com.github.mahanmmi.rollwithit.client.gui.RollWithItFilterScreen;
import iskallia.vault.bounty.Bounty;
import iskallia.vault.bounty.BountyList;
import iskallia.vault.client.gui.framework.ScreenTextures;
import iskallia.vault.client.gui.framework.element.ButtonElement;
import iskallia.vault.client.gui.framework.spatial.Spatials;
import iskallia.vault.client.gui.framework.spatial.spi.ISpatial;
import iskallia.vault.client.gui.screen.bounty.element.BountyElement;
import iskallia.vault.client.gui.screen.bounty.element.BountyTableContainerElement;
import iskallia.vault.container.BountyContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.TextComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds RollWithIt's Super Refresh + Configure buttons into VH's bounty-table UI.
 * <p>
 * Injected at the tail of {@link BountyTableContainerElement}'s constructor so all VH-managed
 * children (background, selection grid, reroll button) are already in place — we just stack two
 * more {@link ButtonElement}s to the right of the existing reroll button.
 */
@Mixin(BountyTableContainerElement.class)
public abstract class BountyTableContainerElementMixin {

    @Shadow @Final
    private BountyContainer container;

    /** Set by {@code refreshBountyElement()} which runs later in the constructor; safe to read at click time. */
    @Shadow
    BountyElement bountyElement;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rwi$addSuperRefreshButtons(ISpatial spatial, BountyContainer ctor, CallbackInfo ci) {
        ContainerElementAccessorMixin self = (ContainerElementAccessorMixin) (Object) this;

        // [Super Refresh] — reuses the reroll button texture (it IS a reroll, just smarter).
        self.rwi$addElement(new ButtonElement<>(
                Spatials.positionXY(120, 117),
                ScreenTextures.BUTTON_BUTTON_REROLL_TEXTURES,
                this::rwi$onSuperRefreshClicked
        ).setDisabled(() -> ctor.getAvailable() == null || ctor.getAvailable().isEmpty()));

        // [Configure] — opens the filter screen overlay.
        self.rwi$addElement(new ButtonElement<>(
                Spatials.positionXY(145, 117),
                ScreenTextures.BUTTON_CRAFT_TEXTURES,
                () -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreen(new RollWithItFilterScreen(mc.screen, ctor.getVaultLevel()));
                }
        ));
    }

    private void rwi$onSuperRefreshClicked() {
        SuperRefreshController ctl = SuperRefreshController.get();

        if (ctl.isRunning()) {
            ctl.cancel();
            rwi$toast("Super Refresh stopped.");
            return;
        }

        // Hard-prune any saved filter entries that no longer exist at the player's current level.
        BountyFilter.PruneResult pr = BountyFilterStore.getAndPruneFor(
                BountyDatabaseStore.get(), container.getVaultLevel());
        if (pr.changed()) {
            rwi$toast("§eFilter pruned: removed " + pr.totalRemoved()
                    + " stale entries (level changed).");
        }

        BountyFilter filter = pr.filter();
        if (filter.isUnconstrained()) {
            rwi$toast("§eOpen Configure first — no filter is set.");
            return;
        }

        Bounty target = pickTarget();
        if (target == null) {
            rwi$toast("§cNo available bounty to reroll.");
            return;
        }

        SuperRefreshController.StartResult r = ctl.start(filter, target.getId());
        switch (r) {
            case OK                    -> rwi$toast("Super Refresh started.");
            case ALREADY_RUNNING       -> rwi$toast("§eAlready running.");
            case FILTER_UNCONSTRAINED  -> rwi$toast("§eOpen Configure first — no filter is set.");
            case NO_BOUNTY_CONTAINER   -> rwi$toast("§cBounty table closed?");
            case NO_TARGET_BOUNTY      -> rwi$toast("§cTarget bounty no longer available.");
            case NO_PEARLS             -> rwi$toast("§cNot enough bounty pearls in the slot.");
        }
    }

    /**
     * Selects the bounty to reroll: prefer the currently-selected available bounty; otherwise the
     * first available bounty in the list.
     */
    private Bounty pickTarget() {
        BountyList avail = container.getAvailable();
        if (avail == null || avail.isEmpty()) return null;

        Bounty selected = bountyElement != null ? bountyElement.getSelectedBounty() : null;
        if (selected != null && avail.contains(selected.getId())) return selected;
        return avail.get(0);
    }

    private void rwi$toast(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(new TextComponent("[RollWithIt] " + msg), true); // action bar
        }
        Rollwithit.LOGGER.info("RollWithIt UI: {}", msg);
    }
}
