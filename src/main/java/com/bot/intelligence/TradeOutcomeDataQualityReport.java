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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class TradeOutcomeDataQualityReport {

    private final Path outcomesPath;
    private final Path reportPath;

    public TradeOutcomeDataQualityReport() {
        this(
                Path.of(System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv")),
                Path.of(System.getenv().getOrDefault("TRADE_OUTCOME_QUALITY_REPORT_PATH", "logs/trade_outcome_quality_report.txt"))
        );
    }

    TradeOutcomeDataQualityReport(Path outcomesPath, Path reportPath) {
        this.outcomesPath = outcomesPath;
        this.reportPath = reportPath;
    }

    public Result runOnce() {
        Stats stats = readStats();
        writeReport(stats);
        return new Result(stats.totalRows, stats.trainingEligibleRows, reportPath);
    }

    private Stats readStats() {
        Stats stats = new Stats();
        if (!Files.exists(outcomesPath)) {
            return stats;
        }

        Set<String> openKeys = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(outcomesPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return stats;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                stats.totalRows++;

                String eventType = h.get(cols, "eventType").trim().toUpperCase(Locale.ROOT);
                String ticker = h.get(cols, "ticker").trim().toUpperCase(Locale.ROOT);
                String side = h.get(cols, "side").trim().toUpperCase(Locale.ROOT);
                String strategy = TradeOutcomeTrainingFilter.normalizeStrategy(h.get(cols, "strategyName"));
                String synced = h.get(cols, "syncedFromBroker");
                double pnl = parseDouble(h.get(cols, "realizedPnlDollars"), 0.0);

                if ("OPEN".equals(eventType)) {
                    stats.openRows++;
                    String key = ticker + "|" + side;
                    if (!ticker.isBlank() && !side.isBlank() && !openKeys.add(key)) {
                        stats.duplicateOpenRows++;
                    }
                } else if ("CLOSE".equals(eventType)) {
                    stats.closeRows++;
                    openKeys.remove(ticker + "|" + side);
                } else if ("PARTIAL_EXIT".equals(eventType)) {
                    stats.partialExitRows++;
                }

                if (TradeOutcomeTrainingFilter.isUnknownStrategy(strategy)) {
                    stats.unknownStrategyRows++;
                }
                if (TradeOutcomeTrainingFilter.isTrue(synced)) {
                    stats.brokerSyncedRows++;
                }

                if (TradeOutcomeTrainingFilter.isTrainingEligible(eventType, strategy, synced)) {
                    stats.trainingEligibleRows++;
                    stats.trainingPnl += pnl;
                    StrategyStats strategyStats = stats.byStrategy.computeIfAbsent(strategy, StrategyStats::new);
                    strategyStats.rows++;
                    strategyStats.pnl += pnl;
                    if (pnl > 0.0) {
                        strategyStats.winners++;
                    } else if (pnl < 0.0) {
                        strategyStats.losers++;
                    }
                } else if ("PARTIAL_EXIT".equals(eventType)) {
                    stats.skippedPartialRows++;
                } else if (TradeOutcomeTrainingFilter.isUnknownStrategy(strategy)) {
                    stats.skippedUnknownStrategyRows++;
                } else if (TradeOutcomeTrainingFilter.isTrue(synced)) {
                    stats.skippedBrokerSyncedRows++;
                }
            }
            stats.openPositionsAtEnd = openKeys.size();
        } catch (IOException e) {
            System.out.println("TRADE OUTCOME QUALITY READ FAILED: " + e.getMessage());
        }
        return stats;
    }

    private void writeReport(Stats stats) {
        try {
            Path parent = reportPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            DecimalFormat df = new DecimalFormat("0.0000");
            StringBuilder b = new StringBuilder();
            b.append("TRADE OUTCOME DATA QUALITY REPORT\n");
            b.append("generatedAt=").append(Instant.now()).append('\n');
            b.append("outcomesPath=").append(outcomesPath).append('\n');
            b.append("totalRows=").append(stats.totalRows).append('\n');
            b.append("openRows=").append(stats.openRows).append('\n');
            b.append("closeRows=").append(stats.closeRows).append('\n');
            b.append("partialExitRows=").append(stats.partialExitRows).append('\n');
            b.append("trainingEligibleRows=").append(stats.trainingEligibleRows).append('\n');
            b.append("trainingEligiblePnl=").append(df.format(stats.trainingPnl)).append('\n');
            b.append("unknownStrategyRows=").append(stats.unknownStrategyRows).append('\n');
            b.append("brokerSyncedRows=").append(stats.brokerSyncedRows).append('\n');
            b.append("duplicateOpenRows=").append(stats.duplicateOpenRows).append('\n');
            b.append("openPositionsAtEnd=").append(stats.openPositionsAtEnd).append('\n');
            b.append("skippedPartialRows=").append(stats.skippedPartialRows).append('\n');
            b.append("skippedUnknownStrategyRows=").append(stats.skippedUnknownStrategyRows).append('\n');
            b.append("skippedBrokerSyncedRows=").append(stats.skippedBrokerSyncedRows).append('\n');
            b.append('\n');
            b.append("trainingStrategyStats:\n");
            stats.byStrategy.values().stream()
                    .sorted((a, c) -> Double.compare(c.pnl, a.pnl))
                    .forEach(s -> b.append("  ").append(s.strategy)
                            .append(" rows=").append(s.rows)
                            .append(" pnl=").append(df.format(s.pnl))
                            .append(" winRate=").append(df.format(s.winRate()))
                            .append('\n'));

            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("TRADE OUTCOME QUALITY REPORT WRITE FAILED: " + e.getMessage());
        }
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

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
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

    private static final class Stats {
        int totalRows;
        int openRows;
        int closeRows;
        int partialExitRows;
        int trainingEligibleRows;
        int unknownStrategyRows;
        int brokerSyncedRows;
        int duplicateOpenRows;
        int openPositionsAtEnd;
        int skippedPartialRows;
        int skippedUnknownStrategyRows;
        int skippedBrokerSyncedRows;
        double trainingPnl;
        Map<String, StrategyStats> byStrategy = new HashMap<>();
    }

    private static final class StrategyStats {
        final String strategy;
        int rows;
        int winners;
        int losers;
        double pnl;

        StrategyStats(String strategy) {
            this.strategy = strategy;
        }

        double winRate() {
            return rows <= 0 ? 0.0 : (double) winners / rows;
        }
    }

    public static final class Result {
        public final int totalRows;
        public final int trainingEligibleRows;
        public final Path reportPath;

        Result(int totalRows, int trainingEligibleRows, Path reportPath) {
            this.totalRows = totalRows;
            this.trainingEligibleRows = trainingEligibleRows;
            this.reportPath = reportPath;
        }
    }
}
