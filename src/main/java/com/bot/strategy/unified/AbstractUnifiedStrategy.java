package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.master.TradingStrategy;
import com.bot.scalping.VolumeFirstScalpingPolicy;
import com.bot.model.TradeDirection;
import com.bot.technical.TechnicalAnalysis;

public abstract class AbstractUnifiedStrategy implements TradingStrategy {

    protected StrategySignal buy(
            StrategyContext context,
            double confidence,
            double expectedMovePercent,
            String reason
    ) {
        int quantity = suggestedQuantity(context, confidence);
        return StrategySignal.buy(
                name(),
                context.getTicker(),
                TradeDirection.LONG_STOCK,
                confidence,
                expectedMovePercent,
                quantity,
                reason
        );
    }

    protected StrategySignal hold(
            StrategyContext context,
            double confidence,
            String reason
    ) {
        return StrategySignal.hold(name(), context.getTicker(), confidence, reason);
    }

    protected int suggestedQuantity(StrategyContext context, double confidence) {
        double price = context.getLastPrice();
        double equity = context.getAccountEquity();
        if (price <= 0 || equity <= 0 || confidence < 0.60) {
            return 0;
        }

        double atrPercent = TechnicalAnalysis.atrPercent(context.getBars(), 14);
        VolumeFirstScalpingPolicy.ScalpingTape tape = VolumeFirstScalpingPolicy.tape(context);
        double baseRisk = equity * envDouble("VOLUME_FIRST_BASE_RISK_FRACTION", 0.0065);
        double confidenceMultiplier = 0.35 + confidence;
        double volatilityPenalty = atrPercent > 0 ? Math.min(1.0, 0.050 / atrPercent) : 1.0;
        double volumeMultiplier = 0.80 + Math.min(1.85, tape.violentScore * 1.20 + Math.log10(Math.max(1.0, tape.dollarVolume)) / 8.0);
        if (tape.topVolume) volumeMultiplier += 0.45;
        double dollars = baseRisk * confidenceMultiplier * volatilityPenalty * volumeMultiplier;

        double maxExposure = equity * envDouble("VOLUME_FIRST_MAX_EXPOSURE_FRACTION", 0.085);
        dollars = Math.max(Math.min(dollars, maxExposure), 25.0);
        return Math.max(1, (int) Math.floor(dollars / price));
    }


    protected StrategySignal shortSell(
            StrategyContext context,
            double confidence,
            double expectedMovePercent,
            String reason
    ) {
        int quantity = suggestedShortQuantity(context, confidence);
        return StrategySignal.buy(
                name(),
                context.getTicker(),
                TradeDirection.SHORT_STOCK,
                confidence,
                expectedMovePercent,
                quantity,
                reason
        );
    }

    protected int suggestedShortQuantity(StrategyContext context, double confidence) {
        double price = context.getLastPrice();
        double equity = context.getAccountEquity();
        if (price <= 0 || equity <= 0 || confidence < 0.66) {
            return 0;
        }

        double atrPercent = TechnicalAnalysis.atrPercent(context.getBars(), 14);
        VolumeFirstScalpingPolicy.ScalpingTape tape = VolumeFirstScalpingPolicy.tape(context);
        double baseRisk = equity * envDouble("SHORT_ALPHA_BASE_RISK_FRACTION", 0.010);
        double convictionMultiplier = Math.max(0.70, Math.min(3.00, 0.50 + (confidence * 2.25)));
        double volatilityPenalty = atrPercent > 0 ? Math.min(1.0, 0.060 / atrPercent) : 1.0;
        double volumeMultiplier = 0.85 + Math.min(1.75, tape.violentScore * 1.15 + Math.log10(Math.max(1.0, tape.dollarVolume)) / 8.5);
        if (tape.topVolume) volumeMultiplier += 0.35;
        double maxExposureFraction = envDouble("SHORT_ALPHA_MAX_EXPOSURE_FRACTION", 0.22);
        double dollars = baseRisk * convictionMultiplier * volatilityPenalty * volumeMultiplier;

        dollars = Math.max(Math.min(dollars, equity * maxExposureFraction), 50.0);
        return Math.max(1, (int) Math.floor(dollars / price));
    }

    protected static double envDouble(String name, double fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    protected boolean badNewsKeyword(String text) {
        String value = text == null ? "" : text.toLowerCase();
        return value.contains("bankruptcy")
                || value.contains("chapter 11")
                || value.contains("delisting")
                || value.contains("going concern")
                || value.contains("fraud")
                || value.contains("sec investigation")
                || value.contains("fda rejection")
                || value.contains("clinical hold");
    }
}
