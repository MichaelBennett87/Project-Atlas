package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Nightly learning layer for the run-all-day / evolve-after-close workflow.
 *
 * UnifiedStrategyMain collects market_features.csv, trade_outcomes.csv and
 * missed_trades.csv. After market close this engine distills those raw logs into
 * stable tomorrow-facing learning artifacts: per-strategy performance, rejected
 * opportunity patterns, exit-quality statistics, probability calibration,
 * feature importance, source reliability and catalyst-combination expectancy.
 *
 * This class is intentionally deterministic and dependency-free so it can run as
 * part of AutonomousCodeEvolutionMain without Python, databases or external ML
 * libraries.
 */
public class AutonomousLearningEngine {

    private final Path featurePath;
    private final Path outcomePath;
    private final Path missedPath;
    private final Path reportPath;
    private final Path propertiesPath;

    public AutonomousLearningEngine() {
        this(
                Path.of(System.getenv().getOrDefault("FEATURE_JOURNAL_PATH", "logs/market_features.csv")),
                Path.of(System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv")),
                Path.of(System.getenv().getOrDefault("MISSED_TRADE_JOURNAL_PATH", "logs/missed_trades.csv")),
                Path.of(System.getenv().getOrDefault("AI_NIGHTLY_LEARNING_REPORT_PATH", "logs/nightly_learning_report.txt")),
                Path.of(System.getenv().getOrDefault("AI_NIGHTLY_LEARNING_PROPERTIES_PATH", "logs/nightly_learning.properties"))
        );
    }

    public AutonomousLearningEngine(Path featurePath, Path outcomePath, Path missedPath, Path reportPath, Path propertiesPath) {
        this.featurePath = featurePath;
        this.outcomePath = outcomePath;
        this.missedPath = missedPath;
        this.reportPath = reportPath;
        this.propertiesPath = propertiesPath;
    }

    public LearningResult run() {
        List<FeatureRow> features = loadFeatures();
        List<OutcomeRow> outcomes = loadOutcomes();
        List<MissedRow> missed = loadMissed();

        LearningResult result = new LearningResult();
        result.featureRows = features.size();
        result.closedOutcomes = (int) outcomes.stream().filter(o -> o.closeLike).count();
        result.missedRows = missed.size();

        Map<String, OutcomeAggregate> byStrategy = aggregateOutcomes(outcomes, o -> safeKey(o.strategy));
        Map<String, OutcomeAggregate> bySide = aggregateOutcomes(outcomes, o -> safeKey(o.side));
        Map<String, OutcomeAggregate> bySource = aggregateBySource(features, outcomes);
        Map<String, OutcomeAggregate> byCatalystCombo = aggregateCatalystCombos(features, outcomes);
        Map<String, RejectionAggregate> byRejection = aggregateRejections(missed);
        Map<String, CalibrationBucket> calibration = buildCalibration(features, outcomes);
        Map<String, FeatureImportance> importance = buildFeatureImportance(features, outcomes);
        Map<String, ExitQuality> exitQuality = buildExitQuality(outcomes);

        writeReport(result, byStrategy, bySide, bySource, byCatalystCombo, byRejection, calibration, importance, exitQuality);
        writeProperties(result, byStrategy, bySide, bySource, byCatalystCombo, byRejection, calibration, importance, exitQuality);

        return result;
    }

    private List<FeatureRow> loadFeatures() {
        List<FeatureRow> out = new ArrayList<>();
        if (!Files.exists(featurePath)) return out;
        try (BufferedReader r = Files.newBufferedReader(featurePath, StandardCharsets.UTF_8)) {
            String headerLine = r.readLine();
            if (headerLine == null) return out;
            Header h = new Header(headerLine);
            String line;
            int max = intEnv("AI_NIGHTLY_LEARNING_MAX_FEATURE_ROWS", 100_000);
            while ((line = r.readLine()) != null && out.size() < max) {
                List<String> c = Csv.parse(line);
                FeatureRow row = new FeatureRow();
                row.ticker = cleanTicker(h.get(c, "ticker"));
                row.timestamp = parseIsoOrEpoch(h.get(c, "timestamp"));
                row.strategy = clean(h.get(c, "selectedStrategy"));
                row.action = clean(h.get(c, "selectedAction"));
                row.reason = h.get(c, "modelReason");
                row.newsSource = clean(h.get(c, "newsSource"));
                row.headline = h.get(c, "headline");
                row.pTarget = num(h.get(c, "pProfitTarget"), num(h.get(c, "probabilityTarget"), 0.0));
                row.pStop = num(h.get(c, "pStopLoss"), 0.0);
                row.ev = num(h.get(c, "expectedValuePercent"), 0.0);
                row.confidence = num(h.get(c, "modelConfidence"), 0.0);
                row.sentiment = num(h.get(c, "sentimentNet"), 0.0);
                row.catalyst = num(h.get(c, "catalystScore"), 0.0);
                row.rvol = Math.max(num(h.get(c, "rvol5"), 0.0), num(h.get(c, "rvol20"), 0.0));
                row.freshnessSeconds = num(h.get(c, "freshnessSeconds"), 999_999.0);
                row.return3 = num(h.get(c, "return3Bars"), 0.0);
                row.vwapDistance = num(h.get(c, "vwapDistance"), 0.0);
                row.atrPercent = num(h.get(c, "atrPercent14"), 0.0);
                row.rsi = num(h.get(c, "rsi14"), 50.0);
                row.bullishBreak = bool(h.get(c, "bullishBreak"));
                row.reclaimedVwap = bool(h.get(c, "reclaimedVwap"));
                row.failedBreakdown = bool(h.get(c, "failedBreakdown"));
                if (!row.ticker.isBlank()) out.add(row);
            }
        } catch (Exception e) {
            System.out.println("NIGHTLY LEARNING FEATURE LOAD FAILED: " + e.getMessage());
        }
        return out;
    }

    private List<OutcomeRow> loadOutcomes() {
        List<OutcomeRow> out = new ArrayList<>();
        if (!Files.exists(outcomePath)) return out;
        try (BufferedReader r = Files.newBufferedReader(outcomePath, StandardCharsets.UTF_8)) {
            String headerLine = r.readLine();
            if (headerLine == null) return out;
            Header h = new Header(headerLine);
            String line;
            while ((line = r.readLine()) != null) {
                List<String> c = Csv.parse(line);
                OutcomeRow row = new OutcomeRow();
                row.ticker = cleanTicker(h.get(c, "ticker"));
                row.eventType = clean(h.get(c, "eventType"));
                row.strategy = TradeOutcomeTrainingFilter.normalizeStrategy(h.get(c, "strategyName"));
                row.side = clean(h.get(c, "side"));
                row.reason = clean(h.get(c, "reason"));
                String syncedFromBroker = h.get(c, "syncedFromBroker");
                if (!TradeOutcomeTrainingFilter.isTrainingEligible(row.eventType, row.strategy, syncedFromBroker)) {
                    continue;
                }
                row.eventAtMs = (long) num(h.get(c, "eventAtMs"), 0.0);
                row.openedAtMs = (long) num(h.get(c, "openedAtMs"), 0.0);
                row.holdMs = (long) num(h.get(c, "holdMs"), 0.0);
                row.pnl = num(h.get(c, "realizedPnlDollars"), num(h.get(c, "realizedProfit"), num(h.get(c, "realizedPnl"), 0.0)));
                row.currentPnlPercent = num(h.get(c, "currentPnlPercent"), 0.0);
                row.maxGainPercent = num(h.get(c, "maxGainPercent"), num(h.get(c, "mfePercent"), 0.0));
                row.maxDrawdownPercent = num(h.get(c, "maxDrawdownPercent"), num(h.get(c, "maePercent"), 0.0));
                row.exitEfficiencyPercent = num(h.get(c, "exitEfficiencyPercent"), estimateExitEfficiency(row));
                row.giveBackPercent = num(h.get(c, "giveBackPercent"), Math.max(0.0, row.maxGainPercent - row.currentPnlPercent));
                row.closeLike = true;
                if (!row.ticker.isBlank()) out.add(row);
            }
        } catch (Exception e) {
            System.out.println("NIGHTLY LEARNING OUTCOME LOAD FAILED: " + e.getMessage());
        }
        return out;
    }

    private List<MissedRow> loadMissed() {
        List<MissedRow> out = new ArrayList<>();
        if (!Files.exists(missedPath)) return out;
        try (BufferedReader r = Files.newBufferedReader(missedPath, StandardCharsets.UTF_8)) {
            String headerLine = r.readLine();
            if (headerLine == null) return out;
            Header h = new Header(headerLine);
            String line;
            while ((line = r.readLine()) != null) {
                List<String> c = Csv.parse(line);
                MissedRow row = new MissedRow();
                row.ticker = cleanTicker(h.get(c, "ticker"));
                row.strategy = clean(h.get(c, "strategy"));
                row.direction = clean(h.get(c, "direction"));
                row.confidence = num(h.get(c, "confidence"), 0.0);
                row.ev = num(h.get(c, "ev"), 0.0);
                row.blockReason = clean(h.get(c, "blockReason"));
                row.reason = h.get(c, "reason");
                if (!row.ticker.isBlank()) out.add(row);
            }
        } catch (Exception e) {
            System.out.println("NIGHTLY LEARNING MISSED LOAD FAILED: " + e.getMessage());
        }
        return out;
    }

    private interface OutcomeKey { String key(OutcomeRow row); }

    private Map<String, OutcomeAggregate> aggregateOutcomes(List<OutcomeRow> outcomes, OutcomeKey keyFn) {
        Map<String, OutcomeAggregate> out = new LinkedHashMap<>();
        for (OutcomeRow row : outcomes) {
            if (!row.closeLike) continue;
            String key = keyFn.key(row);
            OutcomeAggregate agg = out.computeIfAbsent(key, k -> new OutcomeAggregate(k));
            agg.add(row);
        }
        return out;
    }

    private Map<String, OutcomeAggregate> aggregateBySource(List<FeatureRow> features, List<OutcomeRow> outcomes) {
        Map<String, FeatureRow> latestByTicker = latestFeatureByTicker(features);
        Map<String, OutcomeAggregate> out = new LinkedHashMap<>();
        for (OutcomeRow outcome : outcomes) {
            if (!outcome.closeLike) continue;
            FeatureRow f = latestByTicker.get(outcome.ticker);
            String source = f == null || f.newsSource.isBlank() ? "UNKNOWN_SOURCE" : f.newsSource;
            out.computeIfAbsent(source, OutcomeAggregate::new).add(outcome);
        }
        return out;
    }

    private Map<String, OutcomeAggregate> aggregateCatalystCombos(List<FeatureRow> features, List<OutcomeRow> outcomes) {
        Map<String, FeatureRow> latestByTicker = latestFeatureByTicker(features);
        Map<String, OutcomeAggregate> out = new LinkedHashMap<>();
        for (OutcomeRow outcome : outcomes) {
            if (!outcome.closeLike) continue;
            FeatureRow f = latestByTicker.get(outcome.ticker);
            String combo = catalystCombo(f, outcome);
            out.computeIfAbsent(combo, OutcomeAggregate::new).add(outcome);
        }
        return out;
    }

    private Map<String, FeatureRow> latestFeatureByTicker(List<FeatureRow> features) {
        Map<String, FeatureRow> out = new LinkedHashMap<>();
        for (FeatureRow f : features) {
            FeatureRow existing = out.get(f.ticker);
            if (existing == null || f.timestamp >= existing.timestamp) out.put(f.ticker, f);
        }
        return out;
    }

    private Map<String, RejectionAggregate> aggregateRejections(List<MissedRow> missed) {
        Map<String, RejectionAggregate> out = new LinkedHashMap<>();
        for (MissedRow row : missed) {
            String key = row.strategy + "|" + simplifyRejection(row.blockReason);
            RejectionAggregate agg = out.computeIfAbsent(key, RejectionAggregate::new);
            agg.count++;
            agg.avgConfidence += row.confidence;
            agg.avgEv += row.ev;
        }
        for (RejectionAggregate agg : out.values()) {
            if (agg.count > 0) {
                agg.avgConfidence /= agg.count;
                agg.avgEv /= agg.count;
            }
        }
        return out;
    }

    private Map<String, CalibrationBucket> buildCalibration(List<FeatureRow> features, List<OutcomeRow> outcomes) {
        Map<String, OutcomeRow> outcomeByTicker = latestCloseByTicker(outcomes);
        Map<String, CalibrationBucket> out = new LinkedHashMap<>();
        for (FeatureRow f : features) {
            OutcomeRow o = outcomeByTicker.get(f.ticker);
            if (o == null) continue;
            double p = clamp01(f.pTarget > 0.0 ? f.pTarget : f.confidence);
            int bucketLow = Math.min(90, Math.max(0, ((int) Math.floor(p * 10.0)) * 10));
            String key = bucketLow + "-" + (bucketLow + 10) + "%";
            CalibrationBucket b = out.computeIfAbsent(key, CalibrationBucket::new);
            b.samples++;
            b.predicted += p;
            if (o.pnl > 0.0) b.wins++;
        }
        for (CalibrationBucket b : out.values()) {
            if (b.samples > 0) b.predicted /= b.samples;
        }
        return out;
    }

    private Map<String, OutcomeRow> latestCloseByTicker(List<OutcomeRow> outcomes) {
        Map<String, OutcomeRow> out = new LinkedHashMap<>();
        for (OutcomeRow o : outcomes) {
            if (!o.closeLike) continue;
            OutcomeRow existing = out.get(o.ticker);
            if (existing == null || o.eventAtMs >= existing.eventAtMs) out.put(o.ticker, o);
        }
        return out;
    }

    private Map<String, FeatureImportance> buildFeatureImportance(List<FeatureRow> features, List<OutcomeRow> outcomes) {
        Map<String, OutcomeRow> outcomeByTicker = latestCloseByTicker(outcomes);
        Map<String, List<Double>> wins = new LinkedHashMap<>();
        Map<String, List<Double>> losses = new LinkedHashMap<>();
        String[] names = new String[]{"pTarget", "expectedValue", "confidence", "rvol", "sentiment", "negativeSentimentAbs", "catalyst", "freshnessSeconds", "return3Bars", "vwapDistance", "atrPercent", "rsi14", "bullishBreak", "reclaimedVwap", "failedBreakdown"};
        for (String n : names) { wins.put(n, new ArrayList<>()); losses.put(n, new ArrayList<>()); }
        for (FeatureRow f : features) {
            OutcomeRow o = outcomeByTicker.get(f.ticker);
            if (o == null) continue;
            Map<String, Double> vals = featureValues(f);
            for (String n : names) {
                (o.pnl > 0.0 ? wins : losses).get(n).add(vals.getOrDefault(n, 0.0));
            }
        }
        Map<String, FeatureImportance> out = new LinkedHashMap<>();
        for (String n : names) {
            FeatureImportance fi = new FeatureImportance(n);
            fi.winAvg = avg(wins.get(n));
            fi.lossAvg = avg(losses.get(n));
            fi.samples = wins.get(n).size() + losses.get(n).size();
            fi.directionalLift = fi.winAvg - fi.lossAvg;
            out.put(n, fi);
        }
        return out;
    }

    private Map<String, Double> featureValues(FeatureRow f) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("pTarget", f.pTarget);
        out.put("expectedValue", f.ev);
        out.put("confidence", f.confidence);
        out.put("rvol", f.rvol);
        out.put("sentiment", f.sentiment);
        out.put("negativeSentimentAbs", Math.max(0.0, -f.sentiment));
        out.put("catalyst", f.catalyst);
        out.put("freshnessSeconds", f.freshnessSeconds);
        out.put("return3Bars", f.return3);
        out.put("vwapDistance", f.vwapDistance);
        out.put("atrPercent", f.atrPercent);
        out.put("rsi14", f.rsi);
        out.put("bullishBreak", f.bullishBreak ? 1.0 : 0.0);
        out.put("reclaimedVwap", f.reclaimedVwap ? 1.0 : 0.0);
        out.put("failedBreakdown", f.failedBreakdown ? 1.0 : 0.0);
        return out;
    }

