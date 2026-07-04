package com.bot.scanner;

import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.TradeDirection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry timing layer between discovery and order routing.
 *
 * Discovery answers: "is this symbol interesting enough to watch?"
 * Execution routing must answer a different question: "is now the right entry?"
 * This agent stages candidates and returns five-state decisions:
 * BUY_NOW, WAIT, SHORT_NOW, WAIT_FOR_REVERSAL, or REJECT.
 *
 * Non-news longs wait for a pullback recovery/reclaim instead of buying while a
 * mover is still falling. Short candidates wait for exhaustion from a peak and a
 * lower-high/support break instead of shorting the first green spike. Breaking
 * news spikes can bypass this in the news pipeline; the full-market scanner uses
 * this stricter non-news timing discipline.
 */
public final class EntryStagingAgent {

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final SharedRollingBarHistoryService sharedBarHistory = SharedRollingBarHistoryService.getInstance();
    private final OpportunityContextRegistry opportunityContextRegistry = OpportunityContextRegistry.getInstance();
    private final TechnicalFeatureService technicalFeatureService = TechnicalFeatureService.getInstance();
    private final long ttlMs;
    private final int minBarsBeforeNonNewsEntry;
    private final int minBarsBeforeAiTiming;
    private final double buyNowScore;
    private final double shortNowScore;
    private final double minRecoveryPct;
    private final double minBreakoutPct;
    private final double minPeakFadePct;
    private final double minSupportBreakPct;
    private final boolean verbose;

    public EntryStagingAgent() {
        this.ttlMs = longEnv("ENTRY_STAGING_TTL_MS", 12 * 60_000L);
        this.minBarsBeforeNonNewsEntry = intEnv("ENTRY_STAGING_MIN_BARS", 5);
        this.minBarsBeforeAiTiming = intEnv("ENTRY_STAGING_MIN_AI_BARS", Math.max(5, this.minBarsBeforeNonNewsEntry));
        this.buyNowScore = doubleEnv("ENTRY_STAGING_BUY_NOW_SCORE", 0.50);
        this.shortNowScore = doubleEnv("ENTRY_STAGING_SHORT_NOW_SCORE", 0.50);
        this.minRecoveryPct = doubleEnv("ENTRY_STAGING_MIN_RECOVERY_PCT", 0.18);
        this.minBreakoutPct = doubleEnv("ENTRY_STAGING_MIN_BREAKOUT_PCT", 0.12);
        this.minPeakFadePct = doubleEnv("ENTRY_STAGING_MIN_PEAK_FADE_PCT", 0.35);
        this.minSupportBreakPct = doubleEnv("ENTRY_STAGING_MIN_SUPPORT_BREAK_PCT", 0.15);
        this.verbose = boolEnv("ENTRY_STAGING_VERBOSE", true);
    }

