package com.bot.risk;

import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.sentiment.SentimentScore;

public class PositionSizer {

    private static final double BASE_ALLOCATION = envDouble("POSITION_BASE_ALLOCATION", 0.003);
    private static final double MIN_ALLOCATION = envDouble("POSITION_MIN_ALLOCATION", 0.001);
    private static final double A_PLUS_MIN_ALLOCATION = envDouble("POSITION_A_PLUS_MIN_ALLOCATION", 0.015);
    private static final double A_PLUS_STRONG_ALLOCATION = envDouble("POSITION_A_PLUS_STRONG_ALLOCATION", 0.025);
    private static final double A_PLUS_MAX_ALLOCATION = envDouble("POSITION_A_PLUS_MAX_ALLOCATION", 0.04);
    private static final double MAX_ALLOCATION = envDouble("POSITION_MAX_ALLOCATION", 0.03);

    public PositionSizeDecision calculate(
            double accountValue,
            double stockPrice,
            CatalystResult catalyst,
            SentimentScore sentiment
    ) {
        if (isNonActionableCatalyst(catalyst)) {
            return new PositionSizeDecision(
                    0,
                    BASE_ALLOCATION,
                    0.0,
                    0.0,
                    0.0
            );
        }

        if (accountValue <= 0 || stockPrice <= 0) {
            return new PositionSizeDecision(
                    0,
                    BASE_ALLOCATION,
                    1.0,
                    1.0,
                    0.0
            );
        }

        if (isAPlusCatalyst(catalyst)) {
            double finalAllocation =
                    aPlusAllocation(
                            catalyst,
                            sentiment
                    );

            return decisionFromAllocation(
                    accountValue,
                    stockPrice,
                    finalAllocation,
                    finalAllocation / BASE_ALLOCATION,
                    1.0
            );
        }

        double catalystMultiplier =
                catalystMultiplier(
                        catalyst
                );

        double sentimentMultiplier =
                sentimentMultiplier(
                        catalyst,
                        sentiment
                );

        double finalAllocation =
                BASE_ALLOCATION *
                        catalystMultiplier *
                        sentimentMultiplier;

        finalAllocation =
                clamp(
                        finalAllocation,
                        MIN_ALLOCATION,
                        MAX_ALLOCATION
                );

        double dollars =
                accountValue * finalAllocation;

        int shares =
                (int) Math.floor(
                        dollars / stockPrice
                );

        shares =
                Math.max(
                        shares,
                        1
                );

        return new PositionSizeDecision(
                shares,
                BASE_ALLOCATION,
                catalystMultiplier,
                sentimentMultiplier,
                finalAllocation
        );
    }

    public int calculateShares(
            double accountValue,
            double stockPrice,
            CatalystResult catalyst,
            SentimentScore sentiment
    ) {
        return calculate(
                accountValue,
                stockPrice,
                catalyst,
                sentiment
        ).shares;
    }


    private boolean isNonActionableCatalyst(
            CatalystResult catalyst
    ) {
        if (catalyst == null || catalyst.type == null) {
            return false;
        }

        CatalystType type =
                catalyst.type;

        return type == CatalystType.STOCK_PICK_ARTICLE ||
                type == CatalystType.INDUSTRY_COMPARISON ||
                type == CatalystType.NEWS_ROUNDUP ||
                type == CatalystType.REPORTED_EARLIER ||
                type == CatalystType.HISTORICAL_PERFORMANCE ||
                type == CatalystType.WHY_MOVING ||
                type == CatalystType.OPTIONS_FLOW ||
                type == CatalystType.FUND_ETF_COMMENTARY ||
                type == CatalystType.MARKET_COMMENTARY ||
                type == CatalystType.INVESTOR_COMMENTARY ||
                type == CatalystType.POLITICAL_NEWS ||
                type == CatalystType.ENTERTAINMENT_POLITICAL_NEWS ||
                type == CatalystType.PRIVATE_COMPANY_VALUATION ||
                type == CatalystType.VALUATION_ARTICLE ||
                type == CatalystType.INDUSTRY_READTHROUGH ||
                type == CatalystType.IPO_COMMENTARY ||
                type == CatalystType.BROAD_MARKET ||
                type == CatalystType.CRYPTO_NEWS ||
                type == CatalystType.GEOPOLITICAL;
    }

    private PositionSizeDecision decisionFromAllocation(
            double accountValue,
            double stockPrice,
            double finalAllocation,
            double catalystMultiplier,
            double sentimentMultiplier
    ) {
        double dollars =
                accountValue * finalAllocation;

        int shares =
                (int) Math.floor(
                        dollars / stockPrice
                );

        shares =
                Math.max(
                        shares,
                        1
                );

        return new PositionSizeDecision(
                shares,
                BASE_ALLOCATION,
                catalystMultiplier,
                sentimentMultiplier,
                finalAllocation
        );
    }

    private double aPlusAllocation(
            CatalystResult catalyst,
            SentimentScore sentiment
    ) {
        if (isPerfectAPlusSetup(catalyst, sentiment)) {
            return A_PLUS_MAX_ALLOCATION;
        }

        if (isStrongAPlusSetup(catalyst, sentiment)) {
            return A_PLUS_STRONG_ALLOCATION;
        }

        return A_PLUS_MIN_ALLOCATION;
    }

    private boolean isPerfectAPlusSetup(
            CatalystResult catalyst,
            SentimentScore sentiment
    ) {
        if (catalyst == null || catalyst.type == null || sentiment == null) {
            return false;
        }

        return catalyst.weight >= 0.95 &&
                sentiment.positive >= 0.90 &&
                sentiment.negative <= 0.10 &&
                sentiment.netSentiment() >= 0.80;
    }

