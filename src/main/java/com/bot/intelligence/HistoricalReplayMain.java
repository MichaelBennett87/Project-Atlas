package com.bot.intelligence;

public class HistoricalReplayMain {
    public static void main(String[] args) {
        System.out.println(new HistoricalReplayEngine().runReplay().summary());
    }
}
