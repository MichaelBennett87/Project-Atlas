package com.bot.intelligence.bus;

import com.bot.intelligence.MarketKnowledgeDatabase;
import com.bot.intelligence.MarketKnowledgeStore;
import com.bot.intelligence.MarketStateDatabase2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Market Operating System collector.
 *
 * This does not call Polygon directly. It reads the continuously warmed local market knowledge
 * database and turns it into market-wide state: breadth, hot leaders, weak leaders, parabolic
 * pressure, and short/long opportunity pressure. Agents should consume this shared state instead
 * of issuing isolated provider calls.
 */
public final class MarketOperatingSystemCollector {
    private volatile boolean running;
    private volatile Thread worker;

    public synchronized void start() {
        if (running) return;
        if (!boolEnv("MARKET_OS_COLLECTOR_ENABLED", true)) {
            System.out.println("MARKET OPERATING SYSTEM COLLECTOR DISABLED: MARKET_OS_COLLECTOR_ENABLED=false");
            return;
        }
        running = true;
        MarketKnowledgeDatabase.getInstance().start();
        MarketKnowledgeStore.getInstance().start();
        MarketStateDatabase2.getInstance().start();
        worker = new Thread(this::loop, "market-operating-system-collector");
        worker.setDaemon(true);
        worker.start();
        System.out.println("MARKET OPERATING SYSTEM COLLECTOR STARTED: source=MarketKnowledgeDatabase mode=MARKET_CENTRIC_STATE intervalMs=" + intervalMs() + " topK=" + topK());
    }

    public synchronized void stop() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    private void loop() {
        while (running) {
            try { runOnce(); } catch (Exception e) { System.out.println("MARKET OS COLLECTOR ERROR: " + safe(e.getMessage())); }
            sleep(intervalMs());
        }
    }

    public void runOnce() {
        List<MarketKnowledgeDatabase.Record> active = MarketKnowledgeDatabase.getInstance().topByActivity(topK());
        MarketStateDatabase2.getInstance().refresh();
        List<MarketStateDatabase2.State> opportunities = MarketStateDatabase2.getInstance().topOpportunities(Math.min(25, topK()));
        int up = 0, down = 0, liquid = 0, newsActive = 0;
        double totalAbsMove = 0.0, totalRange = 0.0, totalVol = 0.0, longPressure = 0.0, shortPressure = 0.0;
        for (MarketKnowledgeDatabase.Record r : active) {
            if (r.returnPct > 0.15 || r.changePct > 0.15) up++;
            if (r.returnPct < -0.15 || r.changePct < -0.15) down++;
            double vol = Math.max(r.minuteVolume, Math.max(r.snapshotVolume, r.tradeVolume));
            if (vol >= minLiquidVolume()) liquid++;
            if (r.newsCount > 0) newsActive++;
            totalAbsMove += Math.max(Math.abs(r.returnPct), Math.abs(r.changePct));
            totalRange += Math.max(0.0, r.rangePct);
            totalVol += Math.max(0.0, vol);
        }
        for (MarketStateDatabase2.State s : opportunities) {
            longPressure += s.longScore;
            shortPressure += s.shortScore;
        }
        int n = Math.max(1, active.size());
        double breadth = clamp((up - down) / (double)n * 0.5 + 0.5);
        double activity = clamp(Math.log10(Math.max(10.0, totalVol)) / 9.0 + (totalAbsMove / n) / 12.0 + (totalRange / n) / 16.0);
        double parabolic = clamp((totalAbsMove / n) / 9.0 + (totalRange / n) / 11.0 + liquid / (double)n * 0.25);
        double directionBias = clamp((longPressure - shortPressure) / Math.max(1.0, longPressure + shortPressure) * 0.5 + 0.5);

        Map<String, String> m = new LinkedHashMap<>();
        m.put("provider", "LOCAL_MARKET_OPERATING_SYSTEM");
        m.put("activeSymbols", String.valueOf(active.size()));
        m.put("up", String.valueOf(up));
        m.put("down", String.valueOf(down));
        m.put("liquid", String.valueOf(liquid));
        m.put("newsActive", String.valueOf(newsActive));
        m.put("breadth", fmt(breadth));
        m.put("activity", fmt(activity));
        m.put("parabolic", fmt(parabolic));
        m.put("directionBias", directionBias >= 0.5 ? "LONG" : "SHORT");
        m.put("top", summary(opportunities, 8));

        MarketIntelligenceBus.getInstance().publishSignal(new MarketIntelligenceSignal(
                "MARKET_OPERATING_SYSTEM", MarketIntelligenceSignalType.MARKET_DATA, "MARKET",
                "Market OS state: breadth=" + fmt(breadth) + " activity=" + fmt(activity) + " parabolic=" + fmt(parabolic),
                "Continuously maintained market-centric state built from the local knowledge database.",
                System.currentTimeMillis(), 0.76, clamp(activity * 0.45 + parabolic * 0.35 + Math.abs(directionBias - 0.5) * 0.40), m));

        if (log()) System.out.println("MARKET OS REFRESH: activeSymbols=" + active.size() + " up=" + up + " down=" + down + " liquid=" + liquid + " newsActive=" + newsActive + " breadth=" + fmt(breadth) + " activity=" + fmt(activity) + " parabolic=" + fmt(parabolic) + " top=" + summary(opportunities, 8));
    }

    private static String summary(List<MarketStateDatabase2.State> top, int n) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < Math.min(n, top.size()); i++) {
            if (i > 0) b.append(", ");
            MarketStateDatabase2.State s = top.get(i);
            b.append(s.ticker).append(':').append(fmt(s.opportunityScore)).append(':').append(s.direction);
        }
        return b.append(']').toString();
    }

    private static int topK(){return Math.max(50, intEnv("MARKET_OS_COLLECTOR_TOP_K", 1000));}
    private static long intervalMs(){return Math.max(2000L, longEnv("MARKET_OS_COLLECTOR_INTERVAL_MS", 7000L));}
    private static double minLiquidVolume(){return Math.max(0.0, doubleEnv("MARKET_OS_MIN_LIQUID_VOLUME", 50_000.0));}
    private static boolean log(){return boolEnv("MARKET_OS_COLLECTOR_LOG", true);}
    private static double clamp(double v){return Double.isFinite(v)?Math.max(0.0, Math.min(1.0, v)):0.0;}
    private static String fmt(double v){return String.format(Locale.US,"%.4f",Double.isFinite(v)?v:0.0);}    
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ');}    
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private static int intEnv(String k,int f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Integer.parseInt(v.trim());}catch(Exception e){return f;}}
    private static long longEnv(String k,long f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Long.parseLong(v.trim());}catch(Exception e){return f;}}
    private static double doubleEnv(String k,double f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Double.parseDouble(v.trim());}catch(Exception e){return f;}}
    private static boolean boolEnv(String k, boolean f){String v=System.getenv(k); if(v==null||v.isBlank()) return f; String x=v.trim().toLowerCase(Locale.ROOT); return x.equals("true")||x.equals("1")||x.equals("yes")||x.equals("on");}
}
