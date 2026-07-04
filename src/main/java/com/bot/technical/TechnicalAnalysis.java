package com.bot.technical;

import com.bot.model.Bar;

import java.util.List;

public final class TechnicalAnalysis {

    private TechnicalAnalysis() {
    }

    public static double percentDropFromRecentHigh(List<Bar> bars, int lookback) {
        if (bars == null || bars.size() < 2) {
            return 0.0;
        }
        int start = Math.max(0, bars.size() - Math.max(2, lookback));
        double high = 0.0;
        for (int i = start; i < bars.size(); i++) {
            high = Math.max(high, bars.get(i).high);
        }
        double latest = latestClose(bars);
        if (high <= 0 || latest <= 0 || latest >= high) {
            return 0.0;
        }
        return (high - latest) / high;
    }

    public static double bounceFromRecentLow(List<Bar> bars, int lookback) {
        if (bars == null || bars.size() < 2) {
            return 0.0;
        }
        int start = Math.max(0, bars.size() - Math.max(2, lookback));
        double low = Double.MAX_VALUE;
        for (int i = start; i < bars.size(); i++) {
            low = Math.min(low, bars.get(i).low);
        }
        double latest = latestClose(bars);
        if (low <= 0 || low == Double.MAX_VALUE || latest <= low) {
            return 0.0;
        }
        return (latest - low) / low;
    }

    public static boolean noFreshLow(List<Bar> bars, int barsToCheck) {
        if (bars == null || bars.size() < barsToCheck + 2) {
            return false;
        }
        int start = bars.size() - barsToCheck;
        double previousLow = Double.MAX_VALUE;
        for (int i = 0; i < start; i++) {
            previousLow = Math.min(previousLow, bars.get(i).low);
        }
        for (int i = start; i < bars.size(); i++) {
            if (bars.get(i).low < previousLow) {
                return false;
            }
        }
        return true;
    }

    public static boolean higherLows(List<Bar> bars, int count) {
        if (bars == null || bars.size() < count + 1) {
            return false;
        }
        int start = bars.size() - count;
        double previous = bars.get(start).low;
        for (int i = start + 1; i < bars.size(); i++) {
            double low = bars.get(i).low;
            if (low <= previous) {
                return false;
            }
            previous = low;
        }
        return true;
    }

    public static boolean bullishBreak(List<Bar> bars) {
        if (bars == null || bars.size() < 3) {
            return false;
        }
        Bar latest = bars.get(bars.size() - 1);
        Bar previous = bars.get(bars.size() - 2);
        return latest.close > latest.open && latest.close > previous.high;
    }

    public static boolean failedBreakdown(List<Bar> bars) {
        if (bars == null || bars.size() < 8) {
            return false;
        }
        Bar latest = bars.get(bars.size() - 1);
        double priorLow = Double.MAX_VALUE;
        for (int i = Math.max(0, bars.size() - 8); i < bars.size() - 2; i++) {
            priorLow = Math.min(priorLow, bars.get(i).low);
        }
        Bar breakdown = bars.get(bars.size() - 2);
        return breakdown.low < priorLow && latest.close > priorLow && latest.close > latest.open;
    }

    public static boolean doubleBottom(List<Bar> bars, double tolerancePercent) {
        if (bars == null || bars.size() < 12) {
            return false;
        }
        int split = bars.size() - 6;
        double low1 = Double.MAX_VALUE;
        double low2 = Double.MAX_VALUE;
        for (int i = Math.max(0, bars.size() - 24); i < split; i++) {
            low1 = Math.min(low1, bars.get(i).low);
        }
        for (int i = split; i < bars.size(); i++) {
            low2 = Math.min(low2, bars.get(i).low);
        }
        if (low1 <= 0 || low2 <= 0 || low1 == Double.MAX_VALUE || low2 == Double.MAX_VALUE) {
            return false;
        }
        double diff = Math.abs(low1 - low2) / low1;
        return diff <= tolerancePercent && latestClose(bars) > low2 * 1.01;
    }

