package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Offline tournament of concrete exit recipes using lifecycle MFE/MAE evidence.
 */
public final class ExitShadowTournamentEngine {
    private final Path lifecyclePath;
    private final Path policyPath;
    private final Path reportPath;
    private final Path matrixPath;
    private final Path healthPath;
    private final int minSamples;
    private final double roundTripCostFraction;
    private final List<ExitVariant> variants = defaultVariants();

    public ExitShadowTournamentEngine() {
        this(
                Path.of(env("TRADE_LIFECYCLE_JOURNAL_PATH", "logs/trade_lifecycle_optimization.csv")),
                Path.of(env("EXIT_SHADOW_TOURNAMENT_POLICY_PATH", "logs/exit_shadow_tournament_policy.properties")),
                Path.of(env("EXIT_SHADOW_TOURNAMENT_REPORT_PATH", "logs/exit_shadow_tournament_report.txt")),
                Path.of(env("EXIT_SHADOW_TOURNAMENT_MATRIX_PATH", "logs/exit_shadow_tournament_matrix.csv")),
                Path.of(env("EXIT_SHADOW_TOURNAMENT_HEALTH_PATH", "logs/exit_shadow_tournament_health.properties")),
                Math.max(3, envInt("EXIT_SHADOW_TOURNAMENT_MIN_SAMPLES", 8)),
                Math.max(0.0, envDouble("EXIT_SHADOW_TOURNAMENT_ROUND_TRIP_COST_FRACTION", 0.0010))
        );
    }

    ExitShadowTournamentEngine(Path lifecyclePath,
                               Path policyPath,
                               Path reportPath,
                               Path matrixPath,
                               Path healthPath,
                               int minSamples,
                               double roundTripCostFraction) {
        this.lifecyclePath = lifecyclePath;
        this.policyPath = policyPath;
        this.reportPath = reportPath;
        this.matrixPath = matrixPath;
        this.healthPath = healthPath;
        this.minSamples = minSamples;
        this.roundTripCostFraction = roundTripCostFraction;
    }

    public Result run() {
        List<LifecycleRow> rows = readLifecycleRows();
        Map<String, Map<String, VariantStats>> byStrategy = new LinkedHashMap<>();
        Map<String, VariantStats> global = new LinkedHashMap<>();
        for (ExitVariant variant : variants) {
            global.put(variant.name, new VariantStats("GLOBAL", variant));
        }

        for (LifecycleRow row : rows) {
            Map<String, VariantStats> strategyStats = byStrategy.computeIfAbsent(row.strategy, ignored -> {
                Map<String, VariantStats> map = new LinkedHashMap<>();
                for (ExitVariant variant : variants) {
                    map.put(variant.name, new VariantStats(row.strategy, variant));
                }
                return map;
            });
            for (ExitVariant variant : variants) {
                double simulated = variant.simulate(row, roundTripCostFraction);
                global.get(variant.name).record(simulated);
                strategyStats.get(variant.name).record(simulated);
            }
        }

        VariantStats globalWinner = best(global.values());
        Map<String, VariantStats> winners = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, VariantStats>> entry : byStrategy.entrySet()) {
            VariantStats winner = best(entry.getValue().values());
            if (winner != null && winner.samples >= minSamples) {
                winners.put(entry.getKey(), winner);
            }
        }

