package com.bot.intelligence;

import com.bot.model.Bar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight live market-regime classifier.
 *
 * This intentionally does not place trades. It gives the master engine and scanner
 * context about whether the day is trending, choppy, volatile, or panic-like so
 * strategy selection and universe construction can become regime aware.
 */
public final class MarketRegimeEngine {
    private static final MarketRegimeEngine INSTANCE = new MarketRegimeEngine();

    private final ConcurrentHashMap<String, List<Bar>> indexBars = new ConcurrentHashMap<>();
    private volatile MarketRegimeSnapshot snapshot = MarketRegimeSnapshot.unknown();
    private volatile long lastLoggedAt = 0L;

    private MarketRegimeEngine() {
    }

    public static MarketRegimeEngine getInstance() {
        return INSTANCE;
    }

    public MarketRegimeSnapshot currentSnapshot() {
        return snapshot;
    }

    public void observeBar(String ticker, Bar bar) {
        if (ticker == null || bar == null || bar.close <= 0) {
            return;
        }
        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        if (!isMarketProxy(normalized)) {
            return;
        }
        List<Bar> bars = indexBars.computeIfAbsent(normalized, ignored -> new ArrayList<>());
        synchronized (bars) {
            bars.add(bar);
            while (bars.size() > 120) {
                bars.remove(0);
            }
        }
        recompute();
    }

    private void recompute() {
        List<Bar> spy = copyBars("SPY");
        List<Bar> qqq = copyBars("QQQ");
        List<Bar> source = spy.size() >= 6 ? spy : qqq;
        String proxy = spy.size() >= 6 ? "SPY" : "QQQ";
        if (source.size() < 6) {
            return;
        }

        Bar latest = source.get(source.size() - 1);
        Bar fiveBack = source.get(Math.max(0, source.size() - 6));
        Bar twentyBack = source.get(Math.max(0, source.size() - Math.min(21, source.size())));
        double shortTrend = pct(latest.close, fiveBack.close);
        double longerTrend = pct(latest.close, twentyBack.close);
        double volatility = averageRangePercent(source, Math.min(20, source.size()));
        double liquidity = averageVolume(source, Math.min(20, source.size()));

        MarketRegime regime;
        String reason;
        if (longerTrend <= -0.025 || shortTrend <= -0.015) {
            regime = volatility >= 0.012 ? MarketRegime.PANIC : MarketRegime.DOWNTREND;
            reason = proxy + " negative trend short=" + fmt(shortTrend) + " longer=" + fmt(longerTrend) + " vol=" + fmt(volatility);
        } else if (volatility >= 0.018) {
            regime = MarketRegime.HIGH_VOLATILITY;
            reason = proxy + " elevated intraday volatility=" + fmt(volatility);
        } else if (longerTrend >= 0.025 && shortTrend >= 0.004) {
            regime = MarketRegime.STRONG_UPTREND;
            reason = proxy + " strong positive trend short=" + fmt(shortTrend) + " longer=" + fmt(longerTrend);
        } else if (longerTrend >= 0.008 || shortTrend >= 0.006) {
            regime = MarketRegime.UPTREND;
            reason = proxy + " positive trend short=" + fmt(shortTrend) + " longer=" + fmt(longerTrend);
        } else if (liquidity <= 0) {
            regime = MarketRegime.LOW_LIQUIDITY;
            reason = proxy + " missing usable volume";
        } else {
            regime = MarketRegime.RANGE_BOUND;
            reason = proxy + " range-bound short=" + fmt(shortTrend) + " longer=" + fmt(longerTrend) + " vol=" + fmt(volatility);
        }

        MarketRegimeSnapshot next = new MarketRegimeSnapshot(regime, longerTrend, volatility, liquidity, System.currentTimeMillis(), reason);
        snapshot = next;
        maybeLog(next);
    }

    private List<Bar> copyBars(String ticker) {
        List<Bar> bars = indexBars.get(ticker);
        if (bars == null) {
            return new ArrayList<>();
        }
        synchronized (bars) {
            return new ArrayList<>(bars);
        }
    }

    private void maybeLog(MarketRegimeSnapshot next) {
        long now = System.currentTimeMillis();
        if (now - lastLoggedAt < envLong("MARKET_REGIME_LOG_INTERVAL_MS", 120_000L)) {
            return;
        }
        lastLoggedAt = now;
        System.out.println("MARKET REGIME UPDATE: regime=" + next.getRegime() + " reason=" + next.getReason());
    }

    public static boolean isMarketProxy(String ticker) {
        return "SPY".equals(ticker) || "QQQ".equals(ticker) || "IWM".equals(ticker) || "DIA".equals(ticker);
    }

    private static double pct(double latest, double old) {
        if (old <= 0 || latest <= 0) {
            return 0.0;
        }
        return (latest - old) / old;
    }

    private static double averageRangePercent(List<Bar> bars, int limit) {
        if (bars == null || bars.isEmpty()) {
            return 0.0;
        }
        int start = Math.max(0, bars.size() - Math.max(1, limit));
        double sum = 0.0;
        int count = 0;
        for (int i = start; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            if (bar != null && bar.close > 0 && bar.high >= bar.low) {
                sum += (bar.high - bar.low) / bar.close;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double averageVolume(List<Bar> bars, int limit) {
        int start = Math.max(0, bars.size() - Math.max(1, limit));
        double sum = 0.0;
        int count = 0;
        for (int i = start; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            if (bar != null && bar.volume > 0) {
                sum += bar.volume;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
