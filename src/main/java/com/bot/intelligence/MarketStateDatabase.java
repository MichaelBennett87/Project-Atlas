package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Journals normalized market-state snapshots for later replay and similarity search. */
public final class MarketStateDatabase {
    private static final MarketStateDatabase INSTANCE = new MarketStateDatabase();
    private final Path journal = Path.of(env("MARKET_STATE_DATABASE_JOURNAL", "logs/market_state_database.csv"));
    private final long minIntervalMs = longEnv("MARKET_STATE_DATABASE_INTERVAL_MS", 60_000L);
    private volatile long lastWriteAt = 0L;
    private volatile boolean started = false;

    private MarketStateDatabase() {}
    public static MarketStateDatabase getInstance() { return INSTANCE; }

    public synchronized void start() {
        if (started) return;
        started = true;
        System.out.println("MARKET STATE DATABASE STARTED: journal=" + journal + " intervalMs=" + minIntervalMs);
        headerIfNeeded();
    }

    public void record(WorldModelSnapshot snapshot, LiveTechnicalFeatureStore.MarketTechnicalSummary technical) {
        if (snapshot == null) return;
        start();
        long now = System.currentTimeMillis();
        if (now - lastWriteAt < minIntervalMs) return;
        lastWriteAt = now;
        double tech = technical == null ? 0.0 : technical.technicalScore;
        double techLiquidity = technical == null ? 0.0 : technical.liquidityScore;
        double techParabolic = technical == null ? 0.0 : technical.parabolicScore;
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(String.join(",",
                        q(Instant.ofEpochMilli(now).toString()),
                        q(snapshot.getRegime().name()),
                        d(snapshot.getTrendScore()),
                        d(snapshot.getVolatilityScore()),
                        d(snapshot.getLiquidityScore()),
                        d(snapshot.getSmallCapLeadershipScore()),
                        d(snapshot.getLargeCapLeadershipScore()),
                        d(snapshot.getCatalystHeatScore()),
                        d(snapshot.getNewsFlowScore()),
                        d(snapshot.getParabolicHeatScore()),
                        d(snapshot.getDataConfidenceScore()),
                        d(tech),
                        d(techLiquidity),
                        d(techParabolic),
                        q(snapshot.getSummary())
                ));
                w.newLine();
            }
        } catch (Exception e) {
            System.out.println("MARKET STATE DATABASE WRITE FAILED: " + e.getMessage());
        }
    }

    public List<StateRow> loadRecent(int maxRows) {
        List<StateRow> rows = new ArrayList<>();
        if (!Files.exists(journal)) return rows;
        try (BufferedReader r = Files.newBufferedReader(journal)) {
            String header = r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] c = split(line);
                if (c.length < 14) continue;
                rows.add(new StateRow(
                        c[0], c[1], num(c[2]), num(c[3]), num(c[4]), num(c[5]), num(c[6]), num(c[7]), num(c[8]), num(c[9]), num(c[10]), num(c[11]), num(c[12]), num(c[13])
                ));
            }
        } catch (Exception e) {
            System.out.println("MARKET STATE DATABASE READ FAILED: " + e.getMessage());
        }
        rows.sort(Comparator.comparing(row -> row.timestamp));
        if (rows.size() <= maxRows) return rows;
        return new ArrayList<>(rows.subList(rows.size() - maxRows, rows.size()));
    }

    private void headerIfNeeded() {
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(journal) || Files.size(journal) == 0L) {
                try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write("timestamp,regime,trend,volatility,liquidity,smallCap,largeCap,catalyst,newsFlow,parabolic,dataConfidence,technical,technicalLiquidity,technicalParabolic,summary");
                    w.newLine();
                }
            }
        } catch (Exception e) { System.out.println("MARKET STATE DATABASE HEADER FAILED: " + e.getMessage()); }
    }

    private static String q(String s) { return '"' + (s == null ? "" : s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")) + '"'; }
    private static String d(double v) { return String.format(Locale.US, "%.6f", Double.isFinite(v) ? v : 0.0); }
    private static double num(String v) { try { return Double.parseDouble(v.replace("\"", "").trim()); } catch(Exception e) { return 0.0; } }
    private static String[] split(String line) { return line == null ? new String[0] : line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1); }
    private static String env(String key, String fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : v.trim(); }
    private static long longEnv(String key, long fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); } catch(Exception e) { return fallback; } }

    public static final class StateRow {
        public final String timestamp, regime;
        public final double trend, volatility, liquidity, smallCap, largeCap, catalyst, newsFlow, parabolic, dataConfidence, technical, technicalLiquidity, technicalParabolic;
        StateRow(String timestamp, String regime, double trend, double volatility, double liquidity, double smallCap, double largeCap, double catalyst, double newsFlow, double parabolic, double dataConfidence, double technical, double technicalLiquidity, double technicalParabolic) {
            this.timestamp = timestamp; this.regime = regime; this.trend = trend; this.volatility = volatility; this.liquidity = liquidity; this.smallCap = smallCap; this.largeCap = largeCap; this.catalyst = catalyst; this.newsFlow = newsFlow; this.parabolic = parabolic; this.dataConfidence = dataConfidence; this.technical = technical; this.technicalLiquidity = technicalLiquidity; this.technicalParabolic = technicalParabolic;
        }
    }
}
