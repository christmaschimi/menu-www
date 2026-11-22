package com.chimi.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Auction set-up menu: pick an item, name, timer, then start. Open with U (or from the main menu). */
public class AuctionScreen extends Screen {
    public static final int[] TIMERS = {15, 30, 60, 120, 180, 300};
    private static final String[] TIMER_LABELS = {"15s", "30s", "1m", "2m", "3m", "5m"};

    private static int selectedSlot = -1;
    private static String displayName = "";
    private static String status = "";

    private record Btn(String label, int x, int y, int w, int h, boolean active, int color) {}
    private final List<Btn> btns = new ArrayList<>();
    private final List<int[]> cells = new ArrayList<>(); // {slot, x, y}
    private EditBox nameBox;
    private int px, py, pw, ph, nameX, nameY, nameW;

    public AuctionScreen() { super(Component.literal("Auction")); }

    // ---------- shared actions (also used by the K key) ----------
    /** Starts an auction from the saved settings, or ends the running one. */
    public static void quickToggle(net.minecraft.client.Minecraft mc) {
        var a = ChimiMenuClient.auction;
        if (a != null && !a.isOver()) { a.endNow(); return; }
        start(mc);
    }

    public static void start(net.minecraft.client.Minecraft mc) {
        if (mc.player == null) return;
        ItemStack s = selectedSlot >= 0 ? mc.player.getInventory().getItem(selectedSlot) : ItemStack.EMPTY;
        String prize = !displayName.isBlank() ? displayName.trim()
                : !s.isEmpty() ? s.getHoverName().getString() : "";
        if (prize.isEmpty()) { status = "Pick an item or type a display name first."; return; }
        ChimiMenuClient.auctionStack = s.isEmpty() ? ItemStack.EMPTY : s.copy();
        int t = TIMERS[Math.floorMod(Config.I.aucTimerIdx, TIMERS.length)];
        ChimiMenuClient.queueAuction(prize, t, 0, 0, 0);
        status = "Auction started.";
    }

    private static String payName(net.minecraft.client.Minecraft mc) {
        return mc.player == null ? "<you>" : mc.player.getName().getString();
    }

    // ---------- layout ----------
    private void btn(String label, int x, int y, int w, int h, boolean active, Runnable r) {
        addRenderableWidget(Button.builder(Component.empty(), b -> r.run()).bounds(x, y, w, h).build());
        btns.add(new Btn(label, x, y, w, h, active, 0));
    }

    private void colorBtn(String label, int x, int y, int w, int h, int color, Runnable r) {
        addRenderableWidget(Button.builder(Component.empty(), b -> r.run()).bounds(x, y, w, h).build());
        btns.add(new Btn(label, x, y, w, h, false, color));
    }

