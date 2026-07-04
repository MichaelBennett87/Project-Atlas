package com.bot.intelligence;

import com.bot.model.Bar;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live feature store for market-state memory.
 *
 * The earlier technical engine was mostly useful at night because it read CSV/history.
 * This service does the same kind of normalization from live bars so the World Model,
 * evidence fusion, and agents are no longer technically blind during the session.
 */
public final class LiveTechnicalFeatureStore {
    private static final LiveTechnicalFeatureStore INSTANCE = new LiveTechnicalFeatureStore();

    private final ConcurrentHashMap<String, Deque<Bar>> barsByTicker = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TechnicalFeatureVector> latestByTicker = new ConcurrentHashMap<>();
    private final Path journal = Path.of(env("LIVE_FEATURE_STORE_JOURNAL", "logs/live_feature_store.csv"));
    private final TechnicalIntelligenceEngine technical = new TechnicalIntelligenceEngine();
    private final int maxBarsPerTicker = intEnv("LIVE_FEATURE_STORE_MAX_BARS_PER_TICKER", 240);
    private final long journalIntervalMs = longEnv("LIVE_FEATURE_STORE_JOURNAL_INTERVAL_MS", 60_000L);
    private final boolean journalingEnabled = !"false".equalsIgnoreCase(env("LIVE_FEATURE_STORE_JOURNALING_ENABLED", "true"));
    private volatile long lastJournalAt = 0L;
    private volatile boolean started = false;

    private LiveTechnicalFeatureStore() {}

