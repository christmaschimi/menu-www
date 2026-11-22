package com.chimi.menu;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Custom skies. The sky is painted as a grid of small coloured cells (gradient, aurora curtains, galaxy band, stars, moon)
 * over every pixel of sky you can see. Terrain, trees and buildings that block the sky are found with a horizon scan
 * (rays around you, refreshed continuously), so the sky is only drawn where real sky is visible and it stays hidden
 * behind hills, trees and roofs. First person, overworld only.
 */
public final class SkyOverlay {
    private SkyOverlay() {}

    /** Colours are RGB. aurora = green curtains. nebulaA/B = galaxy band colours (0 = no band). moon = disc colour (0 = none). */
    public record Preset(String name, int top, int horizon, int stars, int starTint, boolean aurora, int nebulaA, int nebulaB, int moon) {
        public int dot() { return top == 0 ? 0xFFA0A0A0 : 0xFF000000 | horizon; }
    }

    public static final Preset[] PRESETS = {
            new Preset("Default", 0, 0, 0, 0, false, 0, 0, 0),
            new Preset("Aurora", 0x040A26, 0x0C2C5E, 220, 0xDFFFF4, true, 0, 0, 0),
            new Preset("Galaxy", 0x07021A, 0x2A0B4A, 300, 0xFFFFFF, false, 0x7B3FE4, 0xFF4FD8, 0),
            new Preset("Golden Sunset", 0x3A1C6B, 0xFFB03A, 0, 0xFFFFFF, false, 0, 0, 0),
            new Preset("Pink Dusk", 0x4A5BA8, 0xFFA6C8, 40, 0xFFFFFF, false, 0, 0, 0),
            new Preset("Neon", 0x0A0020, 0x7A1A8A, 200, 0xFFFFFF, false, 0xFF2BD6, 0x2BE7FF, 0),
            new Preset("Blood Moon", 0x120004, 0x6A0A12, 140, 0xFFC8C8, false, 0, 0, 0xD02020),
            new Preset("Midnight", 0x02030A, 0x0A1A3A, 300, 0xFFFFFF, false, 0, 0, 0),
    };

    public static Preset current() {
        for (Preset p : PRESETS) if (p.name().equals(Config.I.skyPreset)) return p;
        return PRESETS[0];
    }

    // ---------- fixed star field ----------
    private static final int MAX_STARS = 300;
    private static final double[][] STAR = new double[MAX_STARS][5]; // x, y, z (unit), size, phase
    static {
        Random r = new Random(1337);
        for (int i = 0; i < MAX_STARS; i++) {
            double th = r.nextDouble() * Math.PI * 2, y = 0.03 + r.nextDouble() * 0.97, rr = Math.sqrt(1 - y * y);
            STAR[i][0] = rr * Math.cos(th);
            STAR[i][1] = y;
            STAR[i][2] = rr * Math.sin(th);
            STAR[i][3] = r.nextDouble() < 0.12 ? 2 : 1;
            STAR[i][4] = r.nextDouble() * 6.28;
        }
    }

    // ---------- horizon scan: highest blocked elevation (radians) for each compass direction ----------
    private static final int N = 120;                         // directions around you (3 degrees apart)
    private static final double RANGE = 96;                   // how far the scan looks
    private static final double[] SCAN_DEG = {0, 5, 10, 16, 23, 31, 40, 50, 62, 75};
    private static final double[] horizon = new double[N];
    private static int scanCursor;
    private static final int SCAN_PER_FRAME = 4;

