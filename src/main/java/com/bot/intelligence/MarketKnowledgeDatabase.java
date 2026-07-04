package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polygon-first in-memory market knowledge table.
 *
 * The research layer writes every snapshot, minute-bar summary, and reference/fundamental
 * result here. Trading agents can read this local table instead of waiting for fresh REST calls.
 */
public final class MarketKnowledgeDatabase {
    private static final MarketKnowledgeDatabase INSTANCE = new MarketKnowledgeDatabase();

    private final ConcurrentHashMap<String, Record> records = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Path journal = Path.of(env("MARKET_KNOWLEDGE_DATABASE_JOURNAL", "logs/market_knowledge_database.csv"));
    private final long journalIntervalMs = longEnv("MARKET_KNOWLEDGE_DATABASE_JOURNAL_INTERVAL_MS", 60_000L);
    private volatile long lastJournalAt = 0L;

    private MarketKnowledgeDatabase() {}

    public static MarketKnowledgeDatabase getInstance() { return INSTANCE; }

    public void start() {
        if (started.compareAndSet(false, true)) {
            System.out.println("MARKET KNOWLEDGE DATABASE STARTED: mode=POLYGON_FIRST_SHARED_STATE journal=" + journal);
            MarketKnowledgeStore.getInstance().start();
            writeHeaderIfNeeded();
        }
    }

    public void recordSnapshot(String ticker, double price, double volume, double changePct, double high, double low, String source) {
        start();
        Record r = records.computeIfAbsent(norm(ticker), Record::new);
        synchronized (r) {
            r.lastUpdatedMs = System.currentTimeMillis();
            r.lastSource = source;
            if (safe(price) > 0.0) r.price = safe(price);
            r.snapshotVolume = Math.max(r.snapshotVolume, safe(volume));
            r.changePct = safe(changePct);
            if (safe(high) > 0.0) r.dayHigh = safe(high);
            if (safe(low) > 0.0) r.dayLow = safe(low);
            r.snapshotCount++;
        }
        afterRecord(norm(ticker), source);
        maybeJournal();
    }

    public void recordBars(String ticker, int bars, double latestClose, double returnPct, double rangePct, double volume, String source) {
        start();
        Record r = records.computeIfAbsent(norm(ticker), Record::new);
        synchronized (r) {
            r.lastUpdatedMs = System.currentTimeMillis();
            r.lastSource = source;
            if (latestClose > 0) r.price = latestClose;
            r.minuteBars = Math.max(r.minuteBars, bars);
            r.returnPct = safe(returnPct);
            r.rangePct = safe(rangePct);
            r.minuteVolume = Math.max(r.minuteVolume, safe(volume));
            r.barsCount++;
        }
        afterRecord(norm(ticker), source);
        maybeJournal();
    }

    public void recordReference(String ticker, String name, String market, String securityType, double marketCap, double shares, String source) {
        start();
        Record r = records.computeIfAbsent(norm(ticker), Record::new);
        synchronized (r) {
            r.lastUpdatedMs = System.currentTimeMillis();
            r.lastSource = source;
            r.name = clean(name);
            r.market = clean(market);
            r.securityType = clean(securityType);
            r.marketCap = safe(marketCap);
            r.sharesOutstanding = safe(shares);
            r.referenceCount++;
        }
        afterRecord(norm(ticker), source);
        maybeJournal();
    }


    public void recordNews(String ticker, String title, String publishedUtc, double priority, String url, String source) {
        start();
        Record r = records.computeIfAbsent(norm(ticker), Record::new);
        synchronized (r) {
            r.lastUpdatedMs = System.currentTimeMillis();
            r.lastSource = source;
            r.newsCount++;
            r.lastNewsTitle = clean(title);
            r.lastNewsPriority = safe(priority);
        }
        MarketKnowledgeStore.getInstance().persistNews(norm(ticker), source, publishedUtc, title, priority, url);
        afterRecord(norm(ticker), source);
        maybeJournal();
    }

    public void recordMicrostructure(String ticker, double score, double continuation, double exhaustion, double pullback, double shortProb, double recovery, String source) {
        start();
        Record r = records.computeIfAbsent(norm(ticker), Record::new);
        synchronized (r) {
            r.lastUpdatedMs = System.currentTimeMillis();
            r.lastSource = source;
            r.microstructureScore = safe(score);
            r.continuationProbability = safe(continuation);
            r.exhaustionProbability = safe(exhaustion);
            r.pullbackProbability = safe(pullback);
            r.shortProbability = safe(shortProb);
            r.recoveryProbability = safe(recovery);
        }
        afterRecord(norm(ticker), source);
        maybeJournal();
    }