    public Decision assess(String ticker,
                           Bar latest,
                           MarketDataCache cache,
                           MomentumDiscoveryEngine.MomentumDiscoveryProfile profile,
                           boolean breakingNewsImmediateEligible,
                           TradeDirection preferredDirection) {
        String symbol = normalize(ticker);
        if (symbol.isEmpty() || latest == null || latest.close <= 0.0 || profile == null) {
            return Decision.reject(symbol, "missing ticker/bar/profile");
        }

        pruneExpired();
        State state = states.computeIfAbsent(symbol, State::new);
        state.lastUpdatedAt = System.currentTimeMillis();
        TechnicalFeatureSnapshot sharedSnapshot = opportunityContextRegistry.observeBar(symbol, latest, "ENTRY_STAGING_AGENT");
        boolean sharedNewBar = sharedBarHistory.observe(symbol, latest);
        boolean newBar = state.observe(latest);
        if (!newBar && state.observedBars > 0) {
            // Same bar was already staged. Keep the state warm but do not let a
            // repeated snapshot masquerade as a new timing observation.
        }
        state.bestScore = Math.max(state.bestScore, profile.score);
        state.bestRvol = Math.max(state.bestRvol, profile.relativeVolume);
        state.bestDollarVolume = Math.max(state.bestDollarVolume, profile.dollarVolume);
        state.lastProfileReason = profile.reason;
        if (latest.high > 0) state.sessionHigh = Math.max(state.sessionHigh, latest.high);
        if (latest.low > 0) state.sessionLow = Math.min(state.sessionLow, latest.low);
        if (state.firstPrice <= 0.0) state.firstPrice = latest.close;
        state.lastPrice = latest.close;

        List<Bar> bars = recentBars(symbol, cache, latest, 24);
        bars = mergeBars(bars, sharedBarHistory.recent(symbol, 60));
        bars = mergeBars(bars, state.recentBars(24));
        Tape tape = Tape.from(bars, latest);
        state.lifecycleStage = deriveLifecycleStage(state, tape, profile, directionOrDefault(preferredDirection));
        TradeDirection direction = preferredDirection != null ? preferredDirection : inferDirection(profile, tape);
        state.lifecycleStage = deriveLifecycleStage(state, tape, profile, direction);

        if (breakingNewsImmediateEligible && profile.score >= doubleEnv("ENTRY_STAGING_NEWS_SPIKE_MIN_SCORE", 0.42)
                && profile.relativeVolume >= doubleEnv("ENTRY_STAGING_NEWS_SPIKE_MIN_RVOL", 1.20)
                && profile.dollarVolume >= adaptiveDollarVolumeTarget(latest.close) * 0.35) {
            Decision d = direction == TradeDirection.SHORT_STOCK
                    ? Decision.shortNow(symbol, "breaking-news downside spike bypass")
                    : Decision.buyNow(symbol, "breaking-news spike bypass");
            log(symbol, d, state, profile, tape, direction);
            return d;
        }

        int uniqueBars = Math.max(Math.max(Math.max(state.observedBars, sharedBarHistory.count(symbol)), sharedSnapshot.bars), countUniqueBars(bars));
        if (uniqueBars < minBarsBeforeNonNewsEntry) {
            Decision d = direction == TradeDirection.SHORT_STOCK
                    ? Decision.waitForReversal(symbol, "staging: observing tape before short timing stage=" + state.lifecycleStage + " bars=" + uniqueBars + "/" + minBarsBeforeNonNewsEntry)
                    : Decision.wait(symbol, "staging: observing tape before long timing stage=" + state.lifecycleStage + " bars=" + uniqueBars + "/" + minBarsBeforeNonNewsEntry);
            log(symbol, d, state, profile, tape, direction);
            return d;
        }

        if (uniqueBars < minBarsBeforeAiTiming && !hasClearEarlyTrigger(direction, tape, profile)) {
            Decision d = direction == TradeDirection.SHORT_STOCK
                    ? Decision.waitForReversal(symbol, "staging: waiting for AI-ready timing window stage=" + state.lifecycleStage + " bars=" + uniqueBars + "/" + minBarsBeforeAiTiming)
                    : Decision.wait(symbol, "staging: waiting for AI-ready timing window stage=" + state.lifecycleStage + " bars=" + uniqueBars + "/" + minBarsBeforeAiTiming);
            log(symbol, d, state, profile, tape, direction);
            return d;
        }

        boolean strongTape = profile.score >= buyNowScore
                || profile.relativeVolume >= 2.50
                || profile.dollarVolume >= adaptiveDollarVolumeTarget(latest.close) * 1.25;
        if (!strongTape && state.bestScore < doubleEnv("ENTRY_STAGING_KEEP_MIN_BEST_SCORE", 0.38)) {
            Decision d = Decision.reject(symbol, "staging: weak tape and no improving score");
            log(symbol, d, state, profile, tape, direction);
            return d;
        }

        if (direction == TradeDirection.SHORT_STOCK) {
            boolean peaked = tape.drawdownFromHighPct >= minPeakFadePct || tape.lastLowerHigh;
            boolean supportBreak = tape.breakingDown || tape.fastVelocityPct <= -minSupportBreakPct;
            boolean stillRippingUp = tape.fastVelocityPct > 0.35 && !tape.breakingDown;
            if (profile.score >= shortNowScore && peaked && supportBreak && !stillRippingUp) {
                state.lifecycleStage = LifecycleStage.EXECUTE_SHORT;
                Decision d = Decision.shortNow(symbol, "short timing: peak fade + support break confirmed stage=" + state.lifecycleStage);
                log(symbol, d, state, profile, tape, direction);
                return d;
            }
            Decision d = Decision.waitForReversal(symbol,
                    "short timing: waiting for peak reversal stage=" + state.lifecycleStage + " drawdown=" + fmt(tape.drawdownFromHighPct)
                            + "% fast=" + fmt(tape.fastVelocityPct) + "% breakdown=" + tape.breakingDown);
            log(symbol, d, state, profile, tape, direction);
            return d;
        }

        boolean recovering = tape.recoveredFromLowPct >= minRecoveryPct || tape.fastVelocityPct >= minRecoveryPct;
        boolean breakout = tape.breakingOut || tape.priceVsVwapPct >= minBreakoutPct;
        boolean stillDropping = tape.liveVelocityPct < -0.10 || tape.fastVelocityPct < -0.18;
        boolean exhausted = tape.drawdownFromHighPct > doubleEnv("ENTRY_STAGING_LONG_MAX_DRAWDOWN_FROM_HIGH_PCT", 3.50)
                && !recovering;

        if (profile.score >= buyNowScore && recovering && breakout && !stillDropping && !exhausted) {
            state.lifecycleStage = LifecycleStage.EXECUTE_LONG;
            Decision d = Decision.buyNow(symbol, "long timing: pullback recovery/reclaim confirmed stage=" + state.lifecycleStage);
            log(symbol, d, state, profile, tape, direction);
            return d;
        }

        Decision d = Decision.wait(symbol,
                "long timing: waiting for recovery/reclaim stage=" + state.lifecycleStage + " recovery=" + fmt(tape.recoveredFromLowPct)
                        + "% fast=" + fmt(tape.fastVelocityPct)
                        + "% breakout=" + tape.breakingOut
                        + " stillDropping=" + stillDropping);
        log(symbol, d, state, profile, tape, direction);
        return d;
    }

