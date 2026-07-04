package com.bot.master;

import com.bot.engine.PositionManager;
import com.bot.model.MarketDataCache;
import com.bot.model.Position;

import java.util.Collection;

/**
 * Keeps the pre-catalyst prediction strategy from consuming the capital needed
 * by the faster intraday/news/parabolic systems.
 */
public class PreCatalystSleeveManager {

    public static final String STRATEGY_NAME = "PRE_CATALYST_PREDICTION_AGENT";

    private final double maxCapitalFraction = envDouble("PRE_CATALYST_MAX_CAPITAL_FRACTION", 0.10);
    private final boolean replacementEnabled = envBoolean("PRE_CATALYST_REPLACEMENT_ENABLED", true);
    private final double replacementConfidenceEdge = envDouble("PRE_CATALYST_REPLACEMENT_CONFIDENCE_EDGE", 0.08);
    private final double replacementPriorityMultiple = envDouble("PRE_CATALYST_REPLACEMENT_PRIORITY_MULTIPLE", 1.25);
    private final long minimumReplaceHoldMs = envLong("PRE_CATALYST_MIN_REPLACED_HOLD_SECONDS", 600L) * 1000L;

    public boolean isPreCatalyst(StrategySignal signal) {
        return signal != null && isPreCatalystStrategy(signal.getStrategyName());
    }

    public boolean isPreCatalystStrategy(String strategyName) {
        return strategyName != null && STRATEGY_NAME.equalsIgnoreCase(strategyName.trim());
    }

    public SleeveReview reviewCandidate(
            StrategyContext context,
            StrategySignal candidate,
            PositionManager positionManager,
            MarketDataCache marketData
    ) {
        if (!isPreCatalyst(candidate)) {
            return SleeveReview.approved("not_pre_catalyst_strategy");
        }
        if (context == null || positionManager == null) {
            return SleeveReview.blocked("Pre-catalyst sleeve could not verify exposure.");
        }

        String ticker = normalize(context.getTicker());
        double equity = Math.max(0.0, context.getAccountEquity());
        if (equity <= 0.0) {
            return SleeveReview.blocked("Pre-catalyst sleeve blocked because account equity was unavailable.");
        }

        double price = context.getLastPrice() > 0.0 ? context.getLastPrice() : latestPrice(marketData, ticker);
        if (price <= 0.0) {
            return SleeveReview.blocked("Pre-catalyst sleeve blocked because current price was unavailable.");
        }

        double sleeveCap = equity * Math.max(0.0, Math.min(0.10, maxCapitalFraction));
        double currentExposure = preCatalystExposure(positionManager.allPositions(), marketData, null);
        double requestedExposure = Math.max(0, candidate.getSuggestedQuantity()) * price;
        if (requestedExposure <= 0.0) {
            return SleeveReview.blocked("Pre-catalyst candidate requested zero exposure.");
        }

        if (currentExposure + requestedExposure <= sleeveCap) {
            return SleeveReview.approved(String.format(
                    "Pre-catalyst sleeve approved: exposure=%.2f requested=%.2f cap=%.2f",
                    currentExposure,
                    requestedExposure,
                    sleeveCap
            ));
        }

        if (!replacementEnabled) {
            return SleeveReview.blocked(String.format(
                    "Pre-catalyst sleeve cap reached: exposure=%.2f requested=%.2f cap=%.2f",
                    currentExposure,
                    requestedExposure,
                    sleeveCap
            ));
        }

        Position replaceable = weakestReplaceablePosition(positionManager.allPositions(), marketData, candidate, ticker);
        if (replaceable == null) {
            return SleeveReview.blocked(String.format(
                    "Pre-catalyst sleeve cap reached and no lower-confidence holding qualifies for replacement: exposure=%.2f requested=%.2f cap=%.2f candidateConfidence=%.2f priority=%.4f",
                    currentExposure,
                    requestedExposure,
                    sleeveCap,
                    candidate.getConfidence(),
                    candidate.priorityScore()
            ));
        }

        String replaceTicker = normalize(replaceable.ticker);
        double replaceExposure = positionExposure(replaceable, marketData);
        boolean liquidated = positionManager.liquidateForPortfolioRotation(
                replaceTicker,
                String.format(
                        "PRE_CATALYST_BETTER_CANDIDATE new=%s newConfidence=%.2f oldConfidence=%.2f newPriority=%.4f oldPriority=%.4f",
                        ticker,
                        candidate.getConfidence(),
                        replaceable.entryConfidence,
                        candidate.priorityScore(),
                        replaceable.entryPriorityScore
                )
        );

        if (!liquidated) {
            return SleeveReview.blocked("Pre-catalyst replacement candidate found but liquidation did not fill: " + replaceTicker);
        }

        double postReplacementExposure = Math.max(0.0, currentExposure - replaceExposure);
        if (postReplacementExposure + requestedExposure > sleeveCap * 1.03) {
            return SleeveReview.blocked(String.format(
                    "Pre-catalyst replacement filled but requested exposure still exceeds sleeve cap: postExposure=%.2f requested=%.2f cap=%.2f",
                    postReplacementExposure,
                    requestedExposure,
                    sleeveCap
            ));
        }

        return SleeveReview.approved(String.format(
                "Pre-catalyst sleeve rotated from %s to %s: postExposure=%.2f requested=%.2f cap=%.2f",
                replaceTicker,
                ticker,
                postReplacementExposure,
                requestedExposure,
                sleeveCap
        ));
    }

