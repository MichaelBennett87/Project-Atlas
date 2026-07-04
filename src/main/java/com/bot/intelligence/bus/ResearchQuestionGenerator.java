package com.bot.intelligence.bus;

import com.bot.intelligence.MarketKnowledgeDatabase;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Generates high-value questions for the REST research planner.
 * This prevents the planner from going idle when symbol files are stale or malformed.
 */
public final class ResearchQuestionGenerator {
    private static final ResearchQuestionGenerator INSTANCE = new ResearchQuestionGenerator();
    private final MarketKnowledgeDatabase db = MarketKnowledgeDatabase.getInstance();
    private final InformationGainAgent infoGain = InformationGainAgent.getInstance();
    private ResearchQuestionGenerator() {}
    public static ResearchQuestionGenerator getInstance() { return INSTANCE; }

    public List<ResearchQuestion> generateQuestions(int max) {
        int limit = Math.max(1, max);
        List<ResearchQuestion> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (MarketKnowledgeDatabase.Record r : db.topByActivity(limit)) {
            addForRecord(out, seen, r);
            if (out.size() >= limit) break;
        }
        for (MarketKnowledgeDatabase.Record r : db.topByMicrostructure(limit)) {
            addForRecord(out, seen, r);
            if (out.size() >= limit) break;
        }
        readSymbolsFromRanker(out, seen, Path.of("logs/market_state_database_2.csv"), limit);
        readSymbolsFromRanker(out, seen, Path.of("logs/state_opportunity_ranker.csv"), limit);
        readSymbolsFromRanker(out, seen, Path.of("logs/market_os_unified_state.csv"), limit);
        readSymbolsFromRanker(out, seen, Path.of("logs/unified_candidate_scores.csv"), limit);
        if (out.isEmpty()) {
            for (String s : env("RESEARCH_QUESTION_DEFAULT_SYMBOLS", "SPY,QQQ,IWM,NVDA,TSLA,AAPL,MSFT,AMZN,META,AMD,RKLB,RGTI,SMR,MARA").split(",")) {
                String t = norm(s);
                if (!t.isBlank()) out.add(new ResearchQuestion(t, "Bootstrap baseline market state", "SNAPSHOT+BARS+NEWS", 0.55, 0.65));
                if (out.size() >= limit) break;
            }
        }
        out.sort(Comparator.comparingDouble(ResearchQuestion::priority).reversed());
        if (boolEnv("RESEARCH_QUESTION_LOG_ENABLED", true)) {
            System.out.println("RESEARCH QUESTION GENERATOR: generated=" + out.size() + " top=" + out.subList(0, Math.min(8, out.size())));
        }
        return out.subList(0, Math.min(limit, out.size()));
    }

    private void addForRecord(List<ResearchQuestion> out, Set<String> seen, MarketKnowledgeDatabase.Record r) {
        if (r == null) return;
        String s = norm(r.ticker);
        if (s.isBlank() || !seen.add(s)) return;
        double ig = infoGain.score(r);
        String hint = r.minuteBars < 10 ? "BARS_1_MIN+SNAPSHOT" : (r.newsCount <= 0 ? "NEWS+SNAPSHOT" : "BARS_1_MIN+ANALOGUE");
        String q = "What current and historical evidence would reduce uncertainty for " + s + "?";
        out.add(new ResearchQuestion(s, q, hint, Math.min(0.95, 0.45 + r.activityScore() / 8.0), ig));
    }

    private void readSymbolsFromRanker(List<ResearchQuestion> out, Set<String> seen, Path path, int limit) {
        if (out.size() >= limit || !Files.exists(path)) return;
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String header = br.readLine();
            int idx = findTickerIndex(header);
            String line;
            while ((line = br.readLine()) != null && out.size() < limit) {
                List<String> cols = parse(line);
                String t = idx >= 0 && idx < cols.size() ? norm(cols.get(idx)) : "";
                if (t.isBlank()) {
                    for (String c : cols) { t = norm(c); if (!t.isBlank()) break; }
                }
                if (!t.isBlank() && seen.add(t)) {
                    out.add(new ResearchQuestion(t, "Research active ranked opportunity " + t, "SNAPSHOT+BARS+NEWS", 0.70, 0.75));
                }
            }
        } catch (Exception ignored) {}
    }

    private static int findTickerIndex(String h) {
        List<String> c = parse(h);
        for (int i = 0; i < c.size(); i++) {
            String x = c.get(i).trim().toLowerCase(Locale.ROOT);
            if (x.equals("ticker") || x.equals("symbol") || x.equals("asset") || x.equals("underlying")) return i;
        }
        if (c.size() > 1 && c.get(0).toLowerCase(Locale.ROOT).contains("time")) return 1;
        return 0;
    }

    private static String norm(String s) {
        if (s == null) return "";
        String t = s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", "");
        if (t.isBlank() || t.equals("TICKER") || t.equals("SYMBOL") || t.equals("NULL") || t.equals("NONE") || t.equals("UNKNOWN")) return "";
        if (t.length() > 8 || t.contains("/") || t.endsWith("USD")) return "";
        if (t.matches(".*\\d{4}-\\d{2}-\\d{2}.*") || t.startsWith("202") || t.contains("T12") || t.endsWith("Z")) return "";
        return t.matches("[A-Z][A-Z0-9.\\-]{0,7}") ? t : "";
    }
    private static List<String> parse(String line){List<String> out=new ArrayList<>(); if(line==null)return out; StringBuilder cur=new StringBuilder(); boolean q=false; for(int i=0;i<line.length();i++){char ch=line.charAt(i); if(q){if(ch=='"'){if(i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;}else q=false;}else cur.append(ch);}else{if(ch==','){out.add(cur.toString());cur.setLength(0);}else if(ch=='"')q=true;else cur.append(ch);}} out.add(cur.toString()); return out;}
    private static String env(String k,String f){String v=System.getenv(k);return v==null||v.isBlank()?f:v.trim();}
    private static boolean boolEnv(String k,boolean f){String v=System.getenv(k);return v==null||v.isBlank()?f:"true".equalsIgnoreCase(v.trim())||"1".equals(v.trim());}
}
