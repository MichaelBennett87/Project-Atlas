package com.bot.scalping;

import com.bot.intelligence.ParabolicTopVolumeTracker;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Bar;
import com.bot.scanner.SharedRollingBarHistoryService;
import com.bot.scanner.TechnicalFeatureService;
import com.bot.scanner.TechnicalFeatureSnapshot;

import java.util.List;
import java.util.Locale;

/**
 * Central mission policy for the project: self-improving AI scalping software
 * that prioritizes liquidity, volume, and violent intraday movement above all
 * slower indicators. Other indicators are only supporting evidence.
 */
public final class VolumeFirstScalpingPolicy {
    public static final String MISSION = "VOLUME_FIRST_AI_SCALPING: prioritize liquidity, top-volume rank, dollar volume, RVOL, range expansion, and violent price movement. Buy dip-recovery waves, short apex/failure waves, exit quickly, and learn nightly to scalp better tomorrow.";

    private static final ParabolicTopVolumeTracker TOP_VOLUME = ParabolicTopVolumeTracker.getInstance();
    private static final TechnicalFeatureService FEATURES = TechnicalFeatureService.getInstance();
    private static final SharedRollingBarHistoryService BARS = SharedRollingBarHistoryService.getInstance();

    private VolumeFirstScalpingPolicy() {}

    public static boolean isScalpingStrategy(String strategyName) {
        String s = strategyName == null ? "" : strategyName.trim().toUpperCase(Locale.ROOT);
        return s.contains("TOP_VOLUME")
                || s.contains("PARABOLIC")
                || s.contains("MOMENTUM")
                || s.contains("VWAP")
                || s.contains("BREAKOUT")
                || s.contains("BREAKDOWN")
                || s.contains("RECLAIM")
                || s.contains("PULLBACK")
                || s.contains("REVERSAL")
                || s.contains("SQUEEZE")
                || s.contains("RANGE_EXPANSION")
                || s.contains("LOW_PRICE_MOMENTUM");
    }

    public static boolean isTopVolumeFastLane(StrategySignal signal) {
        return signal != null && "TOP_VOLUME_RECOVERY_SCALPER".equalsIgnoreCase(signal.getStrategyName());
    }

    public static ScalpingTape tape(StrategyContext context) {
        String ticker = context == null ? "" : context.getTicker();
        TechnicalFeatureSnapshot f = FEATURES.snapshot(ticker);
        List<Bar> recent = BARS.recent(ticker, 40);
        if ((recent == null || recent.isEmpty()) && context != null) {
            recent = context.getBars();
            f = FEATURES.fromBars(ticker, recent);
        }
        double price = f.latestPrice > 0 ? f.latestPrice : (context == null ? 0.0 : context.getLastPrice());
        long volume = Math.max(0L, f.latestVolume);
        double dollar = Math.max(f.dollarVolume, TOP_VOLUME.latestDollarVolume(ticker));
        double absVelocity = Math.max(Math.abs(f.oneBarVelocityPct), Math.abs(f.threeBarVelocityPct));
        double violent = violentMoveScore(f, dollar);
        boolean top = TOP_VOLUME.isTopVolumeTicker(ticker, envInt("VOLUME_FIRST_TOP_RANK", 100));
        int bars = f.bars;
        return new ScalpingTape(ticker, price, volume, dollar, f.relativeVolume, absVelocity,
                f.rangePct, f.atrPct, f.oneBarVelocityPct, f.threeBarVelocityPct, f.accelerationPct,
                f.priceVsVwapPct, top, bars, violent, f.breakingOut, f.breakingDown);
    }

    public static boolean hasEnoughLiquidity(StrategyContext context) {
        ScalpingTape t = tape(context);
        return t.topVolume
                || t.dollarVolume >= envDouble("VOLUME_FIRST_MIN_DOLLAR_VOLUME", 75_000.0)
                || t.volume >= envLong("VOLUME_FIRST_MIN_LATEST_VOLUME", 10_000L);
    }

    public static boolean hasViolentMovement(StrategyContext context) {
        ScalpingTape t = tape(context);
        return t.violentScore >= envDouble("VOLUME_FIRST_MIN_VIOLENT_SCORE", 0.30)
                || t.absVelocityPct >= envDouble("VOLUME_FIRST_MIN_ABS_VELOCITY_PCT", 0.05)
                || t.rangePct >= envDouble("VOLUME_FIRST_MIN_RANGE_PCT", 0.08)
                || t.relativeVolume >= envDouble("VOLUME_FIRST_MIN_RVOL", 1.15);
    }

