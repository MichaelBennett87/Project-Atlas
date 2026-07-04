package com.bot.intelligence;

import com.bot.master.CatalystQualityGate;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.model.Position;
import com.bot.model.TradeDirection;
import com.bot.technical.TechnicalAnalysis;
import com.bot.scalping.VolumeFirstScalpingPolicy;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Final adaptive entry/exit governor.
 *
 * This class is deliberately positioned after normal strategies, ranking,
 * risk checks, and the multi-agent committee.  Its job is to prevent the
 * system from bleeding through tiny, low-quality churn trades while still
 * allowing meaningful exposure when evidence is strong.
 *
 * It combines deterministic guardrails with the real OpenAI governor when
 * enabled.  Runtime thresholds are loaded from the versioned active
 * entry/exit policy produced by the autonomous nightly validation pipeline.
 * That lets tomorrow's live session use improved entry/exit behavior without
 * human intervention while still preserving hard risk limits.
 */
public final class DynamicEntryExitDecisionAgent {
    private static final DynamicEntryExitDecisionAgent INSTANCE = new DynamicEntryExitDecisionAgent();

    private final boolean enabled = envBool("DYNAMIC_ENTRY_EXIT_AGENT_ENABLED", true);
    private final Path journalPath = Path.of(env("DYNAMIC_ENTRY_EXIT_AGENT_JOURNAL", "logs/dynamic_entry_exit_agent.csv"));
    private final Map<String, Long> tickerCooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, LossWindow> recentLossesByStrategy = new ConcurrentHashMap<>();
    private final OpenAiEntryExitGovernorAgent openAiGovernor = OpenAiEntryExitGovernorAgent.getInstance();
    private final EntryExitAdaptivePolicy adaptivePolicy = EntryExitAdaptivePolicy.getInstance();

    private final double minEntryScore = effectiveEntryMinScore();
    private final double minAPlusEntryScore = Math.max(minEntryScore + 0.08, Math.min(policyDouble("DYNAMIC_ENTRY_A_PLUS_SCORE", 0.84), envDouble("DYNAMIC_ENTRY_MAX_EFFECTIVE_A_PLUS_SCORE", 0.78)));
    private final double minMeaningfulNotionalFraction = Math.min(policyDouble("DYNAMIC_ENTRY_MIN_NOTIONAL_FRACTION", 0.0075), envDouble("DYNAMIC_ENTRY_MAX_EFFECTIVE_MIN_NOTIONAL_FRACTION", 0.010));
    private final double minMeaningfulNotionalDollars = Math.min(policyDouble("DYNAMIC_ENTRY_MIN_NOTIONAL_DOLLARS", 600.0), envDouble("DYNAMIC_ENTRY_MAX_EFFECTIVE_MIN_NOTIONAL_DOLLARS", 800.0));
    private final double maxLongFraction = policyDouble("DYNAMIC_ENTRY_MAX_LONG_FRACTION", 0.060);
    private final double maxShortFraction = policyDouble("DYNAMIC_ENTRY_MAX_SHORT_FRACTION", 0.045);
    private final int maxRecentStrategyLosses = policyInt("DYNAMIC_ENTRY_MAX_RECENT_STRATEGY_LOSSES", 5);
    private final long tickerCooldownMs = policyLong("DYNAMIC_ENTRY_TICKER_COOLDOWN_SECONDS", 900L) * 1000L;
    private final long strategyLossWindowMs = policyLong("DYNAMIC_ENTRY_STRATEGY_LOSS_WINDOW_MINUTES", 180L) * 60L * 1000L;

    private DynamicEntryExitDecisionAgent() {
        ensureJournal();
        if (enabled) {
            System.out.println("DYNAMIC ENTRY/EXIT DECISION AGENT READY: minScore=" + minEntryScore +
                    " minNotional=max($" + minMeaningfulNotionalDollars + ", equity*" + minMeaningfulNotionalFraction + ") openAiGovernor=" + openAiGovernor.isEnabled() + " activePolicy=" + adaptivePolicy.activePath() + " policyLoaded=" + adaptivePolicy.loaded() + " journal=" + journalPath);
        }
    }

