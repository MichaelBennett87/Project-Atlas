package com.bot.strategy;

import com.bot.model.AdaptivePositionSizeProfile;
import com.bot.model.AutoBuyCandidate;
import com.bot.model.CatalystType;
import com.bot.model.EntryDecision;
import com.bot.model.FloatProfile;
import com.bot.model.GapProfile;
import com.bot.model.MarketCapProfile;
import com.bot.model.MarketDataCache;
import com.bot.model.MarketRegimeProfile;
import com.bot.model.MarketQuality;
import com.bot.model.NewsFreshnessProfile;
import com.bot.model.NewsOpportunity;
import com.bot.model.PerformanceStats;
import com.bot.model.RankedOpportunity;
import com.bot.model.RelativeVolumeProfile;
import com.bot.model.RelevanceDecision;
import com.bot.risk.MarketHoursService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Function;

public class EntryDecisionService {

    private static final double MIN_AUTO_BUY_SCORE =
            0.68;

    private static final double MICRO_SMALL_CAP_AUTO_BUY_SCORE =
            envDouble(
                    "AUTO_BUY_MICRO_SMALL_CAP_SCORE",
                    0.52
            );

    private static final double SMALL_MID_CAP_AUTO_BUY_SCORE =
            envDouble(
                    "AUTO_BUY_SMALL_MID_CAP_SCORE",
                    0.60
            );

    private static final double MID_CAP_AUTO_BUY_SCORE =
            envDouble(
                    "AUTO_BUY_MID_CAP_SCORE",
                    0.70
            );

    private static final double LARGE_CAP_AUTO_BUY_SCORE =
            envDouble(
                    "AUTO_BUY_LARGE_CAP_SCORE",
                    0.82
            );

    private static final double MEGA_CAP_AUTO_BUY_SCORE =
            envDouble(
                    "AUTO_BUY_MEGA_CAP_SCORE",
                    0.92
            );

    private static final boolean STRICT_INSTITUTIONAL_TEST_AUTO_BUY =
            !"false".equalsIgnoreCase(
                    System.getenv().getOrDefault(
                            "STRICT_INSTITUTIONAL_TEST_AUTO_BUY",
                            "true"
                    )
            );

    private static final long NORMAL_MAX_FLOAT_SHARES =
            envLong(
                    "AUTO_BUY_MAX_FLOAT_SHARES",
                    100_000_000L
            );

    private static final long HIGH_PRIORITY_MAX_FLOAT_SHARES =
            envLong(
                    "AUTO_BUY_HIGH_PRIORITY_MAX_FLOAT_SHARES",
                    250_000_000L
            );


    private static final long MAX_AUTO_BUY_MARKET_CAP =
            envLong(
                    "AUTO_BUY_MAX_MARKET_CAP",
                    5_000_000_000L
            );

    private static final double MIN_AUTO_BUY_RELATIVE_VOLUME =
            envDouble(
                    "AUTO_BUY_MIN_RELATIVE_VOLUME",
                    0.05
            );

    /*
     * Paper-testing execution policy:
     * A+ and B catalysts are allowed to ignore spread/ranking gates, but they
     * must still have a usable quote. This prevents the bot from staying in
     * pending forever while still blocking broken orders like ask=0 or price=0.
     */

    private static final double HARD_REJECT_SPREAD_PERCENT =
            envDouble(
                    "AUTO_BUY_HARD_REJECT_SPREAD_PERCENT",
                    0.50
            );

    private static final double HARD_REJECT_MIN_DOLLAR_VOLUME =
            envDouble(
                    "AUTO_BUY_HARD_REJECT_MIN_DOLLAR_VOLUME",
                    25_000.0
            );

    /*
     * Regular-session orders are submitted as market orders. During regular
     * hours, Alpaca's latest quote can briefly look stale or unusually wide
     * even when the last trade and same-day volume are usable. Using the same
     * hard spread gate for regular and extended sessions caused good catalysts
     * to die before order submission. Extended-hours buys still require clean
     * bid/ask because the broker submits limit orders there.
     */
    private static final double REGULAR_SESSION_HARD_REJECT_SPREAD_PERCENT =
            envDouble(
                    "AUTO_BUY_REGULAR_SESSION_HARD_REJECT_SPREAD_PERCENT",
                    1.00
            );

    private static final boolean ALLOW_REGULAR_SESSION_STALE_OR_WIDE_QUOTES =
            !"false".equalsIgnoreCase(
                    System.getenv().getOrDefault(
                            "AUTO_BUY_ALLOW_REGULAR_SESSION_STALE_OR_WIDE_QUOTES",
                            "true"
                    )
            );

    private static final boolean EXTENDED_HOURS_NEWS_BUYS_ENABLED =
            "true".equalsIgnoreCase(
                    System.getenv().getOrDefault(
                            "NEWS_BOT_EXTENDED_HOURS_BUY_ENABLED",
                            "true"
                    )
            );

    private final FloatAwarenessService floatAwarenessService;
    private final MarketCapAwarenessService marketCapAwarenessService;
    private final RelativeVolumeService relativeVolumeService;
    private final GapService gapService;
    private final GapPriceProvider gapPriceProvider;
    private final NewsFreshnessService newsFreshnessService;
    private final MarketRegimeService marketRegimeService;
    private final SignalPerformanceDatabase signalPerformanceDatabase;
    private final Function<String, ?> level2Provider;
    private final Function<String, ?> sectorMomentumProvider;
    private final AutoBuyList autoBuyList;
    private final MarketHoursService marketHoursService;
    private String lastDecisionReason = "";

    public EntryDecisionService() {
        this(
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public EntryDecisionService(
            MarketDataCache marketData
    ) {
        this(
                marketData,
                null,
                null,
                null,
                null,
                null
        );
    }

    public EntryDecisionService(
            MarketDataCache marketData,
            GapPriceProvider gapPriceProvider
    ) {
        this(
                marketData,
                gapPriceProvider,
                null,
                null,
                null,
                null
        );
    }

    public EntryDecisionService(
            MarketDataCache marketData,
            GapPriceProvider gapPriceProvider,
            MarketRegimeService marketRegimeService
    ) {
        this(
                marketData,
                gapPriceProvider,
                marketRegimeService,
                null,
                null,
                null
        );
    }

    public EntryDecisionService(
            MarketDataCache marketData,
            GapPriceProvider gapPriceProvider,
            MarketRegimeService marketRegimeService,
            SignalPerformanceDatabase signalPerformanceDatabase
    ) {
        this(
                marketData,
                gapPriceProvider,
                marketRegimeService,
                signalPerformanceDatabase,
                null,
                null
        );
    }

    public EntryDecisionService(
            MarketDataCache marketData,
            GapPriceProvider gapPriceProvider,
            MarketRegimeService marketRegimeService,
            Function<String, ?> level2Provider,
            Function<String, ?> sectorMomentumProvider
    ) {
        this(
                marketData,
                gapPriceProvider,
                marketRegimeService,
                null,
                level2Provider,
                sectorMomentumProvider
        );
    }

    private EntryDecisionService(
            MarketDataCache marketData,
            GapPriceProvider gapPriceProvider,
            MarketRegimeService marketRegimeService,
            SignalPerformanceDatabase signalPerformanceDatabase,
            Function<String, ?> level2Provider,
            Function<String, ?> sectorMomentumProvider
    ) {
        this.floatAwarenessService =
                new FloatAwarenessService();

        this.marketCapAwarenessService =
                new MarketCapAwarenessService();

        this.relativeVolumeService =
                marketData == null
                        ? null
                        : new RelativeVolumeService(
                        marketData
                );

        this.gapService =
                new GapService();

        this.gapPriceProvider =
                gapPriceProvider;

        this.newsFreshnessService =
                new NewsFreshnessService();

        this.marketRegimeService =
                marketRegimeService;

        this.signalPerformanceDatabase =
                signalPerformanceDatabase;

        this.level2Provider =
                level2Provider;

        this.sectorMomentumProvider =
                sectorMomentumProvider;

        this.autoBuyList =
                new AutoBuyList();

        this.marketHoursService =
                new MarketHoursService();
    }

    public EntryDecision decide(
            NewsOpportunity opportunity
    ) {
        return decide(
                opportunity,
                RelevanceDecision.PRIMARY_SUBJECT
        );
    }

    public EntryDecision decide(
            NewsOpportunity opportunity,
            RelevanceDecision relevanceDecision
    ) {
        if (opportunity == null || !opportunity.qualityPassed) {
            return EntryDecision.REJECT;
        }

        if (opportunity.sentiment == null || opportunity.catalyst == null) {
            return EntryDecision.REJECT;
        }

        if (relevanceDecision == RelevanceDecision.NOT_RELEVANT) {
            return EntryDecision.REJECT;
        }

        if (negativeSentimentLongVeto(opportunity)) {
            System.out.println(
                    "ENTRY REJECTED: negative sentiment veto before base long decision ticker=" +
                            safeTicker(opportunity) +
                            " catalyst=" +
                            opportunity.catalyst.type +
                            " sentiment=" +
                            opportunity.sentiment
            );
            return EntryDecision.REJECT;
        }

        if (isMustBuyCatalyst(opportunity.catalyst.type)) {
            return EntryDecision.IMMEDIATE_ENTRY;
        }


        if (relevanceDecision == RelevanceDecision.POSSIBLE_SUBJECT) {
            return EntryDecision.PENDING_CONFIRMATION;
        }

        CatalystType type =
                opportunity.catalyst.type;

        boolean veryStrongSentiment =
                opportunity.sentiment.positive >= 0.90 &&
                        opportunity.sentiment.negative <= 0.15 &&
                        opportunity.sentiment.netSentiment() >= 0.75;

        boolean strongSentiment =
                opportunity.sentiment.positive >= 0.45 &&
                        opportunity.sentiment.negative <= 0.28 &&
                        opportunity.sentiment.netSentiment() >= 0.18;

        if (isImmediateCatalyst(type) && strongSentiment) {
            return EntryDecision.IMMEDIATE_ENTRY;
        }

        if (isVeryStrongImmediateCatalyst(type) && veryStrongSentiment) {
            return EntryDecision.IMMEDIATE_ENTRY;
        }

        return EntryDecision.PENDING_CONFIRMATION;
    }

    public EntryDecision decide(
            RankedOpportunity rankedOpportunity
    ) {
        return decide(
                rankedOpportunity,
                RelevanceDecision.PRIMARY_SUBJECT
        );
    }

    public EntryDecision decide(
            RankedOpportunity rankedOpportunity,
            RelevanceDecision relevanceDecision
    ) {
        lastDecisionReason = "";

        if (rankedOpportunity == null || rankedOpportunity.opportunity == null) {
            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.REJECT,
                    "INVALID_RANKED_OPPORTUNITY"
            );
        }

        NewsOpportunity opportunity =
                rankedOpportunity.opportunity;

        if (!opportunity.qualityPassed ||
                opportunity.sentiment == null ||
                opportunity.catalyst == null ||
                opportunity.news == null) {
            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.REJECT,
                    "MISSING_OPPORTUNITY_DATA_OR_QUALITY_FAILED"
            );
        }

        if (relevanceDecision == RelevanceDecision.NOT_RELEVANT) {
            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.REJECT,
                    "TICKER_NOT_RELEVANT"
            );
        }

