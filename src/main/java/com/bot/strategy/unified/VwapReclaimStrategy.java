package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.technical.TechnicalAnalysis;

public class VwapReclaimStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "VWAP_RECLAIM";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        double vwap = TechnicalAnalysis.vwap(context.getBars(), 40);
        double latest = TechnicalAnalysis.latestClose(context.getBars());
        double rvol = TechnicalAnalysis.relativeVolume(context.getBars(), 20);
        double greenRatio = TechnicalAnalysis.greenVolumeRatio(context.getBars(), 8);
        boolean reclaimed = TechnicalAnalysis.reclaimedVwap(context.getBars(), 40);
        boolean higherLows = TechnicalAnalysis.higherLows(context.getBars(), 3);
        boolean bullishBreak = TechnicalAnalysis.bullishBreak(context.getBars());

        double distance = vwap <= 0 || latest <= 0 ? 0.0 : (latest - vwap) / vwap;
        double confidence = 0.0;
        confidence += reclaimed ? 0.26 : 0.0;
        confidence += higherLows ? 0.18 : 0.0;
        confidence += bullishBreak ? 0.16 : 0.0;
        confidence += Math.min(1.0, rvol / 5.0) * 0.18;
        confidence += Math.min(1.0, greenRatio / 2.5) * 0.12;
        confidence += distance > 0 && distance < 0.035 ? 0.10 : 0.0;
        confidence = TechnicalAnalysis.clamp(confidence);

        if (reclaimed && higherLows && (bullishBreak || greenRatio >= 1.5) && rvol >= 1.5 && confidence >= 0.70) {
            return buy(
                    context,
                    confidence,
                    0.04 + Math.min(0.05, rvol / 200.0),
                    String.format("VWAP reclaim confirmed: latest=%.4f vwap=%.4f distance=%.2f%% rvol=%.2f greenRatio=%.2f",
                            latest, vwap, distance * 100.0, rvol, greenRatio)
            );
        }

        return hold(context, confidence, String.format("VWAP reclaim not ready: confidence=%.2f reclaimed=%s", confidence, reclaimed));
    }
}
