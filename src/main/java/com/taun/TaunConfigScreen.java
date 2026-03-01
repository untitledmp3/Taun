package com.taun;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;
import java.util.function.IntSupplier;

public class TaunConfigScreen extends Screen {

    private final Screen parent;

    private enum Tab { PEST_MODES, DYNAMIC_REST, UTILITY }
    private Tab currentTab = Tab.PEST_MODES;

    private boolean secRodswapOpen   = true;
    private boolean secWardrobeOpen  = true;
    private boolean secEtherwarpOpen = true;
    private boolean secEqSwapOpen    = true;
    private boolean secRewarpOpen    = true;

    private boolean secTriggersOpen  = true;
    private boolean secSellOpen      = true;
    private boolean secDropBooksOpen = true;
    private boolean secDelaysOpen    = true;
    private boolean secToolsOpen     = true;

    private int  scrollY      = 0;
    private int  contentHeight = 0;
    private boolean scrollable = false;
    private boolean rebuilding = false;  // guard against spam clicks during rebuild

    private static final int TAB_Y      = 22;
    private static final int TAB_H      = 18;
    private static final int TAB_W      = 80;
    private static final int CONTENT_Y  = TAB_Y + TAB_H + 6;
    private static final int BTN_W      = 220;
    private static final int BTN_H      = 18;
    private static final int GAP        = 22;
    private static final int SEC_GAP    = 4;
    private static final int SCROLL_SPD = 12;

    public TaunConfigScreen(Screen parent) {
        super(Text.literal("Taun+++ Settings"));
        this.parent = parent;
    }

    // ─── DeferredSlider ───────────────────────────────────────────────────────
    private static abstract class DeferredSlider extends SliderWidget {
        DeferredSlider(int x, int y, int w, int h, Text msg, double value) {
            super(x, y, w, h, msg, value);
        }
        @Override protected abstract void updateMessage();
        protected abstract void commit();
        @Override protected void applyValue() { commit(); }
    }

    // ─── init ─────────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        rebuilding = true;
        scrollable = (currentTab == Tab.UTILITY);
        if (!scrollable) scrollY = 0;

