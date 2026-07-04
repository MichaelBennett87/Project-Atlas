package com.bot.engine;

import com.bot.execution.OrderExecutor;
import com.bot.intelligence.FeatureOutcomeJournal;
import com.bot.intelligence.ExpectedValueExitAdvisor;
import com.bot.intelligence.DynamicEntryExitDecisionAgent;
import com.bot.intelligence.AdaptiveExitPolicyModel;
import com.bot.intelligence.TradeLifecycleOptimizationAgent;
import com.bot.model.EntryContextSnapshot;
import com.bot.model.MarketDataCache;
import com.bot.model.Position;
import com.bot.risk.PositionService;
import com.bot.strategy.MomentumExitService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

public class PositionManager implements PositionService {


    private static final boolean SHARED_STAGED_EXIT_ENABLED =
            !"false".equalsIgnoreCase(
                    System.getenv().getOrDefault(
                            "SHARED_STAGED_EXIT_ENABLED",
                            "true"
                    )
            );

    private static final double PARTIAL_PROFIT_TARGET_PERCENT =
            envDouble(
                    "SHARED_PARTIAL_PROFIT_TARGET_PERCENT",
                    1.25
            ) / 100.0;

    private static final double PARTIAL_EXIT_FRACTION =
            envDouble(
                    "SHARED_PARTIAL_EXIT_FRACTION",
                    0.50
            );

    private static final double TRAILING_GAIN_GIVEBACK_PERCENT =
            envDouble(
                    "SHARED_TRAILING_GAIN_GIVEBACK_PERCENT",
                    0.55
            ) / 100.0;

    private static final boolean LEGACY_MOMENTUM_EXIT_ENABLED =
            "true".equalsIgnoreCase(
                    System.getenv().getOrDefault(
                            "LEGACY_MOMENTUM_EXIT_ENABLED",
                            "false"
                    )
            );

    private static final double SHORT_HARD_STOP_FROM_ENTRY =
            0.05;

    private static final double SHORT_MIN_PROFIT_BEFORE_COVER =
            0.03;

    private static final double SHORT_BOUNCE_FROM_LOW =
            0.025;

    private static final double SHORT_PARTIAL_PROFIT_TARGET_PERCENT =
            envDouble("SHORT_PARTIAL_PROFIT_TARGET_PERCENT", 1.25) / 100.0;

    private static final double SHORT_PARTIAL_COVER_FRACTION =
            envDouble("SHORT_PARTIAL_COVER_FRACTION", 0.50);

    private static final double SHORT_TRAILING_GAIN_GIVEBACK_PERCENT =
            envDouble("SHORT_TRAILING_GAIN_GIVEBACK_PERCENT", 0.60) / 100.0;

    private static final double SHORT_FULL_PROFIT_LOCK_PERCENT =
            envDouble("SHORT_FULL_PROFIT_LOCK_PERCENT", 4.0) / 100.0;

    private static final boolean EMERGENCY_EXIT_ENABLED =
            !"false".equalsIgnoreCase(
                    System.getenv().getOrDefault(
                            "EMERGENCY_EXIT_ENABLED",
                            "true"
                    )
            );

    private static final long EARLY_FAILURE_EXIT_AFTER_MS =
            envLong(
                    "EMERGENCY_EXIT_EARLY_FAILURE_AFTER_SECONDS",
                    300L
            ) * 1000L;

    private static final double EARLY_FAILURE_LOSS_PERCENT =
            envDouble(
                    "EMERGENCY_EXIT_EARLY_FAILURE_LOSS_PERCENT",
                    -2.0
            ) / 100.0;

    private static final double HARD_STOP_LOSS_PERCENT =
            envDouble(
                    "EMERGENCY_EXIT_HARD_STOP_LOSS_PERCENT",
                    -3.0
            ) / 100.0;

    private static final long NO_FOLLOW_THROUGH_AFTER_MS =
            envLong(
                    "EMERGENCY_EXIT_NO_FOLLOW_THROUGH_AFTER_SECONDS",
                    900L
            ) * 1000L;

    private static final double NO_FOLLOW_THROUGH_REQUIRED_GAIN_PERCENT =
            envDouble(
                    "EMERGENCY_EXIT_NO_FOLLOW_THROUGH_REQUIRED_GAIN_PERCENT",
                    0.60
            ) / 100.0;

    private static final double NO_FOLLOW_THROUGH_MIN_LOSS_PERCENT =
            envDouble(
                    "EMERGENCY_EXIT_NO_FOLLOW_THROUGH_MIN_LOSS_PERCENT",
                    -1.0
            ) / 100.0;

    private static final long DEAD_TRADE_AFTER_MS =
            envLong(
                    "EMERGENCY_EXIT_DEAD_TRADE_AFTER_SECONDS",
                    1800L
            ) * 1000L;

    private static final double DEAD_TRADE_REQUIRED_GAIN_PERCENT =
            envDouble(
                    "EMERGENCY_EXIT_DEAD_TRADE_REQUIRED_GAIN_PERCENT",
                    0.5
            ) / 100.0;

    private static final boolean SYNCED_POSITION_EXIT_IF_NOT_PROFITABLE =
            "true".equalsIgnoreCase(
                    System.getenv().getOrDefault(
                            "SYNCED_POSITION_EXIT_IF_NOT_PROFITABLE",
                            "false"
                    )
            );

    private static final double SYNCED_POSITION_MIN_PROFIT_PERCENT =
            envDouble(
                    "SYNCED_POSITION_MIN_PROFIT_PERCENT",
                    0.0
            ) / 100.0;

    private static final long POSITION_SWEEP_INTERVAL_MS =
            envLong(
                    "POSITION_SWEEP_INTERVAL_SECONDS",
                    15L
            ) * 1000L;

    private static final long EXIT_RETRY_AFTER_MS =
            envLong(
                    "EXIT_RETRY_AFTER_SECONDS",
                    30L
            ) * 1000L;

    private static final long SYNCED_POSITION_STARTUP_EXIT_GRACE_MS =
            envLong(
                    "SYNCED_POSITION_STARTUP_EXIT_GRACE_SECONDS",
                    30L
            ) * 1000L;

    private static final boolean MAX_HOLD_EXIT_ENABLED =
            !"false".equalsIgnoreCase(
                    System.getenv().getOrDefault(
                            "MAX_HOLD_EXIT_ENABLED",
                            "true"
                    )
            );

    private static final long MAX_HOLD_EXIT_AFTER_MS =
            envLong(
                    "MAX_HOLD_EXIT_AFTER_SECONDS",
                    1_800L
            ) * 1000L;

    private static final boolean PRE_CATALYST_EXIT_ENABLED =
            !"false".equalsIgnoreCase(
                    System.getenv().getOrDefault(
                            "PRE_CATALYST_EXIT_ENABLED",
                            "true"
                    )
            );

    private static final long PRE_CATALYST_MAX_HOLD_MS =
            envLong(
                    "PRE_CATALYST_MAX_HOLD_HOURS",
                    96L
            ) * 60L * 60L * 1000L;

    private static final double PRE_CATALYST_STOP_LOSS_PERCENT =
            envDouble(
                    "PRE_CATALYST_STOP_LOSS_PERCENT",
                    -4.0
            ) / 100.0;

    private static final double PRE_CATALYST_SELL_SPIKE_GAIN_PERCENT =
            envDouble(
                    "PRE_CATALYST_SELL_SPIKE_GAIN_PERCENT",
                    5.0
            ) / 100.0;

