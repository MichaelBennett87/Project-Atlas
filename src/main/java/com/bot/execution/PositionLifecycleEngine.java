package com.bot.execution;

import com.bot.broker.AlpacaBroker;
import com.bot.intelligence.TradeLifecycleOptimizationAgent;
import com.bot.model.Position;

public class PositionLifecycleEngine {

    private final AlpacaBroker broker;
    private final OrderExecutor orderExecutor;
    private final TradeLifecycleOptimizationAgent lifecycleOptimizationAgent = TradeLifecycleOptimizationAgent.getInstance();

    private static final long CHECK_INTERVAL_MS = 5_000;
    private static final long MAX_HOLD_MS = parsePositiveLong(System.getenv("MAX_HOLD_EXIT_AFTER_SECONDS"), 1_800L) * 1000L;

    private static final double LONG_TRAIL_FROM_HIGH = -0.005;
    private static final double LONG_MIN_PROFIT_BEFORE_TRAIL = 0.004;

    private static final double SHORT_BOUNCE_FROM_LOW = 0.005;
    private static final double SHORT_MIN_PROFIT_BEFORE_COVER = 0.004;

    public PositionLifecycleEngine(
            AlpacaBroker broker,
            OrderExecutor orderExecutor
    ) {
        this.broker = broker;
        this.orderExecutor = orderExecutor;
    }

    public void monitorLong(
            String ticker,
            int qty,
            double entryPrice
    ) {
        Thread thread =
                new Thread(() ->
                        runLongMonitor(
                                ticker,
                                qty,
                                entryPrice
                        )
                );

        thread.setDaemon(true);
        thread.start();
    }

    public void monitorShort(
            String ticker,
            int qty,
            double entryPrice
    ) {
        Thread thread =
                new Thread(() ->
                        runShortMonitor(
                                ticker,
                                qty,
                                entryPrice
                        )
                );

        thread.setDaemon(true);
        thread.start();
    }

    private void runLongMonitor(
            String ticker,
            int qty,
            double entryPrice
    ) {
        double highestPrice =
                entryPrice;
        long openedAt = System.currentTimeMillis();
        Position lifecyclePosition = new Position(ticker, entryPrice, entryPrice, qty, openedAt, null, false);
        lifecyclePosition.strategyName = "POSITION_LIFECYCLE_ENGINE_LONG";
        lifecycleOptimizationAgent.recordOpen(lifecyclePosition);

        System.out.println(
                "LONG LIFECYCLE MONITOR STARTED: " +
                        ticker +
                        " entry=" +
                        entryPrice +
                        " qty=" +
                        qty
        );

        while (true) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);

                double currentPrice =
                        broker.getPrice(ticker);

                if (currentPrice > highestPrice) {
                    highestPrice = currentPrice;
                }
                lifecyclePosition.peakPrice = highestPrice;
                if (lifecyclePosition.troughPrice <= 0 || currentPrice < lifecyclePosition.troughPrice) {
                    lifecyclePosition.troughPrice = currentPrice;
                }
                lifecycleOptimizationAgent.recordPrice(lifecyclePosition, currentPrice);

                double gainFromEntry =
                        (currentPrice - entryPrice) / entryPrice;

                double pullbackFromHigh =
                        (currentPrice - highestPrice) / highestPrice;

                System.out.println(
                        "LONG LIFECYCLE CHECK: " +
                                ticker +
                                " current=" +
                                currentPrice +
                                " entry=" +
                                entryPrice +
                                " high=" +
                                highestPrice +
                                " gainFromEntry=" +
                                (gainFromEntry * 100.0) +
                                "%" +
                                " pullbackFromHigh=" +
                                (pullbackFromHigh * 100.0) +
                                "%"
                );

                boolean profitWasAchieved =
                        highestPrice >= entryPrice * (1.0 + LONG_MIN_PROFIT_BEFORE_TRAIL);

                boolean momentumDriedUp =
                        profitWasAchieved &&
                                pullbackFromHigh <= LONG_TRAIL_FROM_HIGH;

                boolean maxHoldReached =
                        MAX_HOLD_MS > 0 &&
                                System.currentTimeMillis() - openedAt >= MAX_HOLD_MS;

