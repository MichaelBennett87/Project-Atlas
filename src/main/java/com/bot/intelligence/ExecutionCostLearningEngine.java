package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Converts execution analytics into a policy that punishes scalps which cannot
 * overcome real fill drag.
 */
public final class ExecutionCostLearningEngine {
    private final Path analyticsPath;
    private final Path policyPath;
    private final Path reportPath;
    private final Path matrixPath;
    private final Path healthPath;
    private final Config config;

    public ExecutionCostLearningEngine() {
        this(
                Path.of(env("EXECUTION_COST_ANALYTICS_PATH",
                        env("EXECUTION_ANALYTICS_JOURNAL", "logs/execution_analytics.csv"))),
                Path.of(env("EXECUTION_COST_POLICY_PATH", "logs/execution_cost_policy.properties")),
                Path.of(env("EXECUTION_COST_REPORT_PATH", "logs/execution_cost_report.txt")),
                Path.of(env("EXECUTION_COST_MATRIX_PATH", "logs/execution_cost_matrix.csv")),
                Path.of(env("EXECUTION_COST_HEALTH_PATH", "logs/execution_cost_health.properties")),
                Config.fromEnv()
        );
    }

    ExecutionCostLearningEngine(Path analyticsPath,
                                Path policyPath,
                                Path reportPath,
                                Path matrixPath,
                                Path healthPath,
                                Config config) {
        this.analyticsPath = analyticsPath;
        this.policyPath = policyPath;
        this.reportPath = reportPath;
        this.matrixPath = matrixPath;
        this.healthPath = healthPath;
        this.config = config == null ? Config.fromEnv() : config;
    }

    public Result run() {
        Map<String, Stats> strategies = new LinkedHashMap<>();
        Map<String, Stats> tickers = new LinkedHashMap<>();
        Stats global = new Stats("GLOBAL", "GLOBAL");
        int rows = readAnalytics(strategies, tickers, global);

        List<Recommendation> strategyRecommendations = recommendations(strategies);
        List<Recommendation> tickerRecommendations = recommendations(tickers);
        Recommendation globalRecommendation = recommend(global);

        writePolicy(globalRecommendation, strategyRecommendations, tickerRecommendations, rows);
        writeMatrix(globalRecommendation, strategyRecommendations, tickerRecommendations);
        writeReport(globalRecommendation, strategyRecommendations, tickerRecommendations, rows);
        writeHealth(globalRecommendation, strategyRecommendations, tickerRecommendations, rows);

        int blocked = 0;
        int shrink = 0;
        int allow = 0;
        for (Recommendation r : combined(strategyRecommendations, tickerRecommendations, globalRecommendation)) {
            if ("BLOCK_COST".equals(r.decision)) blocked++;
            else if ("SHRINK_COST".equals(r.decision)) shrink++;
            else allow++;
        }
        return new Result(rows, strategyRecommendations.size(), tickerRecommendations.size(), allow, shrink, blocked,
                policyPath, reportPath, matrixPath, healthPath);
    }

    private int readAnalytics(Map<String, Stats> strategies, Map<String, Stats> tickers, Stats global) {
        if (analyticsPath == null || !Files.exists(analyticsPath)) {
            return 0;
        }
        int rows = 0;
        try (BufferedReader reader = Files.newBufferedReader(analyticsPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return 0;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                String strategy = normalizeStrategy(h.get(cols, "strategy"));
                String ticker = normalizeTicker(h.get(cols, "ticker"));
                if (strategy.isBlank()) strategy = "UNKNOWN";
                if (ticker.isBlank()) ticker = "UNKNOWN";
                boolean filled = isTrue(h.get(cols, "filled"));
                int requested = parseInt(h.get(cols, "requestedQty"), 0);
                int finalQty = parseInt(h.get(cols, "finalQty"), 0);
                long latencyMs = parseLong(h.get(cols, "latencyMs"), 0L);
                double slippagePercent = parseDouble(h.get(cols, "slippagePercent"), 0.0);
                record(global, filled, requested, finalQty, latencyMs, slippagePercent);
                record(strategies.computeIfAbsent(strategy, s -> new Stats("STRATEGY", s)),
                        filled, requested, finalQty, latencyMs, slippagePercent);
                record(tickers.computeIfAbsent(ticker, t -> new Stats("TICKER", t)),
                        filled, requested, finalQty, latencyMs, slippagePercent);
                rows++;
            }
        } catch (IOException e) {
            System.out.println("EXECUTION COST ANALYTICS READ FAILED: " + analyticsPath + " " + e.getMessage());
        }
        return rows;
    }

