package com.bot.scanner;

import com.bot.master.MomentumIgnitionProfile;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.TradeDirection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Market-first discovery layer for the live scanner.
 *
 * The first versions of this gate used a fixed latest-bar share-count minimum.
 * That was too blunt for momentum trading: a $500 stock can have real momentum
 * with far fewer shares than a $1 stock, and an early ignition move may show up
 * as RVOL/dollar-volume/range expansion before a static share threshold is met.
 *
 * This version is adaptive. It scores the tape from live behavior:
 * relative volume, dollar volume, price velocity, range/ATR expansion, and the
 * existing MomentumIgnitionProfile. News/catalysts are still excluded from the
 * score so this remains a pure market-behavior filter.
 */
public final class MomentumDiscoveryEngine {

    private final int recentBarsLimit;
    private final int minimumBars;
    private final long absoluteMinimumLatestVolume;
    private final double minimumDollarVolume;
    private final double minimumDiscoveryScore;
    private final double minimumIgnitionScore;
    private final double minimumRelativeVolume;
    private final boolean requireHardIgnition;
    private final SharedRollingBarHistoryService sharedBarHistory = SharedRollingBarHistoryService.getInstance();
    private final TechnicalFeatureService technicalFeatureService = TechnicalFeatureService.getInstance();

    public MomentumDiscoveryEngine() {
        this.recentBarsLimit = intEnv("MOMENTUM_DISCOVERY_RECENT_BARS", 40);
        this.minimumBars = intEnv("MOMENTUM_DISCOVERY_MIN_BARS", 6);
        // Safety floor only. This is intentionally not 25,000; liquidity is
        // handled adaptively by dollar volume + RVOL below.
        this.absoluteMinimumLatestVolume = longEnv("MOMENTUM_DISCOVERY_ABSOLUTE_MIN_LATEST_VOLUME", 100L);
        this.minimumDollarVolume = doubleEnv("MOMENTUM_DISCOVERY_MIN_DOLLAR_VOLUME", 35_000.0);
        this.minimumDiscoveryScore = doubleEnv("MOMENTUM_DISCOVERY_MIN_SCORE", 0.46);
        this.minimumIgnitionScore = doubleEnv("MOMENTUM_DISCOVERY_MIN_IGNITION_SCORE", 0.42);
        this.minimumRelativeVolume = doubleEnv("MOMENTUM_DISCOVERY_MIN_RVOL", 1.25);
        this.requireHardIgnition = boolEnv("MOMENTUM_DISCOVERY_REQUIRE_HARD_GATE", false);
    }