    public static double signalPriorityMultiplier(StrategyContext context, StrategySignal signal) {
        ScalpingTape t = tape(context);
        double m = 0.55;
        if (t.topVolume) m += 0.42;
        m += Math.min(0.45, Math.log10(Math.max(1.0, t.dollarVolume)) / 7.0);
        m += Math.min(0.35, Math.max(0.0, t.relativeVolume - 0.80) / 4.0);
        m += Math.min(0.35, t.absVelocityPct / 1.00);
        m += Math.min(0.25, t.rangePct / 2.00);
        if (isScalpingStrategy(signal == null ? "" : signal.getStrategyName())) m += 0.15;
        return clamp(m, 0.25, 2.20);
    }

    public static double confidenceFloor(StrategySignal signal) {
        if (isTopVolumeFastLane(signal)) return envDouble("TOP_VOLUME_FAST_LANE_MASTER_CONFIDENCE_FLOOR", 0.48);
        if (isScalpingStrategy(signal == null ? "" : signal.getStrategyName())) return envDouble("VOLUME_FIRST_SCALP_MASTER_CONFIDENCE_FLOOR", 0.52);
        return envDouble("VOLUME_FIRST_NON_SCALP_CONFIDENCE_FLOOR", 0.58);
    }

    public static String decisionInstruction() {
        return MISSION + " Treat liquidity/volume/violent movement as the primary edge. News, RSI, MACD, VWAP, catalysts, and fundamentals are bonus/context only unless they affect immediate scalp probability. Prefer repeated top-volume wave trades after exits when volume persists.";
    }

    public static String diagnostics(StrategyContext context) {
        ScalpingTape t = tape(context);
        return "volumeFirst{topVolume=" + t.topVolume +
                ",vol=" + t.volume +
                ",$vol=" + fmt(t.dollarVolume) +
                ",rvol=" + fmt(t.relativeVolume) +
                ",absVel=" + fmt(t.absVelocityPct) + "%" +
                ",range=" + fmt(t.rangePct) + "%" +
                ",violent=" + fmt(t.violentScore) +
                ",bars=" + t.bars + "}";
    }

    private static double violentMoveScore(TechnicalFeatureSnapshot f, double dollarVolume) {
        if (f == null) return 0.0;
        double score = 0.0;
        score += Math.min(0.30, Math.log10(Math.max(1.0, dollarVolume)) / 8.0 * 0.30);
        score += Math.min(0.25, Math.max(0.0, f.relativeVolume) / 5.0 * 0.25);
        score += Math.min(0.25, Math.max(Math.abs(f.oneBarVelocityPct), Math.abs(f.threeBarVelocityPct)) / 1.20 * 0.25);
        score += Math.min(0.20, f.rangePct / 2.50 * 0.20);
        return clamp(score, 0.0, 1.0);
    }

    private static double clamp(double v, double lo, double hi) {
        if (!Double.isFinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.3f", Double.isFinite(v) ? v : 0.0);
    }

    private static double envDouble(String key, double fallback) {
        try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); }
        catch (Exception e) { return fallback; }
    }
    private static long envLong(String key, long fallback) {
        try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); }
        catch (Exception e) { return fallback; }
    }
    private static int envInt(String key, int fallback) {
        try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); }
        catch (Exception e) { return fallback; }
    }

    public static final class ScalpingTape {
        public final String ticker;
        public final double price;
        public final long volume;
        public final double dollarVolume;
        public final double relativeVolume;
        public final double absVelocityPct;
        public final double rangePct;
        public final double atrPct;
        public final double oneBarVelocityPct;
        public final double threeBarVelocityPct;
        public final double accelerationPct;
        public final double priceVsVwapPct;
        public final boolean topVolume;
        public final int bars;
        public final double violentScore;
        public final boolean breakingOut;
        public final boolean breakingDown;

        private ScalpingTape(String ticker, double price, long volume, double dollarVolume, double relativeVolume,
                             double absVelocityPct, double rangePct, double atrPct, double oneBarVelocityPct,
                             double threeBarVelocityPct, double accelerationPct, double priceVsVwapPct,
                             boolean topVolume, int bars, double violentScore, boolean breakingOut, boolean breakingDown) {
            this.ticker = ticker;
            this.price = price;
            this.volume = volume;
            this.dollarVolume = dollarVolume;
            this.relativeVolume = relativeVolume;
            this.absVelocityPct = absVelocityPct;
            this.rangePct = rangePct;
            this.atrPct = atrPct;
            this.oneBarVelocityPct = oneBarVelocityPct;
            this.threeBarVelocityPct = threeBarVelocityPct;
            this.accelerationPct = accelerationPct;
            this.priceVsVwapPct = priceVsVwapPct;
            this.topVolume = topVolume;
            this.bars = bars;
            this.violentScore = violentScore;
            this.breakingOut = breakingOut;
            this.breakingDown = breakingDown;
        }
    }
}
