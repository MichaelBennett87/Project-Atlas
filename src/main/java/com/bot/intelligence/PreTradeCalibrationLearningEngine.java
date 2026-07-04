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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Learns whether stated confidence has been honest before orders are allowed.
 *
 * This is deliberately conservative: it only blocks when a bucket has enough
 * evidence and both calibration and realized performance are poor. Otherwise it
 * shrinks confidence, expected move, and size.
 */
public final class PreTradeCalibrationLearningEngine {
    private final Path predictionOutcomesPath;
    private final List<Path> simulationTradePaths;
    private final Path policyPath;
    private final Path reportPath;
    private final Path matrixPath;
    private final Path healthPath;
    private final Config config;

    public PreTradeCalibrationLearningEngine() {
        this(
                Path.of(env("PRE_TRADE_CALIBRATION_PREDICTION_OUTCOMES_PATH",
                        env("PREDICTION_OUTCOME_JOURNAL_PATH", "logs/prediction_outcomes.csv"))),
                parsePaths(env("PRE_TRADE_CALIBRATION_SIMULATION_TRADE_PATHS", defaultSimulationTradePaths())),
                Path.of(env("PRE_TRADE_CALIBRATION_POLICY_PATH", "logs/pre_trade_calibration_policy.properties")),
                Path.of(env("PRE_TRADE_CALIBRATION_REPORT_PATH", "logs/pre_trade_calibration_report.txt")),
                Path.of(env("PRE_TRADE_CALIBRATION_MATRIX_PATH", "logs/pre_trade_calibration_matrix.csv")),
                Path.of(env("PRE_TRADE_CALIBRATION_HEALTH_PATH", "logs/pre_trade_calibration_health.properties")),
                Config.fromEnv()
        );
    }

    PreTradeCalibrationLearningEngine(Path predictionOutcomesPath,
                                      List<Path> simulationTradePaths,
                                      Path policyPath,
                                      Path reportPath,
                                      Path matrixPath,
                                      Path healthPath,
                                      Config config) {
        this.predictionOutcomesPath = predictionOutcomesPath;
        this.simulationTradePaths = simulationTradePaths == null ? List.of() : List.copyOf(simulationTradePaths);
        this.policyPath = policyPath;
        this.reportPath = reportPath;
        this.matrixPath = matrixPath;
        this.healthPath = healthPath;
        this.config = config == null ? Config.fromEnv() : config;
    }

    public Result run() {
        Map<String, BucketStats> stats = new LinkedHashMap<>();
        int predictionRows = readPredictionOutcomes(stats);
        int simulationRows = readSimulationTrades(stats);

        List<Recommendation> recommendations = new ArrayList<>();
        for (BucketStats s : stats.values()) {
            if (s.samples > 0) {
                recommendations.add(recommend(s));
            }
        }
        recommendations.sort(Comparator
                .comparing((Recommendation r) -> r.scopeRank()).reversed()
                .thenComparing((Recommendation r) -> r.decisionRank())
                .thenComparing((Recommendation r) -> r.stats.samples, Comparator.reverseOrder())
                .thenComparing(r -> r.stats.profileKey()));

        writePolicy(recommendations, predictionRows, simulationRows);
        writeMatrix(recommendations);
        writeReport(recommendations, predictionRows, simulationRows);
        writeHealth(recommendations, predictionRows, simulationRows);

        int blocked = 0;
        int shrink = 0;
        int allow = 0;
        int boost = 0;
        for (Recommendation r : recommendations) {
            if ("BLOCK_CALIBRATION".equals(r.decision)) blocked++;
            else if ("SHRINK_CALIBRATION".equals(r.decision)) shrink++;
            else if ("BOOST_CALIBRATION".equals(r.decision)) boost++;
            else allow++;
        }
        return new Result(recommendations.size(), predictionRows, simulationRows, allow, shrink, blocked, boost,
                policyPath, reportPath, matrixPath, healthPath);
    }

