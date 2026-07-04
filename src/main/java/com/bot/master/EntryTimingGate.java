package com.bot.master;

import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.TradeDirection;

import java.util.List;
import java.util.Locale;

/**
 * Final timing gate immediately before order submission.
 *
 * Momentum discovery should find explosive stocks, but execution still needs a
 * good tactical entry. Non-news long setups should not chase a falling bar; they
 * should wait for pullback stabilization/reclaim. Short setups should not short
 * randomly into weakness; they should wait for rejection from a local peak.
 *
 * True breaking news spikes are the only exception allowed to enter immediately.
 */
public final class EntryTimingGate {

    private final boolean enabled = boolEnv("ENTRY_TIMING_GATE_ENABLED", true);
    private final int lookbackBars = intEnv("ENTRY_TIMING_LOOKBACK_BARS", 12);
    private final int minimumBars = intEnv("ENTRY_TIMING_MIN_BARS", 4);
    private final double maxLongPullbackFromHighPct = doubleEnv("ENTRY_TIMING_MAX_LONG_PULLBACK_FROM_HIGH_PCT", 7.5);
    private final double minLongRecoveryPct = doubleEnv("ENTRY_TIMING_MIN_LONG_RECOVERY_PCT", 0.08);
    private final double minShortReversalFromPeakPct = doubleEnv("ENTRY_TIMING_MIN_SHORT_REVERSAL_FROM_PEAK_PCT", 0.25);
    private final double maxShortLateBreakdownPct = doubleEnv("ENTRY_TIMING_MAX_SHORT_LATE_BREAKDOWN_PCT", 8.0);
    private final double minNewsSpikeScore = doubleEnv("ENTRY_TIMING_NEWS_SPIKE_MIN_CONFIDENCE", 0.74);

    public EntryTimingReview review(StrategySignal signal, MarketDataCache marketData) {
        if (!enabled) {
            return EntryTimingReview.allow("ENTRY_TIMING_GATE_DISABLED");
        }
        if (signal == null) {
            return EntryTimingReview.block("ENTRY_TIMING_BLOCK: missing signal");
        }
        String ticker = signal.getTicker();
        if (ticker == null || ticker.isBlank()) {
            return EntryTimingReview.block("ENTRY_TIMING_BLOCK: missing ticker");
        }
        boolean newsSpike = isImmediateNewsSpike(signal);
        if (newsSpike) {
            return EntryTimingReview.allow("ENTRY_TIMING_ALLOW_NEWS_SPIKE strategy=" + signal.getStrategyName());
        }
        if (marketData == null) {
            return EntryTimingReview.block("ENTRY_TIMING_BLOCK: no marketData for non-news setup");
        }
        List<Bar> bars = marketData.recentBars(ticker.trim().toUpperCase(Locale.ROOT), lookbackBars);
        if (bars == null || bars.size() < minimumBars) {
            return EntryTimingReview.block("ENTRY_TIMING_BLOCK: not enough bars for tactical entry bars="
                    + (bars == null ? 0 : bars.size()) + " min=" + minimumBars);
        }

        Bar latest = bars.get(bars.size() - 1);
        Bar previous = bars.get(bars.size() - 2);
        if (latest == null || previous == null || latest.close <= 0.0 || previous.close <= 0.0) {
            return EntryTimingReview.block("ENTRY_TIMING_BLOCK: unusable latest/previous bar");
        }

        Metrics m = metrics(bars);
        boolean shortEntry = signal.getDirection() == TradeDirection.SHORT_STOCK;

        if (shortEntry) {
            return reviewShort(signal, latest, previous, m);
        }
        return reviewLong(signal, latest, previous, m);
    }

