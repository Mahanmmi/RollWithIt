package com.github.mahanmmi.rollwithit.client;

import com.github.mahanmmi.rollwithit.Rollwithit;
import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabase;
import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabaseBuilder;
import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabaseStore;
import com.github.mahanmmi.rollwithit.bounty.refresh.SuperRefreshController;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Client-only entry point. Loaded via {@code DistExecutor} from {@link Rollwithit#Rollwithit()}.
 * <p>
 * Responsible for:
 * <ul>
 *   <li>building the {@link BountyDatabase} snapshot once VH's static configs are loaded,</li>
 *   <li>rebuilding it on world join (so we pick up any server-side overrides),</li>
 * </ul>
 */
public final class ClientSetup {

    private ClientSetup() {}

    /** Called from the mod constructor (on the client side only). */
    public static void init() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientSetup::onClientSetup);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            SuperRefreshController.get().onClientTick();
        }
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        // Configs are already loaded during mod construction; defer to the client thread
        // just to be polite to mixin/forge ordering.
        event.enqueueWork(() -> rebuild("client-setup"));
    }

    private static void onLoggedIn(ClientPlayerNetworkEvent.LoggedInEvent event) {
        // Refresh after world join in case VH reloaded any configs.
        rebuild("logged-in");
    }

    private static void rebuild(String reason) {
        try {
            BountyDatabase db = BountyDatabaseBuilder.build();
            BountyDatabaseStore.set(db);
            Rollwithit.LOGGER.info(
                    "RollWithIt[{}]: bounty DB built — {} task types, {} task rows, {} reward rows, {} ore groups, {} entity groups",
                    reason,
                    db.taskTypeWeights().size(),
                    db.tasks().size(),
                    db.rewards().size(),
                    db.oreExpansion().size(),
                    db.entityExpansion().size()
            );
        } catch (Throwable t) {
            Rollwithit.LOGGER.error("RollWithIt[{}]: failed to build bounty DB", reason, t);
        }
    }
}
