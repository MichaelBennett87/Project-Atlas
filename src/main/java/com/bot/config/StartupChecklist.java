package com.bot.config;

import com.bot.model.AccountService;
import com.bot.model.Position;
import com.bot.risk.MarketHoursService;

import java.util.List;

public class StartupChecklist {

    private final AccountService accountService;
    private final MarketHoursService marketHoursService;
    private final TradingConfig tradingConfig;

    public StartupChecklist(
            AccountService accountService,
            MarketHoursService marketHoursService,
            TradingConfig tradingConfig
    ) {
        this.accountService = accountService;
        this.marketHoursService = marketHoursService;
        this.tradingConfig = tradingConfig;
    }

    public void print(
            List<Position> openPositions
    ) {
        System.out.println();
        System.out.println("========== STARTUP CHECKLIST ==========");

        printCredentials();
        printTradingMode();
        printMarketStatus();
        printAccountStatus(openPositions);
        printRuntimeStatus();

        System.out.println("=======================================");
        System.out.println();
    }

    private void printCredentials() {
        String apiKey =
                System.getenv("ALPACA_API_KEY");

        String secret =
                System.getenv("ALPACA_SECRET_KEY");

        System.out.println(
                "API key present: " +
                        present(apiKey)
        );

        System.out.println(
                "Secret present: " +
                        present(secret)
        );
    }

    private void printTradingMode() {
        String tradingEnabled =
                System.getenv("TRADING_ENABLED");

        String alpacaBaseUrl =
                System.getenv("ALPACA_BASE_URL");

        System.out.println(
                "TRADING_ENABLED: " +
                        tradingEnabled
        );

        System.out.println(
                "Trading enabled parsed: " +
                        tradingConfig.tradingEnabled()
        );

        System.out.println(
                "Alpaca base URL: " +
                        (alpacaBaseUrl == null || alpacaBaseUrl.isBlank()
                                ? "not set"
                                : alpacaBaseUrl)
        );

        if (alpacaBaseUrl != null &&
                alpacaBaseUrl.toLowerCase().contains("paper")) {
            System.out.println("Mode hint: PAPER");
        } else {
            System.out.println("Mode hint: VERIFY PAPER/LIVE URL BEFORE MARKET OPEN");
        }
    }

    private void printMarketStatus() {
        boolean marketOpen =
                marketHoursService.isMarketOpenNow();

        System.out.println(
                "Market open now: " +
                        marketOpen
        );
    }

    private void printAccountStatus(
            List<Position> openPositions
    ) {
        try {
            System.out.println(
                    "Account equity: " +
                            accountService.equity()
            );
        } catch (Exception e) {
            System.out.println(
                    "Account equity: unavailable - " +
                            e.getMessage()
            );
        }

        System.out.println(
                "Open positions synced: " +
                        (openPositions == null ? 0 : openPositions.size())
        );

        if (openPositions != null && !openPositions.isEmpty()) {
            for (Position position : openPositions) {
                System.out.println(
                        "Open position: " +
                                position.ticker
                );
            }
        }
    }

    private void printRuntimeStatus() {
        System.out.println("BOT_KILL_SWITCH: " + System.getenv("BOT_KILL_SWITCH"));
        System.out.println("Kill switch active: " + tradingConfig.killSwitchActive());

        System.out.println("SHORT_STOCK_ENABLED: " + System.getenv("SHORT_STOCK_ENABLED"));
        System.out.println("Short stock trading active: " + tradingConfig.shortStockEnabled());

        System.out.println("News WebSocket: ready to start");
        System.out.println("Price streams: will start when positions/signals require them");
        System.out.println("Options flow: optional confirmation only");
        System.out.println("Put buying: disabled; signal-only architecture");
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}