package com.bot.master;

import com.bot.engine.PositionManager;
import com.bot.intelligence.FeatureJournal;
import com.bot.intelligence.AdaptiveStrategyPerformanceTracker;
import com.bot.intelligence.ExpectedValuePortfolioAllocator;
import com.bot.intelligence.MissedTradeJournal;
import com.bot.intelligence.HeuristicProbabilityModel;
import com.bot.intelligence.MarketFeatureEngine;
import com.bot.intelligence.MarketFeatureSnapshot;
import com.bot.intelligence.MarketIntelligenceStrategy;
import com.bot.intelligence.ProbabilityModel;
import com.bot.intelligence.ProbabilityPrediction;
import com.bot.intelligence.StrategySelectionGovernor;
import com.bot.intelligence.TradeQualityJournal;
import com.bot.intelligence.AdaptiveTradeQualityPositionSizer;
import com.bot.intelligence.DynamicEntryExitDecisionAgent;
import com.bot.intelligence.MarketStateDatabase2;
import com.bot.intelligence.MarketReplayAgent;
import com.bot.intelligence.PolicyVersionManager;
import com.bot.intelligence.CandidateEvidenceGraph;
import com.bot.intelligence.ContinuousExperimentManager;
import com.bot.intelligence.ExecutionCostModel;
import com.bot.intelligence.LiveTradeReadinessGate;
import com.bot.intelligence.PreTradeCalibrationAuditJournal;
import com.bot.intelligence.PreTradeCalibrationModel;
import com.bot.intelligence.UnifiedCandidateScoringEngine;
import com.bot.execution.OrderExecutor;
import com.bot.agents.ExecutionPlan;
import com.bot.agents.ExecutionQualityAgent;
import com.bot.agents.ExecutionTraderAgent;
import com.bot.agents.MultiAgentTradeCommittee;
import com.bot.agents.MultiAgentTradeDecision;
import com.bot.model.EntryContextSnapshot;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.model.TradeDirection;
import com.bot.news.FinBertService;
import com.bot.risk.AdvancedRiskEngine;
import com.bot.risk.MarketHoursService;
import com.bot.sentiment.SentimentScore;
import com.bot.strategy.unified.*;
import com.bot.scalping.VolumeFirstScalpingPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MasterStrategyEngine {

    private final List<TradingStrategy> strategies;
    private final AdvancedRiskEngine riskEngine;
    private final OrderExecutor orderExecutor;
    private final PositionManager positionManager;
    private final MarketDataCache marketData;
    private final FinBertService finBertService;
    private final double minimumMasterConfidence;
    private final int maxCompetingBuysPerCycle;
    private final int minimumBarsBeforeAction;
    private final double watchCandidateConfidence;
    private final StoryExposureManager storyExposureManager;
    private final MarketHoursService marketHoursService = new MarketHoursService();
    private final EntryTimingGate entryTimingGate = new EntryTimingGate();
    private final boolean extendedHoursBuysEnabled = envBoolean("NEWS_BOT_EXTENDED_HOURS_BUY_ENABLED", true);
    private final double dailyLossHaltPercent;
    private final double dailyProfitLockPercent;
    private volatile double sessionStartingEquity = 0.0;
    private volatile boolean dailyLossHaltPrinted = false;
    private volatile boolean dailyProfitLockPrinted = false;
    private final Map<String, NewsEvent> lastDecisionNewsByTicker = new ConcurrentHashMap<>();
    private final Set<String> pendingBuyTickers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> failedBuyCooldownUntilMs = new ConcurrentHashMap<>();
    private final long failedBuyCooldownMs = envLong("MASTER_FAILED_BUY_COOLDOWN_MS", 60_000L);
    private final boolean portfolioRotationEnabled = envBoolean("PORTFOLIO_ROTATION_ENABLED", true);
    private final double rotationMinimumCandidatePriority = envDouble("PORTFOLIO_ROTATION_MIN_CANDIDATE_PRIORITY", 0.035);
    private final double rotationMinimumPriorityEdge = envDouble("PORTFOLIO_ROTATION_MIN_PRIORITY_EDGE", 0.020);
    private final double rotationMinimumConfidenceEdge = envDouble("PORTFOLIO_ROTATION_MIN_CONFIDENCE_EDGE", 0.18);
    private final double rotationEstimatedCostBuffer = envDouble("PORTFOLIO_ROTATION_COST_BUFFER", 1.08);
    private final double rotationProtectProfitPercent = envDouble("PORTFOLIO_ROTATION_PROTECT_PROFIT_PERCENT", 3.0) / 100.0;
    private final boolean rotationCanSellProfitablePositions = envBoolean("PORTFOLIO_ROTATION_ALLOW_PROFITABLE_REPLACEMENT", true);
    private final double rotationMinimumPriorityMultiple = envDouble("PORTFOLIO_ROTATION_MIN_PRIORITY_MULTIPLE", 1.50);
    private final boolean rotationRequireBothPriorityAndConfidence = envBoolean("PORTFOLIO_ROTATION_REQUIRE_PRIORITY_AND_CONFIDENCE", true);
    private final long rotationMinimumHoldMs = envLong("PORTFOLIO_ROTATION_MIN_REPLACED_HOLD_SECONDS", 900L) * 1000L;
    private final double rotationAllowEarlyReplaceLossPercent = envDouble("PORTFOLIO_ROTATION_ALLOW_EARLY_REPLACE_LOSS_PERCENT", -2.0) / 100.0;
    private final MarketFeatureEngine quantFeatureEngine = new MarketFeatureEngine();
    private final ProbabilityModel quantProbabilityModel = new HeuristicProbabilityModel();
    private final FeatureJournal quantFeatureJournal = new FeatureJournal();
    private final MissedTradeJournal missedTradeJournal = new MissedTradeJournal();
    private final AdaptiveStrategyPerformanceTracker strategyPerformanceTracker = new AdaptiveStrategyPerformanceTracker();
    private final ExpectedValuePortfolioAllocator portfolioAllocator = new ExpectedValuePortfolioAllocator();
    private final StrategySelectionGovernor strategySelectionGovernor = StrategySelectionGovernor.getInstance();
    private final TradeQualityJournal tradeQualityJournal = TradeQualityJournal.getInstance();
    private final AdaptiveTradeQualityPositionSizer adaptivePositionSizer = AdaptiveTradeQualityPositionSizer.getInstance();
    private final DynamicEntryExitDecisionAgent dynamicEntryExitDecisionAgent = DynamicEntryExitDecisionAgent.getInstance();
    private final MultiAgentTradeCommittee multiAgentTradeCommittee = new MultiAgentTradeCommittee();
    private final UnifiedCandidateScoringEngine unifiedCandidateScoringEngine = UnifiedCandidateScoringEngine.getInstance();
    private final MarketReplayAgent marketReplayAgent = MarketReplayAgent.getInstance();
    private final PolicyVersionManager policyVersionManager = PolicyVersionManager.getInstance();
    private final ContinuousExperimentManager continuousExperimentManager = ContinuousExperimentManager.getInstance();
    private final ExecutionCostModel executionCostModel = ExecutionCostModel.getInstance();
    private final LiveTradeReadinessGate liveTradeReadinessGate = LiveTradeReadinessGate.getInstance();
    private final PreTradeCalibrationModel preTradeCalibrationModel = PreTradeCalibrationModel.getInstance();
    private final PreTradeCalibrationAuditJournal preTradeCalibrationAuditJournal = PreTradeCalibrationAuditJournal.getInstance();
    private final ExecutionTraderAgent executionTraderAgent = new ExecutionTraderAgent();
    private final ExecutionQualityAgent executionQualityAgent = new ExecutionQualityAgent();
    private final PreCatalystSleeveManager preCatalystSleeveManager = new PreCatalystSleeveManager();
    private final Map<String, MultiAgentTradeDecision> approvedCommitteeDecisionByTicker = new ConcurrentHashMap<>();
    private final Map<String, DynamicEntryExitDecisionAgent.EntryReview> approvedDynamicEntryByTicker = new ConcurrentHashMap<>();
    private final boolean quantDecisionJournalingEnabled = !"false".equalsIgnoreCase(
            System.getenv().getOrDefault("QUANT_INTELLIGENCE_DECISION_JOURNALING_ENABLED", "true")
    );

    public MasterStrategyEngine(
            AdvancedRiskEngine riskEngine,
            OrderExecutor orderExecutor,
            PositionManager positionManager,
            MarketDataCache marketData,
            FinBertService finBertService
    ) {
        this(
                defaultStrategies(),
                riskEngine,
                orderExecutor,
                positionManager,
                marketData,
                finBertService,
                envDouble("MASTER_MIN_CONFIDENCE", 0.58),
                envInt("MASTER_MAX_BUYS_PER_CYCLE", 1)
        );
    }

    public MasterStrategyEngine(
            List<TradingStrategy> strategies,
            AdvancedRiskEngine riskEngine,
            OrderExecutor orderExecutor,
            PositionManager positionManager,
            MarketDataCache marketData,
            FinBertService finBertService,
            double minimumMasterConfidence,
            int maxCompetingBuysPerCycle
    ) {
        this.strategies = strategies == null ? defaultStrategies() : new ArrayList<>(strategies);
        this.riskEngine = riskEngine;
        this.orderExecutor = orderExecutor;
        this.positionManager = positionManager;
        this.marketData = marketData;
        this.finBertService = finBertService;
        this.minimumMasterConfidence = minimumMasterConfidence;
        this.maxCompetingBuysPerCycle = Math.max(1, maxCompetingBuysPerCycle);
        this.minimumBarsBeforeAction = envInt("UNIFIED_MIN_BARS_BEFORE_ACTION", 1);
        this.watchCandidateConfidence = envDouble("UNIFIED_WATCH_CANDIDATE_CONFIDENCE", 0.45);
        this.storyExposureManager = new StoryExposureManager();
        this.dailyLossHaltPercent = Math.abs(envDouble("DAILY_LOSS_HALT_PERCENT", 1.25)) / 100.0;
        this.dailyProfitLockPercent = Math.abs(envDouble("DAILY_PROFIT_LOCK_PERCENT", 1.50)) / 100.0;
        System.out.println("QUANT INTELLIGENCE CONNECTED TO MASTER ENGINE: strategy=MARKET_INTELLIGENCE_AI featureJournal=" +
                System.getenv().getOrDefault("FEATURE_JOURNAL_PATH", "logs/market_features.csv") +
                " outcomeJournal=" +
                System.getenv().getOrDefault("TRADE_OUTCOME_JOURNAL_PATH", "logs/trade_outcomes.csv") +
                " model=HeuristicProbabilityModel decisionJournaling=" + quantDecisionJournalingEnabled);
    }

    public static List<TradingStrategy> defaultStrategies() {
        List<TradingStrategy> list = new ArrayList<>();
        list.add(new MarketIntelligenceStrategy());
        // Volume-first scalper is intentionally first among tactical strategies.
        // The software is a self-improving scalp/momentum engine: liquidity, volume, and violent movement dominate.
        list.add(new TopVolumeRecoveryScalperStrategy());

        // Original core strategies.
        list.add(new PanicReversalStrategy());
        list.add(new MomentumNewsRunnerStrategy());
        list.add(new ShortSqueezeStrategy());
        list.add(new ShortAlphaBreakdownStrategy());
        list.add(new GapFillStrategy());
        list.add(new VwapReclaimStrategy());
        list.add(new FailedBreakdownStrategy());

        // Expanded market-scanning strategy pack. These are deliberately
        // independent TradingStrategy implementations so the unified router,
        // signal journal, AI feature journal, portfolio rotation, and offline
        // evolution layer can see which exact setup is producing edge.
        list.add(new OpeningRangeBreakoutStrategy());
        list.add(new OpeningRangeBreakdownShortStrategy());
        list.add(new HighTightFlagBreakoutStrategy());
        list.add(new PullbackToVwapContinuationStrategy());
        list.add(new InsideBarExpansionStrategy());
        list.add(new RangeExpansionBreakoutStrategy());
        list.add(new VolumeClimaxReversalStrategy());
        list.add(new ParabolicExhaustionShortStrategy());
        list.add(new ParabolicBiDirectionalMomentumAgentStrategy());
        list.add(new RedToGreenStrategy());
        list.add(new GreenToRedShortStrategy());
        list.add(new TrendPullbackDipBuyStrategy());
        list.add(new RelativeStrengthBreakoutStrategy());
        list.add(new RangeBoundMeanReversionStrategy());
        list.add(new LowPriceMomentumIgnitionStrategy());
        list.add(new EarningsContinuationStrategy());
        list.add(new ContractAwardMomentumStrategy());
        list.add(new FdaApprovalMomentumStrategy());
        list.add(new PreCatalystPredictionAgentStrategy());
        list.add(new OfferingFadeShortStrategy());
        list.add(new FailedVwapShortStrategy());
        list.add(new LiquiditySweepReversalStrategy());
        return list;
    }

    public MasterStrategyDecision evaluate(StrategyContext context) {
        if (context == null) {
            return MasterStrategyDecision.hold("UNKNOWN", new ArrayList<>(), "No strategy context supplied.");
        }

        String dailyRiskBlock = dailyRiskBlockReason(context);
        if (dailyRiskBlock != null) {
            List<StrategySignal> riskSignals = new ArrayList<>();
            riskSignals.add(StrategySignal.block(
                    "DAILY_RISK_GOVERNOR",
                    context.getTicker(),
                    dailyRiskBlock
            ));
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            riskSignals,
                            dailyRiskBlock
                    ),
                    "DAILY_RISK_BLOCK"
            );
        }

        if (context.hasNews()) {
            lastDecisionNewsByTicker.put(context.getTicker(), context.getLatestNews());
        }

        if (context.hasNews()) {
            String catalystRejectReason = CatalystQualityGate.rejectReason(context.getLatestNews());
            if (catalystRejectReason != null && isNonNegotiableCatalystBlock(catalystRejectReason)) {
                List<StrategySignal> blockedSignals = new ArrayList<>();
                blockedSignals.add(StrategySignal.block(
                        "CATALYST_QUALITY_GATE",
                        context.getTicker(),
                        "Rejected before strategy competition: " + catalystRejectReason
                ));
                return journalAndReturn(
                        context,
                        MasterStrategyDecision.hold(
                                context.getTicker(),
                                blockedSignals,
                                "Hard catalyst safety gate rejected news: " + catalystRejectReason
                        ),
                        "CATALYST_HARD_BLOCK"
                );
            } else if (catalystRejectReason != null) {
                if (envBoolean("CATALYST_LOW_EDGE_GENERIC_HARD_REJECT", true)
                        && catalystRejectReason.toUpperCase(java.util.Locale.ROOT).contains("LOW_EDGE_GENERIC_FINANCIAL_CONTENT")) {
                    List<StrategySignal> blockedSignals = new ArrayList<>();
                    blockedSignals.add(StrategySignal.block(
                            "CATALYST_QUALITY_GATE",
                            context.getTicker(),
                            "Rejected generic financial/news-roundup content before AI governor: " + catalystRejectReason
                    ));
                    return journalAndReturn(
                            context,
                            MasterStrategyDecision.hold(
                                    context.getTicker(),
                                    blockedSignals,
                                    "Generic financial content rejected before strategy competition: " + catalystRejectReason
                            ),
                            "CATALYST_GENERIC_HARD_BLOCK"
                    );
                }
                System.out.println("CATALYST QUALITY ADVISORY: ticker=" + context.getTicker() +
                        " reason=" + catalystRejectReason + " action=momentum_gate_only");
            }
        }

        int barCount = context.getBars() == null ? 0 : context.getBars().size();
        if (barCount < minimumBarsBeforeAction) {
            List<StrategySignal> warmupSignals = new ArrayList<>();
            warmupSignals.add(StrategySignal.hold(
                    "UNIFIED_WARMUP",
                    context.getTicker(),
                    0.0,
                    "Waiting for enough price bars before strategy evaluation: bars=" + barCount + " required=" + minimumBarsBeforeAction
            ));
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            warmupSignals,
                            "Warming up market data before allowing strategy decisions: bars=" + barCount + " required=" + minimumBarsBeforeAction
                    ),
                    "WARMUP_HOLD"
            );
        }

        List<StrategySignal> signals = collectSignals(context);
        StrategySignal baselineBest = bestActionableSignal(context, signals);
        StrategySignal best = unifiedCandidateScoringEngine.chooseBestCandidate(
                context,
                signals,
                baselineBest,
                signal -> signal != null && passesProfitabilityConfirmation(context, signal),
                signal -> expectancyAdjustedPriority(context, signal)
        );

        if (best == null) {
            best = syntheticStateOpportunitySignal(context, signals);
        }

        if (best == null) {
            StrategySignal watch = bestWatchCandidate(signals);
            if (watch != null && envBoolean("ALLOW_AI_GOVERNOR_TO_REVIEW_WATCH_CANDIDATES", false)) {
                best = StrategySignal.buy(
                        "AI_GOVERNOR_WATCH_PROMOTION",
                        context.getTicker(),
                        TradeDirection.LONG_STOCK,
                        Math.max(watch.getConfidence(), envDouble("AI_GOVERNOR_WATCH_PROMOTION_CONFIDENCE", 0.50)),
                        envDouble("AI_GOVERNOR_WATCH_PROMOTION_EXPECTED_MOVE", 0.035),
                        Math.max(1, watch.getSuggestedQuantity()),
                        "Promoted watch candidate to AI governor review instead of hard-holding: " + watch.getStrategyName() +
                                " reason=" + watch.getReason()
                );
                signals.add(best);
                System.out.println("AI GOVERNOR WATCH PROMOTION: ticker=" + context.getTicker() +
                        " source=" + watch.getStrategyName() + " confidence=" + watch.getConfidence());
            } else if (watch != null) {
                return journalAndReturn(
                        context,
                        MasterStrategyDecision.hold(
                                context.getTicker(),
                                signals,
                                "WATCH candidate only: " + watch.getStrategyName() + " confidence=" + watch.getConfidence() + " reason=" + watch.getReason()
                        ),
                        "WATCH_HOLD"
                );
            } else {
                return journalAndReturn(
                        context,
                        MasterStrategyDecision.hold(context.getTicker(), signals, "No strategy produced an actionable buy."),
                        "NO_ACTIONABLE_BUY"
                );
            }
        }

        String momentumIgnitionBlock = momentumIgnitionBlockReason(context, best);
        if (momentumIgnitionBlock != null) {
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            signals,
                            momentumIgnitionBlock
                    ),
                    "MOMENTUM_IGNITION_BLOCK"
            );
        }

        double effectiveMasterConfidence = Math.min(minimumMasterConfidence, VolumeFirstScalpingPolicy.confidenceFloor(best));
        if (best.getConfidence() < effectiveMasterConfidence) {
            double advisoryFloor = envDouble("AI_GOVERNOR_LOW_CONFIDENCE_REVIEW_FLOOR", 0.35);
            if (best.getConfidence() < advisoryFloor || "true".equalsIgnoreCase(System.getenv().getOrDefault("MASTER_CONFIDENCE_HARD_GATE", "false"))) {
                return journalAndReturn(
                        context,
                        MasterStrategyDecision.hold(
                                context.getTicker(),
                                signals,
                                "Best strategy confidence below volume-first master threshold: " + best.getConfidence() + " required=" + effectiveMasterConfidence + " " + VolumeFirstScalpingPolicy.diagnostics(context)
                        ),
                        "CONFIDENCE_BLOCK"
                );
            }
            System.out.println("MASTER CONFIDENCE ADVISORY: ticker=" + context.getTicker() +
                    " confidence=" + best.getConfidence() + " min=" + effectiveMasterConfidence +
                    " action=allow_to_ai_governor");
        }

        String storyBlockReason = storyExposureManager.blockReason(context.getLatestNews(), best);
        if (storyBlockReason != null) {
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            signals,
                            storyBlockReason
                    ),
                    "STORY_EXPOSURE_BLOCK"
            );
        }

        String normalizedTicker = normalizeTicker(context.getTicker());
        if (!normalizedTicker.isBlank()) {
            Long cooldownUntil = failedBuyCooldownUntilMs.get(normalizedTicker);
            long now = System.currentTimeMillis();
            if (cooldownUntil != null && cooldownUntil > now) {
                return journalAndReturn(
                        context,
                        MasterStrategyDecision.hold(
                                context.getTicker(),
                                signals,
                                "Recent buy submission failed for " + normalizedTicker +
                                        "; cooling down for " + Math.max(0L, cooldownUntil - now) + "ms"
                        ),
                        "FAILED_BUY_COOLDOWN_BLOCK"
                );
            } else if (cooldownUntil != null) {
                failedBuyCooldownUntilMs.remove(normalizedTicker);
            }
        }

        if (!normalizedTicker.isBlank() && pendingBuyTickers.contains(normalizedTicker)) {
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            signals,
                            "Pending buy already in progress for " + normalizedTicker
                    ),
                    "PENDING_BUY_BLOCK"
            );
        }

        if (positionManager != null && positionManager.hasOpenPosition(context.getTicker())) {
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            signals,
                            "Already holding " + normalizedTicker
                    ),
                    "ALREADY_HOLDING_BLOCK"
            );
        }

        String sessionBlockReason = newBuySessionBlockReason();
        if (sessionBlockReason != null) {
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            signals,
                            sessionBlockReason
                    ),
                    "SESSION_BUY_BLOCK"
            );
        }

        if (preCatalystSleeveManager.isPreCatalyst(best)) {
            PreCatalystSleeveManager.SleeveReview sleeveReview = preCatalystSleeveManager.reviewCandidate(
                    context,
                    best,
                    positionManager,
                    marketData
            );
            if (sleeveReview == null || !sleeveReview.isApproved()) {
                return journalAndReturn(
                        context,
                        MasterStrategyDecision.hold(
                                context.getTicker(),
                                signals,
                                sleeveReview == null
                                        ? "Pre-catalyst sleeve failed closed."
                                        : sleeveReview.getReason()
                        ),
                        "PRE_CATALYST_SLEEVE_BLOCK"
                );
            }
            System.out.println("PRE CATALYST SLEEVE APPROVED: " + sleeveReview.getReason());
        }

        RotationAttempt rotationAttempt = attemptPortfolioRotationIfNeeded(context, best, signals);
        if (rotationAttempt.blockReason != null) {
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            signals,
                            rotationAttempt.blockReason
                    ),
                    "PORTFOLIO_ROTATION_HOLD"
            );
        }

        if (riskEngine != null && !riskEngine.allowNewTrade(context.getTicker())) {
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            signals,
                            "Risk engine rejected best signal from " + best.getStrategyName()
                    ),
                    "RISK_ENGINE_BLOCK"
            );
        }

        if (isTopVolumeFastLane(best) && envBoolean("TOP_VOLUME_FAST_LANE_SKIP_AI_COMMITTEE", true)) {
            String fastLaneBlock = topVolumeFastLaneProfitBlockReason(context, best, normalizedTicker);
            if (fastLaneBlock != null) {
                return journalAndReturn(
                        context,
                        MasterStrategyDecision.hold(
                                context.getTicker(),
                                signals,
                                fastLaneBlock
                        ),
                        "TOP_VOLUME_FAST_LANE_PROFIT_BLOCK"
                );
            }

            double fastLaneEntryPrice = marketData == null ? 0.0 : marketData.latestClose(normalizedTicker);
            if (fastLaneEntryPrice <= 0.0) {
                fastLaneEntryPrice = context.getLastPrice();
            }
            double fastLaneEquity = context.getAccountEquity() > 0.0
                    ? context.getAccountEquity()
                    : (sessionStartingEquity > 0.0 ? sessionStartingEquity : 100_000.0);
            DynamicEntryExitDecisionAgent.EntryReview fastLaneEntryReview = dynamicEntryExitDecisionAgent.reviewEntry(
                    context,
                    best,
                    fastLaneEquity,
                    fastLaneEntryPrice,
                    best.getSuggestedQuantity()
            );
            if (fastLaneEntryReview == null || !fastLaneEntryReview.isApproved()) {
                String reason = fastLaneEntryReview == null
                        ? "Dynamic fast-lane entry governor failed closed."
                        : fastLaneEntryReview.getReason();
                return journalAndReturn(
                        context,
                        MasterStrategyDecision.hold(
                                context.getTicker(),
                                signals,
                                "Top-volume fast lane rejected by dynamic profitability governor: " + reason
                        ),
                        "TOP_VOLUME_FAST_LANE_DYNAMIC_BLOCK"
                );
            }
            approvedDynamicEntryByTicker.put(normalizedTicker, fastLaneEntryReview);
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.buy(
                            context.getTicker(),
                            best,
                            signals,
                            "Top-volume recovery scalper fast lane approved after profit-quality and dynamic entry review: " +
                                    fastLaneEntryReview.getReason() + " signal=" + best.getReason()
                    ),
                    "TOP_VOLUME_FAST_LANE_BUY"
            );
        }

        MultiAgentTradeDecision committeeDecision = multiAgentTradeCommittee.review(context, best, signals);
        if (committeeDecision == null || !committeeDecision.isApproved()) {
            String reason = committeeDecision == null
                    ? "Multi-agent committee failed closed before autonomous execution."
                    : committeeDecision.compactSummary();
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            signals,
                            "Multi-agent committee vetoed trade: " + reason
                    ),
                    "MULTI_AGENT_COMMITTEE_BLOCK"
            );
        }
        double dynamicEntryPrice = marketData == null ? 0.0 : marketData.latestClose(normalizedTicker);
        if (dynamicEntryPrice <= 0.0) {
            dynamicEntryPrice = context.getLastPrice();
        }
        double dynamicEquity = context.getAccountEquity() > 0.0
                ? context.getAccountEquity()
                : (sessionStartingEquity > 0.0 ? sessionStartingEquity : 100_000.0);
        int dynamicRequestedQty = committeeDecision.getApprovedQuantity() > 0
                ? committeeDecision.getApprovedQuantity()
                : best.getSuggestedQuantity();
        DynamicEntryExitDecisionAgent.EntryReview dynamicEntryReview = dynamicEntryExitDecisionAgent.reviewEntry(
                context,
                best,
                dynamicEquity,
                dynamicEntryPrice,
                dynamicRequestedQty
        );
        if (dynamicEntryReview == null || !dynamicEntryReview.isApproved()) {
            String reason = dynamicEntryReview == null
                    ? "Dynamic entry/exit decision agent failed closed before execution."
                    : dynamicEntryReview.getReason();
            return journalAndReturn(
                    context,
                    MasterStrategyDecision.hold(
                            context.getTicker(),
                            signals,
                            "Dynamic AI entry governor vetoed trade: " + reason
                    ),
                    "DYNAMIC_AI_ENTRY_BLOCK"
            );
        }

        approvedCommitteeDecisionByTicker.put(normalizedTicker, committeeDecision);
        approvedDynamicEntryByTicker.put(normalizedTicker, dynamicEntryReview);

        return journalAndReturn(
                context,
                MasterStrategyDecision.buy(
                        context.getTicker(),
                        best,
                        signals,
                        "Multi-agent committee + dynamic AI entry governor approved autonomous trade: committee=" +
                                committeeDecision.compactSummary() + " dynamic=" + dynamicEntryReview.getReason()
                ),
                "DYNAMIC_AI_MASTER_BUY"
        );
    }


    private StrategySignal syntheticStateOpportunitySignal(StrategyContext context, List<StrategySignal> signals) {
        if (context == null || context.getTicker() == null || context.getTicker().isBlank()) {
            return null;
        }
        if (!envBoolean("AI_GOVERNOR_ALLOW_SYNTHETIC_STATE_DIRECT_BUYS", false)) {
            return null;
        }
        MarketStateDatabase2.State state = MarketStateDatabase2.getInstance().snapshot(context.getTicker());
        NewsEvent news = context.getLatestNews();
        boolean syntheticNews = CatalystQualityGate.isSyntheticMarketStateOpportunity(news);
        double floor = envDouble("AI_GOVERNOR_STATE_OPPORTUNITY_MIN_SCORE", 0.82);
        if (state == null || state.opportunityScore < floor) {
            return null;
        }
        if (state.volume < envDouble("AI_GOVERNOR_STATE_MIN_VOLUME", 750_000.0)
                || Math.abs(state.returnPct) < envDouble("AI_GOVERNOR_STATE_MIN_ABS_RETURN_PCT", 4.0)
                || state.rangePct < envDouble("AI_GOVERNOR_STATE_MIN_RANGE_PCT", 5.0)
                || state.parabolicScore < envDouble("AI_GOVERNOR_STATE_MIN_PARABOLIC_SCORE", 0.72)) {
            System.out.println("AI GOVERNOR STATE OPPORTUNITY REJECTED: ticker=" + context.getTicker() +
                    " reason=NO_TRUE_MOMENTUM_IGNITION" +
                    " stateScore=" + state.opportunityScore +
                    " returnPct=" + state.returnPct +
                    " rangePct=" + state.rangePct +
                    " volume=" + state.volume +
                    " parabolic=" + state.parabolicScore);
            return null;
        }
        if (!syntheticNews && !envBoolean("AI_GOVERNOR_ALLOW_STATE_WITHOUT_SYNTHETIC_NEWS", false)) {
            return null;
        }
        TradeDirection direction = "SHORT".equalsIgnoreCase(state.direction)
                ? TradeDirection.SHORT_STOCK
                : TradeDirection.LONG_STOCK;
        double confidence = Math.max(envDouble("AI_GOVERNOR_STATE_OPPORTUNITY_CONFIDENCE", 0.58), Math.min(0.88, state.opportunityScore + 0.38));
        double expectedMove = Math.max(envDouble("AI_GOVERNOR_STATE_OPPORTUNITY_EXPECTED_MOVE", 0.025), state.opportunityScore * 0.08);
        StrategySignal signal = StrategySignal.buy(
                "AI_GOVERNOR_STATE_OPPORTUNITY",
                context.getTicker(),
                direction,
                confidence,
                expectedMove,
                1,
                "Market-state opportunity promoted directly to GPT governor. stateScore=" + state.opportunityScore +
                        " direction=" + state.direction +
                        " technical=" + state.technicalScore +
                        " microstructure=" + state.microstructureScore +
                        " orderFlow=" + state.orderFlowScore +
                        " risk=" + state.riskScore
        );
        if (signals != null) {
            signals.add(signal);
        }
        System.out.println("AI GOVERNOR STATE OPPORTUNITY PROMOTION: ticker=" + context.getTicker() +
                " score=" + state.opportunityScore + " direction=" + state.direction +
                " source=" + (syntheticNews ? "STATE_SIGNAL" : "MARKET_STATE_DB2"));
        return signal;
    }

    private boolean isTopVolumeFastLane(StrategySignal signal) {
        if (signal == null) {
            return false;
        }
        String strategy = signal.getStrategyName() == null ? "" : signal.getStrategyName().trim().toUpperCase(java.util.Locale.ROOT);
        return envBoolean("TOP_VOLUME_RECOVERY_SCALPER_ENABLED", true)
                && "TOP_VOLUME_RECOVERY_SCALPER".equals(strategy);
    }

    private String topVolumeFastLaneProfitBlockReason(StrategyContext context, StrategySignal signal, String normalizedTicker) {
        if (!envBoolean("TOP_VOLUME_FAST_LANE_PROFIT_GATE_ENABLED", true)) {
            return null;
        }
        if (context == null || signal == null) {
            return "TOP_VOLUME_FAST_LANE_PROFIT_GATE_BLOCK: missing context or signal";
        }

        VolumeFirstScalpingPolicy.ScalpingTape tape = VolumeFirstScalpingPolicy.tape(context);
        int minBars = envInt("TOP_VOLUME_FAST_LANE_MIN_BARS", 4);
        if (tape.bars < minBars) {
            return "TOP_VOLUME_FAST_LANE_PROFIT_GATE_BLOCK: not enough bars for fast scalp bars=" +
                    tape.bars + " min=" + minBars + " ticker=" + normalizedTicker;
        }

        double minDollarVolume = envDouble("TOP_VOLUME_FAST_LANE_MIN_DOLLAR_VOLUME", 125_000.0);
        long minLatestVolume = envLong("TOP_VOLUME_FAST_LANE_MIN_LATEST_VOLUME", 20_000L);
        boolean liquidityOk = tape.topVolume
                || tape.dollarVolume >= minDollarVolume
                || tape.volume >= minLatestVolume;
        if (!liquidityOk) {
            return "TOP_VOLUME_FAST_LANE_PROFIT_GATE_BLOCK: liquidity too thin " +
                    VolumeFirstScalpingPolicy.diagnostics(context) +
                    " minDollarVolume=" + fmt2(minDollarVolume) +
                    " minLatestVolume=" + minLatestVolume;
        }

        double minViolentScore = envDouble("TOP_VOLUME_FAST_LANE_MIN_VIOLENT_SCORE", 0.38);
        double minAbsVelocityPct = envDouble("TOP_VOLUME_FAST_LANE_MIN_ABS_VELOCITY_PCT", 0.12);
        double minRangePct = envDouble("TOP_VOLUME_FAST_LANE_MIN_RANGE_PCT", 0.35);
        boolean movementOk = tape.violentScore >= minViolentScore
                || tape.absVelocityPct >= minAbsVelocityPct
                || tape.rangePct >= minRangePct;
        if (!movementOk) {
            return "TOP_VOLUME_FAST_LANE_PROFIT_GATE_BLOCK: no real scalp movement " +
                    VolumeFirstScalpingPolicy.diagnostics(context) +
                    " minViolent=" + fmt2(minViolentScore) +
                    " minAbsVelocityPct=" + fmt2(minAbsVelocityPct) +
                    " minRangePct=" + fmt2(minRangePct);
        }

        double minExpectedMoveAfterCosts = envDouble("TOP_VOLUME_FAST_LANE_MIN_EXPECTED_MOVE_AFTER_COST_PCT", 0.20);
        double minConfidenceForSmallEdge = envDouble("TOP_VOLUME_FAST_LANE_SMALL_EDGE_MIN_CONFIDENCE", 0.82);
        if (signal.getExpectedMovePercent() < minExpectedMoveAfterCosts && signal.getConfidence() < minConfidenceForSmallEdge) {
            return "TOP_VOLUME_FAST_LANE_PROFIT_GATE_BLOCK: expected move too small after estimated spread/slippage expectedMove=" +
                    fmt2(signal.getExpectedMovePercent()) +
                    " min=" + fmt2(minExpectedMoveAfterCosts) +
                    " confidence=" + fmt2(signal.getConfidence()) +
                    " requiredConfidenceForSmallEdge=" + fmt2(minConfidenceForSmallEdge);
        }

        double counterTrendVelocity = envDouble("TOP_VOLUME_FAST_LANE_MAX_COUNTER_TREND_FAST_VELOCITY_PCT", 0.30);
        if (signal.getDirection() == TradeDirection.LONG_STOCK && tape.threeBarVelocityPct < -counterTrendVelocity) {
            return "TOP_VOLUME_FAST_LANE_PROFIT_GATE_BLOCK: long fast-lane candidate is still breaking down fastVelocity=" +
                    fmt2(tape.threeBarVelocityPct) + "% maxCounterTrend=" + fmt2(counterTrendVelocity) + "%";
        }
        if (signal.getDirection() == TradeDirection.SHORT_STOCK && tape.threeBarVelocityPct > counterTrendVelocity) {
            return "TOP_VOLUME_FAST_LANE_PROFIT_GATE_BLOCK: short fast-lane candidate is still squeezing upward fastVelocity=" +
                    fmt2(tape.threeBarVelocityPct) + "% maxCounterTrend=" + fmt2(counterTrendVelocity) + "%";
        }

        return null;
    }

    private MasterStrategyDecision journalAndReturn(
            StrategyContext context,
            MasterStrategyDecision decision,
            String actionLabel
    ) {
        recordQuantDecision(context, decision, actionLabel);
        try {
            tradeQualityJournal.record(
                    context == null ? null : context.getLatestNews(),
                    decision,
                    actionLabel
            );
        } catch (Exception e) {
            System.out.println("TRADE QUALITY JOURNAL WARNING: " + e.getMessage());
        }
        try {
            marketReplayAgent.observeDecision(context, decision, actionLabel);
        } catch (Exception e) {
            System.out.println("MARKET REPLAY AGENT WARNING: " + e.getMessage());
        }
        try {
            policyVersionManager.recordDecision(context, decision, actionLabel);
        } catch (Exception e) {
            System.out.println("POLICY VERSION MANAGER WARNING: " + e.getMessage());
        }
        try {
            StrategySignal representative = representativeSignalForEvidence(decision);
            CandidateEvidenceGraph graph = representative == null ? null : CandidateEvidenceGraph.build(context, representative);
            ContinuousExperimentManager.ExperimentAssignment assignment = continuousExperimentManager.assign(
                    context,
                    decision,
                    graph,
                    actionLabel
            );
            if ("true".equalsIgnoreCase(System.getenv().getOrDefault("CONTINUOUS_EXPERIMENT_VERBOSE_LOGS", "false"))) {
                System.out.println("CONTINUOUS EXPERIMENT ASSIGNMENT: group=" + assignment.getGroup() +
                        " evidence=" + assignment.getEvidenceScore() + " reason=" + assignment.getReason());
            }
        } catch (Exception e) {
            System.out.println("CONTINUOUS EXPERIMENT MANAGER WARNING: " + e.getMessage());
        }
        return decision;
    }

    private StrategySignal representativeSignalForEvidence(MasterStrategyDecision decision) {
        if (decision == null) return null;
        if (decision.getWinningSignal() != null) return decision.getWinningSignal();
        if (decision.getAllSignals() == null || decision.getAllSignals().isEmpty()) return null;
        return decision.getAllSignals().stream()
                .filter(signal -> signal != null)
                .max(Comparator.comparingDouble(StrategySignal::getConfidence))
                .orElse(null);
    }

    private void recordQuantDecision(
            StrategyContext context,
            MasterStrategyDecision decision,
            String actionLabel
    ) {
        if (!quantDecisionJournalingEnabled || context == null || quantFeatureJournal == null) {
            return;
        }

        try {
            MarketFeatureSnapshot snapshot = quantFeatureEngine.extract(context);
            ProbabilityPrediction prediction = quantProbabilityModel.predict(snapshot);

            String selectedStrategy = "NONE";
            if (decision != null && decision.getWinningSignal() != null) {
                selectedStrategy = decision.getWinningSignal().getStrategyName();
            } else if (decision != null && decision.getAllSignals() != null && !decision.getAllSignals().isEmpty()) {
                StrategySignal bestSignal = decision.getAllSignals().stream()
                        .filter(signal -> signal != null)
                        .max(Comparator.comparingDouble(StrategySignal::getConfidence))
                        .orElse(null);
                if (bestSignal != null) {
                    selectedStrategy = bestSignal.getStrategyName();
                }
            }

            String finalAction = actionLabel == null || actionLabel.isBlank()
                    ? (decision == null ? "UNKNOWN" : decision.getAction().name())
                    : actionLabel;

            quantFeatureJournal.record(snapshot, prediction, selectedStrategy, finalAction);

            if (decision != null && decision.getAction() != StrategyAction.BUY && decision.getAllSignals() != null) {
                StrategySignal bestMissed = decision.getAllSignals().stream()
                        .filter(signal -> signal != null && signal.getConfidence() >= 0.50)
                        .max(Comparator.comparingDouble(signal -> expectancyAdjustedPriority(context, signal)))
                        .orElse(null);
                if (bestMissed != null) {
                    missedTradeJournal.record(context, bestMissed, prediction, finalAction + ":" + decision.getReason());
                }
            }

            if ("true".equalsIgnoreCase(System.getenv().getOrDefault("QUANT_INTELLIGENCE_VERBOSE_LOGS", "false"))) {
                System.out.println("QUANT FEATURE JOURNAL: ticker=" + context.getTicker() +
                        " action=" + finalAction +
                        " selectedStrategy=" + selectedStrategy +
                        " pTarget=" + String.format("%.3f", prediction.getProbabilityHitProfitTarget()) +
                        " pStop=" + String.format("%.3f", prediction.getProbabilityHitStopLoss()) +
                        " ev=" + String.format("%.3f", prediction.getExpectedValuePercent()));
            }
        } catch (Exception e) {
            System.out.println("QUANT DECISION JOURNAL ERROR: " + e.getMessage());
        }
    }


    private RotationAttempt attemptPortfolioRotationIfNeeded(
            StrategyContext context,
            StrategySignal candidate,
            List<StrategySignal> signals
    ) {
        if (!portfolioRotationEnabled || context == null || candidate == null || positionManager == null) {
            return RotationAttempt.none();
        }

        String candidateTicker = normalizeTicker(candidate.getTicker());
        if (candidateTicker.isBlank() || positionManager.hasOpenPosition(candidateTicker)) {
            return RotationAttempt.none();
        }

        int maxOpenPositions = riskEngine == null
                ? envInt("MAX_OPEN_POSITIONS", 5)
                : riskEngine.getMaxOpenPositions();
        maxOpenPositions = Math.max(1, Math.min(10, maxOpenPositions));

        double candidatePriority = expectancyAdjustedPriority(context, candidate);
        double candidatePrice = marketData == null ? 0.0 : marketData.latestClose(candidateTicker);
        double estimatedCost = candidatePrice > 0.0
                ? candidatePrice * Math.max(1, candidate.getSuggestedQuantity()) * rotationEstimatedCostBuffer
                : 0.0;
        double deployableBuyingPower = riskEngine == null
                ? Double.POSITIVE_INFINITY
                : riskEngine.deployableBuyingPower();

        boolean slotPressure = positionManager.openCount() >= maxOpenPositions;
        boolean buyingPowerPressure = estimatedCost > 0.0 && deployableBuyingPower < estimatedCost;

        if (!slotPressure && !buyingPowerPressure) {
            return RotationAttempt.none();
        }

        if (candidatePriority < rotationMinimumCandidatePriority) {
            return RotationAttempt.block("Portfolio rotation skipped: candidate priority too low. ticker=" +
                    candidateTicker + " priority=" + candidatePriority +
                    " min=" + rotationMinimumCandidatePriority +
                    " slotPressure=" + slotPressure +
                    " buyingPowerPressure=" + buyingPowerPressure);
        }

        PositionScore weakest = weakestReplaceablePosition(candidateTicker);
        if (weakest == null) {
            return RotationAttempt.block("Portfolio rotation needed but no replaceable active position found for " +
                    candidateTicker + " slotPressure=" + slotPressure +
                    " buyingPowerPressure=" + buyingPowerPressure);
        }

        double priorityEdge = candidatePriority - weakest.score;
        double confidenceEdge = candidate.getConfidence() - weakest.entryConfidence;
        double weakestPositiveScore = Math.max(0.001, weakest.score);
        double priorityMultiple = candidatePriority / weakestPositiveScore;
        boolean priorityStrongEnough =
                priorityEdge >= rotationMinimumPriorityEdge &&
                        priorityMultiple >= rotationMinimumPriorityMultiple;
        boolean confidenceStrongEnough =
                confidenceEdge >= rotationMinimumConfidenceEdge &&
                        candidate.getConfidence() >= 0.90;
        boolean emergencyBuyingPowerUpgrade =
                buyingPowerPressure &&
                        candidate.getConfidence() >= 0.97 &&
                        priorityMultiple >= Math.max(1.35, rotationMinimumPriorityMultiple * 0.90) &&
                        priorityEdge > 0.0;

        boolean candidateStrongEnough = rotationRequireBothPriorityAndConfidence
                ? (priorityStrongEnough && confidenceStrongEnough) || emergencyBuyingPowerUpgrade
                : priorityStrongEnough || confidenceStrongEnough || emergencyBuyingPowerUpgrade;

        if (!candidateStrongEnough) {
            return RotationAttempt.block("Portfolio rotation skipped: new signal is not meaningfully stronger. candidate=" +
                    candidateTicker + " candidatePriority=" + candidatePriority +
                    " candidateConfidence=" + candidate.getConfidence() +
                    " weakest=" + weakest.ticker +
                    " weakestScore=" + weakest.score +
                    " weakestPnl=" + String.format("%.2f%%", weakest.pnlPercent * 100.0) +
                    " priorityEdge=" + priorityEdge +
                    " priorityMultiple=" + priorityMultiple +
                    " confidenceEdge=" + confidenceEdge +
                    " requireBoth=" + rotationRequireBothPriorityAndConfidence);
        }

        String reason = "higher_conviction_replacement target=" + candidateTicker +
                " candidateStrategy=" + candidate.getStrategyName() +
                " candidatePriority=" + candidatePriority +
                " candidateConfidence=" + candidate.getConfidence() +
                " replaced=" + weakest.ticker +
                " replacedScore=" + weakest.score +
                " replacedPnl=" + String.format("%.2f%%", weakest.pnlPercent * 100.0) +
                " slotPressure=" + slotPressure +
                " buyingPowerPressure=" + buyingPowerPressure;

        System.out.println("PORTFOLIO ROTATION APPROVED: " + reason);

        boolean liquidated = positionManager.liquidateForPortfolioRotation(weakest.ticker, reason);
        if (!liquidated) {
            return RotationAttempt.block("Portfolio rotation failed: could not liquidate weakest position " +
                    weakest.ticker + " for stronger candidate " + candidateTicker);
        }

        return RotationAttempt.rotated(weakest.ticker);
    }

    private PositionScore weakestReplaceablePosition(String candidateTicker) {
        if (positionManager == null) {
            return null;
        }

        PositionScore weakest = null;
        for (com.bot.model.Position position : positionManager.allPositions()) {
            if (position == null || position.ticker == null || position.quantity <= 0) {
                continue;
            }

            String ticker = normalizeTicker(position.ticker);
            if (ticker.isBlank() || ticker.equals(candidateTicker)) {
                continue;
            }

            double currentPrice = marketData == null ? 0.0 : marketData.latestClose(ticker);
            if (currentPrice <= 0.0) {
                currentPrice = position.entryPrice;
            }

            double pnlPercent = position.entryPrice <= 0.0 || currentPrice <= 0.0
                    ? 0.0
                    : (position.isShortPosition()
                    ? (position.entryPrice - currentPrice) / position.entryPrice
                    : (currentPrice - position.entryPrice) / position.entryPrice);

            long ageMs = position.openedAt <= 0 ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - position.openedAt);
            if (ageMs < rotationMinimumHoldMs && pnlPercent > rotationAllowEarlyReplaceLossPercent) {
                // Logs showed profitable/flat positions being rotated out seconds after entry.
                // Do not churn fresh trades unless they are already failing badly.
                continue;
            }

            if (!rotationCanSellProfitablePositions && pnlPercent > 0.0) {
                continue;
            }

            double score = activePositionConvictionScore(position, currentPrice, pnlPercent);

            // Do not rotate a strong winner unless it is still the weakest and the
            // new candidate clears a very large edge. This keeps the system from
            // churning good positions simply because a fresh setup appears.
            if (pnlPercent >= rotationProtectProfitPercent) {
                score += Math.min(0.05, pnlPercent);
            }

            PositionScore candidate = new PositionScore(ticker, score, pnlPercent,
                    Math.max(0.0, position.entryConfidence));
            if (weakest == null || candidate.score < weakest.score) {
                weakest = candidate;
            }
        }

        return weakest;
    }

    private double activePositionConvictionScore(
            com.bot.model.Position position,
            double currentPrice,
            double pnlPercent
    ) {
        if (position == null) {
            return 0.0;
        }

        double storedPriority = Math.max(0.0, position.entryPriorityScore);
        double storedConfidenceComponent = Math.max(0.0, position.entryConfidence) * 0.015;
        double strategyComponent = strategyHoldWeight(position.strategyName);
        double pnlComponent = pnlPercent * envDouble("PORTFOLIO_ROTATION_ACTIVE_PNL_WEIGHT", 0.75);
        double peakProtection = 0.0;

        if (position.entryPrice > 0.0 && position.peakPrice > position.entryPrice) {
            double peakGain = (position.peakPrice - position.entryPrice) / position.entryPrice;
            peakProtection = Math.min(0.02, Math.max(0.0, peakGain) * 0.25);
        }

        if (position.partialProfitTaken) {
            peakProtection += 0.01;
        }

        return storedPriority + storedConfidenceComponent + strategyComponent + pnlComponent + peakProtection;
    }

    private double strategyHoldWeight(String strategyName) {
        String strategy = strategyName == null ? "" : strategyName.trim().toUpperCase();
        if (strategy.contains("MARKET_INTELLIGENCE_AI")) {
            return envDouble("PORTFOLIO_ROTATION_HOLD_WEIGHT_MARKET_INTELLIGENCE_AI", 0.010);
        }
        if (strategy.contains("PANIC_REVERSAL")) {
            return envDouble("PORTFOLIO_ROTATION_HOLD_WEIGHT_PANIC_REVERSAL", 0.014);
        }
        if (strategy.contains("VWAP_RECLAIM")) {
            return envDouble("PORTFOLIO_ROTATION_HOLD_WEIGHT_VWAP_RECLAIM", 0.013);
        }
        if (strategy.contains("FAILED_BREAKDOWN")) {
            return envDouble("PORTFOLIO_ROTATION_HOLD_WEIGHT_FAILED_BREAKDOWN", 0.012);
        }
        if (strategy.contains("SHORT_SQUEEZE")) {
            return envDouble("PORTFOLIO_ROTATION_HOLD_WEIGHT_SHORT_SQUEEZE", 0.011);
        }
        if (strategy.contains("MOMENTUM_NEWS_RUNNER")) {
            return envDouble("PORTFOLIO_ROTATION_HOLD_WEIGHT_MOMENTUM_NEWS_RUNNER", 0.009);
        }
        if (strategy.contains("BROKER_SYNC") || strategy.contains("UNKNOWN")) {
            return envDouble("PORTFOLIO_ROTATION_HOLD_WEIGHT_UNKNOWN", 0.004);
        }
        return envDouble("PORTFOLIO_ROTATION_HOLD_WEIGHT_DEFAULT", 0.008);
    }



    private boolean isNonNegotiableCatalystBlock(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String normalized = reason.trim().toUpperCase();
        return normalized.contains("DANGEROUS") ||
                normalized.contains("CATASTROPHIC") ||
                normalized.contains("BANKRUPTCY") ||
                normalized.contains("DELISTING") ||
                normalized.contains("OFFERING") ||
                normalized.contains("DILUTION") ||
                normalized.contains("LEGAL_PR") ||
                normalized.contains("LAW") ||
                normalized.contains("LOW_EDGE_GENERIC_FINANCIAL_CONTENT");
    }

    private String newBuySessionBlockReason() {
        if (marketHoursService == null) {
            return null;
        }

        if (!marketHoursService.isExtendedMarketOpenNow()) {
            return "New buys blocked because market is closed. session=" +
                    marketHoursService.currentSessionName();
        }

        if (!marketHoursService.isRegularMarketOpenNow() && !extendedHoursBuysEnabled) {
            return "New buys blocked because extended-hours buys are disabled. session=" +
                    marketHoursService.currentSessionName() +
                    " set NEWS_BOT_EXTENDED_HOURS_BUY_ENABLED=true to allow them.";
        }

        return null;
    }

    private String dailyRiskBlockReason(StrategyContext context) {
        if (context == null || context.getAccountEquity() <= 0) {
            return null;
        }

        if (sessionStartingEquity <= 0) {
            sessionStartingEquity = context.getAccountEquity();
            System.out.println("DAILY RISK GOVERNOR BASELINE: equity=" + sessionStartingEquity);
            return null;
        }

        double drawdown = (sessionStartingEquity - context.getAccountEquity()) / sessionStartingEquity;
        if (drawdown >= dailyLossHaltPercent) {
            if (!dailyLossHaltPrinted) {
                dailyLossHaltPrinted = true;
                System.out.println("DAILY RISK GOVERNOR HALTED NEW BUYS: drawdown=" +
                        String.format("%.2f%%", drawdown * 100.0) +
                        " limit=" + String.format("%.2f%%", dailyLossHaltPercent * 100.0));
            }
            return "Daily loss governor halted new buys: drawdown=" +
                    String.format("%.2f%%", drawdown * 100.0) +
                    " limit=" + String.format("%.2f%%", dailyLossHaltPercent * 100.0);
        }

        double gain = (context.getAccountEquity() - sessionStartingEquity) / sessionStartingEquity;
        if (gain >= dailyProfitLockPercent) {
            if (!dailyProfitLockPrinted) {
                dailyProfitLockPrinted = true;
                System.out.println("DAILY PROFIT LOCK ENABLED: gain=" +
                        String.format("%.2f%%", gain * 100.0) +
                        " target=" + String.format("%.2f%%", dailyProfitLockPercent * 100.0));
            }
            return "Daily profit lock halted new buys after reaching target gain=" +
                    String.format("%.2f%%", gain * 100.0);
        }

        return null;
    }

    private String momentumIgnitionBlockReason(StrategyContext context, StrategySignal signal) {
        if (context == null || signal == null || !signal.isActionableBuy()) {
            return null;
        }

        MomentumIgnitionProfile ignition = MomentumIgnitionProfile.from(context, signal);
        if (isTopVolumeFastLane(signal) && envBoolean("TOP_VOLUME_FAST_LANE_BYPASS_HARD_IGNITION", false)) {
            if (envBoolean("TOP_VOLUME_FAST_LANE_LOG_ACCEPTS", true)) {
                System.out.println("TOP_VOLUME_FAST_LANE_IGNITION_BYPASS: " + ignition.diagnosticReport(context.getTicker(), signal.getStrategyName()));
            }
            return null;
        }
        if (!ignition.passesHardGate()) {
            return "MOMENTUM_IGNITION_GATE_REJECTED: " + ignition.diagnosticReport(context.getTicker(), signal.getStrategyName()) +
                    " required=live volume explosion + volatility expansion + directional acceleration.";
        }
        if (envBoolean("MOMENTUM_IGNITION_LOG_ACCEPTS", true)) {
            System.out.println("MOMENTUM_IGNITION_GATE_ACCEPTED: " + ignition.diagnosticReport(context.getTicker(), signal.getStrategyName()));
        }

        return null;
    }

    private double recentReturnPct(List<com.bot.model.Bar> bars, int barsBack) {
        if (bars == null || bars.size() < 2) {
            return 0.0;
        }
        int index = Math.max(0, bars.size() - 1 - Math.max(1, barsBack));
        double oldClose = bars.get(index).close;
        double latestClose = bars.get(bars.size() - 1).close;
        if (oldClose <= 0.0 || latestClose <= 0.0) {
            return 0.0;
        }
        return ((latestClose - oldClose) / oldClose) * 100.0;
    }

    private double recentRangePct(List<com.bot.model.Bar> bars, int lookback) {
        if (bars == null || bars.isEmpty()) {
            return 0.0;
        }
        int start = Math.max(0, bars.size() - Math.max(1, lookback));
        double high = 0.0;
        double low = Double.MAX_VALUE;
        for (int i = start; i < bars.size(); i++) {
            com.bot.model.Bar bar = bars.get(i);
            if (bar == null) {
                continue;
            }
            if (bar.high > 0.0) {
                high = Math.max(high, bar.high);
            }
            if (bar.low > 0.0) {
                low = Math.min(low, bar.low);
            }
        }
        if (high <= 0.0 || low <= 0.0 || low == Double.MAX_VALUE) {
            return 0.0;
        }
        return ((high - low) / low) * 100.0;
    }

    private long latestVolume(List<com.bot.model.Bar> bars) {
        if (bars == null || bars.isEmpty() || bars.get(bars.size() - 1) == null) {
            return 0L;
        }
        return Math.max(0L, bars.get(bars.size() - 1).volume);
    }

    private String fmt2(double value) {
        return String.format(java.util.Locale.US, "%.2f", Double.isFinite(value) ? value : 0.0);
    }

    private boolean passesProfitabilityConfirmation(StrategyContext context, StrategySignal signal) {
        if (context == null || signal == null || !signal.isActionableBuy()) {
            return false;
        }
        ExecutionCostModel.CostReview costReview = executionCostModel.review(signal, context);
        if (!costReview.approved) {
            return false;
        }

        MomentumIgnitionProfile ignition = MomentumIgnitionProfile.from(context, signal);
        if (VolumeFirstScalpingPolicy.isScalpingStrategy(signal.getStrategyName())) {
            double minScore = envDouble("VOLUME_FIRST_MIN_IGNITION_SCORE", isTopVolumeFastLane(signal) ? 0.18 : 0.26);
            double minDollarVolume = envDouble("VOLUME_FIRST_PROFIT_MIN_DOLLAR_VOLUME", isTopVolumeFastLane(signal) ? 50_000.0 : 75_000.0);
            VolumeFirstScalpingPolicy.ScalpingTape tape = VolumeFirstScalpingPolicy.tape(context);
            return ignition.getScore() >= minScore
                    || tape.dollarVolume >= minDollarVolume
                    || (tape.topVolume && tape.violentScore >= envDouble("VOLUME_FIRST_TOP_VOLUME_MIN_VIOLENT_SCORE", 0.18));
        }
        if (!ignition.passesHardGate()) {
            return false;
        }

        String strategy = signal.getStrategyName();
        double catalystConfirmationScore = CatalystQualityGate.tradeableCatalystScore(context.getLatestNews());

        // News/catalyst can confirm why a move is happening, but cannot bypass tape proof.
        if ("MOMENTUM_NEWS_RUNNER".equals(strategy)
                || "FDA_APPROVAL_MOMENTUM".equals(strategy)
                || "CONTRACT_AWARD_MOMENTUM".equals(strategy)
                || "EARNINGS_CONTINUATION".equals(strategy)) {
            return catalystConfirmationScore >= envDouble("PROFIT_MIN_NEWS_CATALYST_AFTER_IGNITION", 0.10)
                    || ignition.getScore() >= envDouble("PROFIT_NEWS_MIN_IGNITION_WITHOUT_CATALYST", 0.88);
        }

        // Market-only setups must be even more obviously explosive.
        return ignition.getScore() >= envDouble("PROFIT_MARKET_ONLY_MIN_IGNITION_SCORE", 0.70) || (VolumeFirstScalpingPolicy.hasEnoughLiquidity(context) && VolumeFirstScalpingPolicy.hasViolentMovement(context));
    }

    private double expectancyAdjustedPriority(StrategyContext context, StrategySignal signal) {
        if (signal == null) {
            return 0.0;
        }

        String strategy = signal.getStrategyName();
        ExecutionCostModel.CostReview costReview = context == null
                ? executionCostModel.review(signal.getTicker(), strategy, signal.getExpectedMovePercent())
                : executionCostModel.review(signal, context);
        if (!costReview.approved) {
            return 0.0;
        }
        double base = signal.getConfidence() * Math.max(0.0, costReview.expectedNetMoveFraction);

        double weight;
        if ("PANIC_REVERSAL".equals(strategy)) {
            weight = envDouble("EXPECTANCY_WEIGHT_PANIC_REVERSAL", 1.25);
        } else if ("VWAP_RECLAIM".equals(strategy)) {
            weight = envDouble("EXPECTANCY_WEIGHT_VWAP_RECLAIM", 1.18);
        } else if ("FAILED_BREAKDOWN".equals(strategy)) {
            weight = envDouble("EXPECTANCY_WEIGHT_FAILED_BREAKDOWN", 1.15);
        } else if ("SHORT_SQUEEZE".equals(strategy)) {
            weight = envDouble("EXPECTANCY_WEIGHT_SHORT_SQUEEZE", 1.05);
        } else if ("GAP_FILL".equals(strategy)) {
            weight = envDouble("EXPECTANCY_WEIGHT_GAP_FILL", 1.00);
        } else if ("MOMENTUM_NEWS_RUNNER".equals(strategy)) {
            weight = envDouble("EXPECTANCY_WEIGHT_MOMENTUM_NEWS_RUNNER", 0.78);
        } else if ("PARABOLIC_BI_DIRECTIONAL_MOMENTUM_AGENT".equals(strategy)) {
            weight = envDouble("EXPECTANCY_WEIGHT_PARABOLIC_BI_DIRECTIONAL", 1.42);
        } else {
            weight = 1.0;
        }

        double catalystScore = CatalystQualityGate.tradeableCatalystScore(context == null ? null : context.getLatestNews());
        double catalystBoost = 1.0 + Math.min(0.18, catalystScore * 0.18);
        double learnedWeight = strategyPerformanceTracker.strategyWeight(strategy);
        double liveContextBoost = liveContextBoost(context, signal);
        double ignitionMultiplier = context == null ? 0.35 : MomentumIgnitionProfile.from(context, signal).priorityMultiplier();
        double volumeFirstMultiplier = VolumeFirstScalpingPolicy.signalPriorityMultiplier(context, signal);

        return base * weight * catalystBoost * learnedWeight * liveContextBoost * ignitionMultiplier *
                volumeFirstMultiplier * costReview.priorityMultiplier;
    }

    private double liveContextBoost(StrategyContext context, StrategySignal signal) {
        if (context == null || signal == null) {
            return 1.0;
        }
        MomentumIgnitionProfile ignition = MomentumIgnitionProfile.from(context, signal);
        double boost = 0.70 + ignition.getScore() * 0.45;
        if (ignition.getRvol() >= 6.0) {
            boost += 0.12;
        }
        if (ignition.getRangePct() >= 6.0 || ignition.getAtrPct() >= 2.0) {
            boost += 0.10;
        }
        if (ignition.getDirectionScore() >= 0.65) {
            boost += 0.08;
        }
        boost *= VolumeFirstScalpingPolicy.signalPriorityMultiplier(context, signal);
        return Math.max(0.35, Math.min(2.20, boost));
    }



    private StrategySignal bestActionableSignal(StrategyContext context, List<StrategySignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return null;
        }
        return signals.stream()
                .filter(signal -> signal != null && signal.isActionableBuy())
                .filter(signal -> passesProfitabilityConfirmation(context, signal))
                .max(Comparator.comparingDouble(signal -> expectancyAdjustedPriority(context, signal)))
                .orElse(null);
    }

    private StrategySignal bestWatchCandidate(List<StrategySignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return null;
        }

        return signals.stream()
                .filter(signal -> signal != null && signal.getConfidence() >= watchCandidateConfidence)
                .max(Comparator.comparingDouble(StrategySignal::getConfidence))
                .orElse(null);
    }

    public List<StrategySignal> collectSignals(StrategyContext context) {
        List<StrategySignal> signals = new ArrayList<>();
        strategySelectionGovernor.maybeLog();
        for (TradingStrategy strategy : strategies) {
            try {
                if (!strategySelectionGovernor.isStrategyEnabled(strategy.name())) {
                    signals.add(StrategySignal.block(
                            strategy.name(),
                            context == null ? "UNKNOWN" : context.getTicker(),
                            strategySelectionGovernor.disabledReason(strategy.name())
                    ));
                    continue;
                }
                StrategySignal signal = strategy.evaluate(context);
                if (signal != null) {
                    StrategySignal adjusted = applyRegimeMultiplier(signal);
                    adjusted = applyExecutionCostAdjustment(adjusted, context);
                    adjusted = applyPreTradeCalibration(adjusted, context);
                    signals.add(adjusted);
                }
            } catch (Exception e) {
                signals.add(StrategySignal.block(
                        strategy.name(),
                        context == null ? "UNKNOWN" : context.getTicker(),
                        "Strategy threw exception: " + e.getMessage()
                ));
            }
        }
        return signals;
    }

    private StrategySignal applyRegimeMultiplier(StrategySignal signal) {
        if (signal == null || signal.getAction() != StrategyAction.BUY) {
            return signal;
        }
        double multiplier = strategySelectionGovernor.regimeMultiplier(signal.getStrategyName());
        if (!Double.isFinite(multiplier) || Math.abs(multiplier - 1.0) < 0.0001) {
            return signal;
        }
        double confidenceMultiplier = multiplier >= 1.0
                ? Math.min(multiplier, envDouble("REGIME_SIGNAL_MAX_CONFIDENCE_MULTIPLIER", 1.18))
                : Math.max(multiplier, envDouble("REGIME_SIGNAL_MIN_CONFIDENCE_MULTIPLIER", 0.55));
        double expectedMoveMultiplier = multiplier >= 1.0
                ? Math.min(multiplier, envDouble("REGIME_SIGNAL_MAX_EXPECTED_MOVE_MULTIPLIER", 1.25))
                : Math.max(multiplier, envDouble("REGIME_SIGNAL_MIN_EXPECTED_MOVE_MULTIPLIER", 0.50));
        int quantity = signal.getSuggestedQuantity();
        if (multiplier < 0.75 && envBoolean("REGIME_SIGNAL_MULTIPLIER_CAN_SHRINK_SIZE", true)) {
            quantity = Math.max(1, (int)Math.floor(quantity * Math.max(0.10, multiplier)));
        }
        return StrategySignal.buy(
                signal.getStrategyName(),
                signal.getTicker(),
                signal.getDirection(),
                signal.getConfidence() * confidenceMultiplier,
                signal.getExpectedMovePercent() * expectedMoveMultiplier,
                quantity,
                signal.getReason() + " | learnedRegimeMultiplier=" + String.format(java.util.Locale.ROOT, "%.3f", multiplier)
        );
    }

    private StrategySignal applyExecutionCostAdjustment(StrategySignal signal, StrategyContext context) {
        if (signal == null || signal.getAction() != StrategyAction.BUY) {
            return signal;
        }
        ExecutionCostModel.CostReview review = executionCostModel.review(signal, context);
        if (!review.approved) {
            return StrategySignal.block(
                    signal.getStrategyName(),
                    signal.getTicker(),
                    "Blocked by learned execution-cost model: " + review.reason
            );
        }
        if (Math.abs(review.sizingMultiplier - 1.0) < 0.0001
                && Math.abs(review.expectedNetMoveFraction - signal.getExpectedMovePercent()) < 0.0001) {
            return signal;
        }
        int quantity = signal.getSuggestedQuantity();
        if (review.sizingMultiplier < 0.999 && envBoolean("EXECUTION_COST_MODEL_CAN_SHRINK_SIZE", true)) {
            quantity = Math.max(1, (int)Math.floor(quantity * Math.max(0.05, review.sizingMultiplier)));
        }
        double confidenceMultiplier = review.sizingMultiplier >= 1.0
                ? Math.min(review.sizingMultiplier, envDouble("EXECUTION_COST_MAX_CONFIDENCE_MULTIPLIER", 1.05))
                : Math.max(review.sizingMultiplier, envDouble("EXECUTION_COST_MIN_CONFIDENCE_MULTIPLIER", 0.45));
        return StrategySignal.buy(
                signal.getStrategyName(),
                signal.getTicker(),
                signal.getDirection(),
                signal.getConfidence() * confidenceMultiplier,
                review.expectedNetMoveFraction,
                quantity,
                signal.getReason() + " | " + review.reason
        );
    }

    private StrategySignal applyPreTradeCalibration(StrategySignal signal, StrategyContext context) {
        if (signal == null || signal.getAction() != StrategyAction.BUY) {
            return signal;
        }
        PreTradeCalibrationModel.CalibrationReview review = preTradeCalibrationModel.review(signal, context);
        if (!review.approved) {
            preTradeCalibrationAuditJournal.recordBlocked(context, signal, review, "SIGNAL_COLLECTION");
            return StrategySignal.block(
                    signal.getStrategyName(),
                    signal.getTicker(),
                    "Blocked by pre-trade calibration: " + review.reason
            );
        }
        if (Math.abs(review.sizingMultiplier - 1.0) < 0.0001
                && Math.abs(review.confidenceMultiplier - 1.0) < 0.0001
                && Math.abs(review.expectedMoveMultiplier - 1.0) < 0.0001) {
            return signal;
        }
        int quantity = signal.getSuggestedQuantity();
        if (review.sizingMultiplier < 0.999 && envBoolean("PRE_TRADE_CALIBRATION_CAN_SHRINK_SIZE", true)) {
            quantity = Math.max(1, (int)Math.floor(quantity * Math.max(0.05, review.sizingMultiplier)));
        }
        StrategySignal adjusted = StrategySignal.buy(
                signal.getStrategyName(),
                signal.getTicker(),
                signal.getDirection(),
                signal.getConfidence() * review.confidenceMultiplier,
                signal.getExpectedMovePercent() * review.expectedMoveMultiplier,
                quantity,
                signal.getReason() + " | " + review.reason
        );
        preTradeCalibrationAuditJournal.recordAdjusted(context, signal, adjusted, review, "SIGNAL_COLLECTION");
        return adjusted;
    }

    public MasterStrategyDecision evaluateTicker(
            String ticker,
            double accountEquity
    ) {
        return evaluateTicker(ticker, accountEquity, null);
    }

    public MasterStrategyDecision evaluateTicker(
            String ticker,
            double accountEquity,
            NewsEvent recentNews
    ) {
        SentimentScore sentiment = analyzeSentiment(recentNews);
        StrategyContext context = new StrategyContext(
                ticker,
                marketData,
                recentNews,
                sentiment,
                marketData == null ? 0.0 : marketData.latestClose(ticker),
                accountEquity
        );
        return evaluate(context);
    }

    public MasterStrategyDecision evaluateNews(
            NewsEvent news,
            double accountEquity
    ) {
        SentimentScore sentiment = analyzeSentiment(news);
        String ticker = news == null ? "" : news.getTicker();
        StrategyContext context = new StrategyContext(
                ticker,
                marketData,
                news,
                sentiment,
                marketData == null ? 0.0 : marketData.latestClose(ticker),
                accountEquity
        );
        return evaluate(context);
    }

    private SentimentScore analyzeSentiment(NewsEvent news) {
        if (news == null || finBertService == null) {
            return null;
        }

        try {
            return finBertService.analyze(news.fullText());
        } catch (Exception e) {
            System.out.println("MASTER ENGINE SENTIMENT ERROR: " + e.getMessage());
            return null;
        }
    }

    private EntryContextSnapshot entryContextFor(StrategySignal signal, String normalizedTicker) {
        if (signal == null || normalizedTicker == null || normalizedTicker.isBlank()) {
            return EntryContextSnapshot.none();
        }

        try {
            NewsEvent news = lastDecisionNewsByTicker.get(normalizedTicker);
            SentimentScore sentiment = analyzeSentiment(news);
            double lastPrice = marketData == null ? 0.0 : marketData.latestClose(normalizedTicker);
            double accountEquity = sessionStartingEquity > 0.0 ? sessionStartingEquity : 100_000.0;
            StrategyContext context = new StrategyContext(
                    normalizedTicker,
                    marketData,
                    news,
                    sentiment,
                    lastPrice,
                    accountEquity
            );
            MarketFeatureSnapshot snapshot = quantFeatureEngine.extract(context);
            ProbabilityPrediction prediction = quantProbabilityModel.predict(snapshot);
            if (snapshot == null || prediction == null) {
                return EntryContextSnapshot.none();
            }

            String strategy = signal.getStrategyName() == null || signal.getStrategyName().isBlank()
                    ? "UNKNOWN"
                    : signal.getStrategyName().trim().toUpperCase();
            String safeStrategy = strategy.replaceAll("[^A-Z0-9_]+", "_");
            String random = UUID.randomUUID().toString().substring(0, 8);
            String entryContextId = normalizedTicker + "-" + safeStrategy + "-" + System.currentTimeMillis() + "-" + random;

            return new EntryContextSnapshot(
                    entryContextId,
                    prediction.getProbabilityHitProfitTarget(),
                    prediction.getProbabilityHitStopLoss(),
                    prediction.getExpectedValuePercent(),
                    prediction.confidence(),
                    prediction.getReason(),
                    entryMarketRegime(snapshot),
                    Math.max(snapshot.rvol5, snapshot.rvol20),
                    snapshot.return3Bars,
                    snapshot.vwapDistance,
                    snapshot.sentimentNet,
                    snapshot.catalystScore,
                    snapshot.freshnessSeconds
            );
        } catch (Exception e) {
            System.out.println("ENTRY CONTEXT BUILD FAILED: ticker=" + normalizedTicker + " detail=" + e.getMessage());
            return EntryContextSnapshot.none();
        }
    }

    private static String entryMarketRegime(MarketFeatureSnapshot snapshot) {
        if (snapshot == null || snapshot.barCount < 3) {
            return "LOW_DATA";
        }
        double rvol = Math.max(snapshot.rvol5, snapshot.rvol20);
        double absRet = Math.abs(snapshot.return3Bars);
        boolean freshNews =
                snapshot.catalystScore > 0.25 ||
                        Math.abs(snapshot.sentimentNet) > 0.25 ||
                        snapshot.freshnessSeconds < 600.0;
        boolean freshNegativeNews =
                snapshot.sentimentNet <= -0.25 &&
                        snapshot.freshnessSeconds <= envLong("AI_NEGATIVE_NEWS_SHORT_MAX_FRESHNESS_SECONDS", 900L);
        if (freshNegativeNews) {
            return "NEGATIVE_NEWS";
        }
        if (rvol <= 0.0 || (rvol < 0.75 && snapshot.barCount < 20)) {
            return "LOW_VOLUME";
        }
        if (rvol >= 1.8 && absRet > 0.003) {
            return "HIGH_RVOL_MOMENTUM";
        }
        if (freshNews) {
            return "NEWS_CATALYST";
        }
        if (absRet < 0.0015) {
            return "CHOPPY_MEAN_REVERSION";
        }
        return "BALANCED";
    }

    public boolean executeDecision(MasterStrategyDecision decision) {
        if (decision == null || decision.getAction() != StrategyAction.BUY || decision.getWinningSignal() == null) {
            return false;
        }

        StrategySignal signal = decision.getWinningSignal();
        String normalizedTicker = normalizeTicker(signal.getTicker());
        if (normalizedTicker.isBlank()) {
            return false;
        }
        ExecutionCostModel.CostReview executionCostReview =
                executionCostModel.review(normalizedTicker, signal.getStrategyName(), signal.getExpectedMovePercent());
        if (!executionCostReview.approved) {
            System.out.println("MASTER ORDER BLOCKED BY EXECUTION COST MODEL: ticker=" + normalizedTicker +
                    " strategy=" + signal.getStrategyName() + " reason=" + executionCostReview.reason);
            return false;
        }
        PreTradeCalibrationModel.CalibrationReview preTradeReview = preTradeCalibrationModel.review(signal, null);
        if (!preTradeReview.approved) {
            double referencePrice = marketData == null ? 0.0 : marketData.latestClose(normalizedTicker);
            preTradeCalibrationAuditJournal.recordBlockedAtPrice(
                    signal,
                    preTradeReview,
                    "FINAL_EXECUTION",
                    referencePrice,
                    lastDecisionNewsByTicker.get(normalizedTicker)
            );
            System.out.println("MASTER ORDER BLOCKED BY PRE-TRADE CALIBRATION: ticker=" + normalizedTicker +
                    " strategy=" + signal.getStrategyName() + " reason=" + preTradeReview.reason);
            return false;
        }

        boolean topVolumeFastLane = isTopVolumeFastLane(signal) && envBoolean("TOP_VOLUME_FAST_LANE_SKIP_AI_COMMITTEE", true);
        MultiAgentTradeDecision committeeDecision = approvedCommitteeDecisionByTicker.get(normalizedTicker);
        DynamicEntryExitDecisionAgent.EntryReview dynamicEntryReview = approvedDynamicEntryByTicker.get(normalizedTicker);
        ExecutionPlan executionPlan = null;
        if (!topVolumeFastLane) {
            if (committeeDecision == null || !committeeDecision.isApproved()) {
                System.out.println("EXECUTION TRADER BLOCKED: no approved multi-agent committee decision for " + normalizedTicker);
                return false;
            }

            if (dynamicEntryReview == null || !dynamicEntryReview.isApproved()) {
                System.out.println("EXECUTION TRADER BLOCKED: no approved dynamic AI entry review for " + normalizedTicker);
                return false;
            }

            executionPlan = executionTraderAgent.plan(signal, committeeDecision);
            if (executionPlan == null || !executionPlan.isApproved()) {
                System.out.println("EXECUTION TRADER BLOCKED: " + (executionPlan == null ? "no execution plan" : executionPlan.getReason()));
                return false;
            }
        } else {
            if (envBoolean("TOP_VOLUME_FAST_LANE_REQUIRE_DYNAMIC_ENTRY_REVIEW", true)
                    && (dynamicEntryReview == null || !dynamicEntryReview.isApproved())) {
                System.out.println("TOP VOLUME FAST LANE EXECUTION BLOCKED: missing approved dynamic entry review for " + normalizedTicker);
                return false;
            }
            System.out.println("TOP VOLUME FAST LANE EXECUTION: skipping committee/dynamic AI veto for " + normalizedTicker
                    + " strategy=" + signal.getStrategyName() +
                    " dynamicReview=" + (dynamicEntryReview == null ? "none" : dynamicEntryReview.getReason()) +
                    " reason=" + signal.getReason());
        }

        if (orderExecutor == null || positionManager == null) {
            String planReason = executionPlan == null ? "top_volume_fast_lane_or_no_plan" : executionPlan.getReason();
            System.out.println("MASTER SIGNAL ONLY: " + signal + " executionPlan=" + planReason);
            return false;
        }

        if (positionManager.hasOpenPosition(normalizedTicker)) {
            System.out.println("MASTER ORDER BLOCKED: already holding " + normalizedTicker);
            return false;
        }

        if (!topVolumeFastLane || !envBoolean("TOP_VOLUME_FAST_LANE_SKIP_ENTRY_TIMING_GATE", false)) {
            EntryTimingGate.EntryTimingReview timingReview = entryTimingGate.review(signal, marketData);
            if (!timingReview.isApproved()) {
                System.out.println("ENTRY TIMING BLOCKED: ticker=" + normalizedTicker + " " + timingReview.getReason());
                return false;
            }
            System.out.println("ENTRY TIMING APPROVED: ticker=" + normalizedTicker + " " + timingReview.getReason());
        } else {
            System.out.println("TOP VOLUME FAST LANE ENTRY TIMING SELF-APPROVED: ticker=" + normalizedTicker + " reason=" + signal.getReason());
        }

        if (!pendingBuyTickers.add(normalizedTicker)) {
            System.out.println("MASTER ORDER BLOCKED: pending buy already in progress for " + normalizedTicker);
            return false;
        }

        try {
            if (!com.bot.stream.AlpacaSymbolFilter.isEligibleStockSymbol(normalizedTicker)) {
                System.out.println("MASTER ORDER BLOCKED: unsupported Alpaca symbol " + normalizedTicker);
                return false;
            }

            boolean shortEntry = signal.getDirection() == TradeDirection.SHORT_STOCK;
            double entryPriceForSizing = marketData == null ? 0.0 : marketData.latestClose(normalizedTicker);
            double sizingEquity = sessionStartingEquity > 0.0 ? sessionStartingEquity : 100_000.0;
            LiveTradeReadinessGate.ReadinessReview readinessReview = liveTradeReadinessGate.review(
                    signal,
                    null,
                    executionCostReview,
                    preTradeReview,
                    sizingEquity,
                    sessionStartingEquity
            );
            liveTradeReadinessGate.recordReview(readinessReview);
            if (!readinessReview.allowsLiveOrder()) {
                System.out.println("MASTER ORDER HELD BY LIVE READINESS GATE: ticker=" + normalizedTicker +
                        " strategy=" + signal.getStrategyName() +
                        " " + readinessReview.summary());
                return false;
            }

            int baseCommitteeQuantity = executionPlan != null && executionPlan.getQuantity() > 0 ? executionPlan.getQuantity() : signal.getSuggestedQuantity();
            if (readinessReview.sizingMultiplier < 1.0 && envBoolean("LIVE_READINESS_GATE_CAN_SHRINK_SIZE", true)) {
                int beforeReadinessQuantity = baseCommitteeQuantity;
                baseCommitteeQuantity = Math.max(1, (int)Math.floor(baseCommitteeQuantity * Math.max(0.01, readinessReview.sizingMultiplier)));
                if (beforeReadinessQuantity != baseCommitteeQuantity) {
                    System.out.println("LIVE READINESS GATE SHRANK SIZE: ticker=" + normalizedTicker +
                            " strategy=" + signal.getStrategyName() +
                            " before=" + beforeReadinessQuantity +
                            " after=" + baseCommitteeQuantity +
                            " " + readinessReview.summary());
                }
            }
            if (executionCostReview.sizingMultiplier < 1.0 && envBoolean("EXECUTION_COST_MODEL_CAN_SHRINK_SIZE", true)) {
                int beforeExecutionCostQuantity = baseCommitteeQuantity;
                baseCommitteeQuantity = Math.max(1, (int)Math.floor(baseCommitteeQuantity * Math.max(0.05, executionCostReview.sizingMultiplier)));
                if (beforeExecutionCostQuantity != baseCommitteeQuantity) {
                    System.out.println("EXECUTION COST MODEL SHRANK SIZE: ticker=" + normalizedTicker +
                            " strategy=" + signal.getStrategyName() +
                            " before=" + beforeExecutionCostQuantity +
                            " after=" + baseCommitteeQuantity +
                            " reason=" + executionCostReview.reason);
                }
            }
            if (preTradeReview.sizingMultiplier < 1.0 && envBoolean("PRE_TRADE_CALIBRATION_CAN_SHRINK_SIZE", true)) {
                int beforePreTradeCalibrationQuantity = baseCommitteeQuantity;
                baseCommitteeQuantity = Math.max(1, (int)Math.floor(baseCommitteeQuantity * Math.max(0.05, preTradeReview.sizingMultiplier)));
                if (beforePreTradeCalibrationQuantity != baseCommitteeQuantity) {
                    System.out.println("PRE TRADE CALIBRATION SHRANK SIZE: ticker=" + normalizedTicker +
                            " strategy=" + signal.getStrategyName() +
                            " before=" + beforePreTradeCalibrationQuantity +
                            " after=" + baseCommitteeQuantity +
                            " reason=" + preTradeReview.reason);
                }
            }
            if (preCatalystSleeveManager.isPreCatalyst(signal)) {
                int sleeveCappedQuantity = preCatalystSleeveManager.clampQuantity(
                        signal,
                        baseCommitteeQuantity,
                        sizingEquity,
                        entryPriceForSizing,
                        positionManager,
                        marketData
                );
                if (sleeveCappedQuantity <= 0) {
                    System.out.println("PRE CATALYST EXECUTION BLOCKED: sleeve capital cap has no remaining room for " + normalizedTicker);
                    return false;
                }
                if (sleeveCappedQuantity != baseCommitteeQuantity) {
                    System.out.println("PRE CATALYST SLEEVE SIZE CLAMPED: ticker=" + normalizedTicker +
                            " requested=" + baseCommitteeQuantity +
                            " capped=" + sleeveCappedQuantity);
                    baseCommitteeQuantity = sleeveCappedQuantity;
                }
            }
            int allocatorQuantity = portfolioAllocator.approvedQuantity(signal, sizingEquity, entryPriceForSizing);
            if (allocatorQuantity <= 0) {
                allocatorQuantity = baseCommitteeQuantity;
            }
            allocatorQuantity = Math.min(allocatorQuantity, baseCommitteeQuantity);
            int executionQuantity = adaptivePositionSizer.approveQuantity(signal, allocatorQuantity, sizingEquity, entryPriceForSizing);
            if (executionQuantity <= 0) {
                executionQuantity = baseCommitteeQuantity;
            }
            executionQuantity = Math.min(executionQuantity, baseCommitteeQuantity);
            if (dynamicEntryReview != null && dynamicEntryReview.getApprovedQuantity() > 0) {
                int beforeDynamicQuantity = executionQuantity;
                executionQuantity = Math.min(
                        executionQuantity,
                        dynamicEntryReview.getApprovedQuantity()
                );
                if (beforeDynamicQuantity != executionQuantity) {
                    System.out.println("DYNAMIC AI ENTRY SIZER ADJUSTED SIZE: ticker=" + normalizedTicker +
                            " before=" + beforeDynamicQuantity +
                            " dynamicQty=" + executionQuantity +
                            " score=" + dynamicEntryReview.getScore() +
                            " reason=" + dynamicEntryReview.getReason());
                }
            }
            double combinedExecutionMultiplier = committeeDecision != null && dynamicEntryReview != null
                    ? Math.min(committeeDecision.getConfidenceMultiplier(), dynamicEntryReview.getConfidenceMultiplier())
                    : 1.0;
            if (combinedExecutionMultiplier < 1.0) {
                executionQuantity = Math.max(1, (int)Math.floor(executionQuantity * combinedExecutionMultiplier));
            }
            if (allocatorQuantity != signal.getSuggestedQuantity()) {
                System.out.println("EV PORTFOLIO ALLOCATOR ADJUSTED SIZE: ticker=" + normalizedTicker +
                        " strategy=" + signal.getStrategyName() +
                        " requested=" + signal.getSuggestedQuantity() +
                        " approved=" + allocatorQuantity +
                        " reason=" + portfolioAllocator.describe(signal));
            }
            if (executionQuantity != allocatorQuantity) {
                System.out.println("ADAPTIVE TRADE QUALITY SIZER ADJUSTED SIZE: ticker=" + normalizedTicker +
                        " strategy=" + signal.getStrategyName() +
                        " allocatorQty=" + allocatorQuantity +
                        " finalQty=" + executionQuantity +
                        " reason=" + adaptivePositionSizer.describe(signal, allocatorQuantity, executionQuantity));
            }
            long executionStartedAt = System.currentTimeMillis();
            boolean filled = shortEntry
                    ? orderExecutor.shortMarketAndWaitForFill(normalizedTicker, executionQuantity)
                    : orderExecutor.buyMarketAndWaitForFill(normalizedTicker, executionQuantity);
            long executionLatencyMs = Math.max(0L, System.currentTimeMillis() - executionStartedAt);
            tradeQualityJournal.recordExecution(
                    normalizedTicker,
                    signal.getStrategyName(),
                    shortEntry ? "SHORT" : "BUY",
                    signal.getSuggestedQuantity(),
                    executionQuantity,
                    entryPriceForSizing,
                    filled,
                    executionLatencyMs,
                    filled ? "order_filled" : "order_not_filled"
            );
            double observedExecutionPrice = marketData == null ? 0.0 : marketData.latestClose(normalizedTicker);
            executionQualityAgent.record(
                    normalizedTicker,
                    signal.getStrategyName(),
                    shortEntry ? "SHORT" : "BUY",
                    signal.getSuggestedQuantity(),
                    executionQuantity,
                    entryPriceForSizing,
                    observedExecutionPrice,
                    filled,
                    executionLatencyMs,
                    filled ? "order_filled" : "order_not_filled"
            );
            if (!filled) {
                failedBuyCooldownUntilMs.put(normalizedTicker, System.currentTimeMillis() + failedBuyCooldownMs);
                System.out.println("MASTER ORDER NOT FILLED: " + signal +
                        " cooldownMs=" + failedBuyCooldownMs);
                return false;
            }

            double entryPrice = marketData == null ? 0.0 : marketData.latestClose(normalizedTicker);
            if (entryPrice > 0) {
                int trackedQuantity = executionQuantity;
                int signedBrokerQuantity = orderExecutor.getSignedBrokerPositionQuantity(normalizedTicker);

                if (shortEntry && signedBrokerQuantity < 0) {
                    trackedQuantity = Math.abs(signedBrokerQuantity);
                } else if (!shortEntry && signedBrokerQuantity > 0) {
                    trackedQuantity = signedBrokerQuantity;
                }

                if (trackedQuantity <= 0) {
                    trackedQuantity = executionQuantity;
                }

                if (trackedQuantity != signal.getSuggestedQuantity()) {
                    System.out.println("MASTER POSITION TRACKING QTY RESYNC: ticker=" +
                            normalizedTicker +
                            " requestedQty=" +
                            signal.getSuggestedQuantity() +
                            " brokerSignedQty=" +
                            signedBrokerQuantity +
                            " trackedQty=" +
                            trackedQuantity);
                }

                double entryPriority = expectancyAdjustedPriority(null, signal);
                EntryContextSnapshot entryContext = entryContextFor(signal, normalizedTicker);
                if (shortEntry) {
                    positionManager.openShort(
                            normalizedTicker,
                            entryPrice,
                            trackedQuantity,
                            signal.getStrategyName(),
                            signal.getConfidence(),
                            entryPriority,
                            entryContext
                    );
                } else {
                    positionManager.openLong(
                            normalizedTicker,
                            entryPrice,
                            trackedQuantity,
                            signal.getStrategyName(),
                            signal.getConfidence(),
                            entryPriority,
                            entryContext
                    );
                }
            }

            NewsEvent decisionNews = lastDecisionNewsByTicker.get(normalizedTicker);
            storyExposureManager.recordFill(decisionNews, signal);

            System.out.println("MASTER ORDER FILLED: " + signal);
            return true;
        } catch (RuntimeException e) {
            failedBuyCooldownUntilMs.put(normalizedTicker, System.currentTimeMillis() + failedBuyCooldownMs);
            System.out.println("MASTER ORDER FAILED SAFELY: ticker=" + normalizedTicker + " detail=" + e.getMessage() + " cooldownMs=" + failedBuyCooldownMs);
            return false;
        } finally {
            pendingBuyTickers.remove(normalizedTicker);
            approvedCommitteeDecisionByTicker.remove(normalizedTicker);
            approvedDynamicEntryByTicker.remove(normalizedTicker);
        }
    }

    private static final class RotationAttempt {
        private final String rotatedTicker;
        private final String blockReason;

        private RotationAttempt(String rotatedTicker, String blockReason) {
            this.rotatedTicker = rotatedTicker;
            this.blockReason = blockReason;
        }

        private static RotationAttempt none() {
            return new RotationAttempt(null, null);
        }

        private static RotationAttempt rotated(String ticker) {
            return new RotationAttempt(ticker, null);
        }

        private static RotationAttempt block(String reason) {
            return new RotationAttempt(null, reason);
        }
    }

    private static final class PositionScore {
        private final String ticker;
        private final double score;
        private final double pnlPercent;
        private final double entryConfidence;

        private PositionScore(String ticker, double score, double pnlPercent, double entryConfidence) {
            this.ticker = ticker;
            this.score = score;
            this.pnlPercent = pnlPercent;
            this.entryConfidence = entryConfidence;
        }
    }

    private static String normalizeTicker(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase();
    }

    private static boolean envBoolean(String key, boolean fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return "true".equalsIgnoreCase(value.trim())
                    || "1".equals(value.trim())
                    || "yes".equalsIgnoreCase(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }


}
