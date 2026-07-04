package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent execution analytics. Tracks latency/fill/slippage estimates so
 * later candidate scoring can reduce size or confidence for strategies/symbols
 * that consistently execute poorly.
 */
public final class ExecutionAnalyticsService {
    private static final ExecutionAnalyticsService INSTANCE = new ExecutionAnalyticsService();

    private final Path journalPath = Paths.get(System.getenv().getOrDefault(
            "EXECUTION_ANALYTICS_JOURNAL", "logs/execution_analytics.csv"));
    private final ConcurrentHashMap<String, Stats> byStrategy = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Stats> byTicker = new ConcurrentHashMap<>();
    private final boolean enabled = envBoolean("EXECUTION_ANALYTICS_ENABLED", true);

    private ExecutionAnalyticsService() {
        if (enabled) {
            System.out.println("EXECUTION ANALYTICS SERVICE READY: journal=" + journalPath);
        }
    }

    public static ExecutionAnalyticsService getInstance() {
        return INSTANCE;
    }

    public void record(
            String ticker,
            String strategy,
            String side,
            int requestedQuantity,
            int finalQuantity,
            double referencePrice,
            double observedPrice,
            boolean filled,
            long latencyMs,
            String reason
    ) {
        if (!enabled) return;
        double slippagePercent = estimateSlippage(side, referencePrice, observedPrice);
        update(byStrategy.computeIfAbsent(normalize(strategy), Stats::new), filled, latencyMs, slippagePercent);
        update(byTicker.computeIfAbsent(normalize(ticker), Stats::new), filled, latencyMs, slippagePercent);
        journal(ticker, strategy, side, requestedQuantity, finalQuantity, referencePrice, observedPrice, filled, latencyMs, slippagePercent, reason);
    }

    public double executionScore(String ticker, String strategy) {
        if (!enabled) return 0.55;
        Stats s = byStrategy.get(normalize(strategy));
        Stats t = byTicker.get(normalize(ticker));
        double strategyScore = score(s);
        double tickerScore = score(t);
        if (s == null && t == null) return 0.55;
        if (s == null) return tickerScore;
        if (t == null) return strategyScore;
        return clamp(strategyScore * 0.60 + tickerScore * 0.40);
    }

    private void update(Stats stats, boolean filled, long latencyMs, double slippagePercent) {
        if (stats == null) return;
        stats.count++;
        if (filled) stats.filled++;
        stats.totalLatencyMs += Math.max(0L, latencyMs);
        stats.totalAbsSlippagePercent += Math.abs(slippagePercent);
    }

    private double score(Stats s) {
        if (s == null || s.count <= 0) return 0.55;
        double fillRate = s.filled * 1.0 / Math.max(1, s.count);
        double avgLatency = s.totalLatencyMs * 1.0 / Math.max(1, s.count);
        double avgSlip = s.totalAbsSlippagePercent / Math.max(1, s.count);
        double latencyPenalty = Math.min(0.20, avgLatency / 10_000.0);
        double slipPenalty = Math.min(0.25, avgSlip / 2.0);
        return clamp(0.35 + fillRate * 0.45 - latencyPenalty - slipPenalty);
    }

    private void journal(String ticker, String strategy, String side, int requestedQuantity, int finalQuantity,
                         double referencePrice, double observedPrice, boolean filled, long latencyMs,
                         double slippagePercent, String reason) {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            boolean newFile = !Files.exists(journalPath) || Files.size(journalPath) == 0;
            try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (newFile) {
                    writer.write("timestamp,ticker,strategy,side,requestedQty,finalQty,referencePrice,observedPrice,filled,latencyMs,slippagePercent,reason");
                    writer.newLine();
                }
                writer.write(String.join(",",
                        clean(Instant.now().toString()), clean(ticker), clean(strategy), clean(side),
                        String.valueOf(requestedQuantity), String.valueOf(finalQuantity), fmt(referencePrice), fmt(observedPrice),
                        String.valueOf(filled), String.valueOf(Math.max(0L, latencyMs)), fmt(slippagePercent), clean(reason)
                ));
                writer.newLine();
            }
        } catch (Exception e) {
            if (envBoolean("EXECUTION_ANALYTICS_VERBOSE_ERRORS", false)) {
                System.out.println("EXECUTION ANALYTICS JOURNAL WARNING: " + e.getMessage());
            }
        }
    }

    private static double estimateSlippage(String side, double referencePrice, double observedPrice) {
        if (referencePrice <= 0.0 || observedPrice <= 0.0) return 0.0;
        boolean shortSide = side != null && side.toUpperCase(Locale.ROOT).contains("SHORT");
        double raw = (observedPrice - referencePrice) / referencePrice * 100.0;
        return shortSide ? -raw : raw;
    }

    private static final class Stats {
        final String key;
        long count;
        long filled;
        long totalLatencyMs;
        double totalAbsSlippagePercent;
        Stats(String key) { this.key = key; }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) value = 0.0;
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()) || "yes".equalsIgnoreCase(value.trim());
    }
}
