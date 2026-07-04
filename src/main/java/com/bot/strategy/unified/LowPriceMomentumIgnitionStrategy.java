package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class LowPriceMomentumIgnitionStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "LOW_PRICE_MOMENTUM_IGNITION";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!UnifiedStrategyUtil.hasEnoughBars(bars, 6)) return hold(context, 0.0, "Low-price momentum waiting for bars.");
        double price = context.getLastPrice();
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        double change3 = UnifiedStrategyUtil.pctChange(bars, 3);
        double greenRatio = TechnicalAnalysis.greenVolumeRatio(bars, 5);
        boolean cheap = price >= 0.50 && price <= 12.00;
        boolean breakHigh = price > UnifiedStrategyUtil.highestHigh(bars, 8, 1);
        double confidence = TechnicalAnalysis.clamp((cheap ? 0.15 : 0.0) + Math.min(1.0, rvol / 8.0) * 0.30 + Math.min(1.0, change3 / 0.10) * 0.25 + Math.min(1.0, greenRatio / 3.0) * 0.15 + (breakHigh ? 0.15 : 0.0));
        if (cheap && rvol >= 2.0 && change3 >= 0.025 && breakHigh && confidence >= 0.70) return buy(context, confidence, Math.max(0.06, change3 * 0.8), String.format("Low-price momentum ignition: price=%.4f chg3=%.2f%% rvol=%.2f", price, change3 * 100.0, rvol));
        return hold(context, confidence, String.format("Low-price ignition not ready: confidence=%.2f price=%.2f rvol=%.2f", confidence, price, rvol));
    }
}