    private Map<String, ExitQuality> buildExitQuality(List<OutcomeRow> outcomes) {
        Map<String, ExitQuality> out = new LinkedHashMap<>();
        for (OutcomeRow o : outcomes) {
            if (!o.closeLike) continue;
            ExitQuality q = out.computeIfAbsent(safeKey(o.strategy), ExitQuality::new);
            q.trades++;
            q.totalHoldMs += Math.max(0L, o.holdMs);
            q.totalMfe += o.maxGainPercent;
            q.totalMae += o.maxDrawdownPercent;
            q.totalExitEfficiency += o.exitEfficiencyPercent;
            q.totalGiveBack += o.giveBackPercent;
            if (o.reason.contains("MAX_HOLD") || o.reason.contains("TIME")) q.timeExits++;
            if (o.reason.contains("STOP")) q.stopExits++;
            if (o.reason.contains("TRAIL")) q.trailExits++;
        }
        return out;
    }

    private void writeReport(
            LearningResult result,
            Map<String, OutcomeAggregate> byStrategy,
            Map<String, OutcomeAggregate> bySide,
            Map<String, OutcomeAggregate> bySource,
            Map<String, OutcomeAggregate> byCatalystCombo,
            Map<String, RejectionAggregate> byRejection,
            Map<String, CalibrationBucket> calibration,
            Map<String, FeatureImportance> importance,
            Map<String, ExitQuality> exitQuality
    ) {
        StringBuilder b = new StringBuilder();
        b.append("NIGHTLY AUTONOMOUS LEARNING REPORT\n");
        b.append("generatedAt=").append(Instant.now()).append('\n');
        b.append("featureRows=").append(result.featureRows).append('\n');
        b.append("closedOutcomes=").append(result.closedOutcomes).append('\n');
        b.append("missedRows=").append(result.missedRows).append('\n');
        b.append("goal=Run UnifiedStrategyMain during the day, then run AutonomousCodeEvolutionMain after close so tomorrow starts smarter.\n\n");

        appendOutcomeSection(b, "STRATEGY-SPECIFIC OUTCOME LEARNING", byStrategy, 25);
        appendOutcomeSection(b, "SIDE-SPECIFIC OUTCOME LEARNING", bySide, 10);
        appendOutcomeSection(b, "NEWS SOURCE RELIABILITY", bySource, 25);
        appendOutcomeSection(b, "CATALYST COMBINATION LEARNING", byCatalystCombo, 30);

        b.append("\nREJECTED / MISSED OPPORTUNITY LEARNING\n");
        byRejection.values().stream().sorted(Comparator.comparingInt((RejectionAggregate r) -> r.count).reversed()).limit(30).forEach(r ->
                b.append(r.key).append(" count=").append(r.count)
                        .append(" avgConfidence=").append(fmt(r.avgConfidence))
                        .append(" avgEV=").append(fmt(r.avgEv)).append('\n'));

        b.append("\nPROBABILITY CALIBRATION\n");
        for (CalibrationBucket c : calibration.values()) {
            b.append(c.bucket).append(" samples=").append(c.samples)
                    .append(" predicted=").append(fmt(c.predicted))
                    .append(" actualWinRate=").append(fmt(c.actualWinRate()))
                    .append(" calibrationError=").append(fmt(c.calibrationError())).append('\n');
        }

        b.append("\nFEATURE IMPORTANCE APPROXIMATION\n");
        importance.values().stream().sorted((a, z) -> Double.compare(Math.abs(z.directionalLift), Math.abs(a.directionalLift))).forEach(i ->
                b.append(i.name).append(" samples=").append(i.samples)
                        .append(" winAvg=").append(fmt(i.winAvg))
                        .append(" lossAvg=").append(fmt(i.lossAvg))
                        .append(" directionalLift=").append(fmt(i.directionalLift)).append('\n'));

        b.append("\nEXIT QUALITY\n");
        for (ExitQuality q : exitQuality.values()) {
            b.append(q.strategy).append(" trades=").append(q.trades)
                    .append(" avgHoldMinutes=").append(fmt(q.avgHoldMinutes()))
                    .append(" avgMFE=").append(fmt(q.avgMfe()))
                    .append(" avgMAE=").append(fmt(q.avgMae()))
                    .append(" avgExitEfficiency=").append(fmt(q.avgExitEfficiency()))
                    .append(" avgGiveBack=").append(fmt(q.avgGiveBack()))
                    .append(" timeExits=").append(q.timeExits)
                    .append(" stopExits=").append(q.stopExits)
                    .append(" trailExits=").append(q.trailExits).append('\n');
        }

        FilesUtil.writeString(reportPath, b.toString());
    }

