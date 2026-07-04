package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable local market knowledge store.
 *
 * This intentionally avoids an external JDBC dependency so the project still compiles in the
 * current lightweight setup. It writes SQLite-compatible DDL plus append-only tables as CSV.
 * If sqlite-jdbc is later added, these table definitions can be used directly.
 */
public final class MarketKnowledgeStore {
    private static final MarketKnowledgeStore INSTANCE = new MarketKnowledgeStore();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Path dir = Path.of(env("MARKET_KNOWLEDGE_STORE_DIR", "logs/market_knowledge_store"));
    private final Path symbolState = dir.resolve("symbol_state.csv");
    private final Path microstructureState = dir.resolve("microstructure_state.csv");
    private final Path newsState = dir.resolve("news_state.csv");
    private final Path orderFlowState = dir.resolve("order_flow_state.csv");
    private final Path schema = dir.resolve("market_knowledge_schema.sql");

    private MarketKnowledgeStore() {}
    public static MarketKnowledgeStore getInstance() { return INSTANCE; }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        try {
            Files.createDirectories(dir);
            writeSchema();
            writeHeader(symbolState, "timestamp,ticker,source,name,market,securityType,price,snapshotVolume,minuteVolume,changePct,returnPct,rangePct,dayHigh,dayLow,marketCap,sharesOutstanding,minuteBars,snapshotCount,barsCount,referenceCount,newsCount,microstructureScore,activityScore");
            writeHeader(microstructureState, "timestamp,ticker,source,price,rangePct,returnPct,volume,spreadProxy,tapeSpeed,continuationProbability,exhaustionProbability,pullbackProbability,shortProbability,recoveryProbability");
            writeHeader(newsState, "timestamp,ticker,source,publishedUtc,title,priority,url");
            writeHeader(orderFlowState, "timestamp,ticker,source,tradeCount,tradeVolume,avgTradeSize,tapeSpeed,buyPressure,quoteCount,avgSpread,spreadBps");
            System.out.println("MARKET KNOWLEDGE STORE STARTED: mode=SQLiteCompatibleAppendStore dir=" + dir + " schema=" + schema);
        } catch (Exception e) {
            System.out.println("MARKET KNOWLEDGE STORE START FAILED: " + safe(e.getMessage()));
        }
    }

    public void persistRecord(MarketKnowledgeDatabase.Record r) {
        if (r == null) return;
        start();
        try (BufferedWriter w = Files.newBufferedWriter(symbolState, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(String.join(",",
                    q(Instant.ofEpochMilli(r.lastUpdatedMs <= 0 ? System.currentTimeMillis() : r.lastUpdatedMs).toString()),
                    q(r.ticker), q(r.lastSource), q(r.name), q(r.market), q(r.securityType),
                    d(r.price), d(r.snapshotVolume), d(r.minuteVolume), d(r.changePct), d(r.returnPct), d(r.rangePct),
                    d(r.dayHigh), d(r.dayLow), d(r.marketCap), d(r.sharesOutstanding), String.valueOf(r.minuteBars),
                    String.valueOf(r.snapshotCount), String.valueOf(r.barsCount), String.valueOf(r.referenceCount),
                    String.valueOf(r.newsCount), d(r.microstructureScore), d(r.activityScore())));
            w.newLine();
        } catch (Exception e) {
            System.out.println("MARKET KNOWLEDGE STORE RECORD FAILED: ticker=" + r.ticker + " error=" + safe(e.getMessage()));
        }
    }

    public void persistMicrostructure(String ticker, String source, double price, double rangePct, double returnPct, double volume,
                                      double spreadProxy, double tapeSpeed, double continuation, double exhaustion,
                                      double pullback, double shortProb, double recovery) {
        start();
        try (BufferedWriter w = Files.newBufferedWriter(microstructureState, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(String.join(",", q(Instant.now().toString()), q(norm(ticker)), q(source), d(price), d(rangePct), d(returnPct), d(volume),
                    d(spreadProxy), d(tapeSpeed), d(continuation), d(exhaustion), d(pullback), d(shortProb), d(recovery)));
            w.newLine();
        } catch (Exception e) {
            System.out.println("MARKET KNOWLEDGE STORE MICROSTRUCTURE FAILED: ticker=" + ticker + " error=" + safe(e.getMessage()));
        }
    }

    public void persistNews(String ticker, String source, String publishedUtc, String title, double priority, String url) {
        start();
        try (BufferedWriter w = Files.newBufferedWriter(newsState, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(String.join(",", q(Instant.now().toString()), q(norm(ticker)), q(source), q(publishedUtc), q(title), d(priority), q(url)));
            w.newLine();
        } catch (Exception e) {
            System.out.println("MARKET KNOWLEDGE STORE NEWS FAILED: ticker=" + ticker + " error=" + safe(e.getMessage()));
        }
    }



    public void persistOrderFlow(String ticker, String source, int tradeCount, double tradeVolume, double avgTradeSize, double tapeSpeed,
                                 double buyPressure, int quoteCount, double avgSpread, double spreadBps) {
        start();
        try (BufferedWriter w = Files.newBufferedWriter(orderFlowState, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(String.join(",", q(Instant.now().toString()), q(norm(ticker)), q(source), String.valueOf(Math.max(0, tradeCount)),
                    d(tradeVolume), d(avgTradeSize), d(tapeSpeed), d(buyPressure), String.valueOf(Math.max(0, quoteCount)), d(avgSpread), d(spreadBps)));
            w.newLine();
        } catch (Exception e) {
            System.out.println("MARKET KNOWLEDGE STORE ORDER FLOW FAILED: ticker=" + ticker + " error=" + safe(e.getMessage()));
        }
    }

    private void writeSchema() throws Exception {
        if (Files.exists(schema) && Files.size(schema) > 0) return;
        String ddl = "CREATE TABLE IF NOT EXISTS symbol_state (timestamp TEXT, ticker TEXT, source TEXT, name TEXT, market TEXT, securityType TEXT, price REAL, snapshotVolume REAL, minuteVolume REAL, changePct REAL, returnPct REAL, rangePct REAL, dayHigh REAL, dayLow REAL, marketCap REAL, sharesOutstanding REAL, minuteBars INTEGER, snapshotCount INTEGER, barsCount INTEGER, referenceCount INTEGER, newsCount INTEGER, microstructureScore REAL, activityScore REAL);\n" +
                "CREATE TABLE IF NOT EXISTS microstructure_state (timestamp TEXT, ticker TEXT, source TEXT, price REAL, rangePct REAL, returnPct REAL, volume REAL, spreadProxy REAL, tapeSpeed REAL, continuationProbability REAL, exhaustionProbability REAL, pullbackProbability REAL, shortProbability REAL, recoveryProbability REAL);\n" +
                "CREATE TABLE IF NOT EXISTS news_state (timestamp TEXT, ticker TEXT, source TEXT, publishedUtc TEXT, title TEXT, priority REAL, url TEXT);\n" +
                "CREATE TABLE IF NOT EXISTS order_flow_state (timestamp TEXT, ticker TEXT, source TEXT, tradeCount INTEGER, tradeVolume REAL, avgTradeSize REAL, tapeSpeed REAL, buyPressure REAL, quoteCount INTEGER, avgSpread REAL, spreadBps REAL);\n" +
                "CREATE INDEX IF NOT EXISTS idx_symbol_state_ticker_time ON symbol_state(ticker, timestamp);\n" +
                "CREATE INDEX IF NOT EXISTS idx_microstructure_ticker_time ON microstructure_state(ticker, timestamp);\n" +
                "CREATE INDEX IF NOT EXISTS idx_news_state_ticker_time ON news_state(ticker, timestamp);\n" +
                "CREATE INDEX IF NOT EXISTS idx_order_flow_ticker_time ON order_flow_state(ticker, timestamp);\n";
        Files.writeString(schema, ddl, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void writeHeader(Path p, String h) throws Exception { if (!Files.exists(p) || Files.size(p) == 0) Files.writeString(p, h + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
    private static String env(String k, String f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : v.trim(); }
    private static String q(String s) { String v = s == null ? "" : s; return '"' + v.replace("\"", "\"\"") + '"'; }
    private static String d(double v) { return String.format(Locale.US, "%.6f", Double.isFinite(v) ? v : 0.0); }
    private static String norm(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", ""); }
    private static String safe(String s) { return s == null ? "" : s.replace('\n',' ').replace('\r',' '); }
}