    private List<Recommendation> recommendations(Map<String, Stats> stats) {
        List<Recommendation> out = new ArrayList<>();
        for (Stats s : stats.values()) {
            out.add(recommend(s));
        }
        out.sort(Comparator
                .comparing((Recommendation r) -> r.decisionRank())
                .thenComparing((Recommendation r) -> r.stats.attempts, Comparator.reverseOrder())
                .thenComparing(r -> r.stats.key));
        return out;
    }

    private Recommendation recommend(Stats s) {
        if (s == null || s.attempts <= 0) {
            s = new Stats("GLOBAL", "GLOBAL");
        }
        double fillRate = s.fillRate();
        double avgLatency = s.avgLatencyMs();
        double avgAbsSlippage = s.avgAbsSlippagePercent();
        double avgSizeRatio = s.avgSizeFillRatio();
        double cost = roundTripCostFraction(s);
        double quality = qualityScore(s, cost);
        boolean enoughForBlock = s.attempts >= config.minBlockSamples;
        boolean enoughForShrink = s.attempts >= config.minShrinkSamples;

        String decision = "ALLOW";
        boolean blocked = false;
        if (enoughForBlock
                && (fillRate < config.blockFillRate
                || avgLatency > config.blockLatencyMs
                || cost > config.blockCostFraction)) {
            decision = "BLOCK_COST";
            blocked = true;
        } else if (enoughForShrink
                && (fillRate < config.shrinkFillRate
                || avgLatency > config.shrinkLatencyMs
                || avgSizeRatio < config.shrinkSizeRatio
                || cost > config.shrinkCostFraction)) {
            decision = "SHRINK_COST";
        }

        double sizingMultiplier;
        double priorityMultiplier;
        if (blocked) {
            sizingMultiplier = 0.0;
            priorityMultiplier = 0.0;
        } else if ("SHRINK_COST".equals(decision)) {
            sizingMultiplier = clamp(quality, config.minShrinkMultiplier, 0.90);
            priorityMultiplier = clamp(quality + 0.05, config.minPriorityMultiplier, 0.95);
        } else {
            sizingMultiplier = clamp(0.90 + quality * 0.15, 0.90, config.maxAllowMultiplier);
            priorityMultiplier = clamp(0.90 + quality * 0.12, 0.90, config.maxAllowPriorityMultiplier);
        }

        String reason = "attempts=" + s.attempts +
                " fillRate=" + fmt(fillRate) +
                " avgLatencyMs=" + fmt(avgLatency) +
                " avgAbsSlippagePercent=" + fmt(avgAbsSlippage) +
                " avgSizeFillRatio=" + fmt(avgSizeRatio) +
                " roundTripCostFraction=" + fmt(cost) +
                " qualityScore=" + fmt(quality);
        return new Recommendation(s, decision, blocked, cost, quality, sizingMultiplier, priorityMultiplier, reason);
    }

    private double roundTripCostFraction(Stats s) {
        double oneWaySlip = Math.max(config.defaultOneWayCostBps / 10_000.0, s.avgAbsSlippagePercent() / 100.0);
        double latencyPenalty = Math.max(0.0, s.avgLatencyMs() - config.latencyGraceMs)
                / 1000.0 * config.latencyPenaltyPerSecondFraction;
        latencyPenalty = Math.min(config.maxLatencyPenaltyFraction, latencyPenalty);
        double fillPenalty = Math.max(0.0, 1.0 - s.fillRate()) * config.missedFillPenaltyFraction;
        double sizePenalty = Math.max(0.0, 1.0 - s.avgSizeFillRatio()) * config.sizeShrinkPenaltyFraction;
        return Math.max(config.minRoundTripCostFraction,
                Math.min(config.maxRoundTripCostFraction, oneWaySlip * 2.0 + latencyPenalty + fillPenalty + sizePenalty));
    }

