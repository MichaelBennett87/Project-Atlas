package com.bot.strategy;

import com.bot.model.CatalystType;
import com.bot.model.PerformanceStats;
import com.bot.model.SignalPerformanceRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SignalPerformanceDatabase {

    private final Map<String, SignalPerformanceRecord> signals =
            new ConcurrentHashMap<>();

    public void recordSignal(
            SignalPerformanceRecord record
    ) {
        if (record == null || record.signalId == null || record.signalId.isBlank()) {
            return;
        }

        signals.put(
                record.signalId,
                record
        );

        System.out.println(
                "PERFORMANCE DB: signal recorded " +
                        record.ticker +
                        " catalyst=" +
                        record.catalystType +
                        " entry=" +
                        record.entryPrice
        );
    }

    public void recordSignal(
            String signalId,
            String ticker,
            String catalystType,
            String floatCategory,
            double entryPrice
    ) {
        CatalystType parsedCatalyst =
                parseCatalystType(catalystType);

        SignalPerformanceRecord record =
                new SignalPerformanceRecord(
                        signalId,
                        ticker,
                        parsedCatalyst,
                        "",
                        System.currentTimeMillis(),
                        entryPrice,
                        0.0,
                        floatCategory,
                        0.0,
                        0.0,
                        0.0,
                        0.0
                );

        recordSignal(record);
    }

    public void updatePrice(
            String ticker,
            double price
    ) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }

        for (SignalPerformanceRecord record : signals.values()) {
            if (!record.closed &&
                    ticker.equalsIgnoreCase(record.ticker)) {
                record.updatePrice(price);
            }
        }
    }

    public void closeSignal(
            String signalId,
            double exitPrice
    ) {
        SignalPerformanceRecord record =
                signals.get(signalId);

        if (record == null) {
            return;
        }

        record.exitPrice = exitPrice;
        record.closed = true;

        record.updatePrice(exitPrice);

        System.out.println(
                "PERFORMANCE DB: signal closed " +
                        record.ticker +
                        " exit=" +
                        exitPrice +
                        " gain=" +
                        record.exitGainPercent()
        );
    }

    public void closeSignal(
            String signalId,
            double exitPrice,
            double exitGain,
            double maxGain,
            double maxDrawdown
    ) {
        SignalPerformanceRecord record =
                signals.get(signalId);

        if (record == null) {
            return;
        }

        record.exitPrice = exitPrice;
        record.closed = true;

        if (record.entryPrice > 0) {
            record.maxPriceAfterSignal =
                    record.entryPrice * (1.0 + maxGain);

            record.minPriceAfterSignal =
                    record.entryPrice * (1.0 + maxDrawdown);
        }

        System.out.println(
                "PERFORMANCE DB: signal closed " +
                        record.ticker +
                        " exit=" +
                        exitPrice +
                        " gain=" +
                        record.exitGainPercent()
        );
    }

    public PerformanceStats overallStats() {
        return calculateStats(
                new ArrayList<>(signals.values())
        );
    }

    public PerformanceStats catalystStats(
            String catalystType
    ) {
        CatalystType parsed =
                parseCatalystType(catalystType);

        List<SignalPerformanceRecord> matching =
                new ArrayList<>();

        for (SignalPerformanceRecord record : signals.values()) {
            if (record.catalystType == parsed) {
                matching.add(record);
            }
        }

        return calculateStats(matching);
    }

    public PerformanceStats tickerCatalystStats(
            String ticker,
            String catalystType
    ) {
        CatalystType parsed =
                parseCatalystType(catalystType);

        List<SignalPerformanceRecord> matching =
                new ArrayList<>();

        if (ticker == null || ticker.isBlank()) {
            return calculateStats(matching);
        }

        for (SignalPerformanceRecord record : signals.values()) {
            if (record.ticker != null &&
                    record.ticker.equalsIgnoreCase(ticker) &&
                    record.catalystType == parsed) {
                matching.add(record);
            }
        }

        return calculateStats(matching);
    }

    public PerformanceStats floatCategoryStats(
            String floatCategory
    ) {
        List<SignalPerformanceRecord> matching =
                new ArrayList<>();

        for (SignalPerformanceRecord record : signals.values()) {
            if (record.floatCategory != null &&
                    record.floatCategory.equalsIgnoreCase(floatCategory)) {
                matching.add(record);
            }
        }

        return calculateStats(matching);
    }

    public List<SignalPerformanceRecord> allSignals() {
        return new ArrayList<>(
                signals.values()
        );
    }

    public int size() {
        return signals.size();
    }

    private PerformanceStats calculateStats(
            List<SignalPerformanceRecord> records
    ) {
        int totalSignals =
                records.size();

        int closedSignals =
                0;

        int wins =
                0;

        double totalMaxGain =
                0.0;

        double totalExitGain =
                0.0;

        double totalDrawdown =
                0.0;

        for (SignalPerformanceRecord record : records) {
            if (!record.closed) {
                continue;
            }

            closedSignals++;

            double exitGain =
                    record.exitGainPercent();

            double maxGain =
                    record.maxGainPercent();

            double drawdown =
                    record.maxDrawdownPercent();

            totalExitGain += exitGain;
            totalMaxGain += maxGain;
            totalDrawdown += drawdown;

            if (exitGain > 0) {
                wins++;
            }
        }

        double winRate =
                closedSignals == 0
                        ? 0.0
                        : (double) wins / closedSignals;

        double averageMaxGain =
                closedSignals == 0
                        ? 0.0
                        : totalMaxGain / closedSignals;

        double averageExitGain =
                closedSignals == 0
                        ? 0.0
                        : totalExitGain / closedSignals;

        double averageDrawdown =
                closedSignals == 0
                        ? 0.0
                        : totalDrawdown / closedSignals;

        return new PerformanceStats(
                totalSignals,
                closedSignals,
                winRate,
                averageMaxGain,
                averageExitGain,
                averageDrawdown
        );
    }

    private CatalystType parseCatalystType(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return CatalystType.UNKNOWN;
        }

        try {
            return CatalystType.valueOf(value);
        } catch (Exception e) {
            return CatalystType.UNKNOWN;
        }
    }
}