    private static void scan(Minecraft mc, Vec3 eye) {
        for (int n = 0; n < SCAN_PER_FRAME; n++) {
            int i = scanCursor;
            scanCursor = (scanCursor + 1) % N;
            double az = i * (Math.PI * 2 / N);
            double result = Math.PI / 2;
            for (int k = 0; k < SCAN_DEG.length; k++) {
                double e = Math.toRadians(SCAN_DEG[k]);
                Vec3 to = eye.add(-Math.sin(az) * Math.cos(e) * RANGE, Math.sin(e) * RANGE, Math.cos(az) * Math.cos(e) * RANGE);
                var hit = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() == HitResult.Type.MISS) {
                    result = k == 0 ? 0 : (Math.toRadians(SCAN_DEG[k - 1]) + e) / 2;
                    break;
                }
            }
            horizon[i] = result;
        }
    }

    private static double horizonAt(double az) {
        double t = az / (Math.PI * 2);
        t -= Math.floor(t);
        double f = t * N;
        int i0 = (int) f % N, i1 = (i0 + 1) % N;
        double u = f - Math.floor(f);
        return horizon[i0] * (1 - u) + horizon[i1] * u;
    }

    // ---------- colour helpers ----------
    private static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static final double BAND_NX, BAND_NY, BAND_NZ;
    static {
        double x = 0.35, y = 0.75, z = 0.55, l = Math.sqrt(x * x + y * y + z * z);
        BAND_NX = x / l; BAND_NY = y / l; BAND_NZ = z / l;
    }

    // ---------- camera ----------
    private static double fx, fy, fz, rx, rz, ux, uy, uz, focal;
    private static int cxs, cys;

    private static double[] project(double x, double y, double z) {
        double d = x * fx + y * fy + z * fz;
        if (d < 0.08) return null;
        double sx = x * rx + z * rz, sy = x * ux + y * uy + z * uz;
        return new double[]{cxs + sx / d * focal, cys - sy / d * focal, d};
    }

    private static final int CELL = 6; // GUI pixels per sky cell

    public static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        Preset p = current();
        Minecraft mc = Minecraft.getInstance();
        if (!Config.I.skyOverlay || p.top() == 0 || mc.player == null || mc.level == null) return;
        if (mc.level.dimension() != Level.OVERWORLD) return;
        if (!mc.options.getCameraType().isFirstPerson() || mc.player.isUnderWater()) return;

        float pt = delta.getGameTimeDeltaPartialTick(true);
        Vec3 eye = mc.player.getEyePosition(pt);
        scan(mc, eye);

        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        double yaw = Math.toRadians(mc.player.getViewYRot(pt)), pit = Math.toRadians(mc.player.getViewXRot(pt));
        fx = -Math.sin(yaw) * Math.cos(pit);
        fy = -Math.sin(pit);
        fz = Math.cos(yaw) * Math.cos(pit);
        rx = -Math.cos(yaw);
        rz = -Math.sin(yaw);
        ux = -rz * fy;
        uy = rz * fx - rx * fz;
        uz = rx * fy;
        cxs = w / 2;
        cys = h / 2;
        double fov = Math.max(30, Math.min(140, mc.options.fov().get()));
        focal = (h / 2.0) / Math.tan(Math.toRadians(fov) / 2);

        double t = System.currentTimeMillis() / 1000.0;
        boolean band = p.nebulaA() != 0;

        for (int y = 0; y < h; y += CELL) {
            double b = -((y + CELL / 2.0) - cys) / focal;
            for (int x = 0; x < w; x += CELL) {
                double a = ((x + CELL / 2.0) - cxs) / focal;
                double vx = fx + a * rx + b * ux, vy = fy + b * uy, vz = fz + a * rz + b * uz;
                if (vy <= 0.0) continue;
                double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
                double nx = vx / len, ny = vy / len, nz = vz / len;
                double el = Math.asin(ny), az = Math.atan2(-nx, nz);
                if (el < horizonAt(az)) continue; // blocked by terrain

                // sky gradient
                int c = mix(p.horizon(), p.top(), Math.pow(el / (Math.PI / 2), 0.55));

                // galaxy band
                if (band) {
                    double d = nx * BAND_NX + ny * BAND_NY + nz * BAND_NZ;
                    double env = Math.exp(-(d * d) / 0.035);
                    double m = 0.55 + 0.45 * Math.sin(nx * 9 + Math.sin(nz * 7 + t * 0.02) * 2) * Math.sin(nz * 8 + ny * 6);
                    int nc = mix(p.nebulaA(), p.nebulaB(), 0.5 + 0.5 * Math.sin(nx * 4 + nz * 5));
                    c = mix(c, nc, env * Math.max(0, m) * 0.85);
                }

                // aurora curtains
                if (p.aurora()) {
                    double deg = Math.toDegrees(el);
                    double env = smooth(3, 11, deg) * Math.exp(-Math.max(0, deg - 12) / 14.0);
                    double ray = 0.5 + 0.5 * Math.sin(az * 34 + t * 0.8 + 2.2 * Math.sin(az * 5 + t * 0.35));
                    ray = Math.pow(ray, 2.2);
                    double mask = 0.6 + 0.4 * Math.sin(az * 2.3 + t * 0.15);
                    double amt = env * (ray * 0.95 + 0.18) * mask;
                    int ac = mix(0x2BFFA0, 0x6A5BFF, (deg - 12) / 40.0);
                    c = mix(c, ac, amt);
                }
                g.fill(x, y, x + CELL, y + CELL, 0xFF000000 | c);
            }
        }

        // moon
        if (p.moon() != 0) {
            double mx = 0.55, my = 0.62, mz = -0.55, ml = Math.sqrt(mx * mx + my * my + mz * mz);
            mx /= ml; my /= ml; mz /= ml;
            double[] m = project(mx, my, mz);
            if (m != null && Math.asin(my) >= horizonAt(Math.atan2(-mx, mz))) {
                int r = (int) Math.max(6, focal * 0.06 / m[2]);
                for (int dy = -r; dy < r; dy++) {
                    int hw = (int) Math.sqrt((double) r * r - (double) dy * dy);
                    g.fill((int) m[0] - hw, (int) m[1] + dy, (int) m[0] + hw, (int) m[1] + dy + 1, 0xE0000000 | p.moon());
                }
            }
        }

        // stars
        double tw = System.currentTimeMillis() / 700.0;
        int n = Math.min(MAX_STARS, p.stars());
        for (int i = 0; i < n; i++) {
            double sx = STAR[i][0], sy = STAR[i][1], sz = STAR[i][2];
            if (Math.asin(sy) < horizonAt(Math.atan2(-sx, sz))) continue;
            double[] s = project(sx, sy, sz);
            if (s == null) continue;
            int x = (int) s[0], y = (int) s[1];
            if (x < 0 || y < 0 || x >= w || y >= h) continue;
            int al = (int) (170 + 85 * Math.sin(tw + STAR[i][4]));
            int sz2 = (int) STAR[i][3];
            g.fill(x, y, x + sz2, y + sz2, (Math.max(90, Math.min(255, al)) << 24) | p.starTint());
        }
    }
}