    public MomentumDiscoveryProfile evaluate(String ticker, MarketDataCache cache, Bar latest) {
        String symbol = ticker == null ? "UNKNOWN" : ticker.trim().toUpperCase(Locale.ROOT);
        if (latest == null || latest.close <= 0.0) {
            return MomentumDiscoveryProfile.reject(symbol, "missing latest bar");
        }

        double dollarVolume = Math.max(0.0, latest.close * Math.max(0L, latest.volume));
        if (latest.volume < absoluteMinimumLatestVolume && dollarVolume < minimumDollarVolume) {
            return MomentumDiscoveryProfile.reject(symbol,
                    "liquidity floor failed: volume=" + latest.volume
                            + " dollarVolume=" + fmt(dollarVolume)
                            + " minVolume=" + absoluteMinimumLatestVolume
                            + " minDollarVolume=" + fmt(minimumDollarVolume));
        }

        /*
         * Use the shared rolling tape as the primary source of truth. Earlier
         * builds mixed MarketDataCache, scanner history, and staging history,
         * which made RVOL reset to 1.000 and velocity stay 0.000% as candidates
         * moved between subsystems.
         */
        sharedBarHistory.observe(symbol, latest);
        List<Bar> bars = new ArrayList<>();
        bars.addAll(sharedBarHistory.recent(symbol, recentBarsLimit));
        if (cache != null && ticker != null && !ticker.isBlank()) {
            bars = mergeBars(bars, cache.recentBars(symbol, recentBarsLimit));
        }
        appendIfNew(bars, latest);

        TechnicalFeatureSnapshot features = technicalFeatureService.fromBars(symbol, bars);
        double relativeVolume = features.relativeVolume > 0.0 ? features.relativeVolume : startupVolumeProxy(latest);
        double rangeNow = features.rangePct > 0.0 ? features.rangePct : singleBarRangePct(latest);
        double dollarVolumeScore = dollarVolumeScore(latest.close, dollarVolume);
        double rvolScore = clamp01((relativeVolume - 1.0) / 4.0);
        double liquidityScore = clamp01(dollarVolume / adaptiveDollarVolumeTarget(latest.close));

        if (bars.size() < minimumBars) {
            double singleBarScore = singleBarScore(latest, relativeVolume, dollarVolume, liquidityScore);
            boolean liquidityPass = adaptiveLiquidityPass(latest.close, latest.volume, dollarVolume, relativeVolume, rangeNow);
            boolean pass = liquidityPass && singleBarScore >= minimumDiscoveryScore + 0.08;
            return new MomentumDiscoveryProfile(symbol, pass, singleBarScore, 0.0, latest.volume,
                    0.0, 0.0, rangeNow, dollarVolumeScore, relativeVolume, dollarVolume,
                    "startup adaptive discovery score=" + fmt(singleBarScore)
                            + " bars=" + bars.size()
                            + " rvol=" + fmt(relativeVolume)
                            + " dollarVolume=" + fmt(dollarVolume)
                            + " liquidityPass=" + liquidityPass);
        }

        MarketDataCache probeCache = new MarketDataCache();
        for (Bar b : bars) {
            if (b != null) {
                probeCache.addBar(symbol, b);
            }
        }

        StrategySignal longProbe = StrategySignal.buy(
                "MOMENTUM_DISCOVERY_PROBE",
                symbol,
                TradeDirection.LONG_STOCK,
                0.50,
                0.50,
                1,
                "live momentum discovery probe"
        );
        MomentumIgnitionProfile longIgnition = MomentumIgnitionProfile.from(
                new StrategyContext(symbol, probeCache, null, null, latest.close, 100_000.0),
                longProbe
        );

        StrategySignal shortProbe = StrategySignal.buy(
                "MOMENTUM_DISCOVERY_PROBE_SHORT",
                symbol,
                TradeDirection.SHORT_STOCK,
                0.50,
                0.50,
                1,
                "live downside momentum discovery probe"
        );
        MomentumIgnitionProfile shortIgnition = MomentumIgnitionProfile.from(
                new StrategyContext(symbol, probeCache, null, null, latest.close, 100_000.0),
                shortProbe
        );

        MomentumIgnitionProfile best = longIgnition.getScore() >= shortIgnition.getScore() ? longIgnition : shortIgnition;
        double liveVelocity = features.oneBarVelocityPct;
        double fastVelocity = features.threeBarVelocityPct;
        double range = features.rangePct > 0.0 ? features.rangePct : rangePct(bars, Math.min(6, bars.size()));
        double ignitionScore = best.getScore();
        double velocityScore = clamp01(Math.abs(liveVelocity) / 1.15);
        double fastVelocityScore = clamp01(Math.abs(fastVelocity) / 2.0);
        double rangeScore = clamp01(range / 2.75);

        // Rank momentum by actual tape behavior first. Ignition remains important,
        // but acceleration/range/RVOL get more weight so parabolic movers are surfaced
        // even before a strategy produces a final entry signal.
        double score = clamp01(ignitionScore * 0.28
                + rvolScore * 0.20
                + dollarVolumeScore * 0.14
                + velocityScore * 0.16
                + fastVelocityScore * 0.12
                + rangeScore * 0.10);

        boolean liquidityPass = adaptiveLiquidityPass(latest.close, latest.volume, dollarVolume, relativeVolume, range);
        boolean ignitionPass = ignitionScore >= minimumIgnitionScore
                || (relativeVolume >= minimumRelativeVolume * 1.45 && (Math.abs(liveVelocity) >= 0.45 || range >= 0.85))
                || (Math.abs(fastVelocity) >= 1.35 && range >= 0.75);
        boolean pass = liquidityPass
                && score >= minimumDiscoveryScore
                && ignitionPass
                && (relativeVolume >= minimumRelativeVolume
                || dollarVolume >= adaptiveDollarVolumeTarget(latest.close) * 1.10
                || Math.abs(fastVelocity) >= 1.50);
        if (requireHardIgnition) {
            pass = pass && best.passesHardGate();
        }

        return new MomentumDiscoveryProfile(symbol, pass, score, ignitionScore, latest.volume,
                liveVelocity, fastVelocity, range, dollarVolumeScore, relativeVolume, dollarVolume,
                "discoveryScore=" + fmt(score)
                        + " ignitionScore=" + fmt(ignitionScore)
                        + " rvol=" + fmt(relativeVolume)
                        + " latestVolume=" + latest.volume
                        + " dollarVolume=" + fmt(dollarVolume)
                        + " liveVelocity=" + fmt(liveVelocity) + "%"
                        + " fastVelocity=" + fmt(fastVelocity) + "%"
                        + " range=" + fmt(range) + "%"
                        + " dollarVolumeScore=" + fmt(dollarVolumeScore)
                        + " liquidityPass=" + liquidityPass
                        + " hardIgnition=" + best.passesHardGate()
                        + " ignition=" + best.getReason());
    }


