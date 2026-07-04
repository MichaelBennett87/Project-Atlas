package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Promotion gate for paper/shadow strategy outcomes.
 *
 * The output intentionally uses the same policy keys consumed by
 * AdaptiveTradingPolicyStore and StrategySelectionGovernor:
 * strategyMultiplier.*, simulationStatus.*, and disabledStrategy.*.
 */
public final class PaperTradingPerformanceGate {
    private final List<Path> outcomePaths;
    private final Path executionAnalyticsPath;
    private final Path executionCostPolicyPath;
    private final Path policyPath;
    private final Path reportPath;
    private final Path healthPath;
    private final GateConfig config;

    public PaperTradingPerformanceGate() {
        this(
                parsePaths(env("PAPER_TRADING_OUTCOME_PATHS", defaultOutcomePaths())),
                Path.of(env("PAPER_TRADING_EXECUTION_ANALYTICS_PATH",
                        env("EXECUTION_ANALYTICS_PATH", "logs/execution_analytics.csv"))),
                Path.of(env("EXECUTION_COST_POLICY_PATH", "logs/execution_cost_policy.properties")),
                Path.of(env("PAPER_TRADING_STRATEGY_POLICY_PATH", "logs/paper_trading_strategy_policy.properties")),
                Path.of(env("PAPER_TRADING_PERFORMANCE_GATE_REPORT", "logs/paper_trading_performance_gate_report.txt")),
                Path.of(env("PAPER_TRADING_PERFORMANCE_GATE_HEALTH", "logs/paper_trading_performance_gate_health.properties")),
                GateConfig.fromEnv()
        );
    }

    PaperTradingPerformanceGate(List<Path> outcomePaths,
                                Path executionAnalyticsPath,
                                Path executionCostPolicyPath,
                                Path policyPath,
                                Path reportPath,
                                Path healthPath,
                                GateConfig config) {
        this.outcomePaths = outcomePaths == null || outcomePaths.isEmpty()
                ? List.of(Path.of("logs/trade_outcomes.csv"))
                : List.copyOf(outcomePaths);
        this.executionAnalyticsPath = executionAnalyticsPath;
        this.executionCostPolicyPath = executionCostPolicyPath;
        this.policyPath = policyPath;
        this.reportPath = reportPath;
        this.healthPath = healthPath;
        this.config = config == null ? GateConfig.fromEnv() : config;
    }

    public Result run() {
        Map<String, StrategyStats> stats = readOutcomes();
        Map<String, ExecutionStats> execution = readExecutionAnalytics();
        readExecutionCostPolicy(execution);
        for (Map.Entry<String, ExecutionStats> entry : execution.entrySet()) {
            StrategyStats s = stats.computeIfAbsent(entry.getKey(), StrategyStats::new);
            s.execution = entry.getValue();
        }

        List<Recommendation> recommendations = new ArrayList<>();
        for (StrategyStats s : stats.values()) {
            if (s.closedTrades <= 0 && (s.execution == null || s.execution.samples <= 0)) {
                continue;
            }
            recommendations.add(recommend(s));
        }
        recommendations.sort(Comparator
                .comparing((Recommendation r) -> r.statusRank()).reversed()
                .thenComparing((Recommendation r) -> r.multiplier).reversed()
                .thenComparing(r -> r.strategy));

        writePolicy(recommendations);
        writeReport(recommendations, stats, execution);
        writeHealth(recommendations, stats, execution);
        return new Result(stats.size(), execution.size(), recommendations.size(), policyPath, reportPath, healthPath);
    }

