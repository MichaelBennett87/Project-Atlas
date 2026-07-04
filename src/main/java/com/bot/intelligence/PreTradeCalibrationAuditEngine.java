package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Scores calibration interventions after their shadow probes close.
 */
public final class PreTradeCalibrationAuditEngine {
    private final Path auditPath;
    private final Path shadowOutcomePath;
    private final Path reportPath;
    private final Path summaryPath;
    private final Path healthPath;
    private final double winnerPercent;

    public PreTradeCalibrationAuditEngine() {
        this(
                Path.of(env("PRE_TRADE_CALIBRATION_AUDIT_PATH", "logs/pre_trade_calibration_audit.csv")),
                Path.of(env("SHADOW_TRADE_OUTCOME_PATH", "logs/shadow_trade_outcomes.csv")),
                Path.of(env("PRE_TRADE_CALIBRATION_AUDIT_REPORT_PATH", "logs/pre_trade_calibration_audit_report.txt")),
                Path.of(env("PRE_TRADE_CALIBRATION_AUDIT_SUMMARY_PATH", "logs/pre_trade_calibration_audit_summary.csv")),
                Path.of(env("PRE_TRADE_CALIBRATION_AUDIT_HEALTH_PATH", "logs/pre_trade_calibration_audit_health.properties")),
                envDouble("PRE_TRADE_CALIBRATION_AUDIT_WINNER_PERCENT", 0.0020)
        );
    }

    PreTradeCalibrationAuditEngine(Path auditPath,
                                   Path shadowOutcomePath,
                                   Path reportPath,
                                   Path summaryPath,
                                   Path healthPath,
                                   double winnerPercent) {
        this.auditPath = auditPath;
        this.shadowOutcomePath = shadowOutcomePath;
        this.reportPath = reportPath;
        this.summaryPath = summaryPath;
        this.healthPath = healthPath;
        this.winnerPercent = Math.max(0.0, winnerPercent);
    }

    public Result run() {
        ensureAuditFile();
        List<AuditRow> audits = readAudits();
        Map<String, AuditRow> byShadowId = new HashMap<>();
        for (AuditRow row : audits) {
            if (!row.shadowTradeId.isBlank()) {
                byShadowId.put(row.shadowTradeId, row);
            }
        }
        List<ScoredAudit> scored = readOutcomes(byShadowId);

        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        Aggregate global = aggregates.computeIfAbsent("GLOBAL|ALL", ignored -> new Aggregate("GLOBAL", "ALL"));
        for (ScoredAudit score : scored) {
            record(global, score);
            record(aggregates.computeIfAbsent("INTERVENTION|" + score.audit.intervention,
                    ignored -> new Aggregate("INTERVENTION", score.audit.intervention)), score);
            record(aggregates.computeIfAbsent("STRATEGY|" + score.audit.originalStrategy,
                    ignored -> new Aggregate("STRATEGY", score.audit.originalStrategy)), score);
        }
        int pending = Math.max(0, audits.size() - scored.size());
        writeSummary(aggregates);
        writeReport(audits, scored, pending, aggregates);
        writeHealth(audits, scored, pending, global);
        return new Result(audits.size(), scored.size(), pending, global.missedWinners, global.savedLosers,
                global.missedProfitDollars, global.savedLossDollars, global.netBenefitDollars(),
                reportPath, summaryPath, healthPath);
    }