    public void recordOrderFlow(String ticker, int tradeCount, double tradeVolume, double avgTradeSize, double tapeSpeed, double buyPressure,
                                int quoteCount, double avgSpread, double spreadBps, String source) {
        start();
        Record r = records.computeIfAbsent(norm(ticker), Record::new);
        synchronized (r) {
            r.lastUpdatedMs = System.currentTimeMillis();
            r.lastSource = source;
            r.tradeCount = Math.max(r.tradeCount, tradeCount);
            r.tradeVolume = safe(tradeVolume);
            r.avgTradeSize = safe(avgTradeSize);
            r.tapeSpeed = safe(tapeSpeed);
            r.buyPressure = safe(buyPressure);
            r.quoteCount = Math.max(r.quoteCount, quoteCount);
            r.avgSpread = safe(avgSpread);
            r.spreadBps = safe(spreadBps);
        }
        MarketKnowledgeStore.getInstance().persistOrderFlow(norm(ticker), source, tradeCount, tradeVolume, avgTradeSize, tapeSpeed, buyPressure, quoteCount, avgSpread, spreadBps);
        afterRecord(norm(ticker), source);
        maybeJournal();
    }

    private void afterRecord(String ticker, String source) {
        if (ticker == null || ticker.isBlank()) return;
        Record r = records.get(ticker);
        if (r != null && boolEnv("MARKET_KNOWLEDGE_STORE_PERSIST_EACH_UPDATE", true)) {
            MarketKnowledgeStore.getInstance().persistRecord(r.copy());
        }
        if (boolEnv("MARKET_KNOWLEDGE_PROPAGATION_ENABLED", true)) {
            MarketKnowledgePropagationService.getInstance().onKnowledgeUpdated(ticker, source);
        }
    }

    public Record snapshot(String ticker) {
        Record r = records.get(norm(ticker));
        return r == null ? null : r.copy();
    }

    public List<Record> topByActivity(int max) {
        List<Record> list = new ArrayList<>();
        for (Record r : records.values()) list.add(r.copy());
        list.sort(Comparator.comparingDouble(Record::activityScore).reversed());
        return list.subList(0, Math.min(Math.max(0, max), list.size()));
    }

    public List<Record> topByMicrostructure(int max) {
        List<Record> list = new ArrayList<>();
        for (Record r : records.values()) list.add(r.copy());
        list.sort(Comparator.comparingDouble((Record r) -> r.microstructureScore).reversed());
        return list.subList(0, Math.min(Math.max(0, max), list.size()));
    }