        if (negativeSentimentLongVeto(opportunity)) {
            System.out.println(
                    "ENTRY REJECTED: negative sentiment veto for " +
                            opportunity.news.getTicker() +
                            " catalyst=" +
                            opportunity.catalyst.type +
                            " sentiment=" +
                            opportunity.sentiment
            );
            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.REJECT,
                    "NEGATIVE_SENTIMENT_LONG_VETO"
            );
        }

        if (!marketHoursService.isRegularMarketOpenNow() &&
                !EXTENDED_HOURS_NEWS_BUYS_ENABLED) {
            System.out.println(
                    "ENTRY PENDING: extended-hours news buys disabled for " +
                            opportunity.news.getTicker() +
                            " session=" +
                            marketHoursService.currentSessionName()
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "EXTENDED_HOURS_BUY_DISABLED_PENDING_REGULAR_SESSION"
            );
        }

        FloatProfile floatProfile =
                floatAwarenessService.profile(
                        opportunity.news.getTicker()
                );

        MarketCapProfile marketCapProfile =
                marketCapAwarenessService.profile(
                        opportunity.news.getTicker()
                );

        RelativeVolumeProfile relativeVolumeProfile =
                getRelativeVolumeProfile(
                        opportunity.news.getTicker()
                );

        GapProfile gapProfile =
                getGapProfile(
                        opportunity.news.getTicker()
                );

        NewsFreshnessProfile freshnessProfile =
                newsFreshnessService.profile(
                        opportunity.news.getTimestamp()
                );

        MarketRegimeProfile marketRegimeProfile =
                getMarketRegimeProfile();

        Object level2Profile =
                getProviderProfile(
                        level2Provider,
                        opportunity.news.getTicker()
                );

        Object sectorProfile =
                getProviderProfile(
                        sectorMomentumProvider,
                        opportunity.news.getTicker()
                );

        double marketQualityScore =
                rankedOpportunity.marketQuality == null
                        ? 0.0
                        : rankedOpportunity.marketQuality.qualityScore;

        if (marketDataRateLimited(rankedOpportunity.marketQuality)) {
            System.out.println(
                    "ENTRY PENDING: market data rate limited for " +
                            opportunity.news.getTicker() +
                            " marketQuality=" +
                            rankedOpportunity.marketQuality
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "MARKET_DATA_RATE_LIMIT_PENDING_CONFIRMATION"
            );
        }

        boolean hardMarketQualityRejected =
                hardMarketQualityRejected(
                        rankedOpportunity.marketQuality
                );

        if (hardMarketQualityRejected) {
            System.out.println(
                    "ENTRY REJECTED: hard market-quality block for " +
                            opportunity.news.getTicker() +
                            " marketQuality=" +
                            rankedOpportunity.marketQuality
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.REJECT,
                    "HARD_MARKET_QUALITY_REJECT"
            );
        }

        boolean rvolAllowsAutoBuy =
                rvolAllowsAutoBuy(
                        relativeVolumeProfile
                );

        boolean gapAllowsAutoBuy =
                gapAllowsAutoBuy(
                        gapProfile
                );

        boolean freshnessAllowsAutoBuy =
                freshnessAllowsAutoBuy(
                        freshnessProfile
                );

        boolean regimeAllowsAutoBuy =
                regimeAllowsAutoBuy(
                        marketRegimeProfile
                );

        boolean level2AllowsAutoBuy =
                providerProfileAllowsAutoBuy(
                        level2Profile,
                        "HOSTILE_LEVEL2",
                        "WEAK_LEVEL2",
                        "HOSTILE_ORDER_BOOK",
                        "WEAK_ORDER_BOOK",
                        "BAD_LEVEL2"
                );

        boolean sectorAllowsAutoBuy =
                providerProfileAllowsAutoBuy(
                        sectorProfile,
                        "HOSTILE_SECTOR",
                        "WEAK_SECTOR",
                        "BAD_SECTOR"
                );

        double autoBuyScore =
                calculateAutoBuyScore(
                        rankedOpportunity,
                        floatProfile,
                        marketCapProfile,
                        relativeVolumeProfile,
                        gapProfile,
                        freshnessProfile,
                        marketRegimeProfile
                );

        double volatilityMomentumScore =
                calculateVolatilityMomentumScore(
                        rankedOpportunity,
                        floatProfile,
                        marketCapProfile,
                        relativeVolumeProfile,
                        freshnessProfile
                );

        double requiredVolatilityAutoBuyScore =
                requiredVolatilityAutoBuyScore(
                        marketCapProfile,
                        floatProfile,
                        opportunity.catalyst.type
                );

        System.out.println("ENTRY DECISION CHECK:");
        System.out.println("Ticker: " + opportunity.news.getTicker());
        System.out.println("Relevance Decision: " + relevanceDecision);
        System.out.println("Catalyst: " + opportunity.catalyst);
        System.out.println("Sentiment: " + opportunity.sentiment);
        System.out.println("Market quality score: " + marketQualityScore);
        System.out.println("Float profile: " + floatProfile);
        System.out.println("Market cap profile: " + marketCapProfile);
        System.out.println("Relative volume profile: " + relativeVolumeProfile);
        System.out.println("RVOL allows auto-buy: " + rvolAllowsAutoBuy);
        System.out.println("Gap profile: " + gapProfile);
        System.out.println("Gap allows auto-buy: " + gapAllowsAutoBuy);
        System.out.println("Freshness profile: " + freshnessProfile);
        System.out.println("Freshness allows auto-buy: " + freshnessAllowsAutoBuy);
        System.out.println("Market regime profile: " + marketRegimeProfile);
        System.out.println("Regime allows auto-buy: " + regimeAllowsAutoBuy);
        System.out.println("Level 2 profile: " + level2Profile);
        System.out.println("Level 2 allows auto-buy: " + level2AllowsAutoBuy);
        System.out.println("Sector profile: " + sectorProfile);
        System.out.println("Sector allows auto-buy: " + sectorAllowsAutoBuy);
        System.out.println("Auto-buy score: " + autoBuyScore);
        System.out.println("Volatility momentum score: " + volatilityMomentumScore);
        System.out.println("Required volatility auto-buy score: " + requiredVolatilityAutoBuyScore);

        boolean protectiveAutoBuyFiltersPassed =
                protectiveAutoBuyFiltersPassed(
                        rankedOpportunity,
                        floatProfile,
                        marketCapProfile,
                        relativeVolumeProfile
                );

        System.out.println("Protective auto-buy filters passed: " + protectiveAutoBuyFiltersPassed);

        if (isMustBuyCatalyst(opportunity.catalyst.type)) {
            if (!protectiveAutoBuyFiltersPassed) {
                return finishDecision(
                        rankedOpportunity,
                        relevanceDecision,
                        EntryDecision.REJECT,
                        "A_PLUS_PROTECTIVE_FILTERS_FAILED"
                );
            }
            if (hardMarketQualityRejected(rankedOpportunity.marketQuality)) {
                System.out.println(
                        "A+ AUTO-BUY BLOCKED BY HARD MARKET QUALITY REJECT: " +
                                opportunity.news.getTicker() +
                                " marketQuality=" +
                                rankedOpportunity.marketQuality
                );

                return finishDecision(
                        rankedOpportunity,
                        relevanceDecision,
                        EntryDecision.REJECT,
                        "A_PLUS_HARD_MARKET_QUALITY_REJECT"
                );
            }

            AutoBuyCandidate candidate =
                    new AutoBuyCandidate(
                            rankedOpportunity,
                            floatProfile,
                            Math.max(
                                    autoBuyScore,
                                    1.0
                            ),
                            "Auto-buy qualified: A+ catalyst with clean sentiment, protective filters, and hard market-quality checks"
                    );

            autoBuyList.add(
                    candidate
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.IMMEDIATE_ENTRY,
                    "A_PLUS_AUTO_BUY_QUALIFIED"
            );
        }

        if (protectiveAutoBuyFiltersPassed &&
                isPrimarySubjectBusinessMomentumEntry(
                        opportunity,
                        relevanceDecision,
                        rankedOpportunity.marketQuality
                )) {
            AutoBuyCandidate candidate =
                    new AutoBuyCandidate(
                            rankedOpportunity,
                            floatProfile,
                            Math.max(
                                    autoBuyScore,
                                    0.55
                            ),
                            "Auto-buy qualified: primary-subject business momentum with valid quote"
                    );

            autoBuyList.add(
                    candidate
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.IMMEDIATE_ENTRY,
                    "PRIMARY_SUBJECT_BUSINESS_MOMENTUM_AUTO_BUY"
            );
        }

        if (protectiveAutoBuyFiltersPassed &&
                isControlledFreshWireEntry(
                        rankedOpportunity,
                        relevanceDecision
                )) {
            AutoBuyCandidate candidate =
                    new AutoBuyCandidate(
                            rankedOpportunity,
                            floatProfile,
                            Math.max(
                                    autoBuyScore,
                                    0.50
                            ),
                            "Auto-buy qualified: controlled fresh wire entry with action catalyst and usable quote"
                    );

            autoBuyList.add(
                    candidate
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.IMMEDIATE_ENTRY,
                    "CONTROLLED_FRESH_WIRE_AUTO_BUY"
            );
        }

        if (protectiveAutoBuyFiltersPassed &&
                isNeutralWireActionableEntry(
                        rankedOpportunity,
                        relevanceDecision
                )) {
            AutoBuyCandidate candidate =
                    new AutoBuyCandidate(
                            rankedOpportunity,
                            floatProfile,
                            Math.max(
                                    autoBuyScore,
                                    0.58
                            ),
                            "Auto-buy qualified: neutral press-release wording with a high-action catalyst, clean sentiment, and usable quote"
                    );

            autoBuyList.add(
                    candidate
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.IMMEDIATE_ENTRY,
                    "NEUTRAL_WIRE_ACTIONABLE_AUTO_BUY"
            );
        }

        if (protectiveAutoBuyFiltersPassed &&
                isVolatilityFirstInstantEntry(
                        rankedOpportunity,
                        relevanceDecision,
                        floatProfile,
                        marketCapProfile,
                        volatilityMomentumScore,
                        requiredVolatilityAutoBuyScore
                )) {
            AutoBuyCandidate candidate =
                    new AutoBuyCandidate(
                            rankedOpportunity,
                            floatProfile,
                            Math.max(
                                    autoBuyScore,
                                    volatilityMomentumScore
                            ),
                            "Auto-buy qualified: volatility-first small-cap/low-float fresh catalyst profile"
                    );

            autoBuyList.add(
                    candidate
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.IMMEDIATE_ENTRY,
                    "VOLATILITY_FIRST_AUTO_BUY_QUALIFIED"
            );
        }

        if (relevanceDecision == RelevanceDecision.POSSIBLE_SUBJECT) {
            System.out.println(
                    "AUTO-BUY BLOCKED: ticker is only a possible subject for " +
                            opportunity.news.getTicker()
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "POSSIBLE_SUBJECT_PENDING_CONFIRMATION"
            );
        }

        boolean autoBuyQualified =
                protectiveAutoBuyFiltersPassed &&
                        volatilityMomentumScore >= requiredVolatilityAutoBuyScore &&
                        marketCapAutoBuyEligible(marketCapProfile, opportunity.catalyst.type) &&
                        rvolAllowsAutoBuy &&
                        gapAllowsAutoBuy &&
                        freshnessAllowsAutoBuy &&
                        regimeAllowsAutoBuy &&
                        level2AllowsAutoBuy &&
                        sectorAllowsAutoBuy &&
                        marketQualityScore >= requiredMarketQualityScore(marketCapProfile, floatProfile) &&
                        isAutoBuyCatalyst(opportunity.catalyst.type) &&
                        opportunity.sentiment.positive >= requiredPositiveSentiment(marketCapProfile, floatProfile) &&
                        opportunity.sentiment.negative <= allowedNegativeSentiment(marketCapProfile, floatProfile) &&
                        opportunity.sentiment.netSentiment() >= requiredNetSentiment(marketCapProfile, floatProfile);

        if (autoBuyQualified) {
            AutoBuyCandidate candidate =
                    new AutoBuyCandidate(
                            rankedOpportunity,
                            floatProfile,
                            Math.max(autoBuyScore, volatilityMomentumScore),
                            "Auto-buy qualified: volatility-adjusted strong news + favorable cap/float + acceptable liquidity/freshness"
                    );

            autoBuyList.add(
                    candidate
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.IMMEDIATE_ENTRY,
                    "SCORE_BASED_AUTO_BUY_QUALIFIED"
            );
        }

        EntryDecision baseDecision =
                decide(
                        opportunity,
                        relevanceDecision
                );

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY &&
                !protectiveAutoBuyFiltersPassed) {
            System.out.println(
                    "IMMEDIATE ENTRY DOWNGRADED TO PENDING: protective auto-buy filters did not pass for " +
                            opportunity.news.getTicker()
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "PROTECTIVE_AUTO_BUY_FILTERS_FAILED"
            );
        }

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY &&
                !hasUsableQuoteForPaperExecution(rankedOpportunity.marketQuality)) {
            System.out.println(
                    "IMMEDIATE ENTRY DOWNGRADED TO PENDING: hard quote block for " +
                            opportunity.news.getTicker() +
                            " marketQuality=" +
                            rankedOpportunity.marketQuality
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "HARD_QUOTE_BLOCK"
            );
        }

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY && !floatProfile.known) {
            System.out.println(
                    "IMMEDIATE ENTRY ALLOWED: unknown float for " +
                            opportunity.news.getTicker() +
                            " because catalyst/relevance already passed"
            );
        }

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY && !marketCapProfile.known) {
            System.out.println(
                    "IMMEDIATE ENTRY ALLOWED: unknown market cap for " +
                            opportunity.news.getTicker() +
                            " because catalyst/relevance already passed"
            );
        }

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY &&
                marketCapProfile.marketCapScore < 0.20) {
            System.out.println(
                    "IMMEDIATE ENTRY DOWNGRADED TO PENDING: market cap too large for aggressive auto-buy " +
                            opportunity.news.getTicker()
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "MARKET_CAP_TOO_LARGE"
            );
        }

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY && !rvolAllowsAutoBuy) {
            System.out.println(
                    "IMMEDIATE ENTRY DOWNGRADED TO PENDING: confirmed low RVOL for " +
                            opportunity.news.getTicker()
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "LOW_RELATIVE_VOLUME"
            );
        }

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY && !gapAllowsAutoBuy) {
            System.out.println(
                    "IMMEDIATE ENTRY DOWNGRADED TO PENDING: gap too extended for " +
                            opportunity.news.getTicker()
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "GAP_TOO_EXTENDED"
            );
        }

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY && !freshnessAllowsAutoBuy) {
            System.out.println(
                    "IMMEDIATE ENTRY DOWNGRADED TO PENDING: news too stale for aggressive auto-buy " +
                            opportunity.news.getTicker()
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "NEWS_TOO_STALE"
            );
        }

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY && !regimeAllowsAutoBuy) {
            System.out.println(
                    "IMMEDIATE ENTRY DOWNGRADED TO PENDING: market regime is not favorable for aggressive auto-buy " +
                            opportunity.news.getTicker()
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "MARKET_REGIME_NOT_FAVORABLE"
            );
        }

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY && !level2AllowsAutoBuy) {
            System.out.println(
                    "IMMEDIATE ENTRY DOWNGRADED TO PENDING: Level 2/order book is not favorable for " +
                            opportunity.news.getTicker()
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "LEVEL2_NOT_FAVORABLE"
            );
        }

        if (baseDecision == EntryDecision.IMMEDIATE_ENTRY && !sectorAllowsAutoBuy) {
            System.out.println(
                    "IMMEDIATE ENTRY DOWNGRADED TO PENDING: sector momentum is not favorable for " +
                            opportunity.news.getTicker()
            );

            return finishDecision(
                    rankedOpportunity,
                    relevanceDecision,
                    EntryDecision.PENDING_CONFIRMATION,
                    "SECTOR_NOT_FAVORABLE"
            );
        }

        return finishDecision(
                rankedOpportunity,
                relevanceDecision,
                baseDecision,
                baseDecision == EntryDecision.IMMEDIATE_ENTRY
                        ? "BASE_DECISION_IMMEDIATE_ENTRY"
                        : baseDecision == EntryDecision.PENDING_CONFIRMATION
                        ? "BASE_DECISION_PENDING_CONFIRMATION"
                        : "BASE_DECISION_REJECTED"
        );
    }

    public AdaptivePositionSizeProfile adaptiveSize(
            RankedOpportunity opportunity,
            RelevanceDecision relevanceDecision,
            int baseQuantity
    ) {
        if (opportunity == null ||
                opportunity.opportunity == null ||
                opportunity.opportunity.news == null ||
                baseQuantity <= 0) {
            return new AdaptivePositionSizeProfile(
                    "UNKNOWN",
                    0.0,
                    Math.max(
                            baseQuantity,
                            0
                    ),
                    0,
                    0.0,
                    "DEFENSIVE_SIZE",
                    "Adaptive sizing rejected invalid opportunity or base quantity"
            );
        }

        String ticker =
                opportunity.opportunity.news.getTicker();

        if (opportunity.opportunity.catalyst != null &&
                isMustBuyCatalyst(opportunity.opportunity.catalyst.type)) {
            return new AdaptivePositionSizeProfile(
                    ticker,
                    1.0,
                    baseQuantity,
                    baseQuantity,
                    1.0,
                    "A_PLUS_PERCENT_SIZE",
                    "A+ percentage size already selected by PositionSizer"
            );
        }

        double confidenceScore =
                calculateAdaptiveConfidenceScore(
                        opportunity,
                        relevanceDecision
                );

        if (isStarterMomentumSize(opportunity, relevanceDecision, confidenceScore)) {
            int starterQuantity =
                    Math.max(
                            1,
                            (int) Math.round(baseQuantity * 0.35)
                    );

            return new AdaptivePositionSizeProfile(
                    ticker,
                    confidenceScore,
                    baseQuantity,
                    starterQuantity,
                    0.35,
                    "STARTER_MOMENTUM_SIZE",
                    "Fresh single-source momentum starter entry selected reduced size"
            );
        }

        double multiplier;
        String category;

        if (confidenceScore >= 0.90) {
            multiplier = 1.5;
            category = "AGGRESSIVE_SIZE";
        } else if (confidenceScore >= 0.80) {
            multiplier = 1.25;
            category = "INCREASED_SIZE";
        } else if (confidenceScore >= 0.60) {
            multiplier = 1.0;
            category = "NORMAL_SIZE";
        } else if (confidenceScore >= 0.35) {
            multiplier = 0.5;
            category = "REDUCED_SIZE";
        } else {
            multiplier = 0.25;
            category = "DEFENSIVE_SIZE";
        }

        int finalQuantity =
                Math.max(
                        1,
                        (int) Math.round(baseQuantity * multiplier)
                );

        return new AdaptivePositionSizeProfile(
                ticker,
                confidenceScore,
                baseQuantity,
                finalQuantity,
                multiplier,
                category,
                "Adaptive sizing selected multiplier " +
                        multiplier +
                        " from confidence score " +
                        confidenceScore
        );
    }

    private boolean isStarterMomentumSize(
            RankedOpportunity opportunity,
            RelevanceDecision relevanceDecision,
            double confidenceScore
    ) {
        if (opportunity == null ||
                opportunity.opportunity == null ||
                opportunity.opportunity.catalyst == null ||
                opportunity.opportunity.catalyst.type == null ||
                opportunity.opportunity.news == null) {
            return false;
        }

        if (isMustBuyCatalyst(opportunity.opportunity.catalyst.type)) {
            return false;
        }

        if (!isControlledMomentumCatalyst(opportunity.opportunity.catalyst.type)) {
            return false;
        }

        String source =
                opportunity.opportunity.news.getSource() == null
                        ? ""
                        : opportunity.opportunity.news.getSource().toUpperCase();

        boolean singleSourceWire =
                source.contains("ALPACA") ||
                        source.contains("BENZINGA");

        return singleSourceWire &&
                confidenceScore < 0.72 &&
                relevanceDecision != RelevanceDecision.NOT_RELEVANT;
    }

    public AutoBuyList getAutoBuyList() {
        return autoBuyList;
    }

    public String getLastDecisionReason() {
        return lastDecisionReason == null || lastDecisionReason.isBlank()
                ? "ENTRY_DECISION_REJECTED"
                : lastDecisionReason;
    }

    private EntryDecision finishDecision(
            RankedOpportunity rankedOpportunity,
            RelevanceDecision relevanceDecision,
            EntryDecision decision,
            String reason
    ) {
        lastDecisionReason = reason == null || reason.isBlank()
                ? decision.name()
                : reason;

        printEntrySummary(
                rankedOpportunity,
                relevanceDecision,
                decision,
                lastDecisionReason
        );

        return decision;
    }

    private void printEntrySummary(
            RankedOpportunity rankedOpportunity,
            RelevanceDecision relevanceDecision,
            EntryDecision decision,
            String reason
    ) {
        if (rankedOpportunity == null || rankedOpportunity.opportunity == null) {
            System.out.println(
                    "ENTRY SUMMARY: ticker=UNKNOWN decision=" +
                            decision +
                            " reason=" +
                            reason
            );
            return;
        }

        NewsOpportunity opportunity = rankedOpportunity.opportunity;
        String ticker = opportunity.news == null
                ? "UNKNOWN"
                : opportunity.news.getTicker();
        String catalyst = opportunity.catalyst == null
                ? "UNKNOWN"
                : String.valueOf(opportunity.catalyst.type);
        String sentiment = opportunity.sentiment == null
                ? "UNKNOWN"
                : String.valueOf(opportunity.sentiment.netSentiment());
        String positive = opportunity.sentiment == null
                ? "UNKNOWN"
                : String.valueOf(opportunity.sentiment.positive);
        String negative = opportunity.sentiment == null
                ? "UNKNOWN"
                : String.valueOf(opportunity.sentiment.negative);
        String marketQuality = rankedOpportunity.marketQuality == null
                ? "UNKNOWN"
                : String.valueOf(rankedOpportunity.marketQuality.qualityScore);
        String spread = rankedOpportunity.marketQuality == null
                ? "UNKNOWN"
                : String.valueOf(rankedOpportunity.marketQuality.spreadPercent);
        String dollarVolume = rankedOpportunity.marketQuality == null
                ? "UNKNOWN"
                : String.valueOf(rankedOpportunity.marketQuality.dollarVolume);

        System.out.println(
                "ENTRY SUMMARY: ticker=" + ticker +
                        " decision=" + decision +
                        " reason=" + reason +
                        " relevance=" + relevanceDecision +
                        " catalyst=" + catalyst +
                        " sentimentNet=" + sentiment +
                        " sentimentPositive=" + positive +
                        " sentimentNegative=" + negative +
                        " rankScore=" + rankedOpportunity.rankScore +
                        " marketQualityScore=" + marketQuality +
                        " spread=" + spread +
                        " dollarVolume=" + dollarVolume
        );
    }

    private boolean hasUsableQuoteForPaperExecution(
            MarketQuality marketQuality
    ) {
        if (marketQuality == null) {
            return false;
        }

        if (marketQuality.latestTradePrice <= 0.0 && marketQuality.price <= 0.0) {
            return false;
        }

        boolean quoteUsable =
                marketQuality.bid > 0.0 &&
                        marketQuality.ask > 0.0 &&
                        marketQuality.ask >= marketQuality.bid;

        /*
         * Regular-session entries are sent as market orders, so stale or wide
         * latest quotes should not kill a trade when Alpaca still has a valid
         * latest trade price. Extended-hours entries are limit orders, but the
         * broker now caps aggressive limit prices from the latest trade instead
         * of blindly chasing a stale ask.
         */
        if (marketHoursService.isRegularMarketOpenNow() &&
                ALLOW_REGULAR_SESSION_STALE_OR_WIDE_QUOTES) {
            return true;
        }

        return quoteUsable || marketQuality.latestTradePrice > 0.0 || marketQuality.price > 0.0;
    }

    private boolean marketDataRateLimited(
            MarketQuality marketQuality
    ) {
        if (marketQuality == null || marketQuality.reason == null) {
            return false;
        }

        String reason = marketQuality.reason.toLowerCase();
        return reason.contains("429") || reason.contains("too many requests");
    }

    private boolean hardMarketQualityRejected(
            MarketQuality marketQuality
    ) {
        if (marketQuality == null) {
            return false;
        }

        if (marketQuality.latestTradePrice <= 0.0 && marketQuality.price <= 0.0) {
            return true;
        }

        if (marketQuality.dollarVolume > 0.0 &&
                marketQuality.dollarVolume < HARD_REJECT_MIN_DOLLAR_VOLUME) {
            return true;
        }

        boolean regularSession =
                marketHoursService.isRegularMarketOpenNow();

        boolean quoteUsable =
                marketQuality.bid > 0.0 &&
                        marketQuality.ask > 0.0 &&
                        marketQuality.ask >= marketQuality.bid;

        if (regularSession && ALLOW_REGULAR_SESSION_STALE_OR_WIDE_QUOTES) {
            System.out.println(
                    "ENTRY MARKET QUALITY: regular-session quote spread is advisory only. " +
                            "spread=" + marketQuality.spreadPercent +
                            " bid=" + marketQuality.bid +
                            " ask=" + marketQuality.ask +
                            " latestTrade=" + marketQuality.latestTradePrice +
                            " dollarVolume=" + marketQuality.dollarVolume
            );

            return false;
        }

        if (!quoteUsable) {
            return false;
        }

        double maxSpread =
                regularSession
                        ? REGULAR_SESSION_HARD_REJECT_SPREAD_PERCENT
                        : HARD_REJECT_SPREAD_PERCENT;

        if (marketQuality.spreadPercent >= maxSpread) {
            System.out.println(
                    "ENTRY MARKET QUALITY: spread hard reject. session=" +
                            marketHoursService.currentSessionName() +
                            " spread=" + marketQuality.spreadPercent +
                            " max=" + maxSpread +
                            " bid=" + marketQuality.bid +
                            " ask=" + marketQuality.ask
            );

            return true;
        }

        return false;
    }

    private RelativeVolumeProfile getRelativeVolumeProfile(
            String ticker
    ) {
        if (relativeVolumeService == null) {
            return new RelativeVolumeProfile(
                    ticker,
                    0.0,
                    0.0,
                    0.0,
                    0.35,
                    false,
                    "UNKNOWN_RVOL",
                    "Relative volume service unavailable"
            );
        }

        return relativeVolumeService.profile(
                ticker
        );
    }

    private GapProfile getGapProfile(
            String ticker
    ) {
        if (gapPriceProvider == null) {
            return new GapProfile(
                    ticker,
                    0.0,
                    0.0,
                    0.0,
                    0.50,
                    false,
                    "UNKNOWN_GAP",
                    "Gap price provider unavailable"
            );
        }

        double previousClose =
                gapPriceProvider.getPreviousClose(
                        ticker
                );

        double currentPrice =
                gapPriceProvider.getCurrentPrice(
                        ticker
                );

        return gapService.profile(
                ticker,
                previousClose,
                currentPrice
        );
    }

    private MarketRegimeProfile getMarketRegimeProfile() {
        if (marketRegimeService == null) {
            return new MarketRegimeProfile(
                    0,
                    0.0,
                    0.0,
                    0.50,
                    "UNKNOWN_REGIME",
                    "Market regime service unavailable"
            );
        }

        return marketRegimeService.currentRegime();
    }

    private Object getProviderProfile(
            Function<String, ?> provider,
            String ticker
    ) {
        if (provider == null) {
            return null;
        }

        try {
            return provider.apply(
                    ticker
            );
        } catch (Exception e) {
            return null;
        }
    }

    private boolean providerProfileAllowsAutoBuy(
            Object profile,
            String... blockedCategories
    ) {
        if (profile == null) {
            return true;
        }

        Object usableValue =
                readProperty(
                        profile,
                        "usable"
                );

        if (usableValue instanceof Boolean && !(Boolean) usableValue) {
            return true;
        }

        Object allowsValue =
                readProperty(
                        profile,
                        "allowsAutoBuy"
                );

        if (allowsValue instanceof Boolean) {
            return (Boolean) allowsValue;
        }

        Object categoryValue =
                readProperty(
                        profile,
                        "category"
                );

        String category =
                categoryValue == null
                        ? ""
                        : categoryValue.toString();

        for (String blockedCategory : blockedCategories) {
            if (blockedCategory.equalsIgnoreCase(category)) {
                return false;
            }
        }

        return true;
    }

    private Object readProperty(
            Object target,
            String propertyName
    ) {
        if (target == null || propertyName == null || propertyName.isBlank()) {
            return null;
        }

        try {
            Field field =
                    target.getClass().getDeclaredField(
                            propertyName
                    );

            field.setAccessible(
                    true
            );

            return field.get(
                    target
            );
        } catch (Exception ignored) {
        }

        try {
            String getter =
                    "get" +
                            propertyName.substring(0, 1).toUpperCase() +
                            propertyName.substring(1);

            Method method =
                    target.getClass().getMethod(
                            getter
                    );

            return method.invoke(
                    target
            );
        } catch (Exception ignored) {
        }

        try {
            String booleanGetter =
                    "is" +
                            propertyName.substring(0, 1).toUpperCase() +
                            propertyName.substring(1);

            Method method =
                    target.getClass().getMethod(
                            booleanGetter
                    );

            return method.invoke(
                    target
            );
        } catch (Exception ignored) {
        }

        return null;
    }

    private boolean rvolAllowsAutoBuy(
            RelativeVolumeProfile profile
    ) {
        if (profile == null || !profile.usable) {
            return true;
        }

        return !"LOW_RVOL".equalsIgnoreCase(
                profile.category
        );
    }

    private boolean gapAllowsAutoBuy(
            GapProfile profile
    ) {
        if (profile == null || !profile.usable) {
            return true;
        }

        return "LOW_GAP".equalsIgnoreCase(profile.category) ||
                "MODERATE_GAP".equalsIgnoreCase(profile.category);
    }

    private boolean freshnessAllowsAutoBuy(
            NewsFreshnessProfile profile
    ) {
        if (profile == null || !profile.usable) {
            return true;
        }

        return "ULTRA_FRESH".equalsIgnoreCase(profile.category) ||
                "FRESH".equalsIgnoreCase(profile.category);
    }

    private boolean regimeAllowsAutoBuy(
            MarketRegimeProfile profile
    ) {
        if (profile == null) {
            return true;
        }

        return "HOT_REGIME".equalsIgnoreCase(profile.category) ||
                "NORMAL_REGIME".equalsIgnoreCase(profile.category) ||
                "UNKNOWN_REGIME".equalsIgnoreCase(profile.category);
    }


    private double calculateVolatilityMomentumScore(
            RankedOpportunity rankedOpportunity,
            FloatProfile floatProfile,
            MarketCapProfile marketCapProfile,
            RelativeVolumeProfile relativeVolumeProfile,
            NewsFreshnessProfile freshnessProfile
    ) {
        if (rankedOpportunity == null ||
                rankedOpportunity.opportunity == null) {
            return 0.0;
        }

        NewsOpportunity opportunity =
                rankedOpportunity.opportunity;

        double rankScore =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                rankedOpportunity.rankScore
                        )
                );

        double catalystScore =
                opportunity.catalyst == null
                        ? 0.0
                        : Math.max(
                        0.0,
                        opportunity.catalyst.weight
                );

        double positiveSentiment =
                opportunity.sentiment == null
                        ? 0.0
                        : Math.max(
                        0.0,
                        opportunity.sentiment.positive
                );

        double netSentiment =
                opportunity.sentiment == null
                        ? 0.0
                        : Math.max(
                        0.0,
                        opportunity.sentiment.netSentiment()
                );

        double marketQualityScore =
                rankedOpportunity.marketQuality == null
                        ? 0.35
                        : rankedOpportunity.marketQuality.qualityScore;

        double floatScore =
                floatProfile == null
                        ? 0.45
                        : floatProfile.floatScore;

        double marketCapScore =
                marketCapProfile == null
                        ? 0.62
                        : marketCapProfile.marketCapScore;

        double rvolScore =
                relativeVolumeProfile == null
                        ? 0.45
                        : relativeVolumeProfile.rvolScore;

        double freshnessScore =
                freshnessProfile == null
                        ? 0.50
                        : freshnessProfile.freshnessScore;

        return clamp01(
                (marketCapScore * 0.28)
                        + (floatScore * 0.24)
                        + (catalystScore * 0.18)
                        + (positiveSentiment * 0.08)
                        + (netSentiment * 0.08)
                        + (rankScore * 0.06)
                        + (marketQualityScore * 0.04)
                        + (rvolScore * 0.02)
                        + (freshnessScore * 0.02)
        );
    }

    private boolean isVolatilityFirstInstantEntry(
            RankedOpportunity rankedOpportunity,
            RelevanceDecision relevanceDecision,
            FloatProfile floatProfile,
            MarketCapProfile marketCapProfile,
            double volatilityMomentumScore,
            double requiredVolatilityAutoBuyScore
    ) {
        if (rankedOpportunity == null ||
                rankedOpportunity.opportunity == null ||
                rankedOpportunity.opportunity.catalyst == null ||
                rankedOpportunity.opportunity.sentiment == null) {
            return false;
        }

        if (relevanceDecision != RelevanceDecision.PRIMARY_SUBJECT) {
            return false;
        }

        NewsOpportunity opportunity =
                rankedOpportunity.opportunity;

        if (!isControlledMomentumCatalyst(opportunity.catalyst.type) &&
                !isAutoBuyCatalyst(opportunity.catalyst.type)) {
            return false;
        }

        if (!hasUsableQuoteForPaperExecution(rankedOpportunity.marketQuality)) {
            return false;
        }

        if (!marketCapAutoBuyEligible(marketCapProfile, opportunity.catalyst.type)) {
            return false;
        }

        double requiredScore =
                Math.min(
                        requiredVolatilityAutoBuyScore,
                        isMicroOrSmallCap(marketCapProfile)
                                ? 0.54
                                : requiredVolatilityAutoBuyScore
                );

        return volatilityMomentumScore >= requiredScore &&
                opportunity.sentiment.positive >= requiredPositiveSentiment(marketCapProfile, floatProfile) &&
                opportunity.sentiment.negative <= allowedNegativeSentiment(marketCapProfile, floatProfile) &&
                opportunity.sentiment.netSentiment() >= requiredNetSentiment(marketCapProfile, floatProfile) &&
                rankedOpportunity.rankScore >= requiredRankScore(marketCapProfile, floatProfile) &&
                requiredMarketQualityScore(marketCapProfile, floatProfile) <=
                        (rankedOpportunity.marketQuality == null
                                ? 0.35
                                : rankedOpportunity.marketQuality.qualityScore);
    }

    private double requiredVolatilityAutoBuyScore(
            MarketCapProfile marketCapProfile,
            FloatProfile floatProfile,
            CatalystType type
    ) {
        double base;

        if (isMicroOrSmallCap(marketCapProfile)) {
            base = MICRO_SMALL_CAP_AUTO_BUY_SCORE;
        } else if (marketCapProfile != null &&
                "SMALL_MID_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            base = SMALL_MID_CAP_AUTO_BUY_SCORE;
        } else if (marketCapProfile != null &&
                "MID_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            base = MID_CAP_AUTO_BUY_SCORE;
        } else if (marketCapProfile != null &&
                "LARGE_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            base = LARGE_CAP_AUTO_BUY_SCORE;
        } else if (marketCapProfile != null &&
                "MEGA_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            base = MEGA_CAP_AUTO_BUY_SCORE;
        } else {
            base = 0.60;
        }

        if (floatProfile != null && floatProfile.known && floatProfile.floatScore >= 0.90) {
            base -= 0.04;
        }

        if (highPriorityFloatException(type)) {
            base -= 0.05;
        }

        return clamp01(base);
    }

    private double requiredPositiveSentiment(
            MarketCapProfile marketCapProfile,
            FloatProfile floatProfile
    ) {
        if (isMicroOrSmallCap(marketCapProfile) ||
                (floatProfile != null && floatProfile.floatScore >= 0.90)) {
            return 0.25;
        }

        if (marketCapProfile != null &&
                "SMALL_MID_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            return 0.32;
        }

        if (marketCapProfile != null &&
                "MID_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            return 0.40;
        }

        return 0.52;
    }

    private double requiredNetSentiment(
            MarketCapProfile marketCapProfile,
            FloatProfile floatProfile
    ) {
        if (isMicroOrSmallCap(marketCapProfile) ||
                (floatProfile != null && floatProfile.floatScore >= 0.90)) {
            return 0.08;
        }

        if (marketCapProfile != null &&
                "SMALL_MID_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            return 0.12;
        }

        if (marketCapProfile != null &&
                "MID_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            return 0.18;
        }

        return 0.28;
    }

    private double allowedNegativeSentiment(
            MarketCapProfile marketCapProfile,
            FloatProfile floatProfile
    ) {
        if (isMicroOrSmallCap(marketCapProfile) ||
                (floatProfile != null && floatProfile.floatScore >= 0.90)) {
            return 0.34;
        }

        if (marketCapProfile != null &&
                "SMALL_MID_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            return 0.30;
        }

        return 0.24;
    }

    private double requiredMarketQualityScore(
            MarketCapProfile marketCapProfile,
            FloatProfile floatProfile
    ) {
        if (isMicroOrSmallCap(marketCapProfile) ||
                (floatProfile != null && floatProfile.floatScore >= 0.90)) {
            return 0.15;
        }

        if (marketCapProfile != null &&
                "SMALL_MID_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            return 0.22;
        }

        if (marketCapProfile != null &&
                "MID_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            return 0.32;
        }

        return 0.45;
    }

    private double requiredRankScore(
            MarketCapProfile marketCapProfile,
            FloatProfile floatProfile
    ) {
        if (isMicroOrSmallCap(marketCapProfile) ||
                (floatProfile != null && floatProfile.floatScore >= 0.90)) {
            return 0.18;
        }

        if (marketCapProfile != null &&
                "SMALL_MID_CAP".equalsIgnoreCase(marketCapProfile.category)) {
            return 0.24;
        }

        return 0.32;
    }

    private boolean isMicroOrSmallCap(
            MarketCapProfile marketCapProfile
    ) {
        return marketCapProfile != null &&
                (
                        "MICRO_CAP".equalsIgnoreCase(marketCapProfile.category) ||
                                "SMALL_CAP".equalsIgnoreCase(marketCapProfile.category)
                );
    }

    private double clamp01(double value) {
        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        value
                )
        );
    }

    private boolean marketCapAutoBuyEligible(
            MarketCapProfile marketCapProfile
    ) {
        return marketCapAutoBuyEligible(
                marketCapProfile,
                null
        );
    }

    private boolean marketCapAutoBuyEligible(
            MarketCapProfile marketCapProfile,
            CatalystType type
    ) {
        if (marketCapProfile == null || !marketCapProfile.known) {
            return true;
        }

        if (marketCapProfile.marketCap <= MAX_AUTO_BUY_MARKET_CAP) {
            return true;
        }

        if (marketCapProfile.marketCapScore >= 0.80) {
            return true;
        }

        /*
         * Large caps can still trade on truly exceptional catalysts, but the
         * score threshold is much higher. This keeps the strategy focused on
         * lower-cap volatility while not completely excluding rare large-cap
         * shock events.
         */
        return highPriorityFloatException(type) &&
                marketCapProfile.marketCap <= 50_000_000_000L;
    }

    private double calculateAutoBuyScore(
            RankedOpportunity rankedOpportunity,
            FloatProfile floatProfile,
            MarketCapProfile marketCapProfile,
            RelativeVolumeProfile relativeVolumeProfile,
            GapProfile gapProfile,
            NewsFreshnessProfile freshnessProfile,
            MarketRegimeProfile marketRegimeProfile
    ) {
        double rankScore =
                Math.min(
                        1.0,
                        rankedOpportunity.rankScore
                );

        double sentimentScore =
                rankedOpportunity.opportunity.sentiment == null
                        ? 0.0
                        : Math.max(
                        0.0,
                        rankedOpportunity.opportunity.sentiment.netSentiment()
                );

        double catalystScore =
                rankedOpportunity.opportunity.catalyst == null
                        ? 0.0
                        : Math.max(
                        0.0,
                        rankedOpportunity.opportunity.catalyst.weight
                );

        double marketQualityScore =
                rankedOpportunity.marketQuality == null
                        ? 0.0
                        : rankedOpportunity.marketQuality.qualityScore;

        double floatScore =
                floatProfile == null
                        ? 0.0
                        : floatProfile.floatScore;

        double marketCapScore =
                marketCapProfile == null
                        ? 0.0
                        : marketCapProfile.marketCapScore;

        double rvolScore =
                relativeVolumeProfile == null
                        ? 0.35
                        : relativeVolumeProfile.rvolScore;

        double gapScore =
                gapProfile == null
                        ? 0.50
                        : gapProfile.gapScore;

        double freshnessScore =
                freshnessProfile == null
                        ? 0.50
                        : freshnessProfile.freshnessScore;

        double regimeScore =
                marketRegimeProfile == null
                        ? 0.50
                        : marketRegimeProfile.regimeScore;

        return (rankScore * 0.14)
                + (sentimentScore * 0.16)
                + (catalystScore * 0.14)
                + (marketQualityScore * 0.08)
                + (floatScore * 0.17)
                + (marketCapScore * 0.17)
                + (rvolScore * 0.05)
                + (gapScore * 0.03)
                + (freshnessScore * 0.04)
                + (regimeScore * 0.02);
    }

    private double calculateAdaptiveConfidenceScore(
            RankedOpportunity opportunity,
            RelevanceDecision relevanceDecision
    ) {
        double score =
                opportunity.rankScore;

        if (relevanceDecision == RelevanceDecision.PRIMARY_SUBJECT) {
            score += 0.05;
        } else if (relevanceDecision == RelevanceDecision.POSSIBLE_SUBJECT) {
            score -= 0.05;
        }

        FloatProfile floatProfile =
                floatAwarenessService.profile(
                        opportunity.opportunity.news.getTicker()
                );

        MarketCapProfile marketCapProfile =
                marketCapAwarenessService.profile(
                        opportunity.opportunity.news.getTicker()
                );

        double volatilitySizeScore =
                calculateVolatilityMomentumScore(
                        opportunity,
                        floatProfile,
                        marketCapProfile,
                        null,
                        null
                );

        if (opportunity.marketQuality != null) {
            score =
                    (score * 0.60) +
                            (opportunity.marketQuality.qualityScore * 0.15) +
                            (volatilitySizeScore * 0.25);
        } else {
            score =
                    (score * 0.70) +
                            (volatilitySizeScore * 0.30);
        }

        score += performanceAdjustment(opportunity);

        if (score > 1.0) {
            return 1.0;
        }

        if (score < 0.0) {
            return 0.0;
        }

        return score;
    }

    private double performanceAdjustment(
            RankedOpportunity opportunity
    ) {
        if (signalPerformanceDatabase == null ||
                opportunity == null ||
                opportunity.opportunity == null ||
                opportunity.opportunity.news == null ||
                opportunity.opportunity.catalyst == null ||
                opportunity.opportunity.catalyst.type == null) {
            return 0.0;
        }

        PerformanceStats tickerCatalystStats =
                signalPerformanceDatabase.tickerCatalystStats(
                        opportunity.opportunity.news.getTicker(),
                        opportunity.opportunity.catalyst.type.name()
                );

        if (tickerCatalystStats.closedSignals >= 5) {
            return performanceAdjustmentFromStats(
                    tickerCatalystStats
            );
        }

        PerformanceStats catalystStats =
                signalPerformanceDatabase.catalystStats(
                        opportunity.opportunity.catalyst.type.name()
                );

        if (catalystStats.closedSignals >= 5) {
            return performanceAdjustmentFromStats(
                    catalystStats
            );
        }

        return 0.0;
    }

    private double performanceAdjustmentFromStats(
            PerformanceStats stats
    ) {
        if (stats == null || stats.closedSignals <= 0) {
            return 0.0;
        }

        if (stats.winRate >= 0.70 && stats.averageExitGain >= 0.03) {
            return 0.15;
        }

        if (stats.winRate >= 0.60 && stats.averageExitGain >= 0.01) {
            return 0.08;
        }

        if (stats.winRate <= 0.35 || stats.averageExitGain <= -0.03) {
            return -0.45;
        }

        if (stats.winRate <= 0.45 || stats.averageExitGain <= -0.01) {
            return -0.25;
        }

        return 0.0;
    }

    private boolean protectiveAutoBuyFiltersPassed(
            RankedOpportunity rankedOpportunity,
            FloatProfile floatProfile,
            MarketCapProfile marketCapProfile,
            RelativeVolumeProfile relativeVolumeProfile
    ) {
        if (!STRICT_INSTITUTIONAL_TEST_AUTO_BUY) {
            return true;
        }

        if (rankedOpportunity == null ||
                rankedOpportunity.opportunity == null ||
                rankedOpportunity.opportunity.news == null ||
                rankedOpportunity.opportunity.catalyst == null ||
                rankedOpportunity.opportunity.catalyst.type == null) {
            System.out.println("AUTO-BUY PROTECTION BLOCKED: missing ranked opportunity/catalyst.");
            return false;
        }

        String ticker =
                rankedOpportunity.opportunity.news.getTicker();

        CatalystType type =
                rankedOpportunity.opportunity.catalyst.type;

        if (!institutionalTestAutoBuyCatalystAllowed(type)) {
            System.out.println(
                    "AUTO-BUY PROTECTION BLOCKED: catalyst not allowed during institutional-only test " +
                            ticker +
                            " catalyst=" +
                            type
            );

            return false;
        }

        boolean floatKnown =
                floatProfile != null &&
                        floatProfile.known;

        if (!floatKnown) {
            System.out.println(
                    "AUTO-BUY PROTECTION WARNING: float unavailable for " +
                            ticker +
                            "; allowing institutional test trade to rely on market quality / RVOL / freshness instead."
            );
        }

        long maxFloat =
                highPriorityFloatException(type)
                        ? HIGH_PRIORITY_MAX_FLOAT_SHARES
                        : NORMAL_MAX_FLOAT_SHARES;

        if (floatKnown && floatProfile.sharesFloat > maxFloat) {
            System.out.println(
                    "AUTO-BUY PROTECTION BLOCKED: float too large for " +
                            ticker +
                            " sharesFloat=" +
                            floatProfile.sharesFloat +
                            " maxFloat=" +
                            maxFloat +
                            " catalyst=" +
                            type
            );

            return false;
        }

        if (marketCapProfile != null &&
                marketCapProfile.known &&
                marketCapProfile.marketCap > MAX_AUTO_BUY_MARKET_CAP &&
                !highPriorityFloatException(type)) {
            System.out.println(
                    "AUTO-BUY PROTECTION BLOCKED: market cap too large for " +
                            ticker +
                            " marketCap=" +
                            marketCapProfile.marketCap +
                            " maxMarketCap=" +
                            MAX_AUTO_BUY_MARKET_CAP +
                            " catalyst=" +
                            type
            );

            return false;
        }

        double relativeVolume =
                resolvedRelativeVolume(
                        rankedOpportunity,
                        relativeVolumeProfile
                );

        if (relativeVolume <= 0) {
            System.out.println(
                    "AUTO-BUY PROTECTION WARNING: relative volume unavailable for " +
                            ticker +
                            "; allowing institutional test trade to rely on market quality instead."
            );

            return true;
        }

        if (relativeVolume < MIN_AUTO_BUY_RELATIVE_VOLUME) {
            System.out.println(
                    "AUTO-BUY PROTECTION BLOCKED: relative volume too low for " +
                            ticker +
                            " relativeVolume=" +
                            relativeVolume +
                            " required=" +
                            MIN_AUTO_BUY_RELATIVE_VOLUME
            );

            return false;
        }

        return true;
    }

    private boolean institutionalTestAutoBuyCatalystAllowed(
            CatalystType type
    ) {
        return type == CatalystType.FDA_APPROVAL ||
                type == CatalystType.FDA_CLEARANCE ||
                type == CatalystType.FDA_REGISTRATION ||
                type == CatalystType.CLINICAL_TRIAL_SUCCESS ||
                type == CatalystType.GUIDANCE_RAISE ||
                type == CatalystType.EARNINGS_BEAT ||
                type == CatalystType.EARNINGS_GROWTH ||
                type == CatalystType.MAJOR_CONTRACT ||
                type == CatalystType.MAJOR_ORDER ||
                type == CatalystType.CUSTOMER_ORDER ||
                type == CatalystType.SALES_AGREEMENT ||
                type == CatalystType.MATERIAL_SUPPLY_AGREEMENT ||
                type == CatalystType.BUYOUT_OFFER ||
                type == CatalystType.MERGER_ACQUISITION ||
                type == CatalystType.PARTNERSHIP ||
                type == CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP ||
                type == CatalystType.PRODUCT_LAUNCH ||
                type == CatalystType.NEW_PRODUCT_SERVICE ||
                type == CatalystType.FACILITY_EXPANSION ||
                type == CatalystType.STRATEGIC_REVIEW ||
                type == CatalystType.INSIDER_BUYING ||
                type == CatalystType.POSITIVE_BUSINESS_MOMENTUM;
    }

    private boolean highPriorityFloatException(
            CatalystType type
    ) {
        return type == CatalystType.FDA_APPROVAL ||
                type == CatalystType.FDA_CLEARANCE ||
                type == CatalystType.FDA_REGISTRATION ||
                type == CatalystType.BUYOUT_OFFER ||
                type == CatalystType.MERGER_ACQUISITION ||
                type == CatalystType.MAJOR_CONTRACT ||
                type == CatalystType.MAJOR_ORDER ||
                type == CatalystType.GUIDANCE_RAISE ||
                type == CatalystType.STRATEGIC_REVIEW;
    }

    private double resolvedRelativeVolume(
            RankedOpportunity rankedOpportunity,
            RelativeVolumeProfile relativeVolumeProfile
    ) {
        if (relativeVolumeProfile != null &&
                relativeVolumeProfile.usable &&
                relativeVolumeProfile.relativeVolume > 0) {
            return relativeVolumeProfile.relativeVolume;
        }

        if (rankedOpportunity != null &&
                rankedOpportunity.marketQuality != null &&
                rankedOpportunity.marketQuality.relativeVolume > 0) {
            return rankedOpportunity.marketQuality.relativeVolume;
        }

        return 0.0;
    }

    private boolean negativeSentimentLongVeto(
            NewsOpportunity opportunity
    ) {
        if (opportunity == null ||
                opportunity.sentiment == null ||
                opportunity.catalyst == null ||
                opportunity.catalyst.type == null) {
            return false;
        }

        CatalystType type =
                opportunity.catalyst.type;

        if (type == CatalystType.FDA_REJECTION ||
                type == CatalystType.CLINICAL_TRIAL_FAILURE ||
                type == CatalystType.CLINICAL_HOLD ||
                type == CatalystType.CLINICAL_DELAY ||
                type == CatalystType.GUIDANCE_CUT ||
                type == CatalystType.EARNINGS_MISS ||
                type == CatalystType.OFFERING_DILUTION ||
                type == CatalystType.SHELF_OFFERING ||
                type == CatalystType.PRIVATE_PLACEMENT ||
                type == CatalystType.CONVERTIBLE_PREFERRED_OFFERING ||
                type == CatalystType.SECURITIES_LITIGATION ||
                type == CatalystType.LAWSUIT ||
                type == CatalystType.INVESTIGATION ||
                type == CatalystType.RESTATEMENT ||
                type == CatalystType.BANKRUPTCY ||
                type == CatalystType.DELISTING_RISK ||
                type == CatalystType.NASDAQ_NONCOMPLIANCE ||
                type == CatalystType.RECALL ||
                type == CatalystType.CYBERSECURITY_INCIDENT ||
                type == CatalystType.SHORT_SELLER_REPORT ||
                type == CatalystType.NEGATIVE_HEADWIND ||
                type == CatalystType.CREDIT_STRESS) {
            return true;
        }

        double net =
                opportunity.sentiment.netSentiment();

        return opportunity.sentiment.negative >= 0.40 &&
                opportunity.sentiment.negative > opportunity.sentiment.positive &&
                net <= -0.15;
    }

    private String safeTicker(
            NewsOpportunity opportunity
    ) {
        if (opportunity == null || opportunity.news == null) {
            return "UNKNOWN";
        }

        return opportunity.news.getTicker();
    }

    private boolean isAutoBuyCatalyst(
            CatalystType type
    ) {
        return isImmediateCatalyst(type) ||
                isVeryStrongImmediateCatalyst(type);
    }

    private boolean isImmediateCatalyst(
            CatalystType type
    ) {
        return type == CatalystType.FDA_APPROVAL ||
                type == CatalystType.FDA_CLEARANCE ||
                type == CatalystType.CLINICAL_TRIAL_SUCCESS ||
                type == CatalystType.DRUG_DATA_POSITIVE ||
                type == CatalystType.EARNINGS_BEAT ||
                type == CatalystType.EARNINGS_GROWTH ||
                type == CatalystType.DIVIDEND_INCREASE ||
                type == CatalystType.GUIDANCE_RAISE ||
                type == CatalystType.MAJOR_CONTRACT ||
                type == CatalystType.MAJOR_ORDER ||
                type == CatalystType.CUSTOMER_ORDER ||
                type == CatalystType.SALES_AGREEMENT ||
                type == CatalystType.MATERIAL_SUPPLY_AGREEMENT ||
                type == CatalystType.BUYOUT_OFFER ||
                type == CatalystType.MERGER_ACQUISITION ||
                type == CatalystType.SHARE_BUYBACK ||
                type == CatalystType.NASDAQ_COMPLIANCE ||
                type == CatalystType.NASDAQ_COMPLIANCE_EXTENSION ||
                type == CatalystType.NYSE_COMPLIANCE ||
                type == CatalystType.EXCHANGE_COMPLIANCE ||
                type == CatalystType.INDEX_ADDITION ||
                type == CatalystType.FACILITY_EXPANSION ||
                type == CatalystType.STRATEGIC_REVIEW ||
                type == CatalystType.INSIDER_BUYING ||
                type == CatalystType.POSITIVE_BUSINESS_MOMENTUM;
    }

    private boolean isVeryStrongImmediateCatalyst(
            CatalystType type
    ) {
        return type == CatalystType.ANALYST_UPGRADE ||
                type == CatalystType.PRICE_TARGET_RAISE ||
                type == CatalystType.PRODUCT_LAUNCH ||
                type == CatalystType.PARTNERSHIP ||
                type == CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP ||
                type == CatalystType.STRATEGIC_REVIEW ||
                type == CatalystType.INSIDER_BUYING;
    }

    private boolean isMustBuyCatalyst(
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
                type == CatalystType.SHARE_BUYBACK;
    }

    private boolean isNeutralWireActionableEntry(
            RankedOpportunity rankedOpportunity,
            RelevanceDecision relevanceDecision
    ) {
        if (rankedOpportunity == null ||
                rankedOpportunity.opportunity == null ||
                rankedOpportunity.opportunity.catalyst == null ||
                rankedOpportunity.opportunity.catalyst.type == null ||
                rankedOpportunity.opportunity.sentiment == null) {
            return false;
        }

        NewsOpportunity opportunity =
                rankedOpportunity.opportunity;

        if (!hasUsableQuoteForPaperExecution(rankedOpportunity.marketQuality)) {
            return false;
        }

        if (!isNeutralWireActionCatalyst(opportunity.catalyst.type)) {
            return false;
        }

        if (opportunity.sentiment.negative > 0.28 ||
                opportunity.sentiment.netSentiment() < -0.02 ||
                opportunity.sentiment.positive < 0.08) {
            return false;
        }

        double marketQualityScore =
                rankedOpportunity.marketQuality == null
                        ? 0.0
                        : rankedOpportunity.marketQuality.qualityScore;

        double requiredRank =
                relevanceDecision == RelevanceDecision.PRIMARY_SUBJECT
                        ? 0.55
                        : 0.72;

        double requiredMarketQuality =
                relevanceDecision == RelevanceDecision.PRIMARY_SUBJECT
                        ? 0.45
                        : 0.65;

        return relevanceDecision != RelevanceDecision.NOT_RELEVANT &&
                rankedOpportunity.rankScore >= requiredRank &&
                marketQualityScore >= requiredMarketQuality &&
                opportunity.catalyst.weight >= 0.70;
    }

    private boolean isNeutralWireActionCatalyst(
            CatalystType type
    ) {
        return type == CatalystType.FDA_APPROVAL ||
                type == CatalystType.FDA_CLEARANCE ||
                type == CatalystType.FDA_REGISTRATION ||
                type == CatalystType.CLINICAL_TRIAL_SUCCESS ||
                type == CatalystType.GUIDANCE_RAISE ||
                type == CatalystType.EARNINGS_BEAT ||
                type == CatalystType.EARNINGS_GROWTH ||
                type == CatalystType.MAJOR_CONTRACT ||
                type == CatalystType.MAJOR_ORDER ||
                type == CatalystType.CUSTOMER_ORDER ||
                type == CatalystType.SALES_AGREEMENT ||
                type == CatalystType.MATERIAL_SUPPLY_AGREEMENT ||
                type == CatalystType.BUYOUT_OFFER ||
                type == CatalystType.MERGER_ACQUISITION ||
                type == CatalystType.STRATEGIC_REVIEW ||
                type == CatalystType.INSIDER_BUYING;
    }

    private boolean isControlledFreshWireEntry(
            RankedOpportunity rankedOpportunity,
            RelevanceDecision relevanceDecision
    ) {
        if (rankedOpportunity == null ||
                rankedOpportunity.opportunity == null ||
                rankedOpportunity.opportunity.catalyst == null ||
                rankedOpportunity.opportunity.catalyst.type == null ||
                rankedOpportunity.opportunity.sentiment == null) {
            return false;
        }

        NewsOpportunity opportunity =
                rankedOpportunity.opportunity;

        if (relevanceDecision != RelevanceDecision.PRIMARY_SUBJECT) {
            return false;
        }

        if (!hasUsableQuoteForPaperExecution(rankedOpportunity.marketQuality)) {
            return false;
        }

        if (!isControlledMomentumCatalyst(opportunity.catalyst.type)) {
            return false;
        }

        double marketQualityScore =
                rankedOpportunity.marketQuality == null
                        ? 0.0
                        : rankedOpportunity.marketQuality.qualityScore;

        return opportunity.catalyst.weight >= 0.40 &&
                opportunity.sentiment.positive >= 0.30 &&
                opportunity.sentiment.negative <= 0.28 &&
                opportunity.sentiment.netSentiment() >= 0.08 &&
                (rankedOpportunity.rankScore >= 0.20 || marketQualityScore >= 0.20);
    }

    private boolean isControlledMomentumCatalyst(
            CatalystType type
    ) {
        return type == CatalystType.EARNINGS_GROWTH ||
                type == CatalystType.EARNINGS_BEAT ||
                type == CatalystType.DIVIDEND_INCREASE ||
                type == CatalystType.FACILITY_EXPANSION ||
                type == CatalystType.HEADQUARTERS_EXPANSION ||
                type == CatalystType.PRODUCT_SALE ||
                type == CatalystType.CAPITAL_INVESTMENT ||
                type == CatalystType.NEW_PRODUCT_SERVICE ||
                type == CatalystType.PRODUCT_LAUNCH ||
                type == CatalystType.POSITIVE_BUSINESS_MOMENTUM ||
                type == CatalystType.BUSINESS_UPDATE ||
                type == CatalystType.MAJOR_CONTRACT ||
                type == CatalystType.MAJOR_ORDER ||
                type == CatalystType.CUSTOMER_ORDER ||
                type == CatalystType.SALES_AGREEMENT ||
                type == CatalystType.MATERIAL_SUPPLY_AGREEMENT ||
                type == CatalystType.PARTNERSHIP ||
                type == CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP ||
                type == CatalystType.STRATEGIC_REVIEW ||
                type == CatalystType.INSIDER_BUYING ||
                type == CatalystType.ANALYST_UPGRADE ||
                type == CatalystType.PRICE_TARGET_RAISE;
    }


    private boolean isPrimarySubjectBusinessMomentumEntry(
            NewsOpportunity opportunity,
            RelevanceDecision relevanceDecision,
            com.bot.model.MarketQuality marketQuality
    ) {
        if (opportunity == null ||
                opportunity.catalyst == null ||
                opportunity.catalyst.type == null ||
                opportunity.sentiment == null) {
            return false;
        }

        if (relevanceDecision != RelevanceDecision.PRIMARY_SUBJECT) {
            return false;
        }

        if (!hasUsableQuoteForPaperExecution(marketQuality)) {
            return false;
        }

        if (!isBusinessMomentumCatalyst(opportunity.catalyst.type)) {
            return false;
        }

        return opportunity.catalyst.weight >= 0.45 &&
                opportunity.sentiment.positive >= 0.30 &&
                opportunity.sentiment.netSentiment() >= 0.08 &&
                opportunity.sentiment.negative <= 0.28;
    }

    private boolean isBusinessMomentumCatalyst(
            CatalystType type
    ) {
        return type == CatalystType.EARNINGS_GROWTH ||
                type == CatalystType.DIVIDEND_INCREASE ||
                type == CatalystType.ASSET_SALE ||
                type == CatalystType.SPINOFF ||
                type == CatalystType.STRATEGIC_REVIEW ||
                type == CatalystType.PRODUCT_SALE ||
                type == CatalystType.CAPITAL_INVESTMENT ||
                type == CatalystType.FACILITY_EXPANSION ||
                type == CatalystType.HEADQUARTERS_EXPANSION ||
                type == CatalystType.NEW_PRODUCT_SERVICE ||
                type == CatalystType.PRODUCT_LAUNCH ||
                type == CatalystType.POSITIVE_BUSINESS_MOMENTUM ||
                type == CatalystType.BUSINESS_UPDATE ||
                type == CatalystType.PARTNERSHIP ||
                type == CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP ||
                type == CatalystType.STRATEGIC_REVIEW ||
                type == CatalystType.INSIDER_BUYING;
    }

    private boolean isExchangeComplianceCatalyst(
            CatalystType type
    ) {
        return type == CatalystType.NASDAQ_COMPLIANCE ||
                type == CatalystType.NASDAQ_COMPLIANCE_EXTENSION ||
                type == CatalystType.NYSE_COMPLIANCE ||
                type == CatalystType.EXCHANGE_COMPLIANCE;
    }
    private static double envDouble(
            String name,
            double defaultValue
    ) {
        try {
            String value =
                    System.getenv(name);

            if (value == null || value.isBlank()) {
                return defaultValue;
            }

            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static long envLong(
            String name,
            long defaultValue
    ) {
        try {
            String value =
                    System.getenv(name);

            if (value == null || value.isBlank()) {
                return defaultValue;
            }

            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

}