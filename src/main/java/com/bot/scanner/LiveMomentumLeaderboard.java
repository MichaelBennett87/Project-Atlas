package com.bot.scanner;

import com.bot.model.Bar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Rank-first view of market momentum.
 *
 * The scanner should not ask only "did this symbol pass a fixed threshold?".
 * In quiet or data-constrained periods that can return zero candidates forever.
 * This helper keeps discovery rank-based: score every observable bar, sort the
 * market, print the leaders, and let only the strict execution gate place orders.
 */
public final class LiveMomentumLeaderboard {

    public LeaderboardResult rank(List<Entry> entries, int discoveryLimit, int executionLimit) {
        List<Entry> safe = new ArrayList<>();
        if (entries != null) {
            for (Entry e : entries) {
                if (e != null && e.ticker != null && !e.ticker.isBlank() && e.bar != null && e.bar.close > 0.0) {
                    safe.add(e);
                }
            }
        }
        safe.sort(Comparator.comparingDouble((Entry e) -> e.score).reversed());
        int discovery = Math.min(Math.max(0, discoveryLimit), safe.size());
        int execution = Math.min(Math.max(0, executionLimit), safe.size());
        return new LeaderboardResult(safe, discovery, execution);
    }

    public String summarize(List<Entry> entries, int limit) {
        if (entries == null || entries.isEmpty() || limit <= 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size() && i < limit; i++) {
            if (i > 0) sb.append(", ");
            Entry e = entries.get(i);
            sb.append(i + 1)
                    .append(".")
                    .append(e.ticker)
                    .append(":score=").append(fmt(e.score))
                    .append(" rvol=").append(fmt(e.relativeVolume))
                    .append(" vel=").append(fmt(e.liveVelocityPct)).append("%")
                    .append(" fast=").append(fmt(e.fastVelocityPct)).append("%")
                    .append(" range=").append(fmt(e.rangePct)).append("%")
                    .append(" $vol=").append(fmt(e.dollarVolume))
                    .append(e.fresh ? " fresh" : " stale")
                    .append(e.executionReady ? " exec" : " discover");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.3f", Double.isFinite(value) ? value : 0.0);
    }

    public static final class Entry {
        public final String ticker;
        public final Bar bar;
        public final double score;
        public final double relativeVolume;
        public final double liveVelocityPct;
        public final double fastVelocityPct;
        public final double rangePct;
        public final double dollarVolume;
        public final boolean fresh;
        public final boolean executionReady;
        public final boolean tracked;
        public final String reason;

        public Entry(String ticker,
                     Bar bar,
                     double score,
                     double relativeVolume,
                     double liveVelocityPct,
                     double fastVelocityPct,
                     double rangePct,
                     double dollarVolume,
                     boolean fresh,
                     boolean executionReady,
                     boolean tracked,
                     String reason) {
            this.ticker = ticker;
            this.bar = bar;
            this.score = score;
            this.relativeVolume = relativeVolume;
            this.liveVelocityPct = liveVelocityPct;
            this.fastVelocityPct = fastVelocityPct;
            this.rangePct = rangePct;
            this.dollarVolume = dollarVolume;
            this.fresh = fresh;
            this.executionReady = executionReady;
            this.tracked = tracked;
            this.reason = reason == null ? "" : reason;
        }
    }

    public static final class LeaderboardResult {
        public final List<Entry> sorted;
        public final int discoveryCount;
        public final int executionCount;

        private LeaderboardResult(List<Entry> sorted, int discoveryCount, int executionCount) {
            this.sorted = sorted;
            this.discoveryCount = discoveryCount;
            this.executionCount = executionCount;
        }
    }
}
