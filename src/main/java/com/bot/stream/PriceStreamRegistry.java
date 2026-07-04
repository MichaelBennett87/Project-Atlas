package com.bot.stream;

import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.Position;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class PriceStreamRegistry {

    private static final int DEFAULT_MAX_ACTIVE_STREAMS = 25;
    private static final int DEFAULT_MAX_OPPORTUNITY_STREAMS = 50;
    private static final int DEFAULT_MIN_POLL_SECONDS = 15;

    private final AlpacaBroker broker;
    private final MarketDataCache marketData;
    private final PositionManager positionManager;
    private final int pollSeconds;
    private final int maxActiveStreams;
    private final int maxOpportunityStreams;
    private final List<BiConsumer<String, Bar>> barListeners = new CopyOnWriteArrayList<>();

    private final Map<String, AlpacaPriceStream> streams = new ConcurrentHashMap<>();

    public PriceStreamRegistry(
            AlpacaBroker broker,
            MarketDataCache marketData,
            PositionManager positionManager,
            int pollSeconds
    ) {
        this.broker = broker;
        this.marketData = marketData;
        this.positionManager = positionManager;
        this.pollSeconds = resolvePollSeconds(pollSeconds);
        this.maxActiveStreams = resolveMaxActiveStreams();
        this.maxOpportunityStreams = resolveMaxOpportunityStreams();

        if (this.positionManager != null) {
            this.positionManager.addPositionClosedListener(this::stopTracking);
        }
    }

    public void addBarListener(BiConsumer<String, Bar> listener) {
        if (listener != null) {
            barListeners.add(listener);
        }
    }

    public void startTracking(String ticker) {
        startTrackingInternal(ticker, false);
    }

    public void startTrackingForOpenPosition(String ticker) {
        startTrackingInternal(ticker, true);
    }

    private void startTrackingInternal(String ticker, boolean forceForOpenPosition) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }

        purgeStoppedStreams();

        String normalizedTicker = AlpacaSymbolFilter.normalize(ticker);

        if (!AlpacaSymbolFilter.isEligibleStockSymbol(normalizedTicker)) {
            System.out.println(
                    "PRICE TRACKING REJECTED: ticker=" +
                            normalizedTicker +
                            " reason=NOT_ALPACA_US_STOCK_SYMBOL"
            );
            return;
        }

        if (streams.containsKey(normalizedTicker)) {
            return;
        }

        boolean isOpenPosition =
                forceForOpenPosition ||
                        (positionManager != null && positionManager.hasPosition(normalizedTicker));

        int opportunityStreams = opportunityStreamCount();
        if (!isOpenPosition && opportunityStreams >= maxOpportunityStreams) {
            System.out.println(
                    "PRICE TRACKING SKIPPED: max opportunity streams reached ticker=" +
                            normalizedTicker +
                            " opportunityActive=" +
                            opportunityStreams +
                            " maxOpportunity=" +
                            maxOpportunityStreams +
                            " protectedActive=" +
                            protectedStreamCount()
            );

            return;
        }

        if (isOpenPosition && streams.size() >= maxActiveStreams + maxOpportunityStreams) {
            boolean freedSlot = evictOneNonPositionStream(normalizedTicker);

            if (!freedSlot && streams.size() >= maxActiveStreams + maxOpportunityStreams) {
                System.out.println(
                        "PRICE TRACKING WARNING: protected position could not free a stream slot ticker=" +
                                normalizedTicker +
                                " active=" +
                                streams.size() +
                                " protectedMax=" +
                                maxActiveStreams +
                                " opportunityMax=" +
                                maxOpportunityStreams
                );
            }
        }

        if (!isOpenPosition && !hasRecentMarketData(normalizedTicker)) {
            System.out.println(
                    "PRICE TRACKING REJECTED: ticker=" +
                            normalizedTicker +
                            " reason=NO_VALID_MARKET_DATA"
            );
            return;
        }

        streams.computeIfAbsent(
                normalizedTicker,
                symbol -> {
                    AlpacaPriceStream stream =
                            new AlpacaPriceStream(
                                    broker,
                                    marketData,
                                    positionManager,
                                    symbol,
                                    pollSeconds,
                                    bar -> notifyBarListeners(symbol, bar)
                            );

                    stream.start();

                    System.out.println(
                            "PRICE TRACKING STARTED: " +
                                    symbol +
                                    " pollSeconds=" +
                                    pollSeconds +
                                    " active=" +
                                    (streams.size() + 1) +
                                    " protectedActive=" +
                                    protectedStreamCount() +
                                    " opportunityActive=" +
                                    opportunityStreamCount() +
                                    " protectedMax=" +
                                    maxActiveStreams +
                                    " opportunityMax=" +
                                    maxOpportunityStreams +
                                    " protectedPosition=" +
                                    isOpenPosition
                    );

                    return stream;
                }
        );
    }


    private boolean hasRecentMarketData(String ticker) {
        String normalized = AlpacaSymbolFilter.normalize(ticker);
        try {
            if (marketData != null && marketData.latestClose(normalized) > 0.0) {
                return true;
            }

            Bar bar = broker.getLatestBar(normalized);
            boolean valid = bar != null && bar.close > 0.0 && !Double.isNaN(bar.close) && !Double.isInfinite(bar.close);
            if (valid && marketData != null) {
                marketData.addBar(normalized, bar);
            }
            return valid;
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "" : e.getMessage();
            if (detail.contains("404") || detail.toLowerCase().contains("invalid symbol") || detail.contains("400")) {
                AlpacaSymbolFilter.rejectPermanently(normalized, "price_tracking_validation_failed " + detail);
            }
            return false;
        }
    }

    public void startTrackingAll(Collection<Position> positions) {
        if (positions == null || positions.isEmpty()) {
            System.out.println("PRICE TRACKING STARTUP: no synced positions to track.");
            return;
        }

        int requested = 0;

        for (Position position : positions) {
            if (position == null || position.ticker == null || position.ticker.isBlank()) {
                continue;
            }

            requested++;
            startTrackingForOpenPosition(position.ticker);
        }

        System.out.println(
                "PRICE TRACKING STARTUP COMPLETE: requested=" +
                        requested +
                        " active=" +
                        activeStreamCount() +
                        " protectedActive=" +
                        protectedStreamCount() +
                        " opportunityActive=" +
                        opportunityStreamCount() +
                        " protectedMax=" +
                        maxActiveStreams +
                        " opportunityMax=" +
                        maxOpportunityStreams
        );
    }

    public void stopTracking(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }

        String normalizedTicker = AlpacaSymbolFilter.normalize(ticker);

        AlpacaPriceStream stream = streams.remove(normalizedTicker);

        if (stream != null) {
            stream.stop();
            System.out.println(
                    "PRICE TRACKING STOPPED: " +
                            normalizedTicker +
                            " reason=POSITION_CLOSED active=" +
                            streams.size()
            );
        } else {
            System.out.println(
                    "PRICE TRACKING STOP REQUESTED: " +
                            normalizedTicker +
                            " reason=POSITION_CLOSED active=" +
                            streams.size()
            );
        }
    }

    public boolean isTracking(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }

        return streams.containsKey(AlpacaSymbolFilter.normalize(ticker));
    }

    public int activeStreamCount() {
        purgeStoppedStreams();
        return streams.size();
    }

    public void stopAll() {
        for (String ticker : streams.keySet()) {
            stopTracking(ticker);
        }
    }


    private int protectedStreamCount() {
        int count = 0;
        if (positionManager == null) {
            return count;
        }
        for (String ticker : streams.keySet()) {
            if (positionManager.hasPosition(ticker)) {
                count++;
            }
        }
        return count;
    }

    private int opportunityStreamCount() {
        int count = 0;
        for (String ticker : streams.keySet()) {
            if (positionManager == null || !positionManager.hasPosition(ticker)) {
                count++;
            }
        }
        return count;
    }

    private boolean evictOneNonPositionStream(String incomingPositionTicker) {
        for (Map.Entry<String, AlpacaPriceStream> entry : streams.entrySet()) {
            String existingTicker = entry.getKey();

            if (existingTicker == null || existingTicker.equalsIgnoreCase(incomingPositionTicker)) {
                continue;
            }

            if (positionManager != null && positionManager.hasPosition(existingTicker)) {
                continue;
            }

            AlpacaPriceStream stream = streams.remove(existingTicker);

            if (stream != null) {
                stream.stop();
                System.out.println(
                        "PRICE TRACKING EVICTED: " +
                                existingTicker +
                                " reason=OPEN_POSITION_SLOT_PROTECTION incomingPosition=" +
                                incomingPositionTicker +
                                " active=" +
                                streams.size() +
                                " max=" +
                                maxActiveStreams
                );
                return true;
            }
        }

        return false;
    }

    private void notifyBarListeners(String ticker, Bar bar) {
        if (bar == null) {
            return;
        }
        for (BiConsumer<String, Bar> listener : barListeners) {
            try {
                listener.accept(ticker, bar);
            } catch (Exception e) {
                System.err.println("PRICE STREAM REGISTRY LISTENER ERROR: ticker=" + ticker + " error=" + e.getMessage());
            }
        }
    }

    private void purgeStoppedStreams() {
        for (Map.Entry<String, AlpacaPriceStream> entry : streams.entrySet()) {
            String ticker = entry.getKey();
            AlpacaPriceStream stream = entry.getValue();

            if (stream == null || !stream.isRunning() || AlpacaSymbolFilter.isPermanentlyRejected(ticker)) {
                streams.remove(ticker, stream);
            }
        }
    }

    private int resolvePollSeconds(int requestedPollSeconds) {
        int configuredPollSeconds =
                parsePositiveInt(
                        System.getenv("PRICE_POLL_SECONDS"),
                        DEFAULT_MIN_POLL_SECONDS
                );

        int minimumPollSeconds =
                Math.max(DEFAULT_MIN_POLL_SECONDS, configuredPollSeconds);

        return Math.max(requestedPollSeconds, minimumPollSeconds);
    }

    private int resolveMaxActiveStreams() {
        return parsePositiveInt(
                System.getenv("MAX_PROTECTED_POSITION_STREAMS"),
                parsePositiveInt(
                        System.getenv("MAX_PRICE_STREAMS"),
                        DEFAULT_MAX_ACTIVE_STREAMS
                )
        );
    }

    private int resolveMaxOpportunityStreams() {
        return parsePositiveInt(
                System.getenv("MAX_OPPORTUNITY_PRICE_STREAMS"),
                DEFAULT_MAX_OPPORTUNITY_STREAMS
        );
    }

    private int parsePositiveInt(
            String value,
            int defaultValue
    ) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value.trim());

            return parsed > 0 ? parsed : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