    private double qualityScore(Stats s, double cost) {
        double fillScore = s.fillRate();
        double latencyScore = 1.0 - Math.min(1.0, s.avgLatencyMs() / Math.max(1.0, config.blockLatencyMs));
        double slipScore = 1.0 - Math.min(1.0, s.avgAbsSlippagePercent() / Math.max(0.0001, config.badSlippagePercent));
        double costScore = 1.0 - Math.min(1.0, cost / Math.max(0.0001, config.blockCostFraction));
        double sizeScore = s.avgSizeFillRatio();
        return clamp(fillScore * 0.36 + latencyScore * 0.22 + slipScore * 0.16 + costScore * 0.16 + sizeScore * 0.10,
                0.0, 1.0);
    }

    private static void record(Stats stats, boolean filled, int requestedQty, int finalQty, long latencyMs, double slippagePercent) {
        if (stats == null) {
            return;
        }
        stats.attempts++;
        if (filled) {
            stats.filled++;
        }
        stats.totalRequestedQty += Math.max(0, requestedQty);
        stats.totalFinalQty += Math.max(0, finalQty);
        stats.totalLatencyMs += Math.max(0L, latencyMs);
        stats.totalAbsSlippagePercent += Math.abs(slippagePercent);
        stats.maxLatencyMs = Math.max(stats.maxLatencyMs, Math.max(0L, latencyMs));
        stats.maxAbsSlippagePercent = Math.max(stats.maxAbsSlippagePercent, Math.abs(slippagePercent));
    }

    private void writePolicy(Recommendation global,
                             List<Recommendation> strategies,
                             List<Recommendation> tickers,
                             int rows) {
        Properties p = new Properties();
        p.setProperty("source", "execution_cost_learning");
        p.setProperty("updatedAt", Instant.now().toString());
        p.setProperty("analyticsPath", analyticsPath == null ? "" : analyticsPath.toString());
        p.setProperty("reportPath", reportPath.toString());
        p.setProperty("matrixPath", matrixPath.toString());
        p.setProperty("rows", Integer.toString(rows));
        writeRecommendation(p, "global.", global);
        for (Recommendation r : strategies) {
            writeRecommendation(p, "strategy." + r.stats.key + ".", r);
        }
        for (Recommendation r : tickers) {
            writeRecommendation(p, "ticker." + r.stats.key + ".", r);
        }
        try {
            ensureParent(policyPath);
            try (OutputStream out = Files.newOutputStream(policyPath)) {
                p.store(out, "Learned execution cost policy");
            }
        } catch (IOException e) {
            System.out.println("EXECUTION COST POLICY WRITE FAILED: " + e.getMessage());
        }
    }

    private static void writeRecommendation(Properties p, String prefix, Recommendation r) {
        p.setProperty(prefix + "decision", r.decision);
        p.setProperty(prefix + "blocked", Boolean.toString(r.blocked));
        p.setProperty(prefix + "attempts", Integer.toString(r.stats.attempts));
        p.setProperty(prefix + "filled", Integer.toString(r.stats.filled));
        p.setProperty(prefix + "fillRate", fmt(r.stats.fillRate()));
        p.setProperty(prefix + "avgLatencyMs", fmt(r.stats.avgLatencyMs()));
        p.setProperty(prefix + "maxLatencyMs", Long.toString(r.stats.maxLatencyMs));
        p.setProperty(prefix + "avgAbsSlippagePercent", fmt(r.stats.avgAbsSlippagePercent()));
        p.setProperty(prefix + "maxAbsSlippagePercent", fmt(r.stats.maxAbsSlippagePercent));
        p.setProperty(prefix + "avgSizeFillRatio", fmt(r.stats.avgSizeFillRatio()));
        p.setProperty(prefix + "roundTripCostFraction", fmt(r.roundTripCostFraction));
        p.setProperty(prefix + "qualityScore", fmt(r.qualityScore));
        p.setProperty(prefix + "sizingMultiplier", fmt(r.sizingMultiplier));
        p.setProperty(prefix + "priorityMultiplier", fmt(r.priorityMultiplier));
        p.setProperty(prefix + "reason", r.reason);
    }

