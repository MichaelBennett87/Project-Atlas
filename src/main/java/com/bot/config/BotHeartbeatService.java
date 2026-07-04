package com.bot.config;

import com.bot.risk.MarketHoursService;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BotHeartbeatService {

    private final MarketHoursService marketHoursService;
    private final ScheduledExecutorService scheduler;

    public BotHeartbeatService() {
        this.marketHoursService = new MarketHoursService();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        scheduler.scheduleAtFixedRate(
                this::printHeartbeat,
                30,
                30,
                TimeUnit.SECONDS
        );
    }

    private void printHeartbeat() {
        try {
            boolean marketOpen =
                    marketHoursService.isMarketOpenNow();

            System.out.println(
                    "BOT HEARTBEAT: running" +
                            " marketOpen=" +
                            marketOpen +
                            " tradingEnabled=" +
                            System.getenv("TRADING_ENABLED") +
                            " mode=" +
                            modeHint() +
                            " time=" +
                            Instant.now()
            );

        } catch (Exception e) {
            System.out.println(
                    "BOT HEARTBEAT: running statusCheckFailed=" +
                            e.getMessage() +
                            " time=" +
                            Instant.now()
            );
        }
    }

    private String modeHint() {
        String baseUrl =
                System.getenv("ALPACA_BASE_URL");

        if (baseUrl == null || baseUrl.isBlank()) {
            return "UNKNOWN";
        }

        if (baseUrl.toLowerCase().contains("paper")) {
            return "PAPER";
        }

        return "VERIFY_LIVE_OR_PAPER";
    }
}