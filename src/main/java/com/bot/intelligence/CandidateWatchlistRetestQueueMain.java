package com.bot.intelligence;

public final class CandidateWatchlistRetestQueueMain {
    private CandidateWatchlistRetestQueueMain() {
    }

    public static void main(String[] args) {
        System.out.println("CANDIDATE WATCHLIST RETEST QUEUE STARTED");
        CandidateWatchlistRetestQueue.Result result = new CandidateWatchlistRetestQueue().run();
        System.out.println("CANDIDATE WATCHLIST RETEST QUEUE COMPLETE: " + result.summary());
    }
}
