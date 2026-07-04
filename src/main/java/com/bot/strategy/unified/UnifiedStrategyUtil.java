package com.bot.strategy.unified;

import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;

final class UnifiedStrategyUtil {
    private UnifiedStrategyUtil() {}

    static Bar latest(List<Bar> bars) {
        return bars == null || bars.isEmpty() ? null : bars.get(bars.size() - 1);
    }

    static Bar previous(List<Bar> bars) {
        return bars == null || bars.size() < 2 ? null : bars.get(bars.size() - 2);
    }

    static double highestHigh(List<Bar> bars, int lookback, int excludeLast) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int end = Math.max(0, bars.size() - Math.max(0, excludeLast));
        int start = Math.max(0, end - Math.max(1, lookback));
        double high = 0.0;
        for (int i = start; i < end; i++) high = Math.max(high, bars.get(i).high);
        return high;
    }

    static double lowestLow(List<Bar> bars, int lookback, int excludeLast) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int end = Math.max(0, bars.size() - Math.max(0, excludeLast));
        int start = Math.max(0, end - Math.max(1, lookback));
        double low = Double.MAX_VALUE;
        for (int i = start; i < end; i++) low = Math.min(low, bars.get(i).low);
        return low == Double.MAX_VALUE ? 0.0 : low;
    }

    static double smaClose(List<Bar> bars, int lookback) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int start = Math.max(0, bars.size() - Math.max(1, lookback));
        double sum = 0.0;
        int count = 0;
        for (int i = start; i < bars.size(); i++) {
            sum += bars.get(i).close;
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    static double pctChange(List<Bar> bars, int barsBack) {
        if (bars == null || bars.size() < barsBack + 1) return 0.0;
        double old = bars.get(bars.size() - 1 - barsBack).close;
        double now = TechnicalAnalysis.latestClose(bars);
        return old <= 0 || now <= 0 ? 0.0 : (now - old) / old;
    }

    static double candleBodyPct(Bar bar) {
        if (bar == null || bar.close <= 0) return 0.0;
        return Math.abs(bar.close - bar.open) / bar.close;
    }

    static double upperWickPct(Bar bar) {
        if (bar == null || bar.close <= 0) return 0.0;
        double bodyHigh = Math.max(bar.open, bar.close);
        return Math.max(0.0, bar.high - bodyHigh) / bar.close;
    }

    static double lowerWickPct(Bar bar) {
        if (bar == null || bar.close <= 0) return 0.0;
        double bodyLow = Math.min(bar.open, bar.close);
        return Math.max(0.0, bodyLow - bar.low) / bar.close;
    }

    static boolean risingCloses(List<Bar> bars, int count) {
        if (bars == null || bars.size() < count + 1) return false;
        int start = bars.size() - count;
        double previous = bars.get(start).close;
        for (int i = start + 1; i < bars.size(); i++) {
            if (bars.get(i).close <= previous) return false;
            previous = bars.get(i).close;
        }
        return true;
    }

    static boolean fallingCloses(List<Bar> bars, int count) {
        if (bars == null || bars.size() < count + 1) return false;
        int start = bars.size() - count;
        double previous = bars.get(start).close;
        for (int i = start + 1; i < bars.size(); i++) {
            if (bars.get(i).close >= previous) return false;
            previous = bars.get(i).close;
        }
        return true;
    }

    static boolean lowerHighs(List<Bar> bars, int count) {
        if (bars == null || bars.size() < count + 1) return false;
        int start = bars.size() - count;
        double previous = bars.get(start).high;
        for (int i = start + 1; i < bars.size(); i++) {
            if (bars.get(i).high >= previous) return false;
            previous = bars.get(i).high;
        }
        return true;
    }

    static boolean bearishBreak(List<Bar> bars) {
        if (bars == null || bars.size() < 3) return false;
        Bar latest = latest(bars);
        Bar previous = previous(bars);
        return latest.close < latest.open && latest.close < previous.low;
    }

    static double redVolumeRatio(List<Bar> bars, int lookback) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int start = Math.max(0, bars.size() - Math.max(1, lookback));
        double red = 0.0;
        double green = 0.0;
        for (int i = start; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            if (bar.close < bar.open) red += Math.max(0, bar.volume);
            else green += Math.max(0, bar.volume);
        }
        if (green <= 0.0) return red > 0.0 ? 99.0 : 0.0;
        return red / green;
    }

    static double consolidationRangePct(List<Bar> bars, int lookback) {
        double high = highestHigh(bars, lookback, 0);
        double low = lowestLow(bars, lookback, 0);
        double latest = TechnicalAnalysis.latestClose(bars);
        if (high <= 0 || low <= 0 || latest <= 0) return 0.0;
        return (high - low) / latest;
    }

    static double averageVolume(List<Bar> bars, int lookback, int excludeLast) {
        if (bars == null || bars.isEmpty()) return 0.0;
        int end = Math.max(0, bars.size() - Math.max(0, excludeLast));
        int start = Math.max(0, end - Math.max(1, lookback));
        double sum = 0.0;
        int count = 0;
        for (int i = start; i < end; i++) {
            sum += Math.max(0, bars.get(i).volume);
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    static boolean hasEnoughBars(List<Bar> bars, int min) {
        return bars != null && bars.size() >= min;
    }

    static String lowerNews(String text) {
        return text == null ? "" : text.toLowerCase();
    }
}
