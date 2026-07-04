package com.bot.intelligence;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers new strategy templates from feature/outcome correlations.
 *
 * It writes generated strategy recipes to logs/discovered_strategies.properties.
 * These are policy-level recipes consumed by the evolution tournament; they are
 * deliberately bounded and data-driven rather than arbitrary live code edits.
 */
public class StrategyDiscoveryEngine {
    private final Path featurePath = Path.of(System.getenv().getOrDefault("FEATURE_JOURNAL_PATH", "logs/market_features.csv"));
    private final Path outputPath = Path.of(System.getenv().getOrDefault("DISCOVERED_STRATEGIES_PATH", "logs/discovered_strategies.properties"));

    public StrategyDiscoveryResult discover() {
        List<Row> rows = load();
        StringBuilder b = new StringBuilder();
        b.append("# Autonomous strategy discovery\nupdatedAt=").append(Instant.now()).append('\n');
        int n = 0;
        if (avg(rows, "rvol") > 1.5) b.append("strategy.").append(++n).append("=RVOL_EXPANSION_BREAKOUT\n");
        if (avg(rows, "vwap") > 0.25) b.append("strategy.").append(++n).append("=VWAP_STRUCTURE_CONTINUATION\n");
        if (avg(rows, "mean") > 0.20) b.append("strategy.").append(++n).append("=PANIC_RECLAIM_MEAN_REVERSION\n");
        if (avg(rows, "sentiment") > 0.25) b.append("strategy.").append(++n).append("=CATALYST_SENTIMENT_CONFIRMATION\n");
        if (n == 0) b.append("strategy.1=BALANCED_LOW_DATA_EXPLORER\n");
        AutonomousEvolutionSuite.FilesUtil.writeString(outputPath, b.toString());
        return new StrategyDiscoveryResult(Math.max(n,1), rows.size(), outputPath);
    }
    private double avg(List<Row> rows,String key){if(rows.isEmpty())return 0;double s=0;for(Row r:rows){switch(key){case"rvol":s+=r.rvol;break;case"vwap":s+=r.vwap?1:0;break;case"mean":s+=r.drop+r.bounce;break;case"sentiment":s+=Math.max(0,r.sentiment);break;}}return s/rows.size();}
    private List<Row> load(){List<Row> out=new ArrayList<>();if(!Files.exists(featurePath))return out;try(BufferedReader r=Files.newBufferedReader(featurePath, StandardCharsets.UTF_8)){String header=r.readLine();if(header==null)return out;CsvHeader h=new CsvHeader(header);String line;int maxRows=intEnv("AI_DISCOVERY_MAX_FEATURE_ROWS",50000);while((line=r.readLine())!=null){if(out.size()>=maxRows) break; List<String>c=parse(line);Row row=new Row();row.rvol=Math.max(num(h.get(c,"rvol5"),0),num(h.get(c,"rvol20"),0));row.vwap=bool(h.get(c,"reclaimedVwap"))||line.toLowerCase().contains("vwap strength");row.drop=num(h.get(c,"dropFromHigh20"),0);row.bounce=num(h.get(c,"bounceFromLow20"),0);row.sentiment=num(h.get(c,"sentimentNet"),0);out.add(row);}}catch(Exception e){System.out.println("Discovery load failed: "+e.getMessage());}return out;}
    static final class Row{double rvol,drop,bounce,sentiment;boolean vwap;}
    public static final class StrategyDiscoveryResult{public final int strategies,rows;public final Path path;StrategyDiscoveryResult(int s,int r,Path p){strategies=s;rows=r;path=p;}public String summary(){return "strategies="+strategies+" rows="+rows+" path="+path;}}
    static final class CsvHeader{final Map<String,Integer>idx=new LinkedHashMap<>();CsvHeader(String h){List<String>c=parse(h);for(int i=0;i<c.size();i++)idx.put(c.get(i).trim(),i);}String get(List<String>c,String n){Integer i=idx.get(n);return i==null||i<0||i>=c.size()?"":c.get(i);}}
    static List<String> parse(String line){List<String>out=new ArrayList<>();StringBuilder cur=new StringBuilder();boolean q=false;if(line==null)return out;for(int i=0;i<line.length();i++){char ch=line.charAt(i);if(q){if(ch=='"'){if(i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;}else q=false;}else cur.append(ch);}else{if(ch==','){out.add(cur.toString());cur.setLength(0);}else if(ch=='"')q=true;else cur.append(ch);}}out.add(cur.toString());return out;}
    static double num(String v,double f){try{return v==null||v.isBlank()?f:Double.parseDouble(v.trim());}catch(Exception e){return f;}}
    static boolean bool(String v){return "true".equalsIgnoreCase(v==null?"":v.trim());}
    static int intEnv(String key,int fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Integer.parseInt(v.trim());}catch(Exception e){return fallback;}}
}
