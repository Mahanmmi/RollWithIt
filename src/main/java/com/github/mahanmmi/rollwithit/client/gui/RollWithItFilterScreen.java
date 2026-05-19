package com.github.mahanmmi.rollwithit.client.gui;

import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabase;
import com.github.mahanmmi.rollwithit.bounty.db.BountyDatabaseStore;
import com.github.mahanmmi.rollwithit.bounty.filter.BountyFilter;
import com.github.mahanmmi.rollwithit.bounty.filter.BountyFilterStore;
import com.mojang.blaze3d.vertex.PoseStack;
import iskallia.vault.client.gui.framework.ScreenTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
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
 * Bounty filter editor styled to match VH's bounty table — white nine-slice window with two top
 * tabs ("Tasks" / "Rewards") drawn from VH's atlas via {@link ScreenTextures}.
 * <p>
 * The pane is sized responsively against the host {@link Screen}'s width/height so it stays usable
 * on small windows. Each tab renders a single full-width vertical list (paginated) — no overlapping
 * heading text and no multi-column chip grid.
 * <p>
 * Tasks tab nests two sub-tabs ("Types" / "Values"). This keeps the layout one-list-deep at every
 * point and avoids any text floating between buttons.
 * <p>
 * Closing returns to the parent bounty table screen.
 */
public class RollWithItFilterScreen extends Screen {

    // Tab visuals.
    private static final int TAB_W  = 70;
    private static final int TAB_H  = 18;
    private static final int TAB_GAP = 2;

    // Layout constants
    private static final int ROW_H        = 13;
    private static final int BOTTOM_BAR_H = 26;
    private static final int PADDING      = 6;

    // Responsive bounds. These intentionally stay tight to roughly match the VH bounty-table
    // dialog underneath us (~250x200 in scaled px) so the popup feels like a sub-window, not a
    // full overlay. `Screen#width`/`#height` are already the GUI-scaled Minecraft window
    // dimensions (Window.getGuiScaledWidth/Height), not the OS monitor size.
    private static final int MIN_PANE_W = 200;
    private static final int MAX_PANE_W = 260;
    private static final int MIN_PANE_H = 150;
    private static final int MAX_PANE_H = 190;

    private enum Tab        { TASKS, REWARDS }
    private enum TaskSubTab { TYPES, VALUES }

    @Nullable private final Screen parent;
    private final int vaultLevel;
    private Tab activeTab = Tab.TASKS;
    private TaskSubTab activeTaskSub = TaskSubTab.TYPES;

    // working copy of the filter
    private final Set<ResourceLocation> taskTypes   = new HashSet<>();
    private final Set<String>           taskValues  = new HashSet<>();
    private final Set<ResourceLocation> rewardItems = new HashSet<>();
    private int maxAttempts;
    private final int tickCooldown;

    // cached options drawn from the DB
    private final List<ResourceLocation> availableTaskTypes;
    private final List<ResourceLocation> availableRewardItems;
    /** Each row = a (taskType, taskValue) pair, so the Values list can show "Kill Entity: Pig". */
    private List<TaskValueEntry> currentTaskValues = List.of();

    /** A (taskType, taskValue) pair shown as one row in the Values sub-tab. */
    private record TaskValueEntry(ResourceLocation type, String value) {}

    // per-list pagination
    private int typesPage  = 0;
    private int valuesPage = 0;
    private int itemsPage  = 0;

    private EditBox maxAttemptsBox;

    public RollWithItFilterScreen(@Nullable Screen parent, int vaultLevel) {
        super(new TextComponent("RollWithIt Filter"));
        this.parent = parent;
        this.vaultLevel = vaultLevel;

        BountyDatabase db = BountyDatabaseStore.get();

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
        Set<TaskValueEntry> seen = new HashSet<>();
        List<TaskValueEntry> rows = new ArrayList<>();
        Iterable<ResourceLocation> source = taskTypes.isEmpty() ? availableTaskTypes : taskTypes;
        for (ResourceLocation type : source) {
            for (String value : db.taskValuesFor(type, vaultLevel)) {
                TaskValueEntry e = new TaskValueEntry(type, value);
                if (seen.add(e)) rows.add(e);
            }
        }
        rows.sort(Comparator
                .comparing((TaskValueEntry e) -> NameResolver.taskTypeName(e.type()))
                .thenComparing(e -> NameResolver.taskValueName(e.value())));
        this.currentTaskValues = rows;
    }

    // ---- responsive sizing ----

    private int paneW() {
        return Math.max(MIN_PANE_W, Math.min(MAX_PANE_W, this.width - 40));
    }
    private int paneH() {
        return Math.max(MIN_PANE_H, Math.min(MAX_PANE_H, this.height - 60));
    }
    private int paneX() { return (this.width  - paneW()) / 2; }
    private int paneY() { return (this.height - paneH()) / 2; }

