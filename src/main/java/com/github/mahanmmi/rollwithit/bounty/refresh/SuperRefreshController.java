package com.github.mahanmmi.rollwithit.bounty.refresh;

import com.github.mahanmmi.rollwithit.Rollwithit;
import com.github.mahanmmi.rollwithit.bounty.filter.BountyFilter;
import iskallia.vault.bounty.Bounty;
import iskallia.vault.bounty.BountyList;
import iskallia.vault.bounty.client.ClientBountyData;
import iskallia.vault.container.BountyContainer;
import iskallia.vault.init.ModConfigs;
import iskallia.vault.init.ModNetwork;
import iskallia.vault.network.message.bounty.ServerboundRerollMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Client-only Super Refresh engine.
 * <p>
 * Drives a tight loop of {@link ServerboundRerollMessage} packets toward the server, reading back
 * the result of each reroll by tick-polling {@link ClientBountyData#getAvailable()} and
 * {@link iskallia.vault.container.BountyContainer#getBountyPearlSlot() the pearl slot} on the
 * currently-open {@link BountyContainer}.
 * <p>
 * Stop conditions are enumerated in {@link SuperRefreshState}. All public methods are safe to call
 * from the client thread (controller serializes via {@code synchronized}).
 */
public final class SuperRefreshController {

    /** Ticks we wait for the server to apply a reroll before assuming it was dropped. */
    private static final int RESPONSE_TIMEOUT_TICKS = 60; // ≈ 3s @ 20 tps
    /** Consecutive dropped responses tolerated before bailing out. */
    private static final int MAX_NO_RESPONSE = 3;

    private static final SuperRefreshController INSTANCE = new SuperRefreshController();
    public static SuperRefreshController get() { return INSTANCE; }

    // -------- mutable state (all accesses under synchronized) --------
    private SuperRefreshState state = SuperRefreshState.IDLE;
    private SuperRefreshState lastStopReason = SuperRefreshState.IDLE;
    private BountyFilter filter = BountyFilter.empty();
    private UUID target;
    private Set<UUID> preRerollIds = Set.of();
    private int attemptsUsed;
    private int cooldownLeft;
    private int responseTimeoutLeft;
    private int consecutiveNoResponse;

    private SuperRefreshController() {}

    // ------------------------------------------------------------ public API

    public synchronized SuperRefreshState state()           { return state; }
    public synchronized SuperRefreshState lastStopReason()  { return lastStopReason; }
    public synchronized int attemptsUsed()                  { return attemptsUsed; }
    public synchronized int maxAttempts()                   { return filter.maxAttempts(); }
    public synchronized boolean isRunning()                 { return state == SuperRefreshState.RUNNING; }

    public enum StartResult {
        OK,
        ALREADY_RUNNING,
        FILTER_UNCONSTRAINED,
        NO_BOUNTY_CONTAINER,
        NO_TARGET_BOUNTY,
        NO_PEARLS;

        public boolean isOk() { return this == OK; }
    }

    /**
     * Begin a Super Refresh loop targeting {@code initialTarget}. The target is the UUID of the
     * available bounty whose slot will be rerolled in place. Each reroll replaces it with a new
     * bounty (new UUID); the engine retargets automatically.
     */
    public synchronized StartResult start(BountyFilter filter, UUID initialTarget) {
        if (state == SuperRefreshState.RUNNING)        return StartResult.ALREADY_RUNNING;
        if (filter == null || filter.isUnconstrained()) return StartResult.FILTER_UNCONSTRAINED;
        if (initialTarget == null)                      return StartResult.NO_TARGET_BOUNTY;

        BountyContainer container = currentBountyContainer();
        if (container == null) return StartResult.NO_BOUNTY_CONTAINER;

        if (pearlCount(container) < pearlCost(container)) return StartResult.NO_PEARLS;

        // Verify the target actually exists in this container's lists; otherwise we'd loop forever.
        if (container.getBountyById(initialTarget).isEmpty()) return StartResult.NO_TARGET_BOUNTY;

        this.filter                 = filter;
        this.target                 = initialTarget;
        this.preRerollIds           = Set.of();
        this.attemptsUsed           = 0;
        this.cooldownLeft           = 0;
        this.responseTimeoutLeft    = 0;
        this.consecutiveNoResponse  = 0;
        this.state                  = SuperRefreshState.RUNNING;
        this.lastStopReason         = SuperRefreshState.IDLE;

        Rollwithit.LOGGER.info("SuperRefresh: starting on target {} (max {} attempts, {} tick cooldown)",
                initialTarget, filter.maxAttempts(), filter.tickCooldown());
        return StartResult.OK;
    }

    /** User-initiated stop. */
    public synchronized void cancel() {
        if (state == SuperRefreshState.RUNNING) {
            stop(SuperRefreshState.STOPPED_USER_CANCEL);
        }
    }

    // ------------------------------------------------------------ tick driver

    /** Called every client tick (END phase). No-op when {@link #state()} is not RUNNING. */
    public synchronized void onClientTick() {
        if (state != SuperRefreshState.RUNNING) return;

        try {
            tickInternal();
        } catch (Throwable t) {
            Rollwithit.LOGGER.error("SuperRefresh: unhandled error in tick; aborting", t);
            stop(SuperRefreshState.STOPPED_ERROR);
        }
    }

    private void tickInternal() {
        BountyContainer container = currentBountyContainer();
        if (container == null) {
            stop(SuperRefreshState.STOPPED_SCREEN_CLOSED);
            return;
        }

        // 1. If we're waiting for the server to apply a previous reroll, watch for the change.
        if (responseTimeoutLeft > 0) {
            Set<UUID> nowIds = currentAvailableIds();
            if (!nowIds.equals(preRerollIds)) {
                onRerollResponded(nowIds, container);
                return;
            }
            if (--responseTimeoutLeft == 0) {
                consecutiveNoResponse++;
                if (consecutiveNoResponse >= MAX_NO_RESPONSE) {
                    stop(SuperRefreshState.STOPPED_NO_RESPONSE);
                }
                // else fall through next tick and try again
            }
            return;
        }

        // 2. Inter-reroll cooldown.
        if (cooldownLeft > 0) { cooldownLeft--; return; }

        // 3. Attempt cap.
        if (attemptsUsed >= filter.maxAttempts()) {
            stop(SuperRefreshState.STOPPED_MAX_ATTEMPTS);
            return;
        }

        // 4. Pearl pre-flight (VH will happily reroll for free if the slot is empty — we won't).
        int cost = pearlCost(container);
        if (pearlCount(container) < cost) {
            stop(SuperRefreshState.STOPPED_NO_PEARLS);
            return;
        }

        // 5. Fire the next reroll.
        sendNextReroll();
    }

    private void sendNextReroll() {
        preRerollIds = currentAvailableIds();
        attemptsUsed++;
        responseTimeoutLeft = RESPONSE_TIMEOUT_TICKS;
        ModNetwork.CHANNEL.sendToServer(new ServerboundRerollMessage(target));
    }

    private void onRerollResponded(Set<UUID> nowIds, BountyContainer container) {
        responseTimeoutLeft = 0;
        consecutiveNoResponse = 0;

        // Find the new bounty UUID: it's in nowIds but not in preRerollIds.
        UUID newId = null;
        for (UUID id : nowIds) {
            if (!preRerollIds.contains(id)) { newId = id; break; }
        }

        // Defensive: if no new id but list changed (rare race — e.g. expiration concurrent with reroll),
        // just re-aim at whatever's currently in the available list closest to our slot.
        if (newId == null) {
            BountyList avail = ClientBountyData.INSTANCE.getAvailable();
            if (avail != null && !avail.isEmpty()) newId = avail.get(0).getId();
        }

        if (newId == null) {
            // Nothing to retarget to — bail.
            stop(SuperRefreshState.STOPPED_NO_RESPONSE);
            return;
        }

        target = newId;

        Optional<Bounty> bountyOpt = container.getBountyById(newId);
        if (bountyOpt.isPresent() && filter.matches(bountyOpt.get())) {
            stop(SuperRefreshState.STOPPED_MATCH);
            return;
        }

        cooldownLeft = filter.tickCooldown();
    }

    private void stop(SuperRefreshState reason) {
        state = SuperRefreshState.IDLE;
        lastStopReason = reason;
        Rollwithit.LOGGER.info("SuperRefresh: stopped ({}) after {} attempts", reason, attemptsUsed);
    }

    // ------------------------------------------------------------ helpers

    private static BountyContainer currentBountyContainer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        if (mc.player.containerMenu instanceof BountyContainer bc) return bc;
        return null;
    }

    private static Set<UUID> currentAvailableIds() {
        BountyList list = ClientBountyData.INSTANCE.getAvailable();
        if (list == null || list.isEmpty()) return Set.of();
        Set<UUID> ids = new HashSet<>(list.size());
        for (Bounty b : list) {
            if (b != null && b.getId() != null) ids.add(b.getId());
        }
        return ids;
    }

    private static int pearlCost(BountyContainer container) {
        if (ModConfigs.BOUNTY_CONFIG == null) return 1;
        return ModConfigs.BOUNTY_CONFIG.getCost(container.getVaultLevel());
    }

    private static int pearlCount(BountyContainer container) {
        if (container.getBountyPearlSlot() == null) return 0;
        ItemStack stack = container.getBountyPearlSlot().getItem();
        return stack == null || stack.isEmpty() ? 0 : stack.getCount();
    }
}
