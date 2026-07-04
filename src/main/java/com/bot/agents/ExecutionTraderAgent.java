package com.bot.agents;

import com.bot.master.StrategySignal;

public class ExecutionTraderAgent {
    public ExecutionPlan plan(StrategySignal signal, MultiAgentTradeDecision committeeDecision) {
        if (signal == null || committeeDecision == null || !committeeDecision.isApproved()) {
            return ExecutionPlan.blocked("Execution trader blocked: no approved committee decision.");
        }
        int qty = committeeDecision.getApprovedQuantity() > 0
                ? committeeDecision.getApprovedQuantity()
                : signal.getSuggestedQuantity();
        if (qty <= 0) {
            return ExecutionPlan.blocked("Execution trader blocked: approved quantity is zero.");
        }
        return ExecutionPlan.approved(
                signal.getTicker(),
                signal.getDirection() == null ? "BUY" : signal.getDirection().name(),
                qty,
                "Execution trader approved order after committee/risk approval. " + committeeDecision.getReason()
        );
    }
}
