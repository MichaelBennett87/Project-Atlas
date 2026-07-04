package com.bot.intelligence;

import com.bot.intelligence.bus.MarketIntelligenceSignal;
import com.bot.intelligence.bus.MarketIntelligenceSignalType;
import com.bot.model.Bar;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared event-driven feature bus.
 *
 * Market data providers, scanners, replay jobs, and agents should publish bars/features here instead of
 * each subsystem maintaining a private update path. The bus writes to the live technical feature store,
 * publishes technical evidence into the Evidence Fusion Engine, and exposes health counters so starvation
 * is visible in logs.
 */
public final class MarketFeatureBus {
    private static final MarketFeatureBus INSTANCE = new MarketFeatureBus();

    private final LiveTechnicalFeatureStore liveFeatureStore = LiveTechnicalFeatureStore.getInstance();
    private final EvidenceFusionEngine evidenceFusionEngine = EvidenceFusionEngine.getInstance();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean healthThreadStarted = new AtomicBoolean(false);

    private final AtomicLong barsReceived = new AtomicLong();
    private final AtomicLong barsRejected = new AtomicLong();
    private final AtomicLong featuresCalculated = new AtomicLong();
    private final AtomicLong featuresPublished = new AtomicLong();
    private final AtomicLong fusionSignalsPublished = new AtomicLong();
    private final AtomicLong worldModelUpdates = new AtomicLong();

    private volatile long lastBarAtMs = 0L;
    private volatile long lastFeatureAtMs = 0L;
    private volatile long lastFusionAtMs = 0L;
    private volatile long lastWarningAtMs = 0L;

    private final long healthIntervalMs = longEnv("MARKET_FEATURE_BUS_HEALTH_INTERVAL_MS", 60_000L);
    private final long starvationWarningMs = longEnv("MARKET_FEATURE_BUS_STARVATION_WARNING_MS", 90_000L);
    private final boolean verboseHealth = boolEnv("MARKET_FEATURE_BUS_VERBOSE_HEALTH", true);

    private MarketFeatureBus() {}

