package com.bot.execution;

import com.bot.broker.AlpacaBroker;

public class ShortMomentumExitMonitor {

    private final AlpacaBroker broker;
    private final ShortStockExecutionService shortExecutor;

    private static final long CHECK_INTERVAL_MS = 5_000;
    private static final long MAX_HOLD_MS = parsePositiveLong(System.getenv("MAX_HOLD_EXIT_AFTER_SECONDS"), 1_200L) * 1000L;
    private static final double COVER_BOUNCE_FROM_LOW = parsePositiveDouble(System.getenv("SHORT_COVER_BOUNCE_FROM_LOW_PCT"), 0.65) / 100.0;
    private static final double PROFIT_LOCK_MIN_DROP = parsePositiveDouble(System.getenv("SHORT_PROFIT_LOCK_MIN_DROP_PCT"), 1.25) / 100.0;
    private static final double PROFIT_LOCK_BOUNCE = parsePositiveDouble(System.getenv("SHORT_PROFIT_LOCK_BOUNCE_PCT"), 0.35) / 100.0;

    public ShortMomentumExitMonitor(
            AlpacaBroker broker,
            ShortStockExecutionService shortExecutor
    ) {
        this.broker = broker;
        this.shortExecutor = shortExecutor;
    }

    public void monitor(
            String ticker,
            int qty,
            double entryPrice
    ) {
        Thread monitorThread =
                new Thread(() ->
                        runMonitor(
                                ticker,
                                qty,
                                entryPrice
                        )
                );

        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private void runMonitor(
            String ticker,
            int qty,
            double entryPrice
    ) {
        double lowestPrice = entryPrice;
        long openedAt = System.currentTimeMillis();

        System.out.println(
                "SHORT EXIT MONITOR STARTED: " +
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

                double bounceFromLow =
                        (currentPrice - lowestPrice) / lowestPrice;

                System.out.println(
                        "SHORT EXIT CHECK: " +
                                ticker +
                                " current=" +
                                currentPrice +
                                " low=" +
                                lowestPrice +
                                " bounceFromLow=" +
                                (bounceFromLow * 100.0) +
                                "%"
                );

                double dropFromEntry =
                        (entryPrice - currentPrice) / entryPrice;

                boolean maxHoldReached =
                        MAX_HOLD_MS > 0 &&
                                System.currentTimeMillis() - openedAt >= MAX_HOLD_MS;

                boolean profitLockBounce =
                        dropFromEntry >= PROFIT_LOCK_MIN_DROP &&
                                bounceFromLow >= PROFIT_LOCK_BOUNCE;

                if (bounceFromLow >= COVER_BOUNCE_FROM_LOW || profitLockBounce || maxHoldReached) {
                    boolean covered =
                            shortExecutor.coverShort(
                                    ticker,
                                    qty
                            );

                    if (covered) {
                        System.out.println(
                                "SHORT COVERED: " +
                                        ticker +
                                        " qty=" +
                                        qty +
                                        " coverPrice=" +
                                        currentPrice +
                                        " reason=" +
                                        (maxHoldReached ? "MAX_HOLD_EXIT" : (profitLockBounce ? "PROFIT_LOCK_BOUNCE" : "BOUNCE_FROM_LOW"))
                        );
                    } else {
                        System.out.println(
                                "SHORT COVER FAILED: " +
                                        ticker
                        );
                    }

                    return;
                }

            } catch (Exception e) {
                System.out.println(
                        "SHORT EXIT MONITOR ERROR: " +
                                ticker +
                                " " +
                                e.getMessage()
                );

                return;
            }
        }
    }

    private static double parsePositiveDouble(String value, double defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (Exception e) {
            return defaultValue;
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
