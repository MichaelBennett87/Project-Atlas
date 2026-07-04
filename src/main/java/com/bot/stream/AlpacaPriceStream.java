package com.bot.stream;

import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.intelligence.bus.PolygonFirstMarketDataService;
import com.bot.scanner.SharedRollingBarHistoryService;

import java.util.function.Consumer;

public class AlpacaPriceStream {

    private static final int MAX_CONSECUTIVE_ERRORS_BEFORE_PAUSE = 5;

    private final AlpacaBroker broker;
    private final MarketDataCache marketData;
    private final PositionManager positionManager;
    private final String ticker;
    private final int pollSeconds;
    private final Consumer<Bar> barListener;
    private final SharedRollingBarHistoryService sharedBarHistory = SharedRollingBarHistoryService.getInstance();

    private volatile boolean running = false;
    private volatile boolean stoppedBecausePositionClosed = false;
    private volatile Thread workerThread;
    private boolean hasSeenOpenPosition = false;

    public AlpacaPriceStream(
            MarketDataCache marketData,
            PositionManager positionManager
    ) {
        this.broker = null;
        this.marketData = marketData;
        this.positionManager = positionManager;
        this.ticker = null;
        this.pollSeconds = 0;
        this.barListener = null;
    }

    public AlpacaPriceStream(
            AlpacaBroker broker,
            MarketDataCache marketData,
            PositionManager positionManager,
            String ticker,
            int pollSeconds
    ) {
        this(broker, marketData, positionManager, ticker, pollSeconds, null);
    }

    public AlpacaPriceStream(
            AlpacaBroker broker,
            MarketDataCache marketData,
            PositionManager positionManager,
            String ticker,
            int pollSeconds,
            Consumer<Bar> barListener
    ) {
        this.broker = broker;
        this.marketData = marketData;
        this.positionManager = positionManager;
        this.ticker = ticker;
        this.pollSeconds = Math.max(1, pollSeconds);
        this.barListener = barListener;
    }

    public void start() {
        if (broker == null || ticker == null) {
            System.out.println("Price stream started in manual mode.");
            return;
        }

        if (running) {
            return;
        }

        running = true;

        Thread thread = new Thread(this::runLoop);
        thread.setName("alpaca-price-polling-" + ticker);
        thread.setDaemon(true);
        workerThread = thread;
        thread.start();

        System.out.println("Alpaca price polling started for " + ticker);
    }

    public void stop() {
        running = false;

        Thread thread = workerThread;

        if (thread != null) {
            thread.interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean stoppedBecausePositionClosed() {
        return stoppedBecausePositionClosed;
    }

    public void onBar(Bar bar) {
        if (bar == null || bar.ticker == null) {
            return;
        }

        // Critical: always store bars for watched tickers, even before a position
        // exists. Otherwise the unified watchlist stays stuck at WARMUP forever.
        sharedBarHistory.observe(bar.ticker, bar);
        if (marketData != null) {
            marketData.addBar(bar.ticker, bar);
        }

        if (barListener != null) {
            try {
                barListener.accept(bar);
            } catch (Exception e) {
                System.err.println("PRICE STREAM LISTENER ERROR: ticker=" + bar.ticker + " error=" + e.getMessage());
            }
        }

        if (positionManager != null && positionManager.hasPosition(bar.ticker)) {
            hasSeenOpenPosition = true;
            positionManager.onPrice(bar.ticker, bar.close);
            return;
        }

        if (positionManager != null && hasSeenOpenPosition && !positionManager.hasPosition(bar.ticker)) {
            stoppedBecausePositionClosed = true;
            running = false;
        }
    }

    private void runLoop() {
        int consecutiveErrors = 0;

        while (running) {
            try {
                // One market-data call per poll. getLatestBar() now shares the broker-level
                // latest-bar/price cache, so calling getPrice() again here doubled Alpaca
                // traffic without materially improving exits.
                Bar bar = broker.getLatestBar(ticker);

                if (bar == null || bar.close <= 0) {
                    bar = PolygonFirstMarketDataService.getInstance().getFreshBar(ticker, 30_000L);
                    if (bar != null && bar.close > 0) {
                        System.out.println("PRICE POLLING FAILOVER USED: ticker=" + ticker + " provider=POLYGON_FIRST close=" + bar.close);
                    }
                }

                if (bar == null || bar.close <= 0) {
                    throw new RuntimeException("No valid latest bar for " + ticker);
                }

                consecutiveErrors = 0;

                onBar(bar);

                if (hasSeenOpenPosition && positionManager != null && !positionManager.hasPosition(ticker)) {
                    stoppedBecausePositionClosed = true;
                    running = false;
                    System.out.println(
                            "LIVE BAR STOPPED: " +
                                    ticker +
                                    " reason=NO_OPEN_POSITION"
                    );
                    break;
                }

                if (!running) {
                    break;
                }

                System.out.println(
                        "LIVE BAR: " +
                                ticker +
                                " close=" +
                                bar.close +
                                " volume=" +
                                bar.volume
                );

                sleepSeconds(pollSeconds);

            } catch (Exception e) {
                consecutiveErrors++;

                String message = e.getMessage() == null ? "" : e.getMessage();

                System.err.println(
                        "Alpaca price polling error: " +
                                message
                );

                if (isPermanentSymbolError(message)) {
                    AlpacaSymbolFilter.rejectPermanently(ticker, message);
                    running = false;
                    System.err.println(
                            "PRICE POLLING STOPPED: ticker=" +
                                    ticker +
                                    " reason=PERMANENT_SYMBOL_ERROR message=" +
                                    message
                    );
                    break;
                }

                int backoffSeconds =
                        message.contains("429") || message.toLowerCase().contains("too many requests")
                                ? Math.min(120, 15 * consecutiveErrors)
                                : Math.min(60, 5 * consecutiveErrors);

                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS_BEFORE_PAUSE) {
                    Bar fallback = PolygonFirstMarketDataService.getInstance().getEmergencyBar(ticker);
                    if (fallback != null && fallback.close > 0) {
                        consecutiveErrors = 0;
                        onBar(fallback);
                        System.out.println("PRICE POLLING EMERGENCY FAILOVER USED: ticker=" + ticker + " provider=POLYGON_FIRST close=" + fallback.close);
                        sleepSeconds(pollSeconds);
                        continue;
                    }
                    backoffSeconds = Math.max(backoffSeconds, 120);
                    if (consecutiveErrors >= Integer.getInteger("PRICE_POLLING_MAX_ERRORS_BEFORE_STOP", 8)) {
                        running = false;
                        System.err.println("PRICE POLLING STOPPED: ticker=" + ticker + " reason=NO_PROVIDER_DATA errors=" + consecutiveErrors);
                        break;
                    }
                }

                System.err.println(
                        "PRICE POLLING BACKOFF: ticker=" +
                                ticker +
                                " errors=" +
                                consecutiveErrors +
                                " sleepSeconds=" +
                                backoffSeconds
                );

                sleepSeconds(backoffSeconds);
            }
        }
    }

    private boolean isPermanentSymbolError(String message) {
        if (message == null) {
            return false;
        }

        String lower = message.toLowerCase();
        return lower.contains("invalid symbol")
                || lower.contains("invalid asset")
                || lower.contains("not tradable");
    }

    private void sleepSeconds(int seconds) {
        try {
            Thread.sleep(Math.max(1, seconds) * 1000L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
