package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * End-of-day scorecard used by AutonomousCodeEvolutionMain.
 *
 * UnifiedStrategyMain writes market_features.csv and trade_outcomes.csv all day.
 * After the close, this class summarizes what the bot actually learned, writes a
 * durable report, and exports guardrail properties consumed by the nightly
 * evolution process. It does not place orders and it does not modify live code.
 */
public class DailySelfImprovementScorecard {

    private final Path featurePath;
    private final Path outcomePath;
    private final Path reportPath;
    private final Path propertiesPath;

    public DailySelfImprovementScorecard() {
        this(
                Path.of(System.getenv().getOrDefault("FEATURE_JOURNAL_PATH", "logs/market_features.csv")),
                Path.of(System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv")),
                Path.of(System.getenv().getOrDefault("AI_DAILY_SCORECARD_REPORT_PATH", "logs/ai_daily_scorecard.txt")),
                Path.of(System.getenv().getOrDefault("AI_DAILY_SCORECARD_PROPERTIES_PATH", "logs/ai_daily_scorecard.properties"))
        );
    }

    public DailySelfImprovementScorecard(Path featurePath, Path outcomePath, Path reportPath, Path propertiesPath) {
        this.featurePath = featurePath;
        this.outcomePath = outcomePath;
        this.reportPath = reportPath;
        this.propertiesPath = propertiesPath;
    }

    public Result run() {
        FeatureStats featureStats = loadFeatureStats(featurePath);
        OutcomeStats outcomeStats = loadOutcomeStats(outcomePath);
        Result result = new Result(featureStats, outcomeStats);
        writeReport(result);
        writeProperties(result);
        return result;
    }

    private FeatureStats loadFeatureStats(Path path) {
        FeatureStats stats = new FeatureStats();
        if (!Files.exists(path)) {
            return stats;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return stats;
            }
            Header header = new Header(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.List<String> cols = AutonomousEvolutionSuite.Csv.parse(line);
                stats.rows++;
                String strategy = clean(header.get(cols, "selectedStrategy"));
                String action = clean(header.get(cols, "selectedAction"));
                double sentiment = num(header.get(cols, "sentimentNet"), 0.0);
                double freshness = num(header.get(cols, "freshnessSeconds"), 999999.0);
                double catalyst = num(header.get(cols, "catalystScore"), 0.0);
                if (action.contains("BUY") || action.contains("READY")) stats.actionableRows++;
                if (action.contains("HOLD")) stats.holdRows++;
                if (sentiment <= -0.25 && freshness <= envDouble("AI_NEGATIVE_NEWS_SHORT_MAX_FRESHNESS_SECONDS", 900.0)) stats.freshNegativeNewsRows++;
                if (sentiment >= 0.25 && freshness <= envDouble("AI_POSITIVE_NEWS_MAX_FRESHNESS_SECONDS", 900.0)) stats.freshPositiveNewsRows++;
                if (catalyst > 0.0 || Math.abs(sentiment) > 0.10) stats.newsRows++;
                stats.strategyRows.compute(strategy.isBlank() ? "UNKNOWN" : strategy, (k, v) -> v == null ? 1 : v + 1);
            }
        } catch (IOException e) {
            stats.loadError = e.getMessage();
        }
        return stats;
    }

