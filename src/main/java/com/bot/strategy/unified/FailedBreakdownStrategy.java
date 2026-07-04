package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.technical.TechnicalAnalysis;

public class FailedBreakdownStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "FAILED_BREAKDOWN";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        boolean failedBreakdown = TechnicalAnalysis.failedBreakdown(context.getBars());
        boolean higherLows = TechnicalAnalysis.higherLows(context.getBars(), 2);
        boolean vwap = TechnicalAnalysis.latestClose(context.getBars()) > TechnicalAnalysis.vwap(context.getBars(), 30);
        double rvol = TechnicalAnalysis.relativeVolume(context.getBars(), 20);
        double greenRatio = TechnicalAnalysis.greenVolumeRatio(context.getBars(), 6);
        double bounce = TechnicalAnalysis.bounceFromRecentLow(context.getBars(), 10);

        double confidence = 0.0;
        confidence += failedBreakdown ? 0.35 : 0.0;
        confidence += higherLows ? 0.15 : 0.0;
        confidence += vwap ? 0.10 : 0.0;
        confidence += Math.min(1.0, rvol / 5.0) * 0.16;
        confidence += Math.min(1.0, greenRatio / 2.5) * 0.12;
        confidence += Math.min(1.0, bounce / 0.05) * 0.12;
        confidence = TechnicalAnalysis.clamp(confidence);

        if (failedBreakdown && higherLows && rvol >= 1.5 && confidence >= 0.70) {
            return buy(
                    context,
                    confidence,
                    Math.max(0.045, bounce * 1.25),
                    String.format("Failed breakdown trap: bounce=%.2f%% rvol=%.2f greenRatio=%.2f vwap=%s",
                            bounce * 100.0, rvol, greenRatio, vwap)
            );
        }

        return hold(context, confidence, String.format("Failed-breakdown not ready: confidence=%.2f failedBreakdown=%s", confidence, failedBreakdown));
    }
}
