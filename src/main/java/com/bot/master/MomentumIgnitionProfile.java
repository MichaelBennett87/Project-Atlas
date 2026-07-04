package com.bot.master;

import com.bot.model.Bar;
import com.bot.model.TradeDirection;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;
import java.util.Locale;

/**
 * Execution-facing momentum definition.
 *
 * This is intentionally stricter than a normal technical setup score. It answers one question:
 * is this ticker actively exploding right now with volume + volatility + directional expansion?
 * News, AI predictions, state ranking, and historical analogues can add context, but they cannot
 * replace this live tape proof.
 */
public final class MomentumIgnitionProfile {
    private final boolean longSide;
    private final int barCount;
    private final double rvol;
    private final long latestVolume;
    private final double recentReturnPct;
    private final double shortReturnPct;
    private final double rangePct;
    private final double atrPct;
    private final double accelerationPct;
    private final double volumeTrend;
    private final double directionScore;
    private final double vwapDistancePct;
    private final boolean aboveVwap;
    private final boolean belowVwap;
    private final boolean breakHigh;
    private final boolean breakLow;
    private final double score;
    private final String reason;

    private MomentumIgnitionProfile(
            boolean longSide,
            int barCount,
            double rvol,
            long latestVolume,
            double recentReturnPct,
            double shortReturnPct,
            double rangePct,
            double atrPct,
            double accelerationPct,
            double volumeTrend,
            double directionScore,
            double vwapDistancePct,
            boolean aboveVwap,
            boolean belowVwap,
            boolean breakHigh,
            boolean breakLow,
            double score,
            String reason
    ) {
        this.longSide = longSide;
        this.barCount = barCount;
        this.rvol = clean(rvol);
        this.latestVolume = Math.max(0L, latestVolume);
        this.recentReturnPct = clean(recentReturnPct);
        this.shortReturnPct = clean(shortReturnPct);
        this.rangePct = clean(rangePct);
        this.atrPct = clean(atrPct);
        this.accelerationPct = clean(accelerationPct);
        this.volumeTrend = clean(volumeTrend);
        this.directionScore = clean(directionScore);
        this.vwapDistancePct = clean(vwapDistancePct);
        this.aboveVwap = aboveVwap;
        this.belowVwap = belowVwap;
        this.breakHigh = breakHigh;
        this.breakLow = breakLow;
        this.score = clamp(score);
        this.reason = reason == null ? "" : reason;
    }

