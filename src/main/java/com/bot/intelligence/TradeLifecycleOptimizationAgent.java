package com.bot.intelligence;

import com.bot.model.Position;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches every tracked trade from entry through exit and measures how well the
 * bot captured the available move.  This agent does not place orders.  It turns
 * lifecycle evidence into journals and conservative policy hints that the live
 * risk/execution layers may consume on later reloads.
 */
public final class TradeLifecycleOptimizationAgent {
    private static final TradeLifecycleOptimizationAgent INSTANCE = new TradeLifecycleOptimizationAgent();

    private final boolean enabled = envBoolean("TRADE_LIFECYCLE_OPTIMIZER_ENABLED", true);
    private final Path lifecycleJournalPath = Paths.get(System.getenv().getOrDefault(
            "TRADE_LIFECYCLE_JOURNAL_PATH", "logs/trade_lifecycle_optimization.csv"));
    private final Path recommendationPath = Paths.get(System.getenv().getOrDefault(
            "TRADE_LIFECYCLE_RECOMMENDATION_PATH", "logs/trade_lifecycle_recommendations.properties"));
    private final Map<String, LifecycleSnapshot> active = new ConcurrentHashMap<>();
    private final Map<String, StrategyLifecycleStats> strategyStats = new ConcurrentHashMap<>();