    /** Number of list rows that fit between top of body and the bottom bar (with pagination nav). */
    private int rows() {
        int bodyH = paneH() - PADDING - BOTTOM_BAR_H - (ROW_H + 4) /* pagination strip */ - PADDING;
        return Math.max(3, bodyH / (ROW_H + 1));
    }

    // ---- init & widget rebuild ----

    @Override
    protected void init() {
        super.init();
        rebuildWidgets();
    }

    private void rebuildWidgets() {
        if (maxAttemptsBox != null) {
            try { this.maxAttempts = Math.max(1, Math.min(500, Integer.parseInt(maxAttemptsBox.getValue()))); }
            catch (NumberFormatException ignored) {}
        }
        clearWidgets();

        int x = paneX();
        int y = paneY();
        int w = paneW();
        int h = paneH();

        // Top tabs sit on the pane's top edge.
        int tabsY = y - TAB_H + 4;
        addTabHitbox(x + 10,                       tabsY, Tab.TASKS,   "Tasks");
        addTabHitbox(x + 10 + TAB_W + TAB_GAP,     tabsY, Tab.REWARDS, "Rewards");

        // Body
        int bodyX = x + PADDING;
        int bodyY = y + PADDING;
        int bodyW = w - 2 * PADDING;

        switch (activeTab) {
            case TASKS   -> buildTasksTab(bodyX, bodyY, bodyW);
            case REWARDS -> buildRewardsTab(bodyX, bodyY, bodyW);
        }

        // Bottom bar. Labels and button widths are tuned so everything fits at MIN_PANE_W.
        int botY = y + h - BOTTOM_BAR_H + 4;
        int labelW = this.font.width("Attempts:") + 4;
        maxAttemptsBox = new EditBox(this.font, bodyX + labelW, botY, 28, 16, new TextComponent("Max"));
        maxAttemptsBox.setValue(String.valueOf(maxAttempts));
        maxAttemptsBox.setFilter(s -> s.isEmpty() || s.matches("\\d{1,3}"));
        addRenderableWidget(maxAttemptsBox);

        int btnH = 16;
        int rightEdge = bodyX + bodyW;
        // 3 right-aligned buttons; widths tuned per label.
        int cancelW = 38, saveW = 32, clearW = 32;
        addRenderableWidget(new Button(rightEdge - cancelW,                     botY, cancelW, btnH, new TextComponent("Cancel"), b -> onClose()));
        addRenderableWidget(new Button(rightEdge - cancelW - saveW - 2,         botY, saveW,   btnH, new TextComponent("Save"),   b -> saveAndClose()));
        addRenderableWidget(new Button(rightEdge - cancelW - saveW - clearW - 4, botY, clearW,  btnH, new TextComponent("Clear"),  b -> resetAll()));
    }

    // ---- tab bodies ----

    private void buildTasksTab(int bx, int by, int bw) {
        // Sub-tabs row (Types | Values)
        int subY = by;
        int subW = 60;
        int subH = 14;
        addRenderableWidget(new Button(bx,             subY, subW, subH, new TextComponent(subLabel("Types",  activeTaskSub == TaskSubTab.TYPES)),  b -> { activeTaskSub = TaskSubTab.TYPES;  rebuildWidgets(); }));
        addRenderableWidget(new Button(bx + subW + 2,  subY, subW, subH, new TextComponent(subLabel("Values", activeTaskSub == TaskSubTab.VALUES)), b -> { activeTaskSub = TaskSubTab.VALUES; rebuildWidgets(); }));

        int listY = subY + subH + 4;
        int listH = paneY() + paneH() - BOTTOM_BAR_H - PADDING - listY;

        switch (activeTaskSub) {
            case TYPES -> addPaginatedSelector(
                    bx, listY, bw, listH, availableTaskTypes, typesPage,
                    taskTypes::contains,
                    rl -> { taskTypes.add(rl); refreshCurrentTaskValues(); valuesPage = 0; },
                    rl -> { taskTypes.remove(rl); refreshCurrentTaskValues(); valuesPage = 0; },
                    NameResolver::taskTypeName,
                    p -> { typesPage = p; rebuildWidgets(); });
            case VALUES -> addPaginatedSelector(
                    bx, listY, bw, listH, currentTaskValues, valuesPage,
                    e -> taskValues.contains(e.value()),
                    e -> taskValues.add(e.value()),
                    e -> taskValues.remove(e.value()),
                    e -> NameResolver.taskTypeName(e.type()) + ": "
                            + NameResolver.taskValueName(e.value()),
                    p -> { valuesPage = p; rebuildWidgets(); });
        }
    }

