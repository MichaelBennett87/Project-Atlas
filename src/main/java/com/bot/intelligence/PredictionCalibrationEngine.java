package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

public class PredictionCalibrationEngine {

    private final Path outcomesPath;
    private final Path reportPath;
    private final Path policyPath;
    private final int minSamples;
    private final boolean includePartialExits;
    private final double walkForwardTrainFraction;
    private final int minValidationSamples;
    private final double minValidationProfitFactor;
    private final double minValidationExpectancy;
    private final double validationCalibrationTolerance;

    public PredictionCalibrationEngine() {
        this(
                Path.of(System.getenv().getOrDefault("PREDICTION_OUTCOME_JOURNAL_PATH", "logs/prediction_outcomes.csv")),
                Path.of(System.getenv().getOrDefault("PREDICTION_CALIBRATION_REPORT_PATH", "logs/prediction_calibration_report.txt")),
                Path.of(System.getenv().getOrDefault("PREDICTION_CALIBRATION_POLICY_PATH", "logs/prediction_calibration_policy.properties")),
                envInt("PREDICTION_CALIBRATION_MIN_SAMPLES", 8),
                envBool("PREDICTION_CALIBRATION_INCLUDE_PARTIAL_EXITS", false)
        );
    }

    public PredictionCalibrationEngine(
            Path outcomesPath,
            Path reportPath,
            Path policyPath,
            int minSamples,
            boolean includePartialExits
    ) {
        this.outcomesPath = outcomesPath;
        this.reportPath = reportPath;
        this.policyPath = policyPath;
        this.minSamples = Math.max(3, minSamples);
        this.includePartialExits = includePartialExits;
        this.walkForwardTrainFraction = clamp(
                envDouble("PREDICTION_WALK_FORWARD_TRAIN_FRACTION", 0.70),
                0.50,
                0.90
        );
        this.minValidationSamples = Math.max(
                2,
                envInt("PREDICTION_WALK_FORWARD_MIN_VALIDATION_SAMPLES", 4)
        );
        this.minValidationProfitFactor =
                envDouble("PREDICTION_WALK_FORWARD_MIN_PROFIT_FACTOR", 1.10);
        this.minValidationExpectancy =
                envDouble("PREDICTION_WALK_FORWARD_MIN_EXPECTANCY", 0.0);
        this.validationCalibrationTolerance =
                envDouble("PREDICTION_WALK_FORWARD_CALIBRATION_TOLERANCE", 0.10);
    }

    public Result runOnce() {
        CalibrationUniverse universe = readOutcomes();
        Map<String, PolicyDecision> decisions = buildPolicyDecisions(universe);
        PolicyWriteResult writeResult = writePolicy(decisions);
        writeReport(universe, decisions, writeResult);
        return new Result(
                universe.totalSamples,
                universe.byStrategy.size(),
                writeResult.recommendations,
                writeResult.validatedIncreases,
                writeResult.rejectedIncreases,
                writeResult.downweights,
                policyPath,
                reportPath
        );
    }

    private CalibrationUniverse readOutcomes() {
        CalibrationUniverse universe = new CalibrationUniverse();
        if (!Files.exists(outcomesPath)) {
            return universe;
        }

        try (BufferedReader reader = Files.newBufferedReader(outcomesPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return universe;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                String event = upper(h.get(cols, "eventType"));
                if (!"CLOSE".equals(event) && (!includePartialExits || !"PARTIAL_EXIT".equals(event))) {
                    continue;
                }
                if (h.get(cols, "entryContextId").isBlank()) {
                    continue;
                }
                if (TradeOutcomeTrainingFilter.isTrue(h.get(cols, "syncedFromBroker"))) {
                    continue;
                }

                String strategy = TradeOutcomeTrainingFilter.normalizeStrategy(h.get(cols, "strategyName"));
                if (TradeOutcomeTrainingFilter.isUnknownStrategy(strategy)
                        && !envBool("PREDICTION_CALIBRATION_INCLUDE_UNKNOWN_STRATEGY", false)) {
                    continue;
                }

                String regime = upper(h.get(cols, "entryMarketRegime"));
                if (regime.isBlank()) {
                    regime = "UNKNOWN";
                }

                double predicted = parseDouble(h.get(cols, "pTarget"), 0.0);
                if (predicted <= 0.0) {
                    predicted = parseDouble(h.get(cols, "predictionConfidence"), 0.0);
                }
                predicted = clamp(predicted, 0.0, 1.0);
                double pnl = parseDouble(h.get(cols, "realizedPnlDollars"), 0.0);
                CalibrationSample sample = new CalibrationSample(predicted, pnl);

                universe.totalSamples++;
                universe.byStrategy.computeIfAbsent(strategy, CalibrationStats::new).add(sample);
                universe.byRegime.computeIfAbsent(regime, CalibrationStats::new).add(sample);
                universe.byBucket.computeIfAbsent(probabilityBucket(predicted), CalibrationStats::new).add(sample);
                universe.samplesByStrategy.computeIfAbsent(strategy, k -> new ArrayList<>()).add(sample);
            }
        } catch (IOException e) {
            System.out.println("PREDICTION CALIBRATION READ FAILED: " + e.getMessage());
        }

        return universe;
    }