    private TradeLifecycleOptimizationAgent() {
        if (!enabled) {
            return;
        }
        try {
            ensureParent(lifecycleJournalPath);
            ensureParent(recommendationPath);
            if (!Files.exists(lifecycleJournalPath)) {
                try (BufferedWriter writer = Files.newBufferedWriter(lifecycleJournalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    writer.write("timestamp,eventType,ticker,side,strategy,qty,entryPrice,currentPrice,exitPrice,ageSeconds,currentReturnPct,mfePct,maePct,captureRatio,exitEfficiency,entryConfidence,entryPriority,reason,recommendation");
                    writer.newLine();
                }
            }
            if (!Files.exists(recommendationPath)) {
                writeRecommendations();
            }
            System.out.println("TRADE LIFECYCLE OPTIMIZATION AGENT READY: journal=" + lifecycleJournalPath +
                    " recommendations=" + recommendationPath);
        } catch (Exception e) {
            System.out.println("TRADE LIFECYCLE OPTIMIZATION INIT FAILED: " + e.getMessage());
        }
    }

    public static TradeLifecycleOptimizationAgent getInstance() {
        return INSTANCE;
    }

    public void recordOpen(Position position) {
        if (!enabled || position == null || position.ticker == null || position.entryPrice <= 0 || position.quantity <= 0) {
            return;
        }
        try {
            LifecycleSnapshot snapshot = LifecycleSnapshot.from(position);
            active.put(snapshot.ticker, snapshot);
            append("OPEN", snapshot, position.entryPrice, 0, "POSITION_OPEN", "watch_mfe_mae_and_exit_quality");
        } catch (Exception e) {
            System.out.println("TRADE LIFECYCLE OPEN RECORD FAILED: " + e.getMessage());
        }
    }

    public void recordPrice(Position position, double currentPrice) {
        if (!enabled || position == null || position.ticker == null || currentPrice <= 0) {
            return;
        }
        try {
            String ticker = normalize(position.ticker);
            LifecycleSnapshot snapshot = active.computeIfAbsent(ticker, ignored -> LifecycleSnapshot.from(position));
            snapshot.update(position, currentPrice);
            if (snapshot.shouldJournalUpdate()) {
                append("PRICE_UPDATE", snapshot, currentPrice, 0, "MFE_MAE_UPDATE", snapshot.liveRecommendation());
            }
        } catch (Exception e) {
            if (envBoolean("TRADE_LIFECYCLE_VERBOSE_ERRORS", false)) {
                System.out.println("TRADE LIFECYCLE PRICE RECORD FAILED: " + e.getMessage());
            }
        }
    }

    public void recordPartialExit(Position position, double exitPrice, int quantity, String reason) {
        if (!enabled || position == null || position.ticker == null || exitPrice <= 0 || quantity <= 0) {
            return;
        }
        try {
            LifecycleSnapshot snapshot = active.computeIfAbsent(normalize(position.ticker), ignored -> LifecycleSnapshot.from(position));
            snapshot.update(position, exitPrice);
            append("PARTIAL_EXIT", snapshot, exitPrice, quantity, reason, snapshot.exitRecommendation(exitPrice));
            updateStrategyStats(snapshot, exitPrice, true);
            writeRecommendations();
        } catch (Exception e) {
            System.out.println("TRADE LIFECYCLE PARTIAL EXIT RECORD FAILED: " + e.getMessage());
        }
    }

    public void recordClose(Position position, double exitPrice, int quantity, String reason) {
        if (!enabled || position == null || position.ticker == null || exitPrice <= 0 || quantity <= 0) {
            return;
        }
        try {
            String ticker = normalize(position.ticker);
            LifecycleSnapshot snapshot = active.computeIfAbsent(ticker, ignored -> LifecycleSnapshot.from(position));
            snapshot.update(position, exitPrice);
            append("CLOSE", snapshot, exitPrice, quantity, reason, snapshot.exitRecommendation(exitPrice));
            updateStrategyStats(snapshot, exitPrice, false);
            active.remove(ticker);
            writeRecommendations();
        } catch (Exception e) {
            System.out.println("TRADE LIFECYCLE CLOSE RECORD FAILED: " + e.getMessage());
        }
    }

    public double strategyMultiplier(String strategyName) {
        StrategyLifecycleStats stats = strategyStats.get(normalizeStrategy(strategyName));
        if (stats == null || stats.closedTrades < envInt("TRADE_LIFECYCLE_MIN_TRADES_FOR_MULTIPLIER", 4)) {
            return 1.0;
        }
        return stats.recommendedMultiplier();
    }

    private void updateStrategyStats(LifecycleSnapshot snapshot, double exitPrice, boolean partial) {
        if (snapshot == null) {
            return;
        }
        StrategyLifecycleStats stats = strategyStats.computeIfAbsent(snapshot.strategy, StrategyLifecycleStats::new);
        double returnPct = snapshot.returnPct(exitPrice);
        double mfePct = snapshot.mfePct();
        double maePct = snapshot.maePct();
        double capture = snapshot.captureRatio(exitPrice);
        double exitEfficiency = snapshot.exitEfficiency(exitPrice);
        stats.record(returnPct, mfePct, maePct, capture, exitEfficiency, partial);
    }

    private synchronized void append(String eventType, LifecycleSnapshot snapshot, double price, int qty, String reason, String recommendation) throws IOException {
        ensureParent(lifecycleJournalPath);
        try (BufferedWriter writer = Files.newBufferedWriter(lifecycleJournalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(String.join(",",
                    clean(Instant.now().toString()),
                    clean(eventType),
                    clean(snapshot.ticker),
                    clean(snapshot.shortPosition ? "SHORT" : "LONG"),
                    clean(snapshot.strategy),
                    String.valueOf(qty > 0 ? qty : snapshot.quantity),
                    fmt(snapshot.entryPrice),
                    fmt(price),
                    eventType.contains("EXIT") || "CLOSE".equals(eventType) ? fmt(price) : "",
                    fmt((System.currentTimeMillis() - snapshot.openedAtMs) / 1000.0),
                    fmt(snapshot.returnPct(price) * 100.0),
                    fmt(snapshot.mfePct() * 100.0),
                    fmt(snapshot.maePct() * 100.0),
                    fmt(snapshot.captureRatio(price)),
                    fmt(snapshot.exitEfficiency(price)),
                    fmt(snapshot.entryConfidence),
                    fmt(snapshot.entryPriorityScore),
                    clean(reason),
                    clean(recommendation)
            ));
            writer.newLine();
        }
    }

    private synchronized void writeRecommendations() {
        try {
            ensureParent(recommendationPath);
            Properties p = new Properties();
            p.setProperty("updatedAt", Instant.now().toString());
            p.setProperty("description", "Generated by TradeLifecycleOptimizationAgent from MFE/MAE/capture/exit-efficiency evidence. Conservative hints only; risk kernel remains authoritative.");
            for (StrategyLifecycleStats stats : strategyStats.values()) {
                String prefix = "strategy." + stats.strategy + ".";
                p.setProperty(prefix + "closedTrades", Integer.toString(stats.closedTrades));
                p.setProperty(prefix + "partialExits", Integer.toString(stats.partialExits));
                p.setProperty(prefix + "winRate", fmt(stats.winRate()));
                p.setProperty(prefix + "avgReturnPct", fmt(stats.avgReturn() * 100.0));
                p.setProperty(prefix + "avgMfePct", fmt(stats.avgMfe() * 100.0));
                p.setProperty(prefix + "avgMaePct", fmt(stats.avgMae() * 100.0));
                p.setProperty(prefix + "avgCaptureRatio", fmt(stats.avgCapture()));
                p.setProperty(prefix + "avgExitEfficiency", fmt(stats.avgExitEfficiency()));
                p.setProperty("strategyMultiplier." + stats.strategy, fmt(stats.recommendedMultiplier()));
                p.setProperty("exitAdvice." + stats.strategy, stats.exitAdvice());
                p.setProperty("entryAdvice." + stats.strategy, stats.entryAdvice());
            }
            try (java.io.OutputStream out = Files.newOutputStream(recommendationPath)) {
                p.store(out, "Trade lifecycle optimization recommendations");
            }
        } catch (Exception e) {
            System.out.println("TRADE LIFECYCLE RECOMMENDATION WRITE FAILED: " + e.getMessage());
        }
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path == null ? null : path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String normalize(String ticker) {
        return ticker == null || ticker.isBlank() ? "UNKNOWN" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeStrategy(String strategy) {
        return strategy == null || strategy.isBlank() ? "UNKNOWN" : strategy.trim().toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            value = 0.0;
        }
        return String.format(Locale.ROOT, "%.5f", value);
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return fallback;
        return "true".equalsIgnoreCase(value.trim());
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static final class LifecycleSnapshot {
        final String ticker;
        final boolean shortPosition;
        final String strategy;
        final double entryPrice;
        final long openedAtMs;
        final double entryConfidence;
        final double entryPriorityScore;
        int quantity;
        double bestPrice;
        double worstPrice;
        long lastJournalAtMs;

        private LifecycleSnapshot(String ticker, boolean shortPosition, String strategy, double entryPrice, int quantity,
                                  long openedAtMs, double entryConfidence, double entryPriorityScore) {
            this.ticker = ticker;
            this.shortPosition = shortPosition;
            this.strategy = normalizeStrategy(strategy);
            this.entryPrice = entryPrice;
            this.quantity = Math.max(0, quantity);
            this.openedAtMs = openedAtMs <= 0 ? System.currentTimeMillis() : openedAtMs;
            this.entryConfidence = entryConfidence;
            this.entryPriorityScore = entryPriorityScore;
            this.bestPrice = entryPrice;
            this.worstPrice = entryPrice;
            this.lastJournalAtMs = 0L;
        }

        static LifecycleSnapshot from(Position position) {
            LifecycleSnapshot snapshot = new LifecycleSnapshot(
                    normalize(position.ticker),
                    position.isShortPosition(),
                    position.strategyName,
                    position.entryPrice,
                    position.quantity,
                    position.openedAt,
                    position.entryConfidence,
                    position.entryPriorityScore
            );
            snapshot.update(position, position.entryPrice);
            return snapshot;
        }

        void update(Position position, double currentPrice) {
            if (position != null) {
                quantity = Math.max(quantity, position.quantity);
                if (position.isShortPosition()) {
                    if (position.troughPrice > 0) bestPrice = Math.min(bestPrice <= 0 ? position.troughPrice : bestPrice, position.troughPrice);
                    if (position.peakPrice > 0) worstPrice = Math.max(worstPrice, position.peakPrice);
                } else {
                    if (position.peakPrice > 0) bestPrice = Math.max(bestPrice, position.peakPrice);
                    if (position.troughPrice > 0) worstPrice = Math.min(worstPrice <= 0 ? position.troughPrice : worstPrice, position.troughPrice);
                }
            }
            if (currentPrice > 0) {
                if (shortPosition) {
                    bestPrice = Math.min(bestPrice <= 0 ? currentPrice : bestPrice, currentPrice);
                    worstPrice = Math.max(worstPrice, currentPrice);
                } else {
                    bestPrice = Math.max(bestPrice, currentPrice);
                    worstPrice = Math.min(worstPrice <= 0 ? currentPrice : worstPrice, currentPrice);
                }
            }
        }

        boolean shouldJournalUpdate() {
            long now = System.currentTimeMillis();
            long intervalMs = Math.max(5_000L, envInt("TRADE_LIFECYCLE_PRICE_UPDATE_JOURNAL_SECONDS", 60) * 1000L);
            if (now - lastJournalAtMs < intervalMs) return false;
            lastJournalAtMs = now;
            return true;
        }

        double returnPct(double price) {
            if (entryPrice <= 0 || price <= 0) return 0.0;
            return shortPosition ? (entryPrice - price) / entryPrice : (price - entryPrice) / entryPrice;
        }

        double mfePct() {
            if (entryPrice <= 0 || bestPrice <= 0) return 0.0;
            return Math.max(0.0, shortPosition ? (entryPrice - bestPrice) / entryPrice : (bestPrice - entryPrice) / entryPrice);
        }

        double maePct() {
            if (entryPrice <= 0 || worstPrice <= 0) return 0.0;
            double adverse = shortPosition ? (entryPrice - worstPrice) / entryPrice : (worstPrice - entryPrice) / entryPrice;
            return Math.min(0.0, adverse);
        }

        double captureRatio(double price) {
            double mfe = mfePct();
            if (mfe <= 0.000001) return returnPct(price) > 0 ? 1.0 : 0.0;
            return clamp(returnPct(price) / mfe, -2.0, 1.25);
        }

        double exitEfficiency(double price) {
            double mfe = mfePct();
            if (mfe <= 0.000001) return returnPct(price) >= 0 ? 1.0 : 0.0;
            double efficiency = 1.0 - Math.max(0.0, mfe - returnPct(price)) / Math.max(0.0001, Math.abs(mfe));
            return clamp(efficiency, -1.0, 1.0);
        }

        String liveRecommendation() {
            double mfe = mfePct();
            double mae = maePct();
            if (mfe >= 0.025 && Math.abs(mae) <= 0.008) return "strong_entry_low_adverse_excursion_keep_exit_trail_adaptive";
            if (mfe < 0.004 && Math.abs(mae) >= 0.015) return "weak_entry_or_bad_timing_consider_tighter_entry_filter";
            return "continue_monitoring";
        }

        String exitRecommendation(double exitPrice) {
            double capture = captureRatio(exitPrice);
            double mfe = mfePct();
            double ret = returnPct(exitPrice);
            if (ret < 0 && mfe > 0.01) return "winner_became_loser_exit_was_too_late_tighten_trailing_stop";
            if (capture < 0.35 && mfe > 0.015) return "low_capture_ratio_tighten_exit_or_partial_profit";
            if (capture > 0.75 && ret > 0) return "high_capture_exit_logic_working_preserve_rules";
            if (ret < 0) return "losing_trade_review_entry_quality_and_stop";
            return "acceptable_exit";
        }
    }

    private static final class StrategyLifecycleStats {
        final String strategy;
        int closedTrades;
        int partialExits;
        int wins;
        double totalReturn;
        double totalMfe;
        double totalMae;
        double totalCapture;
        double totalExitEfficiency;

        StrategyLifecycleStats(String strategy) {
            this.strategy = normalizeStrategy(strategy);
        }

        void record(double returnPct, double mfePct, double maePct, double capture, double exitEfficiency, boolean partial) {
            if (partial) partialExits++;
            closedTrades++;
            if (returnPct > 0) wins++;
            totalReturn += returnPct;
            totalMfe += mfePct;
            totalMae += maePct;
            totalCapture += capture;
            totalExitEfficiency += exitEfficiency;
        }

        double winRate() { return closedTrades <= 0 ? 0.0 : (double) wins / (double) closedTrades; }
        double avgReturn() { return closedTrades <= 0 ? 0.0 : totalReturn / closedTrades; }
        double avgMfe() { return closedTrades <= 0 ? 0.0 : totalMfe / closedTrades; }
        double avgMae() { return closedTrades <= 0 ? 0.0 : totalMae / closedTrades; }
        double avgCapture() { return closedTrades <= 0 ? 0.0 : totalCapture / closedTrades; }
        double avgExitEfficiency() { return closedTrades <= 0 ? 0.0 : totalExitEfficiency / closedTrades; }

        double recommendedMultiplier() {
            if (closedTrades < envInt("TRADE_LIFECYCLE_MIN_TRADES_FOR_MULTIPLIER", 4)) return 1.0;
            double score = 1.0;
            score += (winRate() - 0.50) * 0.40;
            score += Math.max(-0.20, Math.min(0.20, avgReturn() * 8.0));
            score += (avgCapture() - 0.50) * 0.20;
            score += (avgExitEfficiency() - 0.50) * 0.15;
            if (avgMae() < -0.025) score -= 0.10;
            return clamp(score, 0.50, 1.50);
        }

        String exitAdvice() {
            if (closedTrades <= 0) return "collect_more_data";
            if (avgCapture() < 0.35 && avgMfe() > 0.015) return "tighten_trailing_stop_or_take_partial_profits_sooner";
            if (avgExitEfficiency() > 0.70 && avgReturn() > 0) return "exit_logic_effective_preserve";
            if (avgReturn() < 0 && avgMfe() > 0.01) return "profits_available_but_not_captured_tighten_exit";
            return "neutral_collect_more_samples";
        }

        String entryAdvice() {
            if (closedTrades <= 0) return "collect_more_data";
            if (avgMae() < -0.03 && winRate() < 0.45) return "entry_too_early_or_low_quality_raise_entry_confirmation";
            if (avgMfe() > 0.02 && avgMae() > -0.012) return "entry_quality_good_allow_strategy_to_continue";
            return "neutral";
        }
    }
}
