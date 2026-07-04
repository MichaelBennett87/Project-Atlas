package com.bot.agents;

public class ExecutionPlan {
    private final boolean approved;
    private final String ticker;
    private final String side;
    private final int quantity;
    private final String reason;

    private ExecutionPlan(boolean approved, String ticker, String side, int quantity, String reason) {
        this.approved = approved;
        this.ticker = ticker == null ? "" : ticker.trim().toUpperCase();
        this.side = side == null ? "" : side.trim().toUpperCase();
        this.quantity = Math.max(0, quantity);
        this.reason = reason == null ? "" : reason;
    }

    public static ExecutionPlan approved(String ticker, String side, int quantity, String reason) {
        return new ExecutionPlan(true, ticker, side, quantity, reason);
    }

    public static ExecutionPlan blocked(String reason) {
        return new ExecutionPlan(false, "", "", 0, reason);
    }

    public boolean isApproved() { return approved; }
    public String getTicker() { return ticker; }
    public String getSide() { return side; }
    public int getQuantity() { return quantity; }
    public String getReason() { return reason; }
}