    private static List<Bar> mergeBars(List<Bar> a, List<Bar> b) {
        List<Bar> merged = new ArrayList<>();
        if (a != null) merged.addAll(a);
        if (b != null) merged.addAll(b);
        merged.removeIf(x -> x == null || x.close <= 0.0);
        merged.sort((x, y) -> {
            int cmp = Long.compare(x.timestamp, y.timestamp);
            if (cmp != 0) return cmp;
            return Double.compare(x.close, y.close);
        });
        List<Bar> deduped = new ArrayList<>();
        for (Bar bar : merged) {
            if (deduped.isEmpty()) {
                deduped.add(bar);
            } else {
                Bar last = deduped.get(deduped.size() - 1);
                if (last.timestamp == bar.timestamp
                        && Math.abs(last.close - bar.close) < 0.000001
                        && last.volume == bar.volume) {
                    deduped.set(deduped.size() - 1, bar);
                } else {
                    deduped.add(bar);
                }
            }
        }
        return deduped;
    }

    private static void appendIfNew(List<Bar> bars, Bar latest) {
        if (latest == null) return;
        if (!bars.isEmpty()) {
            Bar last = bars.get(bars.size() - 1);
            if (last != null && last.timestamp == latest.timestamp && Math.abs(last.close - latest.close) < 0.000001) {
                bars.set(bars.size() - 1, latest);
                return;
            }
        }
        bars.add(latest);
    }

    private static double singleBarScore(Bar b, double relativeVolume, double dollarVolume, double liquidityScore) {
        if (b == null || b.close <= 0.0) return 0.0;
        double range = singleBarRangePct(b);
        double dollarScore = dollarVolumeScore(b.close, dollarVolume);
        double rvolScore = clamp01((relativeVolume - 1.0) / 4.0);
        return clamp01(dollarScore * 0.30
                + rvolScore * 0.25
                + liquidityScore * 0.20
                + clamp01(range / 3.0) * 0.25);
    }

    private static double startupVolumeProxy(Bar latest) {
        if (latest == null) return 1.0;
        double dollarVolume = latest.close * Math.max(0L, latest.volume);
        // During startup there may be no prior bars. Treat excellent dollar
        // volume/range as a proxy for RVOL, but keep it bounded.
        return 1.0 + clamp01(dollarVolume / adaptiveDollarVolumeTarget(latest.close)) * 2.0
                + clamp01(singleBarRangePct(latest) / 2.5);
    }

    private static boolean adaptiveLiquidityPass(double price, long volume, double dollarVolume, double relativeVolume, double rangePct) {
        if (volume <= 0 || price <= 0.0) return false;
        double target = adaptiveDollarVolumeTarget(price);
        boolean dollarPass = dollarVolume >= Math.max(20_000.0, target * 0.70);
        boolean rvolPass = relativeVolume >= 1.35 && dollarVolume >= Math.max(12_000.0, target * 0.45);
        boolean earlyIgnitionPass = relativeVolume >= 2.25 && rangePct >= 1.0 && dollarVolume >= Math.max(8_000.0, target * 0.25);
        return dollarPass || rvolPass || earlyIgnitionPass;
    }

