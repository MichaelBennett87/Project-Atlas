package com.bot.intelligence;

public final class PreTradeCalibrationMain {
    private PreTradeCalibrationMain() {
    }

    public static void main(String[] args) {
        System.out.println("PRE-TRADE CALIBRATION STARTED");
        PreTradeCalibrationLearningEngine.Result result = new PreTradeCalibrationLearningEngine().run();
        System.out.println("PRE-TRADE CALIBRATION COMPLETE: " + result.summary());
    }
}
