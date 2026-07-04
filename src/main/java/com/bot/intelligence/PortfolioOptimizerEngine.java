package com.bot.intelligence;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;

/** Allocates capital across surviving strategy species using realized outcome stats. */
public class PortfolioOptimizerEngine {
    private final Path outcomePath = Path.of(System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv"));
    private final Path outputPath = Path.of(System.getenv().getOrDefault("PORTFOLIO_POLICY_PATH", "logs/portfolio_policy.properties"));

    public PortfolioResult optimize() {
        Map<String, Stats> stats = load();
        Map<String, Double> raw = new LinkedHashMap<>();
        double total = 0.0;
        for (Map.Entry<String, Stats> e : stats.entrySet()) {
            Stats s = e.getValue();
            double score = Math.max(0.05, 1.0 + s.expectancy() * 0.05 + (s.profitFactor() - 1.0) * 0.30 - s.drawdownPenalty());
            if (s.trades < 3) score *= 0.50;
            raw.put(e.getKey(), score); total += score;
        }
        if (raw.isEmpty()) { raw.put("MARKET_INTELLIGENCE_AI", 1.0); total = 1.0; }
        Properties p = new Properties(); p.setProperty("updatedAt", Instant.now().toString()); p.setProperty("strategyCount", Integer.toString(raw.size()));
        for (Map.Entry<String, Double> e : raw.entrySet()) p.setProperty("allocation." + e.getKey(), Double.toString(e.getValue()/Math.max(0.000001,total)));
        try { AutonomousEvolutionSuite.FilesUtil.ensureParent(outputPath); try(var out=Files.newOutputStream(outputPath)){p.store(out,"Autonomous portfolio optimizer policy");} } catch(Exception e){throw new RuntimeException(e);}        
        return new PortfolioResult(raw.size(), outputPath);
    }

    private Map<String, Stats> load(){Map<String,Stats> out=new LinkedHashMap<>(); if(!Files.exists(outcomePath))return out; try(BufferedReader r=Files.newBufferedReader(outcomePath, StandardCharsets.UTF_8)){String header=r.readLine(); if(header==null)return out; CsvHeader h=new CsvHeader(header); String line; while((line=r.readLine())!=null){List<String> c=parse(line); String type=h.get(c,"eventType"); String s=norm(h.get(c,"strategyName")); String synced=h.get(c,"syncedFromBroker"); if(!TradeOutcomeTrainingFilter.isTrainingEligible(type,s,synced))continue; double pnl=num(h.get(c,"realizedPnlDollars"),0); double dd=num(h.get(c,"maxDrawdownPercent"),0); out.computeIfAbsent(s,k->new Stats()).add(pnl,dd);}}catch(Exception e){System.out.println("Portfolio outcome load failed: "+e.getMessage());} return out;}
    static final class Stats{int trades;double pnl,win,loss,dd;void add(double p,double d){trades++;pnl+=p;dd+=Math.abs(d);if(p>0)win+=p;else loss+=Math.abs(p);}double expectancy(){return trades==0?0:pnl/trades;}double profitFactor(){return loss==0?(win>0?9:1):win/loss;}double drawdownPenalty(){return trades==0?0:Math.min(0.75,dd/trades*2.0);}}
    public static final class PortfolioResult{public final int strategies;public final Path path;PortfolioResult(int s,Path p){strategies=s;path=p;}public String summary(){return "strategies="+strategies+" path="+path;}}
    static final class CsvHeader{final Map<String,Integer>idx=new LinkedHashMap<>();CsvHeader(String h){List<String>c=parse(h);for(int i=0;i<c.size();i++)idx.put(c.get(i).trim(),i);}String get(List<String>c,String n){Integer i=idx.get(n);return i==null||i<0||i>=c.size()?"":c.get(i);}}
    static List<String> parse(String line){List<String>out=new ArrayList<>();StringBuilder cur=new StringBuilder();boolean q=false;if(line==null)return out;for(int i=0;i<line.length();i++){char ch=line.charAt(i);if(q){if(ch=='"'){if(i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;}else q=false;}else cur.append(ch);}else{if(ch==','){out.add(cur.toString());cur.setLength(0);}else if(ch=='"')q=true;else cur.append(ch);}}out.add(cur.toString());return out;}
    static double num(String v,double f){try{return v==null||v.isBlank()?f:Double.parseDouble(v.trim());}catch(Exception e){return f;}}
    static String norm(String v){return v==null||v.isBlank()?"UNKNOWN":v.trim().toUpperCase();}
}