    private Map<String, PolicyDecision> buildPolicyDecisions(CalibrationUniverse universe) {
        Map<String, PolicyDecision> decisions = new TreeMap<>();
        for (Map.Entry<String, List<CalibrationSample>> e : universe.samplesByStrategy.entrySet()) {
            String strategy = e.getKey();
            List<CalibrationSample> samples = e.getValue();
            CalibrationStats fullStats = universe.byStrategy.get(strategy);
            if (samples == null || fullStats == null || fullStats.samples < minSamples) {
                continue;
            }

            int trainCount = trainCount(samples.size());
            CalibrationStats trainStats = new CalibrationStats(strategy + "_TRAIN");
            CalibrationStats validationStats = new CalibrationStats(strategy + "_VALIDATION");
            for (int i = 0; i < samples.size(); i++) {
                if (i < trainCount) {
                    trainStats.add(samples.get(i));
                } else {
                    validationStats.add(samples.get(i));
                }
            }

            CalibrationStats candidateStats = trainStats.samples >= minSamples ? trainStats : fullStats;
            double candidateMultiplier = recommendedMultiplier(candidateStats);
            ValidationResult validation = validateWalkForward(candidateMultiplier, validationStats);
            double policyMultiplier = validation.policyMultiplier;

            decisions.put(
                    strategy,
                    new PolicyDecision(
                            strategy,
                            fullStats,
                            trainStats,
                            validationStats,
                            candidateMultiplier,
                            policyMultiplier,
                            validation.status,
                            validation.writeStrategyMultiplier
                    )
            );
        }
        return decisions;
    }

    private int trainCount(int samples) {
        if (samples <= minSamples + minValidationSamples) {
            return Math.min(samples, minSamples);
        }
        int train = (int) Math.floor(samples * walkForwardTrainFraction);
        train = Math.max(minSamples, train);
        train = Math.min(train, samples - minValidationSamples);
        return Math.max(0, train);
    }

    private ValidationResult validateWalkForward(double candidateMultiplier, CalibrationStats validationStats) {
        if (candidateMultiplier <= 1.0) {
            return new ValidationResult(candidateMultiplier, "NOT_REQUIRED_DOWNSIDE_GUARD", true);
        }

        if (validationStats == null || validationStats.samples < minValidationSamples) {
            return new ValidationResult(1.0, "REJECTED_INSUFFICIENT_HOLDOUT", false);
        }

        boolean passed =
                validationStats.expectancy() >= minValidationExpectancy &&
                        validationStats.totalPnl > 0.0 &&
                        validationStats.profitFactor() >= minValidationProfitFactor &&
                        validationStats.actualWinRate() >= validationStats.avgPredicted() - validationCalibrationTolerance;

        if (!passed) {
            return new ValidationResult(1.0, "REJECTED_HOLDOUT_PERFORMANCE", false);
        }

        return new ValidationResult(candidateMultiplier, "PASSED", true);
    }

