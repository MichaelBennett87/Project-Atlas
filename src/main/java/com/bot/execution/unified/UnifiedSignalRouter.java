package com.bot.execution.unified;

import com.bot.journal.SignalJournal;
import com.bot.intelligence.ShadowTradeJournal;
import com.bot.intelligence.WatchlistShadowSampler;
import com.bot.master.MasterStrategyDecision;
import com.bot.master.MasterStrategyEngine;
import com.bot.master.CatalystQualityGate;
import com.bot.master.StrategyAction;
import com.bot.master.StrategySignal;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.stream.PriceStreamRegistry;
import com.bot.stream.AlpacaSymbolFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes market-data and news events into the unified master strategy engine.
 *
 * The router keeps the individual strategies independent, but prevents feed noise
 * from creating duplicate evaluations and duplicate journal rows. It also keeps
 * recent news context available for later ticker updates so news-momentum signals
 * do not disappear as soon as the next price bar arrives.
 *
 * The important addition here is the persistent watchlist. A fresh news item no
 * longer gets one single BUY/HOLD verdict and disappears. Instead, eligible
 * tickers are kept on a short-lived watchlist and re-evaluated every few seconds
 * while price bars accumulate. This lets momentum, squeeze, VWAP, gap-fill, and
 * panic-reversal setups graduate from WARMUP/WATCH into BUY when the price action
 * confirms.
 */
public class UnifiedSignalRouter {

    private static final long DEFAULT_RECENT_NEWS_TTL_MS = 20L * 60L * 1000L;
    private static final long DEFAULT_DUPLICATE_EVENT_TTL_MS = 90L * 1000L;
    private static final long DEFAULT_NEWS_TO_TICKER_SUPPRESS_MS = 1_000L;
    private static final long DEFAULT_WATCHLIST_TTL_MS = 6L * 60L * 1000L;
    private static final long DEFAULT_WATCHLIST_MIN_EVAL_MS = 3_000L;
    private static final long DEFAULT_WATCHLIST_MAX_EVAL_MS = 5_000L;
    private static final int DEFAULT_WATCHLIST_MAX_EVALUATIONS = 8;

    private final MasterStrategyEngine masterStrategyEngine;
    private final boolean executeOrders;
    private final PriceStreamRegistry priceStreamRegistry;
    private final MarketDataCache marketData;
    private final SignalJournal signalJournal;
    private final ShadowTradeJournal shadowTradeJournal;
    private final WatchlistShadowSampler watchlistShadowSampler;
    private final Map<String, CachedNews> recentNewsByTicker = new ConcurrentHashMap<>();
    private final Map<String, Long> seenEventKeys = new ConcurrentHashMap<>();
    private final Map<String, Long> recentNewsEvaluationByTicker = new ConcurrentHashMap<>();
    private final Map<String, WatchlistCandidate> watchlist = new ConcurrentHashMap<>();
    private final AtomicBoolean watchlistLoopStarted = new AtomicBoolean(false);
    private final long recentNewsTtlMs;
    private final long duplicateEventTtlMs;
    private final long newsToTickerSuppressMs;
    private final long watchlistTtlMs;
    private final long watchlistMinEvalMs;
    private final long watchlistMaxEvalMs;
    private final int watchlistMaxEvaluations;
    private final boolean verboseConsole;
    private final boolean watchlistEnabled;
    private volatile double lastAccountEquity = 100_000.00;

    public UnifiedSignalRouter(
            MasterStrategyEngine masterStrategyEngine,
            boolean executeOrders
    ) {
        this(masterStrategyEngine, executeOrders, null, null, new SignalJournal());
    }

    public UnifiedSignalRouter(
            MasterStrategyEngine masterStrategyEngine,
            boolean executeOrders,
            PriceStreamRegistry priceStreamRegistry
    ) {
        this(masterStrategyEngine, executeOrders, priceStreamRegistry, null, new SignalJournal());
    }

    public UnifiedSignalRouter(
            MasterStrategyEngine masterStrategyEngine,
            boolean executeOrders,
            PriceStreamRegistry priceStreamRegistry,
            MarketDataCache marketData
    ) {
        this(masterStrategyEngine, executeOrders, priceStreamRegistry, marketData, new SignalJournal());
    }

    public UnifiedSignalRouter(
            MasterStrategyEngine masterStrategyEngine,
            boolean executeOrders,
            PriceStreamRegistry priceStreamRegistry,
            SignalJournal signalJournal
    ) {
        this(masterStrategyEngine, executeOrders, priceStreamRegistry, null, signalJournal);
    }

