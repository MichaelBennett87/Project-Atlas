package com.bot.intelligence;

import com.bot.model.EntryContextSnapshot;
import com.bot.model.Position;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class PredictionOutcomeJournal {

    private final Path path;
    private boolean headerWritten;

    public PredictionOutcomeJournal() {
        this(Path.of(System.getenv().getOrDefault("PREDICTION_OUTCOME_JOURNAL_PATH", "logs/prediction_outcomes.csv")));
    }

    public PredictionOutcomeJournal(Path path) {
        this.path = path;
        this.headerWritten = Files.exists(path);
    }

    public synchronized void recordOpen(Position position) {
        if (position == null) {
            return;
        }
        write("OPEN", position, position.entryPrice, position.quantity, 0.0, "POSITION_OPENED");
    }

    public synchronized void recordPartialExit(Position position, double exitPrice, int quantity, String reason) {
        if (position == null) {
            return;
        }
        write("PARTIAL_EXIT", position, exitPrice, quantity, realizedProfit(position, exitPrice, quantity), reason);
    }

    public synchronized void recordClose(Position position, double exitPrice, int quantity, String reason) {
        if (position == null) {
            return;
        }
        write("CLOSE", position, exitPrice, quantity, realizedProfit(position, exitPrice, quantity), reason);
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
                Files.writeString(
                        path,
                        header() + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
                headerWritten = true;
            }

            long now = System.currentTimeMillis();
            long holdMs = position.openedAt <= 0 ? 0L : Math.max(0L, now - position.openedAt);
            EntryContextSnapshot context =
                    position.entryContext == null ? EntryContextSnapshot.none() : position.entryContext;
            double entry = safe(position.entryPrice);
            double event = safe(eventPrice);
            double currentPnlPercent =
                    entry <= 0.0 ? 0.0 : (event - entry) / entry * (position.isShortPosition() ? -1.0 : 1.0);

            String row = String.join(",",
                    escape(Instant.ofEpochMilli(now).toString()),
                    escape(eventType),
                    escape(context.entryContextId),
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
                    value(context.probabilityTarget),
                    value(context.probabilityStop),
                    value(context.expectedValuePercent),
                    value(context.predictionConfidence),
                    value(position.entryPriorityScore),
                    escape(context.marketRegime),
                    value(context.rvol),
                    value(context.return3Bars),
                    value(context.vwapDistance),
                    value(context.sentimentNet),
                    value(context.catalystScore),
                    value(context.freshnessSeconds),
                    value(currentPnlPercent),
                    value(maxGainPercent(position)),
                    value(maxDrawdownPercent(position)),
                    value(realizedPnlDollars),
                    Boolean.toString(position.syncedFromBroker),
                    escape(reason),
                    escape(context.predictionReason)
            );

            Files.writeString(
                    path,
                    row + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.out.println("PREDICTION OUTCOME JOURNAL WRITE FAILED: " + e.getMessage());
        }
    }

    private static String header() {
        return String.join(",",
                "timestamp",
                "eventType",
                "entryContextId",
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
                "pTarget",
                "pStop",
                "expectedValuePercent",
                "predictionConfidence",
                "entryPriorityScore",
                "entryMarketRegime",
                "entryRvol",
                "entryReturn3Bars",
                "entryVwapDistance",
                "entrySentimentNet",
                "entryCatalystScore",
                "entryFreshnessSeconds",
                "currentPnlPercent",
                "maxGainPercent",
                "maxDrawdownPercent",
                "realizedPnlDollars",
                "syncedFromBroker",
                "reason",
                "predictionReason"
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
        if (position == null || position.entryPrice <= 0.0) {
            return 0.0;
        }
        if (position.isShortPosition()) {
            double trough = position.troughPrice > 0.0 ? position.troughPrice : position.entryPrice;
            return (position.entryPrice - trough) / position.entryPrice;
        }
        double peak = position.peakPrice > 0.0 ? position.peakPrice : position.entryPrice;
        return (peak - position.entryPrice) / position.entryPrice;
    }

    private static double maxDrawdownPercent(Position position) {
        if (position == null || position.entryPrice <= 0.0) {
            return 0.0;
        }
        if (position.isShortPosition()) {
            double peak = position.peakPrice > 0.0 ? position.peakPrice : position.entryPrice;
            return (peak - position.entryPrice) / position.entryPrice;
        }
        double trough = position.troughPrice > 0.0 ? position.troughPrice : position.entryPrice;
        return (position.entryPrice - trough) / position.entryPrice;
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