    private EntryTimingReview reviewLong(StrategySignal signal, Bar latest, Bar previous, Metrics m) {
        boolean currentBarRecovering = latest.close > previous.close
                || pct(latest.close, Math.max(0.000001, previous.close)) >= minLongRecoveryPct;
        boolean recoveredFromPullback = m.drawdownFromHighPct <= maxLongPullbackFromHighPct
                && latest.close > m.lowSinceHigh
                && (latest.close >= previous.close || latest.close >= m.vwap);
        boolean reclaimingStructure = latest.close >= m.vwap
                || latest.close >= m.recentMid
                || latest.close >= previous.high;
        boolean notActivelyDropping = latest.close >= previous.close
                || latest.close >= m.priorLow
                || m.fastVelocityPct > 0.0;

        if (currentBarRecovering && recoveredFromPullback && reclaimingStructure && notActivelyDropping) {
            return EntryTimingReview.allow("ENTRY_TIMING_ALLOW_LONG_RECOVERY"
                    + " close=" + fmt(latest.close)
                    + " prev=" + fmt(previous.close)
                    + " pullbackFromHigh=" + fmt(m.drawdownFromHighPct) + "%"
                    + " fastVelocity=" + fmt(m.fastVelocityPct) + "%"
                    + " vwapDist=" + fmt(pct(latest.close, m.vwap)) + "%");
        }

        return EntryTimingReview.block("ENTRY_TIMING_WAIT_LONG_RECOVERY"
                + " close=" + fmt(latest.close)
                + " prev=" + fmt(previous.close)
                + " recovering=" + currentBarRecovering
                + " recoveredFromPullback=" + recoveredFromPullback
                + " reclaimingStructure=" + reclaimingStructure
                + " notDropping=" + notActivelyDropping
                + " pullbackFromHigh=" + fmt(m.drawdownFromHighPct) + "%"
                + " fastVelocity=" + fmt(m.fastVelocityPct) + "%"
                + " strategy=" + signal.getStrategyName());
    }

    private EntryTimingReview reviewShort(StrategySignal signal, Bar latest, Bar previous, Metrics m) {
        boolean rejectedFromPeak = m.drawdownFromHighPct >= minShortReversalFromPeakPct;
        boolean currentBarRejecting = latest.close < previous.close
                || latest.close < m.vwap
                || latest.close < previous.low;
        boolean notTooLate = m.drawdownFromHighPct <= maxShortLateBreakdownPct;
        boolean lowerStructure = latest.close <= m.recentMid || latest.close < previous.low || m.fastVelocityPct < 0.0;

        if (rejectedFromPeak && currentBarRejecting && notTooLate && lowerStructure) {
            return EntryTimingReview.allow("ENTRY_TIMING_ALLOW_SHORT_PEAK_REVERSAL"
                    + " close=" + fmt(latest.close)
                    + " prev=" + fmt(previous.close)
                    + " reversalFromPeak=" + fmt(m.drawdownFromHighPct) + "%"
                    + " fastVelocity=" + fmt(m.fastVelocityPct) + "%"
                    + " vwapDist=" + fmt(pct(latest.close, m.vwap)) + "%");
        }

        return EntryTimingReview.block("ENTRY_TIMING_WAIT_SHORT_PEAK_REVERSAL"
                + " close=" + fmt(latest.close)
                + " prev=" + fmt(previous.close)
                + " rejectedFromPeak=" + rejectedFromPeak
                + " currentBarRejecting=" + currentBarRejecting
                + " notTooLate=" + notTooLate
                + " lowerStructure=" + lowerStructure
                + " reversalFromPeak=" + fmt(m.drawdownFromHighPct) + "%"
                + " fastVelocity=" + fmt(m.fastVelocityPct) + "%"
                + " strategy=" + signal.getStrategyName());
    }

