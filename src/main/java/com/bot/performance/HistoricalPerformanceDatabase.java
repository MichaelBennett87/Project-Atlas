package com.bot.performance;

import com.bot.model.CatalystType;
import com.bot.model.PerformanceStats;
import com.bot.model.SignalPerformanceRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class HistoricalPerformanceDatabase {

    private final List<SignalPerformanceRecord> records =
            new ArrayList<>();

    public synchronized void recordSignal(SignalPerformanceRecord record) {
        if (record == null || record.signalId == null || record.signalId.isBlank()) {
            return;
        }

        records.add(record);

        System.out.println(
                "PERFORMANCE DB: signal recorded " +
                        record.ticker +
                        " catalyst=" +
                        record.catalystType +
                        " entry=" +
                        record.entryPrice +
                        " float=" +
                        record.floatCategory +
                        " marketCap=" +
                        record.marketCapCategory
        );
    }

    public synchronized void recordSignal(
            String signalId,
            String ticker,
            CatalystType catalystType,
            String headline,
            long signalTimestamp,
            double entryPrice,
            double floatScore,
            String floatCategory,
            long marketCap,
            double marketCapScore,
            String marketCapCategory,
            double marketQualityScore,
            double sentimentNet,
            double sentimentPositive,
            double sentimentNegative,
            double relativeVolume,
            double gapPercent,
            String sectorCategory,
            String level2Category
    ) {
        recordSignal(
                new SignalPerformanceRecord(
                        signalId,
                        ticker,
                        catalystType,
                        headline,
                        signalTimestamp,
                        entryPrice,
                        floatScore,
                        floatCategory,
                        marketCap,
                        marketCapScore,
                        marketCapCategory,
                        marketQualityScore,
                        sentimentNet,
                        sentimentPositive,
                        sentimentNegative,
                        relativeVolume,
                        gapPercent,
                        sectorCategory,
                        level2Category
                )
        );
    }

    public synchronized void updatePrice(
            String ticker,
            double price
    ) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }

        for (SignalPerformanceRecord record : records) {
            if (!record.closed &&
                    ticker.equalsIgnoreCase(record.ticker)) {
                record.updatePrice(price);
            }
        }
    }

    public synchronized void markPriceAfterMinutes(
            String signalId,
            int minutes,
            double price
    ) {
        SignalPerformanceRecord record =
                findBySignalId(signalId);

        if (record == null) {
            return;
        }

        record.markPriceAfterMinutes(
                minutes,
                price
        );
    }

    public synchronized void closeSignal(
            String signalId,
            double exitPrice
    ) {
        SignalPerformanceRecord record =
                findBySignalId(signalId);

        if (record == null) {
            return;
        }

        record.updatePrice(exitPrice);
        record.exitPrice = exitPrice;
        record.closed = true;

        System.out.println(
                "PERFORMANCE DB: signal closed " +
                        record.ticker +
                        " exit=" +
                        exitPrice +
                        " gain=" +
                        record.exitGainPercent()
        );
    }

    public synchronized PerformanceStats overallStats() {
        return calculateStats(records);
    }

    public synchronized PerformanceStats statsForTicker(
            String ticker
    ) {
        return calculateStats(
                records.stream()
                        .filter(record ->
                                record.ticker != null &&
                                        record.ticker.equalsIgnoreCase(ticker)
                        )
                        .collect(Collectors.toList())
        );
    }

    public synchronized PerformanceStats statsForCatalyst(
            CatalystType catalystType
    ) {
        return calculateStats(
                records.stream()
                        .filter(record -> record.catalystType == catalystType)
                        .collect(Collectors.toList())
        );
    }

    public synchronized PerformanceStats statsForTickerAndCatalyst(
            String ticker,
            CatalystType catalystType
    ) {
        return calculateStats(
                records.stream()
                        .filter(record ->
                                record.ticker != null &&
                                        record.ticker.equalsIgnoreCase(ticker) &&
                                        record.catalystType == catalystType
                        )
                        .collect(Collectors.toList())
        );
    }

    public synchronized PerformanceStats statsForFloatCategory(
            String floatCategory
    ) {
        return calculateStats(
                records.stream()
                        .filter(record ->
                                record.floatCategory != null &&
                                        record.floatCategory.equalsIgnoreCase(floatCategory)
                        )
                        .collect(Collectors.toList())
        );
    }

    public synchronized PerformanceStats statsForMarketCapCategory(
            String marketCapCategory
    ) {
        return calculateStats(
                records.stream()
                        .filter(record ->
                                record.marketCapCategory != null &&
                                        record.marketCapCategory.equalsIgnoreCase(marketCapCategory)
                        )
                        .collect(Collectors.toList())
        );
    }

    public synchronized PerformanceStats statsForSetup(
            String ticker,
            CatalystType catalystType,
            String floatCategory,
            String marketCapCategory
    ) {
        return calculateStats(
                records.stream()
                        .filter(record ->
                                record.ticker != null &&
                                        record.ticker.equalsIgnoreCase(ticker) &&
                                        record.catalystType == catalystType &&
                                        record.floatCategory != null &&
                                        record.floatCategory.equalsIgnoreCase(floatCategory) &&
                                        record.marketCapCategory != null &&
                                        record.marketCapCategory.equalsIgnoreCase(marketCapCategory)
                        )
                        .collect(Collectors.toList())
        );
    }

    public synchronized List<SignalPerformanceRecord> bestSignals(
            int limit
    ) {
        return records.stream()
                .sorted(
                        Comparator.comparingDouble(SignalPerformanceRecord::maxGainPercent)
                                .reversed()
                )
                .limit(limit)
                .collect(Collectors.toList());
    }

    public synchronized List<SignalPerformanceRecord> worstSignals(
            int limit
    ) {
        return records.stream()
                .sorted(
                        Comparator.comparingDouble(SignalPerformanceRecord::maxDrawdownPercent)
                )
                .limit(limit)
                .collect(Collectors.toList());
    }

    public synchronized List<SignalPerformanceRecord> allRecords() {
        return new ArrayList<>(records);
    }

    public synchronized int size() {
        return records.size();
    }

    private SignalPerformanceRecord findBySignalId(
            String signalId
    ) {
        if (signalId == null || signalId.isBlank()) {
            return null;
        }

        for (SignalPerformanceRecord record : records) {
            if (signalId.equals(record.signalId)) {
                return record;
            }
        }

        return null;
    }

    private PerformanceStats calculateStats(
            List<SignalPerformanceRecord> source
    ) {
        if (source == null || source.isEmpty()) {
            return new PerformanceStats(
                    0,
                    0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0
            );
        }

        int totalSignals =
                source.size();

        int closedSignals =
                0;

        int wins =
                0;

        int fiveMinuteSamples =
                0;

        int fifteenMinuteSamples =
                0;

        int thirtyMinuteSamples =
                0;

        int sixtyMinuteSamples =
                0;

        int relativeVolumeSamples =
                0;

        int gapSamples =
                0;

        double totalMaxGain =
                0.0;

        double totalExitGain =
                0.0;

        double totalDrawdown =
                0.0;

        double totalGainAfter5Minutes =
                0.0;

        double totalGainAfter15Minutes =
                0.0;

        double totalGainAfter30Minutes =
                0.0;

        double totalGainAfter60Minutes =
                0.0;

        double totalRelativeVolume =
                0.0;

        double totalGapPercent =
                0.0;

        for (SignalPerformanceRecord record : source) {
            totalMaxGain += record.maxGainPercent();
            totalDrawdown += record.maxDrawdownPercent();

            if (record.closed) {
                closedSignals++;
                totalExitGain += record.exitGainPercent();

                if (record.exitGainPercent() > 0) {
                    wins++;
                }
            }

            if (record.priceAfter5Minutes > 0) {
                fiveMinuteSamples++;
                totalGainAfter5Minutes += record.gainAfter5Minutes();
            }

            if (record.priceAfter15Minutes > 0) {
                fifteenMinuteSamples++;
                totalGainAfter15Minutes += record.gainAfter15Minutes();
            }

            if (record.priceAfter30Minutes > 0) {
                thirtyMinuteSamples++;
                totalGainAfter30Minutes += record.gainAfter30Minutes();
            }

            if (record.priceAfter60Minutes > 0) {
                sixtyMinuteSamples++;
                totalGainAfter60Minutes += record.gainAfter60Minutes();
            }

            if (record.relativeVolume > 0) {
                relativeVolumeSamples++;
                totalRelativeVolume += record.relativeVolume;
            }

            if (record.gapPercent != 0.0) {
                gapSamples++;
                totalGapPercent += record.gapPercent;
            }
        }

        double winRate =
                closedSignals == 0
                        ? 0.0
                        : (double) wins / closedSignals;

        double averageMaxGain =
                totalMaxGain / totalSignals;

        double averageDrawdown =
                totalDrawdown / totalSignals;

        double averageExitGain =
                closedSignals == 0
                        ? 0.0
                        : totalExitGain / closedSignals;

        double averageGainAfter5Minutes =
                fiveMinuteSamples == 0
                        ? 0.0
                        : totalGainAfter5Minutes / fiveMinuteSamples;

        double averageGainAfter15Minutes =
                fifteenMinuteSamples == 0
                        ? 0.0
                        : totalGainAfter15Minutes / fifteenMinuteSamples;

        double averageGainAfter30Minutes =
                thirtyMinuteSamples == 0
                        ? 0.0
                        : totalGainAfter30Minutes / thirtyMinuteSamples;

        double averageGainAfter60Minutes =
                sixtyMinuteSamples == 0
                        ? 0.0
                        : totalGainAfter60Minutes / sixtyMinuteSamples;

        double averageRelativeVolume =
                relativeVolumeSamples == 0
                        ? 0.0
                        : totalRelativeVolume / relativeVolumeSamples;

        double averageGapPercent =
                gapSamples == 0
                        ? 0.0
                        : totalGapPercent / gapSamples;

        return new PerformanceStats(
                totalSignals,
                closedSignals,
                winRate,
                averageMaxGain,
                averageExitGain,
                averageDrawdown,
                averageGainAfter5Minutes,
                averageGainAfter15Minutes,
                averageGainAfter30Minutes,
                averageGainAfter60Minutes,
                averageRelativeVolume,
                averageGapPercent
        );
    }
}