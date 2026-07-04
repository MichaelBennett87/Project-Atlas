package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Finds historically similar market-state rows from the local market-state database. */
public final class MarketSimilaritySearchEngine {
    private static final MarketSimilaritySearchEngine INSTANCE = new MarketSimilaritySearchEngine();
    private final Path journal = Path.of(env("MARKET_SIMILARITY_JOURNAL", "logs/market_similarity_search.csv"));
    private final int maxRows = intEnv("MARKET_SIMILARITY_MAX_ROWS", 20_000);
    private final int topK = intEnv("MARKET_SIMILARITY_TOP_K", 8);
    private final long minIntervalMs = longEnv("MARKET_SIMILARITY_INTERVAL_MS", 120_000L);
    private volatile long lastRunAt = 0L;
    private volatile boolean started = false;

    private MarketSimilaritySearchEngine() {}
    public static MarketSimilaritySearchEngine getInstance() { return INSTANCE; }

    public synchronized void start() {
        if (started) return;
        started = true;
        System.out.println("MARKET SIMILARITY SEARCH ENGINE STARTED: topK=" + topK + " journal=" + journal);
        headerIfNeeded();
    }

    public List<SimilarState> findSimilar(WorldModelSnapshot s, LiveTechnicalFeatureStore.MarketTechnicalSummary t) {
        start();
        double[] current = MarketMemoryEmbeddingService.getInstance().vector(s, t);
        List<MarketStateDatabase.StateRow> rows = MarketStateDatabase.getInstance().loadRecent(maxRows);
        List<SimilarState> out = new ArrayList<>();
        for (MarketStateDatabase.StateRow row : rows) {
            double[] rv = new double[]{ row.trend, row.volatility, row.liquidity, row.smallCap, row.largeCap, row.catalyst, row.newsFlow, row.parabolic, row.dataConfidence, row.technical, row.technicalLiquidity, row.technicalParabolic, 0.5 };
            out.add(new SimilarState(row.timestamp, row.regime, distance(current, rv)));
        }
        out.sort(Comparator.comparingDouble(a -> a.distance));
        if (out.size() > topK) out = new ArrayList<>(out.subList(0, topK));
        maybeJournal(out);
        return out;
    }

    public void maybeSearchAndJournal(WorldModelSnapshot s, LiveTechnicalFeatureStore.MarketTechnicalSummary t) {
        long now = System.currentTimeMillis();
        if (now - lastRunAt < minIntervalMs) return;
        lastRunAt = now;
        findSimilar(s, t);
    }

    private void maybeJournal(List<SimilarState> states) {
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                String now = Instant.now().toString();
                for (SimilarState s : states) {
                    w.write('"' + now + "\"," + q(s.timestamp) + ',' + q(s.regime) + ',' + String.format(Locale.US, "%.6f", s.distance));
                    w.newLine();
                }
            }
        } catch (Exception e) { System.out.println("MARKET SIMILARITY JOURNAL FAILED: " + e.getMessage()); }
    }

    private void headerIfNeeded() {
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(journal) || Files.size(journal) == 0L) {
                try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write("queryTimestamp,similarTimestamp,similarRegime,distance"); w.newLine();
                }
            }
        } catch (Exception e) { System.out.println("MARKET SIMILARITY HEADER FAILED: " + e.getMessage()); }
    }

    private static double distance(double[] a, double[] b) { int n = Math.min(a.length, b.length); double s = 0; for (int i=0;i<n;i++){ double d=(safe(a[i])-safe(b[i])); s+=d*d; } return Math.sqrt(s); }
    private static double safe(double v) { return Double.isFinite(v) ? v : 0.0; }
    private static String q(String s) { return '"' + (s == null ? "" : s.replace("\"", "\"\"")) + '"'; }
    private static String env(String key, String fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : v.trim(); }
    private static int intEnv(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch(Exception e) { return fallback; } }
    private static long longEnv(String key, long fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); } catch(Exception e) { return fallback; } }

    public static final class SimilarState { public final String timestamp, regime; public final double distance; SimilarState(String timestamp, String regime, double distance) { this.timestamp = timestamp; this.regime = regime; this.distance = distance; } }
}