                if (momentumDriedUp || maxHoldReached) {
                    boolean sold =
                            orderExecutor.sellMarketAndWaitForFill(
                                    ticker,
                                    qty
                            );

                    if (sold) {
                        lifecycleOptimizationAgent.recordClose(
                                lifecyclePosition,
                                currentPrice,
                                qty,
                                maxHoldReached ? "MAX_HOLD_EXIT" : "MOMENTUM_DRY_UP"
                        );
                        System.out.println(
                                "LONG POSITION EXITED: " +
                                        ticker +
                                        " qty=" +
                                        qty +
                                        " exitPrice=" +
                                        currentPrice +
                                        " reason=" +
                                        (maxHoldReached ? "MAX_HOLD_EXIT" : "MOMENTUM_DRY_UP")
                        );
                    } else {
                        System.out.println(
                                "LONG POSITION EXIT FAILED: " +
                                        ticker
                        );
                    }

                    return;
                }

            } catch (Exception e) {
                System.out.println(
                        "LONG LIFECYCLE MONITOR ERROR: " +
                                ticker +
                                " " +
                                e.getMessage()
                );

                return;
            }
        }
    }

    private void runShortMonitor(
            String ticker,
            int qty,
            double entryPrice
    ) {
        double lowestPrice =
                entryPrice;
        long openedAt = System.currentTimeMillis();
        Position lifecyclePosition = new Position(ticker, entryPrice, entryPrice, qty, openedAt, null, true);
        lifecyclePosition.strategyName = "POSITION_LIFECYCLE_ENGINE_SHORT";
        lifecycleOptimizationAgent.recordOpen(lifecyclePosition);

        System.out.println(
                "SHORT LIFECYCLE MONITOR STARTED: " +
                        ticker +
                        " entry=" +
                        entryPrice +
                        " qty=" +
                        qty
        );

        while (true) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);

                double currentPrice =
                        broker.getPrice(ticker);

                if (currentPrice < lowestPrice) {
                    lowestPrice = currentPrice;
                }
                lifecyclePosition.troughPrice = lowestPrice;
                if (lifecyclePosition.peakPrice <= 0 || currentPrice > lifecyclePosition.peakPrice) {
                    lifecyclePosition.peakPrice = currentPrice;
                }
                lifecycleOptimizationAgent.recordPrice(lifecyclePosition, currentPrice);

                double gainFromEntry =
                        (entryPrice - currentPrice) / entryPrice;

                double bounceFromLow =
                        (currentPrice - lowestPrice) / lowestPrice;

                System.out.println(
                        "SHORT LIFECYCLE CHECK: " +
                                ticker +
                                " current=" +
                                currentPrice +
                                " entry=" +
                                entryPrice +
                                " low=" +
                                lowestPrice +
                                " gainFromEntry=" +
                                (gainFromEntry * 100.0) +
                                "%" +
                                " bounceFromLow=" +
                                (bounceFromLow * 100.0) +
                                "%"
                );

                boolean profitWasAchieved =
                        lowestPrice <= entryPrice * (1.0 - SHORT_MIN_PROFIT_BEFORE_COVER);

                boolean downsideMomentumDriedUp =
                        profitWasAchieved &&
                                bounceFromLow >= SHORT_BOUNCE_FROM_LOW;

                boolean maxHoldReached =
                        MAX_HOLD_MS > 0 &&
                                System.currentTimeMillis() - openedAt >= MAX_HOLD_MS;

                if (downsideMomentumDriedUp || maxHoldReached) {
                    boolean covered =
                            orderExecutor.coverShortAndWaitForFill(
                                    ticker,
                                    qty
                            );

                    if (covered) {
                        lifecycleOptimizationAgent.recordClose(
                                lifecyclePosition,
                                currentPrice,
                                qty,
                                maxHoldReached ? "MAX_HOLD_EXIT" : "SHORT_MOMENTUM_DRY_UP"
                        );
                        System.out.println(
                                "SHORT POSITION COVERED: " +
                                        ticker +
                                        " qty=" +
                                        qty +
                                        " coverPrice=" +
                                        currentPrice +
                                        " reason=" +
                                        (maxHoldReached ? "MAX_HOLD_EXIT" : "SHORT_MOMENTUM_DRY_UP")
                        );
                    } else {
                        System.out.println(
                                "SHORT POSITION COVER FAILED: " +
                                        ticker
                        );
                    }

                    return;
                }

            } catch (Exception e) {
                System.out.println(
                        "SHORT LIFECYCLE MONITOR ERROR: " +
                                ticker +
                                " " +
                                e.getMessage()
                );

                return;
            }
        }
    }

    private static long parsePositiveLong(String value, long defaultValue) {
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
}
