package com.bot.risk;

import com.bot.intelligence.AdaptiveStrategyPerformanceTracker;
import com.bot.model.AccountService;
import com.bot.model.CatalystResult;
import com.bot.sentiment.SentimentScore;

public class AdvancedRiskEngine {

    private final AccountService account;
    private final PositionService positions;
    private final SymbolFilter symbolFilter;
    private final PositionSizer positionSizer;
    private final MarketHoursService marketHoursService;
    private final AdaptiveStrategyPerformanceTracker strategyPerformanceTracker;

    private final double maxDailyLoss = 0.03;
    private final int maxOpenPositions;

    /*
     * Optional safety cap for live paper testing.
     *
     * Leave MAX_TESTING_QUANTITY unset to allow the percentage-based sizing
     * policy to work as designed. Set MAX_TESTING_QUANTITY to a positive
     * number if you want to manually cap shares during testing.
     */
    private final int maxTestingQuantity;
    private final double maxDollarsPerTrade;
    private final double maxExtendedHoursDollarsPerTrade;
    private final double minimumBuyingPowerToTrade;
    private final double buyingPowerReserveDollars;


    public AdvancedRiskEngine(
            AccountService account,
            PositionService positions
    ) {
        this.account = account;
        this.positions = positions;
        this.symbolFilter = new SymbolFilter();
        this.positionSizer = new PositionSizer();
        this.marketHoursService = new MarketHoursService();
        this.strategyPerformanceTracker = new AdaptiveStrategyPerformanceTracker();
        this.maxTestingQuantity = resolveMaxTestingQuantity();
        this.maxDollarsPerTrade = resolveEnvDouble("MAX_DOLLARS_PER_TRADE", 1_500.0);
        this.maxExtendedHoursDollarsPerTrade = resolveEnvDouble("MAX_EXTENDED_HOURS_DOLLARS_PER_TRADE", 500.0);
        this.minimumBuyingPowerToTrade = resolveEnvDouble("MIN_BUYING_POWER_TO_TRADE", 500.0);
        this.buyingPowerReserveDollars = resolveEnvDouble("BUYING_POWER_RESERVE_DOLLARS", 100.0);
        this.maxOpenPositions = resolveMaxOpenPositions();
    }

    public boolean allowNewTrade(
            String ticker
    ) {
        if (!symbolFilter.allowed(ticker)) {
            System.out.println(
                    "RISK BLOCKED: symbol not allowed " +
                            ticker
            );
            return false;
        }

        double buyingPower = safeBuyingPower();
        if (buyingPower < minimumBuyingPowerToTrade) {
            System.out.println(
                    "RISK BLOCKED: insufficient buying power buyingPower=" +
                            buyingPower +
                            " min=" +
                            minimumBuyingPowerToTrade
            );
            return false;
        }

        if (positions.hasPosition(ticker)) {
            System.out.println(
                    "RISK BLOCKED: already holding " +
                            ticker
            );
            return false;
        }

        if (positions.openCount() >= maxOpenPositions) {
            System.out.println(
                    "RISK BLOCKED: max open positions reached"
            );
            return false;
        }

        if (account.dailyDrawdown() <= -maxDailyLoss) {
            System.out.println(
                    "RISK BLOCKED: max daily loss reached"
            );
            return false;
        }

        return true;
    }

    public int calculateQuantity(
            String ticker,
            CatalystResult catalyst,
            SentimentScore sentiment
    ) {
        return calculateQuantity(
                ticker,
                catalyst,
                sentiment,
                "LEGACY_RISK_ENGINE"
        );
    }

    public int calculateQuantity(
            String ticker,
            CatalystResult catalyst,
            SentimentScore sentiment,
            String strategyName
    ) {
        double equity =
                account.equity();

        double price =
                account.lastPrice(
                        ticker
                );

        if (price <= 0) {
            System.out.println(
                    "POSITION SIZE BLOCKED: invalid price for " +
                            ticker
            );
            return 0;
        }

        PositionSizer.PositionSizeDecision sizeDecision =
                positionSizer.calculate(
                        equity,
                        price,
                        catalyst,
                        sentiment
                );

        int calculatedQty =
                sizeDecision.shares;

        double strategySizingMultiplier =
                strategyPerformanceTracker.sizingMultiplier(
                        strategyName
                );

        int strategyAdjustedQty =
                calculatedQty <= 0
                        ? 0
                        : Math.max(
                                1,
                                (int) Math.floor(calculatedQty * strategySizingMultiplier)
                        );

        int cappedQty =
                applyQuantityCaps(
                        strategyAdjustedQty,
                        price
                );

        System.out.println(
                "POSITION SIZE: ticker=" +
                        ticker +
                        " equity=" +
                        equity +
                        " price=" +
                        price +
                        " catalyst=" +
                        catalyst +
                        " sentiment=" +
                        sentiment +
                        " baseAllocation=" +
                        sizeDecision.baseAllocation +
                        " catalystMultiplier=" +
                        sizeDecision.catalystMultiplier +
                        " sentimentMultiplier=" +
                        sizeDecision.sentimentMultiplier +
                        " finalAllocation=" +
                        sizeDecision.finalAllocation +
                        " strategy=" +
                        strategyName +
                        " strategySizingMultiplier=" +
                        strategySizingMultiplier +
                        " calculatedQty=" +
                        calculatedQty +
                        " strategyAdjustedQty=" +
                        strategyAdjustedQty +
                        " cappedQty=" +
                        cappedQty
        );

        return cappedQty;
    }