    public static DynamicEntryExitDecisionAgent getInstance() {
        return INSTANCE;
    }

    public EntryReview reviewEntry(
            StrategyContext context,
            StrategySignal signal,
            double accountEquity,
            double referencePrice,
            int requestedQuantity
    ) {
        if (!enabled) {
            return EntryReview.approved(Math.max(1, requestedQuantity), 1.0, "Dynamic entry/exit agent disabled.", 1.0);
        }
        if (context == null || signal == null) {
            return EntryReview.blocked("Dynamic entry rejected: missing context or signal.", 0.0);
        }

        String ticker = normalize(signal.getTicker());
        if (ticker.isBlank()) {
            return EntryReview.blocked("Dynamic entry rejected: blank ticker.", 0.0);
        }

        long now = System.currentTimeMillis();
        Long cooldownUntil = tickerCooldownUntil.get(ticker);
        if (cooldownUntil != null && cooldownUntil > now) {
            return EntryReview.blocked("Dynamic entry rejected: ticker cooldown active after recent failed/lossy trade. remainingMs=" + (cooldownUntil - now), 0.0);
        }
        if (cooldownUntil != null) {
            tickerCooldownUntil.remove(ticker);
        }

        LossWindow lossWindow = recentLossesByStrategy.get(normalizeStrategy(signal.getStrategyName()));
        if (lossWindow != null && lossWindow.lossCount(now, strategyLossWindowMs) >= maxRecentStrategyLosses) {
            return EntryReview.blocked("Dynamic entry rejected: strategy is in recent loss cooldown. strategy=" + signal.getStrategyName() +
                    " recentLosses=" + lossWindow.lossCount(now, strategyLossWindowMs), 0.0);
        }

        double price = referencePrice > 0.0 ? referencePrice : Math.max(0.0, context.getLastPrice());
        if (price <= 0.0) {
            return EntryReview.blocked("Dynamic entry rejected: no reliable price for sizing.", 0.0);
        }
        double equity = accountEquity > 0.0 ? accountEquity : Math.max(1.0, context.getAccountEquity());

        MarketStateDatabase2.State state = MarketStateDatabase2.getInstance().snapshot(ticker);
        VolumeFirstScalpingPolicy.ScalpingTape scalpTape = VolumeFirstScalpingPolicy.tape(context);
        double rvol = Math.max(safe(TechnicalAnalysis.relativeVolume(context.getBars(), 20)), scalpTape.relativeVolume);
        boolean bullishBreak = TechnicalAnalysis.bullishBreak(context.getBars());
        boolean reclaimedVwap = TechnicalAnalysis.reclaimedVwap(context.getBars(), 30);
        boolean aboveVwap = TechnicalAnalysis.latestClose(context.getBars()) > TechnicalAnalysis.vwap(context.getBars(), 30);
        double catalyst = CatalystQualityGate.tradeableCatalystScore(context.getLatestNews());
        double expectedMove = Math.max(0.0, signal.getExpectedMovePercent());
        double priority = Math.max(0.0, signal.priorityScore());

        double technicalComponent = clamp((rvol / 6.0) * 0.35 + (bullishBreak ? 0.22 : 0.0) +
                (reclaimedVwap ? 0.18 : 0.0) + (aboveVwap ? 0.10 : 0.0));
        double stateOpportunity = state == null ? 0.35 : clamp(state.opportunityScore);
        double stateRisk = state == null ? 0.10 : clamp(state.riskScore);
        double stateDirectionAlignment = stateDirectionAlignment(signal, state);
        double strategyPerformance = clamp01(TradeLifecycleOptimizationAgent.getInstance().strategyMultiplier(signal.getStrategyName()) / 1.20);

        double volumeScalpComponent = clamp(scalpTape.violentScore * 0.75
                + Math.min(0.20, Math.log10(Math.max(1.0, scalpTape.dollarVolume)) / 8.0)
                + (scalpTape.topVolume ? 0.12 : 0.0));
        double score = clamp(
                signal.getConfidence() * 0.20 +
                clamp(expectedMove / 8.0) * 0.09 +
                clamp(priority / 0.08) * 0.11 +
                technicalComponent * 0.08 +
                stateOpportunity * 0.08 +
                catalyst * 0.05 +
                strategyPerformance * 0.04 +
                volumeScalpComponent * 0.35
        );
        score = clamp(score * stateDirectionAlignment - (stateRisk * 0.10));

        OpenAiEntryExitGovernorAgent.Decision openAiDecision = openAiGovernor.reviewEntry(
                context,
                signal,
                equity,
                price,
                requestedQuantity,
                score
        );
        if (openAiDecision != null && openAiDecision.vetoesEntry() && openAiDecision.confidence >= envDouble("OPENAI_ENTRY_VETO_MIN_CONFIDENCE", 0.80)) {
            return blockAndJournal(context, signal, score,
                    "OpenAI entry governor high-confidence vetoed trade: confidence=" + fmt(openAiDecision.confidence) + " reason=" + openAiDecision.reason);
        }
        boolean openAiHighConvictionApprove = openAiDecision != null
                && openAiDecision.attempted
                && ("APPROVE".equals(openAiDecision.action) || "REDUCE".equals(openAiDecision.action))
                && openAiDecision.confidence >= envDouble("OPENAI_ENTRY_EXIT_OVERRIDE_CONFIDENCE", 0.78);

        if (state != null && stateDirectionAlignment < 0.62 && signal.getConfidence() < 0.88 && catalyst < 0.65 && !openAiHighConvictionApprove) {
            if ("true".equalsIgnoreCase(System.getenv().getOrDefault("DYNAMIC_ENTRY_HARD_BLOCK_STATE_CONFLICT", "false")) || stateDirectionAlignment < 0.35) {
                return blockAndJournal(context, signal, score,
                        "Dynamic entry rejected: MarketState DB2 direction conflicts with candidate. stateDirection=" + state.direction +
                                " stateOpportunity=" + fmt(state.opportunityScore) + " signalDirection=" + signal.getDirection());
            }
            System.out.println("DYNAMIC ENTRY STATE CONFLICT ADVISORY: ticker=" + ticker +
                    " stateDirection=" + state.direction + " signalDirection=" + signal.getDirection() +
                    " alignment=" + fmt(stateDirectionAlignment) + " action=allow_to_openai_governor");
        }

        if (!VolumeFirstScalpingPolicy.hasEnoughLiquidity(context) && rvol < policyDouble("DYNAMIC_ENTRY_MIN_RVOL", 0.35) && catalyst < 0.35 && signal.getConfidence() < 0.76 && !openAiHighConvictionApprove) {
            if ("true".equalsIgnoreCase(System.getenv().getOrDefault("DYNAMIC_ENTRY_HARD_BLOCK_WEAK_VOLUME", "false")) || rvol < 0.10) {
                return blockAndJournal(context, signal, score,
                        "Dynamic entry rejected: weak volume with no strong catalyst. rvol=" + fmt(rvol) + " catalyst=" + fmt(catalyst));
            }
            System.out.println("DYNAMIC ENTRY WEAK VOLUME ADVISORY: ticker=" + ticker +
                    " rvol=" + fmt(rvol) + " catalyst=" + fmt(catalyst) + " action=allow_to_openai_governor");
        }

        double openAiOverrideFloor = envDouble("OPENAI_ENTRY_OVERRIDE_MIN_DETERMINISTIC_SCORE", 0.20);
        double softFloor = envDouble("DYNAMIC_ENTRY_SOFT_MIN_SCORE_FLOOR", 0.28);
        boolean allowSoftFloor = score >= softFloor &&
                (openAiHighConvictionApprove || !openAiGovernor.isEnabled() || signal.getConfidence() >= 0.62 || catalyst >= 0.35
                        || (VolumeFirstScalpingPolicy.isScalpingStrategy(signal.getStrategyName()) && VolumeFirstScalpingPolicy.hasEnoughLiquidity(context) && VolumeFirstScalpingPolicy.hasViolentMovement(context)));
        if (score < minEntryScore && !(openAiHighConvictionApprove && score >= openAiOverrideFloor) && !allowSoftFloor) {
            return blockAndJournal(context, signal, score,
                    "Dynamic entry rejected: quality score below profitability threshold. score=" + fmt(score) + " min=" + minEntryScore);
        }
        if (score < minEntryScore) {
            System.out.println("DYNAMIC ENTRY SOFT-FLOOR APPROVAL: ticker=" + ticker +
                    " score=" + fmt(score) + " policyMin=" + fmt(minEntryScore) + " action=allow_with_smaller_size");
        }

        double maxFraction = signal.getDirection() == TradeDirection.SHORT_STOCK ? maxShortFraction : maxLongFraction;
        double minNotional = Math.max(minMeaningfulNotionalDollars, equity * minMeaningfulNotionalFraction);
        double qualityFraction = minMeaningfulNotionalFraction + Math.pow(score, 2.0) * (maxFraction - minMeaningfulNotionalFraction);
        if (score >= minAPlusEntryScore && signal.getConfidence() >= 0.90) {
            qualityFraction = Math.min(maxFraction, qualityFraction * 1.25);
        }
        double targetNotional = Math.max(minNotional, equity * qualityFraction);
        double maxNotional = Math.max(minNotional, equity * maxFraction);
        targetNotional = Math.min(targetNotional, maxNotional);
        if (openAiDecision != null && openAiDecision.attempted) {
            targetNotional = Math.max(minNotional, Math.min(maxNotional, targetNotional * openAiDecision.quantityMultiplier));
        }

        int approvedQty = Math.max(1, (int)Math.floor(targetNotional / price));
        int requestedQty = Math.max(1, requestedQuantity);
        boolean openAiReduced = openAiDecision != null && openAiDecision.attempted && openAiDecision.quantityMultiplier < 0.99;
        if (requestedQty > approvedQty && score < minAPlusEntryScore && !openAiReduced) {
            approvedQty = requestedQty; // Existing risk/sizer requested more; do not shrink non-A+ below current plan unless OpenAI explicitly reduced exposure.
        }

        double notional = approvedQty * price;
        if (notional < minNotional && price < minNotional && score < minAPlusEntryScore && signal.getConfidence() < 0.72 && catalyst < 0.35) {
            return blockAndJournal(context, signal, score,
                    "Dynamic entry rejected: position would be too small to overcome spread/fees/slippage. notional=" + fmt(notional) + " min=" + fmt(minNotional));
        }

        String reason = "Dynamic entry approved: score=" + fmt(score) +
                " qty=" + approvedQty +
                " notional=" + fmt(notional) +
                " rvol=" + fmt(rvol) +
                " volumeFirst=" + VolumeFirstScalpingPolicy.diagnostics(context) +
                " catalyst=" + fmt(catalyst) +
                " stateOpportunity=" + fmt(stateOpportunity) +
                " stateRisk=" + fmt(stateRisk) +
                " directionAlignment=" + fmt(stateDirectionAlignment) +
                (openAiDecision != null && openAiDecision.attempted ? " openAiAction=" + openAiDecision.action + " openAiConfidence=" + fmt(openAiDecision.confidence) + " openAiReason=" + openAiDecision.reason : " openAi=skipped");
        append("ENTRY_APPROVED", ticker, signal.getStrategyName(), signal.getDirection() == null ? "NO_TRADE" : signal.getDirection().name(), approvedQty, price, 0.0, score, reason);
        return EntryReview.approved(approvedQty, score >= minAPlusEntryScore ? 1.0 : 0.92, reason, score);
    }

