package com.bot.intelligence;

import com.bot.intelligence.bus.HistoricalPatternMiner;
import com.bot.intelligence.bus.MarketAnalogueEngine;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Event bridge between raw provider writes and the execution-facing market state.
 *
 * Earlier versions wrote Polygon/Alpha responses into a local REST cache and sometimes
 * into MarketKnowledgeDatabase, but the downstream state agents refreshed on their own
 * cadence. During startup or fast news events that left MarketStateDatabase2, the
 * Market OS, the microstructure agent, and the OpenAI governor seeing zero active
 * symbols while provider responses were clearly arriving.
 *
 * This service makes every knowledge write an event: persist the latest record, refresh
 * MarketStateDatabase2 after a short debounce, update analogue/pattern memory, and emit
 * a clear propagation log. It is intentionally lightweight and daemon-backed so provider
 * HTTP workers do not block on expensive downstream scans.
 */
public final class MarketKnowledgePropagationService {
    private static final MarketKnowledgePropagationService INSTANCE = new MarketKnowledgePropagationService();

    private final ConcurrentLinkedQueue<String> dirtySymbols = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean workerScheduled = new AtomicBoolean(false);
    private volatile long lastFlushMs = 0L;

    private MarketKnowledgePropagationService() {}

    public static MarketKnowledgePropagationService getInstance() { return INSTANCE; }

    public void onKnowledgeUpdated(String ticker, String source) {
        String symbol = norm(ticker);
        if (symbol.isBlank()) return;
        dirtySymbols.add(symbol);
        schedule(source);
    }

    private void schedule(String source) {
        if (!workerScheduled.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> flush(source), "market-knowledge-propagation");
        t.setDaemon(true);
        t.start();
    }

    private void flush(String source) {
        try {
            long debounce = longEnv("MARKET_KNOWLEDGE_PROPAGATION_DEBOUNCE_MS", 250L);
            if (debounce > 0) sleep(debounce);

            int drained = 0;
            String symbol;
            while ((symbol = dirtySymbols.poll()) != null) {
                drained++;
                MarketKnowledgeDatabase.Record r = MarketKnowledgeDatabase.getInstance().snapshot(symbol);
                if (r == null) continue;
                MarketKnowledgeStore.getInstance().persistRecord(r);
                try { HistoricalPatternMiner.getInstance().observe(r); } catch (Exception ignored) {}
                if (boolEnv("MARKET_KNOWLEDGE_PROPAGATE_ANALOGUES", false)) {
                    try { MarketAnalogueEngine.getInstance().nearestSymbols(symbol, intEnv("MARKET_ANALOGUE_TOP_K", 8)); } catch (Exception ignored) {}
                }
            }

            long now = System.currentTimeMillis();
            long minRefreshGap = longEnv("MARKET_KNOWLEDGE_STATE_REFRESH_MIN_MS", 750L);
            if (now - lastFlushMs >= minRefreshGap) {
                lastFlushMs = now;
                try { MarketStateDatabase2.getInstance().refresh(); } catch (Exception e) {
                    System.out.println("MARKET KNOWLEDGE PROPAGATION STATE REFRESH FAILED: " + safe(e.getMessage()));
                }
            }

            if (boolEnv("MARKET_KNOWLEDGE_PROPAGATION_LOG", true) && drained > 0) {
                int active = MarketKnowledgeDatabase.getInstance().topByActivity(intEnv("MARKET_KNOWLEDGE_PROPAGATION_ACTIVE_TOP", 1000)).size();
                int states = MarketStateDatabase2.getInstance().topOpportunities(intEnv("MARKET_KNOWLEDGE_PROPAGATION_STATE_TOP", 1000)).size();
                System.out.println("MARKET KNOWLEDGE PROPAGATED: source=" + safe(source) + " updatedSymbols=" + drained + " activeKnowledge=" + active + " activeStates=" + states);
            }
        } finally {
            workerScheduled.set(false);
            if (!dirtySymbols.isEmpty()) schedule("queued");
        }
    }

    private static String norm(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", ""); }
    private static String safe(String s) { return s == null ? "" : s.replace('\n', ' ').replace('\r', ' '); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static boolean boolEnv(String k, boolean f) { String v = System.getenv(k); if (v == null || v.isBlank()) return f; String x = v.trim().toLowerCase(Locale.ROOT); return x.equals("true") || x.equals("1") || x.equals("yes") || x.equals("on"); }
    private static int intEnv(String k, int f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Integer.parseInt(v.trim()); } catch (Exception e) { return f; } }
    private static long longEnv(String k, long f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Long.parseLong(v.trim()); } catch (Exception e) { return f; } }
}
