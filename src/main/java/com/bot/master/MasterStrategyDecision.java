package com.bot.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MasterStrategyDecision {

    private final StrategyAction action;
    private final String ticker;
    private final StrategySignal winningSignal;
    private final List<StrategySignal> allSignals;
    private final String reason;

    public MasterStrategyDecision(
            StrategyAction action,
            String ticker,
            StrategySignal winningSignal,
            List<StrategySignal> allSignals,
            String reason
    ) {
        this.action = action == null ? StrategyAction.HOLD : action;
        this.ticker = ticker == null ? "" : ticker.trim().toUpperCase();
        this.winningSignal = winningSignal;
        this.allSignals = allSignals == null ? new ArrayList<>() : new ArrayList<>(allSignals);
        this.reason = reason == null ? "" : reason;
    }

    public static MasterStrategyDecision hold(String ticker, List<StrategySignal> signals, String reason) {
        return new MasterStrategyDecision(StrategyAction.HOLD, ticker, null, signals, reason);
    }

    public static MasterStrategyDecision buy(String ticker, StrategySignal winner, List<StrategySignal> signals, String reason) {
        return new MasterStrategyDecision(StrategyAction.BUY, ticker, winner, signals, reason);
    }

    public StrategyAction getAction() { return action; }
    public String getTicker() { return ticker; }
    public StrategySignal getWinningSignal() { return winningSignal; }
    public List<StrategySignal> getAllSignals() { return Collections.unmodifiableList(allSignals); }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return "MasterStrategyDecision{" +
                "action=" + action +
                ", ticker='" + ticker + '\'' +
                ", winningSignal=" + winningSignal +
                ", reason='" + reason + '\'' +
                ", signalCount=" + allSignals.size() +
                '}';
    }
}
