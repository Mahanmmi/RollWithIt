package com.github.mahanmmi.rollwithit.client.gui;

import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabase;
import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabaseStore;
import com.github.mahanmmi.rollwithit.bounty.filter.BountyFilter;
import com.github.mahanmmi.rollwithit.bounty.filter.BountyFilterStore;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * Vanilla {@link Screen} overlay for editing the {@link BountyFilter}. Opens on top of VH's bounty
 * table screen; closing returns to it.
 * <p>
 * Layout is intentionally minimal — paginated lists with text labels — so it's resilient to
 * VH UI changes and easy to extend later with proper item icons and a scroll panel.
 */
public class RollWithItFilterScreen extends Screen {

    private static final int PANE_W   = 420;
    private static final int PANE_H   = 260;
    private static final int ROW_H    = 14;
    private static final int ROWS     = 8;

    @Nullable private final Screen parent;
    private final int vaultLevel;

    // working copy of the filter
    private final Set<ResourceLocation> taskTypes   = new HashSet<>();
    private final Set<String>           taskValues  = new HashSet<>();
    private final Set<ResourceLocation> rewardItems = new HashSet<>();
    private int maxAttempts;
    private final int tickCooldown; // not edited in v1, preserved

    // cached options drawn from the DB
    private final List<ResourceLocation> availableTaskTypes;
    private final List<ResourceLocation> availableRewardItems;
    private List<String> currentTaskValues = List.of();

    // pagination
    private int valuesPage = 0;
    private int itemsPage  = 0;

    private EditBox maxAttemptsBox;

    public RollWithItFilterScreen(@Nullable Screen parent, int vaultLevel) {
        super(new TextComponent("RollWithIt — Bounty Filter (vault level " + vaultLevel + ")"));
        this.parent = parent;
        this.vaultLevel = vaultLevel;

        BountyDatabase db = BountyDatabaseStore.get();

        // Hard-prune disk filter against current level before populating the working copy. This
        // ensures that whatever the user sees + later saves is consistent with the current pool.
        BountyFilter.PruneResult pr = BountyFilterStore.getAndPruneFor(db, vaultLevel);
        BountyFilter f = pr.filter();

        this.taskTypes.addAll(f.taskTypes());
        this.taskValues.addAll(f.taskValues());
        this.rewardItems.addAll(f.rewardItems());
        this.maxAttempts = f.maxAttempts();
        this.tickCooldown = f.tickCooldown();

        this.availableTaskTypes = db.tasksForLevel(vaultLevel).keySet().stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
        this.availableRewardItems = db.rewardItemsForLevel(vaultLevel).stream()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
        refreshCurrentTaskValues();
    }

    private void refreshCurrentTaskValues() {
        BountyDatabase db = BountyDatabaseStore.get();
        Set<String> union = new HashSet<>();
        Iterable<ResourceLocation> source = taskTypes.isEmpty() ? availableTaskTypes : taskTypes;
        for (ResourceLocation type : source) {
            union.addAll(db.taskValuesFor(type, vaultLevel));
        }
        List<String> sorted = new ArrayList<>(union);
        sorted.sort(Comparator.naturalOrder());
        this.currentTaskValues = sorted;
    }

    @Override
    protected void init() {
        super.init();
        // Preserve EditBox text across rebuilds within the same init pass.
        rebuildWidgets();
    }

    private void rebuildWidgets() {
        // Snapshot edit-box text before clearing.
        if (maxAttemptsBox != null) {
            String s = maxAttemptsBox.getValue();
            try { this.maxAttempts = Math.max(1, Math.min(500, Integer.parseInt(s))); }
            catch (NumberFormatException ignored) {}
        }
        clearWidgets();

        int x = (this.width  - PANE_W) / 2;
        int y = (this.height - PANE_H) / 2;

        // ---- Task type toggle row(s) ----
        int ttX = x + 8;
        int ttY = y + 24;
        if (availableTaskTypes.isEmpty()) {
            // no DB? show note
        } else {
            for (ResourceLocation type : availableTaskTypes) {
                boolean on = taskTypes.contains(type);
                String label = (on ? "[x] " : "[ ] ") + type.getPath();
                final ResourceLocation captured = type;
                addRenderableWidget(new Button(ttX, ttY, 130, 16, new TextComponent(label), b -> {
                    if (!taskTypes.add(captured)) taskTypes.remove(captured);
                    refreshCurrentTaskValues();
                    valuesPage = 0;
                    rebuildWidgets();
                }));
                ttX += 134;
                if (ttX + 130 > x + PANE_W - 8) { ttX = x + 8; ttY += 18; }
            }
            if (ttX != x + 8) ttY += 18; // close the row
        }

        int listY = ttY + 12;

        // ---- Left pane: task values ----
        addColumnLabel(x + 8, listY - 10, "Task values (any-of)");
        addPaginatedSelector(
                x + 8, listY, 200, currentTaskValues, valuesPage,
                taskValues::contains, taskValues::add, taskValues::remove,
                Function.identity(),
                p -> { valuesPage = p; rebuildWidgets(); }
        );

        // ---- Right pane: reward items ----
        addColumnLabel(x + 212, listY - 10, "Reward items (any-of)");
        addPaginatedSelector(
                x + 212, listY, 200, availableRewardItems, itemsPage,
                rewardItems::contains, rewardItems::add, rewardItems::remove,
                ResourceLocation::toString,
                p -> { itemsPage = p; rebuildWidgets(); }
        );

        // ---- Bottom row: max attempts + save/reset/cancel ----
        int botY = y + PANE_H - 32;

        maxAttemptsBox = new EditBox(this.font, x + 8, botY, 60, 18, new TextComponent("Max"));
        maxAttemptsBox.setValue(String.valueOf(maxAttempts));
        maxAttemptsBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
        addRenderableWidget(maxAttemptsBox);

        addRenderableWidget(new Button(x + 74, botY, 70, 18,
                new TextComponent("Clear All"), b -> resetAll()));

        addRenderableWidget(new Button(x + PANE_W - 158, botY, 70, 18,
                new TextComponent("Save"), b -> saveAndClose()));
        addRenderableWidget(new Button(x + PANE_W - 84, botY, 70, 18,
                new TextComponent("Cancel"), b -> onClose()));
    }

