package com.github.mahanmmi.rollwithit.mixin.client;

import com.github.mahanmmi.rollwithit.Rollwithit;
import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabaseStore;
import com.github.mahanmmi.rollwithit.bounty.filter.BountyFilter;
import com.github.mahanmmi.rollwithit.bounty.filter.BountyFilterStore;
import com.github.mahanmmi.rollwithit.bounty.refresh.SuperRefreshController;
import com.github.mahanmmi.rollwithit.client.gui.DynamicButtonElement;
import com.github.mahanmmi.rollwithit.client.gui.RWITextures;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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

        // [Super Refresh] — uses our own button-texture set, and live-swaps between the two
        // bundles in RWITextures (SUPER_REFRESH_TEXTURES while idle / STOP_REFRESH_TEXTURES while
        // the loop is running) via DynamicButtonElement.
        // Stays disabled when there's nothing to reroll, when the player has no pearls in the
        // slot to pay for a reroll, or when no available bounty is selected. While Super Refresh
        // is running we keep the button enabled so it doubles as the cancel control.
        self.rwi$addElement(new DynamicButtonElement(
                Spatials.positionXY(120, 117),
                () -> SuperRefreshController.get().isRunning()
                        ? RWITextures.STOP_REFRESH_TEXTURES
                        : RWITextures.SUPER_REFRESH_TEXTURES,
                this::rwi$onSuperRefreshClicked
        ).setDisabled(this::rwi$superRefreshDisabled));

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

        Bounty target = rwi$selectedAvailableBounty();
        if (target == null) {
            rwi$toast("§cSelect an available bounty first.");
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
     * Returns the currently-selected bounty iff it is in the available list (i.e. eligible to
     * reroll). Active/complete/legendary selections don't count.
     */
    private Bounty rwi$selectedAvailableBounty() {
        BountyList avail = container.getAvailable();
        if (avail == null || avail.isEmpty()) return null;
        Bounty selected = bountyElement != null ? bountyElement.getSelectedBounty() : null;
        if (selected == null) return null;
        return avail.contains(selected.getId()) ? selected : null;
    }

    /**
     * Disabled-state supplier for the Super Refresh button. While a run is active we keep the
     * button enabled so the same control can cancel the loop; otherwise it disables when there's
     * nothing to reroll, when no pearls are loaded in the slot, or when no available bounty is
     * selected.
     */
    private boolean rwi$superRefreshDisabled() {
        if (SuperRefreshController.get().isRunning()) return false;
        BountyList avail = container.getAvailable();
        if (avail == null || avail.isEmpty()) return true;
        if (rwi$pearlCount() <= 0) return true;
        return rwi$selectedAvailableBounty() == null;
    }

    private int rwi$pearlCount() {
        Slot slot = container.getBountyPearlSlot();
        if (slot == null) return 0;
        ItemStack stack = slot.getItem();
        return stack == null || stack.isEmpty() ? 0 : stack.getCount();
    }

    private void rwi$toast(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(new TextComponent("[RollWithIt] " + msg), true); // action bar
        }
        Rollwithit.LOGGER.info("RollWithIt UI: {}", msg);
    }
}
