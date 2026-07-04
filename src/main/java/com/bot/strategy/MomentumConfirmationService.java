package com.bot.strategy;

import com.bot.broker.AlpacaBroker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MomentumConfirmationService {

    private static final long WAIT_MS = 10_000;
    private static final double REQUIRED_UP_MOVE = 0.003; // +0.30%
    private static final double STRONG_UP_MOVE = 0.01;    // +1.00%

    private static final String DATA_BASE_URL =
            "https://data.alpaca.markets";

    private final AlpacaBroker broker;
    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public MomentumConfirmationService(
            AlpacaBroker broker
    ) {
        this.broker = broker;
        this.client = new OkHttpClient();
        this.mapper = new ObjectMapper();
    }

    public boolean confirm(String ticker) {

        try {
            double startPrice =
                    broker.getPrice(ticker);

            long startVolume =
                    fetchLatestVolume(ticker);

            System.out.println(
                    "MOMENTUM V2 START: " +
                            ticker +
                            " price=" +
                            startPrice +
                            " volume=" +
                            startVolume
            );

            Thread.sleep(WAIT_MS);

            double currentPrice =
                    broker.getPrice(ticker);

            long currentVolume =
                    fetchLatestVolume(ticker);

            double pctMove =
                    (currentPrice - startPrice) / startPrice;

            boolean priceConfirmed =
                    pctMove >= REQUIRED_UP_MOVE;

            boolean strongPriceConfirmed =
                    pctMove >= STRONG_UP_MOVE;

            boolean volumeConfirmed =
                    startVolume > 0 &&
                            currentVolume > startVolume;

            System.out.println(
                    "MOMENTUM V2 CHECK: " +
                            ticker +
                            " start=" +
                            startPrice +
                            " current=" +
                            currentPrice +
                            " move=" +
                            (pctMove * 100.0) +
                            "%" +
                            " startVolume=" +
                            startVolume +
                            " currentVolume=" +
                            currentVolume +
                            " priceConfirmed=" +
                            priceConfirmed +
                            " volumeConfirmed=" +
                            volumeConfirmed
            );

            if (strongPriceConfirmed) {
                System.out.println(
                        "MOMENTUM V2 CONFIRMED: strong price move"
                );
                return true;
            }

            if (priceConfirmed && volumeConfirmed) {
                System.out.println(
                        "MOMENTUM V2 CONFIRMED: price move + volume increase"
                );
                return true;
            }

            if (priceConfirmed) {
                System.out.println(
                        "MOMENTUM V2 CONFIRMED: price move only"
                );
                return true;
            }

            System.out.println(
                    "MOMENTUM V2 REJECTED: upward momentum not confirmed"
            );

            return false;

        } catch (Exception e) {
            System.err.println(
                    "Momentum V2 confirmation failed: " +
                            e.getMessage()
            );

            return false;
        }
    }

    private long fetchLatestVolume(String ticker) {

        String apiKey =
                System.getenv("ALPACA_API_KEY");

        String secret =
                System.getenv("ALPACA_SECRET_KEY");

        String feed =
                System.getenv("ALPACA_STOCK_DATA_FEED");

        if (feed == null || feed.isBlank()) {
            feed = "iex";
        }

        if (apiKey == null || apiKey.isBlank() ||
                secret == null || secret.isBlank()) {
            return -1;
        }

        try {
            HttpUrl url =
                    HttpUrl.parse(
                                    DATA_BASE_URL +
                                            "/v2/stocks/bars/latest"
                            )
                            .newBuilder()
                            .addQueryParameter("symbols", ticker)
                            .addQueryParameter("feed", feed)
                            .build();

            Request request =
                    new Request.Builder()
                            .url(url)
                            .addHeader("APCA-API-KEY-ID", apiKey)
                            .addHeader("APCA-API-SECRET-KEY", secret)
                            .addHeader("accept", "application/json")
                            .get()
                            .build();

            try (Response response = client.newCall(request).execute()) {
                String body =
                        response.body() == null
                                ? ""
                                : response.body().string();

                if (!response.isSuccessful()) {
                    System.out.println(
                            "MOMENTUM V2 VOLUME UNAVAILABLE: HTTP " +
                                    response.code() +
                                    " body=" +
                                    body
                    );
                    return -1;
                }

                JsonNode root =
                        mapper.readTree(body);

                JsonNode bar =
                        root.path("bars").path(ticker);

                if (bar.isMissingNode() || bar.isNull()) {
                    return -1;
                }

                return bar.path("v").asLong(-1);
            }

        } catch (Exception e) {
            System.out.println(
                    "MOMENTUM V2 VOLUME UNAVAILABLE: " +
                            e.getMessage()
            );

            return -1;
        }
    }
}