    private void writeProperties(
            LearningResult result,
            Map<String, OutcomeAggregate> byStrategy,
            Map<String, OutcomeAggregate> bySide,
            Map<String, OutcomeAggregate> bySource,
            Map<String, OutcomeAggregate> byCatalystCombo,
            Map<String, RejectionAggregate> byRejection,
            Map<String, CalibrationBucket> calibration,
            Map<String, FeatureImportance> importance,
            Map<String, ExitQuality> exitQuality
    ) {
        try {
            FilesUtil.ensureParent(propertiesPath);
            Properties p = new Properties();
            p.setProperty("updatedAt", Instant.now().toString());
            p.setProperty("featureRows", Integer.toString(result.featureRows));
            p.setProperty("closedOutcomes", Integer.toString(result.closedOutcomes));
            p.setProperty("missedRows", Integer.toString(result.missedRows));
            writeOutcomeProps(p, "strategy", byStrategy);
            writeOutcomeProps(p, "side", bySide);
            writeOutcomeProps(p, "source", bySource);
            writeOutcomeProps(p, "combo", byCatalystCombo);
            int i = 0;
            for (FeatureImportance fi : importance.values().stream().sorted((a, z) -> Double.compare(Math.abs(z.directionalLift), Math.abs(a.directionalLift))).toList()) {
                String prefix = "featureImportance.f" + (++i) + ".";
                p.setProperty(prefix + "name", fi.name);
                p.setProperty(prefix + "samples", Integer.toString(fi.samples));
                p.setProperty(prefix + "directionalLift", Double.toString(fi.directionalLift));
                if (i >= 25) break;
            }
            i = 0;
            for (CalibrationBucket c : calibration.values()) {
                String prefix = "calibration.c" + (++i) + ".";
                p.setProperty(prefix + "bucket", c.bucket);
                p.setProperty(prefix + "samples", Integer.toString(c.samples));
                p.setProperty(prefix + "predicted", Double.toString(c.predicted));
                p.setProperty(prefix + "actualWinRate", Double.toString(c.actualWinRate()));
                p.setProperty(prefix + "calibrationError", Double.toString(c.calibrationError()));
            }
            i = 0;
            for (RejectionAggregate r : byRejection.values().stream().sorted(Comparator.comparingInt((RejectionAggregate r) -> r.count).reversed()).toList()) {
                String prefix = "rejection.r" + (++i) + ".";
                p.setProperty(prefix + "key", r.key);
                p.setProperty(prefix + "count", Integer.toString(r.count));
                p.setProperty(prefix + "avgConfidence", Double.toString(r.avgConfidence));
                p.setProperty(prefix + "avgExpectedValue", Double.toString(r.avgEv));
                if (i >= 25) break;
            }
            try (var out = Files.newOutputStream(propertiesPath)) {
                p.store(out, "Nightly autonomous learning artifacts for next-day policy evolution.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write nightly learning properties: " + e.getMessage(), e);
        }
    }

    private void appendOutcomeSection(StringBuilder b, String title, Map<String, OutcomeAggregate> data, int limit) {
        b.append('\n').append(title).append('\n');
        data.values().stream().sorted(Comparator.comparingDouble(OutcomeAggregate::expectancy).reversed()).limit(limit).forEach(a ->
                b.append(a.key).append(" trades=").append(a.trades)
                        .append(" wins=").append(a.wins)
                        .append(" pnl=").append(fmt(a.pnl))
                        .append(" winRate=").append(fmt(a.winRate()))
                        .append(" profitFactor=").append(fmt(a.profitFactor()))
                        .append(" expectancy=").append(fmt(a.expectancy())).append('\n'));
    }

    private void writeOutcomeProps(Properties p, String group, Map<String, OutcomeAggregate> data) {
        int i = 0;
        for (OutcomeAggregate a : data.values().stream().sorted(Comparator.comparingDouble(OutcomeAggregate::expectancy).reversed()).toList()) {
            String prefix = group + ".g" + (++i) + ".";
            p.setProperty(prefix + "key", a.key);
            p.setProperty(prefix + "trades", Integer.toString(a.trades));
            p.setProperty(prefix + "wins", Integer.toString(a.wins));
            p.setProperty(prefix + "pnl", Double.toString(a.pnl));
            p.setProperty(prefix + "winRate", Double.toString(a.winRate()));
            p.setProperty(prefix + "profitFactor", Double.toString(a.profitFactor()));
            p.setProperty(prefix + "expectancy", Double.toString(a.expectancy()));
            if (i >= 50) break;
        }
    }

    private static String catalystCombo(FeatureRow f, OutcomeRow o) {
        if (f == null) return safeKey(o.strategy) + "|NO_FEATURE_MATCH";
        String direction = o.side == null || o.side.isBlank() ? "UNKNOWN_SIDE" : o.side;
        String sentimentBucket = f.sentiment <= -0.35 ? "VERY_NEGATIVE" : f.sentiment <= -0.15 ? "NEGATIVE" : f.sentiment >= 0.35 ? "VERY_POSITIVE" : f.sentiment >= 0.15 ? "POSITIVE" : "NEUTRAL";
        String catalystBucket = f.catalyst >= 0.80 ? "A_CATALYST" : f.catalyst >= 0.50 ? "B_CATALYST" : f.catalyst > 0.0 ? "C_CATALYST" : "NO_CATALYST";
        String rvolBucket = f.rvol >= 10.0 ? "RVOL_10_PLUS" : f.rvol >= 5.0 ? "RVOL_5_PLUS" : f.rvol >= 2.0 ? "RVOL_2_PLUS" : "LOW_RVOL";
        String freshnessBucket = f.freshnessSeconds <= 60 ? "UNDER_60S" : f.freshnessSeconds <= 300 ? "UNDER_5M" : f.freshnessSeconds <= 900 ? "UNDER_15M" : "OLD";
        return direction + "|" + safeKey(o.strategy) + "|" + sentimentBucket + "|" + catalystBucket + "|" + rvolBucket + "|" + freshnessBucket;
    }

    private static String simplifyRejection(String blockReason) {
        String b = blockReason == null ? "UNKNOWN" : blockReason.toUpperCase();
        int colon = b.indexOf(':');
        if (colon > 0) b = b.substring(0, colon);
        if (b.length() > 80) b = b.substring(0, 80);
        return b.isBlank() ? "UNKNOWN" : b;
    }

    private static double estimateExitEfficiency(OutcomeRow row) {
        if (row.maxGainPercent <= 0.0) return row.currentPnlPercent > 0.0 ? 1.0 : 0.0;
        return clamp01(row.currentPnlPercent / row.maxGainPercent);
    }

    private static double clamp01(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v)); }
    private static double avg(List<Double> values) { if (values == null || values.isEmpty()) return 0.0; double s = 0.0; for (double v : values) s += v; return s / values.size(); }
    private static String cleanTicker(String v) { return v == null ? "" : v.trim().toUpperCase(); }
    private static String clean(String v) { return v == null ? "" : v.trim().toUpperCase(); }
    private static String safeKey(String v) { String s = clean(v); return s.isBlank() ? "UNKNOWN" : s; }
    private static String fmt(double v) { return String.format(java.util.Locale.US, "%.6f", Double.isNaN(v) || Double.isInfinite(v) ? 0.0 : v); }
    private static boolean bool(String v) { return "true".equalsIgnoreCase(v == null ? "" : v.trim()); }
    private static double num(String v, double fallback) { try { return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); } catch (Exception e) { return fallback; } }
    private static int intEnv(String k, int f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Integer.parseInt(v.trim()); } catch (Exception e) { return f; } }
    private static long parseIsoOrEpoch(String v) {
        try { if (v == null || v.isBlank()) return 0L; if (v.matches("\\d+")) return Long.parseLong(v); return Instant.parse(v.trim()).toEpochMilli(); } catch (Exception e) { return 0L; }
    }

    static final class FeatureRow {
        String ticker, strategy, action, newsSource, headline, reason;
        long timestamp;
        double pTarget, pStop, ev, confidence, sentiment, catalyst, rvol, freshnessSeconds, return3, vwapDistance, atrPercent, rsi;
        boolean bullishBreak, reclaimedVwap, failedBreakdown;
    }
    static final class OutcomeRow {
        String ticker, eventType, strategy, side, reason;
        long openedAtMs, eventAtMs, holdMs;
        double pnl, currentPnlPercent, maxGainPercent, maxDrawdownPercent, exitEfficiencyPercent, giveBackPercent;
        boolean closeLike;
    }
    static final class MissedRow { String ticker, strategy, direction, blockReason, reason; double confidence, ev; }
    static final class OutcomeAggregate {
        final String key; int trades, wins, losses; double pnl, grossWin, grossLoss;
        OutcomeAggregate(String key) { this.key = key; }
        void add(OutcomeRow o) { trades++; pnl += o.pnl; if (o.pnl > 0) { wins++; grossWin += o.pnl; } else if (o.pnl < 0) { losses++; grossLoss += Math.abs(o.pnl); } }
        double winRate() { return trades <= 0 ? 0.0 : wins / (double) trades; }
        double expectancy() { return trades <= 0 ? 0.0 : pnl / trades; }
        double profitFactor() { return grossLoss <= 0.0 ? (grossWin > 0.0 ? 9.0 : 0.0) : grossWin / grossLoss; }
    }
    static final class RejectionAggregate { final String key; int count; double avgConfidence, avgEv; RejectionAggregate(String key) { this.key = key; } }
    static final class CalibrationBucket { final String bucket; int samples, wins; double predicted; CalibrationBucket(String bucket) { this.bucket = bucket; } double actualWinRate() { return samples <= 0 ? 0.0 : wins / (double) samples; } double calibrationError() { return actualWinRate() - predicted; } }
    static final class FeatureImportance { final String name; int samples; double winAvg, lossAvg, directionalLift; FeatureImportance(String name) { this.name = name; } }
    static final class ExitQuality {
        final String strategy; int trades, timeExits, stopExits, trailExits; long totalHoldMs; double totalMfe, totalMae, totalExitEfficiency, totalGiveBack;
        ExitQuality(String strategy) { this.strategy = strategy; }
        double avgHoldMinutes() { return trades <= 0 ? 0.0 : totalHoldMs / 60000.0 / trades; }
        double avgMfe() { return trades <= 0 ? 0.0 : totalMfe / trades; }
        double avgMae() { return trades <= 0 ? 0.0 : totalMae / trades; }
        double avgExitEfficiency() { return trades <= 0 ? 0.0 : totalExitEfficiency / trades; }
        double avgGiveBack() { return trades <= 0 ? 0.0 : totalGiveBack / trades; }
    }
    public static final class LearningResult {
        public int featureRows, closedOutcomes, missedRows;
        public String summary() { return "nightlyLearning featureRows=" + featureRows + " closedOutcomes=" + closedOutcomes + " missedRows=" + missedRows; }
    }
    static final class Header {
        private final Map<String, Integer> index = new LinkedHashMap<>();
        Header(String headerLine) { List<String> cols = Csv.parse(headerLine); for (int i = 0; i < cols.size(); i++) index.put(cols.get(i).trim(), i); }
        String get(List<String> cols, String name) { Integer i = index.get(name); return i == null || i < 0 || i >= cols.size() ? "" : cols.get(i); }
    }
    static final class Csv {
        static List<String> parse(String line) {
            List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q = false; if (line == null) return out;
            for (int i = 0; i < line.length(); i++) { char ch = line.charAt(i); if (q) { if (ch == '"') { if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false; } else cur.append(ch); } else { if (ch == ',') { out.add(cur.toString()); cur.setLength(0); } else if (ch == '"') q = true; else cur.append(ch); } }
            out.add(cur.toString()); return out;
        }
    }
    static final class FilesUtil {
        static void ensureParent(Path p) throws IOException { Path parent = p.getParent(); if (parent != null) Files.createDirectories(parent); }
        static void writeString(Path p, String s) { try { ensureParent(p); Files.writeString(p, s, StandardCharsets.UTF_8); } catch (IOException e) { throw new RuntimeException(e); } }
    }
}
