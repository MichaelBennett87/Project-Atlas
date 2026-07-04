package com.bot;

import com.bot.broker.AlpacaBroker;
import com.bot.engine.PositionManager;
import com.bot.execution.OrderExecutor;
import com.bot.journal.TradeJournal;
import com.bot.model.MarketDataCache;

public class ExitSimulationTest {

    public static void main(String[] args) {
        runHardDropFromPeakExitSimulationTest();
    }

    private static void runHardDropFromPeakExitSimulationTest() {
        AlpacaBroker broker =
                new AlpacaBroker(true);

        MarketDataCache marketData =
                new MarketDataCache();

        TradeJournal tradeJournal =
                new TradeJournal();

        OrderExecutor orderExecutor =
                new OrderExecutor(
                        broker,
                        tradeJournal
                );

        PositionManager positionManager =
                new PositionManager(
                        marketData,
                        orderExecutor
                );

        String ticker =
                "SMCI";

        double entryPrice =
                100.00;

        int quantity =
                1;

        positionManager.open(
                ticker,
                entryPrice,
                quantity
        );

        boolean positionOpenedBeforeExit =
                positionManager.hasOpenPosition(ticker);

        double peakPrice =
                110.00;

        positionManager.onPrice(
                ticker,
                peakPrice
        );

        boolean positionStillOpenAtPeak =
                positionManager.hasOpenPosition(ticker);

        double exitTriggerPrice =
                104.40;

        double dropFromPeak =
                (exitTriggerPrice - peakPrice) / peakPrice;

        positionManager.onPrice(
                ticker,
                exitTriggerPrice
        );

        boolean positionClosedAfterDrop =
                !positionManager.hasOpenPosition(ticker);

        boolean expectedPositionOpenedBeforeExit =
                true;

        boolean expectedPositionStillOpenAtPeak =
                true;

        boolean expectedDropMoreThanFivePercent =
                dropFromPeak <= -0.05;

        boolean expectedPositionClosedAfterDrop =
                true;

        boolean passed =
                positionOpenedBeforeExit == expectedPositionOpenedBeforeExit &&
                        positionStillOpenAtPeak == expectedPositionStillOpenAtPeak &&
                        expectedDropMoreThanFivePercent &&
                        positionClosedAfterDrop == expectedPositionClosedAfterDrop;

        System.out.println();
        System.out.println("=== EXIT SIMULATION TEST ===");
        System.out.println("Ticker: " + ticker);
        System.out.println("Entry price: " + entryPrice);
        System.out.println("Peak price: " + peakPrice);
        System.out.println("Exit trigger price: " + exitTriggerPrice);
        System.out.println("Drop from peak: " + dropFromPeak);
        System.out.println("Expected drop <= -0.05: true");
        System.out.println("Actual drop <= -0.05: " + expectedDropMoreThanFivePercent);
        System.out.println("Expected position opened before exit: true");
        System.out.println("Actual position opened before exit: " + positionOpenedBeforeExit);
        System.out.println("Expected position still open at peak: true");
        System.out.println("Actual position still open at peak: " + positionStillOpenAtPeak);
        System.out.println("Expected position closed after drop: true");
        System.out.println("Actual position closed after drop: " + positionClosedAfterDrop);
        System.out.println();
        System.out.println("Expected final result: PASS");
        System.out.println("Actual final result: " + (passed ? "PASS" : "FAIL"));

        if (!passed) {
            throw new IllegalStateException(
                    "Exit simulation failed."
            );
        }
    }
}