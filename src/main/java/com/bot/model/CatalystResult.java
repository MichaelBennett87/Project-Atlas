package com.bot.model;

public class CatalystResult {

    public final CatalystType type;
    public final double weight;
    public final String reason;

    public CatalystResult(
            CatalystType type,
            double weight,
            String reason
    ) {
        this.type = type;
        this.weight = weight;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "CatalystResult{" +
                "type=" + type +
                ", weight=" + weight +
                ", reason='" + reason + '\'' +
                '}';
    }
}