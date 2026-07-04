package com.bot.agents;

import com.bot.intelligence.ExecutionAnalyticsService;

public class ExecutionQualityAgent {
    public void record(String ticker, String side, int plannedQuantity, boolean filled, long latencyMs) {
        record(ticker, "UNKNOWN_STRATEGY", side, plannedQuantity, plannedQuantity, 0.0, 0.0, filled, latencyMs, "legacy_record_call");
    }

    public void record(
            String ticker,
            String strategy,
            String side,
            int requestedQuantity,
            int finalQuantity,
            double referencePrice,
            double observedPrice,
            boolean filled,
            long latencyMs,
            String reason
    ) {
        ExecutionAnalyticsService.getInstance().record(
                ticker,
                strategy,
                side,
                requestedQuantity,
                finalQuantity,
                referencePrice,
                observedPrice,
                filled,
                latencyMs,
                reason
        );
        if (!"true".equalsIgnoreCase(System.getenv().getOrDefault("MULTI_AGENT_EXECUTION_QUALITY_LOGS", "true"))) {
            return;
        }
        System.out.println("EXECUTION QUALITY AGENT: ticker=" + ticker +
                " strategy=" + strategy +
                " side=" + side +
                " requestedQty=" + requestedQuantity +
                " finalQty=" + finalQuantity +
                " filled=" + filled +
                " latencyMs=" + latencyMs +
                " refPrice=" + referencePrice +
                " observedPrice=" + observedPrice +
                " reason=" + reason);
    }
}