    public static MarketFeatureBus getInstance() {
        return INSTANCE;
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            liveFeatureStore.start();
            evidenceFusionEngine.start();
            System.out.println("MARKET FEATURE BUS STARTED: healthIntervalMs=" + healthIntervalMs +
                    " starvationWarningMs=" + starvationWarningMs +
                    " routes=bar->technicalFeatureStore->evidenceFusion");
        }
        startHealthThread();
    }

    public TechnicalFeatureVector publishBar(String source, String ticker, Bar bar) {
        start();
        String normalized = normalize(ticker);
        if (normalized.isBlank() || bar == null || bar.close <= 0.0) {
            barsRejected.incrementAndGet();
            return null;
        }

        Bar normalizedBar = normalizeBarTimestamp(bar);
        barsReceived.incrementAndGet();
        lastBarAtMs = System.currentTimeMillis();

        TechnicalFeatureVector vector = liveFeatureStore.observeBar(normalized, normalizedBar);
        if (vector == null) {
            barsRejected.incrementAndGet();
            return null;
        }

        featuresCalculated.incrementAndGet();
        featuresPublished.incrementAndGet();
        lastFeatureAtMs = System.currentTimeMillis();

        publishTechnicalEvidence(source, normalized, normalizedBar, vector);
        publishKnowledgeState(source, normalized, normalizedBar, vector);
        return vector;
    }

    private void publishKnowledgeState(String source, String ticker, Bar bar, TechnicalFeatureVector vector) {
        try {
            double open = bar.open > 0.0 ? bar.open : vector.lastClose;
            double close = bar.close > 0.0 ? bar.close : vector.lastClose;
            double high = bar.high > 0.0 ? bar.high : close;
            double low = bar.low > 0.0 ? bar.low : close;
            double returnPct = open > 0.0 ? ((close - open) / open) * 100.0 : 0.0;
            double rangePct = close > 0.0 ? ((high - low) / close) * 100.0 : 0.0;
            double volume = Math.max(0L, bar.volume);

            MarketKnowledgeDatabase.getInstance().recordBars(
                    ticker,
                    Math.max(1, vector.bars),
                    close,
                    returnPct,
                    rangePct,
                    volume,
                    source == null || source.isBlank() ? "MARKET_FEATURE_BUS" : source
            );
        } catch (Exception e) {
            if (boolEnv("MARKET_FEATURE_BUS_KNOWLEDGE_VERBOSE_ERRORS", false)) {
                System.out.println("MARKET FEATURE BUS KNOWLEDGE PUBLISH FAILED: ticker=" + ticker + " error=" + e.getMessage());
            }
        }
    }

    public void recordWorldModelUpdate() {
        worldModelUpdates.incrementAndGet();
    }

    public HealthSnapshot snapshot() {
        return new HealthSnapshot(
                barsReceived.get(),
                barsRejected.get(),
                featuresCalculated.get(),
                featuresPublished.get(),
                fusionSignalsPublished.get(),
                worldModelUpdates.get(),
                lastBarAtMs,
                lastFeatureAtMs,
                lastFusionAtMs
        );
    }

    public String healthSummary() {
        return snapshot().summary();
    }

    private void publishTechnicalEvidence(String source, String ticker, Bar bar, TechnicalFeatureVector vector) {
        try {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("source", source == null ? "UNKNOWN" : source);
            metadata.put("lastClose", String.valueOf(vector.lastClose));
            metadata.put("relativeVolume", String.valueOf(vector.relativeVolume));
            metadata.put("vwapDistancePct", String.valueOf(vector.vwapDistancePct));
            metadata.put("rsi", String.valueOf(vector.rsi14));
            metadata.put("emaTrendScore", String.valueOf(vector.emaTrendScore));
            metadata.put("parabolicScore", String.valueOf(vector.parabolicScore));
            metadata.put("technicalScore", String.valueOf(vector.technicalScore));
            metadata.put("barTimestamp", String.valueOf(normalizeEpochMillis(bar.timestamp)));

            double priority = clamp(Math.max(vector.technicalScore, Math.max(vector.parabolicScore, vector.breakoutScore)));
            double confidence = clamp(0.55 + Math.min(0.40, Math.max(0.0, vector.bars) / 50.0));

            MarketIntelligenceSignal signal = new MarketIntelligenceSignal(
                    "LIVE_TECHNICAL_FEATURE_STORE",
                    MarketIntelligenceSignalType.TECHNICAL_INDICATOR,
                    ticker,
                    "Live technical feature update for " + ticker,
                    "technical=" + fmt(vector.technicalScore) +
                            " parabolic=" + fmt(vector.parabolicScore) +
                            " rvol=" + fmt(vector.relativeVolume) +
                            " vwapDistancePct=" + fmt(vector.vwapDistancePct),
                    normalizeEpochMillis(bar.timestamp),
                    confidence,
                    priority,
                    metadata
            );
            evidenceFusionEngine.observeSignal(signal, false);
            fusionSignalsPublished.incrementAndGet();
            lastFusionAtMs = System.currentTimeMillis();
        } catch (Exception e) {
            if (boolEnv("MARKET_FEATURE_BUS_VERBOSE_ERRORS", false)) {
                System.out.println("MARKET FEATURE BUS EVIDENCE PUBLISH FAILED: ticker=" + ticker + " error=" + e.getMessage());
            }
        }
    }

    private void startHealthThread() {
        if (!healthThreadStarted.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Math.max(10_000L, healthIntervalMs));
                    emitHealthIfNeeded();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                }
            }
        }, "market-feature-bus-health");
        thread.setDaemon(true);
        thread.start();
    }

    private void emitHealthIfNeeded() {
        HealthSnapshot s = snapshot();
        long now = System.currentTimeMillis();
        boolean starvedAfterBars = s.barsReceived > 0 && s.featuresCalculated <= 0 && now - s.lastBarAtMs >= starvationWarningMs;
        boolean noRecentFeatures = s.barsReceived > 0 && s.featuresCalculated > 0 && now - s.lastFeatureAtMs >= starvationWarningMs * 2L;
        if (starvedAfterBars || noRecentFeatures) {
            if (now - lastWarningAtMs >= Math.max(30_000L, starvationWarningMs)) {
                lastWarningAtMs = now;
                System.out.println("TECHNICAL FEATURE PIPELINE STARVED: " + s.summary() +
                        " reason=" + (starvedAfterBars ? "bars_received_but_no_features" : "no_recent_feature_updates"));
            }
            return;
        }
        if (verboseHealth) {
            System.out.println("MARKET FEATURE BUS HEALTH: " + s.summary());
        }
    }

    private static Bar normalizeBarTimestamp(Bar original) {
        long normalized = normalizeEpochMillis(original.timestamp);
        if (normalized == original.timestamp || normalized <= 0L) {
            return original;
        }
        Bar copy = new Bar();
        copy.ticker = original.ticker;
        copy.timestamp = normalized;
        copy.open = original.open;
        copy.high = original.high;
        copy.low = original.low;
        copy.close = original.close;
        copy.volume = original.volume;
        return copy;
    }

    public static long normalizeEpochMillis(long rawTimestamp) {
        if (rawTimestamp <= 0L) return System.currentTimeMillis();
        return rawTimestamp < 10_000_000_000L ? rawTimestamp * 1000L : rawTimestamp;
    }

    private static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static double clamp(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.5f", v);
    }

    private static boolean boolEnv(String key, boolean fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return fallback;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()) || "yes".equalsIgnoreCase(v.trim());
    }

    private static long longEnv(String key, long fallback) {
        try {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public static final class HealthSnapshot {
        public final long barsReceived;
        public final long barsRejected;
        public final long featuresCalculated;
        public final long featuresPublished;
        public final long fusionSignalsPublished;
        public final long worldModelUpdates;
        public final long lastBarAtMs;
        public final long lastFeatureAtMs;
        public final long lastFusionAtMs;

        private HealthSnapshot(long barsReceived, long barsRejected, long featuresCalculated, long featuresPublished,
                               long fusionSignalsPublished, long worldModelUpdates,
                               long lastBarAtMs, long lastFeatureAtMs, long lastFusionAtMs) {
            this.barsReceived = barsReceived;
            this.barsRejected = barsRejected;
            this.featuresCalculated = featuresCalculated;
            this.featuresPublished = featuresPublished;
            this.fusionSignalsPublished = fusionSignalsPublished;
            this.worldModelUpdates = worldModelUpdates;
            this.lastBarAtMs = lastBarAtMs;
            this.lastFeatureAtMs = lastFeatureAtMs;
            this.lastFusionAtMs = lastFusionAtMs;
        }

        public String summary() {
            long now = System.currentTimeMillis();
            return "barsReceived=" + barsReceived +
                    " barsRejected=" + barsRejected +
                    " featuresCalculated=" + featuresCalculated +
                    " featuresPublished=" + featuresPublished +
                    " fusionSignals=" + fusionSignalsPublished +
                    " worldModelUpdates=" + worldModelUpdates +
                    " lastBarAgeMs=" + age(now, lastBarAtMs) +
                    " lastFeatureAgeMs=" + age(now, lastFeatureAtMs) +
                    " lastFusionAgeMs=" + age(now, lastFusionAtMs);
        }

        private static long age(long now, long then) {
            return then <= 0L ? -1L : Math.max(0L, now - then);
        }
    }
}
