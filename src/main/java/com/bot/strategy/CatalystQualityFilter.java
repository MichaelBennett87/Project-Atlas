package com.bot.strategy;

import com.bot.model.CatalystQualityDecision;
import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.sentiment.SentimentScore;

public class CatalystQualityFilter {

    public CatalystQualityDecision evaluate(
            CatalystResult catalyst,
            SentimentScore sentiment
    ) {
        if (catalyst == null || catalyst.type == null) {
            return new CatalystQualityDecision(
                    false,
                    "Missing catalyst"
            );
        }

        if (catalyst.type == CatalystType.UNKNOWN) {
            return new CatalystQualityDecision(
                    false,
                    "UNKNOWN catalyst rejected"
            );
        }

        if (isNonActionableInformationCatalyst(catalyst.type)) {
            return new CatalystQualityDecision(
                    false,
                    catalyst.type + " catalyst rejected as non-actionable information"
            );
        }

        if (isSentimentBypassCatalyst(catalyst.type)) {
            return new CatalystQualityDecision(
                    true,
                    "High-priority catalyst passed without sentiment requirement"
            );
        }

        if (sentiment == null) {
            return new CatalystQualityDecision(
                    false,
                    "Missing sentiment"
            );
        }

        if (!catalyst.type.isNegative()) {
            /*
             * FinBERT is intentionally conservative on terse PR headlines. This
             * filter should remove clearly weak/noisy positives, not prevent
             * the ranker and entry engine from ever seeing usable catalysts.
             */
            double minPositive = isActionableMomentumCatalyst(catalyst.type) ? 0.30 : 0.42;
            double minNet = isActionableMomentumCatalyst(catalyst.type) ? 0.08 : 0.15;

            if (sentiment.positive < minPositive || sentiment.netSentiment() < minNet) {
                return new CatalystQualityDecision(
                        false,
                        "Positive catalyst rejected due to weak sentiment"
                );
            }
        }

        switch (catalyst.type) {
            case FDA_APPROVAL:
            case FDA_CLEARANCE:
            case FDA_REGISTRATION:
                return new CatalystQualityDecision(
                        true,
                        "FDA catalyst passed"
                );

            case CLINICAL_TRIAL_SUCCESS:
            case CLINICAL_TRIAL_INITIATION:
            case CLINICAL_DATA_PRESENTATION:
            case DRUG_DATA_POSITIVE:
            case ORPHAN_DRUG_DESIGNATION:
                return new CatalystQualityDecision(
                        true,
                        "Clinical / biotech catalyst passed"
                );

            case GUIDANCE_RAISE:
            case EARNINGS_BEAT:
            case EARNINGS_GROWTH:
                return new CatalystQualityDecision(
                        true,
                        "Earnings/guidance catalyst passed"
                );

            case GOVERNMENT_PAYMENT_RATE:
                return new CatalystQualityDecision(
                        true,
                        "Government payment-rate catalyst passed"
                );

            case DIVIDEND_INCREASE:
            case DIVIDEND:
                return new CatalystQualityDecision(
                        true,
                        "Dividend catalyst passed"
                );

            case MAJOR_CONTRACT:
            case MAJOR_ORDER:
            case CUSTOMER_ORDER:
            case CONTRACT_RENEWAL:
            case PRODUCT_SALE:
            case CAPITAL_INVESTMENT:
            case FACILITY_EXPANSION:
            case HEADQUARTERS_EXPANSION:
            case NEW_PRODUCT_SERVICE:
            case POSITIVE_BUSINESS_MOMENTUM:
            case BUSINESS_UPDATE:
            case SALES_AGREEMENT:
            case MATERIAL_SUPPLY_AGREEMENT:
                return new CatalystQualityDecision(
                        true,
                        "Contract/order catalyst passed"
                );

            case ANALYST_UPGRADE:
            case STRONG_ANALYST_ACTION:
            case PRICE_TARGET_RAISE:
                return new CatalystQualityDecision(
                        true,
                        "Analyst catalyst passed"
                );

            case PRODUCT_LAUNCH:
            case PARTNERSHIP:
            case AI_INFRASTRUCTURE_PARTNERSHIP:
            case PATENT_NOTICE:
                return new CatalystQualityDecision(
                        true,
                        "Product, partnership, or patent catalyst passed"
                );

            case BUYOUT_OFFER:
            case MERGER_ACQUISITION:
            case STRATEGIC_REVIEW:
            case ACTIVIST_INVESTOR:
            case SHARE_BUYBACK:
            case ASSET_SALE:
            case SPINOFF:
                return new CatalystQualityDecision(
                        true,
                        "Corporate action catalyst passed"
                );

            case NASDAQ_COMPLIANCE:
            case NASDAQ_COMPLIANCE_EXTENSION:
            case NYSE_COMPLIANCE:
            case EXCHANGE_COMPLIANCE:
            case INDEX_ADDITION:
            case IPO_DEBUT:
                return new CatalystQualityDecision(
                        true,
                        "Listing/index catalyst passed"
                );

            case FDA_REJECTION:
            case CLINICAL_TRIAL_FAILURE:
            case CLINICAL_HOLD:
            case GUIDANCE_CUT:
            case EARNINGS_MISS:
            case OFFERING_DILUTION:
            case PRIVATE_PLACEMENT:
            case DELISTING_RISK:
            case NASDAQ_NONCOMPLIANCE:
            case BANKRUPTCY:
            case INVESTIGATION:
            case SECURITIES_LITIGATION:
            case LAWSUIT:
            case RECALL:
            case SHORT_SELLER_REPORT:
            case CYBERSECURITY_INCIDENT:
            case FDA_DEVICE_ALERT:
            case LAYOFFS:
                return new CatalystQualityDecision(
                        sentiment.negative >= 0.55,
                        sentiment.negative >= 0.55
                                ? "Negative catalyst passed"
                                : "Negative catalyst rejected due to weak negative sentiment"
                );

            default:
                return new CatalystQualityDecision(
                        false,
                        catalyst.type + " catalyst rejected"
                );
        }
    }