    public String dynamicExitReason(Position position, double currentPrice) {
        if (!enabled || position == null || currentPrice <= 0.0 || position.entryPrice <= 0.0) return null;
        long ageMs = position.openedAt <= 0 ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - position.openedAt);
        double pnl = position.isShortPosition()
                ? (position.entryPrice - currentPrice) / position.entryPrice
                : (currentPrice - position.entryPrice) / position.entryPrice;
        double best = bestGain(position, currentPrice);
        double adverse = adverseMove(position, currentPrice);
        MarketStateDatabase2.State state = MarketStateDatabase2.getInstance().snapshot(position.ticker);
        double stateOpportunity = state == null ? 0.0 : state.opportunityScore;
        boolean stateStillSupports = stateSupportsPosition(position, state);

        double hardLoss = policyDouble("DYNAMIC_EXIT_HARD_LOSS_PERCENT", -3.0) / 100.0;
        if (pnl <= hardLoss) {
            return "DYNAMIC_AI_HARD_LOSS_EXIT pnl=" + pct(pnl) + " adverse=" + pct(adverse);
        }

        long minJudgementAgeMs = policyLong("DYNAMIC_EXIT_MIN_JUDGEMENT_SECONDS", 240L) * 1000L;
        if (ageMs < minJudgementAgeMs) {
            return null;
        }

