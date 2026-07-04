package com.bot.scanner;

import com.bot.model.Bar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single technical feature source for discovery, lifecycle, and entry timing.
 * Scanner, news candidates, and entry staging should read these values instead
 * of each recomputing RVOL/velocity from different caches.
 */
public final class TechnicalFeatureService {
    private static final TechnicalFeatureService INSTANCE = new TechnicalFeatureService();
    private final SharedRollingBarHistoryService sharedBars = SharedRollingBarHistoryService.getInstance();
    private final Map<String, Double> rollingVolumeBaseline = new ConcurrentHashMap<>();

    private TechnicalFeatureService() {}

    public static TechnicalFeatureService getInstance() {
        return INSTANCE;
    }

    public TechnicalFeatureSnapshot observeAndSnapshot(String ticker, Bar bar) {
        sharedBars.observe(ticker, bar);
        return snapshot(ticker);
    }

    public TechnicalFeatureSnapshot snapshot(String ticker) {
        String symbol = normalize(ticker);
        List<Bar> bars = sharedBars.recent(symbol, intEnv("TECHNICAL_FEATURE_ROLLING_BARS", 90));
        return fromBars(symbol, bars);
    }

    public TechnicalFeatureSnapshot fromBars(String ticker, List<Bar> input) {
        String symbol = normalize(ticker);
        List<Bar> bars = input == null ? new ArrayList<>() : new ArrayList<>(input);
        bars.removeIf(b -> b == null || b.close <= 0.0);
        bars.sort(Comparator.comparingLong(b -> b.timestamp));
        if (bars.isEmpty()) {
            return new TechnicalFeatureSnapshot(symbol, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);
        }

        Bar latest = bars.get(bars.size() - 1);
        double latestPrice = latest.close;
        long latestVolume = Math.max(0L, latest.volume);
        double dollarVolume = latestPrice * latestVolume;
        List<Bar> uniquePriceBars = uniqueMeaningfulPriceBars(bars);
        double avgPriorVolume = averagePriorVolume(uniquePriceBars, Math.min(20, Math.max(1, uniquePriceBars.size() - 1)), latest);
        double baselineVolume = baselineVolume(symbol, avgPriorVolume, latestVolume);
        double rvol = baselineVolume > 0.0 ? latestVolume / baselineVolume : startupVolumeProxy(latest);

        double one = velocity(uniquePriceBars, 1);
        double three = velocity(uniquePriceBars, 3);
        double five = velocity(uniquePriceBars, 5);
        double ten = velocity(uniquePriceBars, 10);
        double prevOne = previousVelocity(uniquePriceBars, 1);
        if (Math.abs(one) < 0.000001) one = intrabarVelocity(latest);
        if (Math.abs(three) < 0.000001) three = one;
        if (Math.abs(five) < 0.000001) five = three;
        double accel = one - prevOne;

        int start = Math.max(0, bars.size() - 10);
        double high = 0.0;
        double low = Double.MAX_VALUE;
        double trueRangeSum = 0.0;
        int trueRangeCount = 0;
        double pv = 0.0;
        double vol = 0.0;
        double previousClose = 0.0;
        for (int i = start; i < bars.size(); i++) {
            Bar b = bars.get(i);
            if (b.high > 0) high = Math.max(high, b.high);
            if (b.low > 0) low = Math.min(low, b.low);
            long v = Math.max(0L, b.volume);
            pv += b.close * v;
            vol += v;
            double barHigh = b.high > 0 ? b.high : b.close;
            double barLow = b.low > 0 ? b.low : b.close;
            double tr = Math.max(0.0, barHigh - barLow);
            if (previousClose > 0.0) {
                tr = Math.max(tr, Math.abs(barHigh - previousClose));
                tr = Math.max(tr, Math.abs(barLow - previousClose));
            }
            if (b.close > 0.0) {
                trueRangeSum += tr / b.close * 100.0;
                trueRangeCount++;
            }
            previousClose = b.close;
        }
        if (high <= 0.0) high = latestPrice;
        if (low == Double.MAX_VALUE || low <= 0.0) low = latestPrice;
        double rangePct = low > 0.0 ? ((high - low) / low) * 100.0 : 0.0;
        double atrPct = trueRangeCount > 0 ? trueRangeSum / trueRangeCount : 0.0;
        double vwap = vol > 0.0 ? pv / vol : latestPrice;
        double priceVsVwap = pct(vwap, latestPrice);

        Bar prior = bars.size() >= 2 ? bars.get(bars.size() - 2) : latest;
        boolean breakout = bars.size() >= 2 && (latest.close >= high * 0.999 || (prior.high > 0.0 && latest.close > prior.high));
        boolean breakdown = bars.size() >= 2 && (latest.close <= low * 1.001 || (prior.low > 0.0 && latest.close < prior.low));

        return new TechnicalFeatureSnapshot(symbol, bars.size(), latestPrice, latestVolume, dollarVolume, rvol,
                one, three, five, ten, accel, rangePct, atrPct, vwap, priceVsVwap, breakout, breakdown);
    }


