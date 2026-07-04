package com.bot.intelligence;

import com.bot.model.Position;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Writes ML-ready trade outcome rows. This journal is intentionally simple CSV
 * so the bot can collect training data during paper trading without requiring a
 * database server. It records opens, partial exits, full closes, P/L, max run-up,
 * max drawdown, hold time, and the strategy that created the position.
 */
public class FeatureOutcomeJournal {

    private final Path path;
    private boolean headerWritten;
    private final Set<String> openKeys = new HashSet<>();
    private final PredictionOutcomeJournal predictionOutcomeJournal = new PredictionOutcomeJournal();

    public FeatureOutcomeJournal() {
        this(Path.of(System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv")));
    }

    public FeatureOutcomeJournal(Path path) {
        this.path = path;
        this.headerWritten = Files.exists(path);
        loadExistingOpenKeys();
    }

    public synchronized void recordOpen(Position position) {
        if (position == null) {
            return;
        }

        String key = openKey(position);
        if (!openKeys.add(key)) {
            return;
        }

        write(
                "OPEN",
                position,
                position.entryPrice,
                position.quantity,
                0.0,
                "POSITION_OPENED"
        );
        predictionOutcomeJournal.recordOpen(position);
    }

    public synchronized void recordPartialExit(Position position, double exitPrice, int quantitySold, String reason) {
        if (position == null) {
            return;
        }
        write(
                "PARTIAL_EXIT",
                position,
                exitPrice,
                quantitySold,
                realizedProfit(position, exitPrice, quantitySold),
                reason
        );
        predictionOutcomeJournal.recordPartialExit(position, exitPrice, quantitySold, reason);
    }

    public synchronized void recordClose(Position position, double exitPrice, int quantityClosed, String reason) {
        if (position == null) {
            return;
        }
        if (quantityClosed > 0 && position.quantity > 0 && quantityClosed < position.quantity) {
            recordPartialExit(position, exitPrice, quantityClosed, reason);
            return;
        }
        write(
                "CLOSE",
                position,
                exitPrice,
                quantityClosed,
                realizedProfit(position, exitPrice, quantityClosed),
                reason
        );
        predictionOutcomeJournal.recordClose(position, exitPrice, quantityClosed, reason);
        openKeys.remove(openKey(position));
    }

    private void loadExistingOpenKeys() {
        if (!Files.exists(path)) {
            return;
        }
        try {
            java.util.List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.size() <= 1) {
                return;
            }
            String[] header = lines.get(0).split(",", -1);
            int eventIdx = indexOf(header, "eventType");
            int tickerIdx = indexOf(header, "ticker");
            int sideIdx = indexOf(header, "side");
            if (eventIdx < 0 || tickerIdx < 0 || sideIdx < 0) {
                return;
            }
            for (int i = 1; i < lines.size(); i++) {
                String[] cols = lines.get(i).split(",", -1);
                if (cols.length <= Math.max(eventIdx, Math.max(tickerIdx, sideIdx))) {
                    continue;
                }
                String event = clean(cols[eventIdx]);
                String ticker = clean(cols[tickerIdx]).toUpperCase();
                String side = clean(cols[sideIdx]).toUpperCase();
                if (ticker.isBlank() || side.isBlank()) {
                    continue;
                }
                String key = ticker + "|" + side;
                if ("OPEN".equalsIgnoreCase(event)) {
                    openKeys.add(key);
                } else if ("CLOSE".equalsIgnoreCase(event)) {
                    openKeys.remove(key);
                }
            }
        } catch (Exception e) {
            System.out.println("TRADE OUTCOME JOURNAL OPEN-STATE LOAD FAILED: " + e.getMessage());
        }
    }

    private static int indexOf(String[] header, String name) {
        if (header == null) {
            return -1;
        }
        for (int i = 0; i < header.length; i++) {
            if (name.equalsIgnoreCase(clean(header[i]))) {
                return i;
            }
        }
        return -1;
    }

    private static String clean(String v) {
        if (v == null) {
            return "";
        }
        String out = v.trim();
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1).replace("\"\"", "\"");
        }
        return out;
    }

    private void write(
            String eventType,
            Position position,
            double eventPrice,
            int eventQuantity,
            double realizedPnlDollars,
            String reason
    ) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (!headerWritten) {
                Files.writeString(path, header() + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                headerWritten = true;
            }

            long now = System.currentTimeMillis();
            long holdMs = position.openedAt <= 0 ? 0L : Math.max(0L, now - position.openedAt);
            double entry = safe(position.entryPrice);
            double event = safe(eventPrice);
            double currentPnlPercent = entry <= 0 ? 0.0 : (event - entry) / entry * (position.isShortPosition() ? -1.0 : 1.0);
            double maxGainPercent = maxGainPercent(position);
            double maxDrawdownPercent = maxDrawdownPercent(position);
            double exitEfficiencyPercent = maxGainPercent <= 0.0
                    ? (currentPnlPercent > 0.0 ? 1.0 : 0.0)
                    : Math.max(0.0, Math.min(1.0, currentPnlPercent / maxGainPercent));
            double giveBackPercent = Math.max(0.0, maxGainPercent - currentPnlPercent);

            String row = String.join(",",
                    escape(Instant.ofEpochMilli(now).toString()),
                    escape(eventType),
                    escape(position.ticker),
                    escape(position.strategyName),
                    escape(position.isShortPosition() ? "SHORT" : "LONG"),
                    Long.toString(position.openedAt),
                    Long.toString(now),
                    Long.toString(holdMs),
                    Integer.toString(position.initialQuantity),
                    Integer.toString(position.quantity),
                    Integer.toString(eventQuantity),
                    value(entry),
                    value(event),
                    value(position.peakPrice),
                    value(position.troughPrice),
                    value(currentPnlPercent),
                    value(maxGainPercent),
                    value(maxDrawdownPercent),
                    value(exitEfficiencyPercent),
                    value(giveBackPercent),
                    value(realizedPnlDollars),
                    Boolean.toString(position.partialProfitTaken),
                    Boolean.toString(position.syncedFromBroker),
                    escape(reason)
            );

            Files.writeString(path, row + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("TRADE OUTCOME JOURNAL WRITE FAILED: " + e.getMessage());
        }
    }

    private static String header() {
        return String.join(",",
                "timestamp",
                "eventType",
                "ticker",
                "strategyName",
                "side",
                "openedAtMs",
                "eventAtMs",
                "holdMs",
                "initialQuantity",
                "remainingQuantityBeforeEvent",
                "eventQuantity",
                "entryPrice",
                "eventPrice",
                "peakPrice",
                "troughPrice",
                "currentPnlPercent",
                "maxGainPercent",
                "maxDrawdownPercent",
                "exitEfficiencyPercent",
                "giveBackPercent",
                "realizedPnlDollars",
                "partialProfitTaken",
                "syncedFromBroker",
                "reason"
        );
    }

    private static double realizedProfit(Position position, double exitPrice, int qty) {
        if (position == null || qty <= 0) {
            return 0.0;
        }
        double raw = (safe(exitPrice) - safe(position.entryPrice)) * qty;
        return position.isShortPosition() ? -raw : raw;
    }

    private static double maxGainPercent(Position position) {
        if (position == null || position.entryPrice <= 0) {
            return 0.0;
        }
        if (position.isShortPosition()) {
            double trough = position.troughPrice > 0 ? position.troughPrice : position.entryPrice;
            return (position.entryPrice - trough) / position.entryPrice;
        }
        double peak = position.peakPrice > 0 ? position.peakPrice : position.entryPrice;
        return (peak - position.entryPrice) / position.entryPrice;
    }

    private static double maxDrawdownPercent(Position position) {
        if (position == null || position.entryPrice <= 0) {
            return 0.0;
        }
        if (position.isShortPosition()) {
            double peak = position.peakPrice > 0 ? position.peakPrice : position.entryPrice;
            return (peak - position.entryPrice) / position.entryPrice;
        }
        double trough = position.troughPrice > 0 ? position.troughPrice : position.entryPrice;
        return (position.entryPrice - trough) / position.entryPrice;
    }


    private static String openKey(Position position) {
        if (position == null) {
            return "NULL";
        }
        String ticker = position.ticker == null ? "" : position.ticker.trim().toUpperCase();
        String side = position.isShortPosition() ? "SHORT" : "LONG";
        return ticker + "|" + side;
    }

    private static double safe(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0.0 : value;
    }

    private static String value(double value) {
        return Double.toString(safe(value));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\n', ' ').replace('\r', ' ');
        if (cleaned.contains(",") || cleaned.contains("\"")) {
            return "\"" + cleaned.replace("\"", "\"\"") + "\"";
        }
        return cleaned;
    }
}
