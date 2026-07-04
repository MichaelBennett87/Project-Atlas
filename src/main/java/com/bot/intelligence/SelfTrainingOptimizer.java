package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SelfTrainingOptimizer {

    private final Path outcomesPath;
    private final Path featuresPath;
    private final Path reportPath;
    private final AdaptiveTradingPolicyStore policyStore;
    private final long intervalMs;
    private final int minClosedTradesBeforeTuning;
    private volatile boolean started;

    public SelfTrainingOptimizer() {
        this(
                Path.of(System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv")),
                Path.of(System.getenv().getOrDefault("FEATURE_JOURNAL_PATH", "logs/market_features.csv")),
                Path.of(System.getenv().getOrDefault("AI_TRAINING_REPORT_PATH", "logs/ai_training_report.txt")),
                new AdaptiveTradingPolicyStore(),
                envLong("AI_AUTO_TUNE_INTERVAL_SECONDS", 300L) * 1000L,
                envInt("AI_MIN_CLOSED_TRADES_BEFORE_TUNING", 20)
        );
    }

    public SelfTrainingOptimizer(
            Path outcomesPath,
            Path featuresPath,
            Path reportPath,
            AdaptiveTradingPolicyStore policyStore,
            long intervalMs,
            int minClosedTradesBeforeTuning
    ) {
        this.outcomesPath = outcomesPath;
        this.featuresPath = featuresPath;
        this.reportPath = reportPath;
        this.policyStore = policyStore;
        this.intervalMs = Math.max(30_000L, intervalMs);
        this.minClosedTradesBeforeTuning = Math.max(5, minClosedTradesBeforeTuning);
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(this::loop);
        t.setName("self-training-optimizer");
        t.setDaemon(true);
        t.start();
        System.out.println("SELF TRAINING OPTIMIZER STARTED: intervalMs=" + intervalMs +
                " outcomes=" + outcomesPath + " features=" + featuresPath +
                " policy=" + policyStore.path());
        System.out.println("SELF TRAINING MODE: the bot tunes policy thresholds/weights; it does NOT rewrite Java source code while trading.");
    }

    public OptimizationSummary runOnce() {
        TradeOutcomeDataQualityReport.Result quality = new TradeOutcomeDataQualityReport().runOnce();
        TrainingStats stats = readOutcomeStats();
        FeatureStats featureStats = readFeatureStats();
        AdaptiveTradingPolicy current = policyStore.currentPolicy();
        AdaptiveTradingPolicy next = buildNextPolicy(current, stats);
        ReplayDrivenStrategyOptimizer.Result replayResult = null;

        writeReport(stats, featureStats, current, next);

        if (stats.closedTrades >= minClosedTradesBeforeTuning) {
            policyStore.save(next);
        } else {
            System.out.println("AI AUTO TUNE SKIPPED: closedTrades=" + stats.closedTrades +
                    " required=" + minClosedTradesBeforeTuning);
        }

        if (envBool("REPLAY_DRIVEN_STRATEGY_OPTIMIZER_ENABLED", true)) {
            replayResult = new ReplayDrivenStrategyOptimizer().runOnce();
            System.out.println("REPLAY STRATEGY OPTIMIZER UPDATED: outcomeStrategies=" +
                    replayResult.outcomeStrategies +
                    " replayStrategies=" + replayResult.replayStrategies +
                    " recommendations=" + replayResult.recommendations +
                    " policy=" + replayResult.policyPath);
        }
        if (envBool("PREDICTION_CALIBRATION_ENGINE_ENABLED", true)) {
            PredictionCalibrationEngine.Result calibrationResult = new PredictionCalibrationEngine().runOnce();
            System.out.println("PREDICTION CALIBRATION UPDATED: samples=" +
                    calibrationResult.samples +
                    " strategies=" + calibrationResult.strategies +
                    " recommendations=" + calibrationResult.recommendations +
                    " validatedIncreases=" + calibrationResult.validatedIncreases +
                    " rejectedIncreases=" + calibrationResult.rejectedIncreases +
                    " downweights=" + calibrationResult.downweights +
                    " policy=" + calibrationResult.policyPath +
                    " report=" + calibrationResult.reportPath);
        }
        if (envBool("PRE_TRADE_CALIBRATION_ENGINE_ENABLED", true)) {
            PreTradeCalibrationLearningEngine.Result preTradeCalibrationResult =
                    new PreTradeCalibrationLearningEngine().run();
            System.out.println("PRE TRADE CALIBRATION UPDATED: " + preTradeCalibrationResult.summary());
        }
        if (envBool("PRE_TRADE_CALIBRATION_AUDIT_ENGINE_ENABLED", true)) {
            PreTradeCalibrationAuditEngine.Result auditResult = new PreTradeCalibrationAuditEngine().run();
            System.out.println("PRE TRADE CALIBRATION AUDIT UPDATED: " + auditResult.summary());
        }
        if (envBool("EXIT_SHADOW_TOURNAMENT_ENGINE_ENABLED", true)) {
            ExitShadowTournamentEngine.Result exitTournament = new ExitShadowTournamentEngine().run();
            System.out.println("EXIT SHADOW TOURNAMENT UPDATED: " + exitTournament.summary());
        }
        if (envBool("LIVE_TRADE_READINESS_GATE_HEALTH_ENABLED", true)) {
            LiveTradeReadinessGate.Result readiness = LiveTradeReadinessGate.getInstance().writeHealthSnapshot();
            System.out.println("LIVE TRADE READINESS GATE UPDATED: " + readiness.summary());
        }
        System.out.println("TRADE OUTCOME QUALITY UPDATED: trainingRows=" +
                quality.trainingEligibleRows +
                " totalRows=" + quality.totalRows +
                " report=" + quality.reportPath);

        return new OptimizationSummary(stats.closedTrades, stats.totalPnl, stats.winRate(), next);
    }

    private void loop() {
        while (started) {
            try {
                runOnce();
            } catch (Exception e) {
                System.out.println("SELF TRAINING OPTIMIZER ERROR: " + e.getMessage());
            }
            sleep(intervalMs);
        }
    }

    private AdaptiveTradingPolicy buildNextPolicy(AdaptiveTradingPolicy current, TrainingStats stats) {
        double minP = current.minProbabilityTarget;
        double minEv = current.minExpectedValuePercent;
        double minProposal = current.minProposalScore;
        double risk = current.riskFractionPerTrade;

        if (stats.closedTrades >= minClosedTradesBeforeTuning) {
            if (stats.totalPnl < 0 || stats.expectancyDollars() < 0 || stats.profitFactor() < envDouble("AI_MIN_PROFIT_FACTOR_TO_HOLD_RISK", 1.05)) {
                minP += 0.025;
                minEv += 0.15;
                minProposal += 0.025;
                risk *= 0.80;
            } else if (stats.winRate() > envDouble("AI_WIN_RATE_TO_LOOSEN_RISK", 0.58)
                    && stats.expectancyDollars() > envDouble("AI_EXPECTANCY_TO_LOOSEN_RISK", 0.0)
                    && stats.profitFactor() >= envDouble("AI_PROFIT_FACTOR_TO_LOOSEN_RISK", 1.35)) {
                minP -= 0.010;
                minEv -= 0.05;
                risk *= 1.05;
            }

            if (stats.lossStreak >= 3) {
                minP += 0.020;
                minEv += 0.10;
                risk *= 0.75;
            }
        }

        Map<String, Double> multipliers = new HashMap<>(current.strategyMultipliers());
        int minStrategySample = envInt("AI_MIN_STRATEGY_TRADES_FOR_MULTIPLIER", 12);
        double minStrategyExpectancy = envDouble("AI_MIN_STRATEGY_EXPECTANCY_TO_UPWEIGHT", 1.0);
        double minStrategyWinRate = envDouble("AI_MIN_STRATEGY_WIN_RATE_TO_UPWEIGHT", 0.54);
        double weakStrategyWinRate = envDouble("AI_WEAK_STRATEGY_WIN_RATE", 0.43);
        for (Map.Entry<String, StrategyStats> e : stats.byStrategy.entrySet()) {
            StrategyStats s = e.getValue();
            if (s.closed < minStrategySample) {
                if (s.closed >= 5 && (s.expectancy() < 0 || s.winRate() < weakStrategyWinRate)) {
                    double old = multipliers.getOrDefault(e.getKey(), 1.0);
                    multipliers.put(e.getKey(), clamp(old - 0.05, 0.50, 1.50));
                }
                continue;
            }
            double old = multipliers.getOrDefault(e.getKey(), 1.0);
            double next = old;
            if (s.expectancy() >= minStrategyExpectancy
                    && s.winRate() >= minStrategyWinRate
                    && s.profitFactor() >= envDouble("AI_MIN_STRATEGY_PROFIT_FACTOR_TO_UPWEIGHT", 1.25)) {
                next = old + (s.closed >= minStrategySample * 2 ? 0.05 : 0.025);
            } else if (s.expectancy() < 0 || s.winRate() < weakStrategyWinRate || s.profitFactor() < 0.90) {
                next = old - (s.expectancy() < -minStrategyExpectancy ? 0.12 : 0.07);
            }
            multipliers.put(e.getKey(), clamp(next, 0.50, 1.50));
        }

        return new AdaptiveTradingPolicy(
                clamp(minP, 0.58, 0.82),
                clamp(minEv, 0.70, 2.50),
                clamp(minProposal, 0.25, 0.65),
                current.maxStopProbability,
                clamp(risk, 0.0005, 0.0060),
                current.liveTradingAllowed,
                System.currentTimeMillis(),
                multipliers
        );
    }

    private TrainingStats readOutcomeStats() {
        TrainingStats stats = new TrainingStats();
        if (!Files.exists(outcomesPath)) {
            return stats;
        }

        try (BufferedReader reader = Files.newBufferedReader(outcomesPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return stats;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                String eventType = h.get(cols, "eventType");
                String strategy = normalizeStrategy(h.get(cols, "strategyName"));
                String syncedFromBroker = h.get(cols, "syncedFromBroker");
                if (!TradeOutcomeTrainingFilter.isTrainingEligible(eventType, strategy, syncedFromBroker)) {
                    continue;
                }

                double pnl = parseDouble(h.get(cols, "realizedPnlDollars"), 0.0);
                stats.closedTrades++;
                stats.totalPnl += pnl;
                if (pnl > 0) {
                    stats.winners++;
                    stats.lossStreak = 0;
                    stats.grossProfit += pnl;
                } else if (pnl < 0) {
                    stats.losers++;
                    stats.lossStreak++;
                    stats.grossLoss += Math.abs(pnl);
                }

                StrategyStats ss = stats.byStrategy.computeIfAbsent(strategy, k -> new StrategyStats());
                ss.closed++;
                ss.pnl += pnl;
                if (pnl > 0) {
                    ss.winners++;
                    ss.grossProfit += pnl;
                } else if (pnl < 0) {
                    ss.losers++;
                    ss.grossLoss += Math.abs(pnl);
                }
            }
        } catch (IOException e) {
            System.out.println("AI OUTCOME READ FAILED: " + e.getMessage());
        }

        return stats;
    }

    private FeatureStats readFeatureStats() {
        FeatureStats stats = new FeatureStats();
        if (!Files.exists(featuresPath)) {
            return stats;
        }

        try (BufferedReader reader = Files.newBufferedReader(featuresPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                stats.rows++;
                if (line.contains("MODEL_BUY_READY")) {
                    stats.buyReady++;
                }
                if (line.contains("MODEL_HOLD")) {
                    stats.holds++;
                }
                if (line.toLowerCase().contains("no rvol") || line.toLowerCase().contains("rvol unavailable")) {
                    stats.noRvolRows++;
                }
            }
        } catch (IOException e) {
            System.out.println("AI FEATURE READ FAILED: " + e.getMessage());
        }

        return stats;
    }

    private void writeReport(
            TrainingStats stats,
            FeatureStats featureStats,
            AdaptiveTradingPolicy current,
            AdaptiveTradingPolicy next
    ) {
        try {
            Path parent = reportPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            DecimalFormat df = new DecimalFormat("0.0000");
            StringBuilder b = new StringBuilder();
            b.append("AI TRAINING REPORT\n");
            b.append("generatedAt=").append(Instant.now()).append('\n');
            b.append("closedTrades=").append(stats.closedTrades).append('\n');
            b.append("totalPnl=").append(df.format(stats.totalPnl)).append('\n');
            b.append("winRate=").append(df.format(stats.winRate())).append('\n');
            b.append("expectancyDollars=").append(df.format(stats.expectancyDollars())).append('\n');
            b.append("profitFactor=").append(df.format(stats.profitFactor())).append('\n');
            b.append("featureRows=").append(featureStats.rows).append('\n');
            b.append("featureBuyReadyRows=").append(featureStats.buyReady).append('\n');
            b.append("featureHoldRows=").append(featureStats.holds).append('\n');
            b.append("featureNoRvolRows=").append(featureStats.noRvolRows).append('\n');
            b.append('\n');
            b.append("currentPolicy.minProbabilityTarget=").append(current.minProbabilityTarget).append('\n');
            b.append("nextPolicy.minProbabilityTarget=").append(next.minProbabilityTarget).append('\n');
            b.append("currentPolicy.minExpectedValuePercent=").append(current.minExpectedValuePercent).append('\n');
            b.append("nextPolicy.minExpectedValuePercent=").append(next.minExpectedValuePercent).append('\n');
            b.append("currentPolicy.riskFractionPerTrade=").append(current.riskFractionPerTrade).append('\n');
            b.append("nextPolicy.riskFractionPerTrade=").append(next.riskFractionPerTrade).append('\n');
            b.append('\n');
            b.append("strategyStats:\n");
            for (Map.Entry<String, StrategyStats> e : stats.byStrategy.entrySet()) {
                StrategyStats s = e.getValue();
                b.append("  ").append(e.getKey())
                        .append(" closed=").append(s.closed)
                        .append(" pnl=").append(df.format(s.pnl))
                        .append(" winRate=").append(df.format(s.winRate()))
                        .append(" expectancy=").append(df.format(s.expectancy()))
                        .append(" profitFactor=").append(df.format(s.profitFactor()))
                        .append(" multiplier=").append(df.format(next.multiplierFor(e.getKey())))
                        .append('\n');
            }
            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("AI TRAINING REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private static String normalizeStrategy(String raw) {
        return TradeOutcomeTrainingFilter.normalizeStrategy(raw);
    }

    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"') {
                    quoted = true;
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
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

    private static boolean envBool(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CsvHeader {
        private final Map<String, Integer> indexes = new HashMap<>();

        CsvHeader(String header) {
            List<String> cols = parseCsv(header);
            for (int i = 0; i < cols.size(); i++) {
                indexes.put(cols.get(i).trim(), i);
            }
        }

        String get(List<String> cols, String name) {
            Integer idx = indexes.get(name);
            if (idx == null || idx < 0 || idx >= cols.size()) {
                return "";
            }
            return cols.get(idx);
        }
    }

    private static final class TrainingStats {
        int closedTrades;
        int winners;
        int losers;
        int lossStreak;
        double totalPnl;
        double grossProfit;
        double grossLoss;
        Map<String, StrategyStats> byStrategy = new HashMap<>();

        double winRate() {
            return closedTrades <= 0 ? 0.0 : (double) winners / closedTrades;
        }

        double expectancyDollars() {
            return closedTrades <= 0 ? 0.0 : totalPnl / closedTrades;
        }

        double profitFactor() {
            if (grossLoss <= 0.0) {
                return grossProfit > 0.0 ? 99.0 : 0.0;
            }
            return grossProfit / grossLoss;
        }
    }

    private static final class StrategyStats {
        int closed;
        int winners;
        int losers;
        double pnl;
        double grossProfit;
        double grossLoss;

        double winRate() {
            return closed <= 0 ? 0.0 : (double) winners / closed;
        }

        double expectancy() {
            return closed <= 0 ? 0.0 : pnl / closed;
        }

        double profitFactor() {
            if (grossLoss <= 0.0) {
                return grossProfit > 0.0 ? 99.0 : 0.0;
            }
            return grossProfit / grossLoss;
        }
    }

    private static final class FeatureStats {
        int rows;
        int buyReady;
        int holds;
        int noRvolRows;
    }

    public static final class OptimizationSummary {
        public final int closedTrades;
        public final double totalPnl;
        public final double winRate;
        public final AdaptiveTradingPolicy policy;

        OptimizationSummary(int closedTrades, double totalPnl, double winRate, AdaptiveTradingPolicy policy) {
            this.closedTrades = closedTrades;
            this.totalPnl = totalPnl;
            this.winRate = winRate;
            this.policy = policy;
        }
    }
}
