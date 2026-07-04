package com.bot.strategy;

import com.bot.broker.AlpacaBroker;
import com.bot.model.CatalystType;
import com.bot.model.FloatProfile;
import com.bot.model.MarketCapProfile;
import com.bot.model.MarketQuality;
import com.bot.model.NewsOpportunity;
import com.bot.model.RankedOpportunity;
import com.bot.risk.SymbolFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OpportunityRanker {

    private final AlpacaBroker broker;
    private final SymbolFilter symbolFilter;
    private final FloatAwarenessService floatAwarenessService;
    private final MarketCapAwarenessService marketCapAwarenessService;

    public OpportunityRanker(AlpacaBroker broker) {
        this.broker = broker;
        this.symbolFilter = new SymbolFilter();
        this.floatAwarenessService = new FloatAwarenessService();
        this.marketCapAwarenessService = new MarketCapAwarenessService();
    }

    public List<RankedOpportunity> rank(List<NewsOpportunity> opportunities) {
        List<RankedOpportunity> ranked = new ArrayList<>();

        System.out.println("Ranking opportunities: " + opportunities.size());

        for (NewsOpportunity opportunity : opportunities) {
            RankedOpportunity rankedOpportunity = rankOne(opportunity);

            System.out.println(
                    "RANK CHECK: " +
                            opportunity.news.getTicker() +
                            " opportunityScore=" +
                            opportunity.finalScore +
                            " rankScore=" +
                            rankedOpportunity.rankScore +
                            " reason=" +
                            rankedOpportunity.reason +
                            " marketQuality=" +
                            rankedOpportunity.marketQuality
            );

            if (rankedOpportunity.rankScore > 0) {
                ranked.add(rankedOpportunity);
            }
        }

        ranked.sort(
                Comparator.comparingDouble((RankedOpportunity r) -> r.rankScore)
                        .reversed()
        );

        return ranked;
    }

    private RankedOpportunity rankOne(NewsOpportunity opportunity) {
        String ticker = opportunity.news.getTicker();

        if (!symbolFilter.allowed(ticker)) {
            return new RankedOpportunity(
                    opportunity,
                    null,
                    0.0,
                    "Rejected: blocked symbol"
            );
        }

        MarketQuality marketQuality =
                broker.getMarketQuality(ticker);

        FloatProfile floatProfile =
                floatAwarenessService.profile(ticker);

        MarketCapProfile marketCapProfile =
                marketCapAwarenessService.profile(ticker);

        double volatilityScore =
                volatilityScore(
                        opportunity,
                        floatProfile,
                        marketCapProfile
                );

        if (marketQuality == null) {
            if (isMustNotHardRejectCatalyst(opportunity)) {
                return new RankedOpportunity(
                        opportunity,
                        null,
                        Math.max(0.50, (opportunity.finalScore * 0.50) + (volatilityScore * 0.50)),
                        "Ranked with fallback: market quality unavailable but catalyst/volatility profile is high-priority"
                );
            }

            return new RankedOpportunity(
                    opportunity,
                    null,
                    0.0,
                    "Rejected: market quality unavailable"
            );
        }

        if (!marketQuality.tradable) {
            if (isUnsupportedMarketQuality(marketQuality)) {
                return new RankedOpportunity(
                        opportunity,
                        marketQuality,
                        0.0,
                        "Rejected: unsupported/non-Alpaca symbol or missing snapshot"
                );
            }

            if (isMustNotHardRejectCatalyst(opportunity)) {
                return new RankedOpportunity(
                        opportunity,
                        marketQuality,
                        Math.max(0.10, (opportunity.finalScore * 0.20) + (volatilityScore * 0.35)),
                        "Ranked for pending confirmation only: not tradable or spread too wide despite high-priority catalyst/volatility profile"
                );
            }

            if (isControlledLoosenCatalyst(opportunity)) {
                return new RankedOpportunity(
                        opportunity,
                        marketQuality,
                        Math.max(0.25, (opportunity.finalScore * 0.35) + (volatilityScore * 0.40)),
                        "Ranked for controlled momentum: not tradable/spread concern, but catalyst and volatility are actionable"
                );
            }

            return new RankedOpportunity(
                    opportunity,
                    marketQuality,
                    0.0,
                    "Rejected: not tradable or spread too wide"
            );
        }

        double score =
                (opportunity.finalScore * 0.52)
                        + (marketQuality.qualityScore * 0.18)
                        + (volatilityScore * 0.30);

        return new RankedOpportunity(
                opportunity,
                marketQuality,
                Math.min(1.25, score),
                "Ranked using catalyst/sentiment score + market quality + volatility profile"
        );
    }


    private double volatilityScore(
            NewsOpportunity opportunity,
            FloatProfile floatProfile,
            MarketCapProfile marketCapProfile
    ) {
        double floatScore =
                floatProfile == null
                        ? 0.45
                        : floatProfile.floatScore;

        double marketCapScore =
                marketCapProfile == null
                        ? 0.62
                        : marketCapProfile.marketCapScore;

        double catalystScore =
                opportunity == null || opportunity.catalyst == null
                        ? 0.0
                        : Math.max(0.0, opportunity.catalyst.weight);

        double sentimentScore =
                opportunity == null || opportunity.sentiment == null
                        ? 0.0
                        : Math.max(0.0, opportunity.sentiment.netSentiment());

        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        (floatScore * 0.30)
                                + (marketCapScore * 0.35)
                                + (catalystScore * 0.25)
                                + (sentimentScore * 0.10)
                )
        );
    }

    private boolean isUnsupportedMarketQuality(MarketQuality marketQuality) {
        if (marketQuality == null || marketQuality.reason == null) {
            return false;
        }

        String reason = marketQuality.reason.toLowerCase();
        return reason.contains("no snapshot found") ||
                reason.contains("http 404") ||
                reason.contains("market_quality_error: http 404");
    }

    private boolean isMustNotHardRejectCatalyst(NewsOpportunity opportunity) {
        if (opportunity == null ||
                opportunity.catalyst == null ||
                opportunity.catalyst.type == null) {
            return false;
        }

        CatalystType type = opportunity.catalyst.type;

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
                type == CatalystType.PARTNERSHIP ||
                type == CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP ||
                type == CatalystType.ANALYST_UPGRADE ||
                type == CatalystType.PRICE_TARGET_RAISE ||
                type == CatalystType.PRODUCT_LAUNCH ||
                type == CatalystType.EARNINGS_GROWTH ||
                type == CatalystType.DIVIDEND_INCREASE ||
                type == CatalystType.FACILITY_EXPANSION ||
                type == CatalystType.POSITIVE_BUSINESS_MOMENTUM;
    }

    private boolean isControlledLoosenCatalyst(NewsOpportunity opportunity) {
        if (opportunity == null ||
                opportunity.catalyst == null ||
                opportunity.catalyst.type == null ||
                opportunity.sentiment == null) {
            return false;
        }

        CatalystType type = opportunity.catalyst.type;

        boolean actionable = type == CatalystType.EARNINGS_GROWTH ||
                type == CatalystType.EARNINGS_BEAT ||
                type == CatalystType.DIVIDEND_INCREASE ||
                type == CatalystType.FACILITY_EXPANSION ||
                type == CatalystType.POSITIVE_BUSINESS_MOMENTUM ||
                type == CatalystType.PRODUCT_SALE ||
                type == CatalystType.CAPITAL_INVESTMENT ||
                type == CatalystType.NEW_PRODUCT_SERVICE ||
                type == CatalystType.PRODUCT_LAUNCH ||
                type == CatalystType.PARTNERSHIP ||
                type == CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP ||
                type == CatalystType.ANALYST_UPGRADE ||
                type == CatalystType.PRICE_TARGET_RAISE;

        return actionable &&
                opportunity.sentiment.positive >= 0.48 &&
                opportunity.sentiment.negative <= 0.32 &&
                opportunity.sentiment.netSentiment() >= 0.10;
    }
}
