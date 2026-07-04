package com.bot.strategy.unified;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.technical.TechnicalAnalysis;

import java.util.List;
import java.util.Locale;

/**
 * Dedicated short-alpha strategy.
 *
 * This looks for the pattern that produced the user's best day: a heavily extended
 * stock starts failing instead of continuing, volume is expanding, price loses or
 * rejects VWAP, and the latest bars show red pressure. Unlike the old long-only
 * unified path, this emits TradeDirection.SHORT_STOCK so execution and exits use
 * short entry + buy-to-cover logic.
 */
public class ShortAlphaBreakdownStrategy extends AbstractUnifiedStrategy {

    @Override
    public String name() {
        return "SHORT_ALPHA_BREAKDOWN";
    }

    @Override
    public StrategySignal evaluate(StrategyContext context) {
        if (context == null || context.getBars() == null || context.getBars().size() < 5) {
            return StrategySignal.hold(name(), context == null ? "UNKNOWN" : context.getTicker(), 0.0,
                    "Short alpha waiting for enough bars.");
        }

        List<Bar> bars = context.getBars();
        double latest = TechnicalAnalysis.latestClose(bars);
        double vwap = TechnicalAnalysis.vwap(bars, 30);
        double rvol = TechnicalAnalysis.relativeVolume(bars, 20);
        double rsi = TechnicalAnalysis.rsi(bars, 14);
        double atr = TechnicalAnalysis.atrPercent(bars, 14);
        double dropFromHigh = TechnicalAnalysis.percentDropFromRecentHigh(bars, 20);
        double redPressure = redVolumeRatio(bars, 8);
        boolean lostVwap = latest > 0 && vwap > 0 && latest < vwap;
        boolean lowerHighs = lowerHighs(bars, 3);
        boolean bearishBreak = bearishBreak(bars);
        boolean exhaustionHeadline = exhaustionOrNegativeHeadline(context.newsText());

        double confidence = 0.0;
        confidence += Math.min(1.0, rvol / 5.0) * 0.18;
        confidence += Math.min(1.0, dropFromHigh / 0.055) * 0.18;
        confidence += Math.min(1.0, redPressure / 2.8) * 0.16;
        confidence += lostVwap ? 0.14 : 0.0;
        confidence += lowerHighs ? 0.12 : 0.0;
        confidence += bearishBreak ? 0.14 : 0.0;
        confidence += rsi >= 68.0 ? 0.06 : 0.0;
        confidence += exhaustionHeadline ? 0.10 : 0.0;
        confidence += atr >= 0.006 ? 0.04 : 0.0;
        confidence = TechnicalAnalysis.clamp(confidence);

        boolean setupReady = confidence >= envDouble("SHORT_ALPHA_MIN_CONFIDENCE", 0.72)
                && rvol >= envDouble("SHORT_ALPHA_MIN_RVOL", 1.25)
                && redPressure >= envDouble("SHORT_ALPHA_MIN_RED_RATIO", 1.05)
                && (lostVwap || bearishBreak || lowerHighs)
                && dropFromHigh >= envDouble("SHORT_ALPHA_MIN_DROP_FROM_HIGH", 0.004);

        if (setupReady) {
            double expectedMove = Math.max(0.025, Math.min(0.12, dropFromHigh * 1.75 + Math.max(0.0, atr)));
            return shortSell(
                    context,
                    confidence,
                    expectedMove,
                    String.format(Locale.ROOT,
                            "High-conviction short alpha: rvol=%.2f dropFromHigh=%.2f%% redPressure=%.2f lostVwap=%s lowerHighs=%s bearishBreak=%s rsi=%.1f",
                            rvol,
                            dropFromHigh * 100.0,
                            redPressure,
                            lostVwap,
                            lowerHighs,
                            bearishBreak,
                            rsi)
            );
        }

        return hold(context, confidence,
                String.format(Locale.ROOT,
                        "Short alpha not ready: confidence=%.2f rvol=%.2f dropFromHigh=%.2f%% redPressure=%.2f lostVwap=%s bearishBreak=%s",
                        confidence,
                        rvol,
                        dropFromHigh * 100.0,
                        redPressure,
                        lostVwap,
                        bearishBreak));
    }

    private boolean bearishBreak(List<Bar> bars) {
        if (bars == null || bars.size() < 3) {
            return false;
        }
        Bar latest = bars.get(bars.size() - 1);
        Bar previous = bars.get(bars.size() - 2);
        return latest.close < latest.open && latest.close < previous.low;
    }

    private boolean lowerHighs(List<Bar> bars, int count) {
        if (bars == null || bars.size() < count + 1) {
            return false;
        }
        int start = bars.size() - count;
        double previous = bars.get(start).high;
        for (int i = start + 1; i < bars.size(); i++) {
            double high = bars.get(i).high;
            if (high >= previous) {
                return false;
            }
            previous = high;
        }
        return true;
    }

    private double redVolumeRatio(List<Bar> bars, int lookback) {
        if (bars == null || bars.isEmpty()) {
            return 0.0;
        }
        int start = Math.max(0, bars.size() - Math.max(1, lookback));
        double red = 0.0;
        double green = 0.0;
        for (int i = start; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            if (bar.close < bar.open) {
                red += Math.max(0, bar.volume);
            } else {
                green += Math.max(0, bar.volume);
            }
        }
        if (green <= 0.0) {
            return red > 0.0 ? 99.0 : 0.0;
        }
        return red / green;
    }

    private boolean exhaustionOrNegativeHeadline(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return value.contains("stock drops")
                || value.contains("shares fall")
                || value.contains("edges lower")
                || value.contains("selloff")
                || value.contains("halts on circuit breaker to the downside")
                || value.contains("offering")
                || value.contains("public offering")
                || value.contains("private placement")
                || value.contains("downside")
                || value.contains("pullback")
                || value.contains("breaks support")
                || value.contains("critical support")
                || value.contains("why is") && value.contains("down");
    }
}