    public static MomentumIgnitionProfile from(StrategyContext context, StrategySignal signal) {
        boolean longSide = signal == null || signal.getDirection() != TradeDirection.SHORT_STOCK;
        List<Bar> bars = context == null ? null : context.getBars();
        int count = bars == null ? 0 : bars.size();
        if (bars == null || bars.size() < 2) {
            return new MomentumIgnitionProfile(longSide, count, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    false, false, false, false, 0, "not enough bars");
        }

        double rvol = TechnicalAnalysis.relativeVolume(bars, intEnv("MOMENTUM_IGNITION_RVOL_LOOKBACK", 20));
        long latestVolume = Math.max(0L, bars.get(bars.size() - 1).volume);
        double recentReturn = pctChange(bars, intEnv("MOMENTUM_IGNITION_RETURN_BARS", 5));
        double shortReturn = pctChange(bars, intEnv("MOMENTUM_IGNITION_FAST_RETURN_BARS", 2));
        double range = rangePct(bars, intEnv("MOMENTUM_IGNITION_RANGE_BARS", 10));
        double atrPct = TechnicalAnalysis.atrPercent(bars, Math.min(intEnv("MOMENTUM_IGNITION_ATR_PERIOD", 8), Math.max(2, bars.size() - 1))) * 100.0;
        double accel = accelerationPct(bars);
        double volumeTrend = volumeTrend(bars, Math.min(6, bars.size() - 1));
        double vwap = TechnicalAnalysis.vwap(bars, Math.min(30, bars.size()));
        double latestClose = TechnicalAnalysis.latestClose(bars);
        boolean aboveVwap = vwap > 0.0 && latestClose > vwap;
        boolean belowVwap = vwap > 0.0 && latestClose < vwap;
        double vwapDistance = vwap > 0.0 && latestClose > 0.0 ? ((latestClose - vwap) / vwap) * 100.0 : 0.0;
        boolean breakHigh = latestClose > highestHigh(bars, Math.min(8, Math.max(2, bars.size() - 1)), 1);
        boolean breakLow = latestClose < lowestLow(bars, Math.min(8, Math.max(2, bars.size() - 1)), 1);

        double directionalReturn = longSide ? recentReturn : -recentReturn;
        double fastDirectional = longSide ? shortReturn : -shortReturn;
        double directionalAcceleration = longSide ? accel : -accel;
        double directionScore = clamp01(directionalReturn / 6.0 * 0.45
                + fastDirectional / 2.5 * 0.20
                + directionalAcceleration / 2.0 * 0.15
                + (longSide ? (aboveVwap ? 0.12 : 0.0) : (belowVwap ? 0.12 : 0.0))
                + (longSide ? (breakHigh ? 0.18 : 0.0) : (breakLow ? 0.18 : 0.0)));

        double volumeScore = clamp01(rvol / doubleEnv("MOMENTUM_IGNITION_RVOL_SCORE_AT", 6.0) * 0.70
                + logScale(latestVolume, doubleEnv("MOMENTUM_IGNITION_VOLUME_SCORE_AT", 250_000.0)) * 0.20
                + clamp01((volumeTrend - 1.0) / 1.5) * 0.10);
        double volatilityScore = clamp01(Math.abs(recentReturn) / 6.0 * 0.35
                + range / 8.0 * 0.35
                + atrPct / 2.25 * 0.30);
        double accelerationScore = clamp01(Math.max(0.0, directionalAcceleration) / 2.5 * 0.45
                + Math.max(0.0, fastDirectional) / 2.0 * 0.35
                + (longSide ? (breakHigh ? 0.20 : 0.0) : (breakLow ? 0.20 : 0.0)));

        // Volume and volatility dominate. Catalyst quality is intentionally not here.
        double score = volumeScore * 0.40 + volatilityScore * 0.30 + directionScore * 0.20 + accelerationScore * 0.10;
        String reason = String.format(Locale.US,
                "momentumScore=%.3f rvol=%.2f latestVolume=%d return5=%.2f%% range=%.2f%% atr=%.2f%% accel=%.2f%% volumeTrend=%.2f directionScore=%.2f vwapDist=%.2f%% breakHigh=%s breakLow=%s side=%s",
                score, rvol, latestVolume, recentReturn, range, atrPct, accel, volumeTrend, directionScore, vwapDistance,
                breakHigh, breakLow, longSide ? "LONG" : "SHORT");

        return new MomentumIgnitionProfile(longSide, count, rvol, latestVolume, recentReturn, shortReturn, range, atrPct,
                accel, volumeTrend, directionScore, vwapDistance, aboveVwap, belowVwap, breakHigh, breakLow, score, reason);
    }

    public boolean passesHardGate() {
        if (barCount < intEnv("MOMENTUM_IGNITION_MIN_BARS", 10)) return false;
        if (rvol < doubleEnv("MOMENTUM_IGNITION_MIN_RVOL", 5.00)) return false;
        if (latestVolume < (long) doubleEnv("MOMENTUM_IGNITION_MIN_LATEST_BAR_VOLUME", 25_000.0)) return false;
        if (rangePct < doubleEnv("MOMENTUM_IGNITION_MIN_RANGE_PCT", 4.00)
                && atrPct < doubleEnv("MOMENTUM_IGNITION_MIN_ATR_PCT", 1.50)) return false;
        double directionalReturn = longSide ? recentReturnPct : -recentReturnPct;
        double fastDirectional = longSide ? shortReturnPct : -shortReturnPct;
        if (directionalReturn < doubleEnv("MOMENTUM_IGNITION_MIN_DIRECTIONAL_RETURN_PCT", 1.75)
                && fastDirectional < doubleEnv("MOMENTUM_IGNITION_MIN_FAST_DIRECTIONAL_RETURN_PCT", 0.75)) return false;
        if (directionScore < doubleEnv("MOMENTUM_IGNITION_MIN_DIRECTION_SCORE", 0.50)) return false;
        if (isLikelyExhausted()) return false;
        if (score < doubleEnv("MOMENTUM_IGNITION_MIN_SCORE", 0.82)) return false;
        return true;
    }


