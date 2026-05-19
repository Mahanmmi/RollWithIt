package com.github.mahanmmi.rollwithit;

import com.github.mahanmmi.rollwithit.client.ClientSetup;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * RollWithIt — a client-side companion mod for Vault Hunters.
 *
 * <h2>What it does</h2>
 * The headline feature is <b>Super Refresh</b> for the bounty table: the user sets filters on the
 * kind of task and the kind of reward they want, then RollWithIt re-rolls the selected available
 * bounty in a loop until either a match is found, the configured attempt cap is hit, the pearl
 * slot runs dry, or the player cancels / closes the screen.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   bounty/db/      — immutable snapshot of VH's static bounty configuration
 *                     ({@code ModConfigs.BOUNTY_CONFIG}, {@code REWARD_CONFIG}, {@code BOUNTY_ORES},
 *                     {@code BOUNTY_ENTITIES}, {@code TaskConfig.TASK_CONFIGS}) with level-aware
 *                     query helpers ({@code tasksForLevel}, {@code rewardsForLevel}, …).
 *   bounty/filter/  — user-defined {@code BountyFilter} record (any-of within task / reward sides,
 *                     AND between them), persisted globally to {@code config/rollwithit/filter.json}.
 *                     Hard-prunes stale entries when the player's vault level changes.
 *   bounty/refresh/ — {@code SuperRefreshController}: tick-driven engine that sends
 *                     {@code ServerboundRerollMessage} packets in a loop and watches
 *                     {@code ClientBountyData} for the result.
 *   client/gui/     — vanilla {@link net.minecraft.client.gui.screens.Screen} overlay for
 *                     configuring the filter (paginated lists, level-aware options).
 *   mixin/client/   — accessor mixins for VH's private config fields, plus one inject that adds the
 *                     Super Refresh + Configure buttons to {@code BountyTableContainerElement}.
 * </pre>
 *
 * <h2>Distribution stance</h2>
 * Client-only by design. The {@code @Mod} class is intentionally tiny — all real wiring lives
 * in {@link ClientSetup}, loaded via {@link DistExecutor} so the jar is a silent no-op when it
 * ends up on a dedicated server. {@code mods.toml} sets {@code displayTest=IGNORE_SERVER_VERSION}
 * so a vanilla-but-VH server doesn't show a red X to clients running this mod.
 *
 * <h2>Optional server-side features</h2>
 * Reserved for {@code com.github.mahanmmi.rollwithit.server} (not yet present). Anything that
 * lives there must be gated behind {@code FMLLoader.getDist().isDedicatedServer()} or a similar
 * runtime check; the client must continue to function without it.
 */
@Mod(Rollwithit.MOD_ID)
public class Rollwithit {

    public static final String MOD_ID = "rollwithit";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Rollwithit() {
        // Only touch client-side classes when actually on the client.
        // This keeps the jar safe to load on a dedicated server (where it becomes a no-op).
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init);
    }
}