    public void forget(String ticker) {
        String symbol = normalize(ticker);
        if (!symbol.isEmpty()) states.remove(symbol);
    }

    public int activeCount() {
        pruneExpired();
        return states.size();
    }

    public List<String> topSymbols(int limit) {
        pruneExpired();
        int max = Math.max(0, limit);
        List<State> snapshot = new ArrayList<>(states.values());
        snapshot.sort(Comparator
                .comparingDouble((State s) -> Math.max(s.bestScore, s.bestRvol / 4.0)).reversed()
                .thenComparingLong(s -> -s.lastUpdatedAt));
        List<String> result = new ArrayList<>();
        for (State state : snapshot) {
            if (result.size() >= max) break;
            if (state != null && state.ticker != null && !state.ticker.isBlank()) {
                result.add(state.ticker);
            }
        }
        return result;
    }

    private void log(String symbol, Decision decision, State state,
                     MomentumDiscoveryEngine.MomentumDiscoveryProfile profile,
                     Tape tape,
                     TradeDirection direction) {
        if (!verbose || decision == null) return;
        System.out.println("ENTRY STAGING DECISION: ticker=" + symbol
                + " action=" + decision.action
                + " direction=" + direction
                + " stage=" + state.lifecycleStage
                + " bars=" + Math.max(state.observedBars, sharedBarHistory.count(symbol))
                + " ctx=" + System.identityHashCode(OpportunityContextRegistry.getInstance().getOrCreate(symbol))
                + " " + sharedBarHistory.diagnostics(symbol)
                + " score=" + fmt(profile.score)
                + " best=" + fmt(state.bestScore)
                + " rvol=" + fmt(profile.relativeVolume)
                + " live=" + fmt(tape.liveVelocityPct) + "%"
                + " fast=" + fmt(tape.fastVelocityPct) + "%"
                + " recovery=" + fmt(tape.recoveredFromLowPct) + "%"
                + " drawdown=" + fmt(tape.drawdownFromHighPct) + "%"
                + " breakout=" + tape.breakingOut
                + " breakdown=" + tape.breakingDown
                + " reason=" + decision.reason);
    }

