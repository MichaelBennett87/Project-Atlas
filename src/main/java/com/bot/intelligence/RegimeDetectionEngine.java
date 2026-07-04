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
import java.util.Properties;

/** Detects current market regime from collected feature data. */
public class RegimeDetectionEngine {
    private final Path featurePath = Path.of(System.getenv().getOrDefault("FEATURE_JOURNAL_PATH", "logs/market_features.csv"));
    private final Path outputPath = Path.of(System.getenv().getOrDefault("REGIME_POLICY_PATH", "logs/regime_policy.properties"));

    public RegimeResult detect() {
        List<Row> rows = load();
        double avgRvol=0, avgAbsReturn=0, avgSentiment=0; int rvolCount=0, catalystCount=0;
        for (Row r: rows){ avgRvol+=r.rvol; avgAbsReturn+=Math.abs(r.ret3); avgSentiment+=r.sentiment; if(r.rvol>0)rvolCount++; if(r.catalyst>0||Math.abs(r.sentiment)>0.2) catalystCount++; }
        int n=Math.max(1,rows.size()); avgRvol/=n; avgAbsReturn/=n; avgSentiment/=n;
        double rvolCoverage=rvolCount/(double)n, catalystCoverage=catalystCount/(double)n;
        String regime="BALANCED";
        if(rows.size()<50) regime="LOW_DATA";
        else if(rvolCoverage<0.20) regime="LOW_VOLUME";
        else if(avgRvol>1.8 && avgAbsReturn>0.003) regime="HIGH_RVOL_MOMENTUM";
        else if(catalystCoverage>0.35 || Math.abs(avgSentiment)>0.20) regime="NEWS_CATALYST";
        else if(avgAbsReturn<0.0015) regime="CHOPPY_MEAN_REVERSION";
        Properties p=new Properties(); p.setProperty("updatedAt", Instant.now().toString()); p.setProperty("regime",regime); p.setProperty("featureRows",Integer.toString(rows.size())); p.setProperty("avgRvol",Double.toString(avgRvol)); p.setProperty("avgAbsReturn3",Double.toString(avgAbsReturn)); p.setProperty("rvolCoverage",Double.toString(rvolCoverage)); p.setProperty("catalystCoverage",Double.toString(catalystCoverage));
        try{AutonomousEvolutionSuite.FilesUtil.ensureParent(outputPath); try(var out=Files.newOutputStream(outputPath)){p.store(out,"Autonomous regime detection");}}catch(Exception e){throw new RuntimeException(e);}        
        return new RegimeResult(regime, rows.size(), avgRvol, rvolCoverage);
    }
    private List<Row> load(){List<Row> out=new ArrayList<>(); if(!Files.exists(featurePath))return out; try(BufferedReader r=Files.newBufferedReader(featurePath, StandardCharsets.UTF_8)){String header=r.readLine(); if(header==null)return out; CsvHeader h=new CsvHeader(header); String line; int maxRows=intEnv("AI_REGIME_MAX_FEATURE_ROWS",50000); while((line=r.readLine())!=null){ if(out.size()>=maxRows) break; List<String> c=parse(line); Row row=new Row(); row.rvol=Math.max(num(h.get(c,"rvol5"),0),num(h.get(c,"rvol20"),0)); row.ret3=num(h.get(c,"return3Bars"),0); row.sentiment=num(h.get(c,"sentimentNet"),0); row.catalyst=num(h.get(c,"catalystScore"),0); out.add(row);}}catch(Exception e){System.out.println("Regime load failed: "+e.getMessage());} return out;}
    static final class Row{double rvol,ret3,sentiment,catalyst;}
    public static final class RegimeResult{public final String regime; public final int rows; public final double avgRvol,rvolCoverage; RegimeResult(String r,int rows,double a,double c){regime=r;this.rows=rows;avgRvol=a;rvolCoverage=c;} public String summary(){return regime+" rows="+rows+" avgRvol="+avgRvol+" rvolCoverage="+rvolCoverage;}}
    static final class CsvHeader{final Map<String,Integer>idx=new LinkedHashMap<>();CsvHeader(String h){List<String>c=parse(h);for(int i=0;i<c.size();i++)idx.put(c.get(i).trim(),i);}String get(List<String>c,String n){Integer i=idx.get(n);return i==null||i<0||i>=c.size()?"":c.get(i);}}
    static List<String> parse(String line){List<String>out=new ArrayList<>();StringBuilder cur=new StringBuilder();boolean q=false;if(line==null)return out;for(int i=0;i<line.length();i++){char ch=line.charAt(i);if(q){if(ch=='"'){if(i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;}else q=false;}else cur.append(ch);}else{if(ch==','){out.add(cur.toString());cur.setLength(0);}else if(ch=='"')q=true;else cur.append(ch);}}out.add(cur.toString());return out;}
    static double num(String v,double f){try{return v==null||v.isBlank()?f:Double.parseDouble(v.trim());}catch(Exception e){return f;}}
    static int intEnv(String key,int fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Integer.parseInt(v.trim());}catch(Exception e){return fallback;}}
}