    public static LiveTechnicalFeatureStore getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        System.out.println("LIVE TECHNICAL FEATURE STORE STARTED: maxBarsPerTicker=" + maxBarsPerTicker + " journal=" + journal);
        journalHeaderIfNeeded();
    }

    public TechnicalFeatureVector observeBar(String ticker, Bar bar) {
        String normalized = normalize(ticker);
        if (normalized.isBlank() || bar == null || bar.close <= 0.0) return null;
        start();
        Deque<Bar> deque = barsByTicker.computeIfAbsent(normalized, ignored -> new ArrayDeque<>());
        List<Bar> copy;
        synchronized (deque) {
            deque.addLast(bar);
            while (deque.size() > maxBarsPerTicker) deque.removeFirst();
            copy = new ArrayList<>(deque);
        }
        // Do not wait for multiple bars before publishing a vector.
        // The scanner often touches hundreds of symbols before revisiting the same ticker,
        // so waiting for 2+ bars left the World Model technically blind at startup.
        // A one-bar vector is still useful for liquidity/range/price-level context and
        // is replaced automatically as more bars arrive.
        List<HistoricalMarketDataRepository.HistoricalBar> converted = new ArrayList<>();
        for (Bar b : copy) {
            converted.add(new HistoricalMarketDataRepository.HistoricalBar(
                    normalized,
                    normalizedTimestamp(b.timestamp),
                    b.open,
                    b.high,
                    b.low,
                    b.close,
                    b.volume
            ));
        }
        TechnicalFeatureVector vector = technical.compute(normalized, "live", converted);
        latestByTicker.put(normalized, vector);
        maybeJournal(vector);
        return vector;
    }

    public TechnicalFeatureVector latest(String ticker) {
        return latestByTicker.get(normalize(ticker));
    }

    public MarketTechnicalSummary summarize(long activeLookbackMs) {
        long now = System.currentTimeMillis();
        double technical = 0.0;
        double parabolic = 0.0;
        double liquidity = 0.0;
        double trend = 0.0;
        int count = 0;
        int active = 0;
        for (Map.Entry<String, TechnicalFeatureVector> entry : latestByTicker.entrySet()) {
            TechnicalFeatureVector v = entry.getValue();
            if (v == null) continue;
            count++;
            // We do not store vector time directly, so use bar recency from the ticker buffer.
            Bar latestBar = latestBar(entry.getKey());
            long barTime = latestBar == null ? 0L : normalizeEpochMillis(latestBar.timestamp);
            boolean isActive = latestBar != null && (barTime <= 0 || Math.abs(now - barTime) <= activeLookbackMs);
            if (!isActive && activeLookbackMs > 0) continue;
            active++;
            technical = Math.max(technical, v.technicalScore);
            parabolic = Math.max(parabolic, v.parabolicScore);
            liquidity = Math.max(liquidity, clamp(Math.log10(Math.max(1.0, v.relativeVolume * Math.max(1.0, v.lastClose))) / 5.0));
            trend += clamp(0.5 + v.momentumSlope * 0.5);
        }
        double trendScore = active == 0 ? 0.5 : clamp(trend / active);
        return new MarketTechnicalSummary(count, active, technical, parabolic, liquidity, trendScore, topTechnical(8));
    }

    private Bar latestBar(String ticker) {
        Deque<Bar> deque = barsByTicker.get(normalize(ticker));
        if (deque == null) return null;
        synchronized (deque) {
            return deque.peekLast();
        }
    }

    private Map<String, Double> topTechnical(int max) {
        List<Map.Entry<String, TechnicalFeatureVector>> entries = new ArrayList<>(latestByTicker.entrySet());
        entries.sort(Comparator.comparingDouble((Map.Entry<String, TechnicalFeatureVector> e) -> e.getValue() == null ? 0.0 : e.getValue().technicalScore).reversed());
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, TechnicalFeatureVector> e : entries) {
            if (out.size() >= max) break;
            if (e.getValue() != null) out.put(e.getKey(), e.getValue().technicalScore);
        }
        return out;
    }

    private void maybeJournal(TechnicalFeatureVector vector) {
        if (!journalingEnabled || vector == null) return;
        long now = System.currentTimeMillis();
        if (now - lastJournalAt < journalIntervalMs) return;
        lastJournalAt = now;
        try {
            Path parent = journal.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write('"' + Instant.ofEpochMilli(now).toString() + "\"," + vector.toCsv());
                writer.newLine();
            }
        } catch (Exception e) {
            System.out.println("LIVE FEATURE STORE JOURNAL FAILED: " + e.getMessage());
        }
    }

    private void journalHeaderIfNeeded() {
        if (!journalingEnabled) return;
        try {
            Path parent = journal.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(journal) || Files.size(journal) == 0L) {
                try (BufferedWriter writer = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    writer.write("generatedAt," + new TechnicalFeatureVector("TICKER", "live", 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0).csvHeader());
                    writer.newLine();
                }
            }
        } catch (Exception e) {
            System.out.println("LIVE FEATURE STORE HEADER FAILED: " + e.getMessage());
        }
    }

    private static String normalizedTimestamp(long rawTimestamp) {
        long ms = normalizeEpochMillis(rawTimestamp);
        return ms > 0 ? Instant.ofEpochMilli(ms).toString() : Instant.now().toString();
    }

    private static long normalizeEpochMillis(long rawTimestamp) {
        if (rawTimestamp <= 0L) return 0L;
        // Alpaca/other providers may hand us epoch seconds in some paths and epoch millis in others.
        // Treat sub-10-billion values as epoch seconds so recency checks do not incorrectly mark
        // valid live bars as ancient.
        return rawTimestamp < 10_000_000_000L ? rawTimestamp * 1000L : rawTimestamp;
    }

    private static String normalize(String ticker) { return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT); }
    private static double clamp(double v) { if (!Double.isFinite(v)) return 0.0; return Math.max(0.0, Math.min(1.0, v)); }
    private static String env(String key, String fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : v.trim(); }
    private static int intEnv(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch (Exception e) { return fallback; } }
    private static long longEnv(String key, long fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); } catch (Exception e) { return fallback; } }

    public static final class MarketTechnicalSummary {
        public final int totalTickers;
        public final int activeTickers;
        public final double technicalScore;
        public final double parabolicScore;
        public final double liquidityScore;
        public final double trendScore;
        public final Map<String, Double> topTechnicalTickers;

        MarketTechnicalSummary(int totalTickers, int activeTickers, double technicalScore, double parabolicScore, double liquidityScore, double trendScore, Map<String, Double> topTechnicalTickers) {
            this.totalTickers = totalTickers;
            this.activeTickers = activeTickers;
            this.technicalScore = clamp(technicalScore);
            this.parabolicScore = clamp(parabolicScore);
            this.liquidityScore = clamp(liquidityScore);
            this.trendScore = clamp(trendScore);
            this.topTechnicalTickers = topTechnicalTickers == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(topTechnicalTickers));
        }

        public String summary() {
            return "liveTechnicalTickers=" + totalTickers + " activeTechnicalTickers=" + activeTickers +
                    " technical=" + fmt(technicalScore) + " parabolic=" + fmt(parabolicScore) +
                    " liquidity=" + fmt(liquidityScore) + " trend=" + fmt(trendScore) +
                    " topTechnical=" + topTechnicalTickers;
        }
        private static String fmt(double v) { return String.format(Locale.ROOT, "%.5f", v); }
    }
}
