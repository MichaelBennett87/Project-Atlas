package com.bot.intelligence;

import com.bot.intelligence.bus.MarketIntelligenceSignal;
import com.bot.intelligence.bus.MarketIntelligenceSignalType;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider-neutral evidence fusion layer.
 *
 * Every real-time or historical provider should emit MarketIntelligenceSignal objects into the
 * MarketIntelligenceBus. This engine then fuses corroborating provider evidence into one ticker-level
 * object so the WorldModel, evidence graph, scoring committee, and replay tools do not have to reason
 * over raw vendor payloads independently.
 */
public final class EvidenceFusionEngine {
    private static final EvidenceFusionEngine INSTANCE = new EvidenceFusionEngine();

    private final ConcurrentHashMap<String, FusedTickerEvidence> byTicker = new ConcurrentHashMap<>();
    private final Path journalPath = Path.of(System.getenv().getOrDefault("EVIDENCE_FUSION_JOURNAL", "logs/evidence_fusion.csv"));
    private final long maxAgeMs = envLong("EVIDENCE_FUSION_MAX_AGE_MS", 30L * 60L * 1000L);
    private final boolean journalingEnabled = envBoolean("EVIDENCE_FUSION_JOURNALING_ENABLED", true);
    private volatile boolean started = false;

    private EvidenceFusionEngine() {}

