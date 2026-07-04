package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline historical training/replay entry point.
 *
 * This does not place live orders. It scans local historical CSV files, extracts ticker behavior
 * metrics, writes a replay-cluster report, and then runs the existing HistoricalReplayEngine over
 * feature/outcome journals. Use this after market close as part of the autonomous research phase.
 */
public class OfflineReplayClusterMain {
    public static void main(String[] args) {
        HistoricalMarketDataRepository repo = new HistoricalMarketDataRepository();
        Path report = Path.of(System.getenv().getOrDefault("OFFLINE_REPLAY_CLUSTER_REPORT", "logs/offline_replay_cluster_report.csv"));
        int maxRows = Math.max(500, envInt("OFFLINE_REPLAY_MAX_ROWS_PER_FILE", 250_000));
        int maxFiles = Math.max(1, envInt("OFFLINE_REPLAY_MAX_FILES", 500));
        List<Path> files = repo.csvFiles();
        int processedFiles = 0;
        int processedBars = 0;
        Map<String, Stats> stats = new LinkedHashMap<>();
        for (Path file : files) {
            if (processedFiles >= maxFiles) break;
            List<HistoricalMarketDataRepository.HistoricalBar> bars = repo.loadBars(file, maxRows);
            if (bars.size() < 2) continue;
            processedFiles++;
            processedBars += bars.size();
            for (int i = 1; i < bars.size(); i++) {
                HistoricalMarketDataRepository.HistoricalBar prev = bars.get(i - 1);
                HistoricalMarketDataRepository.HistoricalBar cur = bars.get(i);
                if (!prev.ticker.equals(cur.ticker) || prev.close <= 0.0 || cur.close <= 0.0) continue;
                double ret = (cur.close - prev.close) / prev.close;
                double range = cur.close <= 0.0 ? 0.0 : Math.max(0.0, (cur.high - cur.low) / cur.close);
                stats.computeIfAbsent(cur.ticker, ignored -> new Stats()).add(ret, range, cur.volume);
            }
        }
        write(report, stats, processedFiles, processedBars, repo.root());
        HistoricalReplayEngine.ReplayResult replay = new HistoricalReplayEngine().runReplay();
        System.out.println("OFFLINE REPLAY CLUSTER COMPLETE: files=" + processedFiles + " bars=" + processedBars +
                " tickers=" + stats.size() + " historicalReplay=" + replay.summary() + " report=" + report);
    }

    private static void write(Path report, Map<String, Stats> stats, int files, int bars, Path root) {
        try {
            Path parent = report.getParent(); if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(report, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                w.write("generatedAt,root,files,bars,tickers\n");
                w.write(csv(Instant.now().toString()) + "," + csv(root.toString()) + "," + files + "," + bars + "," + stats.size() + "\n\n");
                w.write("ticker,samples,avgReturn,winRate,avgRange,avgVolume,parabolicScore\n");
                stats.entrySet().stream()
                        .sorted((a,b) -> Double.compare(b.getValue().parabolicScore(), a.getValue().parabolicScore()))
                        .forEach(e -> {
                            try {
                                Stats s = e.getValue();
                                w.write(csv(e.getKey()) + "," + s.samples + "," + fmt(s.avgReturn()) + "," + fmt(s.winRate()) + "," + fmt(s.avgRange()) + "," + fmt(s.avgVolume()) + "," + fmt(s.parabolicScore()) + "\n");
                            } catch (Exception ignored) {}
                        });
            }
        } catch (Exception e) {
            System.out.println("OFFLINE REPLAY CLUSTER REPORT FAILED: " + e.getMessage());
        }
    }

    static final class Stats {
        int samples, wins; double sumReturn, sumRange, sumVolume, maxReturn, maxRange;
        void add(double ret, double range, double volume) { samples++; if (ret > 0) wins++; sumReturn += ret; sumRange += range; sumVolume += Math.max(0.0, volume); maxReturn = Math.max(maxReturn, ret); maxRange = Math.max(maxRange, range); }
        double avgReturn() { return samples == 0 ? 0 : sumReturn / samples; }
        double winRate() { return samples == 0 ? 0 : wins * 1.0 / samples; }
        double avgRange() { return samples == 0 ? 0 : sumRange / samples; }
        double avgVolume() { return samples == 0 ? 0 : sumVolume / samples; }
        double parabolicScore() { return clamp(Math.max(0.0, maxReturn) / 0.25 * 0.45 + maxRange / 0.25 * 0.35 + Math.log10(Math.max(1.0, avgVolume())) / 9.0 * 0.20); }
    }
    static double clamp(double v) { if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0; return Math.max(0.0, Math.min(1.0, v)); }
    static String fmt(double v) { return String.format(Locale.ROOT, "%.8f", v); }
    static String csv(String v) { String s = v == null ? "" : v.replace("\r", " ").replace("\n", " "); return '"' + s.replace("\"", "\"\"") + '"'; }
    static int envInt(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch (Exception e) { return fallback; } }
}