    private void addColumnLabel(int x, int y, String text) {
        // Render via a non-interactive Button so it integrates with the same layer; alternatively
        // we could draw text in render(). Button is good enough and self-positions.
        addRenderableWidget(new Button(x, y, 200, 10, new TextComponent(text), b -> {}) {{
            this.active = false;
        }});
    }

    private <T> void addPaginatedSelector(
            int x, int y, int width,
            List<T> items, int page,
            Predicate<T> selected, Consumer<T> select, Consumer<T> deselect,
            Function<T, String> label, IntConsumer onPageChange) {

        int totalPages = Math.max(1, (items.size() + ROWS - 1) / ROWS);
        int p = Math.max(0, Math.min(page, totalPages - 1));
        int start = p * ROWS;
        int end = Math.min(start + ROWS, items.size());

        for (int i = start; i < end; i++) {
            T item = items.get(i);
            String lbl = (selected.test(item) ? "[x] " : "[ ] ") + label.apply(item);
            int row = i - start;
            addRenderableWidget(new Button(x, y + row * (ROW_H + 1), width, ROW_H,
                    new TextComponent(lbl), b -> {
                if (selected.test(item)) deselect.accept(item); else select.accept(item);
                rebuildWidgets();
            }));
        }

        if (totalPages > 1) {
            int navY = y + ROWS * (ROW_H + 1) + 2;
            addRenderableWidget(new Button(x, navY, 24, 14, new TextComponent("<"),
                    b -> { if (p > 0) onPageChange.accept(p - 1); }));
            addRenderableWidget(new Button(x + 28, navY, width - 56, 14,
                    new TextComponent("page " + (p + 1) + " / " + totalPages), b -> {}) {{
                this.active = false;
            }});
            addRenderableWidget(new Button(x + width - 24, navY, 24, 14, new TextComponent(">"),
                    b -> { if (p < totalPages - 1) onPageChange.accept(p + 1); }));
        }
    }

    private void resetAll() {
        taskTypes.clear();
        taskValues.clear();
        rewardItems.clear();
        maxAttempts = BountyFilter.DEFAULT_MAX_ATTEMPTS;
        valuesPage = 0;
        itemsPage = 0;
        refreshCurrentTaskValues();
        rebuildWidgets();
    }

    private void saveAndClose() {
        int attempts = parseAttempts();
        BountyFilter f = new BountyFilter(
                Set.copyOf(taskTypes),
                Set.copyOf(taskValues),
                Set.copyOf(rewardItems),
                OptionalInt.empty(),
                attempts,
                tickCooldown
        );
        BountyFilterStore.set(f);
        onClose();
    }

    private int parseAttempts() {
        if (maxAttemptsBox == null) return maxAttempts;
        try {
            int v = Integer.parseInt(maxAttemptsBox.getValue().trim());
            return Math.max(BountyFilter.MIN_MAX_ATTEMPTS, Math.min(BountyFilter.MAX_MAX_ATTEMPTS, v));
        } catch (NumberFormatException e) {
            return BountyFilter.DEFAULT_MAX_ATTEMPTS;
        }
    }

    @Override
    public void render(PoseStack ps, int mx, int my, float partial) {
        renderBackground(ps);
        int x = (this.width  - PANE_W) / 2;
        int y = (this.height - PANE_H) / 2;
        // Title centered
        drawCenteredString(ps, this.font, this.title, this.width / 2, y + 8, 0xFFFFFF);

        super.render(ps, mx, my, partial);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
