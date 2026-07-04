package com.bot.intelligence.bus;

import com.bot.intelligence.MarketKnowledgeDatabase;
import com.bot.intelligence.MarketKnowledgeStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Microstructure-style agent using the best currently available Polygon premium bars/snapshots.
 * It is deliberately provider-independent: it reads the local MarketKnowledgeDatabase and writes
 * probability estimates back into the bus and durable store.
 */
public final class MicrostructureAgent {
    private volatile boolean running;
    private volatile Thread worker;

    public synchronized void start() {
        if (running) return;
        if (!boolEnv("MICROSTRUCTURE_AGENT_ENABLED", true)) {
            System.out.println("MICROSTRUCTURE AGENT DISABLED: MICROSTRUCTURE_AGENT_ENABLED=false");
            return;
        }
        running = true;
        worker = new Thread(this::loop, "microstructure-agent");
        worker.setDaemon(true);
        worker.start();
        System.out.println("MICROSTRUCTURE AGENT STARTED: source=MarketKnowledgeDatabase intervalMs=" + intervalMs() + " topK=" + topK());
    }

    public synchronized void stop() { running = false; Thread t = worker; if (t != null) t.interrupt(); }

    private void loop() {
        while (running) {
            try { runOnce(); } catch (Exception e) { System.out.println("MICROSTRUCTURE AGENT ERROR: " + safe(e.getMessage())); }
            sleep(intervalMs());
        }
    }

    private void runOnce() {
        List<MarketKnowledgeDatabase.Record> records = MarketKnowledgeDatabase.getInstance().topByActivity(topK());
        int emitted = 0;
        for (MarketKnowledgeDatabase.Record r : records) {
            Scores s = score(r);
            MarketKnowledgeDatabase.getInstance().recordMicrostructure(r.ticker, s.composite, s.continuation, s.exhaustion, s.pullback, s.shortProb, s.recovery, "MICROSTRUCTURE_AGENT");
            MarketKnowledgeStore.getInstance().persistMicrostructure(r.ticker, "MICROSTRUCTURE_AGENT", r.price, r.rangePct, r.returnPct, Math.max(r.minuteVolume, r.snapshotVolume), s.spreadProxy, s.tapeSpeed, s.continuation, s.exhaustion, s.pullback, s.shortProb, s.recovery);
            if (s.composite < minEmitScore()) continue;
            Map<String,String> meta = new LinkedHashMap<>();
            meta.put("provider", "LOCAL_MARKET_KNOWLEDGE");
            meta.put("source", "MICROSTRUCTURE_AGENT");
            meta.put("continuationProbability", fmt(s.continuation));
            meta.put("exhaustionProbability", fmt(s.exhaustion));
            meta.put("pullbackProbability", fmt(s.pullback));
            meta.put("shortProbability", fmt(s.shortProb));
            meta.put("recoveryProbability", fmt(s.recovery));
            meta.put("tapeSpeedProxy", fmt(s.tapeSpeed));
            meta.put("spreadProxy", fmt(s.spreadProxy));
            MarketIntelligenceBus.getInstance().publishSignal(new MarketIntelligenceSignal(
                    "MICROSTRUCTURE_AGENT", MarketIntelligenceSignalType.ORDER_FLOW, r.ticker,
                    "Microstructure read: " + r.ticker + " continuation=" + fmt(s.continuation) + " exhaustion=" + fmt(s.exhaustion),
                    "Local Polygon market knowledge indicates tape/price-path pressure.", System.currentTimeMillis(), 0.72, s.composite, meta));
            emitted++;
        }
        if (boolEnv("MICROSTRUCTURE_AGENT_LOG", true)) System.out.println("MICROSTRUCTURE AGENT SCAN: candidates=" + records.size() + " emitted=" + emitted);
    }

    private static Scores score(MarketKnowledgeDatabase.Record r) {
        double volume = Math.max(r.minuteVolume, r.snapshotVolume);
        double volScore = clamp(Math.log10(Math.max(10.0, volume)) / 8.0);
        double move = clamp(Math.abs(r.returnPct) / 10.0);
        double range = clamp(r.rangePct / 8.0);
        double realTape = r.tapeSpeed > 0.0 ? r.tapeSpeed : clamp((Math.abs(r.returnPct) * 0.55 + r.rangePct * 0.45) / 8.0 + volScore * 0.25);
        double spreadProxy = r.spreadBps > 0.0 ? clamp(r.spreadBps / 120.0) : clamp((r.price <= 0 ? 0 : r.rangePct / Math.max(0.01, r.price)) / 3.0);
        double pressure = r.buyPressure > 0.0 ? r.buyPressure : 0.5;
        double continuation = clamp((Math.max(0.0, r.returnPct) / 10.0) * 0.32 + range * 0.18 + volScore * 0.20 + realTape * 0.18 + Math.max(0.0, pressure - 0.5) * 0.55);
        double exhaustion = clamp((range * 0.32) + (move * 0.25) + (volScore * 0.15) + realTape * 0.18 + spreadProxy * 0.18);
        double pullback = clamp(exhaustion * 0.58 + (r.returnPct > 0 ? 0.10 : 0.0) + Math.max(0.0, 0.5 - pressure) * 0.30);
        double shortProb = clamp(exhaustion * 0.48 + (r.returnPct > 2.5 ? 0.18 : 0.0) + Math.max(0.0, 0.48 - pressure) * 0.45);
        double recovery = clamp((r.returnPct < 0 ? move * 0.34 : continuation * 0.24) + volScore * 0.20 + realTape * 0.16 + Math.max(0.0, pressure - 0.5) * 0.30);
        double composite = clamp(Math.max(continuation, Math.max(exhaustion, recovery)) * 0.65 + realTape * 0.22 + Math.max(0.0, 1.0 - spreadProxy) * 0.13);
        double tape = realTape;
        return new Scores(composite, continuation, exhaustion, pullback, shortProb, recovery, spreadProxy, tape);
    }

    private static final class Scores { final double composite, continuation, exhaustion, pullback, shortProb, recovery, spreadProxy, tapeSpeed; Scores(double c,double a,double b,double p,double s,double r,double sp,double t){composite=c;continuation=a;exhaustion=b;pullback=p;shortProb=s;recovery=r;spreadProxy=sp;tapeSpeed=t;} }
    private static int topK() { return Math.max(10, intEnv("MICROSTRUCTURE_AGENT_TOP_K", 150)); }
    private static long intervalMs() { return Math.max(1_000L, longEnv("MICROSTRUCTURE_AGENT_INTERVAL_MS", 5_000L)); }
    private static double minEmitScore() { return Math.max(0.01, Math.min(0.95, doubleEnv("MICROSTRUCTURE_AGENT_MIN_EMIT_SCORE", 0.48))); }
    private static double clamp(double v) { return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.0; }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
    private static String fmt(double v) { return String.format(java.util.Locale.US, "%.5f", Double.isFinite(v) ? v : 0.0); }
    private static String safe(String s) { return s == null ? "" : s.replace('\n',' ').replace('\r',' '); }
    private static boolean boolEnv(String k, boolean f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()); }
    private static int intEnv(String k, int f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Integer.parseInt(v.trim()); } catch (Exception e) { return f; } }
    private static long longEnv(String k, long f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Long.parseLong(v.trim()); } catch (Exception e) { return f; } }
    private static double doubleEnv(String k, double f) { try { String v = System.getenv(k); return v == null || v.isBlank() ? f : Double.parseDouble(v.trim()); } catch (Exception e) { return f; } }
}
