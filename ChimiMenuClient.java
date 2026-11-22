package com.chimi.menu;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChimiMenuClient implements ClientModInitializer {
    public static final String ID = "chimismenu";
    public static final EconomyCore.Session session = new EconomyCore.Session();
    public static EconomyCore.Auction auction;
    public static ItemStack auctionStack = ItemStack.EMPTY;
    public static final java.util.List<Double> history = new java.util.ArrayList<>();
    public static long sessionStart;
    public record RecentPayment(String player, double amount, boolean incoming) {}
    public static final java.util.List<RecentPayment> recent = new java.util.ArrayList<>();
    private static double lastProfit = Double.NaN;
    private static boolean lastMode;
    // Auction waiting out its start delay.
    private static String pendingPrize;
    private static long pendingDuration, pendingStartMillis;
    private static double pendingMinBid, pendingWorth;
    private static KeyMapping openKey, nextSongKey, prevSongKey, auctionKey, auctionToggleKey;
    private static int tickCounter;
    private static final Pattern API_KEY = Pattern.compile("(?i)^\\s*api key is:?\\s*(\\S+)");

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
        openKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.chimismenu.open", InputConstants.Type.KEYSYM, InputConstants.KEY_G, category));

        nextSongKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.chimismenu.next_song", InputConstants.Type.KEYSYM, InputConstants.KEY_RBRACKET, category));
        prevSongKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.chimismenu.prev_song", InputConstants.Type.KEYSYM, InputConstants.KEY_LBRACKET, category));
        auctionKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.chimismenu.auction", InputConstants.Type.KEYSYM, InputConstants.KEY_U, category));
        auctionToggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.chimismenu.auction_toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));
        SpotifyBridge.start();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) {
                if (mc.gui.screen() == null) mc.gui.setScreen(new ChimiScreen());
            }
            while (auctionKey.consumeClick()) {
                if (mc.gui.screen() == null) mc.gui.setScreen(new AuctionScreen());
            }
            while (auctionToggleKey.consumeClick()) {
                if (mc.gui.screen() == null) AuctionScreen.quickToggle(mc);
            }
            while (nextSongKey.consumeClick()) SpotifyBridge.send("next");
            while (prevSongKey.consumeClick()) SpotifyBridge.send("prev");
            BalanceTracker.tick(mc, session.isActive());
            if (++tickCounter % 10 == 0) BalanceTracker.readSidebar(mc, session.isActive());
            if (tickCounter % 100 == 0 && session.isActive()) {
                history.add(profit());
                while (history.size() > 166) history.remove(0);
                recordSample();
            }
            if (tickCounter % 1200 == 0) Config.save();
            if (pendingPrize != null && System.currentTimeMillis() >= pendingStartMillis) {
                auction = new EconomyCore.Auction(pendingPrize, pendingDuration, pendingMinBid, pendingWorth);
                pendingPrize = null;
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            boolean ok = screen instanceof InventoryScreen
                    || (Config.I.bgAllScreens && screen instanceof AbstractContainerScreen<?>);
            if (!ok) return;
            ScreenEvents.afterBackground(screen).register((scr, g, mx, my, delta) ->
                    BackgroundManager.drawPanel(g, scr));
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { recordSample(); Config.save(); BalanceTracker.resetAll(); });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message.getString()));
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath(ID, "sky"), SkyOverlay::draw);
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath(ID, "block_outline"), BlockOutline::draw);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "auction"), AuctionHud::draw);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "profit"), ChimiMenuClient::drawHud);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "spotify"), SpotifyBridge::drawHud);
        // Crosshair: "Vanilla" keeps the normal one, any other style replaces it.
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (g, delta) -> {
            if ("Vanilla".equals(Config.I.crossStyle)) original.extractRenderState(g, delta);
            else Crosshair.drawHud(g, delta);
        });
    }

    public static void startSession() {
        session.start();
        sessionStart = System.currentTimeMillis();
        history.clear();
        lastProfit = Double.NaN;
        BalanceTracker.beginSession(Minecraft.getInstance());
    }

    public static void stopSession() {
        recordSample();
        session.stop();
        Config.save();
    }

    /** Adds the change in profit since the last sample to today's total (saved to config). */
    public static void recordSample() {
        if (!session.isActive()) return;
        double p = profit();
        boolean mode = usingBalance();
        if (!Double.isNaN(p) && !Double.isNaN(lastProfit) && mode == lastMode && p != lastProfit) {
            Config.I.daily.merge(Config.today(), p - lastProfit, Double::sum);
        }
        if (!Double.isNaN(p)) lastProfit = p;
        lastMode = mode;
    }

    public static void queueAuction(String prize, long durationSeconds, double minBid, double worth, int delaySeconds) {
        pendingPrize = null;
        if (delaySeconds <= 0) {
            auction = new EconomyCore.Auction(prize, durationSeconds, minBid, worth);
            return;
        }
        pendingPrize = prize;
        pendingDuration = durationSeconds;
        pendingMinBid = minBid;
        pendingWorth = worth;
        pendingStartMillis = System.currentTimeMillis() + delaySeconds * 1000L;
        auction = null;
    }

    public static void cancelAuction() {
        pendingPrize = null;
        auction = null;
        auctionStack = ItemStack.EMPTY;
    }

    public static boolean auctionPending() { return pendingPrize != null; }
    public static String pendingPrize() { return pendingPrize; }
    public static long pendingSecondsLeft() { return Math.max(0, (pendingStartMillis - System.currentTimeMillis()) / 1000); }

    /** Profit per hour, or NaN for the first 10 seconds of a session. */
    public static double perHour() {
        double hours = (System.currentTimeMillis() - sessionStart) / 3_600_000.0;
        return hours < 10.0 / 3600 ? Double.NaN : profit() / hours;
    }

    /** Real balance change when the API is connected, otherwise payments-only fallback. */
    public static boolean usingBalance() { return BalanceTracker.hasBalance(); }

    public static double profit() {
        return usingBalance() ? BalanceTracker.profit() : session.profit();
    }

    private static void onMessage(String text) {
        Matcher k = API_KEY.matcher(text);
        if (k.find()) {
            Config.I.apiKey = k.group(1);
            Config.save();
            return;
        }
        var payment = EconomyCore.parse(text);
        if (payment.isPresent()) {
            var pm = payment.get();
            recent.add(0, new RecentPayment(pm.player(), pm.amount(), pm.incoming()));
            while (recent.size() > 20) recent.remove(recent.size() - 1);
            session.accept(payment.get());
            if (auction != null) auction.accept(payment.get());
            return;
        }
        BalanceTracker.acceptChat(Minecraft.getInstance(), text, session.isActive());
    }

    private static void drawHud(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!Config.I.hudEnabled || !session.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        double profit = profit();
        double ph = perHour();
        String line = Config.I.profitText
                .replace("{profit}", (profit >= 0 ? "+" : "") + EconomyCore.format(profit))
                .replace("{perhour}", Double.isNaN(ph) ? "-" : EconomyCore.format(ph));
        int w = mc.font.width(line) + 8, h = 14;
        float sc = HudLayout.scale(0);
        int[] pos = HudLayout.pos(0, w, h, false);
        int x = Math.round(pos[0] / sc), y = Math.round(pos[1] / sc);
        g.pose().pushMatrix();
        g.pose().scale(sc, sc);
        g.fill(x, y, x + w, y + h, 0x99000000);
        g.text(mc.font, line, x + 4, y + 3, profit >= 0 ? 0xFF55FF55 : 0xFFFF5555);
        g.pose().popMatrix();
    }
}
