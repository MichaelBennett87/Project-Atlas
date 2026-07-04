package com.bot.intelligence.historical;

import com.bot.intelligence.HistoricalReplayEngine;
import com.bot.intelligence.OfflineReplayClusterMain;

/** Runs the local replay/simulation steps after fresh historical REST pulls. */
public final class NightlySimulationRunner {
    public Result run() {
        long started = System.currentTimeMillis();
        TrainingDatasetBuilder.Result dataset = new TrainingDatasetBuilder().build();
        try {
            OfflineReplayClusterMain.main(new String[0]);
        } catch (Exception e) {
            System.out.println("NIGHTLY OFFLINE REPLAY CLUSTER WARNING: " + e.getMessage());
        }
        HistoricalReplayEngine.ReplayResult replay = new HistoricalReplayEngine().runReplay();
        return new Result(dataset, replay.summary(), System.currentTimeMillis() - started);
    }

    public static final class Result {
        public final TrainingDatasetBuilder.Result dataset;
        public final String replaySummary;
        public final long elapsedMs;
        Result(TrainingDatasetBuilder.Result dataset, String replaySummary, long elapsedMs) { this.dataset = dataset; this.replaySummary = replaySummary; this.elapsedMs = elapsedMs; }
        public String summary() { return "dataset={" + dataset.summary() + "} replay=" + replaySummary + " elapsedMs=" + elapsedMs; }
    }
}
