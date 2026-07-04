package com.bot.model;

public class Position {

    public String ticker;
    public double entryPrice;
    public double peakPrice;
    public double troughPrice;
    public int quantity;
    public long openedAt;
    public String protectiveStopOrderId;
    public boolean shortPosition;
    public boolean syncedFromBroker;
    public int initialQuantity;
    public boolean partialProfitTaken;
    public String strategyName;
    public double entryConfidence;
    public double entryPriorityScore;
    public EntryContextSnapshot entryContext;

    public Position() {
    }

    public Position(
            String ticker,
            double entryPrice,
            double peakPrice,
            int quantity
    ) {
        this(
                ticker,
                entryPrice,
                peakPrice,
                quantity,
                System.currentTimeMillis(),
                null,
                false
        );
    }

    public Position(
            String ticker,
            double entryPrice,
            double peakPrice,
            int quantity,
            long openedAt
    ) {
        this(
                ticker,
                entryPrice,
                peakPrice,
                quantity,
                openedAt,
                null,
                false
        );
    }

    public Position(
            String ticker,
            double entryPrice,
            double peakPrice,
            int quantity,
            long openedAt,
            String protectiveStopOrderId
    ) {
        this(
                ticker,
                entryPrice,
                peakPrice,
                quantity,
                openedAt,
                protectiveStopOrderId,
                false
        );
    }

    public Position(
            String ticker,
            double entryPrice,
            double peakPrice,
            int quantity,
            long openedAt,
            String protectiveStopOrderId,
            boolean shortPosition
    ) {
        this.ticker = ticker;
        this.entryPrice = entryPrice;
        this.peakPrice = peakPrice;
        this.troughPrice = entryPrice;
        this.quantity = quantity;
        this.initialQuantity = quantity;
        this.partialProfitTaken = false;
        this.openedAt = openedAt;
        this.protectiveStopOrderId = protectiveStopOrderId;
        this.shortPosition = shortPosition;
        this.strategyName = "UNKNOWN";
        this.entryConfidence = 0.0;
        this.entryPriorityScore = 0.0;
        this.entryContext = EntryContextSnapshot.none();
    }

    public boolean isLongPosition() {
        return !shortPosition;
    }

    public boolean isShortPosition() {
        return shortPosition;
    }
}
