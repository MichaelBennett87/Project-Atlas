package com.bot.stream;

import com.bot.model.NewsEvent;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MultiSourceNewsFreshnessEngine {

    private static final long DEFAULT_MAX_PROVIDER_AGE_MS =
            Duration.ofMinutes(60).toMillis();

    private static final long A_PLUS_MAX_PROVIDER_AGE_MS =
            Duration.ofMinutes(30).toMillis();

    private static final long ANALYST_MAX_PROVIDER_AGE_MS =
            Duration.ofMinutes(15).toMillis();

    private static final long DEFAULT_ALPACA_MAX_PROVIDER_AGE_MS =
            Duration.ofMinutes(20).toMillis();

    private static final long DEFAULT_ALPACA_A_PLUS_MAX_PROVIDER_AGE_MS =
            Duration.ofMinutes(10).toMillis();

    private static final long DEFAULT_ALPACA_ANALYST_MAX_PROVIDER_AGE_MS =
            Duration.ofMinutes(5).toMillis();

    private static final long FDA_APPROVAL_MAX_PROVIDER_AGE_MS =
            Duration.ofMinutes(60).toMillis();

    private static final long MERGER_ACQUISITION_MAX_PROVIDER_AGE_MS =
            Duration.ofMinutes(30).toMillis();

    private static final long EARNINGS_MAX_PROVIDER_AGE_MS =
            Duration.ofMinutes(20).toMillis();

    private static final long CONTRACT_AWARD_MAX_PROVIDER_AGE_MS =
            Duration.ofMinutes(15).toMillis();

    private static final long GENERIC_OPINION_MAX_PROVIDER_AGE_MS =
            Duration.ZERO.toMillis();

    private static final long DUPLICATE_REPLAY_WINDOW_MS =
            Duration.ofHours(12).toMillis();

    private final Map<NewsFingerprint, SeenNewsRecord> firstSeen =
            new ConcurrentHashMap<>();

    private final long maxProviderAgeMs;
    private final long aPlusMaxProviderAgeMs;
    private final long analystMaxProviderAgeMs;
    private final long alpacaMaxProviderAgeMs;
    private final long alpacaAPlusMaxProviderAgeMs;
    private final long alpacaAnalystMaxProviderAgeMs;
    private final boolean requireProviderTimestamp;

    public MultiSourceNewsFreshnessEngine() {
        this(
                getLongEnv("NEWS_MAX_PROVIDER_AGE_MINUTES", 60L) * 60_000L,
                getLongEnv("NEWS_A_PLUS_MAX_PROVIDER_AGE_MINUTES", 30L) * 60_000L,
                getLongEnv("NEWS_ANALYST_MAX_PROVIDER_AGE_MINUTES", 15L) * 60_000L,
                getLongEnv("NEWS_ALPACA_MAX_PROVIDER_AGE_MINUTES", 20L) * 60_000L,
                getLongEnv("NEWS_ALPACA_A_PLUS_MAX_PROVIDER_AGE_MINUTES", 10L) * 60_000L,
                getLongEnv("NEWS_ALPACA_ANALYST_MAX_PROVIDER_AGE_MINUTES", 5L) * 60_000L,
                getBooleanEnv("NEWS_REQUIRE_PROVIDER_TIMESTAMP", true)
        );
    }

    public MultiSourceNewsFreshnessEngine(
            long maxProviderAgeMs,
            long aPlusMaxProviderAgeMs,
            long analystMaxProviderAgeMs
    ) {
        this(
                maxProviderAgeMs,
                aPlusMaxProviderAgeMs,
                analystMaxProviderAgeMs,
                DEFAULT_ALPACA_MAX_PROVIDER_AGE_MS,
                DEFAULT_ALPACA_A_PLUS_MAX_PROVIDER_AGE_MS,
                DEFAULT_ALPACA_ANALYST_MAX_PROVIDER_AGE_MS,
                true
        );
    }

    public MultiSourceNewsFreshnessEngine(
            long maxProviderAgeMs,
            long aPlusMaxProviderAgeMs,
            long analystMaxProviderAgeMs,
            long alpacaMaxProviderAgeMs,
            long alpacaAPlusMaxProviderAgeMs,
            long alpacaAnalystMaxProviderAgeMs,
            boolean requireProviderTimestamp
    ) {
        this.maxProviderAgeMs =
                maxProviderAgeMs <= 0 ? DEFAULT_MAX_PROVIDER_AGE_MS : maxProviderAgeMs;

        this.aPlusMaxProviderAgeMs =
                aPlusMaxProviderAgeMs <= 0 ? A_PLUS_MAX_PROVIDER_AGE_MS : aPlusMaxProviderAgeMs;

        this.analystMaxProviderAgeMs =
                analystMaxProviderAgeMs <= 0 ? ANALYST_MAX_PROVIDER_AGE_MS : analystMaxProviderAgeMs;

        this.alpacaMaxProviderAgeMs =
                alpacaMaxProviderAgeMs <= 0 ? DEFAULT_ALPACA_MAX_PROVIDER_AGE_MS : alpacaMaxProviderAgeMs;

        this.alpacaAPlusMaxProviderAgeMs =
                alpacaAPlusMaxProviderAgeMs <= 0 ? DEFAULT_ALPACA_A_PLUS_MAX_PROVIDER_AGE_MS : alpacaAPlusMaxProviderAgeMs;

        this.alpacaAnalystMaxProviderAgeMs =
                alpacaAnalystMaxProviderAgeMs <= 0 ? DEFAULT_ALPACA_ANALYST_MAX_PROVIDER_AGE_MS : alpacaAnalystMaxProviderAgeMs;

        this.requireProviderTimestamp = requireProviderTimestamp;
    }

    public NewsFreshnessDecision evaluate(
            NewsEvent news
    ) {
        long now =
                System.currentTimeMillis();

        if (news == null) {
            return reject(
                    "INVALID_NEWS",
                    "News event is null",
                    0L,
                    0L,
                    0L,
                    "UNKNOWN"
            );
        }

        if (news.getBotFirstSeenAt() <= 0) {
            news.setBotFirstSeenAt(now);
        }

        long providerTimestamp =
                news.getProviderTimestamp() > 0
                        ? news.getProviderTimestamp()
                        : news.getTimestamp();

        if (providerTimestamp <= 0) {
            if (requireProviderTimestamp || isSecondaryOrWireSource(news)) {
                return reject(
                        "MISSING_PROVIDER_TIMESTAMP",
                        "Provider timestamp is missing; refusing to treat socket arrival time as publication time",
                        0L,
                        Math.max(0L, now - news.getBotFirstSeenAt()),
                        0L,
                        safeSource(news.getSource())
                );
            }

            providerTimestamp = now;
            news.setProviderTimestamp(now);
            news.setTimestamp(now);
        }

        long providerAgeMs =
                Math.max(0L, now - providerTimestamp);

        long botFirstSeenAgeMs =
                Math.max(0L, now - news.getBotFirstSeenAt());

        NewsFingerprint fingerprint =
                NewsFingerprint.fromNews(news);

        SeenNewsRecord existing =
                firstSeen.get(fingerprint);

        if (existing != null) {
            long sourceLagMs =
                    Math.max(0L, now - existing.firstSeenAt);

            news.setSourceLagMs(sourceLagMs);

            if (sourceLagMs <= DUPLICATE_REPLAY_WINDOW_MS) {
                return new NewsFreshnessDecision(
                        false,
                        true,
                        "DUPLICATE_NEWS",
                        "Duplicate headline already seen first from " + existing.firstSource,
                        providerAgeMs,
                        botFirstSeenAgeMs,
                        sourceLagMs,
                        existing.firstSource
                );
            }
        }

        SeenNewsRecord newRecord =
                new SeenNewsRecord(
                        fingerprint,
                        safeSource(news.getSource()),
                        now,
                        providerTimestamp,
                        news.getHeadline()
                );

        SeenNewsRecord previous =
                firstSeen.putIfAbsent(
                        fingerprint,
                        newRecord
                );

        String firstSource =
                previous == null
                        ? safeSource(news.getSource())
                        : previous.firstSource;

        long sourceLagMs =
                previous == null
                        ? 0L
                        : Math.max(0L, now - previous.firstSeenAt);

        news.setSourceLagMs(sourceLagMs);

        long allowedAgeMs =
                maxAllowedProviderAgeMs(news);

        if (allowedAgeMs <= 0 && isLiveWebSocketSource(news)) {
            // A zero freshness window is useful for old generic REST commentary, but it
            // accidentally made genuinely live Alpaca/Benzinga websocket headlines stale
            // within milliseconds. Let content/quality gates reject weak headlines; do
            // not reject live pushed news as stale immediately.
            allowedAgeMs = getLongEnv("NEWS_LIVE_WEBSOCKET_MIN_ALLOWED_AGE_SECONDS", 120L) * 1000L;
        }

        if (providerAgeMs > allowedAgeMs) {
            return reject(
                    "STALE_PROVIDER_TIMESTAMP",
                    "Provider timestamp is too old for momentum trading: ageMs=" + providerAgeMs +
                            " allowedMs=" + allowedAgeMs +
                            " source=" + safeSource(news.getSource()),
                    providerAgeMs,
                    botFirstSeenAgeMs,
                    sourceLagMs,
                    firstSource
            );
        }

        if (providerAgeMs <= Duration.ofMinutes(5).toMillis()) {
            return allow(
                    "BREAKING",
                    "Provider timestamp is within 5 minutes",
                    providerAgeMs,
                    botFirstSeenAgeMs,
                    sourceLagMs,
                    firstSource
            );
        }

        if (providerAgeMs <= Duration.ofMinutes(30).toMillis()) {
            return allow(
                    "FRESH",
                    "Provider timestamp is within 30 minutes",
                    providerAgeMs,
                    botFirstSeenAgeMs,
                    sourceLagMs,
                    firstSource
            );
        }

        return allow(
                "AGING",
                "Provider timestamp is acceptable but no longer breaking",
                providerAgeMs,
                botFirstSeenAgeMs,
                sourceLagMs,
                firstSource
        );
    }

    private long maxAllowedProviderAgeMs(
            NewsEvent news
    ) {
        String text =
                news.fullText().toLowerCase();

        boolean alpaca =
                isAlpacaSource(news);

        // Adaptive catalyst windows. Strong catalysts keep their usefulness
        // longer than analyst notes or generic commentary. Generic opinion
        // pieces should not survive freshness filtering at all.
        if (containsAny(
                text,
                "gets a rude awakening",
                "technicals point",
                "stock chart signals",
                "net worth plunges",
                "legendary investor",
                "valuation gauge",
                "viral advice",
                "survey shows",
                "would be worth",
                "if you invested",
                "bulls and bears",
                "stock market today",
                "etf outflows",
                "etf inflows"
        )) {
            return GENERIC_OPINION_MAX_PROVIDER_AGE_MS;
        }

        if (containsAny(
                text,
                "upgrade",
                "downgrade",
                "initiates coverage",
                "reiterates",
                "price target"
        )) {
            return alpaca ? alpacaAnalystMaxProviderAgeMs : analystMaxProviderAgeMs;
        }

        if (containsAny(
                text,
                "fda approves",
                "fda approval",
                "approved by fda",
                "approval from fda",
                "fda clears",
                "510(k)",
                "receives clearance",
                "breakthrough therapy",
                "pdufa"
        )) {
            return Math.max(FDA_APPROVAL_MAX_PROVIDER_AGE_MS, alpaca ? alpacaAPlusMaxProviderAgeMs : aPlusMaxProviderAgeMs);
        }

        if (containsAny(
                text,
                "merger agreement",
                "definitive merger agreement",
                "acquisition agreement",
                "to be acquired",
                "acquires",
                "buyout",
                "takeover"
        )) {
            return MERGER_ACQUISITION_MAX_PROVIDER_AGE_MS;
        }

        if (containsAny(
                text,
                "beats earnings",
                "beats estimates",
                "beats expectations",
                "eps beat",
                "eps beats",
                "revenue beat",
                "revenue beats",
                "record revenue",
                "record results",
                "record quarterly"
        )) {
            return EARNINGS_MAX_PROVIDER_AGE_MS;
        }

        if (containsAny(
                text,
                "wins contract",
                "won contract",
                "awarded contract",
                "contract award",
                "major contract",
                "purchase order"
        )) {
            return CONTRACT_AWARD_MAX_PROVIDER_AGE_MS;
        }

        if (containsAny(
                text,
                "raises guidance",
                "raised guidance",
                "raises full-year",
                "increases outlook",
                "phase 1",
                "phase 2",
                "phase 3",
                "primary endpoint",
                "clinical trial",
                "bla",
                "nda",
                "positive topline",
                "met primary endpoint"
        )) {
            return alpaca ? alpacaAPlusMaxProviderAgeMs : aPlusMaxProviderAgeMs;
        }

        return alpaca ? alpacaMaxProviderAgeMs : maxProviderAgeMs;
    }

    private boolean isSecondaryOrWireSource(
            NewsEvent news
    ) {
        String source =
                safeSource(news == null ? null : news.getSource());

        return source.contains("ALPACA") || source.contains("BENZINGA");
    }

    private boolean isAlpacaSource(
            NewsEvent news
    ) {
        String source =
                safeSource(news == null ? null : news.getSource());

        return source.contains("ALPACA");
    }

    private boolean isLiveWebSocketSource(
            NewsEvent news
    ) {
        String source = safeSource(news == null ? null : news.getSource());
        return source.contains("WS") || source.contains("WEBSOCKET") || source.contains("ALPACA_NEWS") || source.contains("BENZINGA_NEWS");
    }

    private boolean containsAny(
            String text,
            String... patterns
    ) {
        if (text == null || text.isBlank()) {
            return false;
        }

        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && text.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    private NewsFreshnessDecision allow(
            String category,
            String reason,
            long providerAgeMs,
            long botFirstSeenAgeMs,
            long sourceLagMs,
            String firstSource
    ) {
        return new NewsFreshnessDecision(
                true,
                false,
                category,
                reason,
                providerAgeMs,
                botFirstSeenAgeMs,
                sourceLagMs,
                firstSource
        );
    }

    private NewsFreshnessDecision reject(
            String category,
            String reason,
            long providerAgeMs,
            long botFirstSeenAgeMs,
            long sourceLagMs,
            String firstSource
    ) {
        return new NewsFreshnessDecision(
                false,
                false,
                category,
                reason,
                providerAgeMs,
                botFirstSeenAgeMs,
                sourceLagMs,
                firstSource
        );
    }

    private String safeSource(
            String source
    ) {
        return source == null || source.isBlank()
                ? "UNKNOWN"
                : source.trim().toUpperCase();
    }

    private static long getLongEnv(
            String key,
            long defaultValue
    ) {
        try {
            String value =
                    System.getenv(key);

            if (value == null || value.isBlank()) {
                return defaultValue;
            }

            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static boolean getBooleanEnv(
            String key,
            boolean defaultValue
    ) {
        try {
            String value =
                    System.getenv(key);

            if (value == null || value.isBlank()) {
                return defaultValue;
            }

            return Boolean.parseBoolean(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
