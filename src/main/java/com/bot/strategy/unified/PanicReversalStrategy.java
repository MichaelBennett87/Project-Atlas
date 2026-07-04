package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.technical.TechnicalAnalysis;

public class PanicReversalStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "PANIC_REVERSAL";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        double drop = TechnicalAnalysis.percentDropFromRecentHigh(context.getBars(), 30);
        double bounce = TechnicalAnalysis.bounceFromRecentLow(context.getBars(), 12);
        double rvol = TechnicalAnalysis.relativeVolume(context.getBars(), 20);
        double rsi = TechnicalAnalysis.rsi(context.getBars(), 14);
        double greenRatio = TechnicalAnalysis.greenVolumeRatio(context.getBars(), 8);
        boolean noFreshLow = TechnicalAnalysis.noFreshLow(context.getBars(), 4);
        boolean higherLows = TechnicalAnalysis.higherLows(context.getBars(), 3);
        boolean bullishBreak = TechnicalAnalysis.bullishBreak(context.getBars());
        boolean vwap = TechnicalAnalysis.reclaimedVwap(context.getBars(), 30);

        if (badNewsKeyword(context.newsText())) {
            return hold(context, 0.0, "Blocked panic reversal because news contains catastrophic keyword.");
        }

        double confidence = 0.0;
        confidence += score(drop, 0.05, 0.18) * 0.22;
        confidence += score(rvol, 2.5, 10.0) * 0.18;
        confidence += score(35.0 - rsi, 0.0, 25.0) * 0.10;
        confidence += noFreshLow ? 0.14 : 0.0;
        confidence += higherLows ? 0.14 : 0.0;
        confidence += bullishBreak ? 0.12 : 0.0;
        confidence += vwap ? 0.06 : 0.0;
        confidence += score(greenRatio, 1.1, 3.0) * 0.04;
        confidence = TechnicalAnalysis.clamp(confidence);

        if (drop >= 0.055 && bounce >= 0.012 && noFreshLow && higherLows && (bullishBreak || vwap) && confidence >= 0.70) {
            return buy(
                    context,
                    confidence,
                    Math.max(0.05, bounce + drop * 0.35),
                    String.format("Panic reversal confirmed: drop=%.2f%% bounce=%.2f%% rvol=%.2f rsi=%.2f noFreshLow=%s higherLows=%s bullishBreak=%s vwap=%s",
                            drop * 100.0, bounce * 100.0, rvol, rsi, noFreshLow, higherLows, bullishBreak, vwap)
            );
        }

        return hold(
                context,
                confidence,
                String.format("Panic setup not ready: drop=%.2f%% bounce=%.2f%% confidence=%.2f", drop * 100.0, bounce * 100.0, confidence)
        );
    }

    private double score(double value, double min, double max) {
        if (value <= min) return 0.0;
        if (value >= max) return 1.0;
        return (value - min) / (max - min);
    }
}