    private Recommendation recommend(StrategyStats s) {
        if (s.closedTrades < config.minSamples) {
            return new Recommendation(
                    s.strategy,
                    "INSUFFICIENT",
                    1.0,
                    false,
                    "sample_count_below_gate closedTrades=" + s.closedTrades + " minSamples=" + config.minSamples,
                    s
            );
        }

        double expectancyDollars = s.expectancyDollars();
        double expectancyPercent = s.expectancyPercent();
        double winRate = s.winRate();
        double profitFactor = s.profitFactor();
        double worstDrawdown = s.worstDrawdownPercent;
        double avgAbsSlippage = s.execution == null ? 0.0 : s.execution.avgAbsSlippagePercent();
        double learnedCost = s.execution == null ? 0.0 : s.execution.learnedRoundTripCostFraction;
        double learnedFillRate = s.execution == null ? 1.0 : s.execution.learnedFillRate;
        boolean executionBlocked = s.execution != null && s.execution.learnedBlocked;
        StringBuilder reason = new StringBuilder();

        boolean dollarGatePassed = s.dollarPnlSamples <= 0 || expectancyDollars >= config.minExpectancyDollars;
        boolean pass = s.closedTrades >= config.minPromoteSamples
                && dollarGatePassed
                && expectancyPercent >= config.minExpectancyPercent
                && winRate >= config.minWinRate
                && profitFactor >= config.minProfitFactor
                && worstDrawdown <= config.maxWorstDrawdownPercent
                && avgAbsSlippage <= config.maxAvgAbsSlippagePercent
                && learnedCost <= config.maxExecutionCostFraction
                && learnedFillRate >= config.minExecutionFillRate
                && !executionBlocked;

        boolean weak = expectancyDollars < 0.0
                || expectancyPercent < 0.0
                || winRate < config.weakWinRate
                || profitFactor < config.weakProfitFactor
                || worstDrawdown > config.weakWorstDrawdownPercent
                || avgAbsSlippage > config.weakAvgAbsSlippagePercent
                || learnedCost > config.weakExecutionCostFraction
                || learnedFillRate < config.weakExecutionFillRate
                || executionBlocked;

        boolean dollarSevere = s.dollarPnlSamples <= 0 || expectancyDollars <= -Math.abs(config.disableExpectancyDollars);
        boolean severe = s.closedTrades >= config.minDisableSamples
                && dollarSevere
                && expectancyPercent <= -Math.abs(config.disableExpectancyPercent)
                && winRate <= config.disableWinRate
                && profitFactor <= config.disableProfitFactor;
        if (s.closedTrades >= config.minDisableSamples
                && executionBlocked
                && learnedCost >= config.disableExecutionCostFraction) {
            severe = true;
        }

        if (pass) {
            double qualityBoost = 0.10
                    + clamp((profitFactor - config.minProfitFactor) * 0.08, 0.0, 0.12)
                    + clamp((winRate - config.minWinRate) * 0.35, 0.0, 0.08)
                    + clamp((expectancyPercent - config.minExpectancyPercent) * 40.0, 0.0, 0.10);
            double multiplier = clamp(1.0 + qualityBoost, 1.05, config.maxPromoteMultiplier);
            reason.append("paper_gate_passed ");
            appendMetrics(reason, s, avgAbsSlippage);
            return new Recommendation(s.strategy, "PASSED", multiplier, false, reason.toString().trim(), s);
        }

        if (severe) {
            boolean disabled = !config.protectedDisableStrategies.contains(s.strategy);
            reason.append(disabled ? "disabled_by_paper_gate " : "protected_from_disable_shrunk_only ");
            appendMetrics(reason, s, avgAbsSlippage);
            return new Recommendation(s.strategy, "FAILED", config.disableMultiplier, disabled, reason.toString().trim(), s);
        }

        if (weak) {
            double penalty = 0.10;
            if (expectancyDollars < 0.0 || expectancyPercent < 0.0) penalty += 0.12;
            if (profitFactor < config.weakProfitFactor) penalty += 0.08;
            if (winRate < config.weakWinRate) penalty += 0.06;
            if (worstDrawdown > config.weakWorstDrawdownPercent) penalty += 0.06;
            if (avgAbsSlippage > config.weakAvgAbsSlippagePercent) penalty += 0.05;
            if (learnedCost > config.weakExecutionCostFraction) penalty += 0.08;
            if (learnedFillRate < config.weakExecutionFillRate) penalty += 0.08;
            if (executionBlocked) penalty += 0.12;
            double multiplier = clamp(1.0 - penalty, config.minShrinkMultiplier, 0.95);
            reason.append("paper_gate_weak_edge ");
            appendMetrics(reason, s, avgAbsSlippage);
            return new Recommendation(s.strategy, "BLOCKED", multiplier, false, reason.toString().trim(), s);
        }

        reason.append("paper_gate_hold ");
        appendMetrics(reason, s, avgAbsSlippage);
        return new Recommendation(s.strategy, "HOLD", 1.0, false, reason.toString().trim(), s);
    }