    private static final double PRE_CATALYST_SPIKE_PULLBACK_PERCENT =
            envDouble(
                    "PRE_CATALYST_SPIKE_PULLBACK_PERCENT",
                    1.25
            ) / 100.0;

    private static final long A_PLUS_MAX_HOLD_EXIT_AFTER_MS =
            envLong(
                    "A_PLUS_MAX_HOLD_EXIT_AFTER_SECONDS",
                    7_200L
            ) * 1000L;

    private static final double A_PLUS_MIN_CONFIDENCE =
            envDouble(
                    "A_PLUS_MIN_CONFIDENCE",
                    0.90
            );

    private static final double A_PLUS_MIN_PRIORITY_SCORE =
            envDouble(
                    "A_PLUS_MIN_PRIORITY_SCORE",
                    0.90
            );

    private static final double A_PLUS_MIN_OPEN_GAIN_PERCENT =
            envDouble(
                    "A_PLUS_MIN_OPEN_GAIN_PERCENT",
                    0.25
            ) / 100.0;

    private final MarketDataCache marketData;
    private final OrderExecutor orderExecutor;
    private final MomentumExitService momentumExitService;
    private final Function<String, Double> currentPriceProvider;
    private final Map<String, Position> openPositions = new HashMap<>();
    private final FeatureOutcomeJournal featureOutcomeJournal = new FeatureOutcomeJournal();
    private final ExpectedValueExitAdvisor expectedValueExitAdvisor = new ExpectedValueExitAdvisor();
    private final DynamicEntryExitDecisionAgent dynamicEntryExitDecisionAgent = DynamicEntryExitDecisionAgent.getInstance();
    private final AdaptiveExitPolicyModel adaptiveExitPolicyModel = AdaptiveExitPolicyModel.getInstance();
    private final TradeLifecycleOptimizationAgent lifecycleOptimizationAgent = TradeLifecycleOptimizationAgent.getInstance();
    private final Map<String, Long> exitAttemptTimestamps = new HashMap<>();
    private final List<Consumer<String>> positionClosedListeners = new CopyOnWriteArrayList<>();
    private boolean initialBrokerSyncEvaluated = false;

    public PositionManager(
            MarketDataCache marketData,
            OrderExecutor orderExecutor
    ) {
        this(
                marketData,
                orderExecutor,
                null
        );
    }

    public PositionManager(
            MarketDataCache marketData,
            OrderExecutor orderExecutor,
            Function<String, Double> currentPriceProvider
    ) {
        this.marketData = marketData;
        this.orderExecutor = orderExecutor;
        this.momentumExitService = new MomentumExitService(marketData);
        this.currentPriceProvider = currentPriceProvider;

        startPositionSweepThread();
    }

    public void addPositionClosedListener(Consumer<String> listener) {
        if (listener == null) {
            return;
        }

        positionClosedListeners.add(listener);
    }

    private void notifyPositionClosed(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }

        String normalizedTicker = ticker.trim().toUpperCase();

        System.out.println(
                "POSITION CLOSED EVENT: " +
                        normalizedTicker +
                        " notifyingPriceTrackingListeners=" +
                        positionClosedListeners.size()
        );

