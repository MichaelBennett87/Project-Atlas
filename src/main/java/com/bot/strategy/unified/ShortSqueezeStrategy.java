package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.technical.TechnicalAnalysis;

public class ShortSqueezeStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "SHORT_SQUEEZE";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        double rvol = TechnicalAnalysis.relativeVolume(context.getBars(), 20);
        double bounce = TechnicalAnalysis.bounceFromRecentLow(context.getBars(), 20);
        double greenRatio = TechnicalAnalysis.greenVolumeRatio(context.getBars(), 8);
        boolean bullishBreak = TechnicalAnalysis.bullishBreak(context.getBars());
        boolean vwap = TechnicalAnalysis.latestClose(context.getBars()) > TechnicalAnalysis.vwap(context.getBars(), 30);

        String text = context.newsText().toLowerCase();
        double newsBoost = 0.0;
        if (text.contains("short squeeze") || text.contains("heavily shorted")) newsBoost += 0.20;
        if (text.contains("float") || text.contains("low float")) newsBoost += 0.10;
        if (text.contains("borrow") || text.contains("short interest")) newsBoost += 0.10;

        double confidence = 0.0;
        confidence += Math.min(1.0, rvol / 12.0) * 0.30;
        confidence += Math.min(1.0, bounce / 0.12) * 0.20;
        confidence += Math.min(1.0, greenRatio / 3.0) * 0.15;
        confidence += bullishBreak ? 0.15 : 0.0;
        confidence += vwap ? 0.10 : 0.0;
        confidence += newsBoost;
        confidence = TechnicalAnalysis.clamp(confidence);

        if (rvol >= 5.0 && bounce >= 0.025 && greenRatio >= 1.25 && bullishBreak && confidence >= 0.72) {
            return buy(
                    context,
                    confidence,
                    Math.max(0.08, bounce * 1.5),
                    String.format("Possible squeeze: rvol=%.2f bounce=%.2f%% greenVolumeRatio=%.2f bullishBreak=%s vwap=%s",
                            rvol, bounce * 100.0, greenRatio, bullishBreak, vwap)
            );
        }

        return hold(context, confidence, String.format("Short squeeze setup not ready: confidence=%.2f rvol=%.2f", confidence, rvol));
    }
}
