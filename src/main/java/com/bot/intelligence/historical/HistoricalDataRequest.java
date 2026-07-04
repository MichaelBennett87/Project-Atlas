package com.bot.intelligence.historical;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A provider-neutral historical REST request issued by an analyst/research agent.
 *
 * These requests are intended for the offline/nightly research phase, not for the
 * latency-sensitive live strategy path.  The router can fulfill them through
 * Polygon, Alpha Vantage, or the local cache depending on provider availability
 * and rate-limit budget.
 */
public final class HistoricalDataRequest {
    public enum Provider { AUTO, POLYGON, ALPHA_VANTAGE, LOCAL_CACHE }
    public enum DataType { BARS, TRADES, QUOTES, NEWS, FUNDAMENTALS, INDICATOR, OVERVIEW }

    public final String requestingAgent;
    public final Provider provider;
    public final DataType dataType;
    public final String ticker;
    public final LocalDate from;
    public final LocalDate to;
    public final String interval;
    public final int maxRows;
    public final Map<String, String> options;

    private HistoricalDataRequest(Builder b) {
        this.requestingAgent = clean(b.requestingAgent, "UNKNOWN_AGENT");
        this.provider = b.provider == null ? Provider.AUTO : b.provider;
        this.dataType = b.dataType == null ? DataType.BARS : b.dataType;
        this.ticker = clean(b.ticker, "").toUpperCase(Locale.ROOT);
        this.from = b.from == null ? LocalDate.now().minusDays(5) : b.from;
        this.to = b.to == null ? LocalDate.now() : b.to;
        this.interval = clean(b.interval, "1min");
        this.maxRows = Math.max(1, b.maxRows <= 0 ? 50_000 : b.maxRows);
        this.options = new LinkedHashMap<>(b.options);
    }

    public String cacheKey() {
        return provider + "_" + dataType + "_" + ticker + "_" + from + "_" + to + "_" + interval;
    }

    public String summary() {
        return "agent=" + requestingAgent + " provider=" + provider + " type=" + dataType +
                " ticker=" + ticker + " from=" + from + " to=" + to + " interval=" + interval;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String requestingAgent = "UNKNOWN_AGENT";
        private Provider provider = Provider.AUTO;
        private DataType dataType = DataType.BARS;
        private String ticker = "";
        private LocalDate from;
        private LocalDate to;
        private String interval = "1min";
        private int maxRows = 50_000;
        private final Map<String, String> options = new LinkedHashMap<>();

        public Builder requestingAgent(String v) { this.requestingAgent = v; return this; }
        public Builder provider(Provider v) { this.provider = v; return this; }
        public Builder dataType(DataType v) { this.dataType = v; return this; }
        public Builder ticker(String v) { this.ticker = v; return this; }
        public Builder from(LocalDate v) { this.from = v; return this; }
        public Builder to(LocalDate v) { this.to = v; return this; }
        public Builder interval(String v) { this.interval = v; return this; }
        public Builder maxRows(int v) { this.maxRows = v; return this; }
        public Builder option(String k, String v) { if (k != null && !k.isBlank()) this.options.put(k, v == null ? "" : v); return this; }
        public HistoricalDataRequest build() { return new HistoricalDataRequest(this); }
    }

    private static String clean(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