    private boolean isStrongAPlusSetup(
            CatalystResult catalyst,
            SentimentScore sentiment
    ) {
        if (catalyst == null || catalyst.type == null || sentiment == null) {
            return false;
        }

        return catalyst.weight >= 0.90 &&
                sentiment.positive >= 0.75 &&
                sentiment.negative <= 0.20 &&
                sentiment.netSentiment() >= 0.55;
    }

    private boolean isAPlusCatalyst(
            CatalystResult catalyst
    ) {
        if (catalyst == null || catalyst.type == null) {
            return false;
        }

        CatalystType type =
                catalyst.type;

        return type == CatalystType.NASDAQ_COMPLIANCE ||
                type == CatalystType.NASDAQ_COMPLIANCE_EXTENSION ||
                type == CatalystType.NYSE_COMPLIANCE ||
                type == CatalystType.EXCHANGE_COMPLIANCE ||
                type == CatalystType.FDA_APPROVAL ||
                type == CatalystType.FDA_CLEARANCE ||
                type == CatalystType.FDA_REGISTRATION ||
                type == CatalystType.CLINICAL_TRIAL_SUCCESS ||
                type == CatalystType.DRUG_DATA_POSITIVE ||
                type == CatalystType.GUIDANCE_RAISE ||
                type == CatalystType.MAJOR_CONTRACT ||
                type == CatalystType.MAJOR_ORDER ||
                type == CatalystType.CUSTOMER_ORDER ||
                type == CatalystType.MATERIAL_SUPPLY_AGREEMENT ||
                type == CatalystType.SALES_AGREEMENT ||
                type == CatalystType.BUYOUT_OFFER ||
                type == CatalystType.MERGER_ACQUISITION ||
                type == CatalystType.INDEX_ADDITION ||
                type == CatalystType.SHARE_BUYBACK;
    }

    private double catalystMultiplier(
            CatalystResult catalyst
    ) {
        if (catalyst == null || catalyst.type == null) {
            return 0.75;
        }

        CatalystType type =
                catalyst.type;

        if (type == CatalystType.BANKRUPTCY) {
            return 1.75;
        }

        if (type == CatalystType.FDA_APPROVAL ||
                type == CatalystType.FDA_REJECTION ||
                type == CatalystType.CLINICAL_TRIAL_FAILURE) {
            return 1.50;
        }

        if (type == CatalystType.CLINICAL_TRIAL_SUCCESS ||
                type == CatalystType.DRUG_DATA_POSITIVE ||
                type == CatalystType.SHORT_SELLER_REPORT) {
            return 1.40;
        }

        if (type == CatalystType.FDA_CLEARANCE ||
                type == CatalystType.OFFERING_DILUTION ||
                type == CatalystType.PRIVATE_PLACEMENT) {
            return 1.30;
        }

        if (type == CatalystType.MAJOR_CONTRACT ||
                type == CatalystType.MAJOR_ORDER ||
                type == CatalystType.GUIDANCE_CUT) {
            return 1.20;
        }

        if (type == CatalystType.GUIDANCE_RAISE ||
                type == CatalystType.EARNINGS_BEAT ||
                type == CatalystType.EARNINGS_MISS ||
                type == CatalystType.SHARE_BUYBACK ||
                type == CatalystType.NASDAQ_COMPLIANCE ||
                type == CatalystType.NASDAQ_COMPLIANCE_EXTENSION ||
                type == CatalystType.NYSE_COMPLIANCE ||
                type == CatalystType.EXCHANGE_COMPLIANCE ||
                type == CatalystType.INDEX_ADDITION) {
            return 1.10;
        }

        if (Math.abs(catalyst.weight) >= 0.90) {
            return 1.25;
        }

        if (Math.abs(catalyst.weight) >= 0.70) {
            return 1.10;
        }

        if (Math.abs(catalyst.weight) >= 0.40) {
            return 0.90;
        }

        return 0.75;
    }

    private double sentimentMultiplier(
            CatalystResult catalyst,
            SentimentScore sentiment
    ) {
        if (sentiment == null) {
            return 0.75;
        }

        double strength =
                sentimentStrength(
                        catalyst,
                        sentiment
                );

        if (strength >= 0.80) {
            return 1.50;
        }

        if (strength >= 0.60) {
            return 1.25;
        }

        if (strength >= 0.40) {
            return 1.00;
        }

        if (strength >= 0.20) {
            return 0.75;
        }

        if (strength >= 0.10) {
            return 0.50;
        }

        return 0.35;
    }

    private double sentimentStrength(
            CatalystResult catalyst,
            SentimentScore sentiment
    ) {
        if (catalyst != null &&
                catalyst.type != null &&
                catalyst.type.isNegative()) {
            return Math.max(
                    sentiment.negative,
                    Math.abs(
                            sentiment.netSentiment()
                    )
            );
        }

        return Math.max(
                sentiment.positive,
                sentiment.netSentiment()
        );
    }

    private double clamp(
            double value,
            double min,
            double max
    ) {
        return Math.max(
                min,
                Math.min(
                        max,
                        value
                )
        );
    }

    private static double envDouble(String key, double defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            double parsed = Double.parseDouble(value.trim());
            return parsed >= 0.0 ? parsed : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public static class PositionSizeDecision {

        public final int shares;
        public final double baseAllocation;
        public final double catalystMultiplier;
        public final double sentimentMultiplier;
        public final double finalAllocation;

        public PositionSizeDecision(
                int shares,
                double baseAllocation,
                double catalystMultiplier,
                double sentimentMultiplier,
                double finalAllocation
        ) {
            this.shares = shares;
            this.baseAllocation = baseAllocation;
            this.catalystMultiplier = catalystMultiplier;
            this.sentimentMultiplier = sentimentMultiplier;
            this.finalAllocation = finalAllocation;
        }
    }
}