    private PolicyWriteResult writePolicy(Map<String, PolicyDecision> decisions) {
        PolicyWriteResult result = new PolicyWriteResult();
        try {
            Path parent = policyPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Properties p = new Properties();
            p.setProperty("updatedAtMs", Long.toString(System.currentTimeMillis()));
            p.setProperty("source", "prediction_calibration");
            p.setProperty("minSamples", Integer.toString(minSamples));
            p.setProperty("walkForward.enabled", "true");
            p.setProperty("walkForward.trainFraction", Double.toString(walkForwardTrainFraction));
            p.setProperty("walkForward.minValidationSamples", Integer.toString(minValidationSamples));
            p.setProperty("walkForward.minValidationProfitFactor", Double.toString(minValidationProfitFactor));
            p.setProperty("walkForward.minValidationExpectancy", Double.toString(minValidationExpectancy));
            p.setProperty("walkForward.calibrationTolerance", Double.toString(validationCalibrationTolerance));

            for (PolicyDecision decision : decisions.values()) {
                CalibrationStats full = decision.fullStats;
                p.setProperty("samples." + decision.strategy, Integer.toString(full.samples));
                p.setProperty("trainSamples." + decision.strategy, Integer.toString(decision.trainStats.samples));
                p.setProperty("validationSamples." + decision.strategy, Integer.toString(decision.validationStats.samples));
                p.setProperty("expectancy." + decision.strategy, Double.toString(full.expectancy()));
                p.setProperty("profitFactor." + decision.strategy, Double.toString(full.profitFactor()));
                p.setProperty("calibrationError." + decision.strategy, Double.toString(full.calibrationError()));
                p.setProperty("candidateMultiplier." + decision.strategy, Double.toString(decision.candidateMultiplier));
                p.setProperty("validatedMultiplier." + decision.strategy, Double.toString(decision.policyMultiplier));
                p.setProperty("validationStatus." + decision.strategy, decision.validationStatus);
                p.setProperty("validationExpectancy." + decision.strategy, Double.toString(decision.validationStats.expectancy()));
                p.setProperty("validationProfitFactor." + decision.strategy, Double.toString(decision.validationStats.profitFactor()));
                p.setProperty("validationWinRate." + decision.strategy, Double.toString(decision.validationStats.actualWinRate()));

                if (decision.writeStrategyMultiplier) {
                    p.setProperty("strategyMultiplier." + decision.strategy, Double.toString(decision.policyMultiplier));
                    result.recommendations++;
                    if (decision.policyMultiplier > 1.0) {
                        result.validatedIncreases++;
                    } else if (decision.policyMultiplier < 1.0) {
                        result.downweights++;
                    }
                } else if (decision.candidateMultiplier > 1.0) {
                    result.rejectedIncreases++;
                }
            }

            try (java.io.OutputStream out = Files.newOutputStream(policyPath)) {
                p.store(out, "Auto-generated from prediction outcomes.");
            }
        } catch (IOException e) {
            System.out.println("PREDICTION CALIBRATION POLICY WRITE FAILED: " + e.getMessage());
        }
        return result;
    }

