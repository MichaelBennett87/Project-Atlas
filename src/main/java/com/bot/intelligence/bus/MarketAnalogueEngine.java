package com.bot.intelligence.bus;

import com.bot.intelligence.MarketKnowledgeDatabase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds current market states that most resemble a target symbol's feature vector. */
public final class MarketAnalogueEngine {
    private static final MarketAnalogueEngine INSTANCE = new MarketAnalogueEngine();
    private final MarketKnowledgeDatabase db = MarketKnowledgeDatabase.getInstance();
    private MarketAnalogueEngine() {}
    public static MarketAnalogueEngine getInstance() { return INSTANCE; }

    public List<String> nearestSymbols(String symbol, int max) {
        MarketKnowledgeDatabase.Record target = db.snapshot(symbol);
        List<Scored> scored = new ArrayList<>();
        if (target == null) return List.of();
        for (MarketKnowledgeDatabase.Record r : db.topByActivity(1000)) {
            if (r.ticker.equalsIgnoreCase(symbol)) continue;
            double dist = Math.abs(target.returnPct - r.returnPct) * 0.20
                    + Math.abs(target.rangePct - r.rangePct) * 0.18
                    + Math.abs(target.microstructureScore - r.microstructureScore) * 0.30
                    + Math.abs(Math.log10(Math.max(1.0, target.minuteVolume)) - Math.log10(Math.max(1.0, r.minuteVolume))) * 0.18
                    + Math.abs(target.newsCount - r.newsCount) * 0.04;
            scored.add(new Scored(r.ticker, 1.0 / (1.0 + dist)));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        List<String> out = new ArrayList<>();
        for (Scored s : scored) { if (out.size() >= max) break; out.add(s.symbol); }
        ContinuousKnowledgeCache.getInstance().put(symbol, "ANALOGUES", out.toString(), out.isEmpty() ? 0.0 : 0.70);
        return out;
    }
    private static final class Scored { final String symbol; final double score; Scored(String s,double sc){symbol=s;score=sc;} }
}
