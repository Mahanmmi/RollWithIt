package com.github.mahanmmi.rollwithit.bounty.filter;

import com.github.mahanmmi.rollwithit.Rollwithit;
import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabase;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.resources.ResourceLocation;
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
 * Persistent singleton for the currently configured {@link BountyFilter}.
 * <p>
 * Stored globally at {@code config/rollwithit/filter.json} (one preset for v1; multi-preset is a
 * future addition). Lazy-loaded on first {@link #get()}.
 */
public final class BountyFilterStore {

    private static final Path FILE = FMLPaths.CONFIGDIR.get()
            .resolve("rollwithit")
            .resolve("filter.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile BountyFilter current = BountyFilter.empty();
    private static volatile boolean loaded = false;

    private BountyFilterStore() {}

    public static BountyFilter get() {
        if (!loaded) loadOnce();
        return current;
    }

    public static synchronized void set(BountyFilter filter) {
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

    private static synchronized void loadOnce() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                String json = Files.readString(FILE);
                Dto dto = GSON.fromJson(json, Dto.class);
                current = dto == null ? BountyFilter.empty() : dto.toFilter();
            }
        } catch (Exception e) {
            Rollwithit.LOGGER.warn("RollWithIt: failed to load filter; using defaults", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            String json = GSON.toJson(Dto.from(current));
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Some filesystems (older NFS, certain Windows configs) reject ATOMIC_MOVE.
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Rollwithit.LOGGER.warn("RollWithIt: failed to save filter", e);
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
