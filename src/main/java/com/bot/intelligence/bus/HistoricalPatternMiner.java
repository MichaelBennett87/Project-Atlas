package com.bot.intelligence.bus;

import com.bot.intelligence.MarketKnowledgeDatabase;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;

/** Lightweight online/nightly pattern miner scaffold; stores recurring intraday pattern statistics locally. */
public final class HistoricalPatternMiner {
    private static final HistoricalPatternMiner INSTANCE = new HistoricalPatternMiner();
    private final Path journal = Path.of(env("HISTORICAL_PATTERN_MINER_JOURNAL", "logs/historical_pattern_miner.csv"));
    private HistoricalPatternMiner() {}
    public static HistoricalPatternMiner getInstance() { return INSTANCE; }

    public void observe(MarketKnowledgeDatabase.Record r) {
        if (r == null || r.ticker == null || r.ticker.isBlank()) return;
        String pattern = classify(r);
        if (pattern.isBlank()) return;
        ContinuousKnowledgeCache.getInstance().put(r.ticker, "PATTERN", pattern, confidence(r));
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            boolean header = !Files.exists(journal) || Files.size(journal) == 0L;
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (header) w.write("timestamp,ticker,pattern,confidence,returnPct,rangePct,minuteVolume,microstructure\n");
                w.write(q(Instant.now().toString()) + "," + q(r.ticker) + "," + q(pattern) + "," + d(confidence(r)) + "," + d(r.returnPct) + "," + d(r.rangePct) + "," + d(r.minuteVolume) + "," + d(r.microstructureScore));
                w.newLine();
            }
        } catch (Exception ignored) {}
    }

    private String classify(MarketKnowledgeDatabase.Record r) {
        if (r.returnPct >= 8.0 && r.rangePct >= 10.0) return "PARABOLIC_RUN_CANDIDATE";
        if (r.returnPct <= -8.0 && r.rangePct >= 10.0) return "PARABOLIC_BREAKDOWN_CANDIDATE";
        if (r.microstructureScore >= 0.75 && r.continuationProbability > r.exhaustionProbability) return "CONTINUATION_MICROSTRUCTURE";
        if (r.microstructureScore >= 0.75 && r.exhaustionProbability >= r.continuationProbability) return "EXHAUSTION_MICROSTRUCTURE";
        if (r.newsCount > 0 && Math.abs(r.returnPct) >= 3.0) return "NEWS_REACTION";
        return "";
    }
    private double confidence(MarketKnowledgeDatabase.Record r) { return clamp(0.35 + Math.abs(r.returnPct) / 20.0 + r.rangePct / 25.0 + r.microstructureScore * 0.30); }
    private static double clamp(double v) { return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.0; }
    private static String q(String s) { return '"' + (s == null ? "" : s.replace("\"", "\"\"")) + '"'; }
    private static String d(double v) { return String.format(Locale.US, "%.6f", Double.isFinite(v) ? v : 0.0); }
    private static String env(String k,String f){String v=System.getenv(k);return v==null||v.isBlank()?f:v.trim();}
}
