package com.bot.agents;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;

import com.bot.intelligence.AgentPerformanceTracker;
import com.bot.scalping.VolumeFirstScalpingPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MultiAgentTradeCommittee {
    private final boolean enabled;
    private final List<TradingAgent> agents;
    private final RiskManagerAgent riskManagerAgent;
    private final MultiAgentDecisionJournal journal;
    private final double minimumApprovalScore;
    private final AgentPerformanceTracker agentPerformanceTracker = AgentPerformanceTracker.getInstance();
    private final int maxBearishVotes;

    public MultiAgentTradeCommittee() {
        this.enabled = !"false".equalsIgnoreCase(System.getenv().getOrDefault("MULTI_AGENT_TRADE_COMMITTEE_ENABLED", "true"));
        this.riskManagerAgent = new RiskManagerAgent();
        this.agents = Arrays.asList(
                new FundamentalAnalystAgent(),
                new NewsSentimentAnalystAgent(),
                new TechnicalAnalystAgent(),
                new MarketRegimeAgent(),
                new WorldModelConsultantAgent(),
                new OpportunityMemoryAgent(),
                new DataSourceCorroborationAgent(),
                new BullResearcherAgent(),
                new BearResearcherAgent(),
                new TraderDecisionAgent(),
                riskManagerAgent
        );
        this.journal = new MultiAgentDecisionJournal();
        this.minimumApprovalScore = envDouble("MULTI_AGENT_MIN_APPROVAL_SCORE", 0.52);
        this.maxBearishVotes = envInt("MULTI_AGENT_MAX_BEARISH_VOTES", 3);
    }

    public MultiAgentTradeDecision review(StrategyContext context, StrategySignal candidate, List<StrategySignal> allSignals) {
        if (!enabled) {
            MultiAgentTradeDecision disabled = MultiAgentTradeDecision.approved(
                    candidate == null ? 0 : candidate.getSuggestedQuantity(),
                    1.0,
                    "Multi-agent committee disabled by configuration.",
                    new ArrayList<>()
            );
            journal.record(context, candidate, disabled);
            return disabled;
        }

        List<AgentOpinion> opinions = new ArrayList<>();
        for (TradingAgent agent : agents) {
            try {
                AgentOpinion opinion = agent.evaluate(context, candidate, allSignals);
                if (opinion != null) opinions.add(agentPerformanceTracker.calibrate(opinion));
            } catch (Exception e) {
                if (agent == riskManagerAgent) {
                    opinions.add(AgentOpinion.of(agent.name(), AgentVote.VETOED, 1.0,
                            "Risk manager exception treated as safety veto: " + e.getMessage()));
                } else {
                    opinions.add(AgentOpinion.of(agent.name(), AgentVote.NEUTRAL, 0.35,
                            "Non-risk agent exception ignored for tomorrow trade continuity: " + e.getMessage()));
                }
            }
        }

        if (candidate != null && VolumeFirstScalpingPolicy.isTopVolumeFastLane(candidate)) {
            VolumeFirstScalpingPolicy.ScalpingTape tape = VolumeFirstScalpingPolicy.tape(context);
            if ((tape.topVolume || tape.dollarVolume >= envDouble("COMMITTEE_VOLUME_FAST_LANE_MIN_DOLLAR_VOLUME", 50_000.0))
                    && tape.violentScore >= envDouble("COMMITTEE_VOLUME_FAST_LANE_MIN_VIOLENT_SCORE", 0.16)) {
                MultiAgentTradeDecision approved = MultiAgentTradeDecision.approved(
                        Math.max(1, candidate.getSuggestedQuantity()),
                        1.0,
                        "Volume-first scalping fast lane: committee cannot veto a liquid violent top-volume setup. " + VolumeFirstScalpingPolicy.diagnostics(context),
                        opinions
                );
                journal.record(context, candidate, approved);
                agentPerformanceTracker.recordCommitteeDecision(context, candidate, opinions, approved, 1.0);
                System.out.println("MULTI AGENT COMMITTEE VOLUME-FIRST APPROVED: " + approved.compactSummary());
                return approved;
            }
        }

        AgentOpinion riskOpinion = findOpinion(opinions, riskManagerAgent.name());
        if (riskOpinion == null || riskOpinion.getVote() == AgentVote.VETOED) {
            MultiAgentTradeDecision veto = MultiAgentTradeDecision.vetoed(
                    riskOpinion == null ? "Risk manager did not produce approval." : riskOpinion.getReason(),
                    opinions
            );
            journal.record(context, candidate, veto);
            agentPerformanceTracker.recordCommitteeDecision(context, candidate, opinions, veto, 0.0);
            System.out.println("MULTI AGENT COMMITTEE VETO: " + veto.compactSummary());
            return veto;
        }

        int bearishVotes = 0;
        double weightedScore = 0.0;
        double totalWeight = 0.0;
        for (AgentOpinion opinion : opinions) {
            if (opinion == null) continue;
            if (opinion.isBearish()) bearishVotes++;
            double reliabilityWeight = agentPerformanceTracker.weightFor(opinion.getAgentName());
            double weight = Math.max(0.15, opinion.getConfidence()) * Math.max(0.25, reliabilityWeight);
            weightedScore += scoreVote(opinion) * weight;
            totalWeight += weight;
        }
        double approvalScore = totalWeight <= 0.0 ? 0.0 : weightedScore / totalWeight;
        if (bearishVotes > maxBearishVotes) {
            MultiAgentTradeDecision veto = MultiAgentTradeDecision.vetoed(
                    "Too many bearish/veto agent opinions: " + bearishVotes + " max=" + maxBearishVotes,
                    opinions
            );
            journal.record(context, candidate, veto);
            agentPerformanceTracker.recordCommitteeDecision(context, candidate, opinions, veto, approvalScore);
            System.out.println("MULTI AGENT COMMITTEE VETO: " + veto.compactSummary());
            return veto;
        }
        if (approvalScore < minimumApprovalScore) {
            MultiAgentTradeDecision veto = MultiAgentTradeDecision.vetoed(
                    "Committee approval score below threshold: " + String.format("%.3f", approvalScore) + " min=" + minimumApprovalScore,
                    opinions
            );
            journal.record(context, candidate, veto);
            agentPerformanceTracker.recordCommitteeDecision(context, candidate, opinions, veto, approvalScore);
            System.out.println("MULTI AGENT COMMITTEE VETO: " + veto.compactSummary());
            return veto;
        }

        int approvedQuantity = riskManagerAgent.approvedQuantity(candidate, opinions);
        double multiplier = approvalScore >= 0.78 ? 1.0 : approvalScore >= 0.64 ? 0.90 : 0.80;
        MultiAgentTradeDecision approved = MultiAgentTradeDecision.approved(
                approvedQuantity,
                multiplier,
                "Committee approved autonomous trade. approvalScore=" + String.format("%.3f", approvalScore),
                opinions
        );
        journal.record(context, candidate, approved);
        agentPerformanceTracker.recordCommitteeDecision(context, candidate, opinions, approved, approvalScore);
        System.out.println("MULTI AGENT COMMITTEE APPROVED: " + approved.compactSummary());
        return approved;
    }

    private static AgentOpinion findOpinion(List<AgentOpinion> opinions, String agentName) {
        if (opinions == null || agentName == null) return null;
        for (AgentOpinion opinion : opinions) {
            if (opinion != null && agentName.equalsIgnoreCase(opinion.getAgentName())) return opinion;
        }
        return null;
    }

    private static double scoreVote(AgentOpinion opinion) {
        if (opinion == null || opinion.getVote() == null) return 0.50;
        switch (opinion.getVote()) {
            case STRONG_BULLISH: return 1.00;
            case BULLISH: return 0.75;
            case APPROVED: return 0.72;
            case REDUCED: return 0.58;
            case NEUTRAL: return 0.50;
            case BEARISH: return 0.25;
            case STRONG_BEARISH:
            case VETOED:
                return 0.0;
            default: return 0.50;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