    @Override
    protected void init() {
        if (MenuScale.apply(this)) { rebuildWidgets(); return; }
        btns.clear();
        cells.clear();
        pw = Math.min(width - 8, 340);
        ph = Math.min(height - 8, 236);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int ix = px + 10, iw = pw - 20;

        // item grid: 36 inventory slots, two rows of 18
        int cell = Math.min(17, iw / 18);
        int gx = ix, gy = py + 30;
        for (int i = 0; i < 36; i++) {
            final int slot = i < 27 ? 9 + i : i - 27; // main inventory first, hotbar last
            int x = gx + (i % 18) * cell, y = gy + (i / 18) * cell;
            cells.add(new int[]{slot, x, y, cell});
            addRenderableWidget(Button.builder(Component.empty(), b -> { selectedSlot = slot; status = ""; }).bounds(x, y, cell - 1, cell - 1).build());
        }

        // display name
        nameX = ix + 70; nameY = py + 30 + 2 * cell + 8; nameW = iw - 70;
        nameBox = new EditBox(font, nameX + 5, nameY + 4, nameW - 10, 10, Component.empty());
        nameBox.setBordered(false);
        nameBox.setMaxLength(40);
        nameBox.setValue(displayName);
        nameBox.setResponder(s -> displayName = s);
        addRenderableWidget(nameBox);

        // timer
        int ty = nameY + 24;
        int tb = (iw - 70 - 5 * 4) / 6;
        for (int i = 0; i < TIMERS.length; i++) {
            final int idx = i;
            btn(TIMER_LABELS[i], ix + 70 + i * (tb + 4), ty, tb, 16, Config.I.aucTimerIdx == i, () -> { Config.I.aucTimerIdx = idx; Config.save(); rebuildWidgets(); });
        }

        // settings row 1: theme | - HUD x + | board | alerts
        int r1 = ty + 26, gap = 4, bw = (iw - 3 * gap) / 4;
        btn("Theme: " + AuctionHud.theme().name(), ix, r1, bw, 16, false, () -> { Config.I.aucTheme = (Config.I.aucTheme + 1) % AuctionHud.THEMES.length; Config.save(); });
        int mw = 16, hudW = bw - 2 * mw - 4;
        btn("-", ix + bw + gap, r1, mw, 16, false, () -> { Config.I.aucHudScale = Math.max(5, Config.I.aucHudScale - 1); Config.save(); });
        btn("+", ix + bw + gap + mw + 2 + hudW + 2, r1, mw, 16, false, () -> { Config.I.aucHudScale = Math.min(30, Config.I.aucHudScale + 1); Config.save(); });
        btn("Board: Top " + Config.I.aucBoard, ix + 2 * (bw + gap), r1, bw, 16, false, () -> { Config.I.aucBoard = Config.I.aucBoard % 5 + 1; Config.save(); rebuildWidgets(); });
        btn("Bid alerts: " + (Config.I.aucAlerts ? "ON" : "OFF"), ix + 3 * (bw + gap), r1, bw, 16, Config.I.aucAlerts, () -> { Config.I.aucAlerts = !Config.I.aucAlerts; Config.save(); rebuildWidgets(); });

        // settings row 2
        int r2 = r1 + 22;
        btn("Test Bid", ix, r2, bw, 16, false, AuctionHud::testBid);
        btn("Test Win", ix + bw + gap, r2, bw, 16, false, AuctionHud::testWin);

        // action row
        int r3 = r2 + 26;
        int sw = (iw - 3 * gap) * 34 / 100, ow = (iw - 3 * gap - sw) / 3;
        btn("> START AUCTION", ix, r3, sw, 18, true, () -> start(minecraft));
        btn("END NOW", ix + sw + gap, r3, ow, 18, false, () -> { var a = ChimiMenuClient.auction; if (a != null) a.endNow(); });
        btn("+15s", ix + sw + gap + ow + gap, r3, ow, 18, false, () -> { var a = ChimiMenuClient.auction; if (a != null) a.extend(15); });
        btn("CANCEL", ix + sw + gap + 2 * (ow + gap), r3, ow, 18, false, () -> { ChimiMenuClient.cancelAuction(); status = "Cancelled."; });
    }

