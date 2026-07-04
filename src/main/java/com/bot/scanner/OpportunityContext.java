package com.bot.scanner;

import com.bot.model.Bar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Canonical per-symbol opportunity state. This is the object subsystems should
 * share instead of creating separate scanner/news/staging technical state.
 */
public final class OpportunityContext {
    private final String ticker;
    private long createdAt = System.currentTimeMillis();
    private long lastUpdatedAt = createdAt;
    private String source = "";
    private String headline = "";
    private double catalystScore;
    private double predictiveScore;
    private MomentumCandidateTracker.LifecycleStage lifecycleStage = MomentumCandidateTracker.LifecycleStage.DISCOVERED;

    OpportunityContext(String ticker) {
        this.ticker = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    public String ticker() { return ticker; }
    public long createdAt() { return createdAt; }
    public long lastUpdatedAt() { return lastUpdatedAt; }
    public String source() { return source; }
    public String headline() { return headline; }
    public double catalystScore() { return catalystScore; }
    public double predictiveScore() { return predictiveScore; }
    public MomentumCandidateTracker.LifecycleStage lifecycleStage() { return lifecycleStage; }

    public void markSource(String source, String headline) {
        this.source = source == null ? this.source : source;
        this.headline = headline == null ? this.headline : headline;
        this.lastUpdatedAt = System.currentTimeMillis();
    }

    public void markCatalyst(double catalystScore, double predictiveScore, boolean highPriority) {
        this.catalystScore = Math.max(this.catalystScore, catalystScore);
        this.predictiveScore = Math.max(this.predictiveScore, predictiveScore);
        this.lastUpdatedAt = System.currentTimeMillis();
        if (highPriority && this.lifecycleStage == MomentumCandidateTracker.LifecycleStage.DISCOVERED) {
            this.lifecycleStage = MomentumCandidateTracker.LifecycleStage.OBSERVED;
        }
    }

    public void markLifecycle(MomentumCandidateTracker.LifecycleStage stage) {
        if (stage != null) {
            this.lifecycleStage = stage;
            this.lastUpdatedAt = System.currentTimeMillis();
        }
    }

    public List<Bar> bars(int limit) {
        return SharedRollingBarHistoryService.getInstance().recent(ticker, limit);
    }

    public TechnicalFeatureSnapshot features() {
        return TechnicalFeatureService.getInstance().snapshot(ticker);
    }
}
