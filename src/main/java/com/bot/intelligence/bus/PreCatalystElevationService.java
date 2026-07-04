package com.bot.intelligence.bus;

import com.bot.intelligence.MarketKnowledgeDatabase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proactively elevates symbols whose Polygon-enriched market state looks unusual
 * before a confirmed catalyst arrives. This is intentionally not a trading signal
 * by itself; it is a research-priority and evidence signal that helps the agents
 * notice unusual volume/range/momentum earlier.
 */
public final class PreCatalystElevationService {
    private volatile boolean running;
    private volatile Thread worker;

    public synchronized void start() {
        if (running) return;
        if (!boolEnv("PRE_CATALYST_ELEVATION_ENABLED", true)) {
            System.out.println("PRE-CATALYST ELEVATION SERVICE DISABLED: PRE_CATALYST_ELEVATION_ENABLED=false");
            return;
        }
        running = true;
        worker = new Thread(this::loop, "pre-catalyst-elevation-service");
        worker.setDaemon(true);
        worker.start();
        System.out.println("PRE-CATALYST ELEVATION SERVICE STARTED: source=MarketKnowledgeDatabase intervalMs=" + intervalMs() + " topK=" + topK());
    }

    public synchronized void stop() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                runOnce();
            } catch (Exception e) {
                System.out.println("PRE-CATALYST ELEVATION ERROR: " + safe(e.getMessage()));
            }
            sleep(intervalMs());
        }
    }

    private void runOnce() {
        MarketIntelligenceBus bus = MarketIntelligenceBus.getInstance();
        List<MarketKnowledgeDatabase.Record> records = MarketKnowledgeDatabase.getInstance().topByActivity(topK());
        int emitted = 0;
        for (MarketKnowledgeDatabase.Record r : records) {
            double score = score(r);
            if (score < minScore()) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("provider", "POLYGON_MARKET_KNOWLEDGE");
            m.put("source", "PRE_CATALYST_ELEVATION");
            m.put("activityScore", fmt(r.activityScore()));
            m.put("preCatalystScore", fmt(score));
            m.put("price", fmt(r.price));
            m.put("snapshotVolume", fmt(r.snapshotVolume));
            m.put("minuteVolume", fmt(r.minuteVolume));
            m.put("changePct", fmt(r.changePct));
            m.put("returnPct", fmt(r.returnPct));
            m.put("rangePct", fmt(r.rangePct));
            bus.publishSignal(new MarketIntelligenceSignal(
                    "POLYGON_MARKET_KNOWLEDGE",
                    MarketIntelligenceSignalType.MARKET_DATA,
                    r.ticker,
                    "Pre-catalyst elevation: unusual Polygon market behavior detected for " + r.ticker,
                    "Activity=" + fmt(r.activityScore()) + " preCatalystScore=" + fmt(score),
                    System.currentTimeMillis(),
                    0.70,
                    score,
                    m
            ));
            emitted++;
        }
        if (boolEnv("PRE_CATALYST_ELEVATION_LOG", true)) {
            System.out.println("PRE-CATALYST ELEVATION SCAN: candidates=" + records.size() + " emitted=" + emitted);
        }
    }

    private static double score(MarketKnowledgeDatabase.Record r) {
        double volume = Math.max(r.snapshotVolume, r.minuteVolume);
        double volumeScore = Math.min(1.0, Math.log10(Math.max(10.0, volume)) / 8.0);
        double moveScore = Math.min(1.0, (Math.abs(r.changePct) + Math.abs(r.returnPct)) / 12.0);
        double rangeScore = Math.min(1.0, Math.max(0.0, r.rangePct) / 8.0);
        return clamp(volumeScore * 0.35 + moveScore * 0.40 + rangeScore * 0.25);
    }

    private static int topK() { return Math.max(10, intEnv("PRE_CATALYST_ELEVATION_TOP_K", 75)); }
    private static long intervalMs() { return Math.max(2_000L, longEnv("PRE_CATALYST_ELEVATION_INTERVAL_MS", 7_500L)); }
    private static double minScore() { return Math.max(0.05, Math.min(0.95, doubleEnv("PRE_CATALYST_ELEVATION_MIN_SCORE", 0.42))); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
    private static double clamp(double v) { return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.0; }
    private static String fmt(double v) { return String.format(java.util.Locale.US, "%.5f", Double.isFinite(v) ? v : 0.0); }
    private static String safe(String s) { return s == null ? "" : s.replace('\n', ' ').replace('\r', ' '); }
    private static boolean boolEnv(String k, boolean f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()); }
    private static int intEnv(String k, int f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Integer.parseInt(v.trim()); } catch (Exception e) { return f; } }
    private static long longEnv(String k, long f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Long.parseLong(v.trim()); } catch (Exception e) { return f; } }
    private static double doubleEnv(String k, double f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Double.parseDouble(v.trim()); } catch (Exception e) { return f; } }
}
