package com.bot;

import com.bot.options.OptionsFlowService;

public class OptionsFlowTestMain {

    public static void main(String[] args) {

        OptionsFlowService optionsFlow =
                new OptionsFlowService();

        OptionsFlowService.OptionsFlowDecision decision =
                optionsFlow.check("AAPL");

        System.out.println("========== OPTIONS FLOW TEST ==========");
        System.out.println("Ticker: AAPL");
        System.out.println("Bullish: " + decision.bullish);
        System.out.println("Bearish: " + decision.bearish);
        System.out.println("Reason: " + decision.reason);
        System.out.println("=======================================");
    }
}