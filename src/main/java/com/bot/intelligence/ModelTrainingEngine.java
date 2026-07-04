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

/**
 * Lightweight no-dependency probability model trainer.
 *
 * It learns feature weights from realized outcomes and writes them to
 * logs/model_weights.properties. The live bot can consume these weights through
 * policy/config without requiring external ML libraries.
 */
public class ModelTrainingEngine {
    private final Path featurePath = Path.of(System.getenv().getOrDefault("FEATURE_JOURNAL_PATH", "logs/market_features.csv"));
    private final Path outcomePath = Path.of(System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv"));
    private final Path outputPath = Path.of(System.getenv().getOrDefault("MODEL_WEIGHTS_PATH", "logs/model_weights.properties"));

    public ModelTrainingResult train() {
        List<Feature> features = loadFeatures();
        Map<String, Outcome> outcomes = loadOutcomes();
        Map<String, Stat> stats = new LinkedHashMap<>();
        for (Feature f : features) {
            Outcome o = outcomes.get(f.ticker);
            if (o == null) continue;
            add(stats, "bias", 1, o.win());
            add(stats, "rvol", f.rvol, o.win());
            add(stats, "structure", f.structure ? 1 : 0, o.win());
            add(stats, "sentiment", f.sentiment, o.win());
            add(stats, "proposal", f.proposal, o.win());
            add(stats, "momentum", Math.max(0, f.return3), o.win());
            add(stats, "meanReversion", Math.max(0, f.drop + f.bounce), o.win());
        }
        Properties p = new Properties();
        p.setProperty("updatedAt", Instant.now().toString());
        p.setProperty("featureRows", Integer.toString(features.size()));
        p.setProperty("matchedOutcomes", Integer.toString(outcomes.size()));
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            p.setProperty("weight." + e.getKey(), Double.toString(e.getValue().weight()));
            p.setProperty("samples." + e.getKey(), Integer.toString(e.getValue().samples));
        }
        try {
            AutonomousEvolutionSuite.FilesUtil.ensureParent(outputPath);
            try (var out = Files.newOutputStream(outputPath)) { p.store(out, "Autonomous no-dependency model weights"); }
        } catch (Exception e) {
            throw new RuntimeException("Failed to write model weights: " + e.getMessage(), e);
        }
        return new ModelTrainingResult(features.size(), outcomes.size(), stats.size(), outputPath);
    }

    private void add(Map<String, Stat> stats, String name, double value, boolean win) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value == 0.0) return;
        stats.computeIfAbsent(name, k -> new Stat()).add(value, win);
    }

    private List<Feature> loadFeatures() {
        List<Feature> out = new ArrayList<>();
        if (!Files.exists(featurePath)) return out;
        try (BufferedReader r = Files.newBufferedReader(featurePath, StandardCharsets.UTF_8)) {
            String header = r.readLine(); if (header == null) return out;
            CsvHeader h = new CsvHeader(header); String line;
            int maxRows = intEnv("AI_MODEL_MAX_FEATURE_ROWS", 50000);
            while ((line = r.readLine()) != null) {
                if (out.size() >= maxRows) break;
                out.add(Feature.from(h, parse(line), line));
            }
        } catch (Exception e) { System.out.println("Model feature load failed: " + e.getMessage()); }
        return out;
    }

    private Map<String, Outcome> loadOutcomes() {
        Map<String, Outcome> out = new LinkedHashMap<>();
        if (!Files.exists(outcomePath)) return out;
        try (BufferedReader r = Files.newBufferedReader(outcomePath, StandardCharsets.UTF_8)) {
            String header = r.readLine(); if (header == null) return out;
            CsvHeader h = new CsvHeader(header); String line;
            while ((line = r.readLine()) != null) {
                List<String> c = parse(line); String type = h.get(c, "eventType");
                String strategy = h.get(c, "strategyName");
                String syncedFromBroker = h.get(c, "syncedFromBroker");
                if (!TradeOutcomeTrainingFilter.isTrainingEligible(type, strategy, syncedFromBroker)) continue;
                String ticker = h.get(c, "ticker").toUpperCase();
                double pnl = num(h.get(c, "realizedPnlDollars"), 0);
                out.put(ticker, new Outcome(pnl));
            }
        } catch (Exception e) { System.out.println("Model outcome load failed: " + e.getMessage()); }
        return out;
    }

    static final class Feature { String ticker; double rvol, sentiment, proposal, return3, drop, bounce; boolean structure;
        static Feature from(CsvHeader h, List<String> c, String raw){Feature f=new Feature(); f.ticker=h.get(c,"ticker").toUpperCase(); f.rvol=Math.max(num(h.get(c,"rvol5"),0),num(h.get(c,"rvol20"),0)); f.sentiment=num(h.get(c,"sentimentNet"),0); f.proposal=num(h.get(c,"proposalScore"),0); f.return3=num(h.get(c,"return3Bars"),0); f.drop=num(h.get(c,"dropFromHigh20"),0); f.bounce=num(h.get(c,"bounceFromLow20"),0); String l=raw==null?"":raw.toLowerCase(); f.structure=bool(h.get(c,"bullishBreak"))||bool(h.get(c,"reclaimedVwap"))||l.contains("bullish break")||l.contains("vwap strength"); return f;}
    }
    static final class Outcome { double pnl; Outcome(double p){pnl=p;} boolean win(){return pnl>0;} }
    static final class Stat { int samples; double winValue, lossValue; void add(double v, boolean win){samples++; if(win)winValue+=v; else lossValue+=v;} double weight(){return samples==0?0:(winValue-lossValue)/samples;} }
    public static final class ModelTrainingResult { public final int features,outcomes,weights; public final Path path; ModelTrainingResult(int f,int o,int w,Path p){features=f;outcomes=o;weights=w;path=p;} public String summary(){return "features="+features+" outcomes="+outcomes+" weights="+weights+" path="+path;} }
    static final class CsvHeader { final Map<String,Integer> idx=new LinkedHashMap<>(); CsvHeader(String h){List<String> c=parse(h);for(int i=0;i<c.size();i++)idx.put(c.get(i).trim(),i);} String get(List<String> c,String n){Integer i=idx.get(n);return i==null||i<0||i>=c.size()?"":c.get(i);} }
    static List<String> parse(String line){List<String> out=new ArrayList<>();StringBuilder cur=new StringBuilder();boolean q=false;if(line==null)return out;for(int i=0;i<line.length();i++){char ch=line.charAt(i);if(q){if(ch=='"'){if(i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;}else q=false;}else cur.append(ch);}else{if(ch==','){out.add(cur.toString());cur.setLength(0);}else if(ch=='"')q=true;else cur.append(ch);}}out.add(cur.toString());return out;}
    static double num(String v,double f){try{return v==null||v.isBlank()?f:Double.parseDouble(v.trim());}catch(Exception e){return f;}}
    static boolean bool(String v){return "true".equalsIgnoreCase(v==null?"":v.trim());}
    static int intEnv(String key,int fallback){try{String v=System.getenv(key);return v==null||v.isBlank()?fallback:Integer.parseInt(v.trim());}catch(Exception e){return fallback;}}
}