        buildTabBar();
        switch (currentTab) {
            case PEST_MODES   -> buildPestModesTab();
            case DYNAMIC_REST -> buildDynamicRestTab();
            case UTILITY      -> buildUtilityTab();
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), btn -> close())
                .dimensions(width / 2 - 50, height - 26, 100, BTN_H).build());
        rebuilding = false;
    }

    // ─── Tab bar ──────────────────────────────────────────────────────────────
    private void buildTabBar() {
        Tab[] tabs      = Tab.values();
        String[] labels = { "Pest Modes", "Dynamic Rest", "Utility" };
        int totalW = tabs.length * TAB_W + (tabs.length - 1) * 2;
        int startX = (width - totalW) / 2;
        for (int i = 0; i < tabs.length; i++) {
            final Tab tab = tabs[i];
            boolean active = currentTab == tab;
            String lbl = active ? "§e§l" + labels[i] : labels[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(lbl), btn -> {
                currentTab = tab; scrollY = 0; clearChildren(); init();
            }).dimensions(startX + i * (TAB_W + 2), TAB_Y, TAB_W, TAB_H).build());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DYNAMIC REST TAB
    // ═══════════════════════════════════════════════════════════════════════════
    private void buildDynamicRestTab() {
        int col = width / 2 - BTN_W / 2;
        int[] y = { CONTENT_Y };

        y[0] = addToggle(col, y[0], "Dynamic Rest", TaunCore.isDynamicRestEnabled(), TaunCore::setDynamicRestEnabled);

        if (TaunCore.isDynamicRestEnabled()) {
            y[0] += SEC_GAP;
            addIntSlider(col + 8, y[0], BTN_W - 8, "Script Time", "min", 10, 120,
                    TaunCore::getRestScriptingTime, TaunCore::setRestScriptingTimeSilent); y[0] += GAP;
            addIntSlider(col + 8, y[0], BTN_W - 8, "Script Offset ±", "min", 0, 15,
                    TaunCore::getRestScriptingTimeOffset, TaunCore::setRestScriptingTimeOffsetSilent); y[0] += GAP;
            addIntSlider(col + 8, y[0], BTN_W - 8, "Break Time", "min", 1, 60,
                    TaunCore::getRestBreakTime, TaunCore::setRestBreakTimeSilent); y[0] += GAP;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PEST MODES TAB
    // ═══════════════════════════════════════════════════════════════════════════
    private void buildPestModesTab() {
        int col = width / 2 - BTN_W / 2;
        int[] y = { CONTENT_Y };

        // Rod Swap
        addSection(col, y, "Rod Swap", secRodswapOpen, v -> secRodswapOpen = v);
        if (secRodswapOpen) {
            y[0] = addToggle(col, y[0], "Rod Swap",  TaunCore.isRodswapEnabled(),  TaunCore::setRodswapEnabled);
            y[0] = addToggle(col, y[0], "Rose Drag", TaunCore.isRosedragEnabled(), TaunCore::setRosedragEnabled);
            y[0] += SEC_GAP;
        }

        // Wardrobe Swap
        addSection(col, y, "Wardrobe Swap", secWardrobeOpen, v -> secWardrobeOpen = v);
        if (secWardrobeOpen) {
            y[0] = addToggle(col, y[0], "Wardrobe Swap", TaunCore.isWardrobeSwapEnabled(), TaunCore::setWardrobeSwapEnabled);
            addIntSlider(col + 8, y[0], BTN_W - 8, "FF Slot",  "", 1, 9,
                    TaunCore::getWardrobeFfSlot,  TaunCore::setWardrobeFfSlotSilent);  y[0] += GAP;
            addIntSlider(col + 8, y[0], BTN_W - 8, "BPC Slot", "", 1, 9,
                    TaunCore::getWardrobeBpcSlot, TaunCore::setWardrobeBpcSlotSilent); y[0] += GAP;
            y[0] += SEC_GAP;
        }

        // Etherwarp
        addSection(col, y, "Etherwarp", secEtherwarpOpen, v -> secEtherwarpOpen = v);
        if (secEtherwarpOpen) {
            y[0] = addToggle(col, y[0], "Etherwarp", TaunCore.isEtherwarpEnabled(), TaunCore::setEtherwarpEnabled);
            addNote(col, y, "§8Use §e/pest setetherwarp §8to calibrate glass position");
            y[0] += SEC_GAP;
        }

        // Equipment Swap
        addSection(col, y, "Equipment Swap", secEqSwapOpen, v -> secEqSwapOpen = v);
        if (secEqSwapOpen) {
            y[0] = addToggle(col, y[0], "Equipment Swap",     TaunCore.isEqSwapEnabled(), TaunCore::setEqSwapEnabled);
            y[0] = addToggle(col, y[0], "Zorro Cape (Jacob)", TaunCore.isZorroEnabled(),  TaunCore::setZorroEnabled);
            if (TaunCore.isZorroEnabled())
                addNote(col, y, "§e⚠ Give Jacob event priority in tab");
            y[0] += SEC_GAP;
        }

        // Taunahi Rewarp
        addSection(col, y, "Taunahi Rewarp", secRewarpOpen, v -> secRewarpOpen = v);
        if (secRewarpOpen) {
            y[0] = addToggle(col, y[0], "Taunahi Intermediate Rewarp",
                    TaunCore.isTaunahiRewarpEnabled(), TaunCore::setTaunahiRewarpEnabled);
            if (TaunCore.isTaunahiRewarpEnabled())
                addNote(col, y, "§e⚠ Coordinate triggers are disabled");
            else
                addNote(col, y, "§8Enable only if using Taunahi's rewarp");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY TAB  (scrollable)
    // ═══════════════════════════════════════════════════════════════════════════
    private void buildUtilityTab() {
        int col = width / 2 - BTN_W / 2;
        int[] vy = { CONTENT_Y }; // virtual Y — widgets placed at (vy - scrollY)

        // Triggers
        addSectionS(col, vy, "Triggers", secTriggersOpen, v -> secTriggersOpen = v);
        if (secTriggersOpen) {
            addToggleS(col, vy, "Chat Triggers", TaunCore.isChatTriggersEnabled(), TaunCore::setChatTriggersEnabled);
            if (TaunCore.isTaunahiRewarpEnabled())
                addNoteS(col, vy, "§e⚠ Coordinate triggers are disabled");
            else
                addToggleS(col, vy, "Coord Triggers", TaunCore.isCoordTriggersEnabled(), TaunCore::setCoordTriggersEnabled);
            vy[0] += SEC_GAP;
        }

        // Auto Sell
        addSectionS(col, vy, "Auto Sell", secSellOpen, v -> secSellOpen = v);
        if (secSellOpen) {
            addToggleS(col, vy, "George Slug/Rat Sell", TaunCore.isGeorgeSlugSellEnabled(), TaunCore::setGeorgeSlugSellEnabled);
            if (TaunCore.isGeorgeSlugSellEnabled())
                addIntSliderS(col + 8, vy, BTN_W - 8, "Slug/Rat Threshold", "", 1, 10,
                        TaunCore::getSlugSellThreshold, TaunCore::setSlugSellThresholdSilent);
            addToggleS(col, vy, "Extra Sell (Booster Cookie)", TaunCore.isBoosterCookieEnabled(), TaunCore::setBoosterCookieEnabled);
            if (TaunCore.isBoosterCookieEnabled())
                addIntSliderS(col + 8, vy, BTN_W - 8, "Extra Sell Threshold", "", 1, 10,
                        TaunCore::getExtraSellThreshold, TaunCore::setExtraSellThresholdSilent);
            addToggleS(col, vy, "Sell Vinyls", TaunCore.isSellVinylsEnabled(), TaunCore::setSellVinylsEnabled);
            vy[0] += SEC_GAP;
        }

        // Drop Books
        addSectionS(col, vy, "Drop Books", secDropBooksOpen, v -> secDropBooksOpen = v);
        if (secDropBooksOpen) {
            addToggleS(col, vy, "Drop Books", TaunCore.isDropBooksEnabled(), TaunCore::setDropBooksEnabled);
            if (TaunCore.isDropBooksEnabled())
                addIntSliderS(col + 8, vy, BTN_W - 8, "Books Threshold", "", 1, 10,
                        TaunCore::getDropBooksThreshold, TaunCore::setDropBooksThresholdSilent);
            addNoteS(col, vy, "§8Only useful for ironman");
            vy[0] += SEC_GAP;
        }

        // Delays
        addSectionS(col, vy, "Delays", secDelaysOpen, v -> secDelaysOpen = v);
        if (secDelaysOpen) {
            int rnd = TaunCore.getRandomDelay();
            addDrawableChild(new DeferredSlider(col + 8, sy(vy[0]), BTN_W - 8, BTN_H,
                    Text.literal("Random Delay: " + (rnd == 0 ? "OFF" : "±" + rnd + "ms")),
                    rnd / 250.0) {
                @Override protected void updateMessage() {
                    int v = (int)(value * 250);
                    setMessage(Text.literal("Random Delay: " + (v == 0 ? "OFF" : "±" + v + "ms")));
                }
                @Override protected void commit() { TaunCore.setRandomDelaySilent((int)(value * 250)); }
            }); vy[0] += GAP;

            long gui = TaunCore.getGuiClickDelay();
            addDrawableChild(new DeferredSlider(col + 8, sy(vy[0]), BTN_W - 8, BTN_H,
                    Text.literal("GUI Click Delay: " + gui + "ms"),
                    (gui - 350) / 1150.0) {
                @Override protected void updateMessage() {
                    setMessage(Text.literal("GUI Click Delay: " + (long)(350 + value * 1150) + "ms"));
                }
                @Override protected void commit() { TaunCore.setGuiClickDelaySilent((long)(350 + value * 1150)); }
            }); vy[0] += GAP;

            addNoteS(col, vy, "§8Increase GUI delay if equip swap fails on high ping");
            vy[0] += SEC_GAP;
        }

        // Tools
        addSectionS(col, vy, "Tools", secToolsOpen, v -> secToolsOpen = v);
        if (secToolsOpen) {
            addNoteS(col, vy, "§8Run these commands in chat:");
            addNoteS(col, vy, "§e/pest reload  §8— reload triggers.txt");
            addNoteS(col, vy, "§e/pest detect  §8— show detected farming tools");
            addNoteS(col, vy, "§e/pest status  §8— show all toggle states");
            addNoteS(col, vy, "§e/pest files   §8— open config folder");
            addNoteS(col, vy, "§e/pest setetherwarp  §8— calibrate etherwarp");
            addNoteS(col, vy, "§e/pest wardrobe  §8— configure wardrobe slots");
        }

        contentHeight = vy[0] - CONTENT_Y;
    }

    // ─── Scroll support ───────────────────────────────────────────────────────

    /** Converts virtual Y → screen Y */
    private int sy(int vy) { return vy - scrollY; }

    private int maxScroll() {
        int viewH = height - CONTENT_Y - BTN_H - 8;
        return Math.max(0, contentHeight - viewH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizAmt, double vertAmt) {
        if (scrollable) {
            scrollY -= (int)(vertAmt * SCROLL_SPD);
            scrollY = Math.max(0, Math.min(scrollY, maxScroll()));
            clearChildren();
            init();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizAmt, vertAmt);
    }

    // ─── Scrollable widget helpers (suffix S) ─────────────────────────────────

    private void addSectionS(int col, int[] vy, String label, boolean open, BoolSetter setter) {
        addDrawableChild(ButtonWidget.builder(
                Text.literal(open ? "§e▼ " + label : "§e▶ " + label),
                btn -> { setter.set(!open); clearChildren(); init(); }
        ).dimensions(col, sy(vy[0]), BTN_W, BTN_H).build());
        vy[0] += GAP;
    }

    private void addToggleS(int col, int[] vy, String label, boolean current, BoolSetter setter) {
        addDrawableChild(ButtonWidget.builder(
                Text.literal("  " + label + ": " + onOff(current)),
                btn -> {
                    if (rebuilding) return;
                    setter.set(!current);
                    clearChildren(); init();
                }
        ).dimensions(col + 8, sy(vy[0]), BTN_W - 8, BTN_H).build());
        vy[0] += GAP;
    }

    private void addNoteS(int col, int[] vy, String text) {
        addDrawableChild(ButtonWidget.builder(Text.literal(text), btn -> {})
                .dimensions(col + 8, sy(vy[0]), BTN_W - 8, BTN_H - 4).build());
        vy[0] += GAP;
    }

    private void addIntSliderS(int x, int[] vy, int w, String label, String unit,
                                int min, int max, IntSupplier getter, Consumer<Integer> setter) {
        int cur = getter.getAsInt();
        double norm = (max == min) ? 0 : (double)(cur - min) / (max - min);
        addDrawableChild(new DeferredSlider(x, sy(vy[0]), w, BTN_H,
                Text.literal(label + ": " + cur + (unit.isEmpty() ? "" : " " + unit)), norm) {
            @Override protected void updateMessage() {
                int v = (int)Math.round(min + value * (max - min));
                setMessage(Text.literal(label + ": " + v + (unit.isEmpty() ? "" : " " + unit)));
            }
            @Override protected void commit() {
                setter.accept((int)Math.round(min + value * (max - min)));
            }
        });
        vy[0] += GAP;
    }

    // ─── Non-scrollable widget helpers ────────────────────────────────────────

    @FunctionalInterface interface BoolSetter { void set(boolean v); }

    private void addIntSlider(int x, int y, int w, String label, String unit,
                               int min, int max, IntSupplier getter, Consumer<Integer> setter) {
        int cur = getter.getAsInt();
        double norm = (max == min) ? 0 : (double)(cur - min) / (max - min);
        addDrawableChild(new DeferredSlider(x, y, w, BTN_H,
                Text.literal(label + ": " + cur + (unit.isEmpty() ? "" : " " + unit)), norm) {
            @Override protected void updateMessage() {
                int v = (int)Math.round(min + value * (max - min));
                setMessage(Text.literal(label + ": " + v + (unit.isEmpty() ? "" : " " + unit)));
            }
            @Override protected void commit() {
                setter.accept((int)Math.round(min + value * (max - min)));
            }
        });
    }

    private void addSection(int col, int[] y, String label, boolean open, BoolSetter setter) {
        addDrawableChild(ButtonWidget.builder(
                Text.literal(open ? "§e▼ " + label : "§e▶ " + label),
                btn -> { setter.set(!open); clearChildren(); init(); }
        ).dimensions(col, y[0], BTN_W, BTN_H).build());
        y[0] += GAP;
    }

    private int addToggle(int col, int y, String label, boolean current, BoolSetter setter) {
        addDrawableChild(ButtonWidget.builder(
                Text.literal("  " + label + ": " + onOff(current)),
                btn -> {
                    if (rebuilding) return;
                    setter.set(!current);
                    clearChildren(); init();
                }
        ).dimensions(col + 8, y, BTN_W - 8, BTN_H).build());
        return y + GAP;
    }

    private void addNote(int col, int[] y, String text) {
        addDrawableChild(ButtonWidget.builder(Text.literal(text), btn -> {})
                .dimensions(col + 8, y[0], BTN_W - 8, BTN_H - 4).build());
        y[0] += GAP;
    }

    private static String onOff(boolean b) { return b ? "§aON" : "§cOFF"; }

    // ─── Render ───────────────────────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xAA000000);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        super.render(ctx, mouseX, mouseY, delta);

        // Thin scroll bar on the right edge of the content column
        if (scrollable && maxScroll() > 0) {
            int viewH   = height - CONTENT_Y - BTN_H - 8;
            int trackX  = width / 2 + BTN_W / 2 + 4;
            ctx.fill(trackX, CONTENT_Y, trackX + 3, CONTENT_Y + viewH, 0x44FFFFFF);
            int thumbH  = Math.max(16, viewH * viewH / Math.max(1, contentHeight));
            int thumbY  = CONTENT_Y + scrollY * (viewH - thumbH) / Math.max(1, maxScroll());
            ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xCCFFFFFF);
        }
    }

    @Override
    public void close() {
        assert client != null;
        client.setScreen(parent);
    }
}