    private static TradeDirection inferDirection(MomentumDiscoveryEngine.MomentumDiscoveryProfile profile, Tape tape) {
        if (profile != null && (profile.liveVelocityPct < -0.20 || profile.fastVelocityPct < -0.35)) {
            return TradeDirection.SHORT_STOCK;
        }
        if (tape != null && tape.fastVelocityPct < -0.35) {
            return TradeDirection.SHORT_STOCK;
        }
        return TradeDirection.LONG_STOCK;
    }

    private static TradeDirection directionOrDefault(TradeDirection direction) {
        return direction == null ? TradeDirection.LONG_STOCK : direction;
    }

    private static boolean hasClearEarlyTrigger(TradeDirection direction, Tape tape, MomentumDiscoveryEngine.MomentumDiscoveryProfile profile) {
        if (tape == null || profile == null) return false;
        if (direction == TradeDirection.SHORT_STOCK) {
            return profile.score >= 0.62 && tape.drawdownFromHighPct >= 0.45 && tape.fastVelocityPct <= -0.30 && tape.breakingDown;
        }
        return profile.score >= 0.62 && tape.fastVelocityPct >= 0.35 && tape.recoveredFromLowPct >= 0.25 && tape.breakingOut;
    }

    private static LifecycleStage deriveLifecycleStage(State state, Tape tape, MomentumDiscoveryEngine.MomentumDiscoveryProfile profile, TradeDirection direction) {
        int bars = state == null ? 0 : state.observedBars;
        if (bars <= 0) return LifecycleStage.DISCOVERED;
        if (bars < 3) return LifecycleStage.OBSERVING;
        if (direction == TradeDirection.SHORT_STOCK) {
            if (tape != null && tape.drawdownFromHighPct >= 0.35 && tape.breakingDown) return LifecycleStage.REVERSAL_READY;
            if (tape != null && tape.drawdownFromHighPct >= 0.20) return LifecycleStage.PULLBACK;
            if (tape != null && tape.fastVelocityPct < -0.20) return LifecycleStage.ACCELERATING;
            return LifecycleStage.OBSERVING;
        }
        if (tape != null && (tape.recoveredFromLowPct >= 0.18 || tape.fastVelocityPct >= 0.18) && tape.breakingOut) return LifecycleStage.RECOVERY;
        if (tape != null && tape.drawdownFromHighPct >= 0.25 && tape.fastVelocityPct <= 0.05) return LifecycleStage.PULLBACK;
        if (tape != null && tape.fastVelocityPct >= 0.20) return LifecycleStage.ACCELERATING;
        if (profile != null && profile.score >= 0.50) return LifecycleStage.OBSERVING;
        return LifecycleStage.OBSERVING;
    }

    private static int countUniqueBars(List<Bar> bars) {
        if (bars == null || bars.isEmpty()) return 0;
        long lastTs = Long.MIN_VALUE;
        double lastClose = Double.NaN;
        int count = 0;
        for (Bar b : bars) {
            if (b == null || b.close <= 0) continue;
            if (b.timestamp != lastTs || Math.abs(b.close - lastClose) > 0.000001) {
                count++;
                lastTs = b.timestamp;
                lastClose = b.close;
            }
        }
        return count;
    }

    private static List<Bar> mergeBars(List<Bar> a, List<Bar> b) {
        List<Bar> merged = new ArrayList<>();
        if (a != null) merged.addAll(a);
        if (b != null) merged.addAll(b);
        merged.removeIf(x -> x == null || x.close <= 0.0);
        merged.sort(Comparator.comparingLong(x -> x.timestamp));
        List<Bar> deduped = new ArrayList<>();
        for (Bar bar : merged) {
            if (deduped.isEmpty()) {
                deduped.add(bar);
            } else {
                Bar last = deduped.get(deduped.size() - 1);
                if (last.timestamp == bar.timestamp && Math.abs(last.close - bar.close) < 0.000001) {
                    deduped.set(deduped.size() - 1, bar);
                } else {
                    deduped.add(bar);
                }
            }
        }
        return deduped;
    }

