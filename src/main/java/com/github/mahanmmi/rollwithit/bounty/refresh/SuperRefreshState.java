package com.github.mahanmmi.rollwithit.bounty.refresh;

/**
 * Lifecycle states for the Super Refresh engine. {@link #IDLE} and {@link #RUNNING} are the only
 * "live" states; everything else is a terminal stop reason held in
 * {@link SuperRefreshController#lastStopReason()} so the UI can describe why a run ended.
 */
public enum SuperRefreshState {
    /** Engine is not running. */
    IDLE,
    /** Engine is actively rerolling (sending packets / waiting for responses). */
    RUNNING,

    // -------- terminal stop reasons --------

    /** A bounty matched the filter — success. */
    STOPPED_MATCH,
    /** Reached the user-configured attempt cap without a match. */
    STOPPED_MAX_ATTEMPTS,
    /** Pearl slot can't sustain another reroll given the per-reroll cost. */
    STOPPED_NO_PEARLS,
    /** Player closed the bounty table screen mid-run; VH would reject any further packets. */
    STOPPED_SCREEN_CLOSED,
    /** User pressed Stop. */
    STOPPED_USER_CANCEL,
    /** Several consecutive cycles produced no change in the available list. */
    STOPPED_NO_RESPONSE,
    /** Unhandled exception; see logs. */
    STOPPED_ERROR
}