    public static double vwap(List<Bar> bars, int lookback) {
        if (bars == null || bars.isEmpty()) {
            return 0.0;
        }
        int start = Math.max(0, bars.size() - Math.max(1, lookback));
        double pv = 0.0;
        double volume = 0.0;
        for (int i = start; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            double typical = (bar.high + bar.low + bar.close) / 3.0;
            pv += typical * Math.max(0, bar.volume);
            volume += Math.max(0, bar.volume);
        }
        if (volume <= 0) {
            return 0.0;
        }
        return pv / volume;
    }

    public static boolean reclaimedVwap(List<Bar> bars, int lookback) {
        if (bars == null || bars.size() < 2) {
            return false;
        }
        double vwap = vwap(bars, lookback);
        if (vwap <= 0) {
            return false;
        }
        Bar latest = bars.get(bars.size() - 1);
        Bar previous = bars.get(bars.size() - 2);
        return latest.close > vwap && previous.close <= vwap;
    }

    public static double relativeVolume(List<Bar> bars, int lookback) {
        if (bars == null || bars.size() < 2) {
            return 0.0;
        }

        /*
         * Live candidates often have fewer than lookback+1 bars. Returning 0.0
         * makes the quant layer treat the setup as "no RVOL" forever during the
         * exact period where the opportunity is forming. Use the available prior
         * bars as an early RVOL estimate until the full lookback is available.
         */
        int availablePreviousBars = bars.size() - 1;
        int effectiveLookback = Math.max(1, Math.min(Math.max(1, lookback), availablePreviousBars));
        int start = Math.max(0, bars.size() - effectiveLookback - 1);
        double avg = 0.0;
        int count = 0;
        for (int i = start; i < bars.size() - 1; i++) {
            avg += Math.max(0, bars.get(i).volume);
            count++;
        }
        if (count == 0 || avg <= 0) {
            return 0.0;
        }
        avg /= count;
        return bars.get(bars.size() - 1).volume / avg;
    }

    public static double greenVolumeRatio(List<Bar> bars, int lookback) {
        if (bars == null || bars.isEmpty()) {
            return 0.0;
        }
        int start = Math.max(0, bars.size() - lookback);
        double green = 0.0;
        double red = 0.0;
        for (int i = start; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            if (bar.close >= bar.open) {
                green += Math.max(0, bar.volume);
            } else {
                red += Math.max(0, bar.volume);
            }
        }
        if (red <= 0) {
            return green > 0 ? 99.0 : 0.0;
        }
        return green / red;
    }

    public static double atrPercent(List<Bar> bars, int period) {
        if (bars == null || bars.size() < period + 1) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            Bar current = bars.get(i);
            Bar previous = bars.get(i - 1);
            double trueRange = Math.max(
                    current.high - current.low,
                    Math.max(Math.abs(current.high - previous.close), Math.abs(current.low - previous.close))
            );
            sum += trueRange;
        }
        double atr = sum / period;
        double price = latestClose(bars);
        return price <= 0 ? 0.0 : atr / price;
    }

    public static double rsi(List<Bar> bars, int period) {
        if (bars == null || bars.size() < period + 1) {
            return 50.0;
        }
        double gains = 0.0;
        double losses = 0.0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            double change = bars.get(i).close - bars.get(i - 1).close;
            if (change >= 0) {
                gains += change;
            } else {
                losses += Math.abs(change);
            }
        }
        if (losses == 0.0) {
            return 100.0;
        }
        double rs = gains / losses;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    public static boolean morningPanic(long timestamp) {
        java.time.ZonedDateTime time = java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.of("America/New_York"));
        int minutes = time.getHour() * 60 + time.getMinute();
        return minutes >= 570 && minutes <= 660;
    }

    public static double latestClose(List<Bar> bars) {
        if (bars == null || bars.isEmpty()) {
            return 0.0;
        }
        return bars.get(bars.size() - 1).close;
    }

    public static long latestTimestamp(List<Bar> bars) {
        if (bars == null || bars.isEmpty()) {
            return System.currentTimeMillis();
        }
        return bars.get(bars.size() - 1).timestamp;
    }

    public static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
