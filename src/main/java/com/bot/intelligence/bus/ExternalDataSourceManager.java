package com.bot.intelligence.bus;

import java.util.ArrayList;
import java.util.List;

public class ExternalDataSourceManager {
    private final List<MarketDataProvider> providers = new ArrayList<>();
    private volatile boolean started = false;
    private ActiveRestResearchScheduler activeRestResearchScheduler;
    private PreCatalystElevationService preCatalystElevationService;
    private MarketCollectorCluster marketCollectorCluster;

    public ExternalDataSourceManager() {
        providers.add(new PolygonMarketDataProvider());

        // Optional generic Polygon-compatible endpoint fallback. Leave disabled by default
        // because PolygonMarketDataProvider above is the native adapter that uses POLYGON_API_KEY.
        if (!env("POLYGON_SIGNAL_URL", "").isBlank()) {
            providers.add(new GenericRestMarketDataProvider(
                    "POLYGON_GENERIC",
                    env("POLYGON_SIGNAL_URL", ""),
                    env("POLYGON_API_KEY", ""),
                    envLong("POLYGON_SIGNAL_POLL_MS", 15_000L),
                    MarketIntelligenceSignalType.MARKET_DATA
            ));
        }
        providers.add(new GenericRestMarketDataProvider(
                "FINNHUB",
                env("FINNHUB_SIGNAL_URL", ""),
                env("FINNHUB_API_KEY", ""),
                envLong("FINNHUB_SIGNAL_POLL_MS", 30_000L),
                MarketIntelligenceSignalType.CATALYST_CALENDAR
        ));
        providers.add(new AlphaVantageMarketDataProvider());

        // Optional generic Alpha Vantage-compatible endpoint fallback. Leave disabled by default
        // because AlphaVantageMarketDataProvider above is the native adapter that uses ALPHA_VANTAGE_API_KEY.
        if (!env("ALPHA_VANTAGE_SIGNAL_URL", "").isBlank()) {
            providers.add(new GenericRestMarketDataProvider(
                    "ALPHA_VANTAGE_GENERIC",
                    env("ALPHA_VANTAGE_SIGNAL_URL", ""),
                    env("ALPHA_VANTAGE_API_KEY", ""),
                    envLong("ALPHA_VANTAGE_SIGNAL_POLL_MS", 300_000L),
                    MarketIntelligenceSignalType.TECHNICAL_INDICATOR
            ));
        }
        providers.add(new GenericRestMarketDataProvider(
                "FINANCIAL_MODELING_PREP",
                env("FMP_SIGNAL_URL", ""),
                env("FMP_API_KEY", ""),
                envLong("FMP_SIGNAL_POLL_MS", 60_000L),
                MarketIntelligenceSignalType.FUNDAMENTAL
        ));
        providers.add(new GenericRestMarketDataProvider(
                "TRADESALGO",
                env("TRADESALGO_SIGNAL_URL", ""),
                env("TRADESALGO_API_KEY", ""),
                envLong("TRADESALGO_SIGNAL_POLL_MS", 10_000L),
                MarketIntelligenceSignalType.ORDER_FLOW
        ));
        providers.add(new GenericRestMarketDataProvider(
                "YAHOO_FINANCE",
                env("YAHOO_FINANCE_SIGNAL_URL", ""),
                env("YAHOO_FINANCE_API_KEY", ""),
                envLong("YAHOO_FINANCE_SIGNAL_POLL_MS", 60_000L),
                MarketIntelligenceSignalType.MARKET_DATA
        ));
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        boolean enabled = !"false".equalsIgnoreCase(env("EXTERNAL_MARKET_DATA_SOURCES_ENABLED", "true"));
        if (!enabled) {
            System.out.println("EXTERNAL MARKET DATA SOURCES DISABLED: EXTERNAL_MARKET_DATA_SOURCES_ENABLED=false");
            return;
        }
        MarketIntelligenceBus bus = MarketIntelligenceBus.getInstance();
        marketCollectorCluster = new MarketCollectorCluster();
        marketCollectorCluster.start();
        int startedCount = 0;
        for (MarketDataProvider provider : providers) {
            if (provider.enabled()) {
                provider.start(bus::publishSignal);
                startedCount++;
            } else {
                bus.recordProviderStatus(provider.name(), "DISABLED_NO_URL");
            }
        }
        activeRestResearchScheduler = new ActiveRestResearchScheduler();
        activeRestResearchScheduler.start(bus::publishSignal);
        preCatalystElevationService = new PreCatalystElevationService();
        preCatalystElevationService.start();
        System.out.println("EXTERNAL MARKET DATA SOURCE MANAGER READY: configuredProviders=" + providers.size() +
                " activeProviders=" + startedCount +
                " polygonNative=" + (!env("POLYGON_API_KEY", "").isBlank()) +
                " alphaVantageNative=" + (!env("ALPHA_VANTAGE_API_KEY", "").isBlank()) +
                " note=Set POLYGON_API_KEY and ALPHA_VANTAGE_API_KEY for native providers. Set *_SIGNAL_URL env vars for generic Finnhub/FMP/TradesAlgo/Yahoo adapters.");
    }

    public synchronized void stop() {
        if (activeRestResearchScheduler != null) {
            try { activeRestResearchScheduler.stop(); } catch (Exception ignored) {}
        }
        if (preCatalystElevationService != null) {
            try { preCatalystElevationService.stop(); } catch (Exception ignored) {}
        }
        if (marketCollectorCluster != null) {
            try { marketCollectorCluster.stop(); } catch (Exception ignored) {}
        }
        for (MarketDataProvider provider : providers) {
            try {
                provider.stop();
            } catch (Exception ignored) {
            }
        }
        started = false;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