    private void buildRewardsTab(int bx, int by, int bw) {
        // Mirror the Tasks tab's vertical reservation so list rows line up across tabs even
        // though Rewards has no sub-tab strip of its own (subH 14 + 4 padding = 18).
        int listY = by + 14 + 4;
        int listH = paneY() + paneH() - BOTTOM_BAR_H - PADDING - listY;
        addPaginatedSelector(
                bx, listY, bw, listH, availableRewardItems, itemsPage,
                rewardItems::contains, rewardItems::add, rewardItems::remove,
                NameResolver::rewardItemName,
                p -> { itemsPage = p; rebuildWidgets(); });
    }

    private static String subLabel(String name, boolean active) {
        return active ? "▶ " + name : name;
    }

    /** Adds a hidden Button for tab hit-testing; visuals are drawn in {@link #render}. */
    private void addTabHitbox(int tx, int ty, Tab tab, String label) {
        addRenderableWidget(new Button(tx, ty, TAB_W, TAB_H, new TextComponent(""), b -> {
            if (activeTab != tab) { activeTab = tab; rebuildWidgets(); }
        }) {
            @Override public void renderButton(PoseStack ps, int mx, int my, float partial) { /* drawn by screen */ }
        });
    }

    /**
     * Adds a single-column list of selector rows + a pagination strip at the bottom of the
     * provided area. Each row is a full-width Button so click targets are obvious and no text
     * ever floats between two buttons.
     */
    private <T> void addPaginatedSelector(
            int x, int y, int width, int areaH,
            List<T> items, int page,
            Predicate<T> selected, Consumer<T> select, Consumer<T> deselect,
            Function<T, String> label, IntConsumer onPageChange) {

        int rows = Math.max(1, (areaH - (ROW_H + 4)) / (ROW_H + 1));
        int totalPages = Math.max(1, (items.size() + rows - 1) / rows);
        int p = Math.max(0, Math.min(page, totalPages - 1));
        int start = p * rows;
        int end = Math.min(start + rows, items.size());

        for (int i = start; i < end; i++) {
            T item = items.get(i);
            String lbl = (selected.test(item) ? "☑ " : "☐ ") + label.apply(item);
            int row = i - start;
            addRenderableWidget(new Button(x, y + row * (ROW_H + 1), width, ROW_H,
                    new TextComponent(lbl), b -> {
                if (selected.test(item)) deselect.accept(item); else select.accept(item);
                rebuildWidgets();
            }));
        }

        int navY = y + rows * (ROW_H + 1) + 2;
        addRenderableWidget(new Button(x, navY, 20, ROW_H, new TextComponent("<"),
                b -> { if (p > 0) onPageChange.accept(p - 1); }));
        Button pageLabel = new Button(x + 22, navY, width - 44, ROW_H,
                new TextComponent("page " + (p + 1) + " / " + totalPages), b -> {});
        pageLabel.active = false;
        addRenderableWidget(pageLabel);
        addRenderableWidget(new Button(x + width - 20, navY, 20, ROW_H, new TextComponent(">"),
                b -> { if (p < totalPages - 1) onPageChange.accept(p + 1); }));
    }

    private void resetAll() {
        taskTypes.clear();
        taskValues.clear();
        rewardItems.clear();
        maxAttempts = BountyFilter.DEFAULT_MAX_ATTEMPTS;
        typesPage = 0;
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
        int x = paneX();
        int y = paneY();
        int w = paneW();
        int h = paneH();

        // 1. White nine-slice window.
        ScreenTextures.DEFAULT_WINDOW_BACKGROUND.blit(ps, x, y, 0, w, h);

        // 2. Tabs.
        int tabsY = y - TAB_H + 4;
        drawTab(ps, x + 10,                    tabsY, activeTab == Tab.TASKS,   "Tasks");
        drawTab(ps, x + 10 + TAB_W + TAB_GAP, tabsY, activeTab == Tab.REWARDS, "Rewards");

        // 3. Bottom-bar label.
        int botY = y + h - BOTTOM_BAR_H + 4;
        this.font.draw(ps, new TextComponent("Attempts:"), x + PADDING, botY + 4, 0x3F2A14);

        // 4. Vault-level subtitle (small, top-right of pane).
        String sub = "lvl " + vaultLevel;
        int sw = this.font.width(sub);
        this.font.draw(ps, new TextComponent(sub), x + w - PADDING - sw, y + PADDING - 1, 0x6B5034);

        super.render(ps, mx, my, partial);
    }

    private void drawTab(PoseStack ps, int tx, int ty, boolean selected, String label) {
        if (selected) {
            ScreenTextures.TAB_BACKGROUND_TOP_SELECTED.blit(ps, tx, ty, 0, TAB_W, TAB_H);
        } else {
            ScreenTextures.TAB_BACKGROUND_TOP.blit(ps, tx, ty, 0, TAB_W, TAB_H);
        }
        int textColor = selected ? 0x3F2A14 : 0x6B5034;
        int sw = this.font.width(label);
        this.font.draw(ps, new TextComponent(label),
                tx + (TAB_W - sw) / 2f, ty + (TAB_H - 8) / 2f, textColor);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
