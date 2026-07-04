package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.technical.TechnicalAnalysis;

public class GapFillStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "GAP_FILL";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        double drop = TechnicalAnalysis.percentDropFromRecentHigh(context.getBars(), 45);
        double bounce = TechnicalAnalysis.bounceFromRecentLow(context.getBars(), 15);
        double rsi = TechnicalAnalysis.rsi(context.getBars(), 14);
        double rvol = TechnicalAnalysis.relativeVolume(context.getBars(), 20);
        boolean morning = TechnicalAnalysis.morningPanic(TechnicalAnalysis.latestTimestamp(context.getBars()));
        boolean noFreshLow = TechnicalAnalysis.noFreshLow(context.getBars(), 5);
        boolean bullishBreak = TechnicalAnalysis.bullishBreak(context.getBars());

        if (badNewsKeyword(context.newsText())) {
            return hold(context, 0.0, "Catastrophic news blocks gap-fill attempt.");
        }

        double confidence = 0.0;
        confidence += morning ? 0.12 : 0.0;
        confidence += Math.min(1.0, drop / 0.14) * 0.22;
        confidence += Math.min(1.0, bounce / 0.05) * 0.16;
        confidence += Math.min(1.0, Math.max(0.0, 35.0 - rsi) / 25.0) * 0.15;
        confidence += Math.min(1.0, rvol / 6.0) * 0.15;
        confidence += noFreshLow ? 0.10 : 0.0;
        confidence += bullishBreak ? 0.10 : 0.0;
        confidence = TechnicalAnalysis.clamp(confidence);

        if (morning && drop >= 0.04 && bounce >= 0.012 && noFreshLow && bullishBreak && confidence >= 0.68) {
            return buy(
                    context,
                    confidence,
                    Math.max(0.045, drop * 0.45),
                    String.format("Morning gap-fill/panic bounce: drop=%.2f%% bounce=%.2f%% rsi=%.2f rvol=%.2f",
                            drop * 100.0, bounce * 100.0, rsi, rvol)
            );
        }

        return hold(context, confidence, String.format("Gap-fill not ready: confidence=%.2f morning=%s drop=%.2f%%", confidence, morning, drop * 100.0));
    }
}
