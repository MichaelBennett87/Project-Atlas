package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;

/** Lightweight vector-style market memory. No external vector DB required yet. */
public final class MarketMemoryEmbeddingService {
    private static final MarketMemoryEmbeddingService INSTANCE = new MarketMemoryEmbeddingService();
    private final Path journal = Path.of(env("MARKET_MEMORY_EMBEDDING_JOURNAL", "logs/market_memory_embeddings.csv"));
    private final long minIntervalMs = longEnv("MARKET_MEMORY_EMBEDDING_INTERVAL_MS", 60_000L);
    private volatile long lastWriteAt = 0L;
    private volatile boolean started = false;

    private MarketMemoryEmbeddingService() {}
    public static MarketMemoryEmbeddingService getInstance() { return INSTANCE; }

    public synchronized void start() {
        if (started) return;
        started = true;
        System.out.println("MARKET MEMORY EMBEDDING SERVICE STARTED: journal=" + journal);
        headerIfNeeded();
    }

    public double[] vector(WorldModelSnapshot s, LiveTechnicalFeatureStore.MarketTechnicalSummary t) {
        if (s == null) s = WorldModelSnapshot.unknown();
        return new double[] {
                s.getTrendScore(), s.getVolatilityScore(), s.getLiquidityScore(), s.getSmallCapLeadershipScore(),
                s.getLargeCapLeadershipScore(), s.getCatalystHeatScore(), s.getNewsFlowScore(), s.getParabolicHeatScore(),
                s.getDataConfidenceScore(), t == null ? 0.0 : t.technicalScore, t == null ? 0.0 : t.liquidityScore,
                t == null ? 0.0 : t.parabolicScore, t == null ? 0.5 : t.trendScore
        };
    }

    public void record(WorldModelSnapshot s, LiveTechnicalFeatureStore.MarketTechnicalSummary t) {
        start();
        long now = System.currentTimeMillis();
        if (now - lastWriteAt < minIntervalMs) return;
        lastWriteAt = now;
        double[] v = vector(s, t);
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                StringBuilder sb = new StringBuilder();
                sb.append('"').append(Instant.ofEpochMilli(now)).append('"');
                for (double x : v) sb.append(',').append(String.format(Locale.US, "%.6f", Double.isFinite(x) ? x : 0.0));
                sb.append(',').append('"').append(s == null ? "" : s.getSummary().replace("\"", "\"\"")).append('"');
                w.write(sb.toString()); w.newLine();
            }
        } catch (Exception e) { System.out.println("MARKET MEMORY EMBEDDING WRITE FAILED: " + e.getMessage()); }
    }

    private void headerIfNeeded() {
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(journal) || Files.size(journal) == 0L) {
                try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write("timestamp,trend,volatility,liquidity,smallCap,largeCap,catalyst,newsFlow,parabolic,dataConfidence,technical,technicalLiquidity,technicalParabolic,technicalTrend,summary");
                    w.newLine();
                }
            }
        } catch (Exception e) { System.out.println("MARKET MEMORY EMBEDDING HEADER FAILED: " + e.getMessage()); }
    }

    private static String env(String key, String fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : v.trim(); }
    private static long longEnv(String key, long fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); } catch(Exception e) { return fallback; } }
}
