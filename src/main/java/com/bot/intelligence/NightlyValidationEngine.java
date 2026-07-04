package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Conservative overnight validator. It does not promote source code by itself;
 * it reads outcome/feature/quality journals and writes an evidence report that
 * the autonomous evolution layer can use as a promotion gate.
 */
public class NightlyValidationEngine {
    private final Path tradeQualityPath = Paths.get(System.getenv().getOrDefault("TRADE_QUALITY_JOURNAL_PATH", "logs/trade_quality.csv"));
    private final Path outcomePath = Paths.get(System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv"));
    private final Path reportPath = Paths.get(System.getenv().getOrDefault("NIGHTLY_VALIDATION_REPORT_PATH", "logs/nightly_validation_report.csv"));
    private final Path lifecyclePath = Paths.get(System.getenv().getOrDefault("TRADE_LIFECYCLE_JOURNAL_PATH", "logs/trade_lifecycle_optimization.csv"));

    public void run() {
        try {
            Map<String, StrategyStats> stats = new LinkedHashMap<>();
            readTradeQuality(stats);
            readOutcomes(stats);
            readLifecycle(stats);
            writeReport(stats);
            System.out.println("NIGHTLY VALIDATION COMPLETE: strategies=" + stats.size() + " report=" + reportPath);
        } catch (Exception e) {
            System.err.println("NIGHTLY VALIDATION ERROR: " + e.getMessage());
        }
    }

    private void readTradeQuality(Map<String, StrategyStats> stats) throws IOException {
        if (!Files.exists(tradeQualityPath)) return;
        try (BufferedReader reader = Files.newBufferedReader(tradeQualityPath)) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] parts = line.split(",", -1);
                if (parts.length < 5) continue;
                String strategy = parts[4].isBlank() ? "UNKNOWN" : parts[4];
                StrategyStats s = stats.computeIfAbsent(strategy, StrategyStats::new);
                s.decisions++;
                if (parts.length > 2 && "MASTER_BUY".equalsIgnoreCase(parts[2])) {
                    s.buyDecisions++;
                }
            }
        }
    }

    private void readOutcomes(Map<String, StrategyStats> stats) throws IOException {
        if (!Files.exists(outcomePath)) return;
        try (BufferedReader reader = Files.newBufferedReader(outcomePath)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return;
            CsvHeader header = new CsvHeader(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.List<String> cols = AutonomousEvolutionSuite.Csv.parse(line);
                String eventType = header.get(cols, "eventType");
                String strategy = TradeOutcomeTrainingFilter.normalizeStrategy(header.get(cols, "strategyName"));
                String syncedFromBroker = header.get(cols, "syncedFromBroker");
                if (!TradeOutcomeTrainingFilter.isTrainingEligible(eventType, strategy, syncedFromBroker)) {
                    continue;
                }
                StrategyStats s = stats.computeIfAbsent(strategy, StrategyStats::new);
                s.outcomes++;
                Double gain = parseOutcomePnl(header, cols);
                if (gain != null) {
                    s.totalReturn += gain;
                    if (gain > 0) s.winners++; else if (gain < 0) s.losers++;
                    if (gain < s.maxLoss) s.maxLoss = gain;
                }
            }
        }
    }

    private void readLifecycle(Map<String, StrategyStats> stats) throws IOException {
        if (!Files.exists(lifecyclePath)) return;
        try (BufferedReader reader = Files.newBufferedReader(lifecyclePath)) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] parts = line.split(",", -1);
                if (parts.length < 17) continue;
                String eventType = parts[1];
                if (!"CLOSE".equalsIgnoreCase(eventType) && !"PARTIAL_EXIT".equalsIgnoreCase(eventType)) continue;
                String strategy = parts[4].isBlank() ? "UNKNOWN" : parts[4];
                StrategyStats s = stats.computeIfAbsent(strategy, StrategyStats::new);
                s.lifecycleEvents++;
                s.totalCapture += parseDouble(parts[13], 0.0);
                s.totalExitEfficiency += parseDouble(parts[14], 0.0);
                s.totalMfe += parseDouble(parts[11], 0.0) / 100.0;
                s.totalMae += parseDouble(parts[12], 0.0) / 100.0;
            }
        }
    }

    private void writeReport(Map<String, StrategyStats> stats) throws IOException {
        Path parent = reportPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        boolean newFile = !Files.exists(reportPath);
        try (BufferedWriter writer = Files.newBufferedWriter(reportPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (newFile) {
                writer.write("timestamp,strategy,decisions,buyDecisions,outcomes,winners,losers,winRate,avgReturn,maxLoss,lifecycleEvents,avgCaptureRatio,avgExitEfficiency,avgMfe,avgMae,promotionEligible,reason");
                writer.newLine();
            }
            for (StrategyStats s : stats.values()) {
                double winRate = s.outcomes == 0 ? 0.0 : (double)s.winners / (double)s.outcomes;
                double avgReturn = s.outcomes == 0 ? 0.0 : s.totalReturn / (double)s.outcomes;
                boolean eligible = s.outcomes >= envInt("NIGHTLY_VALIDATION_MIN_OUTCOMES", 5) && winRate >= envDouble("NIGHTLY_VALIDATION_MIN_WIN_RATE", 0.52) && avgReturn > 0.0 && s.maxLoss >= -Math.abs(envDouble("NIGHTLY_VALIDATION_MAX_SINGLE_LOSS", 0.08));
                double avgCapture = s.lifecycleEvents == 0 ? 0.0 : s.totalCapture / (double) s.lifecycleEvents;
                double avgExitEfficiency = s.lifecycleEvents == 0 ? 0.0 : s.totalExitEfficiency / (double) s.lifecycleEvents;
                double avgMfe = s.lifecycleEvents == 0 ? 0.0 : s.totalMfe / (double) s.lifecycleEvents;
                double avgMae = s.lifecycleEvents == 0 ? 0.0 : s.totalMae / (double) s.lifecycleEvents;
                writer.write(String.join(",",
                        clean(Instant.now().toString()), clean(s.strategy), String.valueOf(s.decisions), String.valueOf(s.buyDecisions), String.valueOf(s.outcomes), String.valueOf(s.winners), String.valueOf(s.losers), fmt(winRate), fmt(avgReturn), fmt(s.maxLoss), String.valueOf(s.lifecycleEvents), fmt(avgCapture), fmt(avgExitEfficiency), fmt(avgMfe), fmt(avgMae), String.valueOf(eligible), clean(eligible ? "passes_validation" : "insufficient_or_negative_edge")));
                writer.newLine();
            }
        }
    }

    private static Double parseOutcomePnl(CsvHeader header, java.util.List<String> cols) {
        String[] keys = {"realizedPnlDollars", "realizedProfit", "realizedPnl", "currentPnlPercent"};
        for (String key : keys) {
            String raw = header.get(cols, key);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                return Double.parseDouble(raw.trim());
            } catch (Exception ignored) {
            }
        }
        return 0.0;
    }

    private static String clean(String value) { return value == null ? "" : value.replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim(); }
    private static String fmt(double value) { return String.format(Locale.ROOT, "%.5f", Double.isFinite(value) ? value : 0.0); }
    private static int envInt(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch (Exception e) { return fallback; } }
    private static double envDouble(String key, double fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); } catch (Exception e) { return fallback; } }
    private static double parseDouble(String value, double fallback) { try { return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim()); } catch (Exception e) { return fallback; } }

    private static final class CsvHeader {
        final Map<String, Integer> indexes = new LinkedHashMap<>();

        CsvHeader(String headerLine) {
            java.util.List<String> cols = AutonomousEvolutionSuite.Csv.parse(headerLine);
            for (int i = 0; i < cols.size(); i++) {
                indexes.put(cols.get(i).trim(), i);
            }
        }

        String get(java.util.List<String> cols, String name) {
            Integer idx = indexes.get(name);
            return idx == null || idx < 0 || idx >= cols.size() ? "" : cols.get(idx);
        }
    }

    private static final class StrategyStats {
        final String strategy;
        int decisions;
        int buyDecisions;
        int outcomes;
        int winners;
        int losers;
        int lifecycleEvents;
        double totalReturn;
        double totalCapture;
        double totalExitEfficiency;
        double totalMfe;
        double totalMae;
        double maxLoss = 0.0;
        StrategyStats(String strategy) { this.strategy = strategy == null ? "UNKNOWN" : strategy; }
    }
}
