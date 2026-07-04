package com.bot.intelligence;

public class ProbabilityPrediction {
    private final double probabilityHitProfitTarget;
    private final double probabilityHitStopLoss;
    private final double expectedValuePercent;
    private final String reason;

    public ProbabilityPrediction(
            double probabilityHitProfitTarget,
            double probabilityHitStopLoss,
            double expectedValuePercent,
            String reason
    ) {
        this.probabilityHitProfitTarget = clamp(probabilityHitProfitTarget);
        this.probabilityHitStopLoss = clamp(probabilityHitStopLoss);
        this.expectedValuePercent = safe(expectedValuePercent);
        this.reason = reason == null ? "" : reason;
    }

    public double getProbabilityHitProfitTarget() { return probabilityHitProfitTarget; }
    public double getProbabilityHitStopLoss() { return probabilityHitStopLoss; }
    public double getExpectedValuePercent() { return expectedValuePercent; }
    public String getReason() { return reason; }

    public double confidence() {
        double probabilityEdge = probabilityHitProfitTarget - probabilityHitStopLoss;
        double evBoost = Math.max(0.0, Math.min(0.25, expectedValuePercent / 20.0));
        return clamp(0.50 + probabilityEdge * 0.65 + evBoost);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double safe(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0.0 : value;
    }

    @Override
    public String toString() {
        return "ProbabilityPrediction{" +
                "pTarget=" + probabilityHitProfitTarget +
                ", pStop=" + probabilityHitStopLoss +
                ", ev=" + expectedValuePercent +
                ", reason='" + reason + '\'' +
                '}';
    }
}