    private double baselineVolume(String symbol, double avgPriorVolume, long latestVolume) {
        String key = normalize(symbol);
        if (key.isEmpty() || latestVolume <= 0L) {
            return avgPriorVolume;
        }
        double previous = rollingVolumeBaseline.getOrDefault(key, 0.0);
        double candidate = avgPriorVolume > 0.0 ? avgPriorVolume : previous;
        if (candidate <= 0.0) {
            // Conservative startup baseline. It prevents every first observation
            // from defaulting to exactly 1.000 while still allowing unusually
            // high absolute volume to stand out immediately.
            candidate = Math.max(800.0, latestVolume / Math.max(0.85, startupVolumeProxyFromVolume(latestVolume)));
        }
        double updated = previous <= 0.0 ? candidate : previous * 0.85 + latestVolume * 0.15;
        rollingVolumeBaseline.put(key, Math.max(1.0, updated));
        return Math.max(1.0, candidate);
    }

    private static double averagePriorVolume(List<Bar> bars, int lookback, Bar latest) {
        if (bars == null || bars.size() < 2) return 0.0;
        int endExclusive = bars.size() - 1;
        int start = Math.max(0, endExclusive - Math.max(1, lookback));
        double sum = 0.0;
        int count = 0;
        long latestVolume = latest == null ? -1L : latest.volume;
        double latestClose = latest == null ? Double.NaN : latest.close;
        for (int i = start; i < endExclusive; i++) {
            Bar b = bars.get(i);
            if (b != null && b.volume > 0) {
                // Repeated snapshots of the current unfinished minute bar are
                // not a historical baseline. If they match the latest price and
                // volume, skip them so RVOL does not collapse to exactly 1.000.
                if (latestVolume > 0 && b.volume == latestVolume && Math.abs(b.close - latestClose) < 0.000001) {
                    continue;
                }
                sum += b.volume;
                count++;
            }
        }
        return count > 0 ? sum / count : 0.0;
    }

    private static double startupVolumeProxy(Bar latest) {
        if (latest == null || latest.volume <= 0) return 0.0;
        return startupVolumeProxyFromVolume(latest.volume);
    }

    private static double startupVolumeProxyFromVolume(double v) {
        // Bootstrap RVOL from absolute volume when no true historical baseline
        // exists yet. This avoids the misleading default rvol=1.000 for every
        // symbol at startup while still staying conservative.
        if (v >= 250_000) return 4.0;
        if (v >= 100_000) return 3.2;
        if (v >= 50_000) return 2.6;
        if (v >= 20_000) return 2.0;
        if (v >= 10_000) return 1.6;
        if (v >= 2_500) return 1.25;
        return 0.85;
    }

    private static List<Bar> uniqueMeaningfulPriceBars(List<Bar> bars) {
        List<Bar> out = new ArrayList<>();
        if (bars == null) return out;
        for (Bar b : bars) {
            if (b == null || b.close <= 0.0) continue;
            if (out.isEmpty()) {
                out.add(b);
                continue;
            }
            Bar last = out.get(out.size() - 1);
            boolean samePrice = Math.abs(last.close - b.close) < 0.000001;
            boolean sameOhlc = Math.abs(last.open - b.open) < 0.000001
                    && Math.abs(last.high - b.high) < 0.000001
                    && Math.abs(last.low - b.low) < 0.000001
                    && samePrice;
            if (last.timestamp == b.timestamp && sameOhlc && last.volume == b.volume) {
                continue;
            }
            // Keep changed intrabar snapshots even when the exchange minute
            // timestamp is unchanged; they are useful for entry timing.
            out.add(b);
        }
        return out;
    }

    private static double intrabarVelocity(Bar latest) {
        if (latest == null || latest.close <= 0.0) return 0.0;
        if (latest.open > 0.0 && Math.abs(latest.close - latest.open) > 0.000001) {
            return pct(latest.open, latest.close);
        }
        double high = latest.high > 0.0 ? latest.high : latest.close;
        double low = latest.low > 0.0 ? latest.low : latest.close;
        if (high <= low || latest.close <= 0.0) return 0.0;
        double mid = (high + low) / 2.0;
        return pct(mid, latest.close);
    }

    private static double velocity(List<Bar> bars, int back) {
        if (bars == null || bars.size() < 2) return 0.0;
        int lastIdx = bars.size() - 1;
        int priorIdx = Math.max(0, lastIdx - Math.max(1, back));
        return pct(bars.get(priorIdx).close, bars.get(lastIdx).close);
    }

    private static double previousVelocity(List<Bar> bars, int back) {
        if (bars == null || bars.size() < 3) return 0.0;
        int lastIdx = bars.size() - 2;
        int priorIdx = Math.max(0, lastIdx - Math.max(1, back));
        return pct(bars.get(priorIdx).close, bars.get(lastIdx).close);
    }

    private static double pct(double from, double to) {
        if (!Double.isFinite(from) || !Double.isFinite(to) || from <= 0.0) return 0.0;
        return ((to - from) / from) * 100.0;
    }

    private static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static int intEnv(String key, int fallback) {
        try {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