    private int readPredictionOutcomes(Map<String, BucketStats> stats) {
        if (predictionOutcomesPath == null || !Files.exists(predictionOutcomesPath)) {
            return 0;
        }
        int rows = 0;
        try (BufferedReader reader = Files.newBufferedReader(predictionOutcomesPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return 0;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                String event = upper(h.get(cols, "eventType"));
                if (!"CLOSE".equals(event) && (!config.includePartialExits || !"PARTIAL_EXIT".equals(event))) {
                    continue;
                }
                if (isTrue(h.get(cols, "syncedFromBroker"))) {
                    continue;
                }
                String strategy = normalizeStrategy(h.get(cols, "strategyName"));
                if (isUnknownStrategy(strategy)) {
                    continue;
                }
                String regime = normalizeRegime(h.get(cols, "entryMarketRegime"));
                double predicted = parseDouble(h.get(cols, "pTarget"), 0.0);
                double modelConfidence = parseDouble(h.get(cols, "predictionConfidence"), 0.0);
                if (predicted <= 0.0) {
                    predicted = modelConfidence;
                }
                predicted = clamp(predicted, 0.0, 1.0);
                double pnlDollars = parseDouble(h.get(cols, "realizedPnlDollars"), 0.0);
                double pnlPercent = parseDouble(h.get(cols, "currentPnlPercent"), 0.0);
                double expectedMove = parseDouble(h.get(cols, "expectedValuePercent"), 0.0);
                recordAll(stats, strategy, regime, predicted, expectedMove, pnlDollars, pnlPercent, "prediction_outcome");
                rows++;
            }
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION PREDICTION READ FAILED: " + predictionOutcomesPath + " " + e.getMessage());
        }
        return rows;
    }