    private Map<String, StrategyStats> readOutcomes() {
        Map<String, StrategyStats> stats = new LinkedHashMap<>();
        for (Path path : outcomePaths) {
            if (path == null || !Files.exists(path)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String header = reader.readLine();
                if (header == null) {
                    continue;
                }
                CsvHeader h = new CsvHeader(header);
                String line;
                while ((line = reader.readLine()) != null) {
                    List<String> cols = parseCsv(line);
                    String eventType = h.get(cols, "eventType");
                    String strategy = normalizeStrategy(h.get(cols, "strategyName"));
                    String synced = h.get(cols, "syncedFromBroker");
                    if (!config.includeBrokerSynced && isTrue(synced)) {
                        continue;
                    }
                    if (!isTrainingEligible(eventType, strategy, synced)) {
                        continue;
                    }
                    double pnlDollars = parseDouble(firstNonBlank(
                            h.get(cols, "realizedPnlDollars"),
                            h.get(cols, "realizedProfit"),
                            h.get(cols, "realizedPnl"),
                            h.get(cols, "pnlDollars")), 0.0);
                    double pnlPercent = parseDouble(firstNonBlank(
                            h.get(cols, "currentPnlPercent"),
                            h.get(cols, "pnlPercent"),
                            h.get(cols, "returnPercent")), 0.0);
                    double drawdownPercent = Math.abs(parseDouble(firstNonBlank(
                            h.get(cols, "maxDrawdownPercent"),
                            h.get(cols, "drawdownPercent")), 0.0));
                    stats.computeIfAbsent(strategy, StrategyStats::new)
                            .record(pnlDollars, pnlPercent, drawdownPercent);
                }
            } catch (IOException e) {
                System.out.println("PAPER PERFORMANCE GATE OUTCOME READ FAILED: " + path + " " + e.getMessage());
            }
        }
        return stats;
    }

    private void readExecutionCostPolicy(Map<String, ExecutionStats> execution) {
        if (executionCostPolicyPath == null || !Files.exists(executionCostPolicyPath)) {
            return;
        }
        try (InputStream in = Files.newInputStream(executionCostPolicyPath)) {
            Properties p = new Properties();
            p.load(in);
            for (String key : p.stringPropertyNames()) {
                if (!key.startsWith("strategy.") || !key.endsWith(".roundTripCostFraction")) {
                    continue;
                }
                String strategy = normalizeStrategy(key.substring("strategy.".length(),
                        key.length() - ".roundTripCostFraction".length()));
                if (isUnknownStrategy(strategy)) {
                    continue;
                }
                String prefix = "strategy." + strategy + ".";
                ExecutionStats s = execution.computeIfAbsent(strategy, ExecutionStats::new);
                s.learnedRoundTripCostFraction = parseDouble(p.getProperty(prefix + "roundTripCostFraction"), 0.0);
                s.learnedFillRate = parseDouble(p.getProperty(prefix + "fillRate"), 1.0);
                s.learnedDecision = p.getProperty(prefix + "decision", "");
                s.learnedBlocked = Boolean.parseBoolean(p.getProperty(prefix + "blocked", "false"));
                s.learnedAttempts = parseInt(p.getProperty(prefix + "attempts"), 0);
            }
        } catch (Exception e) {
            System.out.println("PAPER PERFORMANCE GATE EXECUTION COST POLICY READ FAILED: " + executionCostPolicyPath + " " + e.getMessage());
        }
    }

    private Map<String, ExecutionStats> readExecutionAnalytics() {
        Map<String, ExecutionStats> stats = new HashMap<>();
        if (executionAnalyticsPath == null || !Files.exists(executionAnalyticsPath)) {
            return stats;
        }
        try (BufferedReader reader = Files.newBufferedReader(executionAnalyticsPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return stats;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                String strategy = normalizeStrategy(h.get(cols, "strategy"));
                if (isUnknownStrategy(strategy)) {
                    continue;
                }
                double slippagePercent = parseDouble(h.get(cols, "slippagePercent"), 0.0);
                boolean filled = isTrue(h.get(cols, "filled"));
                stats.computeIfAbsent(strategy, ExecutionStats::new).record(slippagePercent, filled);
            }
        } catch (IOException e) {
            System.out.println("PAPER PERFORMANCE GATE EXECUTION READ FAILED: " + executionAnalyticsPath + " " + e.getMessage());
        }
        return stats;
    }

