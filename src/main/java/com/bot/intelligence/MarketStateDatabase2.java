package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Market State Database 2.0.
 *
 * This is the state-driven layer that sits above raw Polygon research. Collectors write raw
 * snapshots/bars/trades/quotes into MarketKnowledgeDatabase; this class continuously converts
 * that local knowledge into execution-facing long/short/risk/opportunity scores. Strategies can
 * read this local state instead of making provider calls or recalculating the same features.
 */
public final class MarketStateDatabase2 {
    private static final MarketStateDatabase2 INSTANCE = new MarketStateDatabase2();
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Path journal = Path.of(env("MARKET_STATE_DB2_JOURNAL", "logs/market_state_database_2.csv"));
    private volatile Thread worker;
    private volatile boolean running;

    private MarketStateDatabase2() {}
    public static MarketStateDatabase2 getInstance() { return INSTANCE; }

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) return;
        running = true;
        writeHeader();
        worker = new Thread(this::loop, "market-state-db2");
        worker.setDaemon(true);
        worker.start();
        System.out.println("MARKET STATE DATABASE 2.0 STARTED: source=MarketKnowledgeDatabase mode=STATE_DRIVEN_EXECUTION journal=" + journal);
    }

    public synchronized void stop() { running = false; Thread t = worker; if (t != null) t.interrupt(); started.set(false); }

    public State snapshot(String ticker) {
        State s = states.get(norm(ticker));
        return s == null ? null : s.copy();
    }

    public List<State> topOpportunities(int max) { return sorted(max, Comparator.comparingDouble((State s) -> s.opportunityScore).reversed()); }
    public List<State> topLongs(int max) { return sorted(max, Comparator.comparingDouble((State s) -> s.longScore).reversed()); }
    public List<State> topShorts(int max) { return sorted(max, Comparator.comparingDouble((State s) -> s.shortScore).reversed()); }

    private List<State> sorted(int max, Comparator<State> cmp) {
        List<State> out = new ArrayList<>();
        for (State s : states.values()) out.add(s.copy());
        out.sort(cmp);
        return out.subList(0, Math.min(Math.max(0, max), out.size()));
    }

    private void loop() {
        while (running) {
            try { refresh(); } catch (Exception e) { System.out.println("MARKET STATE DB2 ERROR: " + safe(e.getMessage())); }
            sleep(intervalMs());
        }
    }

    public void refresh() {
        List<MarketKnowledgeDatabase.Record> records = MarketKnowledgeDatabase.getInstance().topByActivity(maxRecords());
        long now = System.currentTimeMillis();
        int updated = 0;
        for (MarketKnowledgeDatabase.Record r : records) {
            if (r == null || r.ticker == null || r.ticker.isBlank()) continue;
            State s = score(r, now);
            states.put(s.ticker, s);
            r.opportunityScore = s.opportunityScore;
            r.longScore = s.longScore;
            r.shortScore = s.shortScore;
            r.riskScore = s.riskScore;
            updated++;
        }
        journal(updated);
        if (boolEnv("MARKET_STATE_DB2_LOG", true)) {
            System.out.println("MARKET STATE DB2 REFRESH: symbols=" + updated + " top=" + topSummary(8));
        }
    }

    private static State score(MarketKnowledgeDatabase.Record r, long now) {
        State s = new State();
        s.ticker = r.ticker;
        s.timestampMs = now;
        s.price = r.price;
        s.returnPct = r.returnPct;
        s.rangePct = r.rangePct;
        s.volume = Math.max(r.minuteVolume, Math.max(r.snapshotVolume, r.tradeVolume));
        s.microstructureScore = r.microstructureScore;
        s.newsScore = clamp(r.lastNewsPriority + Math.min(0.35, r.newsCount * 0.03));
        double absReturn = Math.abs(r.returnPct);
        double directionalVolume = Math.log10(Math.max(10.0, s.volume)) / 8.0;
        double tapeVolume = Math.log10(Math.max(1.0, r.tradeVolume)) / 8.0;
        double volatilityExpansion = clamp(absReturn / 10.0 * 0.30 + r.rangePct / 12.0 * 0.40 + Math.max(0.0, r.tapeSpeed) * 0.30);
        double volumeExpansion = clamp(directionalVolume * 0.35 + tapeVolume * 0.45 + Math.max(0.0, r.tapeSpeed) * 0.20);
        double minIgnitionVolume = doubleEnv("MARKET_STATE_MIN_IGNITION_VOLUME", 750_000.0);
        double minAbsReturnPct = doubleEnv("MARKET_STATE_MIN_ABS_RETURN_PCT", 4.0);
        double minRangePct = doubleEnv("MARKET_STATE_MIN_RANGE_PCT", 5.0);
        double minVolumeExpansion = doubleEnv("MARKET_STATE_MIN_VOLUME_EXPANSION_SCORE", 0.45);
        double ignitionReadiness = clamp(
                clamp(s.volume / Math.max(1.0, minIgnitionVolume)) * 0.30
                        + clamp(absReturn / Math.max(0.01, minAbsReturnPct)) * 0.25
                        + clamp(r.rangePct / Math.max(0.01, minRangePct)) * 0.25
                        + clamp(volumeExpansion / Math.max(0.01, minVolumeExpansion)) * 0.20);

        s.technicalScore = clamp(volatilityExpansion * 0.60 + volumeExpansion * 0.40);
        s.liquidityScore = clamp(volumeExpansion);
        s.orderFlowScore = clamp(r.tapeSpeed * 0.45 + Math.abs(r.buyPressure - 0.5) * 1.1 + Math.max(0.0, 1.0 - r.spreadBps / 60.0) * 0.20);
        s.parabolicScore = clamp(volatilityExpansion * 0.55 + volumeExpansion * 0.35 + s.orderFlowScore * 0.10);
        s.longScore = clamp(Math.max(0.0, r.returnPct) / 12.0 * 0.32 + volumeExpansion * 0.28 + volatilityExpansion * 0.24 + s.orderFlowScore * 0.12 + s.newsScore * 0.04);
        s.shortScore = clamp(Math.max(0.0, -r.returnPct) / 12.0 * 0.32 + volumeExpansion * 0.25 + volatilityExpansion * 0.22 + r.exhaustionProbability * 0.08 + r.shortProbability * 0.08 + Math.max(0.0, 0.5 - r.buyPressure) * 0.12);
        s.riskScore = clamp(r.rangePct / 35.0 * 0.20 + Math.max(0.0, r.spreadBps - 40.0) / 180.0 + (s.liquidityScore < 0.25 ? 0.25 : 0.0));
        double rawOpportunity = clamp(volumeExpansion * 0.36
                + volatilityExpansion * 0.28
                + Math.max(s.longScore, s.shortScore) * 0.20
                + s.orderFlowScore * 0.08
                + s.newsScore * 0.08
                - s.riskScore * 0.18);
        // Do not collapse non-ignition names to 0.000. The state DB is used for
        // ranking and research as well as execution. A soft readiness multiplier
        // preserves meaningful ordering while still preventing weak names from
        // looking like executable momentum trades.
        double floor = doubleEnv("MARKET_STATE_DB2_RESEARCH_SCORE_FLOOR_MULTIPLIER", 0.25);
        s.opportunityScore = clamp(rawOpportunity * Math.max(floor, ignitionReadiness));
        s.direction = s.longScore >= s.shortScore ? "LONG" : "SHORT";
        return s;
    }

    private String topSummary(int max) {
        List<State> top = topOpportunities(max);
        List<String> parts = new ArrayList<>();
        for (State s : top) parts.add(s.ticker + ":" + fmt(s.opportunityScore) + ":" + s.direction);
        return parts.toString();
    }

    private void journal(int updated) {
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (State s : topOpportunities(journalTop())) {
                    w.write(s.toCsv());
                    w.newLine();
                }
            }
        } catch (Exception e) {
            System.out.println("MARKET STATE DB2 JOURNAL FAILED: updated=" + updated + " error=" + safe(e.getMessage()));
        }
    }

    private void writeHeader() {
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(journal) || Files.size(journal) == 0) {
                Files.writeString(journal, State.csvHeader() + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ignored) {}
    }

    public static final class State {
        public String ticker = "";
        public long timestampMs;
        public String direction = "LONG";
        public double price, returnPct, rangePct, volume;
        public double technicalScore, liquidityScore, newsScore, orderFlowScore, microstructureScore, parabolicScore;
        public double longScore, shortScore, riskScore, opportunityScore;
        State copy() { State c = new State(); c.ticker=ticker; c.timestampMs=timestampMs; c.direction=direction; c.price=price; c.returnPct=returnPct; c.rangePct=rangePct; c.volume=volume; c.technicalScore=technicalScore; c.liquidityScore=liquidityScore; c.newsScore=newsScore; c.orderFlowScore=orderFlowScore; c.microstructureScore=microstructureScore; c.parabolicScore=parabolicScore; c.longScore=longScore; c.shortScore=shortScore; c.riskScore=riskScore; c.opportunityScore=opportunityScore; return c; }
        static String csvHeader(){return "timestamp,ticker,direction,price,returnPct,rangePct,volume,technical,liquidity,news,orderFlow,microstructure,parabolic,longScore,shortScore,riskScore,opportunityScore";}
        String toCsv(){return String.join(",", q(Instant.ofEpochMilli(timestampMs).toString()), q(ticker), q(direction), d(price), d(returnPct), d(rangePct), d(volume), d(technicalScore), d(liquidityScore), d(newsScore), d(orderFlowScore), d(microstructureScore), d(parabolicScore), d(longScore), d(shortScore), d(riskScore), d(opportunityScore));}
    }

    private static int maxRecords(){return Math.max(50, intEnv("MARKET_STATE_DB2_MAX_RECORDS", 1000));}
    private static int journalTop(){return Math.max(25, intEnv("MARKET_STATE_DB2_JOURNAL_TOP", 250));}
    private static long intervalMs(){return Math.max(1000L, longEnv("MARKET_STATE_DB2_INTERVAL_MS", 5000L));}
    private static String norm(String s){return s==null?"":s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]","");}
    private static double clamp(double v){return Double.isFinite(v)?Math.max(0,Math.min(1,v)):0.0;}
    private static String q(String s){String v=s==null?"":s;return '"'+v.replace("\"","\"\"")+'"';}
    private static String d(double v){return String.format(Locale.US,"%.6f",Double.isFinite(v)?v:0.0);}    
    private static String fmt(double v){return String.format(Locale.US,"%.3f",Double.isFinite(v)?v:0.0);}    
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ');}    
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private static String env(String k,String f){String v=System.getenv(k);return v==null||v.isBlank()?f:v.trim();}
    private static int intEnv(String k,int f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Integer.parseInt(v.trim());}catch(Exception e){return f;}}
    private static long longEnv(String k,long f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Long.parseLong(v.trim());}catch(Exception e){return f;}}
    private static double doubleEnv(String k,double f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Double.parseDouble(v.trim());}catch(Exception e){return f;}}
    private static boolean boolEnv(String k, boolean f){String v=System.getenv(k); if(v==null||v.isBlank()) return f; String x=v.trim().toLowerCase(Locale.ROOT); return x.equals("true")||x.equals("1")||x.equals("yes")||x.equals("on");}
}