    public UnifiedSignalRouter(
            MasterStrategyEngine masterStrategyEngine,
            boolean executeOrders,
            PriceStreamRegistry priceStreamRegistry,
            MarketDataCache marketData,
            SignalJournal signalJournal
    ) {
        if (masterStrategyEngine == null) {
            throw new IllegalArgumentException("masterStrategyEngine cannot be null");
        }

        this.masterStrategyEngine = masterStrategyEngine;
        this.executeOrders = executeOrders;
        this.priceStreamRegistry = priceStreamRegistry;
        this.marketData = marketData;
        this.signalJournal = signalJournal == null ? new SignalJournal() : signalJournal;
        this.shadowTradeJournal = ShadowTradeJournal.getInstance();
        this.watchlistShadowSampler = WatchlistShadowSampler.getInstance();
        this.recentNewsTtlMs = envLong("UNIFIED_RECENT_NEWS_TTL_MS", DEFAULT_RECENT_NEWS_TTL_MS);
        this.duplicateEventTtlMs = envLong("UNIFIED_DUPLICATE_EVENT_TTL_MS", DEFAULT_DUPLICATE_EVENT_TTL_MS);
        this.newsToTickerSuppressMs = envLong("UNIFIED_NEWS_TO_TICKER_SUPPRESS_MS", DEFAULT_NEWS_TO_TICKER_SUPPRESS_MS);
        this.watchlistTtlMs = envLong("UNIFIED_WATCHLIST_TTL_MS", DEFAULT_WATCHLIST_TTL_MS);
        this.watchlistMinEvalMs = envLong("UNIFIED_WATCHLIST_MIN_EVAL_MS", DEFAULT_WATCHLIST_MIN_EVAL_MS);
        this.watchlistMaxEvalMs = Math.max(
                this.watchlistMinEvalMs,
                envLong("UNIFIED_WATCHLIST_MAX_EVAL_MS", DEFAULT_WATCHLIST_MAX_EVAL_MS)
        );
        this.watchlistMaxEvaluations = envInt("UNIFIED_WATCHLIST_MAX_EVALUATIONS", DEFAULT_WATCHLIST_MAX_EVALUATIONS);
        this.verboseConsole = envBoolean("UNIFIED_VERBOSE_ROUTER", false);
        this.watchlistEnabled = envBoolean("UNIFIED_WATCHLIST_ENABLED", false);
        startWatchlistLoop();
    }

    /**
     * Called after a ticker receives a fresh price/bar update.
     */
    public MasterStrategyDecision onTickerUpdate(
            String ticker,
            double accountEquity
    ) {
        if (ticker == null || ticker.isBlank()) {
            MasterStrategyDecision ignored = MasterStrategyDecision.hold(
                    "UNKNOWN",
                    Collections.emptyList(),
                    "Ticker update ignored because ticker was blank."
            );
            signalJournal.recordUnifiedDecision(null, ignored, "TICKER_BLANK");
            return ignored;
        }

        updateLastAccountEquity(accountEquity);
        String normalizedTicker = ticker.trim().toUpperCase();
        double currentPrice = latestPrice(normalizedTicker);
        shadowTradeJournal.observePrice(normalizedTicker, currentPrice);
        long now = System.currentTimeMillis();
        NewsEvent recentNews = getRecentNews(normalizedTicker, now);
        watchlistShadowSampler.onMarketEvent(
                normalizedTicker,
                effectiveMarketData(),
                recentNews,
                currentPrice,
                lastAccountEquity,
                "TICKER_UPDATE");
        Long recentNewsEvaluationAt = recentNewsEvaluationByTicker.get(normalizedTicker);
        if (recentNewsEvaluationAt != null && now - recentNewsEvaluationAt < newsToTickerSuppressMs) {
            return MasterStrategyDecision.hold(
                    normalizedTicker,
                    Collections.emptyList(),
                    "Ticker update suppressed because the same ticker was just evaluated from news."
            );
        }

        MasterStrategyDecision decision = masterStrategyEngine.evaluateTicker(
                normalizedTicker,
                lastAccountEquity,
                recentNews
        );

        if (watchlistEnabled && recentNews != null && shouldKeepWatching(decision)) {
            addOrRefreshWatchlist(normalizedTicker, recentNews, now, decision.getReason());
        }

        handleDecision(decision, recentNews, "TICKER_UPDATE");
        return decision;
    }

