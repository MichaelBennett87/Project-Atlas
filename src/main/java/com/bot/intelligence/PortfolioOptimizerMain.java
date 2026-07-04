package com.bot.intelligence;

public class PortfolioOptimizerMain {
    public static void main(String[] args) {
        System.out.println(new PortfolioOptimizerEngine().optimize().summary());
    }
}