        writePolicy(globalWinner, winners, rows.size());
        writeMatrix(global, byStrategy);
        writeReport(globalWinner, winners, rows.size());
        writeHealth(globalWinner, winners, rows.size());
        return new Result(rows.size(), byStrategy.size(), winners.size(), globalWinner == null ? "" : globalWinner.variant.name,
                policyPath, reportPath, matrixPath, healthPath);
    }

    private List<LifecycleRow> readLifecycleRows() {
        List<LifecycleRow> rows = new ArrayList<>();
        if (lifecyclePath == null || !Files.exists(lifecyclePath)) {
            return rows;
        }
        try (BufferedReader reader = Files.newBufferedReader(lifecyclePath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return rows;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                String event = normalize(h.get(cols, "eventType"));
                if (!"CLOSE".equals(event) && (!"PARTIAL_EXIT".equals(event)
                        || !envBool("EXIT_SHADOW_TOURNAMENT_INCLUDE_PARTIALS", true))) {
                    continue;
                }
                LifecycleRow row = new LifecycleRow();
                row.strategy = normalizeStrategy(h.get(cols, "strategy"));
                if (row.strategy.isBlank() || "UNKNOWN".equals(row.strategy) || "BROKER_SYNC".equals(row.strategy)) {
                    continue;
                }
                row.side = normalize(h.get(cols, "side"));
                row.returnFraction = parseLifecyclePercent(h.get(cols, "currentReturnPct"));
                row.mfeFraction = Math.max(0.0, parseLifecyclePercent(h.get(cols, "mfePct")));
                row.maeFraction = Math.min(0.0, parseLifecyclePercent(h.get(cols, "maePct")));
                row.ageSeconds = parseDouble(h.get(cols, "ageSeconds"), 0.0);
                row.entryConfidence = parseDouble(h.get(cols, "entryConfidence"), 0.0);
                row.exitReason = h.get(cols, "reason");
                if (!Double.isFinite(row.returnFraction) || !Double.isFinite(row.mfeFraction) || !Double.isFinite(row.maeFraction)) {
                    continue;
                }
                rows.add(row);
            }
        } catch (IOException e) {
            System.out.println("EXIT SHADOW TOURNAMENT LIFECYCLE READ FAILED: " + lifecyclePath + " " + e.getMessage());
        }
        return rows;
    }

    private static double parseLifecyclePercent(String raw) {
        double value = parseDouble(raw, 0.0);
        return value / 100.0;
    }

    private VariantStats best(Iterable<VariantStats> stats) {
        List<VariantStats> eligible = new ArrayList<>();
        for (VariantStats s : stats) {
            if (s != null && s.samples > 0) {
                eligible.add(s);
            }
        }
        return eligible.stream()
                .max(Comparator
                        .comparingDouble((VariantStats s) -> s.expectancy())
                        .thenComparingDouble(VariantStats::profitFactor)
                        .thenComparingDouble(VariantStats::winRate)
                        .thenComparingDouble(s -> -Math.abs(s.worstReturn)))
                .orElse(null);
    }

    private void writePolicy(VariantStats globalWinner, Map<String, VariantStats> winners, int rows) {
        try {
            ensureParent(policyPath);
            Properties p = new Properties();
            p.setProperty("updatedAt", Instant.now().toString());
            p.setProperty("description", "Exit shadow tournament policy. Runtime may tighten exits, but hard loss defaults are never loosened.");
            p.setProperty("lifecyclePath", lifecyclePath == null ? "" : lifecyclePath.toString());
            p.setProperty("samples", Integer.toString(rows));
            p.setProperty("minSamples", Integer.toString(minSamples));
            if (globalWinner != null) {
                writeProfile(p, "global.", globalWinner);
            }
            for (Map.Entry<String, VariantStats> entry : winners.entrySet()) {
                writeProfile(p, "strategy." + entry.getKey() + ".", entry.getValue());
            }
            try (OutputStream out = Files.newOutputStream(policyPath)) {
                p.store(out, "Exit shadow tournament policy");
            }
        } catch (IOException e) {
            System.out.println("EXIT SHADOW TOURNAMENT POLICY WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeProfile(Properties p, String prefix, VariantStats stats) {
        ExitVariant v = stats.variant;
        p.setProperty(prefix + "exitStyle", v.name);
        p.setProperty(prefix + "partialProfitTargetPercent", fmt(v.partialProfitTarget));
        p.setProperty(prefix + "partialExitFraction", fmt(v.partialExitFraction));
        p.setProperty(prefix + "trailingGivebackPercent", fmt(v.trailingGiveback));
        p.setProperty(prefix + "fullProfitLockPercent", fmt(v.fullProfitLock));
        p.setProperty(prefix + "hardStopLossPercent", fmt(v.stopLoss));
        p.setProperty(prefix + "maxHoldMs", Long.toString(v.maxHoldSeconds * 1000L));
        p.setProperty(prefix + "samples", Integer.toString(stats.samples));
        p.setProperty(prefix + "winRate", fmt(stats.winRate()));
        p.setProperty(prefix + "expectancyPercent", fmt(stats.expectancy()));
        p.setProperty(prefix + "profitFactor", fmt(stats.profitFactor()));
        p.setProperty(prefix + "reason", "exit_shadow_winner samples=" + stats.samples +
                " expectancy=" + fmt(stats.expectancy()) +
                " winRate=" + fmt(stats.winRate()) +
                " profitFactor=" + fmt(stats.profitFactor()));
    }

    private void writeMatrix(Map<String, VariantStats> global, Map<String, Map<String, VariantStats>> byStrategy) {
        try {
            ensureParent(matrixPath);
            StringBuilder b = new StringBuilder();
            b.append("scope,strategy,exitStyle,samples,wins,winRate,expectancyPercent,profitFactor,worstReturnPercent,partialProfitTargetPercent,partialExitFraction,trailingGivebackPercent,hardStopLossPercent,maxHoldSeconds\n");
            for (VariantStats s : global.values()) {
                appendMatrixRow(b, "GLOBAL", "ALL", s);
            }
            for (Map.Entry<String, Map<String, VariantStats>> entry : byStrategy.entrySet()) {
                for (VariantStats s : entry.getValue().values()) {
                    appendMatrixRow(b, "STRATEGY", entry.getKey(), s);
                }
            }
            Files.writeString(matrixPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("EXIT SHADOW TOURNAMENT MATRIX WRITE FAILED: " + e.getMessage());
        }
    }

    private void appendMatrixRow(StringBuilder b, String scope, String strategy, VariantStats s) {
        ExitVariant v = s.variant;
        b.append(csv(scope)).append(',')
                .append(csv(strategy)).append(',')
                .append(csv(v.name)).append(',')
                .append(s.samples).append(',')
                .append(s.wins).append(',')
                .append(fmt(s.winRate())).append(',')
                .append(fmt(s.expectancy())).append(',')
                .append(fmt(s.profitFactor())).append(',')
                .append(fmt(s.worstReturn)).append(',')
                .append(fmt(v.partialProfitTarget)).append(',')
                .append(fmt(v.partialExitFraction)).append(',')
                .append(fmt(v.trailingGiveback)).append(',')
                .append(fmt(v.stopLoss)).append(',')
                .append(v.maxHoldSeconds).append('\n');
    }

    private void writeReport(VariantStats globalWinner, Map<String, VariantStats> winners, int rows) {
        try {
            ensureParent(reportPath);
            StringBuilder b = new StringBuilder();
            b.append("EXIT SHADOW TOURNAMENT REPORT\n");
            b.append("generatedAt=").append(Instant.now()).append('\n');
            b.append("lifecyclePath=").append(lifecyclePath).append('\n');
            b.append("samples=").append(rows).append('\n');
            b.append("strategyWinners=").append(winners.size()).append('\n');
            b.append("policyPath=").append(policyPath).append('\n');
            b.append("matrixPath=").append(matrixPath).append('\n');
            b.append('\n').append("GLOBAL WINNER\n");
            if (globalWinner == null) {
                b.append("- none; collect lifecycle samples\n");
            } else {
                appendReportLine(b, globalWinner);
            }
            b.append('\n').append("STRATEGY WINNERS\n");
            if (winners.isEmpty()) {
                b.append("- none yet; collect more closed lifecycle trades\n");
            } else {
                for (VariantStats s : winners.values()) {
                    appendReportLine(b, s);
                }
            }
            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("EXIT SHADOW TOURNAMENT REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private void appendReportLine(StringBuilder b, VariantStats s) {
        b.append("- strategy=").append(s.strategy)
                .append(" exitStyle=").append(s.variant.name)
                .append(" samples=").append(s.samples)
                .append(" winRate=").append(fmt(s.winRate()))
                .append(" expectancyPercent=").append(fmt(s.expectancy()))
                .append(" profitFactor=").append(fmt(s.profitFactor()))
                .append(" partialTarget=").append(fmt(s.variant.partialProfitTarget))
                .append(" trailingGiveback=").append(fmt(s.variant.trailingGiveback))
                .append(" hardStop=").append(fmt(s.variant.stopLoss))
                .append('\n');
    }

    private void writeHealth(VariantStats globalWinner, Map<String, VariantStats> winners, int rows) {
        try {
            ensureParent(healthPath);
            Properties p = new Properties();
            p.setProperty("status", "PASS");
            p.setProperty("generatedAt", Instant.now().toString());
            p.setProperty("samples", Integer.toString(rows));
            p.setProperty("strategyWinners", Integer.toString(winners.size()));
            p.setProperty("globalWinner", globalWinner == null ? "" : globalWinner.variant.name);
            p.setProperty("globalExpectancyPercent", globalWinner == null ? "0.000000" : fmt(globalWinner.expectancy()));
            p.setProperty("policyPath", policyPath.toString());
            p.setProperty("reportPath", reportPath.toString());
            p.setProperty("matrixPath", matrixPath.toString());
            try (OutputStream out = Files.newOutputStream(healthPath)) {
                p.store(out, "Exit shadow tournament health");
            }
        } catch (IOException e) {
            System.out.println("EXIT SHADOW TOURNAMENT HEALTH WRITE FAILED: " + e.getMessage());
        }
    }

    private static List<ExitVariant> defaultVariants() {
        List<ExitVariant> list = new ArrayList<>();
        list.add(new ExitVariant("TIGHT_SCALP", 0.0040, 0.50, 0.0025, 0.0100, 0.0040, 300));
        list.add(new ExitVariant("FAST_PARTIAL_RUNNER", 0.0060, 0.50, 0.0040, 0.0150, 0.0055, 600));
        list.add(new ExitVariant("BALANCED_TRAIL", 0.0100, 0.50, 0.0055, 0.0200, 0.0075, 900));
        list.add(new ExitVariant("MOMENTUM_RUNNER", 0.0150, 0.35, 0.0080, 0.0300, 0.0100, 1_800));
        list.add(new ExitVariant("HARD_FAST_STOP", 0.0080, 0.50, 0.0035, 0.0140, 0.0035, 600));
        return list;
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path == null ? null : path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeStrategy(String value) {
        return normalize(value).replace(' ', '_');
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.6f", Double.isFinite(value) ? value : 0.0);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = env(key, "");
        if (value.isBlank()) {
            return fallback;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = env(key, "");
            return value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = env(key, "");
            return value.isBlank() ? fallback : Double.parseDouble(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class CsvHeader {
        private final Map<String, Integer> indexes = new HashMap<>();

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

    private static final class LifecycleRow {
        String strategy = "";
        String side = "";
        double returnFraction;
        double mfeFraction;
        double maeFraction;
        double ageSeconds;
        double entryConfidence;
        String exitReason = "";
    }

    private static final class ExitVariant {
        final String name;
        final double partialProfitTarget;
        final double partialExitFraction;
        final double trailingGiveback;
        final double fullProfitLock;
        final double stopLoss;
        final long maxHoldSeconds;

        ExitVariant(String name,
                    double partialProfitTarget,
                    double partialExitFraction,
                    double trailingGiveback,
                    double fullProfitLock,
                    double stopLoss,
                    long maxHoldSeconds) {
            this.name = name;
            this.partialProfitTarget = partialProfitTarget;
            this.partialExitFraction = partialExitFraction;
            this.trailingGiveback = trailingGiveback;
            this.fullProfitLock = fullProfitLock;
            this.stopLoss = stopLoss;
            this.maxHoldSeconds = maxHoldSeconds;
        }

        double simulate(LifecycleRow row, double roundTripCostFraction) {
            double actual = row.returnFraction;
            double mfe = Math.max(0.0, row.mfeFraction);
            double mae = Math.min(0.0, row.maeFraction);
            boolean stoppedBeforeTarget = mae <= -stopLoss && mfe < partialProfitTarget;
            if (stoppedBeforeTarget) {
                return -stopLoss - roundTripCostFraction;
            }
            double firstLeg = mfe >= partialProfitTarget ? partialProfitTarget : actual;
            double runner = actual;
            if (mfe >= fullProfitLock && fullProfitLock > 0.0) {
                runner = Math.max(runner, fullProfitLock - trailingGiveback * 0.50);
            }
            if (mfe >= partialProfitTarget) {
                runner = Math.max(runner, mfe - trailingGiveback);
            }
            if (row.ageSeconds >= maxHoldSeconds && maxHoldSeconds > 0 && mfe < partialProfitTarget) {
                runner = Math.min(runner, actual);
            }
            runner = Math.min(runner, mfe);
            runner = Math.max(runner, -stopLoss);
            double simulated = firstLeg * partialExitFraction + runner * (1.0 - partialExitFraction);
            return simulated - roundTripCostFraction;
        }
    }

    private static final class VariantStats {
        final String strategy;
        final ExitVariant variant;
        int samples;
        int wins;
        double totalReturn;
        double grossProfit;
        double grossLoss;
        double worstReturn;

        VariantStats(String strategy, ExitVariant variant) {
            this.strategy = strategy;
            this.variant = variant;
        }

        void record(double returnFraction) {
            samples++;
            totalReturn += returnFraction;
            worstReturn = samples == 1 ? returnFraction : Math.min(worstReturn, returnFraction);
            if (returnFraction > 0.0) {
                wins++;
                grossProfit += returnFraction;
            } else {
                grossLoss += returnFraction;
            }
        }

        double winRate() {
            return samples <= 0 ? 0.0 : wins / (double) samples;
        }

        double expectancy() {
            return samples <= 0 ? 0.0 : totalReturn / samples;
        }

        double profitFactor() {
            if (grossLoss < 0.0) {
                return grossProfit / Math.abs(grossLoss);
            }
            return grossProfit > 0.0 ? 999.0 : 0.0;
        }
    }

    public static final class Result {
        public final int samples;
        public final int strategies;
        public final int strategyWinners;
        public final String globalWinner;
        public final Path policyPath;
        public final Path reportPath;
        public final Path matrixPath;
        public final Path healthPath;

        Result(int samples,
               int strategies,
               int strategyWinners,
               String globalWinner,
               Path policyPath,
               Path reportPath,
               Path matrixPath,
               Path healthPath) {
            this.samples = samples;
            this.strategies = strategies;
            this.strategyWinners = strategyWinners;
            this.globalWinner = globalWinner == null ? "" : globalWinner;
            this.policyPath = policyPath;
            this.reportPath = reportPath;
            this.matrixPath = matrixPath;
            this.healthPath = healthPath;
        }

        public String summary() {
            return "samples=" + samples +
                    " strategies=" + strategies +
                    " strategyWinners=" + strategyWinners +
                    " globalWinner=" + globalWinner +
                    " policyPath=" + policyPath +
                    " reportPath=" + reportPath;
        }
    }
}
