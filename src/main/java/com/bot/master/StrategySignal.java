package com.bot.master;

import com.bot.model.TradeDirection;

public class StrategySignal {

    private final String strategyName;
    private final String ticker;
    private final StrategyAction action;
    private final TradeDirection direction;
    private final double confidence;
    private final double expectedMovePercent;
    private final int suggestedQuantity;
    private final String reason;
    private final long createdAt;

    private StrategySignal(
            String strategyName,
            String ticker,
            StrategyAction action,
            TradeDirection direction,
            double confidence,
            double expectedMovePercent,
            int suggestedQuantity,
            String reason
    ) {
        this.strategyName = normalize(strategyName, "UNKNOWN_STRATEGY");
        this.ticker = normalize(ticker, "UNKNOWN");
        this.action = action == null ? StrategyAction.HOLD : action;
        this.direction = direction == null ? TradeDirection.NO_TRADE : direction;
        this.confidence = clamp(confidence);
        this.expectedMovePercent = expectedMovePercent;
        this.suggestedQuantity = Math.max(0, suggestedQuantity);
        this.reason = reason == null ? "" : reason;
        this.createdAt = System.currentTimeMillis();
    }

    public static StrategySignal buy(
            String strategyName,
            String ticker,
            TradeDirection direction,
            double confidence,
            double expectedMovePercent,
            int suggestedQuantity,
            String reason
    ) {
        return new StrategySignal(
                strategyName,
                ticker,
                StrategyAction.BUY,
                direction,
                confidence,
                expectedMovePercent,
                suggestedQuantity,
                reason
        );
    }

    public static StrategySignal hold(
            String strategyName,
            String ticker,
            double confidence,
            String reason
    ) {
        return new StrategySignal(
                strategyName,
                ticker,
                StrategyAction.HOLD,
                TradeDirection.NO_TRADE,
                confidence,
                0.0,
                0,
                reason
        );
    }

    public static StrategySignal block(
            String strategyName,
            String ticker,
            String reason
    ) {
        return new StrategySignal(
                strategyName,
                ticker,
                StrategyAction.BLOCK,
                TradeDirection.NO_TRADE,
                0.0,
                0.0,
                0,
                reason
        );
    }

    public double priorityScore() {
        return confidence * Math.max(0.0, expectedMovePercent);
    }

    public boolean isActionableBuy() {
        return action == StrategyAction.BUY && confidence > 0.0 && suggestedQuantity > 0;
    }

    public String getStrategyName() { return strategyName; }
    public String getTicker() { return ticker; }
    public StrategyAction getAction() { return action; }
    public TradeDirection getDirection() { return direction; }
    public double getConfidence() { return confidence; }
    public double getExpectedMovePercent() { return expectedMovePercent; }
    public int getSuggestedQuantity() { return suggestedQuantity; }
    public String getReason() { return reason; }
    public long getCreatedAt() { return createdAt; }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toUpperCase();
    }

    @Override
    public String toString() {
        return "StrategySignal{" +
                "strategyName='" + strategyName + '\'' +
                ", ticker='" + ticker + '\'' +
                ", action=" + action +
                ", direction=" + direction +
                ", confidence=" + confidence +
                ", expectedMovePercent=" + expectedMovePercent +
                ", suggestedQuantity=" + suggestedQuantity +
                ", priorityScore=" + priorityScore() +
                ", reason='" + reason + '\'' +
                '}';
    }
}
