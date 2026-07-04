package com.bot.model;

public class SignalPerformanceRecord {

    public final String signalId;
    public final String ticker;
    public final CatalystType catalystType;
    public final String headline;
    public final long signalTimestamp;
    public final double entryPrice;
    public final double floatScore;
    public final String floatCategory;
    public final long marketCap;
    public final double marketCapScore;
    public final String marketCapCategory;
    public final double marketQualityScore;
    public final double sentimentNet;
    public final double sentimentPositive;
    public final double sentimentNegative;
    public final double relativeVolume;
    public final double gapPercent;
    public final String sectorCategory;
    public final String level2Category;

    public double maxPriceAfterSignal;
    public double minPriceAfterSignal;
    public double priceAfter5Minutes;
    public double priceAfter15Minutes;
    public double priceAfter30Minutes;
    public double priceAfter60Minutes;
    public double exitPrice;
    public boolean closed;

    public SignalPerformanceRecord(
            String signalId,
            String ticker,
            CatalystType catalystType,
            String headline,
            long signalTimestamp,
            double entryPrice,
            double floatScore,
            String floatCategory,
            double marketQualityScore,
            double sentimentNet,
            double sentimentPositive,
            double sentimentNegative
    ) {
        this(
                signalId,
                ticker,
                catalystType,
                headline,
                signalTimestamp,
                entryPrice,
                floatScore,
                floatCategory,
                0L,
                0.0,
                "UNKNOWN_MARKET_CAP",
                marketQualityScore,
                sentimentNet,
                sentimentPositive,
                sentimentNegative,
                0.0,
                0.0,
                "UNKNOWN_SECTOR",
                "UNKNOWN_LEVEL2"
        );
    }

    public SignalPerformanceRecord(
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
        this.signalId = signalId;
        this.ticker = ticker;
        this.catalystType = catalystType;
        this.headline = headline;
        this.signalTimestamp = signalTimestamp;
        this.entryPrice = entryPrice;
        this.floatScore = floatScore;
        this.floatCategory = floatCategory;
        this.marketCap = marketCap;
        this.marketCapScore = marketCapScore;
        this.marketCapCategory = marketCapCategory;
        this.marketQualityScore = marketQualityScore;
        this.sentimentNet = sentimentNet;
        this.sentimentPositive = sentimentPositive;
        this.sentimentNegative = sentimentNegative;
        this.relativeVolume = relativeVolume;
        this.gapPercent = gapPercent;
        this.sectorCategory = sectorCategory;
        this.level2Category = level2Category;

        this.maxPriceAfterSignal = entryPrice;
        this.minPriceAfterSignal = entryPrice;
        this.priceAfter5Minutes = 0.0;
        this.priceAfter15Minutes = 0.0;
        this.priceAfter30Minutes = 0.0;
        this.priceAfter60Minutes = 0.0;
        this.exitPrice = 0.0;
        this.closed = false;
    }

    public void updatePrice(double price) {
        if (price <= 0) {
            return;
        }

        if (price > maxPriceAfterSignal) {
            maxPriceAfterSignal = price;
        }

        if (price < minPriceAfterSignal) {
            minPriceAfterSignal = price;
        }
    }

    public void markPriceAfterMinutes(
            int minutes,
            double price
    ) {
        if (price <= 0) {
            return;
        }

        updatePrice(price);

        if (minutes == 5) {
            priceAfter5Minutes = price;
        } else if (minutes == 15) {
            priceAfter15Minutes = price;
        } else if (minutes == 30) {
            priceAfter30Minutes = price;
        } else if (minutes == 60) {
            priceAfter60Minutes = price;
        }
    }

    public double maxGainPercent() {
        return gainFromEntry(maxPriceAfterSignal);
    }

    public double maxDrawdownPercent() {
        return gainFromEntry(minPriceAfterSignal);
    }

    public double gainAfter5Minutes() {
        return gainFromEntry(priceAfter5Minutes);
    }

    public double gainAfter15Minutes() {
        return gainFromEntry(priceAfter15Minutes);
    }

    public double gainAfter30Minutes() {
        return gainFromEntry(priceAfter30Minutes);
    }

    public double gainAfter60Minutes() {
        return gainFromEntry(priceAfter60Minutes);
    }

    public double exitGainPercent() {
        return gainFromEntry(exitPrice);
    }

    private double gainFromEntry(double price) {
        if (entryPrice <= 0 || price <= 0) {
            return 0.0;
        }

        return (price - entryPrice) / entryPrice;
    }

    @Override
    public String toString() {
        return "SignalPerformanceRecord{" +
                "signalId='" + signalId + '\'' +
                ", ticker='" + ticker + '\'' +
                ", catalystType=" + catalystType +
                ", entryPrice=" + entryPrice +
                ", floatCategory='" + floatCategory + '\'' +
                ", marketCap=" + marketCap +
                ", marketCapCategory='" + marketCapCategory + '\'' +
                ", relativeVolume=" + relativeVolume +
                ", gapPercent=" + gapPercent +
                ", sectorCategory='" + sectorCategory + '\'' +
                ", level2Category='" + level2Category + '\'' +
                ", maxGainPercent=" + maxGainPercent() +
                ", maxDrawdownPercent=" + maxDrawdownPercent() +
                ", gainAfter5Minutes=" + gainAfter5Minutes() +
                ", gainAfter15Minutes=" + gainAfter15Minutes() +
                ", gainAfter30Minutes=" + gainAfter30Minutes() +
                ", gainAfter60Minutes=" + gainAfter60Minutes() +
                ", exitGainPercent=" + exitGainPercent() +
                ", closed=" + closed +
                '}';
    }
}