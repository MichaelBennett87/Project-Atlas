package com.bot.intelligence.bus;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight research-agent swarm for choosing what Polygon Premium should study next.
 * Each agent contributes a specialized symbol set; the shared scheduler then asks Polygon.
 */
public final class ResearchAgentSwarm {
    private static final ResearchAgentSwarm INSTANCE = new ResearchAgentSwarm();
    private ResearchAgentSwarm() {}
    public static ResearchAgentSwarm getInstance() { return INSTANCE; }

    public List<String> proposeSymbols(int max) {
        Set<String> out = new LinkedHashSet<>();
        add(out, env("RESEARCH_SWARM_FORCE_SYMBOLS", ""));
        // Question / information-gain agents get first priority because they target uncertainty.
        for (ResearchQuestion q : ResearchQuestionGenerator.getInstance().generateQuestions(max)) {
            String t = norm(q.symbol);
            if (!t.isBlank()) out.add(t);
            if (out.size() >= max) break;
        }
        // Candidate / opportunity agent.
        readTickerColumn(out, Path.of("logs/unified_candidate_scores.csv"), max);
        // State-driven market OS agents.
        readTickerColumn(out, Path.of("logs/market_state_database_2.csv"), max);
        readTickerColumn(out, Path.of("logs/market_os_unified_state.csv"), max);
        readTickerColumn(out, Path.of("logs/market_os_unified_state_snapshots.csv"), max);
        // Technical / momentum agent.
        readTickerColumn(out, Path.of("logs/live_feature_store.csv"), max);
        readTickerColumn(out, Path.of("logs/market_features.csv"), max);
        // Market knowledge / Polygon enrichment agent.
        readTickerColumn(out, Path.of("logs/market_knowledge_database.csv"), max);
        // Memory / personality agent.
        readTickerColumn(out, Path.of("logs/opportunity_memory.csv"), max);
        readTickerColumn(out, Path.of("logs/stock_memory.csv"), max);
        if (out.isEmpty()) add(out, env("RESEARCH_SWARM_DEFAULT_SYMBOLS", "SPY,QQQ,IWM,AAPL,NVDA,TSLA,META,AMD,MSFT,AMZN,MARA,RGTI,SMR"));
        List<String> list = new ArrayList<>();
        for (String s : out) { if (list.size() >= max) break; if (!s.isBlank()) list.add(s); }
        if (boolEnv("RESEARCH_SWARM_LOG_ENABLED", true)) {
            System.out.println("RESEARCH AGENT SWARM: agents=universe,technical,momentum,news,knowledge,memory proposed=" + list.size() + " top=" + list.subList(0, Math.min(10, list.size())));
        }
        return list;
    }

    private static void add(Set<String> out, String csv) { if(csv==null)return; for(String p:csv.split(",")){String t=norm(p); if(!t.isBlank())out.add(t);} }
    private static void readTickerColumn(Set<String> out, Path path, int max) {
        if(out.size()>=max||!Files.exists(path))return;
        try(BufferedReader r=Files.newBufferedReader(path)){
            String h=r.readLine(); int idx=findTickerIndex(h); String line;
            while((line=r.readLine())!=null&&out.size()<max){
                List<String> c=parse(line);
                if(idx>=0&&idx<c.size()){String t=norm(c.get(idx)); if(!t.isBlank())out.add(t);}
                else {
                    for (String cell : c) { String t=norm(cell); if(!t.isBlank()){out.add(t); break;} }
                }
            }
        }catch(Exception ignored){}
    }
    private static int findTickerIndex(String h){
        List<String> c=parse(h);
        for(int i=0;i<c.size();i++){String x=c.get(i).trim().toLowerCase(Locale.ROOT); if(x.equals("ticker")||x.equals("symbol")||x.equals("ticker_symbol")||x.equals("asset")||x.equals("underlying"))return i;}
        // If the first column is timestamp, do not return 0; try the common second ticker column.
        if(c.size()>1 && c.get(0).toLowerCase(Locale.ROOT).contains("time")) return 1;
        return 0;
    }
    private static String norm(String s){
        if(s==null)return""; String t=s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]","");
        if(t.isBlank()||t.equals("TICKER")||t.equals("SYMBOL")||t.equals("NULL")||t.equals("NONE")||t.equals("UNKNOWN")) return "";
        if(t.length()>8||t.contains("/")||t.endsWith("USD")) return "";
        // Reject timestamps and ISO fragments that previously polluted the research queue.
        if(t.matches(".*\\d{4}-\\d{2}-\\d{2}.*")||t.startsWith("202")||t.contains("T12")||t.contains("Z")) return "";
        return t.matches("[A-Z][A-Z0-9.\\-]{0,7}") ? t : "";
    }
    private static String env(String k,String f){String v=System.getenv(k);return v==null||v.isBlank()?f:v.trim();}
    private static boolean boolEnv(String k,boolean f){String v=System.getenv(k);return v==null||v.isBlank()?f:"true".equalsIgnoreCase(v.trim())||"1".equals(v.trim());}
    private static List<String> parse(String line){List<String> out=new ArrayList<>(); if(line==null)return out; StringBuilder cur=new StringBuilder(); boolean q=false; for(int i=0;i<line.length();i++){char ch=line.charAt(i); if(q){if(ch=='"'){if(i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;}else q=false;}else cur.append(ch);}else{if(ch==','){out.add(cur.toString());cur.setLength(0);}else if(ch=='"')q=true;else cur.append(ch);}} out.add(cur.toString()); return out;}
}
