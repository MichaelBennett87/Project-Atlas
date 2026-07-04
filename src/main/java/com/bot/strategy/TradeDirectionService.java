package com.bot.strategy;

import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.TradeDirection;
import com.bot.sentiment.SentimentScore;

public class TradeDirectionService {

    private static final double FORCED_LONG_MIN_NET_SENTIMENT =
            -0.05;

    private static final double FORCED_LONG_MAX_NEGATIVE_SENTIMENT =
            0.45;

    public TradeDirection resolve(
            CatalystResult catalyst,
            SentimentScore sentiment
    ) {
        if (catalyst == null || catalyst.type == null) {
            return TradeDirection.NO_TRADE;
        }

        CatalystType type =
                catalyst.type;

        if (type.isNegative()) {
            return TradeDirection.NO_TRADE;
        }

        if (isUnsafeLongCatalyst(type)) {
            return TradeDirection.NO_TRADE;
        }

        if (sentiment == null) {
            return TradeDirection.NO_TRADE;
        }

        if (isBadSentimentForLong(sentiment) && !isHardPositiveRegulatoryCatalyst(type)) {
            return TradeDirection.NO_TRADE;
        }

        if (isForcedLongCatalyst(type)) {
            return TradeDirection.LONG_STOCK;
        }

        if (sentiment.positive >= 0.40 &&
                sentiment.netSentiment() >= 0.14 &&
                catalyst.weight >= 0.45) {
            return TradeDirection.LONG_STOCK;
        }

        /*
         * Controlled starter path for concise PR headlines. This does not
         * bypass the entry service, ranker, market-quality checks, risk engine,
         * or order execution. It only prevents the trade direction layer from
         * turning every moderate-positive actionable catalyst into NO_TRADE
         * before the rest of the architecture can evaluate it.
         */
        if (isActionableStarterCatalyst(type) &&
                catalyst.weight >= 0.60 &&
                sentiment.positive >= 0.25 &&
                sentiment.negative <= 0.30 &&
                sentiment.netSentiment() >= 0.08) {
            return TradeDirection.LONG_STOCK;
        }

        return TradeDirection.NO_TRADE;
    }

    private boolean isActionableStarterCatalyst(CatalystType type) {
        return type == CatalystType.PARTNERSHIP ||
                type == CatalystType.PRODUCT_LAUNCH ||
                type == CatalystType.NEW_PRODUCT_SERVICE ||
                type == CatalystType.POSITIVE_BUSINESS_MOMENTUM ||
                type == CatalystType.BUSINESS_UPDATE ||
                type == CatalystType.CAPITAL_INVESTMENT ||
                type == CatalystType.FACILITY_EXPANSION ||
                type == CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP ||
                type == CatalystType.FACILITY_EXPANSION ||
                type == CatalystType.STRATEGIC_REVIEW ||
                type == CatalystType.INSIDER_BUYING;
    }

    private boolean isBadSentimentForLong(SentimentScore sentiment) {
        return sentiment.netSentiment() < FORCED_LONG_MIN_NET_SENTIMENT ||
                sentiment.negative >= FORCED_LONG_MAX_NEGATIVE_SENTIMENT;
    }

    private boolean isHardPositiveRegulatoryCatalyst(CatalystType type) {
        return type == CatalystType.FDA_APPROVAL ||
                type == CatalystType.FDA_CLEARANCE ||
                type == CatalystType.NASDAQ_COMPLIANCE ||
                type == CatalystType.NASDAQ_COMPLIANCE_EXTENSION ||
                type == CatalystType.NYSE_COMPLIANCE ||
                type == CatalystType.EXCHANGE_COMPLIANCE;
    }

    private boolean isUnsafeLongCatalyst(CatalystType type) {
        return type == CatalystType.OFFERING_DILUTION ||
                type == CatalystType.SHELF_OFFERING ||
                type == CatalystType.PRIVATE_PLACEMENT ||
                type == CatalystType.CONVERTIBLE_PREFERRED_OFFERING ||
                type == CatalystType.MINI_TENDER_OFFER ||
                type == CatalystType.SEC_FILING ||
                type == CatalystType.SEC_MARKET_STRUCTURE ||
                type == CatalystType.NAME_TICKER_CHANGE ||
                type == CatalystType.LISTING_TRANSFER ||
                type == CatalystType.EXECUTIVE_HIRE ||
                type == CatalystType.GOVERNANCE_CHANGE ||
                type == CatalystType.PROPERTY_ACQUISITION ||
                type == CatalystType.STRATEGIC_INVESTMENT ||
                type == CatalystType.STOCK_PICK_ARTICLE ||
                type == CatalystType.INDUSTRY_COMPARISON ||
                type == CatalystType.INDUSTRY_READTHROUGH ||
                type == CatalystType.HISTORICAL_PERFORMANCE ||
                type == CatalystType.MARKET_COMMENTARY ||
                type == CatalystType.INVESTOR_COMMENTARY ||
                type == CatalystType.REGULATORY_POLITICAL_COMMENTARY ||
                type == CatalystType.BROAD_MARKET ||
                type == CatalystType.NO_MATERIAL_NEWS ||
                type == CatalystType.UNKNOWN;
    }

    private boolean isForcedLongCatalyst(
            CatalystType type
    ) {
        return type == CatalystType.FDA_APPROVAL ||
                type == CatalystType.FDA_CLEARANCE ||
                type == CatalystType.FDA_REGISTRATION ||
                type == CatalystType.CLINICAL_TRIAL_SUCCESS ||
                type == CatalystType.DRUG_DATA_POSITIVE ||
                type == CatalystType.GUIDANCE_RAISE ||
                type == CatalystType.MAJOR_CONTRACT ||
                type == CatalystType.MAJOR_ORDER ||
                type == CatalystType.MATERIAL_SUPPLY_AGREEMENT ||
                type == CatalystType.SALES_AGREEMENT ||
                type == CatalystType.BUYOUT_OFFER ||
                type == CatalystType.MERGER_ACQUISITION ||
                type == CatalystType.NASDAQ_COMPLIANCE ||
                type == CatalystType.NASDAQ_COMPLIANCE_EXTENSION ||
                type == CatalystType.NYSE_COMPLIANCE ||
                type == CatalystType.EXCHANGE_COMPLIANCE ||
                type == CatalystType.INDEX_ADDITION ||
                type == CatalystType.SHARE_BUYBACK ||
                type == CatalystType.STRATEGIC_REVIEW;
    }
}