    private static double adaptiveDollarVolumeTarget(double price) {
        if (price >= 250.0) return 80_000.0;
        if (price >= 100.0) return 110_000.0;
        if (price >= 25.0) return 150_000.0;
        if (price >= 5.0) return 100_000.0;
        if (price >= 1.0) return 65_000.0;
        return 30_000.0;
    }

    private static double dollarVolumeScore(double price, double dollarVolume) {
        return clamp01(Math.log10(Math.max(1.0, dollarVolume)) / Math.log10(Math.max(50_000.0, adaptiveDollarVolumeTarget(price) * 30.0)));
    }

    private static double averagePriorVolume(List<Bar> bars, int lookback) {
        if (bars == null || bars.size() < 2) return 0.0;
        int endExclusive = bars.size() - 1;
        int start = Math.max(0, endExclusive - Math.max(1, lookback));
        double sum = 0.0;
        int count = 0;
        for (int i = start; i < endExclusive; i++) {
            Bar b = bars.get(i);
            if (b != null && b.volume > 0) {
                sum += b.volume;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double velocityPct(List<Bar> bars, int barsBack) {
        if (bars == null || bars.size() <= barsBack) return 0.0;
        Bar latest = bars.get(bars.size() - 1);
        Bar prior = bars.get(bars.size() - 1 - Math.max(1, barsBack));
        if (latest == null || prior == null || prior.close <= 0.0 || latest.close <= 0.0) return 0.0;
        return ((latest.close - prior.close) / prior.close) * 100.0;
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

    private static double singleBarRangePct(Bar b) {
        if (b == null || b.low <= 0.0 || b.high <= b.low) return 0.0;
        return ((b.high - b.low) / b.low) * 100.0;
    }

    private static String fmt(double v) { return String.format(Locale.US, "%.3f", Double.isFinite(v) ? v : 0.0); }
    private static double clamp01(double v) { return !Double.isFinite(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v)); }

    private static int intEnv(String k, int fallback) {
        try { String v = System.getenv(k); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); }
        catch (Exception e) { return fallback; }
    }
    private static long longEnv(String k, long fallback) {
        try { String v = System.getenv(k); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); }
        catch (Exception e) { return fallback; }
    }
    private static double doubleEnv(String k, double fallback) {
        try { String v = System.getenv(k); return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); }
        catch (Exception e) { return fallback; }
    }
    private static boolean boolEnv(String k, boolean fallback) {
        try { String v = System.getenv(k); return v == null || v.isBlank() ? fallback : ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()) || "yes".equalsIgnoreCase(v.trim())); }
        catch (Exception e) { return fallback; }
    }

    public static final class MomentumDiscoveryProfile {
        public final String ticker;
        public final boolean pass;
        public final double score;
        public final double ignitionScore;
        public final long latestVolume;
        public final double liveVelocityPct;
        public final double fastVelocityPct;
        public final double rangePct;
        public final double dollarVolumeScore;
        public final double relativeVolume;
        public final double dollarVolume;
        public final String reason;

        private MomentumDiscoveryProfile(String ticker, boolean pass, double score, double ignitionScore, long latestVolume,
                                         double liveVelocityPct, double fastVelocityPct, double rangePct,
                                         double dollarVolumeScore, double relativeVolume, double dollarVolume, String reason) {
            this.ticker = ticker;
            this.pass = pass;
            this.score = score;
            this.ignitionScore = ignitionScore;
            this.latestVolume = latestVolume;
            this.liveVelocityPct = liveVelocityPct;
            this.fastVelocityPct = fastVelocityPct;
            this.rangePct = rangePct;
            this.dollarVolumeScore = dollarVolumeScore;
            this.relativeVolume = relativeVolume;
            this.dollarVolume = dollarVolume;
            this.reason = reason == null ? "" : reason;
        }

        private static MomentumDiscoveryProfile reject(String ticker, String reason) {
            return new MomentumDiscoveryProfile(ticker, false, 0.0, 0.0, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, reason);
        }
    }
}