        OpenAiEntryExitGovernorAgent.Decision openAiExit = openAiGovernor.reviewExit(position, currentPrice, pnl, best, "NONE_YET");
        if (openAiExit != null && openAiExit.exitsPosition()) {
            return "OPENAI_DYNAMIC_EXIT confidence=" + fmt(openAiExit.confidence) + " reason=" + openAiExit.reason;
        }

        double weakEntryConfidence = policyDouble("DYNAMIC_EXIT_WEAK_ENTRY_CONFIDENCE", 0.78);
        double weakEntryPriority = policyDouble("DYNAMIC_EXIT_WEAK_ENTRY_PRIORITY", 0.050);
        boolean weakEntry = position.entryConfidence < weakEntryConfidence && position.entryPriorityScore < weakEntryPriority;

        if (weakEntry && !stateStillSupports && pnl <= policyDouble("DYNAMIC_EXIT_WEAK_DECAY_LOSS_PERCENT", -0.45) / 100.0) {
            return "DYNAMIC_AI_EXPECTANCY_DECAY_EXIT weakEntry=true pnl=" + pct(pnl) +
                    " stateOpportunity=" + fmt(stateOpportunity) + " ageMin=" + fmt(ageMs / 60000.0);
        }

        double giveback = policyDouble("DYNAMIC_EXIT_PROFIT_GIVEBACK_PERCENT", 0.85) / 100.0;
        if (best >= policyDouble("DYNAMIC_EXIT_MIN_BEST_GAIN_PERCENT", 1.25) / 100.0 && pnl <= best - giveback && !stateStillSupports) {
            return "DYNAMIC_AI_PROFIT_CAPTURE_EXIT best=" + pct(best) + " current=" + pct(pnl) +
                    " stateSupport=false";
        }

