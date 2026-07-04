package com.bot.intelligence;

public final class PreTradeCalibrationAuditMain {
    private PreTradeCalibrationAuditMain() {
    }

    public static void main(String[] args) {
        System.out.println("PRE-TRADE CALIBRATION AUDIT STARTED");
        PreTradeCalibrationAuditEngine.Result result = new PreTradeCalibrationAuditEngine().run();
        System.out.println("PRE-TRADE CALIBRATION AUDIT COMPLETE: " + result.summary());
    }
}