    private void ensureAuditFile() {
        try {
            ensureParent(auditPath);
            if (!Files.exists(auditPath)) {
                Files.writeString(auditPath,
                        "timestamp,auditId,phase,intervention,ticker,originalStrategy,auditStrategy,direction,regime,confidenceBucket,calibrationSamples,originalConfidence,adjustedConfidence,originalExpectedMovePercent,adjustedExpectedMovePercent,originalQuantity,adjustedQuantity,sizingMultiplier,confidenceMultiplier,expectedMoveMultiplier,referencePrice,shadowTradeId,shadowOpened,reason,signalReason" +
                                System.lineSeparator(),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION AUDIT FILE INIT FAILED: " + auditPath + " " + e.getMessage());
        }
    }

    private List<AuditRow> readAudits() {
        List<AuditRow> rows = new ArrayList<>();
        if (auditPath == null || !Files.exists(auditPath)) {
            return rows;
        }
        try (BufferedReader reader = Files.newBufferedReader(auditPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return rows;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                AuditRow row = new AuditRow();
                row.auditId = h.get(cols, "auditId");
                row.timestamp = h.get(cols, "timestamp");
                row.phase = h.get(cols, "phase");
                row.intervention = normalize(h.get(cols, "intervention"));
                row.ticker = normalize(h.get(cols, "ticker"));
                row.originalStrategy = normalizeStrategy(h.get(cols, "originalStrategy"));
                row.auditStrategy = normalizeStrategy(h.get(cols, "auditStrategy"));
                row.originalQuantity = Math.max(1, parseInt(h.get(cols, "originalQuantity"), 1));
                row.adjustedQuantity = Math.max(0, parseInt(h.get(cols, "adjustedQuantity"), 0));
                row.shadowTradeId = h.get(cols, "shadowTradeId").trim();
                row.reason = h.get(cols, "reason");
                rows.add(row);
            }
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION AUDIT READ FAILED: " + auditPath + " " + e.getMessage());
        }
        return rows;
    }

    private List<ScoredAudit> readOutcomes(Map<String, AuditRow> byShadowId) {
        List<ScoredAudit> scored = new ArrayList<>();
        if (shadowOutcomePath == null || !Files.exists(shadowOutcomePath) || byShadowId.isEmpty()) {
            return scored;
        }
        try (BufferedReader reader = Files.newBufferedReader(shadowOutcomePath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return scored;
            }
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsv(line);
                if (!"CLOSE".equalsIgnoreCase(h.get(cols, "eventType"))) {
                    continue;
                }
                String shadowId = extractShadowId(h.get(cols, "reason"));
                if (shadowId.isBlank()) {
                    continue;
                }
                AuditRow audit = byShadowId.get(shadowId);
                if (audit == null) {
                    continue;
                }
                ScoredAudit score = new ScoredAudit();
                score.audit = audit;
                score.shadowTradeId = shadowId;
                score.pnlDollars = parseDouble(h.get(cols, "realizedPnlDollars"), 0.0);
                score.pnlPercent = parseDouble(firstNonBlank(
                        h.get(cols, "currentPnlPercent"),
                        h.get(cols, "pnlPercent"),
                        h.get(cols, "returnPercent")), 0.0);
                score.maxGainPercent = parseDouble(h.get(cols, "maxGainPercent"), 0.0);
                score.maxDrawdownPercent = parseDouble(h.get(cols, "maxDrawdownPercent"), 0.0);
                int skippedQuantity = Math.max(0, audit.originalQuantity - audit.adjustedQuantity);
                double skippedRatio = audit.originalQuantity <= 0 ? 1.0 : skippedQuantity / (double) audit.originalQuantity;
                score.opportunityDollars = score.pnlDollars * skippedRatio;
                scored.add(score);
            }
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION AUDIT OUTCOME READ FAILED: " + shadowOutcomePath + " " + e.getMessage());
        }
        return scored;
    }

    private void record(Aggregate aggregate, ScoredAudit score) {
        aggregate.closed++;
        aggregate.pnlPercentSum += score.pnlPercent;
        aggregate.maxGainPercentSum += score.maxGainPercent;
        aggregate.maxDrawdownPercentSum += score.maxDrawdownPercent;
        if (score.opportunityDollars > 0.0 || score.pnlPercent >= winnerPercent) {
            aggregate.missedWinners++;
            aggregate.missedProfitDollars += Math.max(0.0, score.opportunityDollars);
        } else if (score.opportunityDollars < 0.0) {
            aggregate.savedLosers++;
            aggregate.savedLossDollars += Math.abs(score.opportunityDollars);
        }
    }

    private void writeSummary(Map<String, Aggregate> aggregates) {
        try {
            ensureParent(summaryPath);
            StringBuilder b = new StringBuilder();
            b.append("scope,key,closed,missedWinners,savedLosers,missedProfitDollars,savedLossDollars,netBenefitDollars,avgPnlPercent,avgMaxGainPercent,avgMaxDrawdownPercent\n");
            for (Aggregate a : aggregates.values()) {
                b.append(csv(a.scope)).append(',')
                        .append(csv(a.key)).append(',')
                        .append(a.closed).append(',')
                        .append(a.missedWinners).append(',')
                        .append(a.savedLosers).append(',')
                        .append(fmt(a.missedProfitDollars)).append(',')
                        .append(fmt(a.savedLossDollars)).append(',')
                        .append(fmt(a.netBenefitDollars())).append(',')
                        .append(fmt(a.avgPnlPercent())).append(',')
                        .append(fmt(a.avgMaxGainPercent())).append(',')
                        .append(fmt(a.avgMaxDrawdownPercent())).append('\n');
            }
            Files.writeString(summaryPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION AUDIT SUMMARY WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeReport(List<AuditRow> audits,
                             List<ScoredAudit> scored,
                             int pending,
                             Map<String, Aggregate> aggregates) {
        try {
            ensureParent(reportPath);
            Aggregate global = aggregates.getOrDefault("GLOBAL|ALL", new Aggregate("GLOBAL", "ALL"));
            StringBuilder b = new StringBuilder();
            b.append("PRE-TRADE CALIBRATION MISSED PROFIT AUDIT\n");
            b.append("generatedAt=").append(Instant.now()).append('\n');
            b.append("auditPath=").append(auditPath).append('\n');
            b.append("shadowOutcomePath=").append(shadowOutcomePath).append('\n');
            b.append("audits=").append(audits.size()).append('\n');
            b.append("closedShadowProbes=").append(scored.size()).append('\n');
            b.append("pendingShadowProbes=").append(pending).append('\n');
            b.append("missedWinners=").append(global.missedWinners).append('\n');
            b.append("savedLosers=").append(global.savedLosers).append('\n');
            b.append("missedProfitDollars=").append(fmt(global.missedProfitDollars)).append('\n');
            b.append("savedLossDollars=").append(fmt(global.savedLossDollars)).append('\n');
            b.append("netBenefitDollars=").append(fmt(global.netBenefitDollars())).append('\n');
            b.append('\n').append("INTERPRETATION\n");
            if (scored.isEmpty()) {
                b.append("- No closed calibration shadow probes yet. Keep collecting live events.\n");
            } else if (global.netBenefitDollars() >= 0.0) {
                b.append("- Calibration interventions are currently saving more loss than missed upside.\n");
            } else {
                b.append("- Calibration interventions are currently missing more upside than they save; review blocked/shrunk buckets.\n");
            }
            b.append('\n').append("SUMMARY\n");
            for (Aggregate a : aggregates.values()) {
                b.append("- ").append(a.scope).append(' ').append(a.key)
                        .append(" closed=").append(a.closed)
                        .append(" missedProfit=").append(fmt(a.missedProfitDollars))
                        .append(" savedLoss=").append(fmt(a.savedLossDollars))
                        .append(" netBenefit=").append(fmt(a.netBenefitDollars()))
                        .append(" avgPnlPercent=").append(fmt(a.avgPnlPercent()))
                        .append('\n');
            }
            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION AUDIT REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private void writeHealth(List<AuditRow> audits, List<ScoredAudit> scored, int pending, Aggregate global) {
        try {
            ensureParent(healthPath);
            Properties p = new Properties();
            p.setProperty("status", "PASS");
            p.setProperty("generatedAt", Instant.now().toString());
            p.setProperty("auditPath", auditPath.toString());
            p.setProperty("shadowOutcomePath", shadowOutcomePath.toString());
            p.setProperty("reportPath", reportPath.toString());
            p.setProperty("summaryPath", summaryPath.toString());
            p.setProperty("audits", Integer.toString(audits.size()));
            p.setProperty("closedShadowProbes", Integer.toString(scored.size()));
            p.setProperty("pendingShadowProbes", Integer.toString(pending));
            p.setProperty("missedWinners", Integer.toString(global.missedWinners));
            p.setProperty("savedLosers", Integer.toString(global.savedLosers));
            p.setProperty("missedProfitDollars", fmt(global.missedProfitDollars));
            p.setProperty("savedLossDollars", fmt(global.savedLossDollars));
            p.setProperty("netBenefitDollars", fmt(global.netBenefitDollars()));
            try (OutputStream out = Files.newOutputStream(healthPath)) {
                p.store(out, "Pre-trade calibration missed profit audit health");
            }
        } catch (IOException e) {
            System.out.println("PRE TRADE CALIBRATION AUDIT HEALTH WRITE FAILED: " + e.getMessage());
        }
    }

    private static String extractShadowId(String reason) {
        if (reason == null) {
            return "";
        }
        String marker = "shadowTradeId=";
        int start = reason.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int valueStart = start + marker.length();
        int valueEnd = reason.indexOf(' ', valueStart);
        return (valueEnd < 0 ? reason.substring(valueStart) : reason.substring(valueStart, valueEnd)).trim();
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

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeStrategy(String value) {
        return normalize(value).replace(' ', '_');
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

    private static double envDouble(String key, double fallback) {
        try {
            String value = env(key, "");
            return value.isBlank() ? fallback : Double.parseDouble(value);
        } catch (Exception e) {
            return fallback;
        }
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

    private static final class AuditRow {
        String auditId = "";
        String timestamp = "";
        String phase = "";
        String intervention = "";
        String ticker = "";
        String originalStrategy = "";
        String auditStrategy = "";
        int originalQuantity = 1;
        int adjustedQuantity = 0;
        String shadowTradeId = "";
        String reason = "";
    }

    private static final class ScoredAudit {
        AuditRow audit;
        String shadowTradeId = "";
        double pnlDollars;
        double pnlPercent;
        double maxGainPercent;
        double maxDrawdownPercent;
        double opportunityDollars;
    }

    private static final class Aggregate {
        final String scope;
        final String key;
        int closed;
        int missedWinners;
        int savedLosers;
        double missedProfitDollars;
        double savedLossDollars;
        double pnlPercentSum;
        double maxGainPercentSum;
        double maxDrawdownPercentSum;

        Aggregate(String scope, String key) {
            this.scope = scope;
            this.key = key;
        }

        double netBenefitDollars() {
            return savedLossDollars - missedProfitDollars;
        }

        double avgPnlPercent() {
            return closed <= 0 ? 0.0 : pnlPercentSum / closed;
        }

        double avgMaxGainPercent() {
            return closed <= 0 ? 0.0 : maxGainPercentSum / closed;
        }

        double avgMaxDrawdownPercent() {
            return closed <= 0 ? 0.0 : maxDrawdownPercentSum / closed;
        }
    }

    public static final class Result {
        public final int audits;
        public final int closedShadowProbes;
        public final int pendingShadowProbes;
        public final int missedWinners;
        public final int savedLosers;
        public final double missedProfitDollars;
        public final double savedLossDollars;
        public final double netBenefitDollars;
        public final Path reportPath;
        public final Path summaryPath;
        public final Path healthPath;

        Result(int audits,
               int closedShadowProbes,
               int pendingShadowProbes,
               int missedWinners,
               int savedLosers,
               double missedProfitDollars,
               double savedLossDollars,
               double netBenefitDollars,
               Path reportPath,
               Path summaryPath,
               Path healthPath) {
            this.audits = audits;
            this.closedShadowProbes = closedShadowProbes;
            this.pendingShadowProbes = pendingShadowProbes;
            this.missedWinners = missedWinners;
            this.savedLosers = savedLosers;
            this.missedProfitDollars = missedProfitDollars;
            this.savedLossDollars = savedLossDollars;
            this.netBenefitDollars = netBenefitDollars;
            this.reportPath = reportPath;
            this.summaryPath = summaryPath;
            this.healthPath = healthPath;
        }

        public String summary() {
            return "audits=" + audits +
                    " closedShadowProbes=" + closedShadowProbes +
                    " pendingShadowProbes=" + pendingShadowProbes +
                    " missedWinners=" + missedWinners +
                    " savedLosers=" + savedLosers +
                    " netBenefitDollars=" + fmt(netBenefitDollars) +
                    " reportPath=" + reportPath;
        }
    }
}
