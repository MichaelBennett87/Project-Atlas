package com.bot.intelligence.historical;

import com.bot.intelligence.HistoricalMarketDataRepository;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds labeled training rows from the local historical repository. */
public final class TrainingDatasetBuilder {
    private final HistoricalMarketDataRepository repo = new HistoricalMarketDataRepository();

    public Result build() {
        Path out = Path.of(System.getenv().getOrDefault("NIGHTLY_TRAINING_DATASET_PATH", "logs/nightly_training_dataset.csv"));
        int maxFiles = Math.max(1, intEnv("NIGHTLY_TRAINING_MAX_FILES", 750));
        int maxRowsPerFile = Math.max(100, intEnv("NIGHTLY_TRAINING_MAX_ROWS_PER_FILE", 200_000));
        int files = 0, rows = 0, labels = 0;
        Map<String, TickerStats> stats = new LinkedHashMap<>();
        try {
            Path parent = out.getParent(); if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                w.write("generatedAt,ticker,timestamp,open,high,low,close,volume,nextReturn,nextRange,upNext,parabolicLabel");
                w.newLine();
                for (Path file : repo.csvFiles()) {
                    if (files >= maxFiles) break;
                    List<HistoricalMarketDataRepository.HistoricalBar> bars = repo.loadBars(file, maxRowsPerFile);
                    if (bars.size() < 3) continue;
                    files++;
                    rows += bars.size();
                    for (int i = 0; i < bars.size() - 1; i++) {
                        HistoricalMarketDataRepository.HistoricalBar cur = bars.get(i);
                        HistoricalMarketDataRepository.HistoricalBar next = bars.get(i + 1);
                        if (!cur.ticker.equals(next.ticker) || cur.close <= 0.0 || next.close <= 0.0) continue;
                        double nextReturn = (next.close - cur.close) / cur.close;
                        double nextRange = next.close <= 0.0 ? 0.0 : Math.max(0.0, (next.high - next.low) / next.close);
                        boolean up = nextReturn > 0.0;
                        boolean parabolic = Math.abs(nextReturn) >= doubleEnv("NIGHTLY_PARABOLIC_LABEL_RETURN", 0.015) || nextRange >= doubleEnv("NIGHTLY_PARABOLIC_LABEL_RANGE", 0.025);
                        stats.computeIfAbsent(cur.ticker, ignored -> new TickerStats()).add(nextReturn, nextRange, next.volume, parabolic);
                        w.write(String.join(",",
                                csv(Instant.now().toString()), csv(cur.ticker), csv(cur.timestamp),
                                fmt(cur.open), fmt(cur.high), fmt(cur.low), fmt(cur.close), fmt(cur.volume),
                                fmt(nextReturn), fmt(nextRange), String.valueOf(up), String.valueOf(parabolic)
                        ));
                        w.newLine();
                        labels++;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("TRAINING DATASET BUILD FAILED: " + e.getMessage());
        }
        writeTickerPersonality(stats);
        return new Result(out, files, rows, labels, stats.size());
    }

    private void writeTickerPersonality(Map<String, TickerStats> stats) {
        Path out = Path.of(System.getenv().getOrDefault("NIGHTLY_TICKER_PERSONALITY_PATH", "logs/nightly_ticker_personality.csv"));
        try {
            Path parent = out.getParent(); if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                w.write("ticker,samples,avgForwardReturn,winRate,avgForwardRange,avgVolume,parabolicRate,personalityScore");
                w.newLine();
                stats.entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue().personalityScore(), a.getValue().personalityScore()))
                        .forEach(e -> {
                            try {
                                TickerStats s = e.getValue();
                                w.write(String.join(",", csv(e.getKey()), String.valueOf(s.samples), fmt(s.avgReturn()), fmt(s.winRate()), fmt(s.avgRange()), fmt(s.avgVolume()), fmt(s.parabolicRate()), fmt(s.personalityScore())));
                                w.newLine();
                            } catch (Exception ignored) {}
                        });
            }
        } catch (Exception e) {
            System.out.println("TICKER PERSONALITY WRITE FAILED: " + e.getMessage());
        }
    }

    static final class TickerStats {
        int samples, wins, parabolic; double sumReturn, sumRange, sumVolume;
        void add(double ret, double range, double volume, boolean para) { samples++; if (ret > 0) wins++; if (para) parabolic++; sumReturn += ret; sumRange += range; sumVolume += Math.max(0.0, volume); }
        double avgReturn() { return samples == 0 ? 0 : sumReturn / samples; }
        double winRate() { return samples == 0 ? 0 : wins * 1.0 / samples; }
        double avgRange() { return samples == 0 ? 0 : sumRange / samples; }
        double avgVolume() { return samples == 0 ? 0 : sumVolume / samples; }
        double parabolicRate() { return samples == 0 ? 0 : parabolic * 1.0 / samples; }
        double personalityScore() { return clamp(Math.abs(avgReturn()) / 0.02 * 0.25 + avgRange() / 0.04 * 0.35 + Math.log10(Math.max(1, avgVolume())) / 9.0 * 0.15 + parabolicRate() * 0.25); }
    }

    public static final class Result {
        public final Path datasetPath; public final int files, rawRows, labels, tickers;
        Result(Path datasetPath, int files, int rawRows, int labels, int tickers) { this.datasetPath = datasetPath; this.files = files; this.rawRows = rawRows; this.labels = labels; this.tickers = tickers; }
        public String summary() { return "files=" + files + " rawRows=" + rawRows + " labels=" + labels + " tickers=" + tickers + " dataset=" + datasetPath; }
    }

    private static double clamp(double v) { if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0; return Math.max(0.0, Math.min(1.0, v)); }
    private static String fmt(double v) { if (Double.isNaN(v) || Double.isInfinite(v)) v = 0; return String.format(Locale.ROOT, "%.8f", v); }
    private static String csv(String v) { String s = v == null ? "" : v.replace("\r", " ").replace("\n", " "); return '"' + s.replace("\"", "\"\"") + '"'; }
    private static int intEnv(String k, int f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Integer.parseInt(v.trim()); } catch (Exception e) { return f; } }
    private static double doubleEnv(String k, double f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Double.parseDouble(v.trim()); } catch (Exception e) { return f; } }
}
