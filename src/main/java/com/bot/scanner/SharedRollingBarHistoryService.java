package com.bot.scanner;

import com.bot.model.Bar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single shared rolling tape for scanner, news candidates, entry staging, and
 * price polling. Keeping one source of truth prevents scanner candidates from
 * being evaluated once with bars=1 forever while news candidates get a real
 * multi-bar lifecycle.
 */
public final class SharedRollingBarHistoryService {
    private static final SharedRollingBarHistoryService INSTANCE = new SharedRollingBarHistoryService();

    private final Map<String, List<Bar>> barsBySymbol = new ConcurrentHashMap<>();
    private final int maxBars;

    private SharedRollingBarHistoryService() {
        this.maxBars = Math.max(20, intEnv("SHARED_ROLLING_BAR_HISTORY_MAX", 120));
    }

    public static SharedRollingBarHistoryService getInstance() {
        return INSTANCE;
    }

    public boolean observe(String ticker, Bar bar) {
        String symbol = normalize(ticker);
        if (symbol.isEmpty() || bar == null || bar.close <= 0.0) {
            return false;
        }
        if (bar.ticker == null || bar.ticker.isBlank()) {
            bar.ticker = symbol;
        }
        List<Bar> history = barsBySymbol.computeIfAbsent(symbol, k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        synchronized (history) {
            Bar incoming = copy(bar);
            if (!history.isEmpty()) {
                Bar last = history.get(history.size() - 1);
                if (sameObservation(last, incoming)) {
                    /*
                     * Exact duplicate snapshots do not contain new timing
                     * information. Keep the existing observation and do not let a
                     * repeated latest-bar response advance entry timing. If the
                     * provider returns the same bar timestamp with changed OHLCV,
                     * the block below still converts it into a monotonic intrabar
                     * observation.
                     */
                    return false;
                }
                /*
                 * Alpaca latest-bar polling often returns the same 1-minute bar
                 * timestamp for 8-15 seconds while price/volume inside that bar
                 * changes. Treat those changing snapshots as real tape
                 * observations by giving them a monotonic synthetic timestamp.
                 * Without this, every subsystem sees bars=1 and velocity=0.000%.
                 */
                if (incoming.timestamp <= 0L) {
                    incoming.timestamp = System.currentTimeMillis();
                }
                if (last.timestamp >= incoming.timestamp) {
                    incoming.timestamp = last.timestamp + 1L;
                }
            } else if (incoming.timestamp <= 0L) {
                incoming.timestamp = System.currentTimeMillis();
            }
            history.add(incoming);
            while (history.size() > maxBars) {
                history.remove(0);
            }
            return true;
        }
    }

    public List<Bar> recent(String ticker, int limit) {
        String symbol = normalize(ticker);
        if (symbol.isEmpty()) {
            return new ArrayList<>();
        }
        List<Bar> history = barsBySymbol.get(symbol);
        if (history == null || history.isEmpty()) {
            return new ArrayList<>();
        }
        synchronized (history) {
            int max = Math.max(1, limit);
            int start = Math.max(0, history.size() - max);
            List<Bar> out = new ArrayList<>();
            for (int i = start; i < history.size(); i++) {
                out.add(copy(history.get(i)));
            }
            return out;
        }
    }

    public int count(String ticker) {
        String symbol = normalize(ticker);
        List<Bar> history = barsBySymbol.get(symbol);
        if (history == null) return 0;
        synchronized (history) {
            return history.size();
        }
    }

    public Velocity velocity(String ticker) {
        List<Bar> bars = recent(ticker, 60);
        return Velocity.from(bars);
    }

    public double velocityPct(String ticker, int barsBack) {
        List<Bar> bars = recent(ticker, Math.max(2, barsBack + 1));
        if (bars.size() < 2) return 0.0;
        Bar latest = bars.get(bars.size() - 1);
        Bar prior = bars.get(Math.max(0, bars.size() - 1 - Math.max(1, barsBack)));
        return pct(prior.close, latest.close);
    }


    public TechnicalFeatureSnapshot features(String ticker) {
        return TechnicalFeatureService.getInstance().snapshot(ticker);
    }

    public void forget(String ticker) {
        String symbol = normalize(ticker);
        if (!symbol.isEmpty()) {
            barsBySymbol.remove(symbol);
        }
    }

    private static boolean sameObservation(Bar a, Bar b) {
        if (a == null || b == null) return false;
        if (a.timestamp > 0 && b.timestamp > 0 && a.timestamp == b.timestamp) {
            return Math.abs(a.close - b.close) < 0.000001 && a.volume == b.volume;
        }
        return Math.abs(a.close - b.close) < 0.000001
                && Math.abs(a.high - b.high) < 0.000001
                && Math.abs(a.low - b.low) < 0.000001
                && a.volume == b.volume;
    }

    private static Bar copy(Bar in) {
        Bar b = new Bar();
        b.ticker = in.ticker;
        b.timestamp = in.timestamp;
        b.open = in.open;
        b.high = in.high;
        b.low = in.low;
        b.close = in.close;
        b.volume = in.volume;
        return b;
    }

    private static double pct(double from, double to) {
        if (!Double.isFinite(from) || !Double.isFinite(to) || from <= 0.0) return 0.0;
        return ((to - from) / from) * 100.0;
    }

    private static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static int intEnv(String k, int fallback) {
        try {
            String v = System.getenv(k);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public String diagnostics(String ticker) {
        String symbol = normalize(ticker);
        List<Bar> history = barsBySymbol.get(symbol);
        if (history == null) {
            return "historyBars=0";
        }
        synchronized (history) {
            if (history.isEmpty()) {
                return "historyBars=0";
            }
            Bar first = history.get(0);
            Bar last = history.get(history.size() - 1);
            Bar prior = history.size() >= 2 ? history.get(history.size() - 2) : null;
            Velocity v = Velocity.from(new ArrayList<>(history));
            double rawDelta = prior == null || prior.close <= 0.0 || last == null ? 0.0 : pct(prior.close, last.close);
            long rawTsDelta = prior == null || last == null ? 0L : last.timestamp - prior.timestamp;
            return "historyBars=" + history.size()
                    + " firstTs=" + (first == null ? -1L : first.timestamp)
                    + " lastTs=" + (last == null ? -1L : last.timestamp)
                    + " lastClose=" + (last == null ? 0.0 : last.close)
                    + " prevClose=" + (prior == null ? 0.0 : prior.close)
                    + " lastVol=" + (last == null ? 0L : last.volume)
                    + " prevVol=" + (prior == null ? 0L : prior.volume)
                    + " dtMs=" + rawTsDelta
                    + " rawDelta=" + String.format(Locale.US, "%.3f", rawDelta) + "%"
                    + " v1=" + String.format(Locale.US, "%.3f", v.oneBarPct) + "%"
                    + " v3=" + String.format(Locale.US, "%.3f", v.threeBarPct) + "%";
        }
    }

    public static final class Velocity {
        public final double oneBarPct;
        public final double threeBarPct;
        public final double fiveBarPct;
        public final double tenBarPct;
        public final double accelerationPct;
        public final int bars;

        private Velocity(double oneBarPct, double threeBarPct, double fiveBarPct, double tenBarPct, double accelerationPct, int bars) {
            this.oneBarPct = oneBarPct;
            this.threeBarPct = threeBarPct;
            this.fiveBarPct = fiveBarPct;
            this.tenBarPct = tenBarPct;
            this.accelerationPct = accelerationPct;
            this.bars = bars;
        }

        static Velocity from(List<Bar> bars) {
            List<Bar> safe = bars == null ? new ArrayList<>() : new ArrayList<>(bars);
            safe.removeIf(b -> b == null || b.close <= 0.0);
            safe.sort(Comparator.comparingLong(b -> b.timestamp));
            if (safe.size() < 2) {
                return new Velocity(0, 0, 0, 0, 0, safe.size());
            }
            List<Bar> unique = uniqueMeaningfulPriceBars(safe);
            if (unique.size() < 2 && !safe.isEmpty()) {
                unique = safe;
            }
            double one = velocity(unique, 1);
            double three = velocity(unique, 3);
            double five = velocity(unique, 5);
            double ten = velocity(unique, 10);
            double previousOne = previousVelocity(unique, 1);
            if (Math.abs(one) < 0.000001 && !safe.isEmpty()) one = intrabarVelocity(safe.get(safe.size() - 1));
            if (Math.abs(three) < 0.000001) three = one;
            if (Math.abs(five) < 0.000001) five = three;
            return new Velocity(one, three, five, ten, one - previousOne, safe.size());
        }

        private static double velocity(List<Bar> bars, int back) {
            int lastIdx = bars.size() - 1;
            int priorIdx = Math.max(0, lastIdx - Math.max(1, back));
            return pct(bars.get(priorIdx).close, bars.get(lastIdx).close);
        }

        private static double previousVelocity(List<Bar> bars, int back) {
            if (bars.size() < 3) return 0.0;
            int lastIdx = bars.size() - 2;
            int priorIdx = Math.max(0, lastIdx - Math.max(1, back));
            return pct(bars.get(priorIdx).close, bars.get(lastIdx).close);
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
                boolean sameOhlc = Math.abs(last.open - b.open) < 0.000001
                        && Math.abs(last.high - b.high) < 0.000001
                        && Math.abs(last.low - b.low) < 0.000001
                        && Math.abs(last.close - b.close) < 0.000001;
                if (last.timestamp == b.timestamp && sameOhlc && last.volume == b.volume) {
                    continue;
                }
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
            if (high <= low) return 0.0;
            return pct((high + low) / 2.0, latest.close);
        }
    }
}