    public int clampQuantity(
            StrategySignal signal,
            int requestedQuantity,
            double accountEquity,
            double price,
            PositionManager positionManager,
            MarketDataCache marketData
    ) {
        if (!isPreCatalyst(signal) || requestedQuantity <= 0 || accountEquity <= 0.0 || price <= 0.0) {
            return requestedQuantity;
        }

        double cap = accountEquity * Math.max(0.0, Math.min(0.10, maxCapitalFraction));
        double currentExposure = positionManager == null ? 0.0 : preCatalystExposure(positionManager.allPositions(), marketData, normalize(signal.getTicker()));
        double remaining = Math.max(0.0, cap - currentExposure);
        int capped = (int)Math.floor(remaining / price);
        return Math.max(0, Math.min(requestedQuantity, capped));
    }

    private Position weakestReplaceablePosition(
            Collection<Position> positions,
            MarketDataCache marketData,
            StrategySignal candidate,
            String candidateTicker
    ) {
        if (positions == null || candidate == null) {
            return null;
        }

        Position weakest = null;
        double weakestScore = Double.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (Position position : positions) {
            if (position == null || !isPreCatalystStrategy(position.strategyName)) {
                continue;
            }
            String ticker = normalize(position.ticker);
            if (ticker.isBlank() || ticker.equals(candidateTicker)) {
                continue;
            }
            long ageMs = Math.max(0L, now - position.openedAt);
            if (ageMs < minimumReplaceHoldMs) {
                continue;
            }

            double oldPriority = Math.max(0.0, position.entryPriorityScore);
            double oldConfidence = Math.max(0.0, position.entryConfidence);
            boolean confidenceEdge = candidate.getConfidence() >= oldConfidence + replacementConfidenceEdge;
            boolean priorityEdge = candidate.priorityScore() >= Math.max(0.0001, oldPriority) * replacementPriorityMultiple;
            if (!confidenceEdge && !priorityEdge) {
                continue;
            }

            double score = oldConfidence + oldPriority;
            if (score < weakestScore) {
                weakestScore = score;
                weakest = position;
            }
        }
        return weakest;
    }

    private double preCatalystExposure(Collection<Position> positions, MarketDataCache marketData, String excludeTicker) {
        if (positions == null) {
            return 0.0;
        }
        double exposure = 0.0;
        for (Position position : positions) {
            if (position == null || !isPreCatalystStrategy(position.strategyName)) {
                continue;
            }
            String ticker = normalize(position.ticker);
            if (excludeTicker != null && !excludeTicker.isBlank() && excludeTicker.equals(ticker)) {
                continue;
            }
            exposure += positionExposure(position, marketData);
        }
        return exposure;
    }

    private double positionExposure(Position position, MarketDataCache marketData) {
        if (position == null || position.quantity <= 0) {
            return 0.0;
        }
        double price = latestPrice(marketData, normalize(position.ticker));
        if (price <= 0.0) {
            price = position.entryPrice;
        }
        return Math.max(0.0, price * position.quantity);
    }

    private double latestPrice(MarketDataCache marketData, String ticker) {
        if (marketData == null || ticker == null || ticker.isBlank()) {
            return 0.0;
        }
        try {
            return marketData.latestClose(ticker);
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean envBoolean(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value.trim());
    }

    public static final class SleeveReview {
        private final boolean approved;
        private final String reason;

        private SleeveReview(boolean approved, String reason) {
            this.approved = approved;
            this.reason = reason == null ? "" : reason;
        }

        public static SleeveReview approved(String reason) {
            return new SleeveReview(true, reason);
        }

        public static SleeveReview blocked(String reason) {
            return new SleeveReview(false, reason);
        }

        public boolean isApproved() { return approved; }
        public String getReason() { return reason; }
    }
}
