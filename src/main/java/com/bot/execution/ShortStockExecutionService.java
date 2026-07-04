package com.bot.execution;

public class ShortStockExecutionService {

    private final OrderExecutor orderExecutor;

    public ShortStockExecutionService(
            OrderExecutor orderExecutor
    ) {
        this.orderExecutor = orderExecutor;
    }

    public boolean shortStock(
            String ticker,
            int qty
    ) {
        System.out.println(
                "SHORT STOCK ORDER REQUESTED: " +
                        ticker +
                        " qty=" +
                        qty
        );

        return orderExecutor.shortMarketAndWaitForFill(
                ticker,
                qty
        );
    }

    public boolean coverShort(
            String ticker,
            int qty
    ) {
        System.out.println(
                "COVER SHORT ORDER REQUESTED: " +
                        ticker +
                        " qty=" +
                        qty
        );

        return orderExecutor.coverShortAndWaitForFill(
                ticker,
                qty
        );
    }
}