    public boolean isLikelyExhausted() {
        double directionalReturn = longSide ? recentReturnPct : -recentReturnPct;
        double fastDirectional = longSide ? shortReturnPct : -shortReturnPct;
        double maxDirectionalReturn = doubleEnv("MOMENTUM_IGNITION_NO_CHASE_MAX_RETURN_PCT", 18.0);
        double maxVwapDistance = doubleEnv("MOMENTUM_IGNITION_NO_CHASE_MAX_VWAP_DISTANCE_PCT", 9.0);
        double minFastContinuation = doubleEnv("MOMENTUM_IGNITION_NO_CHASE_MIN_FAST_CONTINUATION_PCT", -0.10);
        if (directionalReturn > maxDirectionalReturn && fastDirectional < minFastContinuation) return true;
        if (Math.abs(vwapDistancePct) > maxVwapDistance && fastDirectional < 0.15) return true;
        if (volumeTrend < doubleEnv("MOMENTUM_IGNITION_NO_CHASE_MIN_VOLUME_TREND", 0.70)
                && directionalReturn > doubleEnv("MOMENTUM_IGNITION_NO_CHASE_MIN_PRIOR_RETURN_PCT", 8.0)) return true;
        return false;
    }

    public String diagnosticReport(String ticker, String strategyName) {
        String symbol = ticker == null || ticker.isBlank() ? "UNKNOWN" : ticker.trim().toUpperCase(Locale.ROOT);
        String strategy = strategyName == null || strategyName.isBlank() ? "UNKNOWN" : strategyName.trim();
        double directionalReturn = longSide ? recentReturnPct : -recentReturnPct;
        double fastDirectional = longSide ? shortReturnPct : -shortReturnPct;
        return String.format(Locale.US,
                "ENTRY MOMENTUM CHECK: ticker=%s strategy=%s side=%s score=%.3f required=%.3f rvol=%.2f required=%.2f latestVolume=%d required=%d return=%.2f%% required=%.2f%% fastReturn=%.2f%% required=%.2f%% range=%.2f%% required=%.2f%% atr=%.2f%% required=%.2f%% directionScore=%.2f required=%.2f exhausted=%s reason=%s",
                symbol, strategy, longSide ? "LONG" : "SHORT", score, doubleEnv("MOMENTUM_IGNITION_MIN_SCORE", 0.82),
                rvol, doubleEnv("MOMENTUM_IGNITION_MIN_RVOL", 5.00), latestVolume, (long) doubleEnv("MOMENTUM_IGNITION_MIN_LATEST_BAR_VOLUME", 25_000.0),
                directionalReturn, doubleEnv("MOMENTUM_IGNITION_MIN_DIRECTIONAL_RETURN_PCT", 1.75),
                fastDirectional, doubleEnv("MOMENTUM_IGNITION_MIN_FAST_DIRECTIONAL_RETURN_PCT", 0.75),
                rangePct, doubleEnv("MOMENTUM_IGNITION_MIN_RANGE_PCT", 4.00), atrPct, doubleEnv("MOMENTUM_IGNITION_MIN_ATR_PCT", 1.50),
                directionScore, doubleEnv("MOMENTUM_IGNITION_MIN_DIRECTION_SCORE", 0.50), isLikelyExhausted(), reason);
    }

    public double priorityMultiplier() {
        if (score <= 0.0) return 0.20;
        return Math.max(0.25, Math.min(2.50, 0.30 + score * 2.20));
    }