    /**
     * Called after a fresh news item is received.
     */
    public MasterStrategyDecision onNews(
            NewsEvent news,
            double accountEquity
    ) {
        if (news == null) {
            MasterStrategyDecision ignored = MasterStrategyDecision.hold(
                    "UNKNOWN",
                    Collections.emptyList(),
                    "News event ignored because it was null."
            );
            signalJournal.recordUnifiedDecision(null, ignored, "NEWS_NULL");
            return ignored;
        }

        updateLastAccountEquity(accountEquity);
        String ticker = news.getTicker() == null ? "" : news.getTicker().trim().toUpperCase();
        if (ticker.isBlank()) {
            MasterStrategyDecision ignored = MasterStrategyDecision.hold(
                    "UNKNOWN",
                    Collections.emptyList(),
                    "News event ignored because ticker was blank."
            );
            signalJournal.recordUnifiedDecision(news, ignored, "NEWS_BLANK_TICKER");
            return ignored;
        }

        news.setTicker(ticker);
        double currentPrice = latestPrice(ticker);
        shadowTradeJournal.observePrice(ticker, currentPrice);
        long now = System.currentTimeMillis();
        String eventKey = eventKey(news);
        Long previouslySeenAt = seenEventKeys.get(eventKey);
        if (previouslySeenAt != null && now - previouslySeenAt < duplicateEventTtlMs) {
            if (verboseConsole) {
                System.out.println("UNIFIED ROUTER DUPLICATE NEWS SKIPPED: ticker=" + ticker + " key=" + eventKey);
            }
            return MasterStrategyDecision.hold(ticker, Collections.emptyList(), "Duplicate news event suppressed.");
        }

        seenEventKeys.put(eventKey, now);
        pruneSeenEventKeys(now);
        if (isLowEdgeGenericNews(news)) {
            MasterStrategyDecision rejected = MasterStrategyDecision.hold(
                    ticker,
                    Collections.emptyList(),
                    "LOW_EDGE_GENERIC_FINANCIAL_CONTENT rejected by router before watchlist/AI governor."
            );
            System.out.println("UNIFIED NEWS HARD REJECTED: ticker=" + ticker +
                    " reason=LOW_EDGE_GENERIC_FINANCIAL_CONTENT headline=" + safeHeadline(news));
            signalJournal.recordUnifiedDecision(news, rejected, "NEWS_GENERIC_HARD_REJECT");
            return rejected;
        }

        recentNewsByTicker.put(ticker, new CachedNews(news, now));
        recentNewsEvaluationByTicker.put(ticker, now);
        watchlistShadowSampler.onMarketEvent(
                ticker,
                effectiveMarketData(),
                news,
                currentPrice,
                lastAccountEquity,
                "NEWS");

        MasterStrategyDecision decision = masterStrategyEngine.evaluateNews(
                news,
                lastAccountEquity
        );

        if (watchlistEnabled && shouldKeepWatching(decision) && isWorthWatching(news, decision)) {
            addOrRefreshWatchlist(ticker, news, now, decision.getReason());
        } else {
            removeWatchlistIfTerminal(ticker, decision);
        }

        handleDecision(decision, news, "NEWS");
        return decision;
    }

    private void handleDecision(
            MasterStrategyDecision decision,
            NewsEvent news,
            String trigger
    ) {
        if (decision == null) {
            return;
        }

        if (shouldPrintDecision(decision)) {
            System.out.println("UNIFIED ROUTER DECISION: " + decision);
        }

        if (shouldJournalDecision(decision, trigger)) {
            signalJournal.recordUnifiedDecision(news, decision, trigger);
        }

        if (decision.getAction() != StrategyAction.BUY) {
            return;
        }

        String ticker = decision.getTicker();
        if (ticker != null) {
            watchlist.remove(ticker.trim().toUpperCase());
        }

        double shadowReferencePrice = latestPrice(ticker);
        shadowTradeJournal.observePrice(ticker, shadowReferencePrice);
        shadowTradeJournal.recordDecision(decision, news, trigger, shadowReferencePrice, executeOrders);

        if (!executeOrders) {
            System.out.println("UNIFIED ROUTER PAPER/SIGNAL MODE: order execution disabled. Winning signal="
                    + decision.getWinningSignal());
            return;
        }

        boolean executed = masterStrategyEngine.executeDecision(decision);
        System.out.println("UNIFIED ROUTER EXECUTION RESULT: " + executed);

        if (executed && priceStreamRegistry != null && decision.getWinningSignal() != null) {
            priceStreamRegistry.startTrackingForOpenPosition(decision.getWinningSignal().getTicker());
        }
    }

