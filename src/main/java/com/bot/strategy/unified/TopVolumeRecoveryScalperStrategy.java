package com.bot.strategy.unified;

import com.bot.intelligence.ParabolicTopVolumeTracker;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.scanner.SharedRollingBarHistoryService;
import com.bot.scanner.TechnicalFeatureService;
import com.bot.scanner.TechnicalFeatureSnapshot;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

/**
 * Aggressive high-volume intraday scalper.
 *
 * This strategy is intentionally built around the behavior Michael described:
 * stay focused on the highest-volume / most violent movers, wait for a dip and
 * recovery for longs, or wait for an apex/failure for shorts. It is allowed to
 * re-signal the same ticker repeatedly after exits because it does not use a
 * story/news one-and-done model.
 */
public class TopVolumeRecoveryScalperStrategy extends AbstractUnifiedStrategy {

    private static final String STRATEGY_NAME = "TOP_VOLUME_RECOVERY_SCALPER";

    private final ParabolicTopVolumeTracker topVolumeTracker = ParabolicTopVolumeTracker.getInstance();
    private final SharedRollingBarHistoryService sharedBars = SharedRollingBarHistoryService.getInstance();
    private final TechnicalFeatureService featureService = TechnicalFeatureService.getInstance();

    private final int topRank = Math.max(5, envInt("TOP_VOLUME_SCALPER_TOP_RANK", 100));
    private final int minBars = Math.max(1, envInt("TOP_VOLUME_SCALPER_MIN_BARS", 1));
    private final double minDollarVolume = envDouble("TOP_VOLUME_SCALPER_MIN_DOLLAR_VOLUME", 50_000.0);
    private final double minAbsVelocity = envDouble("TOP_VOLUME_SCALPER_MIN_ABS_VELOCITY_PCT", 0.025);
    private final double minRange = envDouble("TOP_VOLUME_SCALPER_MIN_RANGE_PCT", 0.035);
    private final double minRecovery = envDouble("TOP_VOLUME_SCALPER_MIN_RECOVERY_PCT", 0.025);
    private final double minPullback = envDouble("TOP_VOLUME_SCALPER_MIN_PULLBACK_PCT", 0.035);
    private final double minConfidence = envDouble("TOP_VOLUME_SCALPER_MIN_CONFIDENCE", 0.58);

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        if (context == null || context.getTicker() == null || context.getTicker().isBlank()) {
            return StrategySignal.hold(STRATEGY_NAME, "UNKNOWN", 0.0, "No context supplied.");
        }

        String ticker = context.getTicker();
        List<Bar> tape = sharedBars.recent(ticker, 30);
        if (tape.size() < minBars) {
            // Fall back to the normal strategy-context bars if the shared tape is
            // still warming up, but the shared tape is preferred because it has
            // intrabar snapshots from the scanner/observation scheduler.
            tape = context.getBars();
        }
        if (tape == null || tape.size() < minBars) {
            return hold(context, 0.0, "Top-volume scalper warming up: bars=" + (tape == null ? 0 : tape.size()) + "/" + minBars);
        }

        TechnicalFeatureSnapshot f = featureService.snapshot(ticker);
        Bar latest = tape.get(tape.size() - 1);
        if (latest == null || latest.close <= 0.0) {
            return hold(context, 0.0, "No usable latest price for top-volume scalper.");
        }

        boolean topVolume = topVolumeTracker.isTopVolumeTicker(ticker, topRank);
        double dollarVolume = Math.max(f.dollarVolume, topVolumeTracker.latestDollarVolume(ticker));
        double absVelocity = Math.max(Math.abs(f.oneBarVelocityPct), Math.abs(f.threeBarVelocityPct));
        double range = f.rangePct;
        if (range <= 0.0) {
            range = recentRangePct(tape, Math.min(12, tape.size()));
        }

        if (!topVolume && dollarVolume < minDollarVolume * 1.20) {
            return hold(context, 0.0, "Not yet in the liquid/top-volume scalp pool. " + topVolumeTracker.describe(ticker));
        }
        if (dollarVolume < minDollarVolume) {
            return hold(context, 0.0, "Dollar volume too low for aggressive scalping: dollarVolume=" + fmt(dollarVolume));
        }
        if (absVelocity < minAbsVelocity && range < minRange) {
            return hold(context, 0.0, "Tape not violent enough yet: absVelocity=" + fmt(absVelocity) + "% range=" + fmt(range) + "%");
        }

        double high = highestHigh(tape, Math.min(15, tape.size()));
        double low = lowestLow(tape, Math.min(15, tape.size()));
        double last = latest.close;
        double pullbackFromHigh = high > 0.0 ? Math.max(0.0, (high - last) / high * 100.0) : 0.0;
        double recoveryFromLow = low > 0.0 ? Math.max(0.0, (last - low) / low * 100.0) : 0.0;
        double vwap = f.vwap > 0.0 ? f.vwap : TechnicalAnalysis.vwap(tape, Math.min(30, tape.size()));
        boolean aboveOrReclaimingVwap = vwap <= 0.0 || last >= vwap * 0.997 || f.priceVsVwapPct > -0.30;
        boolean longVelocity = f.oneBarVelocityPct >= minAbsVelocity || f.threeBarVelocityPct >= minAbsVelocity || f.accelerationPct > 0.02;
        boolean shortVelocity = f.oneBarVelocityPct <= -minAbsVelocity || f.threeBarVelocityPct <= -minAbsVelocity || f.accelerationPct < -0.02;
        boolean greenNow = latest.close >= latest.open || f.oneBarVelocityPct > 0.0;
        boolean redNow = latest.close <= latest.open || f.oneBarVelocityPct < 0.0;