        for (Consumer<String> listener : positionClosedListeners) {
            try {
                listener.accept(normalizedTicker);
            } catch (Exception e) {
                System.out.println(
                        "POSITION CLOSED LISTENER ERROR: " +
                                normalizedTicker +
                                " " +
                                e.getMessage()
                );
            }
        }
    }

    public synchronized void syncFromBroker(List<Position> brokerPositions) {
        Map<String, Position> reconciled = new HashMap<>();

        if (brokerPositions != null) {
            for (Position brokerPosition : brokerPositions) {
                if (brokerPosition == null || brokerPosition.ticker == null) {
                    continue;
                }

                String normalizedTicker = brokerPosition.ticker.trim().toUpperCase();
                brokerPosition.ticker = normalizedTicker;

                if (brokerPosition.entryPrice <= 0 || brokerPosition.quantity <= 0) {
                    continue;
                }

                Position existing = openPositions.get(normalizedTicker);
                boolean alreadyTracked = existing != null;

                Position tracked = alreadyTracked ? existing : brokerPosition;

                tracked.ticker = normalizedTicker;
                if (tracked.entryContext == null) {
                    tracked.entryContext = EntryContextSnapshot.none();
                }
                tracked.quantity = brokerPosition.quantity;
                if (tracked.initialQuantity <= 0 || brokerPosition.quantity > tracked.initialQuantity) {
                    tracked.initialQuantity = brokerPosition.quantity;
                }

                if (!alreadyTracked || tracked.entryPrice <= 0) {
                    tracked.entryPrice = brokerPosition.entryPrice;
                }

                if (tracked.peakPrice <= 0) {
                    tracked.peakPrice = tracked.entryPrice;
                }

                if (tracked.troughPrice <= 0) {
                    tracked.troughPrice = tracked.entryPrice;
                }

                if (tracked.openedAt <= 0) {
                    tracked.openedAt = System.currentTimeMillis();
                }

                tracked.shortPosition = brokerPosition.shortPosition;
                // Only positions discovered from broker at startup/resync are marked as broker-synced.
                // Existing positions opened by this run must stay non-synced; otherwise the synced-position
                // emergency policy can liquidate fresh bot trades moments after entry just because they are
                // flat or slightly negative.
                tracked.syncedFromBroker = !alreadyTracked;

                if (tracked.strategyName == null || tracked.strategyName.isBlank() || "BROKER_SYNC".equalsIgnoreCase(tracked.strategyName)) {
                    String brokerStrategy = brokerPosition.strategyName;
                    tracked.strategyName = brokerStrategy == null || brokerStrategy.isBlank()
                            ? (alreadyTracked ? existing.strategyName : "BROKER_SYNC")
                            : brokerStrategy.trim().toUpperCase();
                }

                reconciled.put(normalizedTicker, tracked);

                if (!alreadyTracked) {
                    if (envBoolean("TRADE_OUTCOME_RECORD_BROKER_SYNC_OPEN", false)) {
                        featureOutcomeJournal.recordOpen(tracked);
                    }
                    System.out.println(
                            "SYNCED POSITION TRACKING ENABLED: " +
                                    normalizedTicker +
                                    " qty=" +
                                    tracked.quantity +
                                    " entry=" +
                                    tracked.entryPrice +
                                    " side=" +
                                    (tracked.isShortPosition() ? "SHORT" : "LONG")
                    );
                    lifecycleOptimizationAgent.recordOpen(tracked);
                } else if (envBoolean("POSITION_SYNC_VERBOSE_REFRESH", false)) {
                    System.out.println(
                            "SYNCED POSITION TRACKING REFRESHED: " +
                                    normalizedTicker +
                                    " qty=" +
                                    tracked.quantity +
                                    " entry=" +
                                    tracked.entryPrice +
                                    " strategy=" +
                                    tracked.strategyName
                    );
                }
            }
        }

        openPositions.clear();
        openPositions.putAll(reconciled);

        if (!initialBrokerSyncEvaluated || envBoolean("POSITION_SYNC_VERBOSE_REFRESH", false)) {
            System.out.println(
                    "POSITION SYNC COMPLETE: " +
                            openPositions.size() +
                            " open position(s)."
            );
        }

        if (!initialBrokerSyncEvaluated) {
            initialBrokerSyncEvaluated = true;
            evaluateAllOpenPositions("STARTUP_SYNC");
        } else if (envBoolean("EVALUATE_POSITIONS_AFTER_PERIODIC_BROKER_SYNC", false)) {
            evaluateAllOpenPositions("BROKER_RESYNC");
        }
    }

    public synchronized void open(
            String ticker,
            double entryPrice,
            int quantity
    ) {
        openLong(
                ticker,
                entryPrice,
                quantity
        );
    }

    public synchronized void openLong(
            String ticker,
            double entryPrice,
            int quantity
    ) {
        openLong(
                ticker,
                entryPrice,
                quantity,
                "UNKNOWN"
        );
    }

    public synchronized void openLong(
            String ticker,
            double entryPrice,
            int quantity,
            String strategyName
    ) {
        openLong(
                ticker,
                entryPrice,
                quantity,
                strategyName,
                0.0,
                0.0
        );
    }

    public synchronized void openLong(
            String ticker,
            double entryPrice,
            int quantity,
            String strategyName,
            double entryConfidence,
            double entryPriorityScore
    ) {
        openLong(
                ticker,
                entryPrice,
                quantity,
                strategyName,
                entryConfidence,
                entryPriorityScore,
                EntryContextSnapshot.none()
        );
    }

    public synchronized void openLong(
            String ticker,
            double entryPrice,
            int quantity,
            String strategyName,
            double entryConfidence,
            double entryPriorityScore,
            EntryContextSnapshot entryContext
    ) {
        openInternal(
                ticker,
                entryPrice,
                quantity,
                false,
                strategyName,
                entryConfidence,
                entryPriorityScore,
                entryContext
        );
    }

    public synchronized void openShort(
            String ticker,
            double entryPrice,
            int quantity
    ) {
        openShort(
                ticker,
                entryPrice,
                quantity,
                "SHORT_STRATEGY",
                0.0,
                0.0
        );
    }

    public synchronized void openShort(
            String ticker,
            double entryPrice,
            int quantity,
            String strategyName,
            double entryConfidence,
            double entryPriorityScore
    ) {
        openShort(
                ticker,
                entryPrice,
                quantity,
                strategyName,
                entryConfidence,
                entryPriorityScore,
                EntryContextSnapshot.none()
        );
    }

    public synchronized void openShort(
            String ticker,
            double entryPrice,
            int quantity,
            String strategyName,
            double entryConfidence,
            double entryPriorityScore,
            EntryContextSnapshot entryContext
    ) {
        openInternal(
                ticker,
                entryPrice,
                quantity,
                true,
                strategyName,
                entryConfidence,
                entryPriorityScore,
                entryContext
        );
    }

    private synchronized void openInternal(
            String ticker,
            double entryPrice,
            int quantity,
            boolean shortPosition,
            String strategyName,
            double entryConfidence,
            double entryPriorityScore,
            EntryContextSnapshot entryContext
    ) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }

        if (entryPrice <= 0 || quantity <= 0) {
            return;
        }

        String normalizedTicker =
                ticker.trim().toUpperCase();

        Position existing = openPositions.get(normalizedTicker);
        String normalizedStrategy =
                strategyName == null || strategyName.isBlank()
                        ? "UNKNOWN"
                        : strategyName.trim().toUpperCase();
        EntryContextSnapshot normalizedEntryContext =
                entryContext == null ? EntryContextSnapshot.none() : entryContext;

        if (existing != null && existing.shortPosition == shortPosition) {
            int oldQty = Math.max(0, existing.quantity);
            int newQty = oldQty + quantity;
            double weightedEntry =
                    newQty <= 0
                            ? entryPrice
                            : ((existing.entryPrice * oldQty) + (entryPrice * quantity)) / newQty;

            existing.quantity = newQty;
            existing.initialQuantity = Math.max(existing.initialQuantity, newQty);
            existing.entryPrice = weightedEntry;
            if (existing.peakPrice <= 0 || entryPrice > existing.peakPrice) {
                existing.peakPrice = Math.max(entryPrice, existing.peakPrice);
            }
            if (existing.troughPrice <= 0 || entryPrice < existing.troughPrice) {
                existing.troughPrice = entryPrice;
            }
            if ((existing.strategyName == null || existing.strategyName.isBlank() || "UNKNOWN".equalsIgnoreCase(existing.strategyName))
                    && !"UNKNOWN".equalsIgnoreCase(normalizedStrategy)) {
                existing.strategyName = normalizedStrategy;
            }
            if (entryConfidence > existing.entryConfidence) {
                existing.entryConfidence = entryConfidence;
            }
            if (entryPriorityScore > existing.entryPriorityScore) {
                existing.entryPriorityScore = entryPriorityScore;
            }
            if ((existing.entryContext == null || !existing.entryContext.hasContext())
                    && normalizedEntryContext.hasContext()) {
                existing.entryContext = normalizedEntryContext;
            }
            existing.syncedFromBroker = false;

            System.out.println(
                    "POSITION TRACKING UPDATED EXISTING: " +
                            normalizedTicker +
                            " addedQty=" +
                            quantity +
                            " totalQty=" +
                            existing.quantity +
                            " avgEntry=" +
                            existing.entryPrice +
                            " strategy=" +
                            existing.strategyName
            );
            lifecycleOptimizationAgent.recordOpen(existing);

            return;
        }

        if (existing != null && existing.shortPosition != shortPosition) {
            System.out.println(
                    "POSITION TRACKING SIDE CHANGED: replacing stale tracked side for " +
                            normalizedTicker +
                            " oldSide=" +
                            (existing.isShortPosition() ? "SHORT" : "LONG") +
                            " newSide=" +
                            (shortPosition ? "SHORT" : "LONG") +
                            " oldQty=" +
                            existing.quantity +
                            " newQty=" +
                            quantity
            );
            openPositions.remove(normalizedTicker);
            exitAttemptTimestamps.remove(normalizedTicker);
        }

        Position position =
                new Position(
                        normalizedTicker,
                        entryPrice,
                        entryPrice,
                        quantity,
                        System.currentTimeMillis(),
                        null,
                        shortPosition
                );

        position.syncedFromBroker = false;
        position.initialQuantity = quantity;
        position.partialProfitTaken = false;
        position.strategyName = normalizedStrategy;
        position.entryConfidence = Math.max(0.0, Math.min(1.0, entryConfidence));
        position.entryPriorityScore = Math.max(0.0, entryPriorityScore);
        position.entryContext = normalizedEntryContext;
        position.ticker = normalizedTicker;

        openPositions.put(
                normalizedTicker,
                position
        );

        featureOutcomeJournal.recordOpen(position);
        lifecycleOptimizationAgent.recordOpen(position);

        System.out.println(
                "POSITION TRACKING STARTED: " +
                        normalizedTicker +
                        " side=" +
                        (shortPosition ? "SHORT" : "LONG") +
                        " strategy=" +
                        position.strategyName +
                        " qty=" +
                        quantity +
                        " entry=" +
                        entryPrice
        );
    }

    public synchronized void onPrice(
            String ticker,
            double currentPrice
    ) {
        if (ticker == null || ticker.isBlank()) {
            return;
        }

        Position position =
                openPositions.get(ticker.trim().toUpperCase());

        if (position == null) {
            return;
        }

        if (currentPrice <= 0) {
            return;
        }

        if (position.isShortPosition()) {
            onShortPrice(
                    ticker,
                    position,
                    currentPrice
            );

            return;
        }

        onLongPrice(
                ticker,
                position,
                currentPrice
        );
    }

    private void onLongPrice(
            String ticker,
            Position position,
            double currentPrice
    ) {
        if (currentPrice > position.peakPrice) {
            position.peakPrice = currentPrice;
        }

        if (position.troughPrice <= 0 || currentPrice < position.troughPrice) {
            position.troughPrice = currentPrice;
        }
        lifecycleOptimizationAgent.recordPrice(position, currentPrice);

        String emergencyExitReason =
                emergencyLongExitReason(
                        position,
                        currentPrice
                );

        if (emergencyExitReason != null) {
            requestLongExit(
                    ticker,
                    position,
                    currentPrice,
                    emergencyExitReason
            );

            return;
        }

        if (SHARED_STAGED_EXIT_ENABLED &&
                handleSharedStagedLongExit(
                        ticker,
                        position,
                        currentPrice
                )) {
            return;
        }

        if (!LEGACY_MOMENTUM_EXIT_ENABLED) {
            return;
        }

        boolean shouldExit =
                momentumExitService.shouldExit(
                        ticker,
                        position.entryPrice,
                        position.peakPrice,
                        currentPrice
                );

        if (!shouldExit) {
            return;
        }

        requestLongExit(
                ticker,
                position,
                currentPrice,
                "LEGACY_MOMENTUM_EXIT"
        );
    }


    private boolean handleSharedStagedLongExit(
            String ticker,
            Position position,
            double currentPrice
    ) {
        if (position == null ||
                position.entryPrice <= 0 ||
                currentPrice <= 0 ||
                position.quantity <= 0) {
            return false;
        }

        double currentGainPercent =
                (currentPrice - position.entryPrice) /
                        position.entryPrice;

        double peakGainPercent =
                (position.peakPrice - position.entryPrice) /
                        position.entryPrice;
        AdaptiveExitPolicyModel.ExitPlan exitPlan = adaptiveExitPolicyModel.plan(
                position,
                false,
                PARTIAL_PROFIT_TARGET_PERCENT,
                PARTIAL_EXIT_FRACTION,
                TRAILING_GAIN_GIVEBACK_PERCENT,
                0.0,
                Math.abs(HARD_STOP_LOSS_PERCENT),
                MAX_HOLD_EXIT_AFTER_MS
        );

        if (!position.partialProfitTaken &&
                currentGainPercent >= exitPlan.partialProfitTargetPercent) {
            return requestPartialLongProfitTake(
                    ticker,
                    position,
                    currentPrice,
                    currentGainPercent,
                    exitPlan
            );
        }

        if (position.partialProfitTaken &&
                peakGainPercent >= exitPlan.partialProfitTargetPercent &&
                currentGainPercent <= peakGainPercent - exitPlan.trailingGivebackPercent) {
            requestLongExit(
                    ticker,
                    position,
                    currentPrice,
                    String.format(
                            "SHARED_TRAILING_GAIN_STOP peakGain=%.2f%% currentGain=%.2f%% giveback=%.2f%%",
                            peakGainPercent * 100.0,
                            currentGainPercent * 100.0,
                            exitPlan.trailingGivebackPercent * 100.0
                    )
            );

            return true;
        }

        return false;
    }

    private boolean requestPartialLongProfitTake(
            String ticker,
            Position position,
            double currentPrice,
            double currentGainPercent,
            AdaptiveExitPolicyModel.ExitPlan exitPlan
    ) {
        int qtyToSell =
                Math.max(
                        1,
                        (int) Math.floor(position.quantity * exitPlan.partialExitFraction)
                );

        if (qtyToSell >= position.quantity && position.quantity > 1) {
            qtyToSell = position.quantity - 1;
        }

        if (qtyToSell <= 0) {
            return false;
        }

        System.out.println(
                "SHARED PARTIAL PROFIT EXIT REQUESTED: " +
                        ticker +
                        " strategy=" +
                        position.strategyName +
                        " qtyToSell=" +
                        qtyToSell +
                        " remainingBefore=" +
                        position.quantity +
                        " currentPrice=" +
                        currentPrice +
                        " entryPrice=" +
                        position.entryPrice +
                        " gain=" +
                        String.format("%.2f%%", currentGainPercent * 100.0) +
                        " target=" +
                        String.format("%.2f%%", exitPlan.partialProfitTargetPercent * 100.0) +
                        " adaptiveExitStyle=" +
                        exitPlan.exitStyle
        );

        if (recentExitAttemptInProgress(ticker)) {
            System.out.println(
                    "SHARED PARTIAL PROFIT EXIT SKIPPED: recent exit attempt cooling down. " +
                            ticker
            );

            return true;
        }

        exitAttemptTimestamps.put(
                ticker.trim().toUpperCase(),
                System.currentTimeMillis()
        );

        boolean sold =
                orderExecutor.sellMarketAndWaitForFill(
                        ticker,
                        qtyToSell
                );

        if (!sold) {
            System.out.println(
                    "SHARED PARTIAL PROFIT EXIT NOT FILLED: " +
                            ticker +
                            " qty=" +
                            qtyToSell
            );

            return true;
        }

        featureOutcomeJournal.recordPartialExit(
                position,
                currentPrice,
                qtyToSell,
                "SHARED_PARTIAL_PROFIT_EXIT"
        );
        lifecycleOptimizationAgent.recordPartialExit(
                position,
                currentPrice,
                qtyToSell,
                "SHARED_PARTIAL_PROFIT_EXIT"
        );

        position.quantity -= qtyToSell;
        position.partialProfitTaken = true;

        exitAttemptTimestamps.remove(
                ticker.trim().toUpperCase()
        );

        double realizedProfit =
                (currentPrice - position.entryPrice) *
                        qtyToSell;

        System.out.println(
                "SHARED PARTIAL PROFIT EXIT FILLED: " +
                        ticker +
                        " strategy=" +
                        position.strategyName +
                        " soldQty=" +
                        qtyToSell +
                        " remainingQty=" +
                        position.quantity +
                        " entry=" +
                        position.entryPrice +
                        " exit=" +
                        currentPrice +
                        " realizedProfit=" +
                        realizedProfit +
                        " nextExit=trailingGainStop givebackPercent=" +
                        (exitPlan.trailingGivebackPercent * 100.0) +
                        " adaptiveExitStyle=" +
                        exitPlan.exitStyle
        );

        if (position.quantity <= 0) {
            String normalizedTicker = ticker.trim().toUpperCase();
            openPositions.remove(normalizedTicker);
            exitAttemptTimestamps.remove(normalizedTicker);
            notifyPositionClosed(normalizedTicker);
        }

        return true;
    }

    private String emergencyLongExitReason(
            Position position,
            double currentPrice
    ) {
        if (!EMERGENCY_EXIT_ENABLED ||
                position == null ||
                position.entryPrice <= 0 ||
                currentPrice <= 0) {
            return null;
        }

        long ageMs =
                Math.max(
                        0L,
                        System.currentTimeMillis() - position.openedAt
                );

        double pnlPercent =
                (currentPrice - position.entryPrice) /
                        position.entryPrice;

        double highestGainPercent =
                (position.peakPrice - position.entryPrice) /
                        position.entryPrice;

        if (position.syncedFromBroker && ageMs < SYNCED_POSITION_STARTUP_EXIT_GRACE_MS) {
            return null;
        }

        String preCatalystExitReason = preCatalystExitReason(position, currentPrice, pnlPercent, highestGainPercent, ageMs);
        if (preCatalystExitReason != null) {
            return preCatalystExitReason;
        }

        String maxHoldExitReason = maxHoldExitReason(position, currentPrice, pnlPercent, ageMs);
        if (maxHoldExitReason != null) {
            return maxHoldExitReason;
        }

        String evExitReason = expectedValueExitAdvisor.exitReason(position, currentPrice);
        if (evExitReason != null) {
            return evExitReason;
        }

        String dynamicExitReason = dynamicEntryExitDecisionAgent.dynamicExitReason(position, currentPrice);
        if (dynamicExitReason != null) {
            return dynamicExitReason;
        }

        // Broker-synced startup positions are not allowed to sit indefinitely.
        // After the short startup grace period, any synced long that is flat or
        // worse is exited unless the environment explicitly disables this guard.
        if (position.syncedFromBroker &&
                SYNCED_POSITION_EXIT_IF_NOT_PROFITABLE &&
                pnlPercent <= SYNCED_POSITION_MIN_PROFIT_PERCENT) {
            return "SYNCED_POSITION_NOT_PROFITABLE";
        }

        long hardStopGraceMs =
                envLong("EMERGENCY_EXIT_HARD_STOP_MINIMUM_SECONDS", 300L) * 1000L;
        double catastrophicLossPercent =
                envDouble("EMERGENCY_EXIT_CATASTROPHIC_LOSS_PERCENT", -10.0) / 100.0;
        AdaptiveExitPolicyModel.ExitPlan exitPlan = adaptiveExitPolicyModel.plan(
                position,
                false,
                PARTIAL_PROFIT_TARGET_PERCENT,
                PARTIAL_EXIT_FRACTION,
                TRAILING_GAIN_GIVEBACK_PERCENT,
                0.0,
                Math.abs(HARD_STOP_LOSS_PERCENT),
                MAX_HOLD_EXIT_AFTER_MS
        );
        double adaptiveHardStopLossPercent = -Math.min(Math.abs(HARD_STOP_LOSS_PERCENT), exitPlan.hardStopLossPercent);

        if (pnlPercent <= adaptiveHardStopLossPercent &&
                (ageMs >= hardStopGraceMs || pnlPercent <= catastrophicLossPercent)) {
            return "EMERGENCY_HARD_STOP adaptiveExitStyle=" + exitPlan.exitStyle;
        }

        long minimumEarlyFailureAgeMs =
                envLong("EMERGENCY_EXIT_MINIMUM_EARLY_FAILURE_SECONDS", 900L) * 1000L;
        double minimumEarlyFailureLossPercent =
                envDouble("EMERGENCY_EXIT_MINIMUM_EARLY_FAILURE_LOSS_PERCENT", -6.0) / 100.0;

        if (ageMs >= Math.max(EARLY_FAILURE_EXIT_AFTER_MS, minimumEarlyFailureAgeMs) &&
                pnlPercent <= Math.min(EARLY_FAILURE_LOSS_PERCENT, minimumEarlyFailureLossPercent)) {
            return "EMERGENCY_EARLY_FAILURE";
        }

        if (ageMs >= NO_FOLLOW_THROUGH_AFTER_MS &&
                pnlPercent <= NO_FOLLOW_THROUGH_MIN_LOSS_PERCENT &&
                highestGainPercent < NO_FOLLOW_THROUGH_REQUIRED_GAIN_PERCENT) {
            return "EMERGENCY_NO_FOLLOW_THROUGH";
        }

        if (ageMs >= DEAD_TRADE_AFTER_MS &&
                highestGainPercent < DEAD_TRADE_REQUIRED_GAIN_PERCENT) {
            return "EMERGENCY_DEAD_TRADE";
        }

        return null;
    }

    private String preCatalystExitReason(
            Position position,
            double currentPrice,
            double pnlPercent,
            double highestGainPercent,
            long ageMs
    ) {
        if (!PRE_CATALYST_EXIT_ENABLED ||
                position == null ||
                position.strategyName == null ||
                !"PRE_CATALYST_PREDICTION_AGENT".equalsIgnoreCase(position.strategyName) ||
                position.entryPrice <= 0 ||
                currentPrice <= 0) {
            return null;
        }

        if (pnlPercent <= PRE_CATALYST_STOP_LOSS_PERCENT) {
            return "PRE_CATALYST_STOP_LOSS pnl=" +
                    String.format("%.2f%%", pnlPercent * 100.0);
        }

        if (highestGainPercent >= PRE_CATALYST_SELL_SPIKE_GAIN_PERCENT) {
            double givebackFromPeak = position.peakPrice <= 0
                    ? 0.0
                    : Math.max(0.0, (position.peakPrice - currentPrice) / position.peakPrice);
            if (givebackFromPeak >= PRE_CATALYST_SPIKE_PULLBACK_PERCENT) {
                return "PRE_CATALYST_NEWS_SPIKE_PULLBACK_EXIT peakGain=" +
                        String.format("%.2f%%", highestGainPercent * 100.0) +
                        " giveback=" +
                        String.format("%.2f%%", givebackFromPeak * 100.0);
            }
        }

        if (PRE_CATALYST_MAX_HOLD_MS > 0 && ageMs >= PRE_CATALYST_MAX_HOLD_MS) {
            return "PRE_CATALYST_TIME_STOP hours=" +
                    String.format("%.1f", ageMs / 3_600_000.0) +
                    " pnl=" +
                    String.format("%.2f%%", pnlPercent * 100.0);
        }

        return null;
    }

    private String maxHoldExitReason(
            Position position,
            double currentPrice,
            double pnlPercent,
            long ageMs
    ) {
        if (!MAX_HOLD_EXIT_ENABLED ||
                position == null ||
                position.entryPrice <= 0 ||
                currentPrice <= 0) {
            return null;
        }

        boolean aPlus = isAPlusPosition(position, pnlPercent);
        AdaptiveExitPolicyModel.ExitPlan exitPlan = adaptiveExitPolicyModel.plan(
                position,
                position != null && position.isShortPosition(),
                position != null && position.isShortPosition() ? SHORT_PARTIAL_PROFIT_TARGET_PERCENT : PARTIAL_PROFIT_TARGET_PERCENT,
                position != null && position.isShortPosition() ? SHORT_PARTIAL_COVER_FRACTION : PARTIAL_EXIT_FRACTION,
                position != null && position.isShortPosition() ? SHORT_TRAILING_GAIN_GIVEBACK_PERCENT : TRAILING_GAIN_GIVEBACK_PERCENT,
                position != null && position.isShortPosition() ? SHORT_FULL_PROFIT_LOCK_PERCENT : 0.0,
                position != null && position.isShortPosition() ? SHORT_HARD_STOP_FROM_ENTRY : Math.abs(HARD_STOP_LOSS_PERCENT),
                MAX_HOLD_EXIT_AFTER_MS
        );

        if (aPlus) {
            if (A_PLUS_MAX_HOLD_EXIT_AFTER_MS > 0 &&
                    ageMs >= A_PLUS_MAX_HOLD_EXIT_AFTER_MS) {
                return "A_PLUS_MAX_HOLD_EXIT ageMinutes=" +
                        String.format("%.1f", ageMs / 60_000.0) +
                        " pnl=" +
                        String.format("%.2f%%", pnlPercent * 100.0);
            }

            return null;
        }

        long adaptiveMaxHoldMs = exitPlan.maxHoldMs > 0L
                ? Math.min(MAX_HOLD_EXIT_AFTER_MS, exitPlan.maxHoldMs)
                : MAX_HOLD_EXIT_AFTER_MS;
        if (ageMs >= adaptiveMaxHoldMs) {
            return "MAX_HOLD_EXIT ageMinutes=" +
                    String.format("%.1f", ageMs / 60_000.0) +
                    " pnl=" +
                    String.format("%.2f%%", pnlPercent * 100.0) +
                    " confidence=" +
                    String.format("%.3f", position.entryConfidence) +
                    " priority=" +
                    String.format("%.3f", position.entryPriorityScore) +
                    " adaptiveExitStyle=" +
                    exitPlan.exitStyle;
        }

        return null;
    }

    private boolean isAPlusPosition(
            Position position,
            double pnlPercent
    ) {
        if (position == null) {
            return false;
        }

        boolean highConfidence =
                position.entryConfidence >= A_PLUS_MIN_CONFIDENCE ||
                        position.entryPriorityScore >= A_PLUS_MIN_PRIORITY_SCORE;

        boolean recognizedEliteStrategy =
                position.strategyName != null &&
                        (position.strategyName.contains("SHORT_ALPHA") ||
                                position.strategyName.contains("MARKET_INTELLIGENCE_AI") ||
                                position.strategyName.contains("SQUEEZE"));

        return highConfidence &&
                recognizedEliteStrategy &&
                pnlPercent >= A_PLUS_MIN_OPEN_GAIN_PERCENT;
    }

    private void requestLongExit(
            String ticker,
            Position position,
            double currentPrice,
            String reason
    ) {
        if (position != null && position.isShortPosition()) {
            requestShortCover(
                    ticker,
                    position,
                    currentPrice,
                    "SHORT_POSITION_ROUTED_FROM_LONG_EXIT:" + reason
            );
            return;
        }

        System.out.println(
                "LONG EXIT ORDER REQUESTED: " +
                        ticker +
                        " qty=" +
                        position.quantity +
                        " currentPrice=" +
                        currentPrice +
                        " entryPrice=" +
                        position.entryPrice +
                        " peakPrice=" +
                        position.peakPrice +
                        " reason=" +
                        reason
        );

        if (recentExitAttemptInProgress(ticker)) {
            System.out.println(
                    "LONG EXIT ORDER SKIPPED: recent exit attempt still cooling down. " +
                            ticker +
                            " reason=" +
                            reason
            );

            return;
        }

        exitAttemptTimestamps.put(
                ticker.trim().toUpperCase(),
                System.currentTimeMillis()
        );

        boolean sold =
                orderExecutor.sellMarketAndWaitForFill(
                        ticker,
                        position.quantity
                );

        if (sold) {
            featureOutcomeJournal.recordClose(
                    position,
                    currentPrice,
                    position.quantity,
                    reason
            );
            lifecycleOptimizationAgent.recordClose(
                    position,
                    currentPrice,
                    position.quantity,
                    reason
            );
            dynamicEntryExitDecisionAgent.recordClosed(position, currentPrice, reason);

            String normalizedTicker = ticker.trim().toUpperCase();

            openPositions.remove(normalizedTicker);
            exitAttemptTimestamps.remove(normalizedTicker);
            notifyPositionClosed(normalizedTicker);

            double profit =
                    (currentPrice - position.entryPrice) *
                            position.quantity;

            System.out.println(
                    "LONG POSITION CLOSED: " +
                            ticker +
                            " qty=" +
                            position.quantity +
                            " exit=" +
                            currentPrice +
                            " entry=" +
                            position.entryPrice +
                            " profit=" +
                            profit +
                            " reason=" +
                            reason
            );
        } else {
            System.out.println(
                    "LONG EXIT ORDER NOT FILLED: " +
                            ticker +
                            " reason=" +
                            reason
            );
        }
    }

    private void onShortPrice(
            String ticker,
            Position position,
            double currentPrice
    ) {
        if (position.troughPrice <= 0 || currentPrice < position.troughPrice) {
            position.troughPrice = currentPrice;
        }

        if (position.peakPrice <= 0 || currentPrice > position.peakPrice) {
            position.peakPrice = currentPrice;
        }
        lifecycleOptimizationAgent.recordPrice(position, currentPrice);

        long ageMs =
                Math.max(
                        0L,
                        System.currentTimeMillis() - position.openedAt
                );

        if (position.syncedFromBroker && ageMs < SYNCED_POSITION_STARTUP_EXIT_GRACE_MS) {
            return;
        }

        double shortPnlPercent =
                (position.entryPrice - currentPrice) /
                        position.entryPrice;

        String maxHoldExitReason = maxHoldExitReason(position, currentPrice, shortPnlPercent, ageMs);
        if (maxHoldExitReason != null) {
            requestShortCover(
                    ticker,
                    position,
                    currentPrice,
                    maxHoldExitReason
            );
            return;
        }

        // Apply the same startup-resynced emergency policy to shorts that longs use.
        // This prevents broker-synced short positions from sitting indefinitely
        // when they are flat/losing after startup recovery. Profitable shorts are
        // still allowed to follow the normal staged cover/trailing logic.
        if (position.syncedFromBroker &&
                SYNCED_POSITION_EXIT_IF_NOT_PROFITABLE &&
                shortPnlPercent <= SYNCED_POSITION_MIN_PROFIT_PERCENT) {
            requestShortCover(
                    ticker,
                    position,
                    currentPrice,
                    "SYNCED_SHORT_NOT_PROFITABLE"
            );
            return;
        }

        String evExitReason = expectedValueExitAdvisor.exitReason(position, currentPrice);
        if (evExitReason != null) {
            requestShortCover(
                    ticker,
                    position,
                    currentPrice,
                    evExitReason
            );
            return;
        }

        String dynamicExitReason = dynamicEntryExitDecisionAgent.dynamicExitReason(position, currentPrice);
        if (dynamicExitReason != null) {
            requestShortCover(
                    ticker,
                    position,
                    currentPrice,
                    dynamicExitReason
            );
            return;
        }

        if (handleSharedStagedShortExit(ticker, position, currentPrice)) {
            return;
        }

        AdaptiveExitPolicyModel.ExitPlan exitPlan = adaptiveExitPolicyModel.plan(
                position,
                true,
                SHORT_PARTIAL_PROFIT_TARGET_PERCENT,
                SHORT_PARTIAL_COVER_FRACTION,
                SHORT_TRAILING_GAIN_GIVEBACK_PERCENT,
                SHORT_FULL_PROFIT_LOCK_PERCENT,
                SHORT_HARD_STOP_FROM_ENTRY,
                MAX_HOLD_EXIT_AFTER_MS
        );
        double adaptiveShortHardStop = Math.min(SHORT_HARD_STOP_FROM_ENTRY, exitPlan.hardStopLossPercent);
        double hardStopPrice =
                position.entryPrice *
                        (1.0 + adaptiveShortHardStop);

        boolean hardStopHit =
                currentPrice >= hardStopPrice;

        boolean profitWasAchieved =
                position.troughPrice <=
                        position.entryPrice *
                                (1.0 - SHORT_MIN_PROFIT_BEFORE_COVER);

        double bounceFromLow =
                position.troughPrice <= 0
                        ? 0.0
                        : (currentPrice - position.troughPrice) /
                          position.troughPrice;

        boolean downsideMomentumFaded =
                profitWasAchieved &&
                        bounceFromLow >= SHORT_BOUNCE_FROM_LOW;

        if (!hardStopHit && !downsideMomentumFaded) {
            return;
        }

        requestShortCover(
                ticker,
                position,
                currentPrice,
                hardStopHit ? "SHORT_EMERGENCY_HARD_STOP" : "SHORT_MOMENTUM_FADE"
        );
    }

    private boolean handleSharedStagedShortExit(
            String ticker,
            Position position,
            double currentPrice
    ) {
        if (position == null ||
                position.entryPrice <= 0 ||
                currentPrice <= 0 ||
                position.quantity <= 0) {
            return false;
        }

        double currentGainPercent =
                (position.entryPrice - currentPrice) /
                        position.entryPrice;

        double bestGainPercent =
                position.troughPrice <= 0
                        ? currentGainPercent
                        : (position.entryPrice - position.troughPrice) /
                        position.entryPrice;
        AdaptiveExitPolicyModel.ExitPlan exitPlan = adaptiveExitPolicyModel.plan(
                position,
                true,
                SHORT_PARTIAL_PROFIT_TARGET_PERCENT,
                SHORT_PARTIAL_COVER_FRACTION,
                SHORT_TRAILING_GAIN_GIVEBACK_PERCENT,
                SHORT_FULL_PROFIT_LOCK_PERCENT,
                SHORT_HARD_STOP_FROM_ENTRY,
                MAX_HOLD_EXIT_AFTER_MS
        );

        if (!position.partialProfitTaken &&
                currentGainPercent >= exitPlan.partialProfitTargetPercent) {
            return requestPartialShortProfitTake(
                    ticker,
                    position,
                    currentPrice,
                    currentGainPercent,
                    exitPlan
            );
        }

        if (bestGainPercent >= exitPlan.fullProfitLockPercent &&
                currentGainPercent <= bestGainPercent - (exitPlan.trailingGivebackPercent * 0.50)) {
            requestShortCover(
                    ticker,
                    position,
                    currentPrice,
                    String.format(
                            "SHORT_FULL_PROFIT_LOCK bestGain=%.2f%% currentGain=%.2f%%",
                            bestGainPercent * 100.0,
                            currentGainPercent * 100.0
                    )
            );
            return true;
        }

        if (position.partialProfitTaken &&
                bestGainPercent >= exitPlan.partialProfitTargetPercent &&
                currentGainPercent <= bestGainPercent - exitPlan.trailingGivebackPercent) {
            requestShortCover(
                    ticker,
                    position,
                    currentPrice,
                    String.format(
                            "SHORT_TRAILING_GAIN_STOP bestGain=%.2f%% currentGain=%.2f%% giveback=%.2f%%",
                            bestGainPercent * 100.0,
                            currentGainPercent * 100.0,
                            exitPlan.trailingGivebackPercent * 100.0
                    )
            );
            return true;
        }

        return false;
    }

    private boolean requestPartialShortProfitTake(
            String ticker,
            Position position,
            double currentPrice,
            double currentGainPercent,
            AdaptiveExitPolicyModel.ExitPlan exitPlan
    ) {
        int qtyToCover =
                Math.max(
                        1,
                        (int) Math.floor(position.quantity * exitPlan.partialExitFraction)
                );

        if (qtyToCover >= position.quantity && position.quantity > 1) {
            qtyToCover = position.quantity - 1;
        }

        if (qtyToCover <= 0) {
            return false;
        }

        System.out.println(
                "SHORT PARTIAL PROFIT COVER REQUESTED: " +
                        ticker +
                        " qty=" +
                        qtyToCover +
                        " remainingBefore=" +
                        position.quantity +
                        " currentGain=" +
                        (currentGainPercent * 100.0) +
                        "%" +
                        " adaptiveExitStyle=" +
                        exitPlan.exitStyle
        );

        if (recentExitAttemptInProgress(ticker)) {
            System.out.println(
                    "SHORT PARTIAL PROFIT COVER SKIPPED: recent exit attempt still cooling down. " +
                            ticker
            );
            return false;
        }

        exitAttemptTimestamps.put(
                ticker.trim().toUpperCase(),
                System.currentTimeMillis()
        );

        boolean covered =
                orderExecutor.coverShortAndWaitForFill(
                        ticker,
                        qtyToCover
                );

        String normalizedTicker = ticker.trim().toUpperCase();
        exitAttemptTimestamps.remove(normalizedTicker);

        if (!covered) {
            System.out.println(
                    "SHORT PARTIAL PROFIT COVER NOT FILLED: " +
                            ticker +
                            " qty=" +
                            qtyToCover
            );
            return false;
        }

        featureOutcomeJournal.recordPartialExit(
                position,
                currentPrice,
                qtyToCover,
                "SHORT_PARTIAL_PROFIT_TAKE"
        );
        lifecycleOptimizationAgent.recordPartialExit(
                position,
                currentPrice,
                qtyToCover,
                "SHORT_PARTIAL_PROFIT_TAKE"
        );

        position.quantity -= qtyToCover;
        position.partialProfitTaken = true;

        if (position.quantity <= 0) {
            openPositions.remove(normalizedTicker);
            notifyPositionClosed(normalizedTicker);
        }

        double profit =
                (position.entryPrice - currentPrice) *
                        qtyToCover;

        System.out.println(
                "SHORT PARTIAL PROFIT COVER FILLED: " +
                        ticker +
                        " qty=" +
                        qtyToCover +
                        " remaining=" +
                        position.quantity +
                        " cover=" +
                        currentPrice +
                        " entry=" +
                        position.entryPrice +
                        " profit=" +
                        profit
        );

        return true;
    }

    private void requestShortCover(
            String ticker,
            Position position,
            double currentPrice,
            String reason
    ) {
        if (position == null || position.quantity <= 0) {
            return;
        }

        System.out.println(
                "SHORT COVER ORDER REQUESTED: " +
                        ticker +
                        " qty=" +
                        position.quantity +
                        " currentPrice=" +
                        currentPrice +
                        " entryPrice=" +
                        position.entryPrice +
                        " lowPrice=" +
                        position.troughPrice +
                        " reason=" +
                        reason
        );

        if (recentExitAttemptInProgress(ticker)) {
            System.out.println(
                    "SHORT COVER ORDER SKIPPED: recent exit attempt still cooling down. " +
                            ticker +
                            " reason=" +
                            reason
            );

            return;
        }

        exitAttemptTimestamps.put(
                ticker.trim().toUpperCase(),
                System.currentTimeMillis()
        );

        boolean covered =
                orderExecutor.coverShortAndWaitForFill(
                        ticker,
                        position.quantity
                );

        if (covered) {
            featureOutcomeJournal.recordClose(
                    position,
                    currentPrice,
                    position.quantity,
                    reason
            );
            lifecycleOptimizationAgent.recordClose(
                    position,
                    currentPrice,
                    position.quantity,
                    reason
            );
            dynamicEntryExitDecisionAgent.recordClosed(position, currentPrice, reason);

            String normalizedTicker = ticker.trim().toUpperCase();

            openPositions.remove(normalizedTicker);
            exitAttemptTimestamps.remove(normalizedTicker);
            notifyPositionClosed(normalizedTicker);

            double profit =
                    (position.entryPrice - currentPrice) *
                            position.quantity;

            System.out.println(
                    "SHORT POSITION COVERED: " +
                            ticker +
                            " qty=" +
                            position.quantity +
                            " cover=" +
                            currentPrice +
                            " entry=" +
                            position.entryPrice +
                            " profit=" +
                            profit +
                            " reason=" +
                            reason
            );
        } else {
            System.out.println(
                    "SHORT COVER ORDER NOT FILLED: " +
                            ticker +
                            " reason=" +
                            reason
            );
        }
    }


    public synchronized boolean liquidateForPortfolioRotation(
            String ticker,
            String reason
    ) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }

        String normalizedTicker = ticker.trim().toUpperCase();
        Position position = openPositions.get(normalizedTicker);
        if (position == null || position.quantity <= 0) {
            System.out.println("PORTFOLIO ROTATION LIQUIDATION SKIPPED: no tracked position " + normalizedTicker);
            return false;
        }

        if (recentExitAttemptInProgress(normalizedTicker)) {
            System.out.println("PORTFOLIO ROTATION LIQUIDATION SKIPPED: recent exit cooldown ticker=" +
                    normalizedTicker + " reason=" + reason);
            return false;
        }

        double currentPrice = latestKnownPrice(normalizedTicker);
        if (currentPrice <= 0) {
            currentPrice = position.entryPrice;
        }

        System.out.println("PORTFOLIO ROTATION LIQUIDATION REQUESTED: ticker=" + normalizedTicker +
                " side=" + (position.isShortPosition() ? "SHORT" : "LONG") +
                " qty=" + position.quantity +
                " current=" + currentPrice +
                " entry=" + position.entryPrice +
                " reason=" + reason);

        exitAttemptTimestamps.put(normalizedTicker, System.currentTimeMillis());

        boolean filled = position.isShortPosition()
                ? orderExecutor.coverShortAndWaitForFill(normalizedTicker, position.quantity)
                : orderExecutor.sellMarketAndWaitForFill(normalizedTicker, position.quantity);

        if (!filled) {
            System.out.println("PORTFOLIO ROTATION LIQUIDATION NOT FILLED: ticker=" + normalizedTicker +
                    " reason=" + reason);
            return false;
        }

        featureOutcomeJournal.recordClose(position, currentPrice, position.quantity,
                "PORTFOLIO_ROTATION:" + reason);
        lifecycleOptimizationAgent.recordClose(position, currentPrice, position.quantity,
                "PORTFOLIO_ROTATION:" + reason);
        dynamicEntryExitDecisionAgent.recordClosed(position, currentPrice, "PORTFOLIO_ROTATION:" + reason);

        openPositions.remove(normalizedTicker);
        exitAttemptTimestamps.remove(normalizedTicker);
        notifyPositionClosed(normalizedTicker);

        double profit = position.isShortPosition()
                ? (position.entryPrice - currentPrice) * position.quantity
                : (currentPrice - position.entryPrice) * position.quantity;

        System.out.println("PORTFOLIO ROTATION LIQUIDATION FILLED: ticker=" + normalizedTicker +
                " qty=" + position.quantity +
                " profit=" + profit +
                " reason=" + reason);

        return true;
    }


    private void startPositionSweepThread() {
        if (POSITION_SWEEP_INTERVAL_MS <= 0) {
            System.out.println(
                    "POSITION SWEEP DISABLED: POSITION_SWEEP_INTERVAL_SECONDS <= 0"
            );

            return;
        }

        Thread thread =
                new Thread(() -> {
                    while (true) {
                        try {
                            Thread.sleep(POSITION_SWEEP_INTERVAL_MS);

                            evaluateAllOpenPositions(
                                    "PERIODIC_SWEEP"
                            );
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();

                            return;
                        } catch (Exception e) {
                            System.out.println(
                                    "POSITION SWEEP ERROR: " +
                                            e.getMessage()
                            );
                        }
                    }
                });

        thread.setDaemon(true);
        thread.setName("position-emergency-sweep");
        thread.start();

        System.out.println(
                "POSITION EMERGENCY SWEEP STARTED: intervalMs=" +
                        POSITION_SWEEP_INTERVAL_MS +
                        " syncedExitIfNotProfitable=" +
                        SYNCED_POSITION_EXIT_IF_NOT_PROFITABLE +
                        " syncedMinProfitPercent=" +
                        (SYNCED_POSITION_MIN_PROFIT_PERCENT * 100.0)
        );
    }

    private void evaluateAllOpenPositions(
            String reason
    ) {
        List<Position> snapshot;

        synchronized (this) {
            snapshot = new ArrayList<>(openPositions.values());
        }

        if (snapshot.isEmpty()) {
            return;
        }

        for (Position position : snapshot) {
            if (position == null ||
                    position.ticker == null ||
                    position.ticker.isBlank()) {
                continue;
            }

            double currentPrice =
                    latestKnownPrice(
                            position.ticker
                    );

            if (currentPrice <= 0) {
                System.out.println(
                        "POSITION SWEEP SKIPPED: no current price for " +
                                position.ticker +
                                " reason=" +
                                reason
                );

                continue;
            }

            System.out.println(
                    "POSITION SWEEP CHECK: " +
                            position.ticker +
                            " current=" +
                            currentPrice +
                            " entry=" +
                            position.entryPrice +
                            " peak=" +
                            position.peakPrice +
                            " trough=" +
                            position.troughPrice +
                            " side=" +
                            (position.isShortPosition() ? "SHORT" : "LONG") +
                            " synced=" +
                            position.syncedFromBroker +
                            " reason=" +
                            reason
            );

            onPrice(
                    position.ticker,
                    currentPrice
            );
        }
    }

    private double latestKnownPrice(
            String ticker
    ) {
        if (ticker == null || ticker.isBlank()) {
            return 0.0;
        }

        if (currentPriceProvider != null) {
            try {
                Double providerPrice =
                        currentPriceProvider.apply(
                                ticker.trim().toUpperCase()
                        );

                if (providerPrice != null && providerPrice > 0) {
                    return providerPrice;
                }
            } catch (Exception e) {
                System.out.println(
                        "POSITION PRICE LOOKUP FAILED: " +
                                ticker +
                                " " +
                                e.getMessage()
                );
            }
        }

        try {
            List<com.bot.model.Bar> recentBars =
                    marketData.recentBars(
                            ticker.trim().toUpperCase(),
                            1
                    );

            if (!recentBars.isEmpty()) {
                double close =
                        recentBars.get(recentBars.size() - 1).close;

                if (close > 0) {
                    return close;
                }
            }
        } catch (Exception ignored) {
        }

        return 0.0;
    }

    private boolean recentExitAttemptInProgress(
            String ticker
    ) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }

        Long lastAttemptAt =
                exitAttemptTimestamps.get(
                        ticker.trim().toUpperCase()
                );

        if (lastAttemptAt == null) {
            return false;
        }

        return System.currentTimeMillis() - lastAttemptAt < EXIT_RETRY_AFTER_MS;
    }

    @Override
    public synchronized boolean hasPosition(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return false;
        }

        return openPositions.containsKey(ticker.trim().toUpperCase());
    }

    public synchronized boolean hasOpenPosition(String ticker) {
        return hasPosition(ticker);
    }

    @Override
    public synchronized int openCount() {
        return openPositions.size();
    }

    public synchronized int openPositionCount() {
        return openCount();
    }

    @Override
    public synchronized Position getPosition(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return null;
        }

        return openPositions.get(ticker.trim().toUpperCase());
    }

    @Override
    public synchronized Collection<Position> allPositions() {
        return new ArrayList<>(openPositions.values());
    }

    public synchronized Collection<Position> getOpenPositions() {
        return allPositions();
    }
    private static boolean envBoolean(
            String name,
            boolean defaultValue
    ) {
        try {
            String value =
                    System.getenv(name);

            if (value == null || value.isBlank()) {
                return defaultValue;
            }

            String normalized = value.trim();
            return "true".equalsIgnoreCase(normalized)
                    || "1".equals(normalized)
                    || "yes".equalsIgnoreCase(normalized);
        } catch (Exception ignored) {
            return defaultValue;
        }
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