    private void writePolicy(List<Recommendation> recommendations) {
        Properties p = new Properties();
        p.setProperty("updatedAt", Instant.now().toString());
        p.setProperty("description", "Paper/shadow performance gate. Increases require paperStatus=PASSED.");
        p.setProperty("outcomePaths", outcomePaths.toString());
        p.setProperty("executionAnalyticsPath", executionAnalyticsPath == null ? "" : executionAnalyticsPath.toString());
        p.setProperty("executionCostPolicyPath", executionCostPolicyPath == null ? "" : executionCostPolicyPath.toString());
        p.setProperty("minSamples", Integer.toString(config.minSamples));
        p.setProperty("minPromoteSamples", Integer.toString(config.minPromoteSamples));
        for (Recommendation r : recommendations) {
            String prefix = "strategy." + r.strategy + ".";
            p.setProperty("strategyMultiplier." + r.strategy, fmt(r.multiplier));
            p.setProperty("paperStatus." + r.strategy, r.status);
            p.setProperty("simulationStatus." + r.strategy, "PASSED".equals(r.status) ? "PASSED" : r.status);
            p.setProperty(prefix + "reason", r.reason);
            p.setProperty(prefix + "closedTrades", Integer.toString(r.stats.closedTrades));
            p.setProperty(prefix + "dollarPnlSamples", Integer.toString(r.stats.dollarPnlSamples));
            p.setProperty(prefix + "winRate", fmt(r.stats.winRate()));
            p.setProperty(prefix + "expectancyDollars", fmt(r.stats.expectancyDollars()));
            p.setProperty(prefix + "expectancyPercent", fmt(r.stats.expectancyPercent()));
            p.setProperty(prefix + "profitFactor", fmt(r.stats.profitFactor()));
            p.setProperty(prefix + "worstDrawdownPercent", fmt(r.stats.worstDrawdownPercent));
            p.setProperty(prefix + "avgAbsSlippagePercent", fmt(r.stats.execution == null ? 0.0 : r.stats.execution.avgAbsSlippagePercent()));
            p.setProperty(prefix + "executionCostFraction", fmt(r.stats.execution == null ? 0.0 : r.stats.execution.learnedRoundTripCostFraction));
            p.setProperty(prefix + "executionFillRate", fmt(r.stats.execution == null ? 1.0 : r.stats.execution.learnedFillRate));
            p.setProperty(prefix + "executionCostDecision", r.stats.execution == null ? "" : r.stats.execution.learnedDecision);
            if (r.disabled) {
                p.setProperty("disabledStrategy." + r.strategy, "true");
            }
        }
        try {
            ensureParent(policyPath);
            try (OutputStream out = Files.newOutputStream(policyPath)) {
                p.store(out, "Paper trading performance gate policy");
            }
        } catch (IOException e) {
            System.out.println("PAPER PERFORMANCE GATE POLICY WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeReport(List<Recommendation> recommendations,
                             Map<String, StrategyStats> stats,
                             Map<String, ExecutionStats> execution) {
        try {
            ensureParent(reportPath);
            DecimalFormat df = new DecimalFormat("0.0000");
            StringBuilder b = new StringBuilder();
            b.append("PAPER TRADING PERFORMANCE GATE REPORT\n");
            b.append("generatedAt=").append(Instant.now()).append('\n');
            b.append("outcomePaths=").append(outcomePaths).append('\n');
            b.append("executionAnalyticsPath=").append(executionAnalyticsPath).append('\n');
            b.append("executionCostPolicyPath=").append(executionCostPolicyPath).append('\n');
            b.append("strategiesWithOutcomes=").append(stats.size()).append('\n');
            b.append("strategiesWithExecutionStats=").append(execution.size()).append('\n');
            b.append("policyPath=").append(policyPath).append('\n');
            b.append("noLiveOrdersPlaced=true\n");
            b.append('\n');
            b.append("strategy,status,multiplier,disabled,closedTrades,dollarPnlSamples,winRate,expectancyDollars,expectancyPercent,profitFactor,worstDrawdownPercent,avgAbsSlippagePercent,executionCostFraction,executionFillRate,executionDecision,reason\n");
            for (Recommendation r : recommendations) {
                StrategyStats s = r.stats;
                double slippage = s.execution == null ? 0.0 : s.execution.avgAbsSlippagePercent();
                double executionCost = s.execution == null ? 0.0 : s.execution.learnedRoundTripCostFraction;
                double executionFillRate = s.execution == null ? 1.0 : s.execution.learnedFillRate;
                String executionDecision = s.execution == null ? "" : s.execution.learnedDecision;
                b.append(s.strategy).append(',')
                        .append(r.status).append(',')
                        .append(df.format(r.multiplier)).append(',')
                        .append(r.disabled).append(',')
                        .append(s.closedTrades).append(',')
                        .append(s.dollarPnlSamples).append(',')
                        .append(df.format(s.winRate())).append(',')
                        .append(df.format(s.expectancyDollars())).append(',')
                        .append(df.format(s.expectancyPercent())).append(',')
                        .append(df.format(s.profitFactor())).append(',')
                        .append(df.format(s.worstDrawdownPercent)).append(',')
                        .append(df.format(slippage)).append(',')
                        .append(df.format(executionCost)).append(',')
                        .append(df.format(executionFillRate)).append(',')
                        .append(clean(executionDecision)).append(',')
                        .append(clean(r.reason))
                        .append('\n');
            }
            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("PAPER PERFORMANCE GATE REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeHealth(List<Recommendation> recommendations,
                             Map<String, StrategyStats> stats,
                             Map<String, ExecutionStats> execution) {
        try {
            ensureParent(healthPath);
            int passed = 0;
            int blocked = 0;
            int failed = 0;
            int insufficient = 0;
            for (Recommendation r : recommendations) {
                if ("PASSED".equals(r.status)) passed++;
                else if ("FAILED".equals(r.status)) failed++;
                else if ("INSUFFICIENT".equals(r.status)) insufficient++;
                else if ("BLOCKED".equals(r.status)) blocked++;
            }
            Properties p = new Properties();
            p.setProperty("status", "PASS");
            p.setProperty("generatedAt", Instant.now().toString());
            p.setProperty("policyPath", policyPath.toString());
            p.setProperty("reportPath", reportPath.toString());
            p.setProperty("strategies", Integer.toString(recommendations.size()));
            p.setProperty("outcomeStrategies", Integer.toString(stats.size()));
            p.setProperty("executionStrategies", Integer.toString(execution.size()));
            p.setProperty("passed", Integer.toString(passed));
            p.setProperty("blocked", Integer.toString(blocked));
            p.setProperty("failed", Integer.toString(failed));
            p.setProperty("insufficient", Integer.toString(insufficient));
            try (OutputStream out = Files.newOutputStream(healthPath)) {
                p.store(out, "Paper trading performance gate health");
            }
        } catch (IOException e) {
            System.out.println("PAPER PERFORMANCE GATE HEALTH WRITE FAILED: " + e.getMessage());
        }
    }

    private static void appendMetrics(StringBuilder reason, StrategyStats s, double slippage) {
        reason.append("trades=").append(s.closedTrades)
                .append(" winRate=").append(fmt(s.winRate()))
                .append(" expectancyDollars=").append(fmt(s.expectancyDollars()))
                .append(" expectancyPercent=").append(fmt(s.expectancyPercent()))
                .append(" profitFactor=").append(fmt(s.profitFactor()))
                .append(" worstDrawdownPercent=").append(fmt(s.worstDrawdownPercent))
                .append(" avgAbsSlippagePercent=").append(fmt(slippage));
        if (s.execution != null) {
            reason.append(" executionCostFraction=").append(fmt(s.execution.learnedRoundTripCostFraction))
                    .append(" executionFillRate=").append(fmt(s.execution.learnedFillRate))
                    .append(" executionCostDecision=").append(s.execution.learnedDecision);
        }
    }

    private static List<Path> parsePaths(String raw) {
        List<Path> paths = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return paths;
        }
        for (String part : raw.split("[;,]")) {
            if (part != null && !part.isBlank()) {
                paths.add(Path.of(part.trim()));
            }
        }
        return paths;
    }

    private static String defaultOutcomePaths() {
        String primary = env("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv");
        String shadow = env("SHADOW_TRADE_OUTCOME_PATH", "logs/shadow_trade_outcomes.csv");
        return primary + ";" + shadow;
    }

    private static boolean isTrainingEligible(String eventType, String strategyName, String syncedFromBroker) {
        String event = normalize(eventType);
        if (!"CLOSE".equals(event) && (!"PARTIAL_EXIT".equals(event) || !envBool("PAPER_GATE_INCLUDE_PARTIAL_EXITS", false))) {
            return false;
        }
        String strategy = normalizeStrategy(strategyName);
        if (isUnknownStrategy(strategy) && !envBool("PAPER_GATE_INCLUDE_UNKNOWN_STRATEGY", false)) {
            return false;
        }
        if (strategy.contains("PRE_TRADE_CALIBRATION_AUDIT")
                && !envBool("PAPER_GATE_INCLUDE_PRE_TRADE_CALIBRATION_AUDIT", false)) {
            return false;
        }
        return !isTrue(syncedFromBroker) || envBool("PAPER_GATE_INCLUDE_BROKER_SYNCED", false);
    }

    private static String normalizeStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        return raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static boolean isUnknownStrategy(String strategyName) {
        String strategy = normalizeStrategy(strategyName);
        return strategy.isBlank() || "UNKNOWN".equals(strategy) || "BROKER_SYNC".equals(strategy);
    }

    private static boolean isTrue(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else if (c == '"') {
                quoted = true;
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
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
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String fmt(double value) {
        if (!Double.isFinite(value)) {
            value = 0.0;
        }
        return String.format(Locale.ROOT, "%.6f", value);
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

    private static final class StrategyStats {
        final String strategy;
        int closedTrades;
        int dollarPnlSamples;
        int wins;
        int losses;
        double totalPnlDollars;
        double totalPnlPercent;
        double grossProfit;
        double grossLoss;
        double worstDrawdownPercent;
        double equity;
        double peakEquity;
        double maxEquityDrawdownDollars;
        ExecutionStats execution;

        StrategyStats(String strategy) {
            this.strategy = strategy;
        }

        void record(double pnlDollars, double pnlPercent, double drawdownPercent) {
            closedTrades++;
            if (Math.abs(pnlDollars) > 0.000001) {
                dollarPnlSamples++;
            }
            totalPnlDollars += pnlDollars;
            totalPnlPercent += pnlPercent;
            worstDrawdownPercent = Math.max(worstDrawdownPercent, Math.abs(drawdownPercent));
            double edge = Math.abs(pnlDollars) > 0.000001 ? pnlDollars : pnlPercent;
            if (edge > 0.0) {
                wins++;
                grossProfit += Math.abs(edge);
            } else if (edge < 0.0) {
                losses++;
                grossLoss += Math.abs(edge);
            }
            equity += pnlDollars;
            peakEquity = Math.max(peakEquity, equity);
            maxEquityDrawdownDollars = Math.max(maxEquityDrawdownDollars, peakEquity - equity);
        }

        double winRate() {
            return closedTrades <= 0 ? 0.0 : (double) wins / closedTrades;
        }

        double expectancyDollars() {
            return closedTrades <= 0 ? 0.0 : totalPnlDollars / closedTrades;
        }

        double expectancyPercent() {
            return closedTrades <= 0 ? 0.0 : totalPnlPercent / closedTrades;
        }

        double profitFactor() {
            if (grossLoss <= 0.0) {
                return grossProfit > 0.0 ? 99.0 : 0.0;
            }
            return grossProfit / grossLoss;
        }
    }

    private static final class ExecutionStats {
        final String strategy;
        int samples;
        int fills;
        double absSlippagePercent;
        int learnedAttempts;
        double learnedRoundTripCostFraction;
        double learnedFillRate = 1.0;
        boolean learnedBlocked;
        String learnedDecision = "";

        ExecutionStats(String strategy) {
            this.strategy = strategy;
        }

        void record(double slippagePercent, boolean filled) {
            samples++;
            if (filled) {
                fills++;
            }
            absSlippagePercent += Math.abs(slippagePercent);
        }

        double avgAbsSlippagePercent() {
            return samples <= 0 ? 0.0 : absSlippagePercent / samples;
        }
    }

    private static final class Recommendation {
        final String strategy;
        final String status;
        final double multiplier;
        final boolean disabled;
        final String reason;
        final StrategyStats stats;

        Recommendation(String strategy, String status, double multiplier, boolean disabled, String reason, StrategyStats stats) {
            this.strategy = strategy;
            this.status = status;
            this.multiplier = multiplier;
            this.disabled = disabled;
            this.reason = reason == null ? "" : reason;
            this.stats = stats;
        }

        int statusRank() {
            if ("PASSED".equals(status)) return 4;
            if ("HOLD".equals(status)) return 3;
            if ("BLOCKED".equals(status)) return 2;
            if ("FAILED".equals(status)) return 1;
            return 0;
        }
    }

    private static final class GateConfig {
        final int minSamples;
        final int minPromoteSamples;
        final int minDisableSamples;
        final double minExpectancyDollars;
        final double minExpectancyPercent;
        final double minWinRate;
        final double minProfitFactor;
        final double maxWorstDrawdownPercent;
        final double maxAvgAbsSlippagePercent;
        final double maxExecutionCostFraction;
        final double minExecutionFillRate;
        final double weakWinRate;
        final double weakProfitFactor;
        final double weakWorstDrawdownPercent;
        final double weakAvgAbsSlippagePercent;
        final double weakExecutionCostFraction;
        final double weakExecutionFillRate;
        final double disableExpectancyDollars;
        final double disableExpectancyPercent;
        final double disableWinRate;
        final double disableProfitFactor;
        final double disableExecutionCostFraction;
        final double maxPromoteMultiplier;
        final double minShrinkMultiplier;
        final double disableMultiplier;
        final boolean includeBrokerSynced;
        final Set<String> protectedDisableStrategies;

        GateConfig(int minSamples,
                   int minPromoteSamples,
                   int minDisableSamples,
                   double minExpectancyDollars,
                   double minExpectancyPercent,
                   double minWinRate,
                   double minProfitFactor,
                   double maxWorstDrawdownPercent,
                   double maxAvgAbsSlippagePercent,
                   double maxExecutionCostFraction,
                   double minExecutionFillRate,
                   double weakWinRate,
                   double weakProfitFactor,
                   double weakWorstDrawdownPercent,
                   double weakAvgAbsSlippagePercent,
                   double weakExecutionCostFraction,
                   double weakExecutionFillRate,
                   double disableExpectancyDollars,
                   double disableExpectancyPercent,
                   double disableWinRate,
                   double disableProfitFactor,
                   double disableExecutionCostFraction,
                   double maxPromoteMultiplier,
                   double minShrinkMultiplier,
                   double disableMultiplier,
                   boolean includeBrokerSynced,
                   Set<String> protectedDisableStrategies) {
            this.minSamples = Math.max(3, minSamples);
            this.minPromoteSamples = Math.max(this.minSamples, minPromoteSamples);
            this.minDisableSamples = Math.max(this.minPromoteSamples, minDisableSamples);
            this.minExpectancyDollars = minExpectancyDollars;
            this.minExpectancyPercent = minExpectancyPercent;
            this.minWinRate = minWinRate;
            this.minProfitFactor = minProfitFactor;
            this.maxWorstDrawdownPercent = maxWorstDrawdownPercent;
            this.maxAvgAbsSlippagePercent = maxAvgAbsSlippagePercent;
            this.maxExecutionCostFraction = maxExecutionCostFraction;
            this.minExecutionFillRate = minExecutionFillRate;
            this.weakWinRate = weakWinRate;
            this.weakProfitFactor = weakProfitFactor;
            this.weakWorstDrawdownPercent = weakWorstDrawdownPercent;
            this.weakAvgAbsSlippagePercent = weakAvgAbsSlippagePercent;
            this.weakExecutionCostFraction = weakExecutionCostFraction;
            this.weakExecutionFillRate = weakExecutionFillRate;
            this.disableExpectancyDollars = disableExpectancyDollars;
            this.disableExpectancyPercent = disableExpectancyPercent;
            this.disableWinRate = disableWinRate;
            this.disableProfitFactor = disableProfitFactor;
            this.disableExecutionCostFraction = disableExecutionCostFraction;
            this.maxPromoteMultiplier = maxPromoteMultiplier;
            this.minShrinkMultiplier = minShrinkMultiplier;
            this.disableMultiplier = disableMultiplier;
            this.includeBrokerSynced = includeBrokerSynced;
            this.protectedDisableStrategies = protectedDisableStrategies == null ? Set.of() : Set.copyOf(protectedDisableStrategies);
        }

        static GateConfig fromEnv() {
            Set<String> protectedStrategies = new HashSet<>();
            for (String raw : env("PAPER_GATE_PROTECTED_DISABLE_STRATEGIES",
                    "MARKET_INTELLIGENCE_AI,AI_GOVERNOR_STATE_OPPORTUNITY").split(",")) {
                if (raw != null && !raw.isBlank()) {
                    protectedStrategies.add(normalizeStrategy(raw));
                }
            }
            return new GateConfig(
                    envInt("PAPER_GATE_MIN_SAMPLES", 10),
                    envInt("PAPER_GATE_MIN_PROMOTE_SAMPLES", 20),
                    envInt("PAPER_GATE_MIN_DISABLE_SAMPLES", 30),
                    envDouble("PAPER_GATE_MIN_EXPECTANCY_DOLLARS", 0.05),
                    envDouble("PAPER_GATE_MIN_EXPECTANCY_PERCENT", 0.0005),
                    envDouble("PAPER_GATE_MIN_WIN_RATE", 0.52),
                    envDouble("PAPER_GATE_MIN_PROFIT_FACTOR", 1.20),
                    envDouble("PAPER_GATE_MAX_WORST_DRAWDOWN_PERCENT", 0.035),
                    envDouble("PAPER_GATE_MAX_AVG_ABS_SLIPPAGE_PERCENT", 0.003),
                    envDouble("PAPER_GATE_MAX_EXECUTION_COST_FRACTION", 0.0060),
                    envDouble("PAPER_GATE_MIN_EXECUTION_FILL_RATE", 0.80),
                    envDouble("PAPER_GATE_WEAK_WIN_RATE", 0.45),
                    envDouble("PAPER_GATE_WEAK_PROFIT_FACTOR", 0.90),
                    envDouble("PAPER_GATE_WEAK_WORST_DRAWDOWN_PERCENT", 0.050),
                    envDouble("PAPER_GATE_WEAK_AVG_ABS_SLIPPAGE_PERCENT", 0.006),
                    envDouble("PAPER_GATE_WEAK_EXECUTION_COST_FRACTION", 0.0100),
                    envDouble("PAPER_GATE_WEAK_EXECUTION_FILL_RATE", 0.70),
                    envDouble("PAPER_GATE_DISABLE_EXPECTANCY_DOLLARS", 0.20),
                    envDouble("PAPER_GATE_DISABLE_EXPECTANCY_PERCENT", 0.0015),
                    envDouble("PAPER_GATE_DISABLE_WIN_RATE", 0.38),
                    envDouble("PAPER_GATE_DISABLE_PROFIT_FACTOR", 0.70),
                    envDouble("PAPER_GATE_DISABLE_EXECUTION_COST_FRACTION", 0.0200),
                    envDouble("PAPER_GATE_MAX_PROMOTE_MULTIPLIER", 1.35),
                    envDouble("PAPER_GATE_MIN_SHRINK_MULTIPLIER", 0.50),
                    envDouble("PAPER_GATE_DISABLE_MULTIPLIER", 0.50),
                    envBool("PAPER_GATE_INCLUDE_BROKER_SYNCED", false),
                    protectedStrategies
            );
        }
    }

    public static final class Result {
        public final int outcomeStrategies;
        public final int executionStrategies;
        public final int recommendations;
        public final Path policyPath;
        public final Path reportPath;
        public final Path healthPath;

        Result(int outcomeStrategies, int executionStrategies, int recommendations, Path policyPath, Path reportPath, Path healthPath) {
            this.outcomeStrategies = outcomeStrategies;
            this.executionStrategies = executionStrategies;
            this.recommendations = recommendations;
            this.policyPath = policyPath;
            this.reportPath = reportPath;
            this.healthPath = healthPath;
        }

        public String summary() {
            return "outcomeStrategies=" + outcomeStrategies +
                    " executionStrategies=" + executionStrategies +
                    " recommendations=" + recommendations +
                    " policyPath=" + policyPath +
                    " reportPath=" + reportPath;
        }
    }
}
