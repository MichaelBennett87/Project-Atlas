package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

public class FdaApprovalMomentumStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "FDA_APPROVAL_MOMENTUM";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        List<Bar> bars = context.getBars();

        if (!context.hasNews()) return hold(context, 0.0, "No FDA/clinical catalyst.");
        String text = UnifiedStrategyUtil.lowerNews(context.newsText());
        boolean fda = text.contains("fda approval") || text.contains("fda approves") || text.contains("approved by the fda") || text.contains("notice of allowance") || text.contains("phase 3") || text.contains("new drug application");
        boolean negative = text.contains("clinical hold") || text.contains("rejection") || text.contains("complete response letter");
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        boolean priceConfirm = TechnicalAnalysis.bullishBreak(bars) || UnifiedStrategyUtil.risingCloses(bars, 2);
        double confidence = TechnicalAnalysis.clamp((fda ? 0.32 : 0.0) + (!negative ? 0.08 : -0.20) + (priceConfirm ? 0.20 : 0.0) + Math.min(1.0, rvol / 5.0) * 0.26 + (context.getLastPrice() < 25 ? 0.14 : 0.06));
        if (fda && !negative && priceConfirm && rvol >= 1.2 && confidence >= 0.70) return buy(context, confidence, 0.06 + Math.min(0.07, rvol / 100.0), String.format("FDA/clinical momentum: rvol=%.2f priceConfirm=%s", rvol, priceConfirm));
        return hold(context, confidence, String.format("FDA momentum not ready: confidence=%.2f fda=%s negative=%s", confidence, fda, negative));
    }
}
