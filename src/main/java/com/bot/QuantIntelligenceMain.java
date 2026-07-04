package com.bot;

public class QuantIntelligenceMain {
    public static void main(String[] args) throws Exception {
        System.out.println("Quant Intelligence mode enabled.");
        System.out.println("This build adds a feature engine, probability model, feature CSV journal, and MARKET_INTELLIGENCE_AI strategy.");
        System.out.println("Run com.bot.UnifiedStrategyMain for live trading. The master engine now includes MARKET_INTELLIGENCE_AI by default.");
        UnifiedStrategyMain.main(args);
    }
}
