package com.bot.intelligence;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Memory-safe offline historical replay approximation.
 *
 * The previous implementation loaded every feature row and every replay score
 * into ArrayLists. With a full-day/full-market scanner this can easily create
 * millions of rows and crash the nightly evolution process with Java heap OOM.
 *
 * This version streams the feature journal line-by-line and keeps only the top
 * N replay scores needed for the report. It still counts every processed row,
 * but it does not retain the whole file in memory.
 */
public class HistoricalReplayEngine {
    private final Path featurePath = Path.of(System.getenv().getOrDefault("FEATURE_JOURNAL_PATH", "logs/market_features.csv"));
    private final Path outcomePath = Path.of(System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv"));
    private final Path reportPath = Path.of(System.getenv().getOrDefault("HISTORICAL_REPLAY_REPORT_PATH", "logs/historical_replay_report.csv"));

    private final int maxRowsToProcess = Math.max(1_000, intEnv("AI_REPLAY_MAX_FEATURE_ROWS", 250_000));
    private final int topScoreLimit = Math.max(100, intEnv("AI_REPLAY_TOP_SCORE_LIMIT", 1_000));

    public ReplayResult runReplay() {
        Map<String, Stats> byStrategy = loadOutcomeStats(outcomePath);
        PriorityQueue<ReplayScore> topScores = new PriorityQueue<>(Comparator.comparingDouble(s -> s.quality));

        int rowsSeen = 0;
        int rowsProcessed = 0;
        boolean truncated = false;

        if (Files.exists(featurePath)) {
            try (BufferedReader r = Files.newBufferedReader(featurePath, StandardCharsets.UTF_8)) {
                String header = r.readLine();
                if (header != null) {
                    CsvHeader h = new CsvHeader(header);
                    String line;
                    while ((line = r.readLine()) != null) {
                        rowsSeen++;
                        if (rowsProcessed >= maxRowsToProcess) {
                            truncated = true;
                            continue;
                        }
                        Row row = Row.from(h, parse(line), line);
                        ReplayScore score = score(row, byStrategy);
                        addTopScore(topScores, score);
                        rowsProcessed++;
                    }
                }
            } catch (Exception e) {
                System.out.println("Historical replay feature stream failed: " + e.getMessage());
            }
        }

        List<ReplayScore> sortedTop = new ArrayList<>(topScores);
        sortedTop.sort(Comparator.comparingDouble((ReplayScore s) -> s.quality).reversed());
        writeReport(sortedTop, byStrategy, rowsSeen, rowsProcessed, truncated);
        return new ReplayResult(rowsProcessed, rowsSeen, byStrategy.size(), sortedTop.isEmpty() ? 0 : sortedTop.get(0).quality, truncated);
    }

    private ReplayScore score(Row r, Map<String, Stats> byStrategy) {
        double p = clamp(r.pTarget > 0 ? r.pTarget : 0.35 + r.proposalScore * 0.35);
        double ev = r.expectedValue != 0 ? r.expectedValue : p * 5.0 - (1 - p) * 2.5;
        double quality = ev + r.proposalScore * 2.0 + (r.hasStructure ? 0.35 : 0.0) + (r.hasRvol ? 0.25 : -0.15);
        Stats s = byStrategy.get(normalize(r.selectedStrategy));
        if (s != null && s.trades >= 3) {
            quality += Math.min(2.0, s.expectancy() * 0.10);
            quality += Math.min(1.5, s.profitFactor() - 1.0);
        }
        return new ReplayScore(r.ticker, normalize(r.selectedStrategy), quality, ev, p, r.proposalScore);
    }

    private void addTopScore(PriorityQueue<ReplayScore> topScores, ReplayScore score) {
        if (topScores.size() < topScoreLimit) {
            topScores.add(score);
        } else if (!topScores.isEmpty() && score.quality > topScores.peek().quality) {
            topScores.poll();
            topScores.add(score);
        }
    }

    private void writeReport(List<ReplayScore> scores, Map<String, Stats> byStrategy,
                             int rowsSeen, int rowsProcessed, boolean truncated) {
        StringBuilder b = new StringBuilder("rank,ticker,strategy,quality,expectedValue,pTarget,proposalScore\n");
        int limit = Math.min(topScoreLimit, scores.size());
        for (int i = 0; i < limit; i++) {
            ReplayScore s = scores.get(i);
            b.append(i + 1).append(',').append(s.ticker).append(',').append(s.strategy).append(',')
                    .append(s.quality).append(',').append(s.ev).append(',').append(s.p).append(',').append(s.proposal).append('\n');
        }
        b.append("\nsummary,rowsSeen,rowsProcessed,truncated,maxRowsToProcess,topScoreLimit\n");
        b.append("historicalReplay,").append(rowsSeen).append(',').append(rowsProcessed).append(',')
                .append(truncated).append(',').append(maxRowsToProcess).append(',').append(topScoreLimit).append('\n');
        b.append("\nstrategy,trades,pnl,profitFactor,expectancy\n");
        for (Map.Entry<String, Stats> e : byStrategy.entrySet()) {
            Stats s = e.getValue();
            b.append(e.getKey()).append(',').append(s.trades).append(',').append(s.pnl).append(',')
                    .append(s.profitFactor()).append(',').append(s.expectancy()).append('\n');
        }
        AutonomousEvolutionSuite.FilesUtil.writeString(reportPath, b.toString());
    }

    private Map<String, Stats> loadOutcomeStats(Path p) {
        Map<String, Stats> out = new LinkedHashMap<>();
        if (!Files.exists(p)) return out;
        int maxOutcomeRows = Math.max(1_000, intEnv("AI_REPLAY_MAX_OUTCOME_ROWS", 250_000));
        int processed = 0;
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header == null) return out;
            CsvHeader h = new CsvHeader(header);
            String line;
            while ((line = r.readLine()) != null && processed < maxOutcomeRows) {
                processed++;
                List<String> c = parse(line);
                String type = h.get(c, "eventType");
                String strategy = normalize(h.get(c, "strategyName"));
                String syncedFromBroker = h.get(c, "syncedFromBroker");
                if (!TradeOutcomeTrainingFilter.isTrainingEligible(type, strategy, syncedFromBroker)) continue;
                double pnl = num(h.get(c, "realizedPnlDollars"), 0);
                out.computeIfAbsent(strategy, k -> new Stats()).add(pnl);
            }
        } catch (Exception e) {
            System.out.println("Historical replay outcome load failed: " + e.getMessage());
        }
        return out;
    }

    static final class Row {
        String ticker;
        String selectedStrategy;
        double pTarget;
        double expectedValue;
        double proposalScore;
        boolean hasStructure;
        boolean hasRvol;

        static Row from(CsvHeader h, List<String> c, String raw) {
            Row r = new Row();
            r.ticker = h.get(c, "ticker");
            r.selectedStrategy = h.get(c, "selectedStrategy");
            r.pTarget = num(h.get(c, "pTarget"), num(h.get(c, "probabilityTarget"), 0));
            r.expectedValue = num(h.get(c, "expectedValuePercent"), num(h.get(c, "ev"), 0));
            r.proposalScore = num(h.get(c, "proposalScore"), 0);
            String lower = raw == null ? "" : raw.toLowerCase();
            r.hasStructure = bool(h.get(c, "bullishBreak")) || bool(h.get(c, "reclaimedVwap")) || lower.contains("bullish break") || lower.contains("vwap strength");
            r.hasRvol = num(h.get(c, "rvol5"), 0) > 0 || num(h.get(c, "rvol20"), 0) > 0 || !lower.contains("no rvol");
            return r;
        }
    }

    static final class ReplayScore {
        String ticker, strategy;
        double quality, ev, p, proposal;
        ReplayScore(String t, String s, double q, double e, double p, double pr) {
            ticker = t; strategy = s; quality = q; ev = e; this.p = p; proposal = pr;
        }
    }

    static final class Stats {
        int trades, wins;
        double pnl, grossWin, grossLoss;
        void add(double v) { trades++; pnl += v; if (v > 0) { wins++; grossWin += v; } else grossLoss += Math.abs(v); }
        double expectancy() { return trades == 0 ? 0 : pnl / trades; }
        double profitFactor() { return grossLoss == 0 ? (grossWin > 0 ? 9 : 1) : grossWin / grossLoss; }
    }

    public static final class ReplayResult {
        public final int rows, rowsSeen, strategies;
        public final double topQuality;
        public final boolean truncated;
        ReplayResult(int r, int seen, int s, double q, boolean t) { rows = r; rowsSeen = seen; strategies = s; topQuality = q; truncated = t; }
        public String summary() { return "rows=" + rows + " rowsSeen=" + rowsSeen + " strategies=" + strategies + " topQuality=" + topQuality + " truncated=" + truncated; }
    }

    static final class CsvHeader {
        final Map<String, Integer> idx = new LinkedHashMap<>();
        CsvHeader(String h) { List<String> c = parse(h); for (int i = 0; i < c.size(); i++) idx.put(c.get(i).trim(), i); }
        String get(List<String> c, String n) { Integer i = idx.get(n); return i == null || i < 0 || i >= c.size() ? "" : c.get(i); }
    }

    static List<String> parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        if (line == null) return out;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else q = false;
                } else cur.append(ch);
            } else {
                if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
                else if (ch == '"') q = true;
                else cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    static double num(String v, double f) { try { return v == null || v.isBlank() ? f : Double.parseDouble(v.trim()); } catch (Exception e) { return f; } }
    static boolean bool(String v) { return "true".equalsIgnoreCase(v == null ? "" : v.trim()); }
    static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
    static String normalize(String v) { return v == null || v.isBlank() ? "UNKNOWN" : v.trim().toUpperCase(); }
    static int intEnv(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch (Exception e) { return fallback; } }
}
