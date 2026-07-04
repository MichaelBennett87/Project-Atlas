package com.bot.intelligence;

/**
 * Offline one-shot trainer/tuner. Run this after paper trading sessions to update
 * logs/ai_policy.properties from logs/trade_outcomes.csv and logs/market_features.csv.
 *
 * The live runner also starts SelfTrainingOptimizer when AI_SELF_TRAINING_ENABLED=true.
 *
 * For autonomous bounded source rewriting after the bot is stopped, run:
 *   com.bot.intelligence.AutonomousCodeEvolutionMain
 */
public class AutoTuningOptimizerMain {
    public static void main(String[] args) {
        SelfTrainingOptimizer optimizer = new SelfTrainingOptimizer();
        SelfTrainingOptimizer.OptimizationSummary summary = optimizer.runOnce();
        System.out.println("AI AUTO TUNING COMPLETE: closedTrades=" + summary.closedTrades +
                " totalPnl=" + summary.totalPnl +
                " winRate=" + summary.winRate +
                " minP=" + summary.policy.minProbabilityTarget +
                " minEV=" + summary.policy.minExpectedValuePercent +
                " risk=" + summary.policy.riskFractionPerTrade);
    }
}
