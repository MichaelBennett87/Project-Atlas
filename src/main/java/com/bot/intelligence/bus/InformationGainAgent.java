package com.bot.intelligence.bus;

import com.bot.intelligence.MarketKnowledgeDatabase;

/** Scores how valuable one more research request is likely to be for a symbol. */
public final class InformationGainAgent {
    private static final InformationGainAgent INSTANCE = new InformationGainAgent();
    private InformationGainAgent() {}
    public static InformationGainAgent getInstance() { return INSTANCE; }

    public double score(MarketKnowledgeDatabase.Record r) {
        if (r == null) return 0.50;
        long ageMs = Math.max(0L, System.currentTimeMillis() - r.lastUpdatedMs);
        double stale = Math.min(1.0, ageMs / 600_000.0);
        double missingBars = r.minuteBars <= 5 ? 0.35 : 0.0;
        double missingNews = r.newsCount <= 0 ? 0.15 : 0.0;
        double highActivity = Math.min(1.0, Math.abs(r.returnPct) / 8.0 + r.rangePct / 12.0 + r.microstructureScore);
        double uncertainty = 1.0 - Math.min(1.0, Math.max(r.longScore, r.shortScore));
        return clamp(stale * 0.22 + missingBars + missingNews + highActivity * 0.25 + uncertainty * 0.18);
    }

    private static double clamp(double v) { return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.0; }
}