    private int readSimulationTrades(Map<String, BucketStats> stats) {
        int rows = 0;
        for (Path path : simulationTradePaths) {
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
                    String strategy = normalizeStrategy(h.get(cols, "strategy"));
                    if (isUnknownStrategy(strategy)) {
                        continue;
                    }
                    double predicted = clamp(parseDouble(h.get(cols, "confidence"), 0.0), 0.0, 1.0);
                    if (predicted <= 0.0) {
                        continue;
                    }
                    double expectedMove = parseDouble(h.get(cols, "expectedMove"), 0.0);
                    double pnlDollars = parseDouble(h.get(cols, "pnlDollars"), 0.0);
                    double pnlPercent = parseDouble(h.get(cols, "pnlPercent"), 0.0);
                    recordAll(stats, strategy, "UNKNOWN", predicted, expectedMove, pnlDollars, pnlPercent,
                            path.getFileName() == null ? "simulation" : path.getFileName().toString());
                    rows++;
                }
            } catch (IOException e) {
                System.out.println("PRE TRADE CALIBRATION SIMULATION READ FAILED: " + path + " " + e.getMessage());
            }
        }
        return rows;
    }

    private void recordAll(Map<String, BucketStats> stats,
                           String strategy,
                           String regime,
                           double predicted,
                           double expectedMove,
                           double pnlDollars,
                           double pnlPercent,
                           String source) {
        String cleanStrategy = normalizeStrategy(strategy);
        String cleanRegime = normalizeRegime(regime);
        String bucket = confidenceBucket(predicted);
        record(stats, "GLOBAL", "ANY", "ANY", "ANY", predicted, expectedMove, pnlDollars, pnlPercent, source);
        record(stats, "CONFIDENCE", "ANY", "ANY", bucket, predicted, expectedMove, pnlDollars, pnlPercent, source);
        record(stats, "STRATEGY", cleanStrategy, "ANY", "ANY", predicted, expectedMove, pnlDollars, pnlPercent, source);
        record(stats, "STRATEGY_CONFIDENCE", cleanStrategy, "ANY", bucket, predicted, expectedMove, pnlDollars, pnlPercent, source);
        if (!"UNKNOWN".equals(cleanRegime) && !"ANY".equals(cleanRegime)) {
            record(stats, "REGIME", "ANY", cleanRegime, "ANY", predicted, expectedMove, pnlDollars, pnlPercent, source);
            record(stats, "REGIME_CONFIDENCE", "ANY", cleanRegime, bucket, predicted, expectedMove, pnlDollars, pnlPercent, source);
            record(stats, "STRATEGY_REGIME", cleanStrategy, cleanRegime, "ANY", predicted, expectedMove, pnlDollars, pnlPercent, source);
            record(stats, "STRATEGY_REGIME_CONFIDENCE", cleanStrategy, cleanRegime, bucket, predicted, expectedMove, pnlDollars, pnlPercent, source);
        }
    }

    private void record(Map<String, BucketStats> stats,
                        String scope,
                        String strategy,
                        String regime,
                        String confidenceBucket,
                        double predicted,
                        double expectedMove,
                        double pnlDollars,
                        double pnlPercent,
                        String source) {
        String key = profileKey(scope, strategy, regime, confidenceBucket);
        stats.computeIfAbsent(key, ignored -> new BucketStats(scope, strategy, regime, confidenceBucket))
                .add(predicted, expectedMove, pnlDollars, pnlPercent, source);
    }

    private Recommendation recommend(BucketStats s) {
        if (s.samples < config.minSamples) {
            return new Recommendation(s, "INSUFFICIENT", 1.0, 1.0, 1.0, false,
                    "sample_count_below_gate samples=" + s.samples + " minSamples=" + config.minSamples);
        }
        double winRate = s.winRate();
        double avgPredicted = s.avgPredicted();
        double calibrationError = s.calibrationError();
        double overconfidence = Math.max(0.0, avgPredicted - winRate);
        double expectancyPercent = s.expectancyPercent();
        double profitFactor = s.profitFactor();
        boolean enoughToBlock = s.samples >= config.minBlockSamples;
        boolean severe = enoughToBlock
                && expectancyPercent <= -Math.abs(config.blockExpectancyPercent)
                && winRate <= config.blockWinRate
                && profitFactor <= config.blockProfitFactor
                && overconfidence >= config.blockOverconfidence;
        if (severe) {
            return new Recommendation(s, "BLOCK_CALIBRATION", 0.0, 0.0, 0.0, true,
                    "pre_trade_calibration_block " + metrics(s));
        }

        boolean weak = s.samples >= config.minShrinkSamples
                && (expectancyPercent < config.minExpectancyPercent
                || profitFactor < config.minProfitFactor
                || overconfidence > config.maxToleratedOverconfidence
                || winRate < config.minWinRate);
        if (weak) {
            double penalty = 0.0;
            if (expectancyPercent < 0.0) penalty += 0.16;
            if (profitFactor < config.minProfitFactor) penalty += 0.10;
            if (winRate < config.minWinRate) penalty += 0.08;
            penalty += clamp(overconfidence * 0.85, 0.0, 0.28);
            double sizing = clamp(1.0 - penalty, config.minShrinkMultiplier, 0.95);
            double confidence = clamp(1.0 - overconfidence * 0.70, config.minConfidenceMultiplier, 1.0);
            double expectedMove = clamp(1.0 - Math.max(0.0, -expectancyPercent) * 12.0 - overconfidence * 0.25,
                    config.minExpectedMoveMultiplier, 1.0);
            return new Recommendation(s, "SHRINK_CALIBRATION", sizing, confidence, expectedMove, false,
                    "pre_trade_calibration_shrink " + metrics(s));
        }

        boolean strong = s.samples >= config.minBoostSamples
                && expectancyPercent >= config.boostExpectancyPercent
                && profitFactor >= config.boostProfitFactor
                && winRate >= avgPredicted - config.boostCalibrationTolerance;
        if (strong) {
            double edge = clamp((profitFactor - config.boostProfitFactor) * 0.04, 0.0, 0.06)
                    + clamp((expectancyPercent - config.boostExpectancyPercent) * 10.0, 0.0, 0.04);
            double multiplier = clamp(1.0 + edge, 1.0, config.maxBoostMultiplier);
            return new Recommendation(s, "BOOST_CALIBRATION", multiplier, Math.min(multiplier, 1.05),
                    Math.min(multiplier, 1.06), false,
                    "pre_trade_calibration_confirmed " + metrics(s));
        }

        return new Recommendation(s, "ALLOW_CALIBRATION", 1.0, 1.0, 1.0, false,
                "pre_trade_calibration_neutral " + metrics(s));
    }

    private String metrics(BucketStats s) {
        return "samples=" + s.samples +
                " avgPredicted=" + fmt(s.avgPredicted()) +
                " winRate=" + fmt(s.winRate()) +
                " calibrationError=" + fmt(s.calibrationError()) +
                " expectancyPercent=" + fmt(s.expectancyPercent()) +
                " profitFactor=" + fmt(s.profitFactor());
    }

    private void writePolicy(List<Recommendation> recommendations, int predictionRows, int simulationRows) {
        Properties p = new Properties();
        p.setProperty("updatedAt", Instant.now().toString());
        p.setProperty("description", "Pre-trade confidence calibration policy. Live gate blocks or shrinks overconfident setups before execution.");
        p.setProperty("predictionOutcomesPath", predictionOutcomesPath == null ? "" : predictionOutcomesPath.toString());
        p.setProperty("simulationTradePaths", simulationTradePaths.toString());
        p.setProperty("predictionRows", Integer.toString(predictionRows));
        p.setProperty("simulationRows", Integer.toString(simulationRows));
        p.setProperty("profiles", Integer.toString(recommendations.size()));
        p.setProperty("minSamples", Integer.toString(config.minSamples));
        p.setProperty("minShrinkSamples", Integer.toString(config.minShrinkSamples));
        p.setProperty("minBlockSamples", Integer.toString(config.minBlockSamples));
        int i = 0;
        for (Recommendation r : recommendations) {
            BucketStats s = r.stats;
            String id = "p" + (++i);
            String prefix = "profile." + id + ".";
            p.setProperty(prefix + "scope", s.scope);
            p.setProperty(prefix + "strategy", s.strategy);
            p.setProperty(prefix + "regime", s.regime);
            p.setProperty(prefix + "confidenceBucket", s.confidenceBucket);
            p.setProperty(prefix + "decision", r.decision);
            p.setProperty(prefix + "blocked", Boolean.toString(r.blocked));
            p.setProperty(prefix + "samples", Integer.toString(s.samples));
            p.setProperty(prefix + "wins", Integer.toString(s.wins));
            p.setProperty(prefix + "avgPredictedConfidence", fmt(s.avgPredicted()));
            p.setProperty(prefix + "avgExpectedMove", fmt(s.avgExpectedMove()));
            p.setProperty(prefix + "actualWinRate", fmt(s.winRate()));
            p.setProperty(prefix + "calibrationError", fmt(s.calibrationError()));
            p.setProperty(prefix + "expectancyDollars", fmt(s.expectancyDollars()));
            p.setProperty(prefix + "expectancyPercent", fmt(s.expectancyPercent()));
            p.setProperty(prefix + "profitFactor", fmt(s.profitFactor()));
            p.setProperty(prefix + "sizingMultiplier", fmt(r.sizingMultiplier));
            p.setProperty(prefix + "confidenceMultiplier", fmt(r.confidenceMultiplier));
            p.setProperty(prefix + "expectedMoveMultiplier", fmt(r.expectedMoveMultiplier));
            p.setProperty(prefix + "reason", r.reason);
            p.setProperty(prefix + "sources", s.sourceSummary());
        }
        try {
            ensureParent(policyPath);
            try (OutputStream out = Files.newOutputStream(policyPath)) {
                p.store(out, "Pre-trade confidence calibration policy");
            }
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION POLICY WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeMatrix(List<Recommendation> recommendations) {
        try {
            ensureParent(matrixPath);
            StringBuilder b = new StringBuilder();
            b.append("scope,strategy,regime,confidenceBucket,decision,blocked,samples,wins,avgPredictedConfidence,actualWinRate,calibrationError,expectancyDollars,expectancyPercent,profitFactor,sizingMultiplier,confidenceMultiplier,expectedMoveMultiplier,sources,reason\n");
            for (Recommendation r : recommendations) {
                BucketStats s = r.stats;
                b.append(csv(s.scope)).append(',')
                        .append(csv(s.strategy)).append(',')
                        .append(csv(s.regime)).append(',')
                        .append(csv(s.confidenceBucket)).append(',')
                        .append(csv(r.decision)).append(',')
                        .append(r.blocked).append(',')
                        .append(s.samples).append(',')
                        .append(s.wins).append(',')
                        .append(fmt(s.avgPredicted())).append(',')
                        .append(fmt(s.winRate())).append(',')
                        .append(fmt(s.calibrationError())).append(',')
                        .append(fmt(s.expectancyDollars())).append(',')
                        .append(fmt(s.expectancyPercent())).append(',')
                        .append(fmt(s.profitFactor())).append(',')
                        .append(fmt(r.sizingMultiplier)).append(',')
                        .append(fmt(r.confidenceMultiplier)).append(',')
                        .append(fmt(r.expectedMoveMultiplier)).append(',')
                        .append(csv(s.sourceSummary())).append(',')
                        .append(csv(r.reason)).append('\n');
            }
            Files.writeString(matrixPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION MATRIX WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeReport(List<Recommendation> recommendations, int predictionRows, int simulationRows) {
        try {
            ensureParent(reportPath);
            DecimalFormat df = new DecimalFormat("0.0000");
            StringBuilder b = new StringBuilder();
            b.append("PRE-TRADE CALIBRATION REPORT\n");
            b.append("generatedAt=").append(Instant.now()).append('\n');
            b.append("predictionRows=").append(predictionRows).append('\n');
            b.append("simulationRows=").append(simulationRows).append('\n');
            b.append("policyPath=").append(policyPath).append('\n');
            b.append("matrixPath=").append(matrixPath).append('\n');
            b.append('\n');
            b.append("scope,strategy,regime,confidenceBucket,decision,samples,winRate,avgPredicted,expectancyPercent,profitFactor,sizing,reason\n");
            for (Recommendation r : recommendations) {
                BucketStats s = r.stats;
                b.append(s.scope).append(',')
                        .append(s.strategy).append(',')
                        .append(s.regime).append(',')
                        .append(s.confidenceBucket).append(',')
                        .append(r.decision).append(',')
                        .append(s.samples).append(',')
                        .append(df.format(s.winRate())).append(',')
                        .append(df.format(s.avgPredicted())).append(',')
                        .append(df.format(s.expectancyPercent())).append(',')
                        .append(df.format(s.profitFactor())).append(',')
                        .append(df.format(r.sizingMultiplier)).append(',')
                        .append(clean(r.reason)).append('\n');
            }
            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeHealth(List<Recommendation> recommendations, int predictionRows, int simulationRows) {
        Properties p = new Properties();
        int blocked = 0;
        int shrink = 0;
        int allow = 0;
        int boost = 0;
        for (Recommendation r : recommendations) {
            if ("BLOCK_CALIBRATION".equals(r.decision)) blocked++;
            else if ("SHRINK_CALIBRATION".equals(r.decision)) shrink++;
            else if ("BOOST_CALIBRATION".equals(r.decision)) boost++;
            else allow++;
        }
        p.setProperty("status", "PASS");
        p.setProperty("generatedAt", Instant.now().toString());
        p.setProperty("predictionRows", Integer.toString(predictionRows));
        p.setProperty("simulationRows", Integer.toString(simulationRows));
        p.setProperty("profiles", Integer.toString(recommendations.size()));
        p.setProperty("allow", Integer.toString(allow));
        p.setProperty("shrink", Integer.toString(shrink));
        p.setProperty("blocked", Integer.toString(blocked));
        p.setProperty("boost", Integer.toString(boost));
        p.setProperty("policyPath", policyPath.toString());
        p.setProperty("reportPath", reportPath.toString());
        p.setProperty("matrixPath", matrixPath.toString());
        try {
            ensureParent(healthPath);
            try (OutputStream out = Files.newOutputStream(healthPath)) {
                p.store(out, "Pre-trade confidence calibration health");
            }
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION HEALTH WRITE FAILED: " + e.getMessage());
        }
    }

    private static String profileKey(String scope, String strategy, String regime, String confidenceBucket) {
        return normalize(scope) + "|" + normalizeStrategy(strategy) + "|" + normalizeRegime(regime) + "|" + normalize(confidenceBucket);
    }

    static String confidenceBucket(double confidence) {
        double clamped = clamp(confidence, 0.0, 0.999999);
        int bucket = (int)Math.floor(clamped * 10.0);
        int from = bucket * 10;
        int to = from + 9;
        return String.format(Locale.ROOT, "C%02d_%02d", from, to);
    }

    private static String defaultSimulationTradePaths() {
        return env("BAR_BY_BAR_SIMULATION_TRADES_PATH", "logs/bar_by_bar_simulation_trades.csv")
                + ";" + env("BAR_BY_BAR_CANDIDATE_RETEST_TRADES_PATH", "logs/bar_by_bar_candidate_retest_trades.csv");
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

    private static boolean isUnknownStrategy(String strategy) {
        String normalized = normalizeStrategy(strategy);
        return normalized.isBlank() || "UNKNOWN".equals(normalized) || "BROKER_SYNC".equals(normalized);
    }

    private static String normalizeStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private static String normalizeRegime(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private static String normalize(String raw) {
        return raw == null || raw.isBlank()
                ? "ANY"
                : raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private static String upper(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.contains(",") && !safe.contains("\"") && !safe.contains("\n") && !safe.contains("\r")) {
            return safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static boolean isTrue(String raw) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
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
        private final Map<String, Integer> indexes = new LinkedHashMap<>();

        CsvHeader(String header) {
            List<String> cols = parseCsv(header);
            for (int i = 0; i < cols.size(); i++) {
                indexes.put(cols.get(i).trim().toLowerCase(Locale.ROOT), i);
            }
        }

        String get(List<String> cols, String name) {
            Integer idx = indexes.get(name.toLowerCase(Locale.ROOT));
            if (idx == null || idx < 0 || idx >= cols.size()) {
                return "";
            }
            return cols.get(idx);
        }
    }

    private static final class BucketStats {
        final String scope;
        final String strategy;
        final String regime;
        final String confidenceBucket;
        final Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        int samples;
        int wins;
        double predictedSum;
        double expectedMoveSum;
        double pnlDollarsSum;
        double pnlPercentSum;
        double grossProfit;
        double grossLoss;

        BucketStats(String scope, String strategy, String regime, String confidenceBucket) {
            this.scope = normalize(scope);
            this.strategy = normalizeStrategy(strategy);
            this.regime = normalizeRegime(regime);
            this.confidenceBucket = normalize(confidenceBucket);
        }

        void add(double predicted, double expectedMove, double pnlDollars, double pnlPercent, String source) {
            samples++;
            predictedSum += clamp(predicted, 0.0, 1.0);
            expectedMoveSum += expectedMove;
            pnlDollarsSum += pnlDollars;
            pnlPercentSum += pnlPercent;
            double edge = Math.abs(pnlDollars) > 0.000001 ? pnlDollars : pnlPercent;
            if (edge > 0.0) {
                wins++;
                grossProfit += Math.abs(edge);
            } else if (edge < 0.0) {
                grossLoss += Math.abs(edge);
            }
            sourceCounts.merge(source == null || source.isBlank() ? "unknown" : source, 1, Integer::sum);
        }

        String profileKey() {
            return PreTradeCalibrationLearningEngine.profileKey(scope, strategy, regime, confidenceBucket);
        }

        double avgPredicted() {
            return samples <= 0 ? 0.0 : predictedSum / samples;
        }

        double avgExpectedMove() {
            return samples <= 0 ? 0.0 : expectedMoveSum / samples;
        }

        double winRate() {
            return samples <= 0 ? 0.0 : (double) wins / samples;
        }

        double calibrationError() {
            return winRate() - avgPredicted();
        }

        double expectancyDollars() {
            return samples <= 0 ? 0.0 : pnlDollarsSum / samples;
        }

        double expectancyPercent() {
            return samples <= 0 ? 0.0 : pnlPercentSum / samples;
        }

        double profitFactor() {
            if (grossLoss <= 0.0) {
                return grossProfit > 0.0 ? 99.0 : 0.0;
            }
            return grossProfit / grossLoss;
        }

        String sourceSummary() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Integer> entry : sourceCounts.entrySet()) {
                if (b.length() > 0) {
                    b.append(';');
                }
                b.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return b.toString();
        }
    }

    private static final class Recommendation {
        final BucketStats stats;
        final String decision;
        final double sizingMultiplier;
        final double confidenceMultiplier;
        final double expectedMoveMultiplier;
        final boolean blocked;
        final String reason;

        Recommendation(BucketStats stats,
                       String decision,
                       double sizingMultiplier,
                       double confidenceMultiplier,
                       double expectedMoveMultiplier,
                       boolean blocked,
                       String reason) {
            this.stats = stats;
            this.decision = decision == null ? "ALLOW_CALIBRATION" : decision;
            this.sizingMultiplier = clamp(sizingMultiplier, 0.0, 1.50);
            this.confidenceMultiplier = clamp(confidenceMultiplier, 0.0, 1.50);
            this.expectedMoveMultiplier = clamp(expectedMoveMultiplier, 0.0, 1.50);
            this.blocked = blocked;
            this.reason = reason == null ? "" : reason;
        }

        int scopeRank() {
            return switch (stats.scope) {
                case "STRATEGY_REGIME_CONFIDENCE" -> 8;
                case "STRATEGY_REGIME" -> 7;
                case "STRATEGY_CONFIDENCE" -> 6;
                case "REGIME_CONFIDENCE" -> 5;
                case "STRATEGY" -> 4;
                case "REGIME" -> 3;
                case "CONFIDENCE" -> 2;
                default -> 1;
            };
        }

        int decisionRank() {
            if ("BLOCK_CALIBRATION".equals(decision)) return 0;
            if ("SHRINK_CALIBRATION".equals(decision)) return 1;
            if ("INSUFFICIENT".equals(decision)) return 2;
            if ("ALLOW_CALIBRATION".equals(decision)) return 3;
            return 4;
        }
    }

    static final class Config {
        final int minSamples;
        final int minShrinkSamples;
        final int minBlockSamples;
        final int minBoostSamples;
        final boolean includePartialExits;
        final double minWinRate;
        final double minProfitFactor;
        final double minExpectancyPercent;
        final double maxToleratedOverconfidence;
        final double blockWinRate;
        final double blockProfitFactor;
        final double blockExpectancyPercent;
        final double blockOverconfidence;
        final double minShrinkMultiplier;
        final double minConfidenceMultiplier;
        final double minExpectedMoveMultiplier;
        final double boostExpectancyPercent;
        final double boostProfitFactor;
        final double boostCalibrationTolerance;
        final double maxBoostMultiplier;

        Config(int minSamples,
               int minShrinkSamples,
               int minBlockSamples,
               int minBoostSamples,
               boolean includePartialExits,
               double minWinRate,
               double minProfitFactor,
               double minExpectancyPercent,
               double maxToleratedOverconfidence,
               double blockWinRate,
               double blockProfitFactor,
               double blockExpectancyPercent,
               double blockOverconfidence,
               double minShrinkMultiplier,
               double minConfidenceMultiplier,
               double minExpectedMoveMultiplier,
               double boostExpectancyPercent,
               double boostProfitFactor,
               double boostCalibrationTolerance,
               double maxBoostMultiplier) {
            this.minSamples = Math.max(3, minSamples);
            this.minShrinkSamples = Math.max(this.minSamples, minShrinkSamples);
            this.minBlockSamples = Math.max(this.minShrinkSamples, minBlockSamples);
            this.minBoostSamples = Math.max(this.minShrinkSamples, minBoostSamples);
            this.includePartialExits = includePartialExits;
            this.minWinRate = clamp(minWinRate, 0.0, 1.0);
            this.minProfitFactor = Math.max(0.0, minProfitFactor);
            this.minExpectancyPercent = minExpectancyPercent;
            this.maxToleratedOverconfidence = clamp(maxToleratedOverconfidence, 0.0, 1.0);
            this.blockWinRate = clamp(blockWinRate, 0.0, 1.0);
            this.blockProfitFactor = Math.max(0.0, blockProfitFactor);
            this.blockExpectancyPercent = Math.max(0.0, blockExpectancyPercent);
            this.blockOverconfidence = clamp(blockOverconfidence, 0.0, 1.0);
            this.minShrinkMultiplier = clamp(minShrinkMultiplier, 0.05, 1.0);
            this.minConfidenceMultiplier = clamp(minConfidenceMultiplier, 0.05, 1.0);
            this.minExpectedMoveMultiplier = clamp(minExpectedMoveMultiplier, 0.05, 1.0);
            this.boostExpectancyPercent = boostExpectancyPercent;
            this.boostProfitFactor = Math.max(1.0, boostProfitFactor);
            this.boostCalibrationTolerance = clamp(boostCalibrationTolerance, 0.0, 0.50);
            this.maxBoostMultiplier = clamp(maxBoostMultiplier, 1.0, 1.25);
        }

        static Config fromEnv() {
            return new Config(
                    envInt("PRE_TRADE_CALIBRATION_MIN_SAMPLES", 8),
                    envInt("PRE_TRADE_CALIBRATION_MIN_SHRINK_SAMPLES", 10),
                    envInt("PRE_TRADE_CALIBRATION_MIN_BLOCK_SAMPLES", 24),
                    envInt("PRE_TRADE_CALIBRATION_MIN_BOOST_SAMPLES", 30),
                    envBool("PRE_TRADE_CALIBRATION_INCLUDE_PARTIAL_EXITS", false),
                    envDouble("PRE_TRADE_CALIBRATION_MIN_WIN_RATE", 0.42),
                    envDouble("PRE_TRADE_CALIBRATION_MIN_PROFIT_FACTOR", 0.95),
                    envDouble("PRE_TRADE_CALIBRATION_MIN_EXPECTANCY_PERCENT", 0.0),
                    envDouble("PRE_TRADE_CALIBRATION_MAX_OVERCONFIDENCE", 0.18),
                    envDouble("PRE_TRADE_CALIBRATION_BLOCK_WIN_RATE", 0.30),
                    envDouble("PRE_TRADE_CALIBRATION_BLOCK_PROFIT_FACTOR", 0.70),
                    envDouble("PRE_TRADE_CALIBRATION_BLOCK_EXPECTANCY_PERCENT", 0.0020),
                    envDouble("PRE_TRADE_CALIBRATION_BLOCK_OVERCONFIDENCE", 0.25),
                    envDouble("PRE_TRADE_CALIBRATION_MIN_SHRINK_MULTIPLIER", 0.35),
                    envDouble("PRE_TRADE_CALIBRATION_MIN_CONFIDENCE_MULTIPLIER", 0.50),
                    envDouble("PRE_TRADE_CALIBRATION_MIN_EXPECTED_MOVE_MULTIPLIER", 0.45),
                    envDouble("PRE_TRADE_CALIBRATION_BOOST_EXPECTANCY_PERCENT", 0.0010),
                    envDouble("PRE_TRADE_CALIBRATION_BOOST_PROFIT_FACTOR", 1.40),
                    envDouble("PRE_TRADE_CALIBRATION_BOOST_TOLERANCE", 0.08),
                    envDouble("PRE_TRADE_CALIBRATION_MAX_BOOST_MULTIPLIER", 1.10)
            );
        }
    }

    public static final class Result {
        public final int profiles;
        public final int predictionRows;
        public final int simulationRows;
        public final int allow;
        public final int shrink;
        public final int blocked;
        public final int boost;
        public final Path policyPath;
        public final Path reportPath;
        public final Path matrixPath;
        public final Path healthPath;

        Result(int profiles,
               int predictionRows,
               int simulationRows,
               int allow,
               int shrink,
               int blocked,
               int boost,
               Path policyPath,
               Path reportPath,
               Path matrixPath,
               Path healthPath) {
            this.profiles = profiles;
            this.predictionRows = predictionRows;
            this.simulationRows = simulationRows;
            this.allow = allow;
            this.shrink = shrink;
            this.blocked = blocked;
            this.boost = boost;
            this.policyPath = policyPath;
            this.reportPath = reportPath;
            this.matrixPath = matrixPath;
            this.healthPath = healthPath;
        }

        public String summary() {
            return "profiles=" + profiles +
                    " predictionRows=" + predictionRows +
                    " simulationRows=" + simulationRows +
                    " allow=" + allow +
                    " shrink=" + shrink +
                    " blocked=" + blocked +
                    " boost=" + boost +
                    " policyPath=" + policyPath +
                    " reportPath=" + reportPath;
        }
    }
}
