package com.bot.intelligence;

import com.bot.master.MasterStrategyDecision;
import com.bot.master.StrategyAction;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learns from decisions the bot did not take. This gives the optimizer more
 * samples than executed trades alone by journaling hypothetical opportunity
 * quality for the losing/held candidate signals.
 */
public final class MarketReplayAgent {
    private static final MarketReplayAgent INSTANCE = new MarketReplayAgent();

    private final boolean enabled = envBoolean("MARKET_REPLAY_AGENT_ENABLED", true);
    private final Path journalPath = Paths.get(System.getenv().getOrDefault("MARKET_REPLAY_JOURNAL", "logs/market_replay_agent.csv"));
    private final Path hintPath = Paths.get(System.getenv().getOrDefault("MARKET_REPLAY_HINTS", "logs/market_replay_hints.properties"));
    private final int minSamplesForHint = envInt("MARKET_REPLAY_MIN_SAMPLES_FOR_HINT", 8);
    private final ConcurrentHashMap<String, ReplayStats> strategyStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReplayStats> tickerStrategyStats = new ConcurrentHashMap<>();

    private MarketReplayAgent() {
        if (enabled) {
            System.out.println("MARKET REPLAY AGENT READY: journal=" + journalPath + " hints=" + hintPath);
        }
    }

    public static MarketReplayAgent getInstance() {
        return INSTANCE;
    }

    public void observeDecision(StrategyContext context, MasterStrategyDecision decision, String actionLabel) {
        if (!enabled || context == null || decision == null) return;
        try {
            List<StrategySignal> signals = decision.getAllSignals();
            if (signals == null || signals.isEmpty()) return;
            StrategySignal winner = decision.getWinningSignal();
            for (StrategySignal signal : signals) {
                if (signal == null || !signal.isActionableBuy()) continue;
                ReplaySample sample = sample(context, decision, signal, winner, actionLabel);
                append(sample);
                update(sample);
            }
            writeHints();
        } catch (Exception e) {
            if (envBoolean("MARKET_REPLAY_VERBOSE_ERRORS", false)) {
                System.out.println("MARKET REPLAY OBSERVE FAILED: " + e.getMessage());
            }
        }
    }

    public double hintFor(String ticker, String strategyName) {
        if (!enabled) return 0.50;
        String tickerKey = key(ticker, strategyName);
        ReplayStats pair = tickerStrategyStats.get(tickerKey);
        if (pair != null && pair.samples >= minSamplesForHint) {
            return pair.normalizedHint();
        }
        ReplayStats strategy = strategyStats.get(normalize(strategyName));
        if (strategy != null && strategy.samples >= minSamplesForHint) {
            return strategy.normalizedHint();
        }
        return 0.50;
    }

    private ReplaySample sample(
            StrategyContext context,
            MasterStrategyDecision decision,
            StrategySignal signal,
            StrategySignal winner,
            String actionLabel
    ) {
        List<Bar> bars = context.getBars();
        Bar last = bars == null || bars.isEmpty() ? null : bars.get(bars.size() - 1);
        double current = last == null ? context.getLastPrice() : last.close;
        double microMove = recentMove(bars, 3);
        double twelveMove = recentMove(bars, 12);
        double volumePulse = volumePulse(bars);
        double hypotheticalQuality = clamp(0.50 + microMove * 7.0 + twelveMove * 2.0 + (volumePulse - 1.0) * 0.10);
        boolean chosen = winner != null && winner == signal && decision.getAction() == StrategyAction.BUY;
        boolean missed = !chosen && hypotheticalQuality >= envDouble("MARKET_REPLAY_MISSED_QUALITY_THRESHOLD", 0.68);
        String classification = chosen ? "CHOSEN" : missed ? "MISSED_POSSIBLE_EDGE" : "NOT_CHOSEN";
        return new ReplaySample(
                Instant.now().toString(),
                context.getTicker(),
                signal.getTicker(),
                signal.getStrategyName(),
                actionLabel,
                classification,
                chosen,
                current,
                signal.getConfidence(),
                signal.getExpectedMovePercent(),
                signal.priorityScore(),
                microMove,
                twelveMove,
                volumePulse,
                hypotheticalQuality,
                decision.getReason()
        );
    }

    private void update(ReplaySample sample) {
        if (sample == null) return;
        strategyStats.computeIfAbsent(normalize(sample.strategy), ReplayStats::new).record(sample);
        tickerStrategyStats.computeIfAbsent(key(sample.ticker, sample.strategy), ReplayStats::new).record(sample);
    }

