package com.bot.model;

public class OrderStatus {

    private final String orderId;
    private final String status;
    private final boolean filled;

    public OrderStatus(String orderId, String status, boolean filled) {
        this.orderId = orderId;
        this.status = status;
        this.filled = filled;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getStatus() {
        return status;
    }

    public boolean isFilled() {
        return filled;
    }

    @Override
    public String toString() {
        return "OrderStatus{" +
                "orderId='" + orderId + '\'' +
                ", status='" + status + '\'' +
                ", filled=" + filled +
                '}';
    }
}