        double base = 0.50;
        base += topVolume ? 0.10 : 0.04;
        base += Math.min(0.12, dollarVolume / Math.max(minDollarVolume, 1.0) * 0.025);
        base += Math.min(0.10, absVelocity / 0.60 * 0.10);
        base += Math.min(0.08, range / 1.20 * 0.08);
        base += Math.min(0.06, Math.max(0.0, f.relativeVolume - 0.80) / 3.0 * 0.06);

        boolean violentVolumeLong = topVolume || dollarVolume >= minDollarVolume * 2.0 || f.relativeVolume >= 1.25;
        boolean longSetup = violentVolumeLong
                && recoveryFromLow >= minRecovery
                && (longVelocity || f.oneBarVelocityPct > 0.0 || f.accelerationPct > 0.0)
                && (aboveOrReclaimingVwap || recoveryFromLow >= minRecovery * 2.0)
                && greenNow;
        double longConfidence = clamp(base
                + Math.min(0.08, recoveryFromLow / 0.70 * 0.08)
                + (f.breakingOut ? 0.05 : 0.0)
                + (aboveOrReclaimingVwap ? 0.03 : 0.0));

        boolean violentVolumeShort = topVolume || dollarVolume >= minDollarVolume * 2.0 || f.relativeVolume >= 1.25;
        boolean shortSetup = violentVolumeShort
                && pullbackFromHigh >= Math.max(minPullback, 0.05)
                && (shortVelocity || f.oneBarVelocityPct < 0.0 || f.accelerationPct < 0.0)
                && redNow
                && (f.breakingDown || f.priceVsVwapPct < 0.35 || pullbackFromHigh >= 0.12);
        double shortConfidence = clamp(base
                + Math.min(0.10, pullbackFromHigh / 1.20 * 0.10)
                + (f.breakingDown ? 0.06 : 0.0)
                + (f.priceVsVwapPct < 0.0 ? 0.03 : 0.0));

        if (longSetup && longConfidence >= minConfidence && longConfidence >= shortConfidence) {
            return buy(context,
                    longConfidence,
                    Math.max(0.020, Math.min(0.090, (recoveryFromLow + absVelocity + range) / 100.0)),
                    "Top-volume dip-recovery long: " + diagnostics(ticker, f, dollarVolume, pullbackFromHigh, recoveryFromLow, range));
        }

        if (shortSetup && shortConfidence >= minConfidence) {
            return shortSell(context,
                    shortConfidence,
                    Math.max(0.018, Math.min(0.085, (pullbackFromHigh + absVelocity + range) / 100.0)),
                    "Top-volume apex/failure short: " + diagnostics(ticker, f, dollarVolume, pullbackFromHigh, recoveryFromLow, range));
        }

        double watchConfidence = Math.max(longConfidence, shortConfidence);
        return hold(context,
                watchConfidence,
                "Top-volume scalper watching for dip/recovery or apex/failure: " + diagnostics(ticker, f, dollarVolume, pullbackFromHigh, recoveryFromLow, range));
    }

    private String diagnostics(String ticker, TechnicalFeatureSnapshot f, double dollarVolume, double pullback, double recovery, double range) {
        return topVolumeTracker.describe(ticker)
                + " bars=" + f.bars
                + " rvol=" + fmt(f.relativeVolume)
                + " dollarVolume=" + fmt(dollarVolume)
                + " v1=" + fmt(f.oneBarVelocityPct) + "%"
                + " v3=" + fmt(f.threeBarVelocityPct) + "%"
                + " accel=" + fmt(f.accelerationPct) + "%"
                + " pullback=" + fmt(pullback) + "%"
                + " recovery=" + fmt(recovery) + "%"
                + " range=" + fmt(range) + "%"
                + " breakout=" + f.breakingOut
                + " breakdown=" + f.breakingDown;
    }

    private static double highestHigh(List<Bar> bars, int lookback) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int start = Math.max(0, bars.size() - Math.max(1, lookback));
        double high = 0.0;
        for (int i = start; i < bars.size(); i++) {
            Bar b = bars.get(i);
            if (b != null) high = Math.max(high, b.high > 0.0 ? b.high : b.close);
        }
        return high;
    }

    private static double lowestLow(List<Bar> bars, int lookback) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int start = Math.max(0, bars.size() - Math.max(1, lookback));
        double low = Double.MAX_VALUE;
        for (int i = start; i < bars.size(); i++) {
            Bar b = bars.get(i);
            if (b != null && b.close > 0.0) low = Math.min(low, b.low > 0.0 ? b.low : b.close);
        }
        return low == Double.MAX_VALUE ? 0.0 : low;
    }

    private static double recentRangePct(List<Bar> bars, int lookback) {
        double high = highestHigh(bars, lookback);
        double low = lowestLow(bars, lookback);
        return low > 0.0 ? (high - low) / low * 100.0 : 0.0;
    }

    private static double clamp(double v) {
        if (!Double.isFinite(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.3f", Double.isFinite(v) ? v : 0.0);
    }

    private static int envInt(String key, int fallback) {
        try {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