    public int calculateQuantity(
            String ticker,
            SentimentScore sentiment
    ) {
        return calculateQuantity(
                ticker,
                sentiment,
                "LEGACY_NEWS_MOMENTUM"
        );
    }

    public int calculateQuantity(
            String ticker,
            SentimentScore sentiment,
            String strategyName
    ) {
        double equity =
                account.equity();

        double price =
                account.lastPrice(
                        ticker
                );

        if (price <= 0) {
            return 0;
        }

        int calculatedQty =
                Math.max(
                        1,
                        (int) Math.floor(
                                (equity * 0.005) / price
                        )
                );

        double strategySizingMultiplier =
                strategyPerformanceTracker.sizingMultiplier(
                        strategyName
                );

        int strategyAdjustedQty =
                Math.max(
                        1,
                        (int) Math.floor(calculatedQty * strategySizingMultiplier)
                );

        return applyQuantityCaps(
                strategyAdjustedQty,
                price
        );
    }

    public double lastPrice(
            String ticker
    ) {
        return account.lastPrice(
                ticker
        );
    }

    private int applyQuantityCaps(
            int calculatedQty,
            double price
    ) {
        if (calculatedQty <= 0 || price <= 0.0) {
            return 0;
        }

        int cappedQty = calculatedQty;

        if (maxTestingQuantity > 0) {
            cappedQty = Math.min(
                    cappedQty,
                    maxTestingQuantity
            );
        }

        double dollarCap = marketHoursService.isRegularMarketOpenNow()
                ? maxDollarsPerTrade
                : maxExtendedHoursDollarsPerTrade;

        if (dollarCap > 0.0) {
            int dollarCappedQty = Math.max(
                    1,
                    (int) Math.floor(dollarCap / price)
            );

            cappedQty = Math.min(
                    cappedQty,
                    dollarCappedQty
            );
        }

        double availableBuyingPower = Math.max(0.0, safeBuyingPower() - buyingPowerReserveDollars);
        if (availableBuyingPower <= 0.0) {
            System.out.println(
                    "POSITION SIZE BLOCKED: no available buying power after reserve buyingPower=" +
                            safeBuyingPower() +
                            " reserve=" +
                            buyingPowerReserveDollars
            );
            return 0;
        }

        int buyingPowerCappedQty = (int) Math.floor(availableBuyingPower / price);
        cappedQty = Math.min(cappedQty, buyingPowerCappedQty);

        return Math.max(0, cappedQty);
    }


    public int getMaxOpenPositions() {
        return maxOpenPositions;
    }

    public double currentBuyingPower() {
        return safeBuyingPower();
    }

    public double deployableBuyingPower() {
        return Math.max(0.0, safeBuyingPower() - buyingPowerReserveDollars);
    }

    public double getBuyingPowerReserveDollars() {
        return buyingPowerReserveDollars;
    }

    private double safeBuyingPower() {
        try {
            return account.buyingPower();
        } catch (Exception e) {
            System.out.println(
                    "RISK BLOCKED: buying power lookup failed " +
                            e.getMessage()
            );
            return 0.0;
        }
    }

    private double resolveEnvDouble(String key, double defaultValue) {
        String value = System.getenv().getOrDefault(
                key,
                String.valueOf(defaultValue)
        );

        try {
            double parsed = Double.parseDouble(value.trim());
            return parsed >= 0.0 ? parsed : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private int resolveMaxTestingQuantity() {
        String value =
                System.getenv().getOrDefault(
                        "MAX_TESTING_QUANTITY",
                        "0"
                );

        try {
            int parsed = Integer.parseInt(value.trim());

            return parsed > 0 ? parsed : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private int resolveMaxOpenPositions() {
        String value =
                System.getenv().getOrDefault(
                        "MAX_OPEN_POSITIONS",
                        "5"
                );

        try {
            int parsed = Integer.parseInt(value.trim());

            if (parsed < 1) {
                return 1;
            }

            if (parsed > 10) {
                return 10;
            }

            return parsed;
        } catch (Exception e) {
            return 5;
        }
    }

}