    private synchronized void append(ReplaySample s) throws IOException {
        Path parent = journalPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter writer = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(String.join(",",
                    clean(s.timestamp), clean(s.contextTicker), clean(s.ticker), clean(s.strategy),
                    clean(s.actionLabel), clean(s.classification), Boolean.toString(s.chosen),
                    fmt(s.price), fmt(s.confidence), fmt(s.expectedMovePercent), fmt(s.priorityScore),
                    fmt(s.microMovePct * 100.0), fmt(s.twelveBarMovePct * 100.0), fmt(s.volumePulse),
                    fmt(s.hypotheticalQuality), clean(s.decisionReason)
            ));
            writer.newLine();
        }
    }

    private synchronized void writeHints() {
        try {
            Path parent = hintPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            java.util.Properties props = new java.util.Properties();
            props.setProperty("updatedAt", Instant.now().toString());
            props.setProperty("description", "Advisory hints from MarketReplayAgent. These compare chosen and missed candidate quality; promotion gates and risk kernel remain authoritative.");
            for (Map.Entry<String, ReplayStats> e : strategyStats.entrySet()) {
                ReplayStats s = e.getValue();
                String p = "strategy." + e.getKey() + ".";
                props.setProperty(p + "samples", Integer.toString(s.samples));
                props.setProperty(p + "chosen", Integer.toString(s.chosen));
                props.setProperty(p + "missedEdges", Integer.toString(s.missedEdges));
                props.setProperty(p + "avgQuality", fmt(s.avgQuality()));
                props.setProperty("replayHint." + e.getKey(), fmt(s.normalizedHint()));
            }
            try (java.io.OutputStream out = Files.newOutputStream(hintPath)) {
                props.store(out, "Market replay hints");
            }
        } catch (Exception e) {
            if (envBoolean("MARKET_REPLAY_VERBOSE_ERRORS", false)) {
                System.out.println("MARKET REPLAY HINT WRITE FAILED: " + e.getMessage());
            }
        }
    }

    private static double recentMove(List<Bar> bars, int lookback) {
        if (bars == null || bars.size() < 2) return 0.0;
        int lastIndex = bars.size() - 1;
        int startIndex = Math.max(0, lastIndex - Math.max(1, lookback));
        double start = bars.get(startIndex).close;
        double end = bars.get(lastIndex).close;
        return start <= 0 ? 0.0 : (end - start) / start;
    }

    private static double volumePulse(List<Bar> bars) {
        if (bars == null || bars.size() < 3) return 1.0;
        Bar last = bars.get(bars.size() - 1);
        long total = 0L;
        int count = 0;
        for (int i = Math.max(0, bars.size() - 16); i < bars.size() - 1; i++) {
            total += Math.max(0L, bars.get(i).volume);
            count++;
        }
        double avg = count <= 0 ? Math.max(1L, last.volume) : total * 1.0 / count;
        return avg <= 0 ? 1.0 : last.volume / avg;
    }

    private static String key(String ticker, String strategy) {
        return normalize(ticker) + "|" + normalize(strategy);
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

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class ReplaySample {
        final String timestamp;
        final String contextTicker;
        final String ticker;
        final String strategy;
        final String actionLabel;
        final String classification;
        final boolean chosen;
        final double price;
        final double confidence;
        final double expectedMovePercent;
        final double priorityScore;
        final double microMovePct;
        final double twelveBarMovePct;
        final double volumePulse;
        final double hypotheticalQuality;
        final String decisionReason;

        ReplaySample(String timestamp, String contextTicker, String ticker, String strategy, String actionLabel,
                     String classification, boolean chosen, double price, double confidence, double expectedMovePercent,
                     double priorityScore, double microMovePct, double twelveBarMovePct, double volumePulse,
                     double hypotheticalQuality, String decisionReason) {
            this.timestamp = timestamp;
            this.contextTicker = contextTicker;
            this.ticker = ticker;
            this.strategy = strategy;
            this.actionLabel = actionLabel;
            this.classification = classification;
            this.chosen = chosen;
            this.price = price;
            this.confidence = confidence;
            this.expectedMovePercent = expectedMovePercent;
            this.priorityScore = priorityScore;
            this.microMovePct = microMovePct;
            this.twelveBarMovePct = twelveBarMovePct;
            this.volumePulse = volumePulse;
            this.hypotheticalQuality = hypotheticalQuality;
            this.decisionReason = decisionReason;
        }
    }

    private static final class ReplayStats {
        final String key;
        int samples;
        int chosen;
        int missedEdges;
        double totalQuality;

        ReplayStats(String key) { this.key = key; }

        void record(ReplaySample s) {
            samples++;
            if (s.chosen) chosen++;
            if ("MISSED_POSSIBLE_EDGE".equals(s.classification)) missedEdges++;
            totalQuality += s.hypotheticalQuality;
        }

        double avgQuality() { return samples <= 0 ? 0.50 : totalQuality / samples; }

        double normalizedHint() {
            double q = avgQuality();
            double missedPressure = samples <= 0 ? 0.0 : Math.min(0.20, missedEdges * 1.0 / samples * 0.35);
            double chosenConfidence = samples <= 0 ? 0.0 : Math.min(0.10, chosen * 1.0 / samples * 0.10);
            return clamp(0.45 + (q - 0.50) * 0.90 + missedPressure + chosenConfidence);
        }
    }
}
