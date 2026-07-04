package com.bot.model;

public class CatalystQualityDecision {

    public final boolean passed;
    public final String reason;

    public CatalystQualityDecision(
            boolean passed,
            String reason
    ) {
        this.passed = passed;
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "CatalystQualityDecision{" +
                "passed=" + passed +
                ", reason='" + reason + '\'' +
                '}';
    }
}