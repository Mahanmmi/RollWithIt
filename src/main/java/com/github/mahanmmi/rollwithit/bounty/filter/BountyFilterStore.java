package com.github.mahanmmi.rollwithit.bounty.filter;

import com.github.mahanmmi.rollwithit.Rollwithit;
import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabase;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Persistent per-world / per-server {@link BountyFilter} store.
 * <p>
 * <h3>File layout</h3>
 * <pre>
 *   config/rollwithit/
 *     filter.json                 ← legacy global preset, read-only fallback after upgrade
 *     filters/
 *       world_New_World.json      ← singleplayer (sanitized save name)
 *       server_play.example.com.json ← multiplayer (sanitized {@code ServerData.ip})
 *       default.json              ← used when neither context is available
 * </pre>
 * The active path is recomputed on every {@link #get()}; when the current world / server changes
 * (e.g. switching from one save to another), the cached filter is invalidated and the new file is
 * loaded. New worlds inherit the legacy {@code filter.json} once on first read, so users coming
 * from earlier versions don't have to reconfigure.
 */
public final class BountyFilterStore {

    private static final Path CONFIG_ROOT = FMLPaths.CONFIGDIR.get().resolve("rollwithit");
    private static final Path LEGACY_FILE = CONFIG_ROOT.resolve("filter.json");
    private static final Path PER_CONTEXT_DIR = CONFIG_ROOT.resolve("filters");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile BountyFilter current = BountyFilter.empty();
    /** Key the in-memory {@link #current} corresponds to; null until first load. */
    private static volatile String loadedKey = null;

    private BountyFilterStore() {}

    public static BountyFilter get() {
        ensureLoadedForCurrentContext();
        return current;
    }

    public static synchronized void set(BountyFilter filter) {
        ensureLoadedForCurrentContext();
        current = filter == null ? BountyFilter.empty() : filter;
        save();
    }

    /**
     * Load the current filter, prune entries that no longer exist in {@code db} at
     * {@code vaultLevel}, persist the pruned version to disk if anything changed, and return the
     * {@link BountyFilter.PruneResult} so the caller can show feedback.
     */
    public static synchronized BountyFilter.PruneResult getAndPruneFor(BountyDatabase db, int vaultLevel) {
        BountyFilter.PruneResult r = get().prunedFor(db, vaultLevel);
        if (r.changed()) {
            current = r.filter();
            save();
            Rollwithit.LOGGER.info(
                    "RollWithIt: pruned filter for level {} — removed {} task types, {} task values, {} reward items",
                    vaultLevel, r.removedTaskTypes(), r.removedTaskValues(), r.removedRewardItems());
        }
        return r;
    }

    // ------------------------------------------------------------ context resolution

    /**
     * @return a stable, filesystem-safe key for the player's current world / server context. Used
     *         as the filename stem under {@link #PER_CONTEXT_DIR}.
     */
    private static String currentContextKey() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                ServerData server = mc.getCurrentServer();
                if (server != null && server.ip != null && !server.ip.isBlank()) {
                    return "server_" + sanitize(server.ip);
                }
                IntegratedServer ss = mc.getSingleplayerServer();
                if (ss != null && ss.getWorldData() != null) {
                    return "world_" + sanitize(ss.getWorldData().getLevelName());
                }
            }
        } catch (Throwable t) {
            // Defensive: Minecraft.getInstance() can be unhappy during very early init.
            Rollwithit.LOGGER.debug("RollWithIt: context-key lookup failed; using 'default'", t);
        }
        return "default";
    }

    private static Path pathFor(String key) {
        return PER_CONTEXT_DIR.resolve(key + ".json");
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    // ------------------------------------------------------------ disk I/O

    private static synchronized void ensureLoadedForCurrentContext() {
        String key = currentContextKey();
        if (key.equals(loadedKey)) return;
        loadedKey = key;
        loadForKey(key);
    }

    private static void loadForKey(String key) {
        Path file = pathFor(key);
        current = BountyFilter.empty();

        // 1. If we have a per-context file, that wins.
        if (Files.exists(file)) {
            try {
                Dto dto = GSON.fromJson(Files.readString(file), Dto.class);
                current = dto == null ? BountyFilter.empty() : dto.toFilter();
                return;
            } catch (Exception e) {
                Rollwithit.LOGGER.warn("RollWithIt: failed to load filter for context {}; using defaults", key, e);
                return;
            }
        }

        // 2. Otherwise inherit the legacy global preset (one-time migration) so users coming from
        //    pre-per-world versions don't lose their setup. We do NOT delete the legacy file —
        //    other worlds without a per-context file will also pick it up as their seed.
        if (Files.exists(LEGACY_FILE)) {
            try {
                Dto dto = GSON.fromJson(Files.readString(LEGACY_FILE), Dto.class);
                if (dto != null) {
                    current = dto.toFilter();
                    Rollwithit.LOGGER.info(
                            "RollWithIt: seeded filter for context '{}' from legacy filter.json", key);
                }
            } catch (Exception e) {
                Rollwithit.LOGGER.warn("RollWithIt: failed to read legacy filter.json", e);
            }
        }
    }

    private static void save() {
        if (loadedKey == null) loadedKey = currentContextKey();
        Path file = pathFor(loadedKey);
        try {
            Files.createDirectories(file.getParent());
            String json = GSON.toJson(Dto.from(current));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Some filesystems (older NFS, certain Windows configs) reject ATOMIC_MOVE.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Rollwithit.LOGGER.warn("RollWithIt: failed to save filter for context {}", loadedKey, e);
        }
    }

    /**
     * Plain DTO for JSON I/O. We don't serialize the record directly because GSON struggles with
     * record canonical constructors that validate, and we want field renames / defaults to be
     * tolerant on read.
     */
    private static final class Dto {
        List<String>   taskTypes;
        List<String>   taskValues;
        List<String>   rewardItems;
        Integer        minVaultExp;
        int            maxAttempts;
        int            tickCooldown;

        static Dto from(BountyFilter f) {
            Dto d = new Dto();
            d.taskTypes    = f.taskTypes().stream().map(ResourceLocation::toString).sorted().toList();
            d.taskValues   = f.taskValues().stream().sorted().toList();
            d.rewardItems  = f.rewardItems().stream().map(ResourceLocation::toString).sorted().toList();
            d.minVaultExp  = f.minVaultExp().isPresent() ? f.minVaultExp().getAsInt() : null;
            d.maxAttempts  = f.maxAttempts();
            d.tickCooldown = f.tickCooldown();
            return d;
        }

        BountyFilter toFilter() {
            Set<ResourceLocation> types = parseIds(taskTypes);
            Set<ResourceLocation> items = parseIds(rewardItems);
            Set<String> values = taskValues == null ? Set.of() : Set.copyOf(taskValues);
            OptionalInt vx = minVaultExp == null ? OptionalInt.empty() : OptionalInt.of(minVaultExp);
            int max = maxAttempts == 0 ? BountyFilter.DEFAULT_MAX_ATTEMPTS : maxAttempts;
            int cd  = tickCooldown <= 0 ? BountyFilter.DEFAULT_TICK_COOLDOWN : tickCooldown;
            return new BountyFilter(types, values, items, vx, max, cd);
        }

        private static Set<ResourceLocation> parseIds(List<String> raw) {
            if (raw == null || raw.isEmpty()) return Set.of();
            Set<ResourceLocation> out = new HashSet<>(raw.size());
            List<String> bad = new ArrayList<>();
            for (String s : raw) {
                if (s == null) continue;
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) out.add(rl);
                else bad.add(s);
            }
            if (!bad.isEmpty()) {
                Rollwithit.LOGGER.warn("RollWithIt: dropped {} invalid id(s) from filter: {}",
                        bad.size(), bad);
            }
            return out;
        }
    }
}
