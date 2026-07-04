package com.bot.intelligence;

import com.bot.intelligence.bus.MarketIntelligenceBus;
import com.bot.intelligence.bus.MarketIntelligenceSignal;
import com.bot.intelligence.bus.MarketIntelligenceSignalType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Publishes the best continuously maintained market-state opportunities to the intelligence bus. */
public final class StateDrivenOpportunityRankingEngine {
    private volatile boolean running;
    private volatile Thread worker;

    public synchronized void start() {
        if (running) return;
        running = true;
        MarketStateDatabase2.getInstance().start();
        worker = new Thread(this::loop, "state-driven-opportunity-ranker");
        worker.setDaemon(true);
        worker.start();
        System.out.println("STATE-DRIVEN OPPORTUNITY RANKING ENGINE STARTED: source=MarketStateDatabase2 intervalMs=" + intervalMs() + " topK=" + topK());
    }

    public synchronized void stop(){running=false; Thread t=worker; if(t!=null)t.interrupt();}

    private void loop(){while(running){try{runOnce();}catch(Exception e){System.out.println("STATE OPPORTUNITY RANKER ERROR: "+safe(e.getMessage()));} sleep(intervalMs());}}

    private void runOnce(){
        MarketStateDatabase2.getInstance().refresh();
        if (!boolEnv("STATE_OPPORTUNITY_RANKER_EMIT_SIGNALS", false)) {
            if (log()) {
                List<MarketStateDatabase2.State> top = MarketStateDatabase2.getInstance().topOpportunities(topK());
                System.out.println("STATE OPPORTUNITY RANKER SCAN: candidates=" + top.size() +
                        " emitted=0 mode=RESEARCH_ONLY top=" + summary(top, 8));
            }
            return;
        }
        List<MarketStateDatabase2.State> top = MarketStateDatabase2.getInstance().topOpportunities(topK());
        int emitted=0;
        for(MarketStateDatabase2.State s: top){
            if(s.opportunityScore < minScore()) continue;
            Map<String,String> m=new LinkedHashMap<>();
            m.put("provider","LOCAL_MARKET_STATE_DB2");
            m.put("direction",s.direction);
            m.put("technical",fmt(s.technicalScore));
            m.put("orderFlow",fmt(s.orderFlowScore));
            m.put("microstructure",fmt(s.microstructureScore));
            m.put("risk",fmt(s.riskScore));
            m.put("routeToNews", boolEnv("STATE_OPPORTUNITY_ROUTE_TO_NEWS", false) ? "true" : "false");
            m.put("catalystScore", fmt(Math.max(doubleEnv("STATE_OPPORTUNITY_SYNTHETIC_CATALYST_SCORE", 0.12), s.opportunityScore)));
            m.put("sentimentScore", fmt(Math.max(0.55, s.opportunityScore)));
            m.put("syntheticMarketStateOpportunity", "true");
            MarketIntelligenceBus.getInstance().publishSignal(new MarketIntelligenceSignal(
                    "STATE_OPPORTUNITY_RANKER", MarketIntelligenceSignalType.MARKET_DATA, s.ticker,
                    "State opportunity: "+s.ticker+" direction="+s.direction+" score="+fmt(s.opportunityScore),
                    "Continuously maintained local market state supports this opportunity.", System.currentTimeMillis(), 0.74, s.opportunityScore, m));
            emitted++;
        }
        if(log()) System.out.println("STATE OPPORTUNITY RANKER SCAN: candidates="+top.size()+" emitted="+emitted+" top="+summary(top,8));
    }

    private static String summary(List<MarketStateDatabase2.State> top,int n){StringBuilder b=new StringBuilder("["); for(int i=0;i<Math.min(n,top.size());i++){if(i>0)b.append(", "); MarketStateDatabase2.State s=top.get(i); b.append(s.ticker).append(":").append(fmt(s.opportunityScore)).append(":").append(s.direction);} return b.append(']').toString();}
    private static int topK(){return Math.max(5,intEnv("STATE_OPPORTUNITY_RANKER_TOP_K",50));}
    private static double minScore(){return Math.max(0.01,Math.min(0.95,doubleEnv("STATE_OPPORTUNITY_RANKER_MIN_SCORE",0.92)));}
    private static long intervalMs(){return Math.max(1000L,longEnv("STATE_OPPORTUNITY_RANKER_INTERVAL_MS",30000L));}
    private static boolean log(){return boolEnv("STATE_OPPORTUNITY_RANKER_LOG",true);}    
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private static String fmt(double v){return String.format(Locale.US,"%.5f",Double.isFinite(v)?v:0.0);}    
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ');}    
    private static int intEnv(String k,int f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Integer.parseInt(v.trim());}catch(Exception e){return f;}}
    private static long longEnv(String k,long f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Long.parseLong(v.trim());}catch(Exception e){return f;}}
    private static double doubleEnv(String k,double f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Double.parseDouble(v.trim());}catch(Exception e){return f;}}
    private static boolean boolEnv(String k, boolean f){String v=System.getenv(k); if(v==null||v.isBlank()) return f; String x=v.trim().toLowerCase(Locale.ROOT); return x.equals("true")||x.equals("1")||x.equals("yes")||x.equals("on");}
}
