package com.bot.execution;

import com.bot.broker.AlpacaBroker;
import com.bot.journal.TradeJournal;
import com.bot.model.OrderStatus;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class OrderExecutor {

    private final AlpacaBroker broker;
    private final TradeJournal journal;
    private static final int ORDER_FILL_WAIT_ATTEMPTS =
            parsePositiveInt(
                    System.getenv("ORDER_FILL_WAIT_ATTEMPTS"),
                    30
            );

    private static final long ORDER_FILL_WAIT_SLEEP_MS =
            parsePositiveLong(
                    System.getenv("ORDER_FILL_WAIT_SLEEP_MS"),
                    1_000L
            );

    private final boolean brokerCallsEnabled;

    /*
     * Last-line duplicate protection. Higher layers also check for existing positions,
     * but live news, ticker updates, and watchlist rechecks can race each other.
     * This set is deliberately inside OrderExecutor so every caller shares the same
     * lock before an Alpaca buy request can be submitted.
     */
    private static final Set<String> PENDING_BUY_SYMBOLS = ConcurrentHashMap.newKeySet();
    private static final Set<String> PENDING_SELL_SYMBOLS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> PENDING_BUY_UNTIL_MS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> PENDING_SELL_UNTIL_MS = new ConcurrentHashMap<>();
    private static final long NON_WAITING_BUY_PENDING_TTL_MS = parsePositiveLong(System.getenv("NON_WAITING_BUY_PENDING_TTL_MS"), 120_000L);
    private static final long NON_WAITING_SELL_PENDING_TTL_MS = parsePositiveLong(System.getenv("NON_WAITING_SELL_PENDING_TTL_MS"), 180_000L);
    private static final long FAILED_BUY_RETRY_COOLDOWN_MS = parsePositiveLong(System.getenv("FAILED_BUY_RETRY_COOLDOWN_MS"), 60_000L);
    private static final long FAILED_SELL_RETRY_COOLDOWN_MS = parsePositiveLong(System.getenv("FAILED_SELL_RETRY_COOLDOWN_MS"), 180_000L);
    private static final double MIN_BUYING_POWER_TO_TRADE = parsePositiveDouble(System.getenv("MIN_BUYING_POWER_TO_TRADE"), 250.0);
    private static final double BUYING_POWER_RESERVE_DOLLARS = parsePositiveDouble(System.getenv("BUYING_POWER_RESERVE_DOLLARS"), 50.0);
    private static final double BUYING_POWER_ORDER_BUFFER = parsePositiveDouble(System.getenv("BUYING_POWER_ORDER_BUFFER"), 1.05);
    private static final boolean SHORT_STOCK_ENABLED =
            !"false".equalsIgnoreCase(System.getenv().getOrDefault("SHORT_STOCK_ENABLED", "true"));
    private static final double SHORT_ENTRY_MAX_BUYING_POWER_FRACTION =
            parsePositiveDouble(System.getenv("SHORT_ENTRY_MAX_BUYING_POWER_FRACTION"), 0.35);
    private static final double SHORT_ENTRY_ORDER_BUFFER =
            parsePositiveDouble(System.getenv("SHORT_ENTRY_ORDER_BUFFER"), 1.15);


    public OrderExecutor(
            AlpacaBroker broker,
            TradeJournal journal
    ) {
        this(
                broker,
                journal,
                true
        );
    }

    public OrderExecutor(
            AlpacaBroker broker,
            TradeJournal journal,
            boolean brokerCallsEnabled
    ) {
        this.broker = broker;
        this.journal = journal;
        this.brokerCallsEnabled = brokerCallsEnabled;
    }

    private boolean acquireBuyLock(String normalizedTicker, int qty) {
        long now = System.currentTimeMillis();
        Long until = PENDING_BUY_UNTIL_MS.get(normalizedTicker);
        if (until != null && until > now) {
            System.out.println("BUY ORDER BLOCKED: pending buy cooldown active for " + normalizedTicker + " qty=" + qty);
            journal.record("BUY_ORDER_BLOCKED", normalizedTicker, qty, "pending_buy_cooldown_active");
            return false;
        }
        if (!PENDING_BUY_SYMBOLS.add(normalizedTicker)) {
            System.out.println("BUY ORDER BLOCKED: pending buy already in progress for " + normalizedTicker + " qty=" + qty);
            journal.record("BUY_ORDER_BLOCKED", normalizedTicker, qty, "pending_buy_already_in_progress");
            return false;
        }
        PENDING_BUY_UNTIL_MS.put(normalizedTicker, now + NON_WAITING_BUY_PENDING_TTL_MS);
        return true;
    }

    private void releaseBuyLock(String normalizedTicker) {
        releaseBuyLock(normalizedTicker, true);
    }

    private void releaseBuyLock(String normalizedTicker, boolean clearCooldown) {
        PENDING_BUY_SYMBOLS.remove(normalizedTicker);
        if (clearCooldown) {
            PENDING_BUY_UNTIL_MS.remove(normalizedTicker);
        }
    }

    private void holdFailedBuyCooldown(String normalizedTicker) {
        if (normalizedTicker == null || normalizedTicker.isBlank()) {
            return;
        }
        long until = System.currentTimeMillis() + FAILED_BUY_RETRY_COOLDOWN_MS;
        PENDING_BUY_UNTIL_MS.put(normalizedTicker, until);
        System.out.println("BUY ORDER FAILURE COOLDOWN SET: ticker=" + normalizedTicker +
                " cooldownMs=" + FAILED_BUY_RETRY_COOLDOWN_MS);
    }

    private boolean acquireSellLock(String normalizedTicker, int qty) {
        long now = System.currentTimeMillis();
        Long until = PENDING_SELL_UNTIL_MS.get(normalizedTicker);
        if (until != null && until > now) {
            System.out.println("SELL ORDER BLOCKED: pending sell cooldown active for " + normalizedTicker + " qty=" + qty);
            journal.record("SELL_ORDER_BLOCKED", normalizedTicker, qty, "pending_sell_cooldown_active");
            return false;
        }

        if (!PENDING_SELL_SYMBOLS.add(normalizedTicker)) {
            System.out.println("SELL ORDER BLOCKED: pending sell already in progress for " + normalizedTicker + " qty=" + qty);
            journal.record("SELL_ORDER_BLOCKED", normalizedTicker, qty, "pending_sell_already_in_progress");
            return false;
        }

        PENDING_SELL_UNTIL_MS.put(normalizedTicker, now + NON_WAITING_SELL_PENDING_TTL_MS);
        return true;
    }

    private void releaseSellLock(String normalizedTicker) {
        releaseSellLock(normalizedTicker, true);
    }

    private void releaseSellLock(String normalizedTicker, boolean clearCooldown) {
        PENDING_SELL_SYMBOLS.remove(normalizedTicker);
        if (clearCooldown) {
            PENDING_SELL_UNTIL_MS.remove(normalizedTicker);
        }
    }

    private void holdFailedSellCooldown(String normalizedTicker, String reason) {
        if (normalizedTicker == null || normalizedTicker.isBlank()) {
            return;
        }

        long until = System.currentTimeMillis() + FAILED_SELL_RETRY_COOLDOWN_MS;
        PENDING_SELL_UNTIL_MS.put(normalizedTicker, until);
        System.out.println("SELL ORDER FAILURE COOLDOWN SET: ticker=" +
                normalizedTicker +
                " cooldownMs=" +
                FAILED_SELL_RETRY_COOLDOWN_MS +
                " reason=" +
                reason);
    }

    private int preflightBuyQuantity(String normalizedTicker, int requestedQty) {
        if (!brokerCallsEnabled || requestedQty <= 0) {
            return requestedQty;
        }

        try {
            AlpacaBroker.AccountSnapshot account = broker.getAccount();
            double buyingPower = account == null ? 0.0 : account.getBuyingPower();

            if (buyingPower < MIN_BUYING_POWER_TO_TRADE) {
                System.out.println("BUY ORDER BLOCKED: insufficient buying power gate ticker=" +
                        normalizedTicker + " buyingPower=" + buyingPower + " min=" + MIN_BUYING_POWER_TO_TRADE);
                journal.record("BUY_ORDER_BLOCKED", normalizedTicker, requestedQty,
                        "insufficient_buying_power_gate buyingPower=" + buyingPower);
                return 0;
            }

            double deployableBuyingPower = Math.max(0.0, buyingPower - BUYING_POWER_RESERVE_DOLLARS);
            if (deployableBuyingPower <= 0.0) {
                System.out.println("BUY ORDER BLOCKED: no deployable buying power ticker=" +
                        normalizedTicker + " buyingPower=" + buyingPower + " reserve=" + BUYING_POWER_RESERVE_DOLLARS);
                journal.record("BUY_ORDER_BLOCKED", normalizedTicker, requestedQty,
                        "no_deployable_buying_power buyingPower=" + buyingPower);
                return 0;
            }

            double price = broker.getPrice(normalizedTicker);
            if (price <= 0.0 || Double.isNaN(price) || Double.isInfinite(price)) {
                System.out.println("BUY ORDER BLOCKED: no reliable price for buying-power preflight ticker=" + normalizedTicker);
                journal.record("BUY_ORDER_BLOCKED", normalizedTicker, requestedQty,
                        "no_reliable_price_for_buying_power_preflight");
                return 0;
            }

            int affordableQty = (int) Math.floor(deployableBuyingPower / (price * BUYING_POWER_ORDER_BUFFER));
            if (affordableQty <= 0) {
                System.out.println("BUY ORDER BLOCKED: estimated cost exceeds buying power ticker=" +
                        normalizedTicker + " requestedQty=" + requestedQty + " price=" + price +
                        " buyingPower=" + buyingPower + " reserve=" + BUYING_POWER_RESERVE_DOLLARS);
                journal.record("BUY_ORDER_BLOCKED", normalizedTicker, requestedQty,
                        "estimated_cost_exceeds_buying_power price=" + price + " buyingPower=" + buyingPower);
                return 0;
            }

            if (affordableQty < requestedQty) {
                System.out.println("BUY ORDER QTY CLAMPED: ticker=" + normalizedTicker +
                        " requestedQty=" + requestedQty + " affordableQty=" + affordableQty +
                        " price=" + price + " buyingPower=" + buyingPower);
                journal.record("BUY_ORDER_QTY_CLAMPED", normalizedTicker, requestedQty,
                        "affordableQty=" + affordableQty + " price=" + price + " buyingPower=" + buyingPower);
                return affordableQty;
            }

            return requestedQty;
        } catch (Exception e) {
            String detail = rootCauseMessage(e);
            System.out.println("BUY ORDER BLOCKED: buying power preflight failed ticker=" +
                    normalizedTicker + " detail=" + detail);
            journal.record("BUY_ORDER_BLOCKED", normalizedTicker, requestedQty,
                    "buying_power_preflight_failed " + detail);
            return 0;
        }
    }

    private int preflightShortQuantity(String normalizedTicker, int requestedQty) {
        if (!SHORT_STOCK_ENABLED) {
            System.out.println("SHORT ORDER BLOCKED: short selling disabled by SHORT_STOCK_ENABLED=false ticker=" +
                    normalizedTicker + " requestedQty=" + requestedQty);
            journal.record("SHORT_ORDER_BLOCKED", normalizedTicker, requestedQty, "short_selling_disabled");
            return 0;
        }

        if (!brokerCallsEnabled || requestedQty <= 0) {
            return requestedQty;
        }

        try {
            AlpacaBroker.AccountSnapshot account = broker.getAccount();
            double buyingPower = account == null ? 0.0 : account.getBuyingPower();
            if (buyingPower < MIN_BUYING_POWER_TO_TRADE) {
                System.out.println("SHORT ORDER BLOCKED: insufficient buying power ticker=" +
                        normalizedTicker + " buyingPower=" + buyingPower + " min=" + MIN_BUYING_POWER_TO_TRADE);
                journal.record("SHORT_ORDER_BLOCKED", normalizedTicker, requestedQty,
                        "insufficient_buying_power buyingPower=" + buyingPower);
                return 0;
            }

            double price = broker.getPrice(normalizedTicker);
            if (price <= 0.0 || Double.isNaN(price) || Double.isInfinite(price)) {
                System.out.println("SHORT ORDER BLOCKED: no reliable price for short preflight ticker=" + normalizedTicker);
                journal.record("SHORT_ORDER_BLOCKED", normalizedTicker, requestedQty, "no_reliable_price_for_short_preflight");
                return 0;
            }

            double deployable = Math.max(0.0, buyingPower - BUYING_POWER_RESERVE_DOLLARS);
            double maxShortDollars = deployable * SHORT_ENTRY_MAX_BUYING_POWER_FRACTION;
            int affordableQty = (int) Math.floor(maxShortDollars / (price * SHORT_ENTRY_ORDER_BUFFER));
            if (affordableQty <= 0) {
                System.out.println("SHORT ORDER BLOCKED: estimated short exposure exceeds available buying power ticker=" +
                        normalizedTicker + " requestedQty=" + requestedQty + " price=" + price +
                        " buyingPower=" + buyingPower + " maxShortDollars=" + maxShortDollars);
                journal.record("SHORT_ORDER_BLOCKED", normalizedTicker, requestedQty,
                        "estimated_short_exposure_exceeds_available_buying_power price=" + price + " buyingPower=" + buyingPower);
                return 0;
            }

            if (affordableQty < requestedQty) {
                System.out.println("SHORT ORDER QTY CLAMPED: ticker=" + normalizedTicker +
                        " requestedQty=" + requestedQty + " affordableQty=" + affordableQty +
                        " price=" + price + " buyingPower=" + buyingPower);
                journal.record("SHORT_ORDER_QTY_CLAMPED", normalizedTicker, requestedQty,
                        "affordableQty=" + affordableQty + " price=" + price + " buyingPower=" + buyingPower);
                return affordableQty;
            }

            return requestedQty;
        } catch (Exception e) {
            String detail = rootCauseMessage(e);
            System.out.println("SHORT ORDER BLOCKED: short preflight failed ticker=" +
                    normalizedTicker + " detail=" + detail);
            journal.record("SHORT_ORDER_BLOCKED", normalizedTicker, requestedQty, "short_preflight_failed " + detail);
            return 0;
        }
    }

    private int clampSellQuantityToBrokerPosition(String normalizedTicker, int requestedQty) {
        if (!brokerCallsEnabled || requestedQty <= 0) {
            return requestedQty;
        }

        broker.cancelOpenSellOrdersForSymbol(normalizedTicker, "pre_exit_quantity_clamp");

        int actualQty = broker.getOpenPositionQuantity(normalizedTicker);

        if (actualQty <= 0) {
            System.out.println("SELL ORDER BLOCKED: no broker long position found ticker=" +
                    normalizedTicker + " requestedQty=" + requestedQty);
            journal.record("SELL_ORDER_BLOCKED", normalizedTicker, requestedQty,
                    "no_broker_long_position_found");
            return 0;
        }

        if (requestedQty > actualQty) {
            System.out.println("SELL ORDER QTY CLAMPED: ticker=" + normalizedTicker +
                    " requestedQty=" + requestedQty + " actualBrokerQty=" + actualQty);
            journal.record("SELL_ORDER_QTY_CLAMPED", normalizedTicker, requestedQty,
                    "actualBrokerQty=" + actualQty);
        }

        return Math.min(requestedQty, actualQty);
    }


    private int clampCoverQuantityToBrokerShortPosition(String normalizedTicker, int requestedQty) {
        if (!brokerCallsEnabled || requestedQty <= 0) {
            return requestedQty;
        }

        broker.cancelOpenBuyOrdersForSymbol(normalizedTicker, "pre_cover_quantity_clamp");

        int signedQty = broker.getSignedOpenPositionQuantity(normalizedTicker);

        if (signedQty >= 0) {
            System.out.println("COVER ORDER BLOCKED: no broker short position found ticker=" +
                    normalizedTicker + " requestedQty=" + requestedQty + " signedBrokerQty=" + signedQty);
            journal.record("COVER_ORDER_BLOCKED", normalizedTicker, requestedQty,
                    "no_broker_short_position_found signedBrokerQty=" + signedQty);
            return 0;
        }

        int actualShortQty = Math.abs(signedQty);

        if (requestedQty > actualShortQty) {
            System.out.println("COVER ORDER QTY CLAMPED: ticker=" + normalizedTicker +
                    " requestedQty=" + requestedQty + " actualBrokerShortQty=" + actualShortQty);
            journal.record("COVER_ORDER_QTY_CLAMPED", normalizedTicker, requestedQty,
                    "actualBrokerShortQty=" + actualShortQty);
        }

        return Math.min(requestedQty, actualShortQty);
    }

    private boolean brokerPositionIsShort(String normalizedTicker) {
        if (!brokerCallsEnabled) {
            return false;
        }

        try {
            return broker.getSignedOpenPositionQuantity(normalizedTicker) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void handleCoverSubmissionFailure(String normalizedTicker, int requestedQty, RuntimeException e) {
        String detail = rootCauseMessage(e);
        System.err.println("COVER ORDER SUBMIT ERROR: ticker=" + normalizedTicker +
                " qty=" + requestedQty +
                " detail=" + detail);
        journal.record("COVER_ORDER_FAILED", normalizedTicker, requestedQty, detail);

        broker.cancelOpenBuyOrdersForSymbol(normalizedTicker, "cover_submission_failure");

        int refreshedQty = broker.getSignedOpenPositionQuantity(normalizedTicker);
        journal.record("COVER_POSITION_RESYNC", normalizedTicker, refreshedQty,
                "after_cover_failure detail=" + detail);

        holdFailedSellCooldown(normalizedTicker, detail);
    }

    private String tryFullPositionCloseFallback(String normalizedTicker, int requestedQty, String reason, RuntimeException originalError) {
        if (!brokerCallsEnabled) {
            return null;
        }

        String detail = rootCauseMessage(originalError);
        String lower = detail == null ? "" : detail.toLowerCase();
        boolean shouldFallback = lower.contains("403") ||
                lower.contains("insufficient buying power") ||
                lower.contains("qty") ||
                lower.contains("position") ||
                lower.contains("not enough");

        if (!shouldFallback) {
            return null;
        }

        try {
            System.out.println("SELL EXIT FALLBACK: attempting full Alpaca position close ticker=" +
                    normalizedTicker + " requestedQty=" + requestedQty + " originalError=" + detail);
            String orderId = broker.closePositionFullyMarket(normalizedTicker);
            journal.record("SELL_FULL_CLOSE_FALLBACK_SUBMITTED", normalizedTicker, requestedQty,
                    orderId + " reason=" + reason + " originalError=" + detail);
            return orderId;
        } catch (RuntimeException fallbackError) {
            System.err.println("SELL EXIT FALLBACK FAILED: ticker=" + normalizedTicker +
                    " detail=" + rootCauseMessage(fallbackError));
            journal.record("SELL_FULL_CLOSE_FALLBACK_FAILED", normalizedTicker, requestedQty,
                    rootCauseMessage(fallbackError));
            return null;
        }
    }

    private String tryFullCoverCloseFallback(String normalizedTicker, int requestedQty, String reason, String originalDetail) {
        if (!brokerCallsEnabled) {
            return null;
        }

        try {
            System.out.println("COVER EXIT FALLBACK: attempting full Alpaca position close ticker=" +
                    normalizedTicker + " requestedQty=" + requestedQty + " originalDetail=" + originalDetail);
            String orderId = broker.closePositionFullyMarket(normalizedTicker);
            journal.record("COVER_FULL_CLOSE_FALLBACK_SUBMITTED", normalizedTicker, requestedQty,
                    orderId + " reason=" + reason + " originalDetail=" + originalDetail);
            return orderId;
        } catch (RuntimeException fallbackError) {
            System.err.println("COVER EXIT FALLBACK FAILED: ticker=" + normalizedTicker +
                    " detail=" + rootCauseMessage(fallbackError));
            journal.record("COVER_FULL_CLOSE_FALLBACK_FAILED", normalizedTicker, requestedQty,
                    rootCauseMessage(fallbackError));
            return null;
        }
    }

    private String tryFullCloseWhenSideIsAmbiguous(String normalizedTicker, int requestedQty, String reason, String detail) {
        if (!brokerCallsEnabled) {
            return null;
        }

        try {
            System.out.println("AMBIGUOUS EXIT FALLBACK: attempting full Alpaca position close ticker=" +
                    normalizedTicker +
                    " requestedQty=" + requestedQty +
                    " reason=" + reason +
                    " detail=" + detail);
            broker.cancelOpenBuyOrdersForSymbol(normalizedTicker, "ambiguous_exit_fallback");
            broker.cancelOpenSellOrdersForSymbol(normalizedTicker, "ambiguous_exit_fallback");
            String orderId = broker.closePositionFullyMarket(normalizedTicker);
            journal.record("AMBIGUOUS_FULL_CLOSE_FALLBACK_SUBMITTED", normalizedTicker, requestedQty,
                    orderId + " reason=" + reason + " detail=" + detail);
            return orderId;
        } catch (RuntimeException fallbackError) {
            System.err.println("AMBIGUOUS EXIT FALLBACK FAILED: ticker=" + normalizedTicker +
                    " detail=" + rootCauseMessage(fallbackError));
            journal.record("AMBIGUOUS_FULL_CLOSE_FALLBACK_FAILED", normalizedTicker, requestedQty,
                    rootCauseMessage(fallbackError));
            return null;
        }
    }

    private void handleSellSubmissionFailure(String normalizedTicker, int requestedQty, RuntimeException e) {
        String detail = rootCauseMessage(e);
        System.err.println("SELL ORDER SUBMIT ERROR: ticker=" + normalizedTicker +
                " qty=" + requestedQty +
                " detail=" + detail);
        journal.record("SELL_ORDER_FAILED", normalizedTicker, requestedQty, detail);

        broker.cancelOpenSellOrdersForSymbol(normalizedTicker, "sell_submission_failure");

        int refreshedQty = broker.getOpenPositionQuantity(normalizedTicker);
        journal.record("SELL_POSITION_RESYNC", normalizedTicker, refreshedQty,
                "after_sell_failure detail=" + detail);

        holdFailedSellCooldown(normalizedTicker, detail);
    }

    public String buyMarket(
            String ticker,
            int qty
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank() || qty <= 0) {
            journal.record(
                    "BUY_ORDER_BLOCKED",
                    ticker,
                    qty,
                    "invalid_ticker_or_quantity"
            );
            return null;
        }

        if (!acquireBuyLock(normalizedTicker, qty)) {
            return null;
        }

        int safeQty = preflightBuyQuantity(normalizedTicker, qty);
        if (safeQty <= 0) {
            holdFailedBuyCooldown(normalizedTicker);
            releaseBuyLock(normalizedTicker, false);
            return null;
        }

        try {
            if (!brokerCallsEnabled) {
                releaseBuyLock(normalizedTicker);
                return blockedOrder(
                        "BUY",
                        normalizedTicker,
                        safeQty
                );
            }

            String orderId =
                    broker.buyMarket(
                            normalizedTicker,
                            safeQty
                    );

            journal.record(
                    "BUY_SUBMITTED",
                    normalizedTicker,
                    safeQty,
                    orderId
            );

            return orderId;
        } catch (RuntimeException e) {
            holdFailedBuyCooldown(normalizedTicker);
            releaseBuyLock(normalizedTicker, false);
            String detail = rootCauseMessage(e);
            System.err.println("BUY ORDER SUBMIT ERROR: ticker=" + normalizedTicker +
                    " qty=" + qty +
                    " detail=" + detail);
            journal.record("BUY_ORDER_FAILED", normalizedTicker, qty, detail);
            return null;
        }
    }

    public String sellMarket(
            String ticker,
            int qty,
            String reason
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank() || qty <= 0) {
            journal.record("SELL_ORDER_BLOCKED", ticker, qty, "invalid_ticker_or_quantity");
            return null;
        }

        if (!acquireSellLock(normalizedTicker, qty)) {
            return null;
        }

        try {
            if (brokerPositionIsShort(normalizedTicker)) {
                int coverQty = clampCoverQuantityToBrokerShortPosition(normalizedTicker, qty);
                if (coverQty <= 0) {
                    String fallbackOrderId = tryFullCloseWhenSideIsAmbiguous(
                            normalizedTicker,
                            qty,
                            reason,
                            "short_detected_but_cover_qty_unavailable");
                    releaseSellLock(normalizedTicker, true);
                    return fallbackOrderId;
                }

                if (!brokerCallsEnabled) {
                    releaseSellLock(normalizedTicker, true);
                    return blockedOrder(
                            "COVER",
                            normalizedTicker,
                            coverQty
                    );
                }

                String coverOrderId =
                        broker.closePositionMarket(
                                normalizedTicker,
                                coverQty
                        );

                journal.record(
                        "COVER_SUBMITTED",
                        normalizedTicker,
                        coverQty,
                        coverOrderId + " reason=" + reason
                );

                return coverOrderId;
            }

            int safeQty = clampSellQuantityToBrokerPosition(normalizedTicker, qty);
            if (safeQty <= 0) {
                String fallbackOrderId = tryFullCloseWhenSideIsAmbiguous(
                        normalizedTicker,
                        qty,
                        reason,
                        "no_broker_long_position_found_or_position_side_ambiguous");
                releaseSellLock(normalizedTicker, true);
                return fallbackOrderId;
            }

            if (!brokerCallsEnabled) {
                releaseSellLock(normalizedTicker, true);
                return blockedOrder(
                        "SELL",
                        normalizedTicker,
                        safeQty
                );
            }

            String orderId =
                    broker.closePositionMarket(
                            normalizedTicker,
                            safeQty
                    );

            journal.record(
                    "SELL_SUBMITTED",
                    normalizedTicker,
                    safeQty,
                    orderId + " reason=" + reason
            );

            return orderId;
        } catch (RuntimeException e) {
            String fallbackOrderId = tryFullPositionCloseFallback(normalizedTicker, qty, reason, e);
            if (fallbackOrderId != null) {
                releaseSellLock(normalizedTicker, true);
                return fallbackOrderId;
            }
            handleSellSubmissionFailure(normalizedTicker, qty, e);
            releaseSellLock(normalizedTicker, false);
            return null;
        }
    }

    public String sellStopOrder(
            String ticker,
            int qty,
            double stopPrice
    ) {
        if (!brokerCallsEnabled) {
            return blockedOrder(
                    "STOP_SELL",
                    ticker,
                    qty
            );
        }

        String orderId =
                broker.sellStopOrder(
                        ticker,
                        qty,
                        stopPrice
                );

        journal.record(
                "STOP_SELL_SUBMITTED",
                ticker,
                qty,
                orderId + " stopPrice=" + stopPrice
        );

        return orderId;
    }

    public void cancelOrder(
            String orderId
    ) {
        if (!brokerCallsEnabled) {
            System.out.println("ORDER CANCEL BLOCKED: broker calls disabled " + orderId);

            journal.record(
                    "CANCEL_BLOCKED",
                    "N/A",
                    0,
                    orderId
            );

            return;
        }

        broker.cancelOrder(orderId);

        journal.record(
                "CANCEL_ORDER",
                "N/A",
                0,
                orderId
        );
    }

    public boolean buyMarketAndWaitForFill(
            String ticker,
            int qty
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank() || qty <= 0) {
            journal.record(
                    "BUY_ORDER_BLOCKED",
                    ticker,
                    qty,
                    "invalid_ticker_or_quantity"
            );
            return false;
        }

        if (!acquireBuyLock(normalizedTicker, qty)) {
            return false;
        }

        int safeQty = preflightBuyQuantity(normalizedTicker, qty);
        if (safeQty <= 0) {
            holdFailedBuyCooldown(normalizedTicker);
            releaseBuyLock(normalizedTicker, false);
            return false;
        }

        try {
            if (!brokerCallsEnabled) {
                blockedOrder(
                        "BUY",
                        normalizedTicker,
                        safeQty
                );
                releaseBuyLock(normalizedTicker, true);

                return false;
            }

            String orderId =
                    broker.buyMarket(
                            normalizedTicker,
                            safeQty
                    );

            journal.record(
                    "BUY_SUBMITTED",
                    normalizedTicker,
                    safeQty,
                    orderId
            );

            boolean filled = waitForFill(
                    "BUY",
                    normalizedTicker,
                    safeQty,
                    orderId
            );

            if (!filled) {
                holdFailedBuyCooldown(normalizedTicker);
                releaseBuyLock(normalizedTicker, false);
            } else {
                releaseBuyLock(normalizedTicker, true);
            }

            return filled;
        } catch (RuntimeException e) {
            holdFailedBuyCooldown(normalizedTicker);
            releaseBuyLock(normalizedTicker, false);
            String detail = rootCauseMessage(e);
            System.err.println("BUY ORDER SUBMIT ERROR: ticker=" + normalizedTicker +
                    " qty=" + qty +
                    " detail=" + detail);
            journal.record("BUY_ORDER_FAILED", normalizedTicker, qty, detail);
            return false;
        }
    }

    public boolean sellMarketAndWaitForFill(
            String ticker,
            int qty
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank() || qty <= 0) {
            journal.record(
                    "SELL_ORDER_BLOCKED",
                    ticker,
                    qty,
                    "invalid_ticker_or_quantity"
            );
            return false;
        }

        if (!acquireSellLock(normalizedTicker, qty)) {
            return false;
        }

        try {
            if (brokerPositionIsShort(normalizedTicker)) {
                int coverQty = clampCoverQuantityToBrokerShortPosition(normalizedTicker, qty);
                if (coverQty <= 0) {
                    String fallbackOrderId = tryFullCloseWhenSideIsAmbiguous(
                            normalizedTicker,
                            qty,
                            "WAIT_FOR_FILL_COVER_QTY_UNAVAILABLE",
                            "short_detected_but_cover_qty_unavailable");
                    if (fallbackOrderId != null) {
                        boolean filled = waitForFill("AMBIGUOUS_FULL_CLOSE_FALLBACK", normalizedTicker, qty, fallbackOrderId);
                        releaseSellLock(normalizedTicker, filled);
                        if (!filled) {
                            holdFailedSellCooldown(normalizedTicker, "ambiguous_full_close_fallback_not_filled");
                        }
                        return filled;
                    }
                    releaseSellLock(normalizedTicker, true);
                    return false;
                }

                boolean covered = coverShortAndWaitForFillWithExistingLock(normalizedTicker, coverQty);
                if (covered) {
                    releaseSellLock(normalizedTicker, true);
                } else {
                    holdFailedSellCooldown(normalizedTicker, "cover_not_filled");
                    releaseSellLock(normalizedTicker, false);
                }

                return covered;
            }

            int safeQty = clampSellQuantityToBrokerPosition(normalizedTicker, qty);
            if (safeQty <= 0) {
                String fallbackOrderId = tryFullCloseWhenSideIsAmbiguous(
                        normalizedTicker,
                        qty,
                        "WAIT_FOR_FILL_LONG_QTY_UNAVAILABLE",
                        "no_broker_long_position_found_or_position_side_ambiguous");
                if (fallbackOrderId != null) {
                    boolean filled = waitForFill("AMBIGUOUS_FULL_CLOSE_FALLBACK", normalizedTicker, qty, fallbackOrderId);
                    releaseSellLock(normalizedTicker, filled);
                    if (!filled) {
                        holdFailedSellCooldown(normalizedTicker, "ambiguous_full_close_fallback_not_filled");
                    }
                    return filled;
                }
                releaseSellLock(normalizedTicker, true);
                return false;
            }

            if (!brokerCallsEnabled) {
                blockedOrder(
                        "SELL",
                        normalizedTicker,
                        safeQty
                );
                releaseSellLock(normalizedTicker, true);

                return false;
            }

            boolean filled;
            if (broker.isExtendedOnlySessionNow()) {
                filled = sellExtendedHoursExitAndWaitForFill(
                        normalizedTicker,
                        safeQty
                );
            } else {
                String orderId =
                        broker.closePositionMarket(
                                normalizedTicker,
                                safeQty
                        );

                filled = waitForFill(
                        "CLOSE_LONG",
                        normalizedTicker,
                        safeQty,
                        orderId
                );
            }

            if (filled) {
                releaseSellLock(normalizedTicker, true);
            } else {
                holdFailedSellCooldown(normalizedTicker, "exit_not_filled");
                releaseSellLock(normalizedTicker, false);
            }

            return filled;
        } catch (RuntimeException e) {
            String fallbackOrderId = tryFullPositionCloseFallback(normalizedTicker, qty, "WAIT_FOR_FILL_EXIT", e);
            if (fallbackOrderId != null) {
                boolean filled = waitForFill("SELL_FULL_CLOSE_FALLBACK", normalizedTicker, qty, fallbackOrderId);
                if (filled) {
                    releaseSellLock(normalizedTicker, true);
                    return true;
                }
            }
            handleSellSubmissionFailure(normalizedTicker, qty, e);
            releaseSellLock(normalizedTicker, false);
            return false;
        }
    }

    private boolean sellExtendedHoursExitAndWaitForFill(
            String ticker,
            int qty
    ) {
        System.out.println(
                "EXTENDED HOURS SELL EXIT MODE: using aggressive limit sell retries. ticker=" +
                        ticker +
                        " qty=" +
                        qty
        );

        for (int attempt = 1; attempt <= 3; attempt++) {
            String orderId =
                    broker.sellExitOrder(
                            ticker,
                            qty,
                            attempt
                    );

            boolean filled =
                    waitForFill(
                            "SELL_EXTENDED_EXIT_ATTEMPT_" + attempt,
                            ticker,
                            qty,
                            orderId
                    );

            if (filled) {
                journal.record(
                        "SELL_EXTENDED_EXIT_FILLED",
                        ticker,
                        qty,
                        "attempt=" + attempt + " orderId=" + orderId
                );

                return true;
            }

            System.out.println(
                    "EXTENDED HOURS SELL EXIT ATTEMPT NOT FILLED: " +
                            ticker +
                            " attempt=" +
                            attempt +
                            " orderId=" +
                            orderId
            );
        }

        journal.record(
                "SELL_EXTENDED_EXIT_FAILED",
                ticker,
                qty,
                "all_attempts_unfilled"
        );

        return false;
    }


    public int getSignedBrokerPositionQuantity(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return 0;
        }

        if (!brokerCallsEnabled) {
            return 0;
        }

        try {
            return broker.getSignedOpenPositionQuantity(ticker.trim().toUpperCase());
        } catch (Exception e) {
            System.out.println("BROKER POSITION QTY RESYNC FAILED: ticker=" + ticker +
                    " detail=" + rootCauseMessage(e));
            return 0;
        }
    }

    public double latestPrice(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return 0.0;
        }

        try {
            return broker.getPrice(ticker.trim().toUpperCase());
        } catch (Exception e) {
            System.out.println(
                    "LATEST PRICE LOOKUP FAILED: " +
                            ticker +
                            " error=" +
                            e.getMessage()
            );

            return 0.0;
        }
    }

    public boolean shortMarketAndWaitForFill(
            String ticker,
            int qty
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank() || qty <= 0) {
            journal.record("SHORT_ORDER_BLOCKED", ticker, qty, "invalid_ticker_or_quantity");
            return false;
        }

        if (!acquireSellLock(normalizedTicker, qty)) {
            return false;
        }

        int safeQty = preflightShortQuantity(normalizedTicker, qty);
        if (safeQty <= 0) {
            holdFailedSellCooldown(normalizedTicker, "short_preflight_blocked");
            releaseSellLock(normalizedTicker, false);
            return false;
        }

        try {
            if (!brokerCallsEnabled) {
                blockedOrder("SHORT", normalizedTicker, safeQty);
                releaseSellLock(normalizedTicker, true);
                return false;
            }

            String orderId = broker.shortMarket(normalizedTicker, safeQty);
            journal.record("SHORT_SUBMITTED", normalizedTicker, safeQty, orderId);

            boolean filled = waitForFill("SHORT", normalizedTicker, safeQty, orderId);
            if (!filled) {
                holdFailedSellCooldown(normalizedTicker, "short_not_filled");
                releaseSellLock(normalizedTicker, false);
            } else {
                releaseSellLock(normalizedTicker, true);
            }

            return filled;
        } catch (RuntimeException e) {
            String detail = rootCauseMessage(e);
            System.err.println("SHORT ORDER SUBMIT ERROR: ticker=" + normalizedTicker +
                    " qty=" + qty +
                    " detail=" + detail);
            journal.record("SHORT_ORDER_FAILED", normalizedTicker, qty, detail);
            holdFailedSellCooldown(normalizedTicker, detail);
            releaseSellLock(normalizedTicker, false);
            return false;
        }
    }

    public boolean coverShortAndWaitForFill(
            String ticker,
            int qty
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank() || qty <= 0) {
            journal.record(
                    "COVER_ORDER_BLOCKED",
                    ticker,
                    qty,
                    "invalid_ticker_or_quantity"
            );
            return false;
        }

        if (!acquireSellLock(normalizedTicker, qty)) {
            return false;
        }

        try {
            int coverQty = clampCoverQuantityToBrokerShortPosition(normalizedTicker, qty);
            if (coverQty <= 0) {
                String fallbackOrderId = tryFullCoverCloseFallback(
                        normalizedTicker,
                        qty,
                        "COVER_ZERO_SIGNED_QTY",
                        "signedBrokerQtyNotShortOrLookupFailed"
                );
                if (fallbackOrderId != null) {
                    boolean filled = waitForFill(
                            "COVER_FULL_CLOSE_FALLBACK",
                            normalizedTicker,
                            qty,
                            fallbackOrderId
                    );
                    releaseSellLock(normalizedTicker, filled);
                    if (!filled) {
                        holdFailedSellCooldown(normalizedTicker, "cover_full_close_fallback_not_filled");
                    }
                    return filled;
                }

                releaseSellLock(normalizedTicker, true);
                return false;
            }

            boolean covered = coverShortAndWaitForFillWithExistingLock(normalizedTicker, coverQty);

            if (covered) {
                releaseSellLock(normalizedTicker, true);
            } else {
                holdFailedSellCooldown(normalizedTicker, "cover_not_filled");
                releaseSellLock(normalizedTicker, false);
            }

            return covered;
        } catch (RuntimeException e) {
            String fallbackOrderId = tryFullCoverCloseFallback(
                    normalizedTicker,
                    qty,
                    "COVER_SUBMISSION_EXCEPTION",
                    rootCauseMessage(e)
            );
            if (fallbackOrderId != null) {
                boolean filled = waitForFill(
                        "COVER_FULL_CLOSE_FALLBACK",
                        normalizedTicker,
                        qty,
                        fallbackOrderId
                );
                releaseSellLock(normalizedTicker, filled);
                if (!filled) {
                    holdFailedSellCooldown(normalizedTicker, "cover_full_close_fallback_not_filled");
                }
                return filled;
            }

            handleCoverSubmissionFailure(normalizedTicker, qty, e);
            releaseSellLock(normalizedTicker, false);
            return false;
        }
    }

    private boolean coverShortAndWaitForFillWithExistingLock(
            String normalizedTicker,
            int qty
    ) {
        if (!brokerCallsEnabled) {
            blockedOrder(
                    "COVER",
                    normalizedTicker,
                    qty
            );

            return false;
        }

        try {
            boolean filled;
            if (broker.isExtendedOnlySessionNow()) {
                filled = coverShortExtendedHoursExitAndWaitForFill(
                        normalizedTicker,
                        qty
                );
            } else {
                String orderId =
                        broker.closePositionMarket(
                                normalizedTicker,
                                qty
                        );

                filled = waitForFill(
                        "CLOSE_SHORT",
                        normalizedTicker,
                        qty,
                        orderId
                );
            }

            return filled;
        } catch (RuntimeException e) {
            String fallbackOrderId = tryFullCoverCloseFallback(
                    normalizedTicker,
                    qty,
                    "COVER_EXISTING_LOCK_EXCEPTION",
                    rootCauseMessage(e)
            );
            if (fallbackOrderId != null) {
                return waitForFill(
                        "COVER_FULL_CLOSE_FALLBACK",
                        normalizedTicker,
                        qty,
                        fallbackOrderId
                );
            }

            handleCoverSubmissionFailure(normalizedTicker, qty, e);
            return false;
        }
    }

    private boolean coverShortExtendedHoursExitAndWaitForFill(
            String ticker,
            int qty
    ) {
        System.out.println(
                "EXTENDED HOURS COVER EXIT MODE: using aggressive limit buy retries. ticker=" +
                        ticker +
                        " qty=" +
                        qty
        );

        for (int attempt = 1; attempt <= 3; attempt++) {
            String orderId =
                    broker.buyExitOrder(
                            ticker,
                            qty,
                            attempt
                    );

            boolean filled =
                    waitForFill(
                            "COVER_EXTENDED_EXIT_ATTEMPT_" + attempt,
                            ticker,
                            qty,
                            orderId
                    );

            if (filled) {
                journal.record(
                        "COVER_EXTENDED_EXIT_FILLED",
                        ticker,
                        qty,
                        "attempt=" + attempt + " orderId=" + orderId
                );

                return true;
            }

            System.out.println(
                    "EXTENDED HOURS COVER EXIT ATTEMPT NOT FILLED: " +
                            ticker +
                            " attempt=" +
                            attempt +
                            " orderId=" +
                            orderId
            );
        }

        journal.record(
                "COVER_EXTENDED_EXIT_FAILED",
                ticker,
                qty,
                "all_attempts_unfilled"
        );

        return false;
    }

    private String blockedOrder(
            String side,
            String ticker,
            int qty
    ) {
        String message =
                side +
                        " ORDER BLOCKED: broker calls are disabled. ticker=" +
                        ticker +
                        " qty=" +
                        qty;

        System.out.println(message);

        journal.record(
                side + "_ORDER_BLOCKED",
                ticker,
                qty,
                "broker_calls_enabled=false"
        );

        return null;
    }

    private boolean waitForFill(
            String side,
            String ticker,
            int qty,
            String orderId
    ) {
        if (orderId == null || orderId.isBlank()) {
            System.out.println(
                    side +
                            " ORDER FAILED: " +
                            ticker
            );

            journal.record(
                    side + "_ORDER_FAILED",
                    ticker,
                    qty,
                    "missing_order_id"
            );

            return false;
        }

        System.out.println("Waiting for fill: " + orderId);

        for (int i = 0; i < ORDER_FILL_WAIT_ATTEMPTS; i++) {
            try {
                Thread.sleep(ORDER_FILL_WAIT_SLEEP_MS);

                OrderStatus status =
                        broker.getOrderStatus(orderId);

                System.out.println("Order Status: " + status);

                if (status.isFilled()) {
                    System.out.println(
                            side +
                                    " Order Filled: " +
                                    orderId
                    );

                    journal.record(
                            side,
                            ticker,
                            qty,
                            orderId + " status=filled"
                    );

                    return true;
                }

                if (isTerminalRejectedStatus(status.getStatus())) {
                    System.out.println("Order terminal status: " + status);

                    journal.record(
                            side + "_NOT_FILLED",
                            ticker,
                            qty,
                            orderId + " status=" + status.getStatus()
                    );

                    return false;
                }

            } catch (Exception e) {
                System.err.println(
                        side +
                                " fill check error: " +
                                e.getMessage()
                );
            }
        }

        System.out.println(
                side +
                        " order not filled in time. Cancelling: " +
                        orderId
        );

        cancelOrder(orderId);

        journal.record(
                side + "_NOT_FILLED",
                ticker,
                qty,
                orderId + " status=timeout_cancelled"
        );

        return false;
    }

    private static String rootCauseMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown_error";
        }

        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static String normalizeTicker(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase();
    }

    private static int parsePositiveInt(
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

    private static long parsePositiveLong(
            String value,
            long defaultValue
    ) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static double parsePositiveDouble(
            String value,
            double defaultValue
    ) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            double parsed = Double.parseDouble(value.trim());
            return parsed > 0.0 ? parsed : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean isTerminalRejectedStatus(
            String status
    ) {
        if (status == null) {
            return false;
        }

        String normalized =
                status.toLowerCase();

        return normalized.equals("canceled") ||
                normalized.equals("expired") ||
                normalized.equals("rejected") ||
                normalized.equals("done_for_day");
    }
}