    private boolean isNonActionableInformationCatalyst(CatalystType type) {
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
                type == CatalystType.REGULATORY_POLITICAL_COMMENTARY ||
                type == CatalystType.ENTERTAINMENT_POLITICAL_NEWS ||
                type == CatalystType.PRIVATE_COMPANY_VALUATION ||
                type == CatalystType.VALUATION_ARTICLE ||
                type == CatalystType.INDUSTRY_READTHROUGH ||
                type == CatalystType.IPO_COMMENTARY ||
                type == CatalystType.BROAD_MARKET ||
                type == CatalystType.CRYPTO_NEWS ||
                type == CatalystType.GEOPOLITICAL;
    }

    private boolean isActionableMomentumCatalyst(CatalystType type) {
        return type == CatalystType.EARNINGS_GROWTH ||
                type == CatalystType.DIVIDEND_INCREASE ||
                type == CatalystType.FACILITY_EXPANSION ||
                type == CatalystType.HEADQUARTERS_EXPANSION ||
                type == CatalystType.NEW_PRODUCT_SERVICE ||
                type == CatalystType.PRODUCT_SALE ||
                type == CatalystType.CAPITAL_INVESTMENT ||
                type == CatalystType.POSITIVE_BUSINESS_MOMENTUM ||
                type == CatalystType.BUSINESS_UPDATE ||
                type == CatalystType.STRATEGIC_REVIEW ||
                type == CatalystType.PRODUCT_LAUNCH ||
                type == CatalystType.PARTNERSHIP ||
                type == CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP ||
                type == CatalystType.ANALYST_UPGRADE ||
                type == CatalystType.PRICE_TARGET_RAISE;
    }

    public boolean isSentimentBypassCatalyst(CatalystType type) {
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
                type == CatalystType.SHARE_BUYBACK;
    }
}