    private boolean isImmediateNewsSpike(StrategySignal signal) {
        String strategy = upper(signal.getStrategyName());
        String reason = upper(signal.getReason());
        boolean newsStrategy = strategy.contains("NEWS")
                || strategy.contains("FDA")
                || strategy.contains("CONTRACT")
                || strategy.contains("CATALYST")
                || strategy.contains("EARNINGS")
                || strategy.contains("GUIDANCE")
                || reason.contains("BREAKING NEWS")
                || reason.contains("NEWS SPIKE")
                || reason.contains("FDA APPROVAL")
                || reason.contains("CONTRACT AWARD")
                || reason.contains("GUIDANCE")
                || reason.contains("RAISES FY")
                || reason.contains("RAISES GUIDANCE");
        boolean strongEnough = signal.getConfidence() >= minNewsSpikeScore
                || reason.contains("WEBSOCKET_FIRST")
                || reason.contains("BREAKING")
                || reason.contains("PRESS_RELEASE");
        return newsStrategy && strongEnough && signal.getDirection() != TradeDirection.SHORT_STOCK;
    }

    private Metrics metrics(List<Bar> bars) {
        Metrics m = new Metrics();
        double high = 0.0;
        double low = Double.MAX_VALUE;
        double pv = 0.0;
        double vol = 0.0;
        for (Bar b : bars) {
            if (b == null || b.close <= 0.0) continue;
            high = Math.max(high, b.high > 0.0 ? b.high : b.close);
            low = Math.min(low, b.low > 0.0 ? b.low : b.close);
            pv += b.close * Math.max(1L, b.volume);
            vol += Math.max(1L, b.volume);
        }
        Bar latest = bars.get(bars.size() - 1);
        Bar prior = bars.get(Math.max(0, bars.size() - 4));
        m.recentHigh = high <= 0.0 ? latest.close : high;
        m.recentLow = low == Double.MAX_VALUE ? latest.close : low;
        m.recentMid = (m.recentHigh + m.recentLow) / 2.0;
        m.vwap = vol <= 0.0 ? latest.close : pv / vol;
        m.drawdownFromHighPct = m.recentHigh <= 0.0 ? 0.0 : Math.max(0.0, (m.recentHigh - latest.close) / m.recentHigh * 100.0);
        m.fastVelocityPct = prior.close <= 0.0 ? 0.0 : (latest.close - prior.close) / prior.close * 100.0;
        m.priorLow = bars.get(bars.size() - 2).low > 0.0 ? bars.get(bars.size() - 2).low : bars.get(bars.size() - 2).close;
        m.lowSinceHigh = latest.close;
        boolean afterHigh = false;
        for (Bar b : bars) {
            if (b == null) continue;
            double bh = b.high > 0.0 ? b.high : b.close;
            if (!afterHigh && Math.abs(bh - m.recentHigh) < 0.000001) {
                afterHigh = true;
            }
            if (afterHigh) {
                double bl = b.low > 0.0 ? b.low : b.close;
                m.lowSinceHigh = Math.min(m.lowSinceHigh, bl);
            }
        }
        return m;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static double pct(double value, double base) {
        if (base <= 0.0 || !Double.isFinite(base)) return 0.0;
        return (value - base) / base * 100.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.3f", Double.isFinite(v) ? v : 0.0);
    }

    private static int intEnv(String key, int fallback) {
        try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static double doubleEnv(String key, double fallback) {
        try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static boolean boolEnv(String key, boolean fallback) {
        try {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback :
                    ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()) || "yes".equalsIgnoreCase(v.trim()));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class Metrics {
        double recentHigh;
        double recentLow;
        double recentMid;
        double vwap;
        double drawdownFromHighPct;
        double fastVelocityPct;
        double priorLow;
        double lowSinceHigh;
    }

    public static final class EntryTimingReview {
        private final boolean approved;
        private final String reason;

        private EntryTimingReview(boolean approved, String reason) {
            this.approved = approved;
            this.reason = reason == null ? "" : reason;
        }

        public static EntryTimingReview allow(String reason) { return new EntryTimingReview(true, reason); }
        public static EntryTimingReview block(String reason) { return new EntryTimingReview(false, reason); }
        public boolean isApproved() { return approved; }
        public String getReason() { return reason; }
    }
}