        long noFollowThroughMs = policyLong("DYNAMIC_EXIT_NO_FOLLOW_THROUGH_SECONDS", 1_200L) * 1000L;
        if (ageMs >= noFollowThroughMs && best < policyDouble("DYNAMIC_EXIT_NO_FOLLOW_BEST_GAIN_PERCENT", 0.40) / 100.0 && pnl <= 0.0) {
            return "DYNAMIC_AI_NO_FOLLOW_THROUGH_EXIT best=" + pct(best) + " pnl=" + pct(pnl) +
                    " ageMin=" + fmt(ageMs / 60000.0);
        }

        return null;
    }

    public void recordClosed(Position position, double exitPrice, String reason) {
        if (!enabled || position == null || exitPrice <= 0.0 || position.entryPrice <= 0.0) return;
        double pnl = position.isShortPosition()
                ? (position.entryPrice - exitPrice) / position.entryPrice
                : (exitPrice - position.entryPrice) / position.entryPrice;
        String ticker = normalize(position.ticker);
        String strategy = normalizeStrategy(position.strategyName);
        append("CLOSE_OBSERVED", ticker, strategy, position.isShortPosition() ? "SHORT" : "LONG", position.quantity, exitPrice, pnl, position.entryPriorityScore, reason);
        if (pnl < 0.0) {
            tickerCooldownUntil.put(ticker, System.currentTimeMillis() + tickerCooldownMs);
            recentLossesByStrategy.computeIfAbsent(strategy, ignored -> new LossWindow()).recordLoss(System.currentTimeMillis());
        }
    }

    private EntryReview blockAndJournal(StrategyContext context, StrategySignal signal, double score, String reason) {
        append("ENTRY_BLOCKED", normalize(signal == null ? null : signal.getTicker()),
                signal == null ? "UNKNOWN" : signal.getStrategyName(),
                signal == null || signal.getDirection() == null ? "NO_TRADE" : signal.getDirection().name(),
                0,
                context == null ? 0.0 : context.getLastPrice(),
                0.0,
                score,
                reason);
        return EntryReview.blocked(reason, score);
    }

    private static double stateDirectionAlignment(StrategySignal signal, MarketStateDatabase2.State state) {
        if (signal == null || state == null || state.direction == null) return 1.0;
        boolean shortSignal = signal.getDirection() == TradeDirection.SHORT_STOCK;
        boolean shortState = "SHORT".equalsIgnoreCase(state.direction);
        return shortSignal == shortState ? 1.08 : 0.70;
    }

    private static boolean stateSupportsPosition(Position position, MarketStateDatabase2.State state) {
        if (position == null || state == null) return true;
        if (position.isShortPosition()) {
            return "SHORT".equalsIgnoreCase(state.direction) && state.shortScore >= 0.45 && state.riskScore < 0.85;
        }
        return "LONG".equalsIgnoreCase(state.direction) && state.longScore >= 0.45 && state.riskScore < 0.85;
    }

    private static double bestGain(Position p, double currentPrice) {
        if (p == null || p.entryPrice <= 0.0) return 0.0;
        if (p.isShortPosition()) {
            double trough = p.troughPrice > 0 ? Math.min(p.troughPrice, currentPrice) : currentPrice;
            return (p.entryPrice - trough) / p.entryPrice;
        }
        double peak = p.peakPrice > 0 ? Math.max(p.peakPrice, currentPrice) : currentPrice;
        return (peak - p.entryPrice) / p.entryPrice;
    }

    private static double adverseMove(Position p, double currentPrice) {
        if (p == null || p.entryPrice <= 0.0) return 0.0;
        if (p.isShortPosition()) {
            double peak = p.peakPrice > 0 ? Math.max(p.peakPrice, currentPrice) : currentPrice;
            return (p.entryPrice - peak) / p.entryPrice;
        }
        double trough = p.troughPrice > 0 ? Math.min(p.troughPrice, currentPrice) : currentPrice;
        return (trough - p.entryPrice) / p.entryPrice;
    }

    private void ensureJournal() {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(journalPath) || Files.size(journalPath) == 0) {
                try (BufferedWriter w = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write("timestamp,event,ticker,strategy,side,qty,price,pnlOrZero,score,reason");
                    w.newLine();
                }
            }
        } catch (Exception e) {
            System.out.println("DYNAMIC ENTRY/EXIT JOURNAL INIT FAILED: " + e.getMessage());
        }
    }

    private synchronized void append(String event, String ticker, String strategy, String side, int qty, double price, double pnl, double score, String reason) {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(journalPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(String.join(",",
                        csv(Instant.now().toString()),
                        csv(event),
                        csv(normalize(ticker)),
                        csv(normalizeStrategy(strategy)),
                        csv(side),
                        Integer.toString(Math.max(0, qty)),
                        num(price),
                        num(pnl),
                        num(score),
                        csv(reason)));
                w.newLine();
            }
        } catch (Exception e) {
            if (envBool("DYNAMIC_ENTRY_EXIT_VERBOSE_ERRORS", false)) {
                System.out.println("DYNAMIC ENTRY/EXIT JOURNAL FAILED: " + e.getMessage());
            }
        }
    }

    public static final class EntryReview {
        private final boolean approved;
        private final int approvedQuantity;
        private final double confidenceMultiplier;
        private final String reason;
        private final double score;

        private EntryReview(boolean approved, int approvedQuantity, double confidenceMultiplier, String reason, double score) {
            this.approved = approved;
            this.approvedQuantity = Math.max(0, approvedQuantity);
            this.confidenceMultiplier = Math.max(0.0, Math.min(1.25, confidenceMultiplier));
            this.reason = reason == null ? "" : reason;
            this.score = score;
        }
        public static EntryReview approved(int approvedQuantity, double confidenceMultiplier, String reason, double score) {
            return new EntryReview(true, approvedQuantity, confidenceMultiplier, reason, score);
        }
        public static EntryReview blocked(String reason, double score) {
            return new EntryReview(false, 0, 0.0, reason, score);
        }
        public boolean isApproved() { return approved; }
        public int getApprovedQuantity() { return approvedQuantity; }
        public double getConfidenceMultiplier() { return confidenceMultiplier; }
        public String getReason() { return reason; }
        public double getScore() { return score; }
    }

    private static final class LossWindow {
        private final java.util.ArrayList<Long> losses = new java.util.ArrayList<>();
        synchronized void recordLoss(long at) { losses.add(at); prune(at, Long.MAX_VALUE / 4); }
        synchronized int lossCount(long now, long windowMs) { prune(now, windowMs); return losses.size(); }
        private void prune(long now, long windowMs) { losses.removeIf(ts -> ts == null || now - ts > windowMs); }
    }

    private static String normalize(String ticker) { return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", ""); }
    private static String normalizeStrategy(String strategy) { return strategy == null || strategy.isBlank() ? "UNKNOWN" : strategy.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", "_"); }
    private static double clamp(double v) { return Double.isFinite(v) ? Math.max(0.0, Math.min(1.0, v)) : 0.0; }
    private static double clamp01(double v) { return clamp(v); }
    private static double safe(double v) { return Double.isFinite(v) ? v : 0.0; }
    private static String pct(double v) { return fmt(v * 100.0) + "%"; }
    private static String fmt(double v) { return String.format(Locale.US, "%.4f", Double.isFinite(v) ? v : 0.0); }
    private static String num(double v) { return String.format(Locale.US, "%.6f", Double.isFinite(v) ? v : 0.0); }
    private static String csv(String value) { String v = value == null ? "" : value; return '"' + v.replace("\"", "\"\"").replace('\n',' ').replace('\r',' ') + '"'; }
    private static String env(String key, String fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : v.trim(); }
    private static boolean envBool(String key, boolean fallback) { String v = System.getenv(key); if (v == null || v.isBlank()) return fallback; String x = v.trim().toLowerCase(Locale.ROOT); return x.equals("true") || x.equals("1") || x.equals("yes") || x.equals("on"); }
    private double effectiveEntryMinScore() {
        double policyScore = policyDouble("DYNAMIC_ENTRY_MIN_SCORE", 0.66);
        double maxEffective = envDouble("DYNAMIC_ENTRY_MAX_EFFECTIVE_MIN_SCORE", 0.72);
        double minEffective = envDouble("DYNAMIC_ENTRY_MIN_EFFECTIVE_MIN_SCORE", 0.58);
        return Math.max(minEffective, Math.min(policyScore, maxEffective));
    }

    private double policyDouble(String key, double fallback) { return adaptivePolicy.getDouble(key, envDouble(key, fallback)); }
    private int policyInt(String key, int fallback) { return adaptivePolicy.getInt(key, envInt(key, fallback)); }
    private long policyLong(String key, long fallback) { return adaptivePolicy.getLong(key, envLong(key, fallback)); }

    private static int envInt(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch(Exception e) { return fallback; } }
    private static long envLong(String key, long fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); } catch(Exception e) { return fallback; } }
    private static double envDouble(String key, double fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim()); } catch(Exception e) { return fallback; } }
}