    private void maybeJournal() {
        long now = System.currentTimeMillis();
        if (now - lastJournalAt < journalIntervalMs) return;
        lastJournalAt = now;
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (Record r : topByActivity(intEnv("MARKET_KNOWLEDGE_DATABASE_JOURNAL_TOP", 250))) {
                    w.write(r.toCsv());
                    w.newLine();
                    MarketKnowledgeStore.getInstance().persistRecord(r);
                }
            }
        } catch (Exception e) {
            System.out.println("MARKET KNOWLEDGE DATABASE JOURNAL FAILED: " + e.getMessage());
        }
    }

    private void writeHeaderIfNeeded() {
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(journal) || Files.size(journal) == 0L) {
                try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(Record.csvHeader());
                    w.newLine();
                }
            }
        } catch (Exception e) {
            System.out.println("MARKET KNOWLEDGE DATABASE HEADER FAILED: " + e.getMessage());
        }
    }

    private static String norm(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", ""); }
    private static String clean(String s) { return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim(); }
    private static double safe(double v) { return Double.isFinite(v) ? v : 0.0; }
    private static String env(String k, String f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : v.trim(); }
    private static int intEnv(String k, int f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Integer.parseInt(v.trim()); } catch (Exception e) { return f; } }
    private static long longEnv(String k, long f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Long.parseLong(v.trim()); } catch (Exception e) { return f; } }
    private static boolean boolEnv(String k, boolean f) { String v = System.getenv(k); if (v == null || v.isBlank()) return f; String x = v.trim().toLowerCase(Locale.ROOT); return x.equals("true") || x.equals("1") || x.equals("yes") || x.equals("on"); }
    private static String q(String s) { String v = s == null ? "" : s; return '"' + v.replace("\"", "\"\"") + '"'; }
    private static String d(double v) { return String.format(Locale.US, "%.6f", safe(v)); }

    public static final class Record {
        public final String ticker;
        public long lastUpdatedMs;
        public String lastSource = "";
        public String name = "";
        public String market = "";
        public String securityType = "";
        public double price;
        public double snapshotVolume;
        public double minuteVolume;
        public double changePct;
        public double returnPct;
        public double rangePct;
        public double dayHigh;
        public double dayLow;
        public double marketCap;
        public double sharesOutstanding;
        public int minuteBars;
        public int snapshotCount;
        public int barsCount;
        public int referenceCount;
        public int newsCount;
        public String lastNewsTitle = "";
        public double lastNewsPriority;
        public double microstructureScore;
        public double continuationProbability;
        public double exhaustionProbability;
        public double pullbackProbability;
        public double shortProbability;
        public double recoveryProbability;
        public int tradeCount;
        public double tradeVolume;
        public double avgTradeSize;
        public double tapeSpeed;
        public double buyPressure;
        public int quoteCount;
        public double avgSpread;
        public double spreadBps;
        public double opportunityScore;
        public double longScore;
        public double shortScore;
        public double riskScore;

        Record(String ticker) { this.ticker = ticker; }

        Record copy() {
            Record c = new Record(ticker);
            synchronized (this) {
                c.lastUpdatedMs = lastUpdatedMs; c.lastSource = lastSource; c.name = name; c.market = market; c.securityType = securityType;
                c.price = price; c.snapshotVolume = snapshotVolume; c.minuteVolume = minuteVolume; c.changePct = changePct; c.returnPct = returnPct;
                c.rangePct = rangePct; c.dayHigh = dayHigh; c.dayLow = dayLow; c.marketCap = marketCap; c.sharesOutstanding = sharesOutstanding;
                c.minuteBars = minuteBars; c.snapshotCount = snapshotCount; c.barsCount = barsCount; c.referenceCount = referenceCount;
                c.newsCount = newsCount; c.lastNewsTitle = lastNewsTitle; c.lastNewsPriority = lastNewsPriority;
                c.microstructureScore = microstructureScore; c.continuationProbability = continuationProbability; c.exhaustionProbability = exhaustionProbability;
                c.pullbackProbability = pullbackProbability; c.shortProbability = shortProbability; c.recoveryProbability = recoveryProbability;
                c.tradeCount = tradeCount; c.tradeVolume = tradeVolume; c.avgTradeSize = avgTradeSize; c.tapeSpeed = tapeSpeed; c.buyPressure = buyPressure;
                c.quoteCount = quoteCount; c.avgSpread = avgSpread; c.spreadBps = spreadBps; c.opportunityScore = opportunityScore; c.longScore = longScore; c.shortScore = shortScore; c.riskScore = riskScore;
            }
            return c;
        }

        public double activityScore() {
            double vol = Math.max(snapshotVolume, minuteVolume);
            return Math.abs(changePct) * 0.8 + Math.abs(returnPct) * 0.9 + rangePct * 0.7 + Math.log10(Math.max(1.0, vol))
                    + snapshotCount * 0.05 + barsCount * 0.10 + newsCount * 0.12 + microstructureScore * 1.25
                    + Math.min(2.0, Math.log10(Math.max(1.0, tradeVolume)) / 4.0)
                    + Math.min(1.5, Math.abs(buyPressure - 0.5) * 2.0)
                    + Math.min(1.0, tapeSpeed);
        }

        static String csvHeader() {
            return "timestamp,ticker,lastSource,name,market,securityType,price,snapshotVolume,minuteVolume,changePct,returnPct,rangePct,dayHigh,dayLow,marketCap,sharesOutstanding,minuteBars,snapshotCount,barsCount,referenceCount,newsCount,microstructureScore,continuationProbability,exhaustionProbability,pullbackProbability,shortProbability,recoveryProbability,activityScore,tradeCount,tradeVolume,avgTradeSize,tapeSpeed,buyPressure,quoteCount,avgSpread,spreadBps,opportunityScore,longScore,shortScore,riskScore,lastNewsTitle";
        }

        String toCsv() {
            return String.join(",", q(Instant.ofEpochMilli(lastUpdatedMs <= 0 ? System.currentTimeMillis() : lastUpdatedMs).toString()), q(ticker), q(lastSource), q(name), q(market), q(securityType), d(price), d(snapshotVolume), d(minuteVolume), d(changePct), d(returnPct), d(rangePct), d(dayHigh), d(dayLow), d(marketCap), d(sharesOutstanding), String.valueOf(minuteBars), String.valueOf(snapshotCount), String.valueOf(barsCount), String.valueOf(referenceCount), String.valueOf(newsCount), d(microstructureScore), d(continuationProbability), d(exhaustionProbability), d(pullbackProbability), d(shortProbability), d(recoveryProbability), d(activityScore()), String.valueOf(tradeCount), d(tradeVolume), d(avgTradeSize), d(tapeSpeed), d(buyPressure), String.valueOf(quoteCount), d(avgSpread), d(spreadBps), d(opportunityScore), d(longScore), d(shortScore), d(riskScore), q(lastNewsTitle));
        }
    }
}
