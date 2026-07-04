package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight relationship graph for sectors/themes/sympathy chains.
 * Agents can use this to reason beyond isolated tickers, e.g. NVDA -> AI -> semiconductors -> power demand.
 */
public final class MarketKnowledgeGraph {
    private final Map<String, Set<Edge>> graph = new LinkedHashMap<>();
    private final Path source = Path.of(env("MARKET_KNOWLEDGE_GRAPH_PATH", "logs/market_knowledge_graph.csv"));
    private final Path journal = Path.of(env("MARKET_KNOWLEDGE_GRAPH_JOURNAL", "logs/market_knowledge_graph_expansions.csv"));

    public MarketKnowledgeGraph() { loadDefaults(); loadExternal(); }

    public List<Edge> expand(String node, int depth) {
        List<Edge> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        expand(node == null ? "" : node.toUpperCase(Locale.ROOT), Math.max(1, depth), seen, out);
        return out;
    }

    public Result runNightlyExpansion() {
        int seeds = 0, edges = 0;
        Set<String> nodes = new LinkedHashSet<>();
        nodes.addAll(graph.keySet());
        readTickerSeeds(nodes, Path.of("logs/unified_candidate_scores.csv"));
        readTickerSeeds(nodes, Path.of("logs/opportunity_memory.csv"));
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                w.write("generatedAt,seed,relatedNode,relationship,weight"); w.newLine();
                for (String node : nodes) {
                    if (++seeds > intEnv("MARKET_KNOWLEDGE_GRAPH_MAX_SEEDS", 500)) break;
                    for (Edge e : expand(node, 2)) {
                        w.write(String.join(",", csv(Instant.now().toString()), csv(node), csv(e.to), csv(e.type), d(e.weight))); w.newLine(); edges++;
                    }
                }
            }
        } catch (Exception e) { System.out.println("MARKET KNOWLEDGE GRAPH EXPANSION FAILED: " + e.getMessage()); }
        System.out.println("MARKET KNOWLEDGE GRAPH UPDATED: nodes=" + graph.size() + " seeds=" + seeds + " edges=" + edges + " journal=" + journal);
        return new Result(graph.size(), edges, journal.toString());
    }

    private void expand(String node, int depth, Set<String> seen, List<Edge> out) {
        if (node.isBlank() || depth <= 0 || !seen.add(node)) return;
        for (Edge e : graph.getOrDefault(node, Set.of())) { out.add(e); expand(e.to, depth - 1, seen, out); }
    }

    private void loadDefaults() {
        link("NVDA", "AI", "THEME", .95); link("AMD", "AI", "THEME", .8); link("SMCI", "AI", "THEME", .85);
        link("AI", "SEMICONDUCTORS", "SUPPLY_CHAIN", .9); link("SEMICONDUCTORS", "POWER_DEMAND", "DERIVATIVE_THEME", .75);
        link("POWER_DEMAND", "UTILITIES", "SECTOR_IMPACT", .65); link("POWER_DEMAND", "NUCLEAR", "SECTOR_IMPACT", .65);
        link("TSLA", "EV", "THEME", .9); link("EV", "LITHIUM", "INPUT", .75); link("LITHIUM", "MINERS", "SECTOR_IMPACT", .7);
        link("OIL", "ENERGY", "SECTOR", .9); link("OIL", "AIRLINES", "INVERSE_COST", .6); link("OIL", "CRUISE_LINES", "INVERSE_COST", .55);
        link("XBI", "BIOTECH", "ETF_THEME", .9); link("FDA", "BIOTECH", "CATALYST_THEME", .9);
        link("BTCUSD", "COIN", "SYMPATHY", .75); link("BTCUSD", "MSTR", "SYMPATHY", .8); link("BTCUSD", "MARA", "SYMPATHY", .75);
    }

    private void loadExternal() {
        if (!Files.exists(source)) return;
        try (BufferedReader r = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                List<String> c = parse(line); if (c.size() < 3) continue;
                link(c.get(0), c.get(1), c.get(2), c.size() > 3 ? num(c.get(3), .5) : .5);
            }
        } catch (Exception ignored) {}
    }

    private void link(String from, String to, String type, double weight) {
        String f = norm(from), t = norm(to); if (f.isBlank() || t.isBlank()) return;
        graph.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(new Edge(f, t, type, weight));
        graph.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(new Edge(t, f, "REVERSE_" + type, weight * .75));
    }

    private static void readTickerSeeds(Set<String> nodes, Path path) { if(!Files.exists(path))return; try(BufferedReader r=Files.newBufferedReader(path, StandardCharsets.UTF_8)){String h=r.readLine(); int idx=findTickerIndex(h); String line; while((line=r.readLine())!=null){List<String> c=parse(line); if(idx>=0&&idx<c.size()){String t=norm(c.get(idx)); if(t.matches("[A-Z][A-Z0-9.-]{0,9}")) nodes.add(t);}}}catch(Exception ignored){} }
    private static int findTickerIndex(String h){List<String> p=parse(h); for(int i=0;i<p.size();i++){String x=p.get(i).toLowerCase(Locale.ROOT); if(x.equals("ticker")||x.equals("symbol"))return i;} return 0;}
    private static String norm(String s){return s==null?"":s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", "_");}
    private static String env(String k,String f){String v=System.getenv(k);return v==null||v.isBlank()?f:v.trim();}
    private static int intEnv(String k,int f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Integer.parseInt(v.trim());}catch(Exception e){return f;}}
    private static double num(String v,double f){try{return v==null||v.isBlank()?f:Double.parseDouble(v.trim());}catch(Exception e){return f;}}
    private static String d(double v){return String.format(Locale.US,"%.6f",Double.isFinite(v)?v:0);}
    private static String csv(String v){String s=v==null?"":v.replace("\n"," ").replace("\r"," ");return '"'+s.replace("\"","\"\"")+'"';}
    private static List<String> parse(String line){List<String> out=new ArrayList<>();StringBuilder cur=new StringBuilder();boolean q=false;if(line==null)return out;for(int i=0;i<line.length();i++){char ch=line.charAt(i);if(q){if(ch=='"'){if(i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;}else q=false;}else cur.append(ch);}else{if(ch==','){out.add(cur.toString());cur.setLength(0);}else if(ch=='"')q=true;else cur.append(ch);}}out.add(cur.toString());return out;}
    public static final class Edge { public final String from,to,type; public final double weight; Edge(String f,String t,String ty,double w){from=f;to=t;type=ty;weight=w;} }
    public static final class Result { public final int nodes,edges; public final String journal; Result(int n,int e,String j){nodes=n;edges=e;journal=j;} public String summary(){return "nodes="+nodes+" edges="+edges+" journal="+journal;} }
}
