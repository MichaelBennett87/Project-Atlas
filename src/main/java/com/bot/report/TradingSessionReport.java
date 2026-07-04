package com.bot.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class TradingSessionReport {

    private static final Path SIGNAL_JOURNAL =
            Path.of("logs", "signal-journal.csv");

    private static final Path TRADE_JOURNAL =
            Path.of("logs", "trade-journal.csv");

    public void printReport() {
        System.out.println("========== TRADING SESSION REPORT ==========");

        printSignalReport();
        printTradeReport();

        System.out.println("============================================");
    }

    private void printSignalReport() {
        if (!Files.exists(SIGNAL_JOURNAL)) {
            System.out.println("No signal journal found.");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(SIGNAL_JOURNAL);

            if (lines.size() <= 1) {
                System.out.println("No signal entries found.");
                return;
            }

            int totalSignals = 0;

            Map<String, Integer> decisions = new HashMap<>();
            Map<String, Integer> reasons = new HashMap<>();
            Map<String, Integer> catalysts = new HashMap<>();

            for (int i = 1; i < lines.size(); i++) {
                String[] cols = lines.get(i).split(",", -1);

                if (cols.length < 21) {
                    continue;
                }

                totalSignals++;

                String catalyst = cols[8];
                String decision = cols[19];
                String reason = cols[20];

                increment(catalysts, catalyst);
                increment(decisions, decision);
                increment(reasons, reason);
            }

            System.out.println();
            System.out.println("Signals:");
            System.out.println("Total signals: " + totalSignals);

            printTop("Decisions", decisions);
            printTop("Catalysts", catalysts);
            printTop("Reasons", reasons);

        } catch (IOException e) {
            System.err.println("Failed reading signal journal: " + e.getMessage());
        }
    }

    private void printTradeReport() {
        if (!Files.exists(TRADE_JOURNAL)) {
            System.out.println();
            System.out.println("No trade journal found.");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(TRADE_JOURNAL);

            if (lines.isEmpty()) {
                System.out.println();
                System.out.println("No trade entries found.");
                return;
            }

            int buys = 0;
            int sells = 0;
            int stops = 0;
            int cancels = 0;

            for (String line : lines) {
                if (line.contains(",BUY,")) buys++;
                if (line.contains(",SELL,")) sells++;
                if (line.contains(",STOP_SELL,")) stops++;
                if (line.contains(",CANCEL_ORDER,")) cancels++;
            }

            System.out.println();
            System.out.println("Trades:");
            System.out.println("Buys: " + buys);
            System.out.println("Sells: " + sells);
            System.out.println("Protective stops: " + stops);
            System.out.println("Canceled orders: " + cancels);

        } catch (IOException e) {
            System.err.println("Failed reading trade journal: " + e.getMessage());
        }
    }

    private void increment(Map<String, Integer> map, String key) {
        if (key == null || key.isBlank()) {
            key = "BLANK";
        }

        map.put(key, map.getOrDefault(key, 0) + 1);
    }

    private void printTop(String label, Map<String, Integer> map) {
        System.out.println();
        System.out.println(label + ":");

        map.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(entry ->
                        System.out.println(
                                entry.getKey() + ": " + entry.getValue()
                        )
                );
    }
}