    private static List<Bar> recentBars(String ticker, MarketDataCache cache, Bar latest, int limit) {
        List<Bar> bars = new ArrayList<>();
        if (cache != null && ticker != null && !ticker.isBlank()) {
            bars.addAll(cache.recentBars(ticker, limit));
        }
        if (latest != null) {
            if (!bars.isEmpty()) {
                Bar last = bars.get(bars.size() - 1);
                if (last != null && last.timestamp == latest.timestamp && Math.abs(last.close - latest.close) < 0.000001) {
                    bars.set(bars.size() - 1, latest);
                } else {
                    bars.add(latest);
                }
            } else {
                bars.add(latest);
            }
        }
        bars.removeIf(b -> b == null || b.close <= 0.0);
        bars.sort(Comparator.comparingLong(b -> b.timestamp));
        if (bars.size() > limit) {
            return new ArrayList<>(bars.subList(bars.size() - limit, bars.size()));
        }
        return bars;
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> now - e.getValue().createdAt > ttlMs);
    }

    private static String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }

    private static double adaptiveDollarVolumeTarget(double price) {
        if (price >= 250.0) return 80_000.0;
        if (price >= 100.0) return 110_000.0;
        if (price >= 25.0) return 150_000.0;
        if (price >= 5.0) return 100_000.0;
        if (price >= 1.0) return 65_000.0;
        return 30_000.0;
    }

    private static double pct(double from, double to) {
        if (from <= 0.0 || to <= 0.0) return 0.0;
        return ((to - from) / from) * 100.0;
    }

    private static double fmtDouble(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.3f", fmtDouble(value));
    }

    private static int intEnv(String key, int fallback) {
        try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static long longEnv(String key, long fallback) {
        try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static double doubleEnv(String key, double fallback) {
        try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static boolean boolEnv(String key, boolean fallback) {
        try {
            String v = System.getenv(key);
            return v == null || v.isBlank() ? fallback : ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()) || "yes".equalsIgnoreCase(v.trim()));
        } catch (Exception e) { return fallback; }
    }

    public enum Action {
        BUY_NOW,
        WAIT,
        SHORT_NOW,
        WAIT_FOR_REVERSAL,
        REJECT
    }

    public static final class Decision {
        public final String ticker;
        public final Action action;
        public final String reason;

        private Decision(String ticker, Action action, String reason) {
            this.ticker = ticker == null ? "" : ticker;
            this.action = action == null ? Action.WAIT : action;
            this.reason = reason == null ? "" : reason;
        }

        public boolean routeNow() {
            return action == Action.BUY_NOW || action == Action.SHORT_NOW;
        }

        public static Decision buyNow(String ticker, String reason) { return new Decision(ticker, Action.BUY_NOW, reason); }
        public static Decision wait(String ticker, String reason) { return new Decision(ticker, Action.WAIT, reason); }
        public static Decision shortNow(String ticker, String reason) { return new Decision(ticker, Action.SHORT_NOW, reason); }
        public static Decision waitForReversal(String ticker, String reason) { return new Decision(ticker, Action.WAIT_FOR_REVERSAL, reason); }
        public static Decision reject(String ticker, String reason) { return new Decision(ticker, Action.REJECT, reason); }
    }

    private static final class State {
        final String ticker;
        final long createdAt = System.currentTimeMillis();
        long lastUpdatedAt = createdAt;
        int observedBars;
        double bestScore;
        double bestRvol;
        double bestDollarVolume;
        double firstPrice;
        double lastPrice;
        double sessionHigh;
        double sessionLow = Double.MAX_VALUE;
        String lastProfileReason = "";
        LifecycleStage lifecycleStage = LifecycleStage.DISCOVERED;
        final List<Bar> bars = new ArrayList<>();

        State(String ticker) { this.ticker = ticker; }

        boolean observe(Bar bar) {
            if (bar == null || bar.close <= 0.0) return false;
            if (!bars.isEmpty()) {
                Bar last = bars.get(bars.size() - 1);
                if (last.timestamp == bar.timestamp && Math.abs(last.close - bar.close) < 0.000001) {
                    bars.set(bars.size() - 1, bar);
                    return false;
                }
            }
            bars.add(bar);
            if (bars.size() > 60) {
                bars.remove(0);
            }
            observedBars++;
            return true;
        }

        List<Bar> recentBars(int limit) {
            int max = Math.max(1, limit);
            if (bars.size() <= max) return new ArrayList<>(bars);
            return new ArrayList<>(bars.subList(bars.size() - max, bars.size()));
        }
    }

    public enum LifecycleStage {
        DISCOVERED,
        OBSERVING,
        ACCELERATING,
        PULLBACK,
        RECOVERY,
        REVERSAL_READY,
        EXECUTE_LONG,
        EXECUTE_SHORT,
        REJECTED
    }

    private static final class Tape {
        final double liveVelocityPct;
        final double fastVelocityPct;
        final double recoveredFromLowPct;
        final double drawdownFromHighPct;
        final double priceVsVwapPct;
        final boolean breakingOut;
        final boolean breakingDown;
        final boolean lastLowerHigh;

        Tape(double liveVelocityPct, double fastVelocityPct, double recoveredFromLowPct,
             double drawdownFromHighPct, double priceVsVwapPct, boolean breakingOut,
             boolean breakingDown, boolean lastLowerHigh) {
            this.liveVelocityPct = liveVelocityPct;
            this.fastVelocityPct = fastVelocityPct;
            this.recoveredFromLowPct = recoveredFromLowPct;
            this.drawdownFromHighPct = drawdownFromHighPct;
            this.priceVsVwapPct = priceVsVwapPct;
            this.breakingOut = breakingOut;
            this.breakingDown = breakingDown;
            this.lastLowerHigh = lastLowerHigh;
        }

        static Tape from(List<Bar> bars, Bar latest) {
            if (latest == null || latest.close <= 0.0) {
                return new Tape(0, 0, 0, 0, 0, false, false, false);
            }
            List<Bar> safe = bars == null ? new ArrayList<>() : new ArrayList<>(bars);
            safe.removeIf(b -> b == null || b.close <= 0.0);
            safe.sort(Comparator.comparingLong(b -> b.timestamp));
            if (safe.isEmpty()) safe.add(latest);

            Bar last = safe.get(safe.size() - 1);
            Bar prior1 = safe.size() >= 2 ? safe.get(safe.size() - 2) : last;
            Bar prior3 = safe.size() >= 4 ? safe.get(safe.size() - 4) : safe.get(0);
            double live = pct(prior1.close, last.close);
            double fast = pct(prior3.close, last.close);
            if (Math.abs(live) < 0.000001) live = intrabarVelocity(last);
            if (Math.abs(fast) < 0.000001) fast = live;

            double high = 0.0;
            double low = Double.MAX_VALUE;
            double pv = 0.0;
            double vol = 0.0;
            int start = Math.max(0, safe.size() - 10);
            for (int i = start; i < safe.size(); i++) {
                Bar b = safe.get(i);
                if (b.high > 0) high = Math.max(high, b.high);
                if (b.low > 0) low = Math.min(low, b.low);
                long v = Math.max(0L, b.volume);
                pv += b.close * v;
                vol += v;
            }
            if (high <= 0) high = last.close;
            if (low == Double.MAX_VALUE || low <= 0) low = last.close;
            double vwap = vol > 0 ? pv / vol : last.close;
            double recovered = pct(low, last.close);
            double drawdown = high > 0 ? ((high - last.close) / high) * 100.0 : 0.0;
            double vwapDist = pct(vwap, last.close);
            boolean breakHigh = safe.size() >= 3 && (last.close >= high * 0.999 || (prior1.high > 0 && last.close > prior1.high));
            boolean breakLow = safe.size() >= 3 && (last.close <= low * 1.001 || (prior1.low > 0 && last.close < prior1.low));
            boolean lowerHigh = false;
            if (safe.size() >= 3) {
                Bar a = safe.get(safe.size() - 3);
                Bar b = safe.get(safe.size() - 2);
                Bar c = safe.get(safe.size() - 1);
                lowerHigh = b.high > 0 && c.high > 0 && c.high < b.high && b.high <= Math.max(a.high, b.high);
            }
            return new Tape(live, fast, recovered, drawdown, vwapDist, breakHigh, breakLow, lowerHigh);
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
