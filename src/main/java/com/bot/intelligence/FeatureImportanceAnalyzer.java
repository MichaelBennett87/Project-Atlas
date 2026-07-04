package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Nightly feature selection helper using simple correlation against trade outcomes. */
public final class FeatureImportanceAnalyzer {
    private final Path featurePath = Path.of(env("TECHNICAL_FEATURE_JOURNAL", "logs/technical_feature_vectors.csv"));
    private final Path outcomePath = Path.of(env("TRADE_OUTCOME_JOURNAL", "logs/trade_outcomes.csv"));
    private final Path journal = Path.of(env("FEATURE_IMPORTANCE_JOURNAL", "logs/feature_importance.csv"));

    public Result analyze() {
        List<Map<String, String>> features = readRows(featurePath);
        List<Map<String, String>> outcomes = readRows(outcomePath);
        if (features.isEmpty()) return new Result(0, 0, "no technical features yet");
        Map<String, Double> pnlByTicker = new HashMap<>();
        for (Map<String, String> o : outcomes) {
            if (!TradeOutcomeTrainingFilter.isTrainingEligible(
                    first(o, "eventType"),
                    first(o, "strategyName", "strategy"),
                    first(o, "syncedFromBroker"))) {
                continue;
            }
            String t = first(o, "ticker", "symbol").toUpperCase(Locale.ROOT);
            double pnl = num(first(o, "realizedPnlDollars", "pnl", "profit", "gain", "returnPct", "realizedPnl"), 0);
            if (!t.isBlank()) pnlByTicker.merge(t, pnl, Double::sum);
        }
        String[] keys = {"returnPct","vwapDistancePct","emaTrendScore","rsi14","atrPct","relativeVolume","volumeAcceleration","intradayRangePct","momentumSlope","pullbackDepthPct","breakoutScore","meanReversionScore","parabolicScore","technicalScore"};
        List<String> lines = new ArrayList<>();
        for (String key : keys) {
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            for (Map<String, String> f : features) {
                String t = first(f, "ticker", "symbol").toUpperCase(Locale.ROOT);
                Double y = pnlByTicker.get(t);
                if (y == null) continue;
                xs.add(num(f.get(key), 0)); ys.add(y);
            }
            double corr = corr(xs, ys);
            lines.add(csv(Instant.now().toString()) + "," + csv(key) + "," + xs.size() + "," + d(corr) + "," + csv(Math.abs(corr) > 0.15 ? "WATCH" : "NEUTRAL"));
        }
        write(lines);
        return new Result(features.size(), outcomes.size(), "features=" + features.size() + " outcomes=" + outcomes.size() + " journal=" + journal);
    }

    private void write(List<String> lines) {
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            boolean exists = Files.exists(journal) && Files.size(journal) > 0;
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (!exists) { w.write("generatedAt,feature,samples,correlation,recommendation"); w.newLine(); }
                for (String line : lines) { w.write(line); w.newLine(); }
            }
        } catch (Exception e) { System.out.println("FEATURE IMPORTANCE ANALYZER FAILED: " + e.getMessage()); }
    }

    static List<Map<String, String>> readRows(Path path) {
        List<Map<String, String>> out = new ArrayList<>();
        if (!Files.exists(path)) return out;
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = r.readLine(); if (header == null) return out;
            List<String> h = parse(header); String line;
            while ((line = r.readLine()) != null) {
                List<String> c = parse(line); Map<String, String> row = new HashMap<>();
                for (int i=0; i<h.size() && i<c.size(); i++) row.put(h.get(i).trim(), c.get(i).trim());
                out.add(row);
            }
        } catch (Exception ignored) {}
        return out;
    }
    static List<String> parse(String line) { List<String> out=new ArrayList<>(); StringBuilder cur=new StringBuilder(); boolean q=false; if(line==null)return out; for(int i=0;i<line.length();i++){char ch=line.charAt(i); if(q){ if(ch=='"'){ if(i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;} else q=false;} else cur.append(ch);} else { if(ch==','){out.add(cur.toString());cur.setLength(0);} else if(ch=='"') q=true; else cur.append(ch);} } out.add(cur.toString()); return out; }
    private static double corr(List<Double> xs, List<Double> ys) { int n=Math.min(xs.size(),ys.size()); if(n<3) return 0; double sx=0,sy=0; for(int i=0;i<n;i++){sx+=xs.get(i);sy+=ys.get(i);} double mx=sx/n,my=sy/n,num=0,dx=0,dy=0; for(int i=0;i<n;i++){double a=xs.get(i)-mx,b=ys.get(i)-my; num+=a*b; dx+=a*a; dy+=b*b;} return dx==0||dy==0?0:num/Math.sqrt(dx*dy); }
    private static String first(Map<String,String> row, String... keys){ for(String k:keys){String v=row.get(k); if(v!=null&&!v.isBlank()) return v;} return ""; }
    private static double num(String v,double f){ try{return v==null||v.isBlank()?f:Double.parseDouble(v.replace("%","").trim());}catch(Exception e){return f;} }
    private static String d(double v){ return String.format(Locale.US,"%.6f",Double.isFinite(v)?v:0); }
    private static String csv(String v){ String s=v==null?"":v.replace("\n"," ").replace("\r"," "); return '"'+s.replace("\"","\"\"")+'"'; }
    private static String env(String k,String f){String v=System.getenv(k);return v==null||v.isBlank()?f:v.trim();}
    public static final class Result { public final int features,outcomes; public final String summary; Result(int f,int o,String s){features=f;outcomes=o;summary=s;} }
}