    private void writeReport(
            CalibrationUniverse universe,
            Map<String, PolicyDecision> decisions,
            PolicyWriteResult writeResult
    ) {
        try {
            Path parent = reportPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            DecimalFormat df = new DecimalFormat("0.0000");
            StringBuilder b = new StringBuilder();
            b.append("PREDICTION CALIBRATION REPORT\n");
            b.append("generatedAt=").append(Instant.now()).append('\n');
            b.append("outcomesPath=").append(outcomesPath).append('\n');
            b.append("samples=").append(universe.totalSamples).append('\n');
            b.append("strategies=").append(universe.byStrategy.size()).append('\n');
            b.append("recommendations=").append(writeResult.recommendations).append('\n');
            b.append("validatedIncreases=").append(writeResult.validatedIncreases).append('\n');
            b.append("rejectedIncreases=").append(writeResult.rejectedIncreases).append('\n');
            b.append("downweights=").append(writeResult.downweights).append('\n');
            b.append("minSamples=").append(minSamples).append('\n');
            b.append("includePartialExits=").append(includePartialExits).append('\n');
            b.append("walkForwardTrainFraction=").append(walkForwardTrainFraction).append('\n');
            b.append("walkForwardMinValidationSamples=").append(minValidationSamples).append('\n');
            b.append("walkForwardMinProfitFactor=").append(minValidationProfitFactor).append('\n');
            b.append("walkForwardMinExpectancy=").append(minValidationExpectancy).append('\n');
            b.append("walkForwardCalibrationTolerance=").append(validationCalibrationTolerance).append('\n');
            b.append("policyPath=").append(policyPath).append('\n');
            b.append('\n');
            appendPolicySection(b, decisions, df);
            appendSection(b, "strategyCalibration", universe.byStrategy, df, false);
            appendSection(b, "regimeCalibration", universe.byRegime, df, false);
            appendSection(b, "probabilityBucketCalibration", universe.byBucket, df, false);

            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("PREDICTION CALIBRATION REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private void appendPolicySection(
            StringBuilder b,
            Map<String, PolicyDecision> decisions,
            DecimalFormat df
    ) {
        b.append("walkForwardPolicyDecisions:\n");
        for (PolicyDecision d : decisions.values()) {
            b.append("  ").append(d.strategy)
                    .append(" candidate=").append(df.format(d.candidateMultiplier))
                    .append(" live=").append(df.format(d.policyMultiplier))
                    .append(" status=").append(d.validationStatus)
                    .append(" trainSamples=").append(d.trainStats.samples)
                    .append(" trainExpectancy=").append(df.format(d.trainStats.expectancy()))
                    .append(" trainPF=").append(df.format(d.trainStats.profitFactor()))
                    .append(" validationSamples=").append(d.validationStats.samples)
                    .append(" validationExpectancy=").append(df.format(d.validationStats.expectancy()))
                    .append(" validationPF=").append(df.format(d.validationStats.profitFactor()))
                    .append(" validationWinRate=").append(df.format(d.validationStats.actualWinRate()))
                    .append('\n');
        }
        b.append('\n');
    }

    private void appendSection(
            StringBuilder b,
            String title,
            Map<String, CalibrationStats> stats,
            DecimalFormat df,
            boolean includeMultiplier
    ) {
        b.append(title).append(":\n");
        for (Map.Entry<String, CalibrationStats> e : stats.entrySet()) {
            CalibrationStats s = e.getValue();
            b.append("  ").append(e.getKey())
                    .append(" samples=").append(s.samples)
                    .append(" avgPred=").append(df.format(s.avgPredicted()))
                    .append(" actualWinRate=").append(df.format(s.actualWinRate()))
                    .append(" calibrationError=").append(df.format(s.calibrationError()))
                    .append(" pnl=").append(df.format(s.totalPnl))
                    .append(" expectancy=").append(df.format(s.expectancy()))
                    .append(" profitFactor=").append(df.format(s.profitFactor()));
            if (includeMultiplier && s.samples >= minSamples) {
                b.append(" multiplier=").append(df.format(recommendedMultiplier(s)));
            }
            b.append('\n');
        }
        b.append('\n');
    }

    private double recommendedMultiplier(CalibrationStats stats) {
        double multiplier = 1.0;
        if (stats != null && stats.samples >= minSamples) {
            if (stats.expectancy() < 0.0 || stats.profitFactor() < 0.95) {
                multiplier *= 0.82;
            }
            if (Math.abs(stats.calibrationError()) > 0.20 && stats.actualWinRate() < stats.avgPredicted()) {
                multiplier *= 0.85;
            }
            if (stats.expectancy() > 0.0 &&
                    stats.profitFactor() >= 1.35 &&
                    stats.actualWinRate() >= stats.avgPredicted() - 0.05) {
                multiplier *= 1.08;
            }
        }
        return clamp(
                multiplier,
                envDouble("PREDICTION_CALIBRATION_MIN_MULTIPLIER", 0.55),
                envDouble("PREDICTION_CALIBRATION_MAX_MULTIPLIER", 1.20)
        );
    }

    private static String probabilityBucket(double predicted) {
        int bucket = (int) Math.floor(clamp(predicted, 0.0, 0.9999) * 10.0);
        int from = bucket * 10;
        int to = from + 9;
        return String.format(Locale.ROOT, "%02d-%02d", from, to);
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

    private static String upper(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
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

    private static boolean envBool(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
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

    private static final class CsvHeader {
        private final Map<String, Integer> indexes = new TreeMap<>();

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

    private static final class CalibrationUniverse {
        int totalSamples;
        Map<String, CalibrationStats> byStrategy = new TreeMap<>();
        Map<String, CalibrationStats> byRegime = new TreeMap<>();
        Map<String, CalibrationStats> byBucket = new TreeMap<>();
        Map<String, List<CalibrationSample>> samplesByStrategy = new TreeMap<>();
    }

    private static final class CalibrationSample {
        final double predicted;
        final double pnl;

        CalibrationSample(double predicted, double pnl) {
            this.predicted = predicted;
            this.pnl = pnl;
        }
    }

    private static final class CalibrationStats {
        final String name;
        int samples;
        int winners;
        double predictedSum;
        double totalPnl;
        double grossProfit;
        double grossLoss;

        CalibrationStats(String name) {
            this.name = name;
        }

        void add(CalibrationSample sample) {
            if (sample == null) {
                return;
            }
            samples++;
            predictedSum += sample.predicted;
            totalPnl += sample.pnl;
            if (sample.pnl > 0.0) {
                winners++;
                grossProfit += sample.pnl;
            } else if (sample.pnl < 0.0) {
                grossLoss += Math.abs(sample.pnl);
            }
        }

        double avgPredicted() {
            return samples <= 0 ? 0.0 : predictedSum / samples;
        }

        double actualWinRate() {
            return samples <= 0 ? 0.0 : (double) winners / samples;
        }

        double calibrationError() {
            return actualWinRate() - avgPredicted();
        }

        double expectancy() {
            return samples <= 0 ? 0.0 : totalPnl / samples;
        }

        double profitFactor() {
            if (grossLoss <= 0.0) {
                return grossProfit > 0.0 ? 99.0 : 0.0;
            }
            return grossProfit / grossLoss;
        }
    }

    private static final class ValidationResult {
        final double policyMultiplier;
        final String status;
        final boolean writeStrategyMultiplier;

        ValidationResult(double policyMultiplier, String status, boolean writeStrategyMultiplier) {
            this.policyMultiplier = policyMultiplier;
            this.status = status;
            this.writeStrategyMultiplier = writeStrategyMultiplier;
        }
    }

    private static final class PolicyDecision {
        final String strategy;
        final CalibrationStats fullStats;
        final CalibrationStats trainStats;
        final CalibrationStats validationStats;
        final double candidateMultiplier;
        final double policyMultiplier;
        final String validationStatus;
        final boolean writeStrategyMultiplier;

        PolicyDecision(
                String strategy,
                CalibrationStats fullStats,
                CalibrationStats trainStats,
                CalibrationStats validationStats,
                double candidateMultiplier,
                double policyMultiplier,
                String validationStatus,
                boolean writeStrategyMultiplier
        ) {
            this.strategy = strategy;
            this.fullStats = fullStats;
            this.trainStats = trainStats;
            this.validationStats = validationStats;
            this.candidateMultiplier = candidateMultiplier;
            this.policyMultiplier = policyMultiplier;
            this.validationStatus = validationStatus;
            this.writeStrategyMultiplier = writeStrategyMultiplier;
        }
    }

    private static final class PolicyWriteResult {
        int recommendations;
        int validatedIncreases;
        int rejectedIncreases;
        int downweights;
    }

    public static final class Result {
        public final int samples;
        public final int strategies;
        public final int recommendations;
        public final int validatedIncreases;
        public final int rejectedIncreases;
        public final int downweights;
        public final Path policyPath;
        public final Path reportPath;

        Result(
                int samples,
                int strategies,
                int recommendations,
                int validatedIncreases,
                int rejectedIncreases,
                int downweights,
                Path policyPath,
                Path reportPath
        ) {
            this.samples = samples;
            this.strategies = strategies;
            this.recommendations = recommendations;
            this.validatedIncreases = validatedIncreases;
            this.rejectedIncreases = rejectedIncreases;
            this.downweights = downweights;
            this.policyPath = policyPath;
            this.reportPath = reportPath;
        }
    }
}