    private OutcomeStats loadOutcomeStats(Path path) {
        OutcomeStats stats = new OutcomeStats();
        if (!Files.exists(path)) {
            return stats;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return stats;
            }
            Header header = new Header(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.List<String> cols = AutonomousEvolutionSuite.Csv.parse(line);
                String event = clean(header.get(cols, "eventType"));
                String strategy = TradeOutcomeTrainingFilter.normalizeStrategy(header.get(cols, "strategyName"));
                String syncedFromBroker = header.get(cols, "syncedFromBroker");
                if (!TradeOutcomeTrainingFilter.isTrainingEligible(event, strategy, syncedFromBroker)) {
                    continue;
                }
                String side = clean(header.get(cols, "side"));
                double pnl = num(header.get(cols, "realizedPnlDollars"), num(header.get(cols, "realizedProfit"), num(header.get(cols, "realizedPnl"), 0.0)));
                stats.exits++;
                stats.realizedPnl += pnl;
                if (pnl > 0.0) {
                    stats.wins++;
                    stats.grossWin += pnl;
                } else if (pnl < 0.0) {
                    stats.losses++;
                    stats.grossLoss += Math.abs(pnl);
                }
                double holdMs = num(header.get(cols, "holdMs"), 0.0);
                double maxGain = num(header.get(cols, "maxGainPercent"), num(header.get(cols, "mfePercent"), 0.0));
                double maxDrawdown = num(header.get(cols, "maxDrawdownPercent"), num(header.get(cols, "maePercent"), 0.0));
                double currentPnl = num(header.get(cols, "currentPnlPercent"), 0.0);
                double exitEfficiency = num(header.get(cols, "exitEfficiencyPercent"), maxGain <= 0.0 ? (currentPnl > 0.0 ? 1.0 : 0.0) : Math.max(0.0, Math.min(1.0, currentPnl / maxGain)));
                double giveBack = num(header.get(cols, "giveBackPercent"), Math.max(0.0, maxGain - currentPnl));
                stats.totalHoldMs += Math.max(0.0, holdMs);
                stats.totalMfePercent += maxGain;
                stats.totalMaePercent += maxDrawdown;
                stats.totalExitEfficiency += exitEfficiency;
                stats.totalGiveBackPercent += giveBack;
                if (side.equals("SHORT")) stats.shortExits++;
                else if (side.equals("LONG")) stats.longExits++;
                StrategyOutcome outcome = stats.strategyOutcomes.computeIfAbsent(strategy.isBlank() ? "UNKNOWN" : strategy, k -> new StrategyOutcome());
                outcome.trades++;
                outcome.pnl += pnl;
                outcome.totalHoldMs += Math.max(0.0, holdMs);
                outcome.totalMfePercent += maxGain;
                outcome.totalMaePercent += maxDrawdown;
                outcome.totalExitEfficiency += exitEfficiency;
                outcome.totalGiveBackPercent += giveBack;
                if (pnl > 0) outcome.wins++;
            }
        } catch (IOException e) {
            stats.loadError = e.getMessage();
        }
        return stats;
    }

    private void writeReport(Result result) {
        StringBuilder b = new StringBuilder();
        b.append("DAILY SELF-IMPROVEMENT SCORECARD\n");
        b.append("generatedAt=").append(Instant.now()).append('\n');
        b.append("featureRows=").append(result.features.rows).append('\n');
        b.append("actionableFeatureRows=").append(result.features.actionableRows).append('\n');
        b.append("holdFeatureRows=").append(result.features.holdRows).append('\n');
        b.append("newsFeatureRows=").append(result.features.newsRows).append('\n');
        b.append("freshPositiveNewsRows=").append(result.features.freshPositiveNewsRows).append('\n');
        b.append("freshNegativeNewsRows=").append(result.features.freshNegativeNewsRows).append('\n');
        b.append("trainingEligibleExits=").append(result.outcomes.exits).append('\n');
        b.append("longExits=").append(result.outcomes.longExits).append('\n');
        b.append("shortExits=").append(result.outcomes.shortExits).append('\n');
        b.append("realizedPnl=").append(result.outcomes.realizedPnl).append('\n');
        b.append("winRate=").append(result.outcomes.winRate()).append('\n');
        b.append("profitFactor=").append(result.outcomes.profitFactor()).append('\n');
        b.append("avgHoldMinutes=").append(result.outcomes.avgHoldMinutes()).append('\n');
        b.append("avgMfePercent=").append(result.outcomes.avgMfePercent()).append('\n');
        b.append("avgMaePercent=").append(result.outcomes.avgMaePercent()).append('\n');
        b.append("avgExitEfficiency=").append(result.outcomes.avgExitEfficiency()).append('\n');
        b.append("avgGiveBackPercent=").append(result.outcomes.avgGiveBackPercent()).append('\n');
        b.append("promotionDataReady=").append(result.promotionDataReady()).append('\n');
        b.append("shortArchitectureHasTrainingData=").append(result.shortArchitectureHasTrainingData()).append('\n');
        if (!result.features.loadError.isBlank()) b.append("featureLoadError=").append(result.features.loadError).append('\n');
        if (!result.outcomes.loadError.isBlank()) b.append("outcomeLoadError=").append(result.outcomes.loadError).append('\n');
        b.append('\n').append("STRATEGY FEATURE ROWS\n");
        for (Map.Entry<String, Integer> e : result.features.strategyRows.entrySet()) {
            b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        b.append('\n').append("STRATEGY OUTCOMES\n");
        for (Map.Entry<String, StrategyOutcome> e : result.outcomes.strategyOutcomes.entrySet()) {
            StrategyOutcome o = e.getValue();
            b.append(e.getKey()).append(" trades=").append(o.trades)
                    .append(" wins=").append(o.wins)
                    .append(" pnl=").append(o.pnl)
                    .append(" expectancy=").append(o.trades <= 0 ? 0.0 : o.pnl / o.trades)
                    .append(" avgHoldMinutes=").append(o.avgHoldMinutes())
                    .append(" avgExitEfficiency=").append(o.avgExitEfficiency())
                    .append(" avgGiveBackPercent=").append(o.avgGiveBackPercent())
                    .append('\n');
        }
        AutonomousEvolutionSuite.FilesUtil.writeString(reportPath, b.toString());
    }

    private void writeProperties(Result result) {
        try {
            Path parent = propertiesPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Properties p = new Properties();
            p.setProperty("generatedAtMs", Long.toString(System.currentTimeMillis()));
            p.setProperty("featureRows", Integer.toString(result.features.rows));
            p.setProperty("trainingEligibleExits", Integer.toString(result.outcomes.exits));
            p.setProperty("realizedPnl", Double.toString(result.outcomes.realizedPnl));
            p.setProperty("winRate", Double.toString(result.outcomes.winRate()));
            p.setProperty("profitFactor", Double.toString(result.outcomes.profitFactor()));
            p.setProperty("avgHoldMinutes", Double.toString(result.outcomes.avgHoldMinutes()));
            p.setProperty("avgMfePercent", Double.toString(result.outcomes.avgMfePercent()));
            p.setProperty("avgMaePercent", Double.toString(result.outcomes.avgMaePercent()));
            p.setProperty("avgExitEfficiency", Double.toString(result.outcomes.avgExitEfficiency()));
            p.setProperty("avgGiveBackPercent", Double.toString(result.outcomes.avgGiveBackPercent()));
            p.setProperty("freshNegativeNewsRows", Integer.toString(result.features.freshNegativeNewsRows));
            p.setProperty("freshPositiveNewsRows", Integer.toString(result.features.freshPositiveNewsRows));
            p.setProperty("promotionDataReady", Boolean.toString(result.promotionDataReady()));
            p.setProperty("shortArchitectureHasTrainingData", Boolean.toString(result.shortArchitectureHasTrainingData()));
            try (var out = Files.newOutputStream(propertiesPath)) {
                p.store(out, "Daily self-improvement scorecard for the next autonomous evolution run.");
            }
        } catch (IOException e) {
            System.out.println("DAILY SCORECARD PROPERTY WRITE FAILED: " + e.getMessage());
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static double num(String value, double fallback) {
        try { return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    static final class Header {
        private final Map<String, Integer> index = new LinkedHashMap<>();
        Header(String headerLine) {
            java.util.List<String> cols = AutonomousEvolutionSuite.Csv.parse(headerLine);
            for (int i = 0; i < cols.size(); i++) index.put(cols.get(i).trim(), i);
        }
        String get(java.util.List<String> cols, String name) {
            Integer idx = index.get(name);
            return idx == null || idx < 0 || idx >= cols.size() ? "" : cols.get(idx);
        }
    }

    static final class FeatureStats {
        int rows;
        int actionableRows;
        int holdRows;
        int newsRows;
        int freshPositiveNewsRows;
        int freshNegativeNewsRows;
        String loadError = "";
        final Map<String, Integer> strategyRows = new LinkedHashMap<>();
    }

    static final class OutcomeStats {
        int exits;
        int longExits;
        int shortExits;
        int wins;
        int losses;
        double realizedPnl;
        double grossWin;
        double grossLoss;
        double totalHoldMs;
        double totalMfePercent;
        double totalMaePercent;
        double totalExitEfficiency;
        double totalGiveBackPercent;
        String loadError = "";
        final Map<String, StrategyOutcome> strategyOutcomes = new LinkedHashMap<>();
        double winRate() { return exits <= 0 ? 0.0 : wins / (double) exits; }
        double profitFactor() { return grossLoss <= 0.0 ? (grossWin > 0.0 ? 9.0 : 0.0) : grossWin / grossLoss; }
        double avgHoldMinutes() { return exits <= 0 ? 0.0 : totalHoldMs / 60000.0 / exits; }
        double avgMfePercent() { return exits <= 0 ? 0.0 : totalMfePercent / exits; }
        double avgMaePercent() { return exits <= 0 ? 0.0 : totalMaePercent / exits; }
        double avgExitEfficiency() { return exits <= 0 ? 0.0 : totalExitEfficiency / exits; }
        double avgGiveBackPercent() { return exits <= 0 ? 0.0 : totalGiveBackPercent / exits; }
    }

    static final class StrategyOutcome {
        int trades;
        int wins;
        double pnl;
        double totalHoldMs;
        double totalMfePercent;
        double totalMaePercent;
        double totalExitEfficiency;
        double totalGiveBackPercent;
        double avgHoldMinutes() { return trades <= 0 ? 0.0 : totalHoldMs / 60000.0 / trades; }
        double avgExitEfficiency() { return trades <= 0 ? 0.0 : totalExitEfficiency / trades; }
        double avgGiveBackPercent() { return trades <= 0 ? 0.0 : totalGiveBackPercent / trades; }
    }

    public static final class Result {
        public final FeatureStats features;
        public final OutcomeStats outcomes;
        Result(FeatureStats features, OutcomeStats outcomes) {
            this.features = features;
            this.outcomes = outcomes;
        }
        public boolean promotionDataReady() {
            int minFeatures = (int) envDouble("AI_MIN_FEATURE_ROWS_BEFORE_POLICY_PROMOTION", 250.0);
            int minClosed = (int) envDouble("AI_MIN_CLOSED_TRADES_BEFORE_POLICY_PROMOTION", 20.0);
            return features.rows >= minFeatures || outcomes.exits >= minClosed;
        }
        public boolean shortArchitectureHasTrainingData() {
            return features.freshNegativeNewsRows > 0 || outcomes.shortExits > 0;
        }
        public String summary() {
            return "scorecard featureRows=" + features.rows +
                    " exits=" + outcomes.exits +
                    " pnl=" + outcomes.realizedPnl +
                    " profitFactor=" + outcomes.profitFactor() +
                    " freshNegativeNewsRows=" + features.freshNegativeNewsRows +
                    " promotionDataReady=" + promotionDataReady();
        }
    }
}