    public double getScore() { return score; }
    public double getRvol() { return rvol; }
    public long getLatestVolume() { return latestVolume; }
    public double getRecentReturnPct() { return recentReturnPct; }
    public double getRangePct() { return rangePct; }
    public double getAtrPct() { return atrPct; }
    public double getDirectionScore() { return directionScore; }
    public String getReason() { return reason; }

    private static double pctChange(List<Bar> bars, int barsBack) {
        if (bars == null || bars.size() < 2) return 0.0;
        int index = Math.max(0, bars.size() - 1 - Math.max(1, barsBack));
        double oldClose = bars.get(index).close;
        double latestClose = bars.get(bars.size() - 1).close;
        if (oldClose <= 0.0 || latestClose <= 0.0) return 0.0;
        return ((latestClose - oldClose) / oldClose) * 100.0;
    }

    private static double rangePct(List<Bar> bars, int lookback) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int start = Math.max(0, bars.size() - Math.max(1, lookback));
        double high = 0.0;
        double low = Double.MAX_VALUE;
        for (int i = start; i < bars.size(); i++) {
            Bar b = bars.get(i);
            if (b == null) continue;
            if (b.high > 0.0) high = Math.max(high, b.high);
            if (b.low > 0.0) low = Math.min(low, b.low);
        }
        if (high <= 0.0 || low <= 0.0 || low == Double.MAX_VALUE) return 0.0;
        return ((high - low) / low) * 100.0;
    }

    private static double accelerationPct(List<Bar> bars) {
        if (bars == null || bars.size() < 7) return 0.0;
        double fast = pctChange(bars, 2);
        int priorEnd = bars.size() - 3;
        int priorStart = Math.max(0, priorEnd - 2);
        double old = bars.get(priorStart).close;
        double newer = bars.get(priorEnd).close;
        double prior = old <= 0.0 || newer <= 0.0 ? 0.0 : ((newer - old) / old) * 100.0;
        return fast - prior;
    }

    private static double volumeTrend(List<Bar> bars, int count) {
        if (bars == null || bars.size() < 4) return 0.0;
        int n = Math.min(Math.max(2, count), bars.size());
        int start = bars.size() - n;
        double firstHalf = 0.0;
        double secondHalf = 0.0;
        int firstCount = 0;
        int secondCount = 0;
        for (int i = start; i < bars.size(); i++) {
            if (i < start + n / 2) { firstHalf += Math.max(0L, bars.get(i).volume); firstCount++; }
            else { secondHalf += Math.max(0L, bars.get(i).volume); secondCount++; }
        }
        if (firstCount == 0 || secondCount == 0 || firstHalf <= 0.0) return 0.0;
        return (secondHalf / secondCount) / (firstHalf / firstCount);
    }

    private static double highestHigh(List<Bar> bars, int lookback, int skipLatest) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int end = Math.max(0, bars.size() - Math.max(0, skipLatest));
        int start = Math.max(0, end - Math.max(1, lookback));
        double high = 0.0;
        for (int i = start; i < end; i++) high = Math.max(high, bars.get(i).high);
        return high;
    }

    private static double lowestLow(List<Bar> bars, int lookback, int skipLatest) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int end = Math.max(0, bars.size() - Math.max(0, skipLatest));
        int start = Math.max(0, end - Math.max(1, lookback));
        double low = Double.MAX_VALUE;
        for (int i = start; i < end; i++) low = Math.min(low, bars.get(i).low);
        return low == Double.MAX_VALUE ? 0.0 : low;
    }

    private static double logScale(double value, double target) {
        if (value <= 0.0 || target <= 1.0) return 0.0;
        return clamp01(Math.log10(value + 1.0) / Math.log10(target + 1.0));
    }

    private static double clean(double value) { return Double.isFinite(value) ? value : 0.0; }
    private static double clamp(double value) { return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0; }
    private static double clamp01(double value) { return clamp(value); }

    private static int intEnv(String k, int fallback) {
        try { String v = System.getenv(k); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static double doubleEnv(String k, double fallback) {
        try { String v = System.getenv(k); return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); }
        catch (Exception e) { return fallback; }
    }
}
