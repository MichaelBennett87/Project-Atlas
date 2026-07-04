package com.bot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

public class TradingSessionReportMain {

    private static final String SIGNAL_FILE = "signals.csv";
    private static final String TRADE_FILE = "trades.csv";

    public static void main(String[] args) throws Exception {

        System.out.println("========== TRADING SESSION REPORT ==========");
        System.out.println();

        reportSignals();
        reportTrades();

        System.out.println("============================================");
    }

    private static void reportSignals() throws Exception {
        File file = new File(SIGNAL_FILE);

        if (!file.exists()) {
            System.out.println("No signal journal found.");
            System.out.println();
            return;
        }

        int totalSignals = 0;

        Map<String, Integer> decisions = new HashMap<>();
        Map<String, Integer> catalysts = new HashMap<>();
        Map<String, Integer> reasons = new HashMap<>();
        Map<String, Integer> directions = new HashMap<>();
        Map<String, Integer> executionModes = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String[] parts =
                        line.split(",", -1);

                totalSignals++;

                String catalyst =
                        safe(parts, 8);

                String direction;
                String decision;
                String reason;

                if (parts.length >= 17 &&
                        isDirection(safe(parts, 14))) {
                    direction = safe(parts, 14);
                    decision = safe(parts, 15);
                    reason = safe(parts, 16);
                } else {
                    direction = inferDirectionFromOldRow(parts);
                    decision = safe(parts, parts.length - 2);
                    reason = safe(parts, parts.length - 1);
                }

                increment(catalysts, catalyst);
                increment(directions, direction);
                increment(decisions, decision);
                increment(reasons, reason);
                increment(executionModes, executionMode(reason));
            }
        }

        System.out.println("Signals:");
        System.out.println("Total signals: " + totalSignals);
        System.out.println();

        System.out.println("Directions:");
        printMap(directions);
        System.out.println();

        System.out.println("Execution Modes:");
        printMap(executionModes);
        System.out.println();

        System.out.println("Decisions:");
        printMap(decisions);
        System.out.println();

        System.out.println("Catalysts:");
        printMap(catalysts);
        System.out.println();

        System.out.println("Reasons:");
        printMap(reasons);
        System.out.println();
    }

    private static void reportTrades() throws Exception {
        File file = new File(TRADE_FILE);

        if (!file.exists()) {
            System.out.println("No trade journal found.");
            return;
        }

        int buys = 0;
        int sells = 0;
        int shorts = 0;
        int covers = 0;
        int protectiveStops = 0;
        int canceledOrders = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String upper =
                        line.toUpperCase();

                if (upper.contains(",BUY,")) {
                    buys++;
                }

                if (upper.contains(",SELL,")) {
                    sells++;
                }

                if (upper.contains(",SHORT,")) {
                    shorts++;
                }

                if (upper.contains(",COVER_SHORT,")) {
                    covers++;
                }

                if (upper.contains("PROTECTIVE_STOP")) {
                    protectiveStops++;
                }

                if (upper.contains("CANCEL")) {
                    canceledOrders++;
                }
            }
        }

        System.out.println("Trades:");
        System.out.println("Buys: " + buys);
        System.out.println("Sells: " + sells);
        System.out.println("Shorts: " + shorts);
        System.out.println("Covers: " + covers);
        System.out.println("Protective stops: " + protectiveStops);
        System.out.println("Canceled orders: " + canceledOrders);
    }

    private static String executionMode(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }

        String upper =
                reason.toUpperCase();

        if (upper.contains("MARKET_CLOSED")) {
            return "MARKET_CLOSED";
        }

        if (upper.contains("KILL_SWITCH")) {
            return "KILL_SWITCH_ACTIVE";
        }

        if (upper.contains("TRADING_DISABLED")) {
            return "TRADING_DISABLED";
        }

        if (upper.contains("SHORT_STOCK_DISABLED")) {
            return "SHORT_STOCK_DISABLED";
        }

        if (upper.contains("MOMENTUM_NOT_CONFIRMED") ||
                upper.contains("DOWNSIDE_MOMENTUM_NOT_CONFIRMED")) {
            return "MOMENTUM_NOT_CONFIRMED";
        }

        if (upper.contains("ORDER_NOT_FILLED")) {
            return "ORDER_NOT_FILLED";
        }

        if (upper.contains("ENTRY_FILLED") ||
                upper.contains("SHORT_STOCK_ENTRY_FILLED")) {
            return "EXECUTED";
        }

        if (upper.contains("SIGNAL_ONLY")) {
            return "SIGNAL_ONLY";
        }

        return "OTHER";
    }

    private static String inferDirectionFromOldRow(String[] parts) {
        String catalyst =
                safe(parts, 8);

        String reason =
                safe(parts, parts.length - 1);

        if (reason.contains("SHORT_SETUP") ||
                reason.contains("SHORT_STOCK") ||
                reason.contains("SHORT_ORDER") ||
                reason.contains("SHORT_DOWNSIDE")) {
            return "SHORT_STOCK";
        }

        if (reason.contains("PUT_SETUP") ||
                catalyst.equals("GUIDANCE_CUT") ||
                catalyst.equals("EARNINGS_MISS") ||
                catalyst.equals("OFFERING_DILUTION") ||
                catalyst.equals("PRIVATE_PLACEMENT") ||
                catalyst.equals("FDA_REJECTION") ||
                catalyst.equals("CLINICAL_TRIAL_FAILURE") ||
                catalyst.equals("DELISTING_RISK") ||
                catalyst.equals("NASDAQ_NONCOMPLIANCE") ||
                catalyst.equals("BANKRUPTCY") ||
                catalyst.equals("SHORT_SELLER_REPORT")) {
            return "LONG_PUT";
        }

        if (catalyst.equals("FDA_APPROVAL") ||
                catalyst.equals("FDA_CLEARANCE") ||
                catalyst.equals("CLINICAL_TRIAL_SUCCESS") ||
                catalyst.equals("DRUG_DATA_POSITIVE") ||
                catalyst.equals("MAJOR_CONTRACT") ||
                catalyst.equals("MAJOR_ORDER") ||
                catalyst.equals("SHARE_BUYBACK") ||
                catalyst.equals("NASDAQ_COMPLIANCE") ||
                catalyst.equals("NASDAQ_COMPLIANCE_EXTENSION") ||
                catalyst.equals("NYSE_COMPLIANCE") ||
                catalyst.equals("EXCHANGE_COMPLIANCE") ||
                catalyst.equals("INDEX_ADDITION") ||
                catalyst.equals("MERGER_ACQUISITION") ||
                catalyst.equals("BUYOUT_OFFER")) {
            return "LONG_STOCK";
        }

        return "NO_TRADE";
    }

    private static boolean isDirection(String value) {
        return value.equals("LONG_STOCK") ||
                value.equals("SHORT_STOCK") ||
                value.equals("LONG_CALL") ||
                value.equals("LONG_PUT") ||
                value.equals("NO_TRADE");
    }

    private static String safe(String[] parts, int index) {
        if (parts == null || index < 0 || index >= parts.length) {
            return "";
        }

        return parts[index] == null ? "" : parts[index].trim();
    }

    private static void increment(
            Map<String, Integer> map,
            String key
    ) {
        if (key == null || key.isBlank()) {
            key = "UNKNOWN";
        }

        map.put(
                key,
                map.getOrDefault(key, 0) + 1
        );
    }

    private static void printMap(Map<String, Integer> map) {
        if (map.isEmpty()) {
            return;
        }

        map.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry ->
                        System.out.println(
                                entry.getKey() +
                                        ": " +
                                        entry.getValue()
                        )
                );
    }
}