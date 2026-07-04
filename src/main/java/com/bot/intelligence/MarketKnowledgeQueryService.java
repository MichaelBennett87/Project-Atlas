package com.bot.intelligence;

import java.util.List;

/** Local read API for agents. Execution and strategy code should use this before any provider call. */
public final class MarketKnowledgeQueryService {
    private static final MarketKnowledgeQueryService INSTANCE = new MarketKnowledgeQueryService();
    private final MarketKnowledgeDatabase db = MarketKnowledgeDatabase.getInstance();
    private MarketKnowledgeQueryService() {}
    public static MarketKnowledgeQueryService getInstance() { return INSTANCE; }
    public MarketKnowledgeDatabase.Record get(String ticker) { return db.snapshot(ticker); }
    public MarketStateDatabase2.State state(String ticker) { return MarketStateDatabase2.getInstance().snapshot(ticker); }
    public boolean hasFresh(String ticker, long maxAgeMs) {
        MarketKnowledgeDatabase.Record r = get(ticker);
        return r != null && r.price > 0.0 && System.currentTimeMillis() - r.lastUpdatedMs <= maxAgeMs;
    }
    public boolean hasFreshState(String ticker, long maxAgeMs) {
        MarketStateDatabase2.State s = state(ticker);
        return s != null && s.price > 0.0 && System.currentTimeMillis() - s.timestampMs <= maxAgeMs;
    }
    public double latestPrice(String ticker) { MarketKnowledgeDatabase.Record r = get(ticker); return r == null ? 0.0 : r.price; }
    public double opportunityScore(String ticker) { MarketStateDatabase2.State s = state(ticker); return s == null ? 0.0 : s.opportunityScore; }
    public String preferredDirection(String ticker) { MarketStateDatabase2.State s = state(ticker); return s == null ? "UNKNOWN" : s.direction; }
    public List<MarketKnowledgeDatabase.Record> topActivity(int max) { return db.topByActivity(max); }
    public List<MarketKnowledgeDatabase.Record> topMicrostructure(int max) { return db.topByMicrostructure(max); }
    public List<MarketStateDatabase2.State> topOpportunities(int max) { return MarketStateDatabase2.getInstance().topOpportunities(max); }
    public List<MarketStateDatabase2.State> topLongs(int max) { return MarketStateDatabase2.getInstance().topLongs(max); }
    public List<MarketStateDatabase2.State> topShorts(int max) { return MarketStateDatabase2.getInstance().topShorts(max); }

    /** Single local read for agents: all currently maintained knowledge/state for a ticker. */
    public String describeEverythingKnown(String ticker) {
        MarketKnowledgeDatabase.Record r = get(ticker);
        MarketStateDatabase2.State s = state(ticker);
        if (r == null && s == null) return "No local market knowledge for " + ticker;
        StringBuilder b = new StringBuilder();
        b.append("ticker=").append(ticker == null ? "" : ticker.toUpperCase());
        if (r != null) {
            b.append(" price=").append(r.price)
             .append(" snapshotVolume=").append(r.snapshotVolume)
             .append(" minuteVolume=").append(r.minuteVolume)
             .append(" returnPct=").append(r.returnPct)
             .append(" rangePct=").append(r.rangePct)
             .append(" newsCount=").append(r.newsCount)
             .append(" microstructure=").append(r.microstructureScore)
             .append(" tradeCount=").append(r.tradeCount)
             .append(" quoteCount=").append(r.quoteCount)
             .append(" source=").append(r.lastSource);
        }
        if (s != null) {
            b.append(" stateDirection=").append(s.direction)
             .append(" opportunity=").append(s.opportunityScore)
             .append(" longScore=").append(s.longScore)
             .append(" shortScore=").append(s.shortScore)
             .append(" risk=").append(s.riskScore)
             .append(" technical=").append(s.technicalScore)
             .append(" orderFlow=").append(s.orderFlowScore);
        }
        return b.toString();
    }
}