    private double latestPrice(String ticker) {
        String normalizedTicker = ticker == null ? "" : ticker.trim().toUpperCase();
        if (normalizedTicker.isBlank()) {
            return 0.0;
        }

        double directPrice = latestPriceFrom(marketData, normalizedTicker);
        if (directPrice > 0.0) {
            return directPrice;
        }

        return latestPriceFrom(marketDataFromRegistry(), normalizedTicker);
    }

    private double latestPriceFrom(MarketDataCache cache, String ticker) {
        if (cache == null) {
            return 0.0;
        }
        double price = cache.latestClose(ticker);
        return price > 0.0 && Double.isFinite(price) ? price : 0.0;
    }

    private MarketDataCache marketDataFromRegistry() {
        if (priceStreamRegistry == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = PriceStreamRegistry.class.getDeclaredField("marketData");
            field.setAccessible(true);
            Object value = field.get(priceStreamRegistry);
            return value instanceof MarketDataCache ? (MarketDataCache) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private MarketDataCache effectiveMarketData() {
        return marketData == null ? marketDataFromRegistry() : marketData;
    }

    private void startWatchlistLoop() {
        if (!watchlistEnabled) {
            System.out.println("UNIFIED WATCHLIST DISABLED: momentum-first mode rejects non-buy candidates instead of rechecking them.");
            return;
        }
        if (!watchlistLoopStarted.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(() -> {
            System.out.println("UNIFIED WATCHLIST LOOP STARTED: ttlMs=" + watchlistTtlMs +
                    " evalMs=" + watchlistMinEvalMs + "-" + watchlistMaxEvalMs +
                    " maxEvaluations=" + watchlistMaxEvaluations);
            while (true) {
                try {
                    runWatchlistEvaluationCycle();
                    long delay = ThreadLocalRandom.current().nextLong(watchlistMinEvalMs, watchlistMaxEvalMs + 1L);
                    Thread.sleep(Math.max(500L, delay));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.err.println("UNIFIED WATCHLIST LOOP ERROR: " + e.getMessage());
                    sleepQuietly(2_000L);
                }
            }
        });

        thread.setName("unified-watchlist-reevaluator");
        thread.setDaemon(true);
        thread.start();
    }

    private void runWatchlistEvaluationCycle() {
        long now = System.currentTimeMillis();
        List<WatchlistCandidate> snapshot = new ArrayList<>(watchlist.values());
        for (WatchlistCandidate candidate : snapshot) {
            if (candidate == null) {
                continue;
            }
            String ticker = candidate.ticker;
            if (now - candidate.createdAt > watchlistTtlMs) {
                watchlist.remove(ticker, candidate);
                System.out.println("UNIFIED WATCHLIST EXPIRED: ticker=" + ticker + " reason=TTL");
                continue;
            }
            if (candidate.evaluationCount >= watchlistMaxEvaluations) {
                watchlist.remove(ticker, candidate);
                System.out.println("UNIFIED WATCHLIST EXPIRED: ticker=" + ticker + " reason=MAX_EVALUATIONS count=" + candidate.evaluationCount);
                continue;
            }

            if (now - candidate.lastEvaluatedAt < watchlistMinEvalMs) {
                continue;
            }

            NewsEvent recentNews = getRecentNews(ticker, now);
            if (recentNews == null) {
                watchlist.remove(ticker, candidate);
                continue;
            }

            candidate.lastEvaluatedAt = now;
            candidate.evaluationCount++;
            double currentPrice = latestPrice(ticker);
            watchlistShadowSampler.onMarketEvent(
                    ticker,
                    effectiveMarketData(),
                    recentNews,
                    currentPrice,
                    lastAccountEquity,
                    "WATCHLIST_RECHECK");
            MasterStrategyDecision decision = masterStrategyEngine.evaluateTicker(ticker, lastAccountEquity, recentNews);
            if (shouldKeepWatching(decision)) {
                candidate.lastReason = decision.getReason();
            } else {
                removeWatchlistIfTerminal(ticker, decision);
            }
            handleDecision(decision, recentNews, "WATCHLIST_RECHECK");
        }
    }

    private void addOrRefreshWatchlist(String ticker, NewsEvent news, long now, String reason) {
        if (!watchlistEnabled || ticker == null || ticker.isBlank() || news == null) {
            return;
        }
        String normalized = ticker.trim().toUpperCase();
        if (!AlpacaSymbolFilter.isEligibleStockSymbol(normalized) || AlpacaSymbolFilter.isPermanentlyRejected(normalized)) {
            System.out.println("UNIFIED WATCHLIST REJECTED: ticker=" + normalized + " reason=INVALID_OR_PERMANENTLY_REJECTED_SYMBOL");
            return;
        }
        if (isLowEdgeGenericNews(news)) {
            System.out.println("UNIFIED WATCHLIST REJECTED: ticker=" + normalized +
                    " reason=LOW_EDGE_GENERIC_FINANCIAL_CONTENT headline=" + safeHeadline(news));
            return;
        }
        watchlist.compute(normalized, (key, existing) -> {
            if (existing == null) {
                System.out.println("UNIFIED WATCHLIST ADDED: ticker=" + key + " headline=" + news.getHeadline());
                return new WatchlistCandidate(key, now, now, reason);
            }
            existing.lastEvaluatedAt = Math.min(existing.lastEvaluatedAt, now - watchlistMinEvalMs);
            existing.lastReason = reason;
            return existing;
        });
    }


    private boolean isWorthWatching(NewsEvent news, MasterStrategyDecision decision) {
        if (news == null || decision == null) {
            return false;
        }
        if (isSyntheticMarketStateOpportunity(news) && !envBoolean("UNIFIED_WATCHLIST_ALLOW_SYNTHETIC_STATE", false)) {
            return false;
        }
        if (isLowEdgeGenericNews(news)) {
            return false;
        }
        if (decision.getAction() == StrategyAction.BUY) {
            return true;
        }
        double minWatchScore = envDouble("UNIFIED_WATCHLIST_MIN_CATALYST_SCORE", 0.60);
        if (news.getCatalystScore() > 0 && news.getCatalystScore() < minWatchScore) {
            return false;
        }
        String reason = decision.getReason() == null ? "" : decision.getReason().toUpperCase();
        if (reason.contains("MOMENTUM_IGNITION_BLOCK")
                || reason.contains("MOMENTUM_IGNITION_GATE_REJECTED")
                || reason.contains("MOMENTUM IGNITION GATE REJECTED")) {
            // Momentum-first rule: a ticker that fails live tape ignition is dead for this event.
            // Do not keep it on the watchlist hoping AI/news makes it tradable later.
            return false;
        }
        if (reason.contains("CATALYST_SCORE_TOO_LOW") || reason.contains("LOW_EDGE") || reason.contains("MISMATCH") || reason.contains("NO_VALID_MARKET_DATA")) {
            return false;
        }
        return envBoolean("UNIFIED_WATCHLIST_ALLOW_NON_IGNITION_HOLDS", false);
    }


    private boolean isLowEdgeGenericNews(NewsEvent news) {
        if (news == null) return false;
        String reason = CatalystQualityGate.rejectReason(news);
        if (reason != null && reason.toUpperCase().contains("LOW_EDGE_GENERIC_FINANCIAL_CONTENT")) {
            return envBoolean("UNIFIED_REJECT_GENERIC_FINANCIAL_NEWS", true);
        }
        String text = news.fullText() == null ? "" : news.fullText().toUpperCase();
        return envBoolean("UNIFIED_REJECT_GENERIC_FINANCIAL_NEWS", true)
                && (text.contains("HERE ARE 20 STOCKS MOVING")
                || text.contains("STOCKS MOVING PREMARKET")
                || text.contains("STOCKS MOVING IN")
                || text.contains("WHY ") && text.contains(" SHARES ARE TRADING HIGHER"));
    }

    private String safeHeadline(NewsEvent news) {
        String h = news == null ? "" : news.getHeadline();
        return h == null ? "" : h.replace('\n', ' ').replace('\r', ' ');
    }

    private boolean isSyntheticMarketStateOpportunity(NewsEvent news) {
        if (news == null) return false;
        String source = news.getSource() == null ? "" : news.getSource().toUpperCase();
        String text = news.fullText() == null ? "" : news.fullText().toUpperCase();
        return source.contains("STATE_OPPORTUNITY_RANKER") || source.contains("MICROSTRUCTURE_AGENT") || text.contains("STATE OPPORTUNITY:");
    }

    private boolean shouldKeepWatching(MasterStrategyDecision decision) {
        if (!watchlistEnabled || decision == null) {
            return false;
        }
        if (decision.getAction() == StrategyAction.BUY || decision.getAction() == StrategyAction.SELL || decision.getAction() == StrategyAction.BLOCK) {
            return false;
        }
        String reason = decision.getReason() == null ? "" : decision.getReason().toUpperCase();
        return reason.contains("WARM") || reason.contains("WATCH") || reason.contains("NO STRATEGY") || reason.contains("NOT STRONG ENOUGH");
    }

    private void removeWatchlistIfTerminal(String ticker, MasterStrategyDecision decision) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }
        if (decision == null || shouldKeepWatching(decision)) {
            return;
        }
        watchlist.remove(ticker.trim().toUpperCase());
    }

    private boolean shouldJournalDecision(MasterStrategyDecision decision, String trigger) {
        if (decision == null) {
            return false;
        }
        if (decision.getAction() == StrategyAction.BUY || decision.getAction() == StrategyAction.SELL || decision.getAction() == StrategyAction.BLOCK) {
            return true;
        }
        String reason = decision.getReason() == null ? "" : decision.getReason().toUpperCase();
        if (reason.contains("WATCH")) {
            return true;
        }
        if ("NEWS".equalsIgnoreCase(trigger)) {
            return true;
        }
        if ("WATCHLIST_RECHECK".equalsIgnoreCase(trigger)) {
            return envBoolean("UNIFIED_JOURNAL_WATCHLIST_RECHECKS", false);
        }
        if (reason.contains("WARMING")) {
            return envBoolean("UNIFIED_JOURNAL_WARMUP", false);
        }
        return envBoolean("UNIFIED_JOURNAL_HOLD_TICKER_UPDATES", false);
    }

    private boolean shouldPrintDecision(MasterStrategyDecision decision) {
        if (verboseConsole) {
            return true;
        }
        if (decision.getAction() == StrategyAction.BUY || decision.getAction() == StrategyAction.SELL || decision.getAction() == StrategyAction.BLOCK) {
            return true;
        }
        if (!envBoolean("UNIFIED_PRINT_HOLD_DECISIONS", false)) {
            return false;
        }
        StrategySignal winner = decision.getWinningSignal();
        if (winner != null && winner.getConfidence() >= envDouble("UNIFIED_CONSOLE_CONFIDENCE_THRESHOLD", 0.85)) {
            return true;
        }
        String reason = decision.getReason() == null ? "" : decision.getReason().toUpperCase();
        return (reason.contains("WATCH") && envBoolean("UNIFIED_PRINT_WATCH", false)) || (reason.contains("WARMING") && envBoolean("UNIFIED_PRINT_WARMUP", false));
    }

    private NewsEvent getRecentNews(String ticker, long now) {
        CachedNews cached = recentNewsByTicker.get(ticker);
        if (cached == null) {
            return null;
        }
        if (now - cached.cachedAt > recentNewsTtlMs) {
            recentNewsByTicker.remove(ticker);
            watchlist.remove(ticker);
            return null;
        }
        return cached.news;
    }

    private String eventKey(NewsEvent news) {
        String ticker = news.getTicker() == null ? "" : news.getTicker().trim().toUpperCase();
        String id = news.getId() == null ? "" : news.getId().trim();
        if (!id.isBlank()) {
            return ticker + ":ID:" + id;
        }
        String headline = news.getHeadline() == null ? "" : news.getHeadline().trim().toLowerCase();
        return ticker + ":HEADLINE:" + headline;
    }

    private void pruneSeenEventKeys(long now) {
        if (seenEventKeys.size() < 5_000) {
            return;
        }
        seenEventKeys.entrySet().removeIf(entry -> now - entry.getValue() > duplicateEventTtlMs);
    }

    private void updateLastAccountEquity(double accountEquity) {
        if (accountEquity > 0 && Double.isFinite(accountEquity)) {
            this.lastAccountEquity = accountEquity;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean envBoolean(String key, boolean fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()) || "yes".equalsIgnoreCase(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static class CachedNews {
        private final NewsEvent news;
        private final long cachedAt;

        private CachedNews(NewsEvent news, long cachedAt) {
            this.news = news;
            this.cachedAt = cachedAt;
        }
    }

    private static class WatchlistCandidate {
        private final String ticker;
        private final long createdAt;
        private volatile long lastEvaluatedAt;
        private volatile String lastReason;
        private volatile int evaluationCount;

        private WatchlistCandidate(String ticker, long createdAt, long lastEvaluatedAt, String lastReason) {
            this.ticker = ticker;
            this.createdAt = createdAt;
            this.lastEvaluatedAt = lastEvaluatedAt;
            this.lastReason = lastReason;
            this.evaluationCount = 0;
        }
    }
}