    public static EvidenceFusionEngine getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        if (journalingEnabled) headerIfNeeded();
        System.out.println("EVIDENCE FUSION ENGINE STARTED: maxAgeMs=" + maxAgeMs + " journal=" + journalPath);
    }

    public void observeSignal(MarketIntelligenceSignal signal, boolean duplicate) {
        if (signal == null || duplicate) return;
        start();
        String ticker = normalize(signal.getTicker());
        if (ticker.isBlank()) ticker = "MARKET";
        FusedTickerEvidence evidence = byTicker.computeIfAbsent(ticker, FusedTickerEvidence::new);
        evidence.observe(signal);
        journal(evidence, signal);
    }

    public FusedTickerEvidence evidenceFor(String ticker) {
        String normalized = normalize(ticker);
        if (normalized.isBlank()) return FusedTickerEvidence.empty("UNKNOWN");
        FusedTickerEvidence evidence = byTicker.get(normalized);
        return evidence == null ? FusedTickerEvidence.empty(normalized) : evidence.prunedCopy(System.currentTimeMillis(), maxAgeMs);
    }

    public double fusedScore(String ticker) {
        return evidenceFor(ticker).fusedScore();
    }

    public FusionMarketSnapshot marketSnapshot(long lookbackMs) {
        long now = System.currentTimeMillis();
        long window = Math.max(1_000L, lookbackMs <= 0 ? maxAgeMs : lookbackMs);
        Set<String> providers = new LinkedHashSet<>();
        double catalyst = 0.0;
        double marketData = 0.0;
        double orderFlow = 0.0;
        double technical = 0.0;
        double parabolic = 0.0;
        int activeTickers = 0;
        for (FusedTickerEvidence raw : byTicker.values()) {
            FusedTickerEvidence e = raw.prunedCopy(now, window);
            if (e.signalCount() <= 0) continue;
            activeTickers++;
            providers.addAll(e.providers());
            catalyst = Math.max(catalyst, e.typeScore(MarketIntelligenceSignalType.NEWS));
            catalyst = Math.max(catalyst, e.typeScore(MarketIntelligenceSignalType.PRESS_RELEASE));
            catalyst = Math.max(catalyst, e.typeScore(MarketIntelligenceSignalType.CATALYST_CALENDAR));
            marketData = Math.max(marketData, e.typeScore(MarketIntelligenceSignalType.MARKET_DATA));
            orderFlow = Math.max(orderFlow, e.typeScore(MarketIntelligenceSignalType.ORDER_FLOW));
            technical = Math.max(technical, e.typeScore(MarketIntelligenceSignalType.TECHNICAL_INDICATOR));
            parabolic = Math.max(parabolic, e.parabolicProxyScore());
        }
        double providerDiversity = clamp(providers.size() / 5.0);
        double dataConfidence = clamp(providerDiversity * 0.70 + Math.min(1.0, activeTickers / 50.0) * 0.30);
        double liquidity = clamp(marketData * 0.60 + orderFlow * 0.40);
        return new FusionMarketSnapshot(providers, activeTickers, catalyst, liquidity, technical, parabolic, dataConfidence);
    }

    private void journal(FusedTickerEvidence evidence, MarketIntelligenceSignal signal) {
        if (!journalingEnabled) return;
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(String.join(",",
                        csv(Instant.now().toString()),
                        csv(evidence.ticker()),
                        csv(signal.getProvider()),
                        csv(signal.getType().name()),
                        fmt(signal.getConfidence()),
                        fmt(signal.getPriority()),
                        fmt(evidence.fusedScore()),
                        String.valueOf(evidence.providers().size()),
                        String.valueOf(evidence.signalCount()),
                        csv(signal.getHeadline())
                ));
                writer.newLine();
            }
        } catch (Exception e) {
            if (envBoolean("EVIDENCE_FUSION_VERBOSE_ERRORS", false)) {
                System.out.println("EVIDENCE FUSION JOURNAL ERROR: " + e.getMessage());
            }
        }
    }

    private void headerIfNeeded() {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(journalPath) || Files.size(journalPath) == 0L) {
                try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    writer.write("timestamp,ticker,provider,type,confidence,priority,fusedScore,providerCount,signalCount,headline");
                    writer.newLine();
                }
            }
        } catch (Exception ignored) {}
    }

    public static final class FusionMarketSnapshot {
        private final Set<String> activeProviders;
        private final int activeTickers;
        private final double catalystHeat;
        private final double liquidityScore;
        private final double technicalScore;
        private final double parabolicHeat;
        private final double dataConfidence;

        private FusionMarketSnapshot(Set<String> providers, int activeTickers, double catalystHeat, double liquidityScore,
                                     double technicalScore, double parabolicHeat, double dataConfidence) {
            this.activeProviders = Collections.unmodifiableSet(new LinkedHashSet<>(providers));
            this.activeTickers = activeTickers;
            this.catalystHeat = clamp(catalystHeat);
            this.liquidityScore = clamp(liquidityScore);
            this.technicalScore = clamp(technicalScore);
            this.parabolicHeat = clamp(parabolicHeat);
            this.dataConfidence = clamp(dataConfidence);
        }

        public Set<String> activeProviders() { return activeProviders; }
        public int activeTickers() { return activeTickers; }
        public double catalystHeat() { return catalystHeat; }
        public double liquidityScore() { return liquidityScore; }
        public double technicalScore() { return technicalScore; }
        public double parabolicHeat() { return parabolicHeat; }
        public double dataConfidence() { return dataConfidence; }
        public String summary() {
            return "fusionProviders=" + activeProviders.size() + " activeTickers=" + activeTickers +
                    " catalyst=" + fmt(catalystHeat) + " liquidity=" + fmt(liquidityScore) +
                    " technical=" + fmt(technicalScore) + " parabolic=" + fmt(parabolicHeat) +
                    " confidence=" + fmt(dataConfidence);
        }
    }

    public static final class FusedTickerEvidence {
        private final String ticker;
        private final List<SignalObservation> observations = new ArrayList<>();

        private FusedTickerEvidence(String ticker) { this.ticker = normalize(ticker); }
        static FusedTickerEvidence empty(String ticker) { return new FusedTickerEvidence(ticker); }

        synchronized void observe(MarketIntelligenceSignal signal) {
            observations.add(new SignalObservation(signal.getProvider(), signal.getType(), signal.getReceivedAtMs(), signal.getConfidence(), signal.getPriority(), signal.getHeadline()));
            long cutoff = System.currentTimeMillis() - envLong("EVIDENCE_FUSION_OBSERVATION_RETENTION_MS", 2L * 60L * 60L * 1000L);
            observations.removeIf(o -> o.receivedAtMs < cutoff);
        }

        synchronized FusedTickerEvidence prunedCopy(long now, long maxAgeMs) {
            FusedTickerEvidence copy = new FusedTickerEvidence(ticker);
            long cutoff = now - Math.max(1_000L, maxAgeMs);
            for (SignalObservation o : observations) {
                if (o.receivedAtMs >= cutoff) copy.observations.add(o);
            }
            return copy;
        }

        public synchronized String ticker() { return ticker; }
        public synchronized int signalCount() { return observations.size(); }
        public synchronized Set<String> providers() {
            Set<String> p = new LinkedHashSet<>();
            for (SignalObservation o : observations) p.add(o.provider);
            return p;
        }

        public synchronized double typeScore(MarketIntelligenceSignalType type) {
            double best = 0.0;
            for (SignalObservation o : observations) {
                if (o.type == type) best = Math.max(best, o.score());
            }
            return clamp(best);
        }

        public synchronized double parabolicProxyScore() {
            double market = typeScore(MarketIntelligenceSignalType.MARKET_DATA);
            double technical = typeScore(MarketIntelligenceSignalType.TECHNICAL_INDICATOR);
            double flow = typeScore(MarketIntelligenceSignalType.ORDER_FLOW);
            return clamp(Math.max(market, technical * 0.80 + flow * 0.20));
        }

        public synchronized double fusedScore() {
            if (observations.isEmpty()) return 0.45;
            Map<String, Double> providerBest = new LinkedHashMap<>();
            double best = 0.0;
            double sum = 0.0;
            for (SignalObservation o : observations) {
                double score = o.score();
                best = Math.max(best, score);
                sum += score;
                providerBest.merge(o.provider, score, Math::max);
            }
            double average = sum / observations.size();
            double providerBoost = Math.min(0.18, Math.max(0, providerBest.size() - 1) * 0.06);
            return clamp(best * 0.55 + average * 0.35 + providerBoost);
        }
    }

    private static final class SignalObservation {
        final String provider;
        final MarketIntelligenceSignalType type;
        final long receivedAtMs;
        final double confidence;
        final double priority;
        final String headline;
        SignalObservation(String provider, MarketIntelligenceSignalType type, long receivedAtMs, double confidence, double priority, String headline) {
            this.provider = normalize(provider).isBlank() ? "UNKNOWN" : normalize(provider);
            this.type = type == null ? MarketIntelligenceSignalType.UNKNOWN : type;
            this.receivedAtMs = receivedAtMs <= 0 ? System.currentTimeMillis() : receivedAtMs;
            this.confidence = clamp(confidence);
            this.priority = clamp(priority);
            this.headline = headline == null ? "" : headline;
        }
        double score() { return clamp(confidence * 0.45 + priority * 0.55); }
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_'); }
    private static double clamp(double value) { if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0; return Math.max(0.0, Math.min(1.0, value)); }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.5f", clamp(v)); }
    private static String csv(String v) { String s = v == null ? "" : v.replace("\r", " ").replace("\n", " "); return '"' + s.replace("\"", "\"\"") + '"'; }
    private static boolean envBoolean(String key, boolean fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()) || "yes".equalsIgnoreCase(v.trim()); }
    private static long envLong(String key, long fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); } catch (Exception e) { return fallback; } }
}
