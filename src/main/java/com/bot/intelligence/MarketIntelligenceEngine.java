package com.bot.intelligence;

import com.bot.master.StrategyContext;

import java.util.List;

public class MarketIntelligenceEngine {

    private final MarketFeatureEngine featureEngine;
    private final ProbabilityModel probabilityModel;
    private final StrategyProposalGenerator proposalGenerator;
    private final FeatureJournal featureJournal;
    private final AdaptiveTradingPolicyStore policyStore;

    public MarketIntelligenceEngine() {
        this(
                new MarketFeatureEngine(),
                new HeuristicProbabilityModel(),
                new StrategyProposalGenerator(),
                new FeatureJournal(),
                new AdaptiveTradingPolicyStore()
        );
    }

    public MarketIntelligenceEngine(
            MarketFeatureEngine featureEngine,
            ProbabilityModel probabilityModel,
            StrategyProposalGenerator proposalGenerator,
            FeatureJournal featureJournal,
            AdaptiveTradingPolicyStore policyStore
    ) {
        this.featureEngine = featureEngine;
        this.probabilityModel = probabilityModel;
        this.proposalGenerator = proposalGenerator;
        this.featureJournal = featureJournal;
        this.policyStore = policyStore == null ? new AdaptiveTradingPolicyStore() : policyStore;
    }

    public MarketIntelligenceDecision evaluate(StrategyContext context) {
        AdaptiveTradingPolicy basePolicy = policyStore.currentPolicy();

        MarketFeatureSnapshot snapshot = featureEngine.extract(context);
        ProbabilityPrediction rawPrediction = probabilityModel.predict(snapshot);
        List<StrategyProposal> proposals = proposalGenerator.generate(snapshot);
        StrategyProposal preliminaryBest = bestWeightedProposal(proposals, basePolicy);
        AdaptiveTradingPolicy policy = policyStore.policyFor(snapshot, preliminaryBest.strategyName);
        StrategyProposal best = bestWeightedProposal(proposals, policy);

        double proposalMultiplier = policy.multiplierFor(best.strategyName);
        ProbabilityPrediction prediction = adjustPrediction(rawPrediction, proposalMultiplier);

        boolean passProbability = prediction.getProbabilityHitProfitTarget() >= policy.minProbabilityTarget;
        boolean passExpectedValue = prediction.getExpectedValuePercent() >= policy.minExpectedValuePercent;
        boolean passFeatureProposal = best.rawScore >= policy.minProposalScore;
        boolean passStopRisk = prediction.getProbabilityHitStopLoss() <= policy.maxStopProbability;
        boolean hardPolicyGates = !"false".equalsIgnoreCase(System.getenv().getOrDefault("AI_POLICY_HARD_GATES", "false"));
        boolean advisoryTradable = policy.liveTradingAllowed &&
                passStopRisk &&
                (passProbability || passExpectedValue || passFeatureProposal || best.rawScore >= 0.30);
        boolean tradable = hardPolicyGates
                ? policy.liveTradingAllowed && passProbability && passExpectedValue && passFeatureProposal && passStopRisk
                : advisoryTradable;

        String reason = tradable
                ? "Adaptive model approved: " + best.strategyName +
                " weightedScore=" + best.rawScore +
                " policyMinP=" + policy.minProbabilityTarget +
                " " + prediction
                : "Adaptive model rejected: pTarget=" + prediction.getProbabilityHitProfitTarget() +
                " minP=" + policy.minProbabilityTarget +
                " pStop=" + prediction.getProbabilityHitStopLoss() +
                " maxStop=" + policy.maxStopProbability +
                " ev=" + prediction.getExpectedValuePercent() +
                " minEV=" + policy.minExpectedValuePercent +
                " proposal=" + best.strategyName + ":" + best.rawScore +
                " minProposal=" + policy.minProposalScore +
                " hardPolicyGates=" + hardPolicyGates +
                " multiplier=" + proposalMultiplier +
                " liveAllowed=" + policy.liveTradingAllowed +
                " reason=" + prediction.getReason();

        MarketIntelligenceDecision decision = new MarketIntelligenceDecision(
                snapshot,
                prediction,
                best,
                tradable,
                reason
        );

        if (featureJournal != null) {
            featureJournal.record(snapshot, prediction, best.strategyName, tradable ? "MODEL_BUY_READY" : "MODEL_HOLD");
        }

        return decision;
    }

    private StrategyProposal bestWeightedProposal(List<StrategyProposal> proposals, AdaptiveTradingPolicy policy) {
        if (proposals == null || proposals.isEmpty()) {
            return new StrategyProposal("NO_PROPOSAL", 0.0, "No proposal generated.");
        }

        StrategyProposal best = null;
        double bestScore = -1.0;
        for (StrategyProposal proposal : proposals) {
            if (proposal == null) {
                continue;
            }
            double weighted = safe(proposal.rawScore) * policy.multiplierFor(proposal.strategyName);
            if (weighted > bestScore) {
                bestScore = weighted;
                best = proposal;
            }
        }

        if (best == null) {
            return new StrategyProposal("NO_PROPOSAL", 0.0, "No proposal generated.");
        }

        return new StrategyProposal(
                best.strategyName,
                clamp(bestScore),
                best.reason + " adaptiveMultiplier=" + policy.multiplierFor(best.strategyName)
        );
    }

    private ProbabilityPrediction adjustPrediction(ProbabilityPrediction raw, double proposalMultiplier) {
        if (raw == null) {
            return new ProbabilityPrediction(0.0, 1.0, -100.0, "No prediction.");
        }

        double boundedMultiplier = Math.max(0.50, Math.min(1.50, proposalMultiplier));
        double edgeAdjustment = (boundedMultiplier - 1.0) * 0.06;
        double pTarget = raw.getProbabilityHitProfitTarget() + edgeAdjustment;
        double pStop = raw.getProbabilityHitStopLoss() - edgeAdjustment * 0.50;
        double ev = raw.getExpectedValuePercent() * boundedMultiplier;

        return new ProbabilityPrediction(
                pTarget,
                pStop,
                ev,
                raw.getReason() + " adaptiveMultiplier=" + boundedMultiplier
        );
    }

    private static double safe(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? 0.0 : value;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, safe(value)));
    }
}
