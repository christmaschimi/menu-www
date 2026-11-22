package com.chimi.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core logic with zero Minecraft dependencies, so it works on any version.
 * Feed it plain-text chat/system messages; it tracks payments, profit and auctions.
 */
public final class EconomyCore {
    private EconomyCore() {}

    // Amount like: 1000, 1,000, $1.5k, 2m, 3b
    private static final String AMOUNT = "\\$?([\\d,]*\\.?\\d+)\\s*([kKmMbB])?";

    // Anchored with ^ so a player typing "<Bob> Steve paid you 1m" in chat can't fake it.
    private static final Pattern RECEIVED =
            Pattern.compile("^\\s*([\\w.]{1,20}) paid you " + AMOUNT);
    private static final Pattern SENT =
            Pattern.compile("(?i)^\\s*you paid ([\\w.]{1,20}) " + AMOUNT);

    public record Payment(String player, double amount, boolean incoming) {}

    public static double parseAmount(String number, String suffix) {
        double v = Double.parseDouble(number.replace(",", ""));
        if (suffix == null) return v;
        return switch (Character.toLowerCase(suffix.charAt(0))) {
            case 'k' -> v * 1_000;
            case 'm' -> v * 1_000_000;
            case 'b' -> v * 1_000_000_000;
            default -> v;
        };
    }

    public static Optional<Payment> parse(String message) {
        Matcher in = RECEIVED.matcher(message);
        if (in.find()) {
            return Optional.of(new Payment(in.group(1), parseAmount(in.group(2), in.group(3)), true));
        }
        Matcher out = SENT.matcher(message);
        if (out.find()) {
            return Optional.of(new Payment(out.group(1), parseAmount(out.group(2), out.group(3)), false));
        }
        return Optional.empty();
    }

    public static String format(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000_000) return String.format("%.2fB", v / 1_000_000_000);
        if (a >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
        if (a >= 1_000) return String.format("%.2fK", v / 1_000);
        return String.format("%.0f", v);
    }

    /** Live profit tracking for the HUD. Toggle with start()/stop(). */
    public static final class Session {
        private boolean active;
        private double received;
        private double sent;

        public void start() { active = true; received = 0; sent = 0; }
        public void stop() { active = false; }
        public boolean isActive() { return active; }

        public void accept(Payment p) {
            if (!active) return;
            if (p.incoming()) received += p.amount(); else sent += p.amount();
        }

        public double profit() { return received - sent; }
        public double received() { return received; }
        public double sent() { return sent; }
    }

    /** One auction: highest total paid by a single player wins. */
    public static final class Auction {
        public final String prize;
        public final long durationSeconds;
        public final double minBid;
        public final double worth;
        private long endMillis;
        public volatile String lastBidPlayer = "";
        public volatile double lastBidAmount;
        public volatile long lastBidAt;
        private final Map<String, Double> totals = new HashMap<>();

        public Auction(String prize, long durationSeconds) { this(prize, durationSeconds, 0, 0); }

        public Auction(String prize, long durationSeconds, double minBid, double worth) {
            this.minBid = minBid;
            this.worth = worth;
            this.prize = prize;
            this.durationSeconds = durationSeconds;
            this.endMillis = System.currentTimeMillis() + durationSeconds * 1000;
        }

        public boolean isOver() { return System.currentTimeMillis() >= endMillis; }
        public void extend(long seconds) { if (!isOver()) endMillis += seconds * 1000; }
        public void endNow() { endMillis = System.currentTimeMillis(); }
        public long secondsLeft() { return Math.max(0, (endMillis - System.currentTimeMillis()) / 1000); }

        public double pot() { return leaderboard().stream().mapToDouble(Map.Entry::getValue).sum(); }
        public int bidders() { return leaderboard().size(); }

        public void accept(Payment p) {
            if (p.incoming() && !isOver()) {
                totals.merge(p.player(), p.amount(), Double::sum);
                lastBidPlayer = p.player();
                lastBidAmount = p.amount();
                lastBidAt = System.currentTimeMillis();
            }
        }

        public List<Map.Entry<String, Double>> leaderboard() {
            List<Map.Entry<String, Double>> list = new ArrayList<>();
            for (Map.Entry<String, Double> e : totals.entrySet()) if (e.getValue() >= minBid) list.add(e);
            list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            return list;
        }

        public Optional<Map.Entry<String, Double>> winner() {
            List<Map.Entry<String, Double>> lb = leaderboard();
            return lb.isEmpty() ? Optional.empty() : Optional.of(lb.get(0));
        }
    }
}