    // ---------- render ----------
    private static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int border, int fill) {
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        AuctionHud.Theme th = AuctionHud.theme();
        box(g, px - 1, py - 1, pw + 2, ph + 2, th.accent(), th.bg());
        box(g, px, py, pw, ph, th.border(), th.bg());
        g.fill(px + 1, py + 1, px + pw - 1, py + ph - 1, th.bg());

        String name = minecraft != null && minecraft.player != null ? payName(minecraft).toUpperCase() : "AUCTION";
        String title = "* " + name + " AUCTION *";
        g.text(font, title, px + (pw - font.width(title)) / 2, py + 8, th.accent());
        g.text(font, "ITEM", px + 10, py + 20, 0xFF9AA4C0);

        // item grid backgrounds (drawn under the invisible buttons' own look, then re-drawn on top below)
        super.extractRenderState(g, mx, my, delta);

        for (int[] c : cells) {
            int x = c[1], y = c[2], s = c[3] - 1;
            boolean hover = mx >= x && mx < x + s && my >= y && my < y + s;
            g.fill(x, y, x + s, y + s, hover ? 0xFF3A4470 : 0xFF1A2236);
            if (minecraft != null && minecraft.player != null) {
                ItemStack st = minecraft.player.getInventory().getItem(c[0]);
                if (!st.isEmpty()) g.item(st, x, y);
            }
            if (c[0] == selectedSlot) {
                g.fill(x, y, x + s, y + 1, th.accent());
                g.fill(x, y + s - 1, x + s, y + s, th.accent());
                g.fill(x, y, x + 1, y + s, th.accent());
                g.fill(x + s - 1, y, x + s, y + s, th.accent());
            }
        }

        g.text(font, "Display name", px + 10, nameY + 4, 0xFF9AA4C0);
        box(g, nameX, nameY, nameW, 16, th.border(), 0xFF000000);
        if (nameBox != null) {
            // EditBox is a widget, so super already drew it; this re-draws the hint only when empty.
            if (nameBox.getValue().isEmpty()) g.text(font, "Name shown on the HUD", nameX + 5, nameY + 4, 0xFF5A6078);
        }
        g.text(font, "Timer", px + 10, nameY + 28, 0xFF9AA4C0);

        for (Btn b : btns) {
            boolean hover = mx >= b.x() && mx < b.x() + b.w() && my >= b.y() && my < b.y() + b.h();
            int fill = b.active() ? mixWhite(th.panel(), 0.12f) : 0xFF4A4A4A;
            if (hover) fill = mixWhite(fill, 0.15f);
            int border = b.active() ? th.accent() : 0xFF1C1C1C;
            box(g, b.x(), b.y(), b.w(), b.h(), border, fill);
            int color = b.label().contains("START") ? 0xFF5CE08A
                    : b.label().contains("END") ? 0xFFFFB347
                    : b.label().contains("CANCEL") ? 0xFFFF5555
                    : b.active() ? th.accent() : 0xFFE0E0E0;
            if (b.label().equals("-") || b.label().equals("+")) color = 0xFFFFFFFF;
            g.text(font, b.label(), b.x() + (b.w() - font.width(b.label())) / 2, b.y() + (b.h() - 8) / 2, color);
        }
        // HUD scale label between - and +
        int iw = pw - 20, bw = (iw - 12) / 4, mw = 16;
        int hx = px + 10 + bw + 4 + mw, hudW = bw - 2 * mw - 4;
        int r1y = nameY + 24 + 26;
        String hs = "HUD " + (Config.I.aucHudScale / 10) + "." + (Config.I.aucHudScale % 10) + "x";
        g.text(font, hs, hx + (hudW + 4 - font.width(hs)) / 2, r1y + 4, 0xFFE0E0E0);

        String line = status.isEmpty()
                ? "Ready. Viewers bid with /pay " + (minecraft == null ? "" : payName(minecraft)) + " <amount>"
                : status;
        g.text(font, line, px + (pw - font.width(line)) / 2, py + ph - 24, 0xFFFFFFFF);
        String keys = "Keys: U menu - K start/end";
        g.text(font, keys, px + (pw - font.width(keys)) / 2, py + ph - 13, 0xFF6A7390);
    }

    private static int mixWhite(int c, float t) {
        int r = (int) (((c >> 16) & 255) * (1 - t) + 255 * t), gg = (int) (((c >> 8) & 255) * (1 - t) + 255 * t), b = (int) ((c & 255) * (1 - t) + 255 * t);
        return 0xFF000000 | (r << 16) | (gg << 8) | b;
    }

    @Override
    public void removed() { MenuScale.restore(); super.removed(); }

    @Override
    public boolean isPauseScreen() { return false; }
}
