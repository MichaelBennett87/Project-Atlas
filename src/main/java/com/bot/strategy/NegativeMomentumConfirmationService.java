package com.bot.strategy;

import com.bot.broker.AlpacaBroker;

public class NegativeMomentumConfirmationService {

    private final AlpacaBroker broker;

    private static final double REQUIRED_DOWN_MOVE = -0.003; // -0.30%
    private static final long WAIT_MS = 10_000;

    public NegativeMomentumConfirmationService(
            AlpacaBroker broker
    ) {
        this.broker = broker;
    }

    public boolean confirmDownside(String ticker) {

        try {

            double startPrice =
                    broker.getPrice(ticker);

            System.out.println(
                    "NEGATIVE MOMENTUM START: " +
                            ticker +
                            " price=" +
                            startPrice
            );

            Thread.sleep(WAIT_MS);

            double currentPrice =
                    broker.getPrice(ticker);

            double pctMove =
                    (currentPrice - startPrice) / startPrice;

            System.out.println(
                    "NEGATIVE MOMENTUM CHECK: " +
                            ticker +
                            " start=" +
                            startPrice +
                            " current=" +
                            currentPrice +
                            " move=" +
                            (pctMove * 100.0) +
                            "%"
            );

            return pctMove <= REQUIRED_DOWN_MOVE;

        } catch (Exception e) {

            System.err.println(
                    "Negative momentum confirmation failed: " +
                            e.getMessage()
            );

            return false;
        }
    }
}