    private void writeMatrix(Recommendation global,
                             List<Recommendation> strategies,
                             List<Recommendation> tickers) {
        try {
            ensureParent(matrixPath);
            StringBuilder b = new StringBuilder();
            b.append("scope,key,decision,blocked,attempts,filled,fillRate,avgLatencyMs,maxLatencyMs,avgAbsSlippagePercent,maxAbsSlippagePercent,avgSizeFillRatio,roundTripCostFraction,qualityScore,sizingMultiplier,priorityMultiplier,reason\n");
            appendMatrix(b, global);
            for (Recommendation r : strategies) appendMatrix(b, r);
            for (Recommendation r : tickers) appendMatrix(b, r);
            Files.writeString(matrixPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("EXECUTION COST MATRIX WRITE FAILED: " + e.getMessage());
        }
    }

    private static void appendMatrix(StringBuilder b, Recommendation r) {
        b.append(csv(r.stats.scope)).append(',')
                .append(csv(r.stats.key)).append(',')
                .append(csv(r.decision)).append(',')
                .append(r.blocked).append(',')
                .append(r.stats.attempts).append(',')
                .append(r.stats.filled).append(',')
                .append(fmt(r.stats.fillRate())).append(',')
                .append(fmt(r.stats.avgLatencyMs())).append(',')
                .append(r.stats.maxLatencyMs).append(',')
                .append(fmt(r.stats.avgAbsSlippagePercent())).append(',')
                .append(fmt(r.stats.maxAbsSlippagePercent)).append(',')
                .append(fmt(r.stats.avgSizeFillRatio())).append(',')
                .append(fmt(r.roundTripCostFraction)).append(',')
                .append(fmt(r.qualityScore)).append(',')
                .append(fmt(r.sizingMultiplier)).append(',')
                .append(fmt(r.priorityMultiplier)).append(',')
                .append(csv(r.reason))
                .append('\n');
    }

    private void writeReport(Recommendation global,
                             List<Recommendation> strategies,
                             List<Recommendation> tickers,
                             int rows) {
        try {
            ensureParent(reportPath);
            DecimalFormat df = new DecimalFormat("0.0000");
            StringBuilder b = new StringBuilder();
            b.append("EXECUTION COST LEARNING REPORT\n");
            b.append("generatedAt=").append(Instant.now()).append('\n');
            b.append("analyticsPath=").append(analyticsPath).append('\n');
            b.append("policyPath=").append(policyPath).append('\n');
            b.append("matrixPath=").append(matrixPath).append('\n');
            b.append("rows=").append(rows).append('\n');
            b.append("globalDecision=").append(global.decision).append('\n');
            b.append("globalRoundTripCostFraction=").append(df.format(global.roundTripCostFraction)).append('\n');
            b.append("globalFillRate=").append(df.format(global.stats.fillRate())).append('\n');
            b.append("globalAvgLatencyMs=").append(df.format(global.stats.avgLatencyMs())).append('\n');
            b.append("noLiveOrdersPlaced=true\n\n");
            b.append("STRATEGY COST POLICY\n");
            for (Recommendation r : strategies) {
                b.append("- ").append(r.stats.key)
                        .append(" decision=").append(r.decision)
                        .append(" attempts=").append(r.stats.attempts)
                        .append(" fillRate=").append(df.format(r.stats.fillRate()))
                        .append(" avgLatencyMs=").append(df.format(r.stats.avgLatencyMs()))
                        .append(" costFraction=").append(df.format(r.roundTripCostFraction))
                        .append(" sizing=").append(df.format(r.sizingMultiplier))
                        .append(" priority=").append(df.format(r.priorityMultiplier))
                        .append('\n');
            }
            b.append("\nWORST TICKER EXECUTION COSTS\n");
            tickers.stream()
                    .sorted(Comparator.comparingDouble((Recommendation r) -> r.roundTripCostFraction).reversed())
                    .limit(40)
                    .forEach(r -> b.append("- ").append(r.stats.key)
                            .append(" decision=").append(r.decision)
                            .append(" attempts=").append(r.stats.attempts)
                            .append(" fillRate=").append(df.format(r.stats.fillRate()))
                            .append(" avgLatencyMs=").append(df.format(r.stats.avgLatencyMs()))
                            .append(" costFraction=").append(df.format(r.roundTripCostFraction))
                            .append('\n'));
            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("EXECUTION COST REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeHealth(Recommendation global,
                             List<Recommendation> strategies,
                             List<Recommendation> tickers,
                             int rows) {
        try {
            ensureParent(healthPath);
            int blocked = 0;
            int shrink = 0;
            int allow = 0;
            for (Recommendation r : combined(strategies, tickers, global)) {
                if ("BLOCK_COST".equals(r.decision)) blocked++;
                else if ("SHRINK_COST".equals(r.decision)) shrink++;
                else allow++;
            }
            Properties p = new Properties();
            p.setProperty("status", "PASS");
            p.setProperty("generatedAt", Instant.now().toString());
            p.setProperty("analyticsPath", analyticsPath == null ? "" : analyticsPath.toString());
            p.setProperty("policyPath", policyPath.toString());
            p.setProperty("reportPath", reportPath.toString());
            p.setProperty("matrixPath", matrixPath.toString());
            p.setProperty("rows", Integer.toString(rows));
            p.setProperty("strategies", Integer.toString(strategies.size()));
            p.setProperty("tickers", Integer.toString(tickers.size()));
            p.setProperty("allow", Integer.toString(allow));
            p.setProperty("shrink", Integer.toString(shrink));
            p.setProperty("blocked", Integer.toString(blocked));
            p.setProperty("globalRoundTripCostFraction", fmt(global.roundTripCostFraction));
            p.setProperty("globalFillRate", fmt(global.stats.fillRate()));
            p.setProperty("globalAvgLatencyMs", fmt(global.stats.avgLatencyMs()));
            try (OutputStream out = Files.newOutputStream(healthPath)) {
                p.store(out, "Execution cost learning health");
            }
        } catch (IOException e) {
            System.out.println("EXECUTION COST HEALTH WRITE FAILED: " + e.getMessage());
        }
    }

    private static List<Recommendation> combined(List<Recommendation> strategies,
                                                 List<Recommendation> tickers,
                                                 Recommendation global) {
        List<Recommendation> out = new ArrayList<>();
        if (global != null) out.add(global);
        if (strategies != null) out.addAll(strategies);
        if (tickers != null) out.addAll(tickers);
        return out;
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) return out;
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

    private static String csv(String raw) {
        String value = raw == null ? "" : raw;
        if (!value.contains(",") && !value.contains("\"") && !value.contains("\n") && !value.contains("\r")) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static boolean isTrue(String raw) {
        if (raw == null) return false;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.equals("true") || value.equals("1") || value.equals("yes") || value.equals("on");
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String normalizeStrategy(String raw) {
        return raw == null || raw.isBlank() ? "" : raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String normalizeTicker(String raw) {
        return raw == null || raw.isBlank() ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
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

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.6f", Double.isFinite(value) ? value : 0.0);
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

    private static final class Stats {
        final String scope;
        final String key;
        int attempts;
        int filled;
        long totalLatencyMs;
        long maxLatencyMs;
        int totalRequestedQty;
        int totalFinalQty;
        double totalAbsSlippagePercent;
        double maxAbsSlippagePercent;

        Stats(String scope, String key) {
            this.scope = scope;
            this.key = key == null || key.isBlank() ? "UNKNOWN" : key;
        }

        double fillRate() {
            return attempts <= 0 ? 1.0 : filled * 1.0 / attempts;
        }

        double avgLatencyMs() {
            return attempts <= 0 ? 0.0 : totalLatencyMs * 1.0 / attempts;
        }

        double avgAbsSlippagePercent() {
            return attempts <= 0 ? 0.0 : totalAbsSlippagePercent / attempts;
        }

        double avgSizeFillRatio() {
            if (totalRequestedQty <= 0) {
                return 1.0;
            }
            return Math.max(0.0, Math.min(1.0, totalFinalQty * 1.0 / totalRequestedQty));
        }
    }

    private static final class Recommendation {
        final Stats stats;
        final String decision;
        final boolean blocked;
        final double roundTripCostFraction;
        final double qualityScore;
        final double sizingMultiplier;
        final double priorityMultiplier;
        final String reason;

        Recommendation(Stats stats,
                       String decision,
                       boolean blocked,
                       double roundTripCostFraction,
                       double qualityScore,
                       double sizingMultiplier,
                       double priorityMultiplier,
                       String reason) {
            this.stats = stats;
            this.decision = decision;
            this.blocked = blocked;
            this.roundTripCostFraction = roundTripCostFraction;
            this.qualityScore = qualityScore;
            this.sizingMultiplier = sizingMultiplier;
            this.priorityMultiplier = priorityMultiplier;
            this.reason = reason == null ? "" : reason;
        }

        int decisionRank() {
            if ("BLOCK_COST".equals(decision)) return 0;
            if ("SHRINK_COST".equals(decision)) return 1;
            return 2;
        }
    }

    static final class Config {
        final int minShrinkSamples;
        final int minBlockSamples;
        final double defaultOneWayCostBps;
        final double minRoundTripCostFraction;
        final double maxRoundTripCostFraction;
        final double latencyGraceMs;
        final double latencyPenaltyPerSecondFraction;
        final double maxLatencyPenaltyFraction;
        final double missedFillPenaltyFraction;
        final double sizeShrinkPenaltyFraction;
        final double shrinkFillRate;
        final double blockFillRate;
        final double shrinkLatencyMs;
        final double blockLatencyMs;
        final double shrinkCostFraction;
        final double blockCostFraction;
        final double shrinkSizeRatio;
        final double badSlippagePercent;
        final double minShrinkMultiplier;
        final double minPriorityMultiplier;
        final double maxAllowMultiplier;
        final double maxAllowPriorityMultiplier;

        Config(int minShrinkSamples,
               int minBlockSamples,
               double defaultOneWayCostBps,
               double minRoundTripCostFraction,
               double maxRoundTripCostFraction,
               double latencyGraceMs,
               double latencyPenaltyPerSecondFraction,
               double maxLatencyPenaltyFraction,
               double missedFillPenaltyFraction,
               double sizeShrinkPenaltyFraction,
               double shrinkFillRate,
               double blockFillRate,
               double shrinkLatencyMs,
               double blockLatencyMs,
               double shrinkCostFraction,
               double blockCostFraction,
               double shrinkSizeRatio,
               double badSlippagePercent,
               double minShrinkMultiplier,
               double minPriorityMultiplier,
               double maxAllowMultiplier,
               double maxAllowPriorityMultiplier) {
            this.minShrinkSamples = Math.max(1, minShrinkSamples);
            this.minBlockSamples = Math.max(this.minShrinkSamples, minBlockSamples);
            this.defaultOneWayCostBps = Math.max(0.0, defaultOneWayCostBps);
            this.minRoundTripCostFraction = Math.max(0.0, minRoundTripCostFraction);
            this.maxRoundTripCostFraction = Math.max(this.minRoundTripCostFraction, maxRoundTripCostFraction);
            this.latencyGraceMs = Math.max(0.0, latencyGraceMs);
            this.latencyPenaltyPerSecondFraction = Math.max(0.0, latencyPenaltyPerSecondFraction);
            this.maxLatencyPenaltyFraction = Math.max(0.0, maxLatencyPenaltyFraction);
            this.missedFillPenaltyFraction = Math.max(0.0, missedFillPenaltyFraction);
            this.sizeShrinkPenaltyFraction = Math.max(0.0, sizeShrinkPenaltyFraction);
            this.shrinkFillRate = clamp(shrinkFillRate, 0.0, 1.0);
            this.blockFillRate = clamp(blockFillRate, 0.0, this.shrinkFillRate);
            this.shrinkLatencyMs = Math.max(1.0, shrinkLatencyMs);
            this.blockLatencyMs = Math.max(this.shrinkLatencyMs, blockLatencyMs);
            this.shrinkCostFraction = Math.max(0.0, shrinkCostFraction);
            this.blockCostFraction = Math.max(this.shrinkCostFraction, blockCostFraction);
            this.shrinkSizeRatio = clamp(shrinkSizeRatio, 0.0, 1.0);
            this.badSlippagePercent = Math.max(0.0001, badSlippagePercent);
            this.minShrinkMultiplier = clamp(minShrinkMultiplier, 0.05, 1.0);
            this.minPriorityMultiplier = clamp(minPriorityMultiplier, 0.05, 1.0);
            this.maxAllowMultiplier = clamp(maxAllowMultiplier, 1.0, 1.50);
            this.maxAllowPriorityMultiplier = clamp(maxAllowPriorityMultiplier, 1.0, 1.50);
        }

        static Config fromEnv() {
            return new Config(
                    envInt("EXECUTION_COST_MIN_SHRINK_SAMPLES", 5),
                    envInt("EXECUTION_COST_MIN_BLOCK_SAMPLES", 12),
                    envDouble("EXECUTION_COST_DEFAULT_ONE_WAY_BPS", 6.0),
                    envDouble("EXECUTION_COST_MIN_ROUND_TRIP_FRACTION", 0.0008),
                    envDouble("EXECUTION_COST_MAX_ROUND_TRIP_FRACTION", 0.0500),
                    envLong("EXECUTION_COST_LATENCY_GRACE_MS", 1_500L),
                    envDouble("EXECUTION_COST_LATENCY_PENALTY_PER_SECOND", 0.00015),
                    envDouble("EXECUTION_COST_MAX_LATENCY_PENALTY", 0.0120),
                    envDouble("EXECUTION_COST_MISSED_FILL_PENALTY", 0.0060),
                    envDouble("EXECUTION_COST_SIZE_SHRINK_PENALTY", 0.0030),
                    envDouble("EXECUTION_COST_SHRINK_FILL_RATE", 0.82),
                    envDouble("EXECUTION_COST_BLOCK_FILL_RATE", 0.55),
                    envLong("EXECUTION_COST_SHRINK_LATENCY_MS", 8_000L),
                    envLong("EXECUTION_COST_BLOCK_LATENCY_MS", 25_000L),
                    envDouble("EXECUTION_COST_SHRINK_COST_FRACTION", 0.0100),
                    envDouble("EXECUTION_COST_BLOCK_COST_FRACTION", 0.0250),
                    envDouble("EXECUTION_COST_SHRINK_SIZE_RATIO", 0.55),
                    envDouble("EXECUTION_COST_BAD_SLIPPAGE_PERCENT", 0.75),
                    envDouble("EXECUTION_COST_MIN_SHRINK_MULTIPLIER", 0.35),
                    envDouble("EXECUTION_COST_MIN_PRIORITY_MULTIPLIER", 0.30),
                    envDouble("EXECUTION_COST_MAX_ALLOW_MULTIPLIER", 1.05),
                    envDouble("EXECUTION_COST_MAX_ALLOW_PRIORITY_MULTIPLIER", 1.03)
            );
        }
    }

    public static final class Result {
        public final int rows;
        public final int strategies;
        public final int tickers;
        public final int allow;
        public final int shrink;
        public final int blocked;
        public final Path policyPath;
        public final Path reportPath;
        public final Path matrixPath;
        public final Path healthPath;

        Result(int rows,
               int strategies,
               int tickers,
               int allow,
               int shrink,
               int blocked,
               Path policyPath,
               Path reportPath,
               Path matrixPath,
               Path healthPath) {
            this.rows = rows;
            this.strategies = strategies;
            this.tickers = tickers;
            this.allow = allow;
            this.shrink = shrink;
            this.blocked = blocked;
            this.policyPath = policyPath;
            this.reportPath = reportPath;
            this.matrixPath = matrixPath;
            this.healthPath = healthPath;
        }

        public String summary() {
            return "rows=" + rows +
                    " strategies=" + strategies +
                    " tickers=" + tickers +
                    " allow=" + allow +
                    " shrink=" + shrink +
                    " blocked=" + blocked +
                    " policyPath=" + policyPath +
                    " reportPath=" + reportPath;
        }
    }
}
