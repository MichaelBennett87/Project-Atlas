package com.bot.model;

public class PerformanceStats {

    public final int totalSignals;
    public final int closedSignals;
    public final double winRate;
    public final double averageMaxGain;
    public final double averageExitGain;
    public final double averageDrawdown;
    public final double averageGainAfter5Minutes;
    public final double averageGainAfter15Minutes;
    public final double averageGainAfter30Minutes;
    public final double averageGainAfter60Minutes;
    public final double averageRelativeVolume;
    public final double averageGapPercent;

    public PerformanceStats(
            int totalSignals,
            int closedSignals,
            double winRate,
            double averageMaxGain,
            double averageExitGain,
            double averageDrawdown
    ) {
        this(
                totalSignals,
                closedSignals,
                winRate,
                averageMaxGain,
                averageExitGain,
                averageDrawdown,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0
        );
    }

    public PerformanceStats(
            int totalSignals,
            int closedSignals,
            double winRate,
            double averageMaxGain,
            double averageExitGain,
            double averageDrawdown,
            double averageGainAfter5Minutes,
            double averageGainAfter15Minutes,
            double averageGainAfter30Minutes,
            double averageGainAfter60Minutes,
            double averageRelativeVolume,
            double averageGapPercent
    ) {
        this.totalSignals = totalSignals;
        this.closedSignals = closedSignals;
        this.winRate = winRate;
        this.averageMaxGain = averageMaxGain;
        this.averageExitGain = averageExitGain;
        this.averageDrawdown = averageDrawdown;
        this.averageGainAfter5Minutes = averageGainAfter5Minutes;
        this.averageGainAfter15Minutes = averageGainAfter15Minutes;
        this.averageGainAfter30Minutes = averageGainAfter30Minutes;
        this.averageGainAfter60Minutes = averageGainAfter60Minutes;
        this.averageRelativeVolume = averageRelativeVolume;
        this.averageGapPercent = averageGapPercent;
    }

    @Override
    public String toString() {
        return "PerformanceStats{" +
                "totalSignals=" + totalSignals +
                ", closedSignals=" + closedSignals +
                ", winRate=" + winRate +
                ", averageMaxGain=" + averageMaxGain +
                ", averageExitGain=" + averageExitGain +
                ", averageDrawdown=" + averageDrawdown +
                ", averageGainAfter5Minutes=" + averageGainAfter5Minutes +
                ", averageGainAfter15Minutes=" + averageGainAfter15Minutes +
                ", averageGainAfter30Minutes=" + averageGainAfter30Minutes +
                ", averageGainAfter60Minutes=" + averageGainAfter60Minutes +
                ", averageRelativeVolume=" + averageRelativeVolume +
                ", averageGapPercent=" + averageGapPercent +
                '}';
    }
}